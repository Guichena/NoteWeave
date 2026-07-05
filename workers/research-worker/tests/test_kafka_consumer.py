from __future__ import annotations

import json
from dataclasses import dataclass

import pytest

from app.kafka_consumer import consume_research_run_messages, handle_research_run_message
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


def test_handle_research_run_message_should_reject_payload_without_task_id() -> None:
    with pytest.raises(RuntimeError):
        handle_research_run_message(json.dumps({"target_id": "run-1"}), FakeCallbackClient())
