from __future__ import annotations

import json
from typing import Iterable, Protocol

from pydantic import BaseModel


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
    """Raised before consumption crosses an offset whose DLQ record was not persisted."""


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


def message_value(message: object) -> str:
    value = getattr(message, "value", message)
    if isinstance(value, bytes):
        return value.decode("utf-8")
    if isinstance(value, str):
        return value
    raise RuntimeError(f"Unsupported Kafka message value type: {type(value).__name__}")


def commit_if_supported(messages: Iterable[object], message: object | None = None) -> None:
    commit = getattr(messages, "commit", None)
    if not callable(commit):
        return
    topic = getattr(message, "topic", None)
    partition = getattr(message, "partition", None)
    offset = getattr(message, "offset", None)
    if message is None or topic is None or partition is None or offset is None:
        commit()
        return
    # KafkaConsumer may prefetch multiple records. A bare commit() persists the
    # consumer's current *position*, which can be past records that this loop has
    # not executed yet. Commit exactly the record that just reached a durable
    # outcome so a crash cannot silently skip prefetched research tasks.
    from kafka import OffsetAndMetadata, TopicPartition

    commit(offsets={
        TopicPartition(str(topic), int(partition)): OffsetAndMetadata(int(offset) + 1, "")
    })


def kafka_security_options(settings: object) -> dict[str, object]:
    protocol = str(getattr(settings, "kafka_security_protocol", "PLAINTEXT")).strip().upper()
    if not protocol.startswith("SASL"):
        return {"security_protocol": protocol} if protocol != "PLAINTEXT" else {}
    username = str(getattr(settings, "kafka_sasl_username", "")).strip()
    password = str(getattr(settings, "kafka_sasl_password", ""))
    if not username or not password:
        raise RuntimeError(
            "SASL Kafka requires NOTEWEAVE_KAFKA_SASL_USERNAME and NOTEWEAVE_KAFKA_SASL_PASSWORD"
        )
    return {
        "security_protocol": protocol,
        "sasl_mechanism": str(getattr(settings, "kafka_sasl_mechanism", "PLAIN")).strip().upper(),
        "sasl_plain_username": username,
        "sasl_plain_password": password,
    }


def create_kafka_dead_letter_sink(
    *,
    bootstrap_servers: str,
    topic: str,
    retries: int,
    security_options: dict[str, object] | None = None,
) -> KafkaDeadLetterSink:
    try:
        from kafka import KafkaProducer
    except ImportError as exc:
        raise RuntimeError(
            "kafka-python is required for Kafka dead-letter publishing. "
            "Run workers/scripts/setup-workers-conda.ps1 -Update first."
        ) from exc
    producer = KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        acks="all",
        retries=max(1, retries),
        value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
        **(security_options or {}),
    )
    return KafkaDeadLetterSink(producer, topic)
