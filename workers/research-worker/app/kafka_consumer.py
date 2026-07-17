from __future__ import annotations

import json
import time
from typing import Any, Iterable, Protocol

from pydantic import BaseModel

from app.callback import (
    ResearchCallbackClient,
    ResearchWorkerExecutionResponse,
    run_research_task_with_callbacks,
)
from app.config import load_settings
from app.trace_security import sanitize_error_message


class KafkaConsumeSummary(BaseModel):
    consumed_count: int
    completed_count: int
    failed_count: int
    skipped_count: int = 0
    failed_message_count: int = 0
    failed_attempt_count: int = 0
    dead_letter_count: int = 0


class DeadLetterSink(Protocol):
    def publish(self, record: dict[str, object]) -> bool:
        ...


class DeadLetterPublishError(RuntimeError):
    """Raised to stop consumption before an unacknowledged poison offset is crossed."""


class InMemoryDeadLetterSink:
    """Test/local sink only. Production wiring must use KafkaDeadLetterSink."""
    def __init__(self) -> None:
        self.records: list[dict[str, object]] = []

    def publish(self, record: dict[str, object]) -> bool:
        self.records.append(record)
        return True


class KafkaDeadLetterSink:
    """Durable DLQ publisher that confirms broker acknowledgement before success."""

    def __init__(self, producer: object, topic: str, timeout_seconds: float = 10.0) -> None:
        if not topic.strip():
            raise ValueError("Kafka DLQ topic must not be empty")
        self.producer = producer
        self.topic = topic
        self.timeout_seconds = timeout_seconds

    def publish(self, record: dict[str, object]) -> bool:
        try:
            future = self.producer.send(self.topic, value=record)
            get = getattr(future, "get", None)
            if callable(get):
                get(timeout=self.timeout_seconds)
            flush = getattr(self.producer, "flush", None)
            if callable(flush):
                flush(timeout=self.timeout_seconds)
            return True
        except Exception:
            return False


def handle_research_run_message(
    payload_json: str,
    client: ResearchCallbackClient | None = None,
) -> ResearchWorkerExecutionResponse:
    payload = json.loads(payload_json)
    task_id = _read_task_id(payload)
    return run_research_task_with_callbacks(task_id, client)


def consume_research_run_messages(
    messages: Iterable[object],
    client: ResearchCallbackClient | None = None,
    max_messages: int | None = None,
    max_attempts: int = 3,
    raise_on_failure: bool = False,
    dead_letter_sink: DeadLetterSink | None = None,
    backoff_base_seconds: float = 0.0,
) -> KafkaConsumeSummary:
    consumed_count = 0
    completed_count = 0
    failed_count = 0
    skipped_count = 0
    failed_message_count = 0
    dead_letter_count = 0
    dlq = dead_letter_sink or InMemoryDeadLetterSink()
    attempts = max(1, max_attempts)
    for message in messages:
        if max_messages is not None and consumed_count >= max_messages:
            break
        consumed_count += 1
        try:
            message_value = _message_value(message)
        except Exception as exc:
            failed_count += 1
            skipped_count += 1
            failed_message_count += 1
            raw_value = getattr(message, "value", message)
            dlq_record = {
                "original_message": repr(raw_value)[:4000],
                "error_type": type(exc).__name__,
                "error_message": sanitize_error_message(exc),
                "attempt_count": 1,
                "retryable": False,
            }
            if not dlq.publish(dlq_record):
                raise DeadLetterPublishError(
                    "Kafka dead-letter publish failed for undecodable message; offset not committed"
                ) from exc
            dead_letter_count += 1
            _commit_if_supported(messages)
            if raise_on_failure:
                raise
            continue
        completed = False
        last_error: Exception | None = None
        attempt_count = 0
        for attempt_index in range(attempts):
            attempt_count += 1
            try:
                handle_research_run_message(message_value, client)
                completed = True
                break
            except Exception as exc:
                failed_count += 1
                last_error = exc
                if not _is_retryable(exc):
                    break
                if attempt_index + 1 < attempts and backoff_base_seconds > 0:
                    time.sleep(min(backoff_base_seconds * (2 ** attempt_index), 5.0))
        if completed:
            completed_count += 1
        else:
            skipped_count += 1
            failed_message_count += 1
            dlq_record = {
                "original_message": message_value,
                "error_type": type(last_error).__name__ if last_error is not None else "UNKNOWN",
                "error_message": sanitize_error_message(last_error or "unknown consumer failure"),
                "attempt_count": attempt_count,
                "retryable": _is_retryable(last_error) if last_error is not None else False,
            }
            if not dlq.publish(dlq_record):
                raise DeadLetterPublishError(
                    "Kafka dead-letter publish failed; consumption stopped before offset commit"
                ) from last_error
            dead_letter_count += 1
        _commit_if_supported(messages)
        if last_error is not None and not completed and raise_on_failure:
            raise last_error
    return KafkaConsumeSummary(
        consumed_count=consumed_count,
        completed_count=completed_count,
        failed_count=failed_count,
        skipped_count=skipped_count,
        failed_message_count=failed_message_count,
        failed_attempt_count=failed_count,
        dead_letter_count=dead_letter_count,
    )


def _is_retryable(exc: Exception) -> bool:
    message = str(exc).lower()
    if "missing task_id" in message or isinstance(exc, (json.JSONDecodeError, UnicodeDecodeError)):
        return False
    return isinstance(exc, (TimeoutError, ConnectionError, OSError, RuntimeError))


def create_research_kafka_consumer() -> object:
    settings = load_settings()
    try:
        from kafka import KafkaConsumer
    except ImportError as exc:
        raise RuntimeError(
            "kafka-python is required for Kafka consumption. "
            "Run workers/scripts/setup-workers-conda.ps1 -Update first."
        ) from exc
    return KafkaConsumer(
        settings.kafka_research_topic,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        group_id=settings.kafka_group_id,
        enable_auto_commit=False,
        auto_offset_reset="earliest",
    )


def create_research_kafka_dead_letter_sink() -> KafkaDeadLetterSink:
    settings = load_settings()
    try:
        from kafka import KafkaProducer
    except ImportError as exc:
        raise RuntimeError(
            "kafka-python is required for Kafka dead-letter publishing. "
            "Run workers/scripts/setup-workers-conda.ps1 -Update first."
        ) from exc
    producer = KafkaProducer(
        bootstrap_servers=settings.kafka_bootstrap_servers,
        acks="all",
        retries=max(1, settings.kafka_consume_max_attempts),
        value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
    )
    return KafkaDeadLetterSink(producer, settings.kafka_research_dlq_topic)


def run_research_kafka_consumer_forever() -> None:
    settings = load_settings()
    consumer = create_research_kafka_consumer()
    dead_letter_sink = create_research_kafka_dead_letter_sink()
    consume_research_run_messages(
        consumer,
        max_attempts=settings.kafka_consume_max_attempts,
        raise_on_failure=settings.kafka_consume_raise_on_failure,
        dead_letter_sink=dead_letter_sink,
    )


def _read_task_id(payload: dict[str, Any]) -> str:
    task_id = payload.get("task_id") or payload.get("taskId")
    if not isinstance(task_id, str) or not task_id.strip():
        raise RuntimeError("Kafka research message missing task_id")
    return task_id.strip()


def _message_value(message: object) -> str:
    value = getattr(message, "value", message)
    if isinstance(value, bytes):
        return value.decode("utf-8")
    if isinstance(value, str):
        return value
    raise RuntimeError(f"Unsupported Kafka message value type: {type(value).__name__}")


def _commit_if_supported(messages: Iterable[object]) -> None:
    commit = getattr(messages, "commit", None)
    if callable(commit):
        commit()


if __name__ == "__main__":
    run_research_kafka_consumer_forever()
