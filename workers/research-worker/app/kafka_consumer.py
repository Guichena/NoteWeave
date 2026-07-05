from __future__ import annotations

import json
from typing import Any, Iterable

from pydantic import BaseModel

from app.callback import (
    ResearchCallbackClient,
    ResearchWorkerExecutionResponse,
    run_research_task_with_callbacks,
)
from app.config import load_settings


class KafkaConsumeSummary(BaseModel):
    consumed_count: int
    completed_count: int
    failed_count: int


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
) -> KafkaConsumeSummary:
    consumed_count = 0
    completed_count = 0
    failed_count = 0
    for message in messages:
        if max_messages is not None and consumed_count >= max_messages:
            break
        consumed_count += 1
        try:
            handle_research_run_message(_message_value(message), client)
            completed_count += 1
            _commit_if_supported(messages)
        except Exception:
            failed_count += 1
            raise
    return KafkaConsumeSummary(
        consumed_count=consumed_count,
        completed_count=completed_count,
        failed_count=failed_count,
    )


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
        value_deserializer=lambda value: value.decode("utf-8"),
    )


def run_research_kafka_consumer_forever() -> None:
    consumer = create_research_kafka_consumer()
    consume_research_run_messages(consumer)


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
