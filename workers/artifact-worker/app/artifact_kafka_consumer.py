from __future__ import annotations

import json
import logging
import threading
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from hashlib import sha256
from typing import Protocol

from pydantic import ValidationError

from app.artifact_command_contract import ArtifactCommand, VideoMaterialCommand
from app.callback import (ArtifactCallbackHttpError, run_artifact_task_with_callbacks,
                          run_video_material_task_with_callbacks)
from app.config import Settings, load_settings
from app.error_sanitizer import sanitize_error_message


logger = logging.getLogger(__name__)


class DeadLetterSink(Protocol):
    def publish(self, record: dict[str, object]) -> bool:
        ...


class ArtifactDeadLetterPublishError(RuntimeError):
    pass


@dataclass(frozen=True)
class ArtifactConsumeSummary:
    consumed_count: int
    completed_count: int
    skipped_count: int
    dead_letter_count: int


class KafkaArtifactDeadLetterSink:
    def __init__(self, producer: object, topic: str, timeout_seconds: float = 10.0) -> None:
        self.producer = producer
        self.topic = topic
        self.timeout_seconds = timeout_seconds

    def publish(self, record: dict[str, object]) -> bool:
        try:
            future = self.producer.send(self.topic, value=record)
            get = getattr(future, "get", None)
            if callable(get):
                get(timeout=self.timeout_seconds)
            return True
        except Exception:
            logger.exception("Artifact command dead-letter publish failed")
            return False

    def close(self) -> None:
        close = getattr(self.producer, "close", None)
        if callable(close):
            close(timeout=self.timeout_seconds)


def consume_artifact_commands(
    messages: Iterable[object],
    *,
    executor: Callable[..., object] = run_artifact_task_with_callbacks,
    material_executor: Callable[..., object] = run_video_material_task_with_callbacks,
    dead_letter_sink: DeadLetterSink | None = None,
    max_messages: int | None = None,
) -> ArtifactConsumeSummary:
    consumed = completed = skipped = dead_letters = 0
    iterator = iter(messages)
    while max_messages is None or consumed < max_messages:
        try:
            message = next(iterator)
        except StopIteration:
            break
        consumed += 1
        try:
            command = _parse_command(message)
        except (UnicodeDecodeError, json.JSONDecodeError, ValidationError, TypeError) as exc:
            if dead_letter_sink is None or not dead_letter_sink.publish(_dead_letter_record(message, exc)):
                raise ArtifactDeadLetterPublishError(
                    "Artifact command is invalid and could not be persisted to the DLQ"
                ) from exc
            dead_letters += 1
            _commit(messages)
            continue

        try:
            selected_executor = material_executor if isinstance(command, VideoMaterialCommand) else executor
            selected_executor(command.task_id, delivery_token=command.delivery_token)
        except ArtifactCallbackHttpError as exc:
            if bool(getattr(exc, "artifact_failure_reported", False)):
                completed += 1
                _commit(messages)
                continue
            if exc.status_code == 409:
                # The Java host no longer recognizes this delivery token. The
                # message is an old Kafka replay and must not execute again.
                skipped += 1
                _commit(messages)
                continue
            raise
        except Exception as exc:
            if bool(getattr(exc, "artifact_failure_reported", False)):
                completed += 1
                _commit(messages)
                continue
            raise

        completed += 1
        _commit(messages)

    return ArtifactConsumeSummary(consumed, completed, skipped, dead_letters)


def create_artifact_kafka_consumer(settings: Settings | None = None) -> object:
    active = settings or load_settings()
    try:
        from kafka import KafkaConsumer
    except ImportError as exc:
        raise RuntimeError("kafka-python is required for Artifact command consumption") from exc
    return KafkaConsumer(
        active.kafka_artifact_topic,
        bootstrap_servers=active.kafka_bootstrap_servers,
        group_id=active.kafka_artifact_group_id,
        enable_auto_commit=False,
        auto_offset_reset="earliest",
        max_poll_records=1,
        max_poll_interval_ms=active.kafka_artifact_max_poll_interval_seconds * 1000,
        **_kafka_security_options(active),
    )


def create_artifact_dead_letter_sink(settings: Settings | None = None) -> KafkaArtifactDeadLetterSink:
    active = settings or load_settings()
    try:
        from kafka import KafkaProducer
    except ImportError as exc:
        raise RuntimeError("kafka-python is required for Artifact dead-letter publishing") from exc
    producer = KafkaProducer(
        bootstrap_servers=active.kafka_bootstrap_servers,
        acks="all",
        retries=active.kafka_consume_max_attempts,
        value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
        **_kafka_security_options(active),
    )
    return KafkaArtifactDeadLetterSink(producer, active.kafka_artifact_dlq_topic)


class ArtifactKafkaConsumerRuntime:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or load_settings()
        self.consumer: object | None = None
        self.dead_letter_sink: KafkaArtifactDeadLetterSink | None = None
        self.thread: threading.Thread | None = None
        self.failure: Exception | None = None
        self._stop = threading.Event()
        self._session_lock = threading.Lock()

    def start(self) -> None:
        if not self.settings.artifact_consumer_enabled:
            return

        def consume() -> None:
            retry_delay_seconds = 1.0
            while not self._stop.is_set():
                try:
                    consumer, dead_letter_sink = self._open_session()
                    self.failure = None
                    consume_artifact_commands(
                        consumer,
                        dead_letter_sink=dead_letter_sink,
                        max_messages=1,
                    )
                    retry_delay_seconds = 1.0
                except Exception as exc:
                    if self._stop.is_set():
                        break
                    self.failure = exc
                    logger.exception("Artifact Kafka consumer iteration failed; reconnecting")
                    self._close_session()
                    if self._stop.wait(retry_delay_seconds):
                        break
                    retry_delay_seconds = min(retry_delay_seconds * 2, 30.0)

        self.thread = threading.Thread(
            target=consume,
            name="artifact-kafka-consumer",
            daemon=True,
        )
        self.thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._close_session()
        if self.thread is not None:
            self.thread.join(timeout=10)

    def _open_session(self) -> tuple[object, KafkaArtifactDeadLetterSink]:
        with self._session_lock:
            if self.consumer is None:
                self.consumer = create_artifact_kafka_consumer(self.settings)
            if self.dead_letter_sink is None:
                self.dead_letter_sink = create_artifact_dead_letter_sink(self.settings)
            return self.consumer, self.dead_letter_sink

    def _close_session(self) -> None:
        # stop() and the consumer thread can both reach this path. Detach the
        # session once so Kafka resources are never closed concurrently.
        with self._session_lock:
            consumer = self.consumer
            self.consumer = None
            sink = self.dead_letter_sink
            self.dead_letter_sink = None
        close = getattr(consumer, "close", None)
        if callable(close):
            try:
                close()
            except Exception:
                logger.exception("Artifact Kafka consumer close failed")
        if sink is not None:
            try:
                sink.close()
            except Exception:
                logger.exception("Artifact Kafka dead-letter producer close failed")

    @property
    def healthy(self) -> bool:
        if not self.settings.artifact_consumer_enabled:
            return True
        return self.failure is None and self.thread is not None and self.thread.is_alive()


def _parse_command(message: object) -> ArtifactCommand | VideoMaterialCommand:
    value = getattr(message, "value", message)
    if isinstance(value, bytes):
        value = value.decode("utf-8")
    if not isinstance(value, str):
        raise TypeError(f"Unsupported Kafka message value type: {type(value).__name__}")
    payload = json.loads(value)
    if isinstance(payload, dict) and payload.get("schema_version") == "video-material-command.v1":
        return VideoMaterialCommand.model_validate(payload)
    return ArtifactCommand.model_validate(payload)


def _commit(messages: Iterable[object]) -> None:
    commit = getattr(messages, "commit", None)
    if callable(commit):
        commit()


def _dead_letter_record(message: object, exc: Exception) -> dict[str, object]:
    value = getattr(message, "value", message)
    if isinstance(value, bytes):
        value = value.decode("utf-8", errors="replace")
    sanitized = sanitize_error_message(str(exc))
    return {
        "original_message_sha256": sha256(str(value).encode("utf-8")).hexdigest(),
        "error_type": type(exc).__name__,
        "error_message": sanitized,
        "retryable": False,
    }


def _kafka_security_options(settings: Settings) -> dict[str, object]:
    protocol = settings.kafka_security_protocol.strip().upper()
    if not protocol.startswith("SASL"):
        return {"security_protocol": protocol} if protocol != "PLAINTEXT" else {}
    if not settings.kafka_sasl_username.strip() or not settings.kafka_sasl_password:
        raise RuntimeError(
            "SASL Kafka requires NOTEWEAVE_KAFKA_SASL_USERNAME and NOTEWEAVE_KAFKA_SASL_PASSWORD"
        )
    return {
        "security_protocol": protocol,
        "sasl_mechanism": settings.kafka_sasl_mechanism.strip().upper(),
        "sasl_plain_username": settings.kafka_sasl_username.strip(),
        "sasl_plain_password": settings.kafka_sasl_password,
    }
