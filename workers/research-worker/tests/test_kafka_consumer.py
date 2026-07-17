from __future__ import annotations

import json
import sys
import types
from dataclasses import dataclass

import pytest

pytestmark = pytest.mark.usefixtures("fake_default_llm")

from app import kafka_consumer as kafka_consumer_module
from app.kafka_consumer import (
    DeadLetterPublishError,
    KafkaDeadLetterSink,
    consume_research_run_messages,
    create_research_kafka_consumer,
    handle_research_run_message,
)
from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-r-kafka",
            "workspace_id": "ws-1",
            "target_id": "run-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Kafka Source",
                    "summary": "Kafka task dispatch should still produce evidence cards.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Kafka dispatch must not bypass verification."],
            },
            "input_payload": {
                "question": "What should Kafka dispatch verify?",
                "profile_key": "DEFAULT",
            },
        }
    )


class FakeCallbackClient:
    def __init__(self) -> None:
        self.progress_events: list[ResearchProgressEvent] = []
        self.completed_results: list[ResearchTaskResult] = []

    def fetch_task_input(self, task_id: str) -> ResearchTaskInput:
        assert task_id == "task-r-kafka"
        return _build_task_input()

    def send_progress(self, task_id: str, event: ResearchProgressEvent) -> None:
        assert task_id == "task-r-kafka"
        self.progress_events.append(event)

    def send_complete(self, task_id: str, result: ResearchTaskResult) -> None:
        assert task_id == "task-r-kafka"
        self.completed_results.append(result)

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str) -> None:
        raise AssertionError(f"unexpected failure callback: {task_id} {phase} {error_code} {error_message}")


@dataclass
class FakeKafkaMessage:
    value: str


class FakeKafkaConsumer:
    def __init__(self, messages: list[FakeKafkaMessage]) -> None:
        self._messages = messages
        self.commit_count = 0

    def __iter__(self):
        return iter(self._messages)

    def commit(self) -> None:
        self.commit_count += 1


def test_handle_research_run_message_should_execute_task_from_kafka_payload() -> None:
    client = FakeCallbackClient()
    response = handle_research_run_message(
        json.dumps({"task_id": "task-r-kafka", "target_id": "run-1"}),
        client,
    )

    assert response.status == "COMPLETED"
    assert [event.phase for event in client.progress_events] == [
        "PLANNING",
        "SEARCHING",
        "READING",
        "EXTRACTING",
        "VERIFYING",
        "WRITING",
    ]
    assert client.completed_results[0].result_payload["evidence_cards"][0]["source_title"] == "Kafka Source"


def test_consume_research_run_messages_should_handle_iterable_kafka_messages() -> None:
    client = FakeCallbackClient()
    summary = consume_research_run_messages(
        [FakeKafkaMessage(json.dumps({"task_id": "task-r-kafka"}))],
        client,
        max_messages=1,
    )

    assert summary.consumed_count == 1
    assert summary.completed_count == 1
    assert summary.failed_count == 0
    assert len(client.completed_results) == 1


def test_consume_research_run_messages_should_commit_after_success() -> None:
    client = FakeCallbackClient()
    consumer = FakeKafkaConsumer([FakeKafkaMessage(json.dumps({"task_id": "task-r-kafka"}))])

    summary = consume_research_run_messages(consumer, client, max_messages=1)

    assert summary.completed_count == 1
    assert consumer.commit_count == 1


def test_consume_research_run_messages_should_skip_poison_message_and_continue() -> None:
    client = FakeCallbackClient()
    consumer = FakeKafkaConsumer(
        [
            FakeKafkaMessage(json.dumps({"target_id": "run-missing-task"})),
            FakeKafkaMessage(json.dumps({"task_id": "task-r-kafka"})),
        ]
    )

    summary = consume_research_run_messages(consumer, client, max_messages=2, max_attempts=2)

    assert summary.consumed_count == 2
    assert summary.completed_count == 1
    assert summary.failed_count == 1
    assert summary.failed_attempt_count == 1
    assert summary.failed_message_count == 1
    assert summary.dead_letter_count == 1
    assert summary.skipped_count == 1
    assert consumer.commit_count == 2
    assert len(client.completed_results) == 1


def test_consume_research_run_messages_can_raise_after_retry_exhaustion() -> None:
    consumer = FakeKafkaConsumer([FakeKafkaMessage(json.dumps({"target_id": "run-missing-task"}))])

    with pytest.raises(RuntimeError):
        consume_research_run_messages(
            consumer,
            FakeCallbackClient(),
            max_messages=1,
            max_attempts=2,
            raise_on_failure=True,
        )

    assert consumer.commit_count == 1


def test_handle_research_run_message_should_reject_payload_without_task_id() -> None:
    with pytest.raises(RuntimeError):
        handle_research_run_message(json.dumps({"target_id": "run-1"}), FakeCallbackClient())


def test_kafka_dead_letter_sink_should_wait_for_broker_ack() -> None:
    calls: list[tuple[str, object]] = []

    class FakeFuture:
        def get(self, timeout: float) -> None:
            calls.append(("get", timeout))

    class FakeProducer:
        def send(self, topic: str, value: object) -> FakeFuture:
            calls.append((topic, value))
            return FakeFuture()

        def flush(self, timeout: float) -> None:
            calls.append(("flush", timeout))

    sink = KafkaDeadLetterSink(FakeProducer(), "research.dlq", timeout_seconds=3.0)

    assert sink.publish({"error": "poison"}) is True
    assert calls == [
        ("research.dlq", {"error": "poison"}),
        ("get", 3.0),
        ("flush", 3.0),
    ]


def test_consumer_should_not_commit_poison_message_when_dlq_publish_fails() -> None:
    class FailingSink:
        def publish(self, record: dict[str, object]) -> bool:
            return False

    consumer = FakeKafkaConsumer([FakeKafkaMessage(json.dumps({"target_id": "missing"}))])
    with pytest.raises(DeadLetterPublishError):
        consume_research_run_messages(
            consumer,
            FakeCallbackClient(),
            dead_letter_sink=FailingSink(),
        )

    assert consumer.commit_count == 0


def test_consumer_should_not_cross_unacked_poison_offset_to_commit_later_message(monkeypatch) -> None:
    class FailingSink:
        def publish(self, record: dict[str, object]) -> bool:
            return False

    processed: list[str] = []
    consumer = FakeKafkaConsumer([
        FakeKafkaMessage("poison"),
        FakeKafkaMessage("good"),
    ])

    def fake_handle(payload: str, client=None):
        processed.append(payload)
        if payload == "poison":
            raise RuntimeError("poison")
        return object()

    monkeypatch.setattr(kafka_consumer_module, "handle_research_run_message", fake_handle)

    with pytest.raises(DeadLetterPublishError):
        consume_research_run_messages(
            consumer,
            dead_letter_sink=FailingSink(),
            max_attempts=1,
        )

    assert processed == ["poison"]
    assert consumer.commit_count == 0


def test_consumer_should_dead_letter_undecodable_message_before_commit() -> None:
    class RecordingSink:
        def __init__(self) -> None:
            self.records: list[dict[str, object]] = []

        def publish(self, record: dict[str, object]) -> bool:
            self.records.append(record)
            return True

    sink = RecordingSink()
    consumer = FakeKafkaConsumer([FakeKafkaMessage(value=object())])

    summary = consume_research_run_messages(consumer, dead_letter_sink=sink)

    assert summary.dead_letter_count == 1
    assert summary.failed_message_count == 1
    assert sink.records[0]["retryable"] is False
    assert consumer.commit_count == 1


def test_consumer_should_redact_secrets_before_writing_dlq_record(monkeypatch) -> None:
    class RecordingSink:
        def __init__(self) -> None:
            self.records: list[dict[str, object]] = []

        def publish(self, record: dict[str, object]) -> bool:
            self.records.append(record)
            return True

    def fail_with_secret(payload: str, client=None):
        del payload, client
        raise RuntimeError("provider rejected request api_key=top-secret")

    sink = RecordingSink()
    monkeypatch.setattr(kafka_consumer_module, "handle_research_run_message", fail_with_secret)

    consume_research_run_messages(
        FakeKafkaConsumer([FakeKafkaMessage("payload")]),
        dead_letter_sink=sink,
        max_attempts=1,
    )

    assert sink.records[0]["error_message"] == "provider rejected request api_key=[REDACTED]"


def test_kafka_consumer_should_keep_raw_bytes_for_dlq_decode_handling(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeSettings:
        kafka_research_topic = "research.run"
        kafka_bootstrap_servers = "kafka:9092"
        kafka_group_id = "research-worker"

    class FakeKafkaConsumer:
        def __init__(self, *topics, **kwargs) -> None:
            captured["topics"] = topics
            captured["kwargs"] = kwargs

    monkeypatch.setattr(kafka_consumer_module, "load_settings", lambda: FakeSettings())
    monkeypatch.setitem(sys.modules, "kafka", types.SimpleNamespace(KafkaConsumer=FakeKafkaConsumer))

    create_research_kafka_consumer()

    assert captured["topics"] == ("research.run",)
    assert captured["kwargs"]["enable_auto_commit"] is False
    assert "value_deserializer" not in captured["kwargs"]


def test_run_research_kafka_consumer_forever_should_use_retry_settings(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeSettings:
        kafka_consume_max_attempts = 7
        kafka_consume_raise_on_failure = True

    sink = object()

    def fake_consume(
        messages,
        client=None,
        max_messages=None,
        max_attempts=3,
        raise_on_failure=False,
        dead_letter_sink=None,
    ):
        captured["messages"] = messages
        captured["max_attempts"] = max_attempts
        captured["raise_on_failure"] = raise_on_failure
        captured["dead_letter_sink"] = dead_letter_sink
        return None

    consumer = FakeKafkaConsumer([])
    monkeypatch.setattr(kafka_consumer_module, "load_settings", lambda: FakeSettings())
    monkeypatch.setattr(kafka_consumer_module, "create_research_kafka_consumer", lambda: consumer)
    monkeypatch.setattr(kafka_consumer_module, "create_research_kafka_dead_letter_sink", lambda: sink)
    monkeypatch.setattr(kafka_consumer_module, "consume_research_run_messages", fake_consume)

    kafka_consumer_module.run_research_kafka_consumer_forever()

    assert captured == {
        "messages": consumer,
        "max_attempts": 7,
        "raise_on_failure": True,
        "dead_letter_sink": sink,
    }
