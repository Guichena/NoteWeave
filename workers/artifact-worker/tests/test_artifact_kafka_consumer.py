from __future__ import annotations

import json
import threading
import time

import pytest

from app.artifact_kafka_consumer import (
    ArtifactCallbackHttpError,
    ArtifactKafkaConsumerRuntime,
    consume_artifact_commands,
    create_artifact_kafka_consumer,
)
from app.config import Settings


class FakeMessages:
    def __init__(self, values: list[str]) -> None:
        self.values = [type("Message", (), {"value": value.encode("utf-8")})() for value in values]
        self.commit_count = 0
        self.next_count = 0

    def __iter__(self):
        owner = self

        class Iterator:
            def __init__(self) -> None:
                self.index = 0

            def __iter__(self):
                return self

            def __next__(self):
                if self.index >= len(owner.values):
                    raise StopIteration
                value = owner.values[self.index]
                self.index += 1
                owner.next_count += 1
                return value

        return Iterator()

    def commit(self) -> None:
        self.commit_count += 1


class FakeDeadLetterSink:
    def __init__(self, succeeds: bool = True) -> None:
        self.succeeds = succeeds
        self.records: list[dict[str, object]] = []

    def publish(self, record: dict[str, object]) -> bool:
        self.records.append(record)
        return self.succeeds


def command(task_id: str = "task-1", delivery_token: str = "delivery-1") -> str:
    return json.dumps({
        "schema_version": "artifact-command.v1",
        "task_id": task_id,
        "delivery_token": delivery_token,
    })


def test_consumer_commits_only_after_execution_protocol_completes() -> None:
    messages = FakeMessages([command()])
    calls: list[tuple[str, str]] = []

    summary = consume_artifact_commands(
        messages,
        executor=lambda task_id, *, delivery_token: calls.append((task_id, delivery_token)),
    )

    assert calls == [("task-1", "delivery-1")]
    assert messages.commit_count == 1
    assert summary.completed_count == 1


def test_material_command_uses_material_executor_and_keeps_artifact_path_separate() -> None:
    messages = FakeMessages([json.dumps({
        "schema_version": "video-material-command.v1",
        "task_id": "material-1", "delivery_token": "delivery-material",
    })])
    calls: list[tuple[str, str]] = []
    summary = consume_artifact_commands(
        messages,
        executor=lambda *_args, **_kwargs: pytest.fail("Artifact Job executor was called"),
        material_executor=lambda task_id, *, delivery_token: calls.append((task_id, delivery_token)),
    )
    assert calls == [("material-1", "delivery-material")]
    assert summary.completed_count == 1
    assert messages.commit_count == 1


def test_consumer_does_not_commit_when_execution_or_callback_is_unconfirmed() -> None:
    messages = FakeMessages([command()])

    with pytest.raises(RuntimeError, match="callback unavailable"):
        consume_artifact_commands(
            messages,
            executor=lambda *_args, **_kwargs: (_ for _ in ()).throw(
                RuntimeError("callback unavailable")
            ),
        )

    assert messages.commit_count == 0


def test_consumer_commits_execution_failure_after_fail_callback_is_confirmed() -> None:
    messages = FakeMessages([command()])

    def fail_after_report(*_args, **_kwargs):
        failure = ValueError("generation failed")
        failure.artifact_failure_reported = True
        raise failure

    summary = consume_artifact_commands(messages, executor=fail_after_report)

    assert messages.commit_count == 1
    assert summary.completed_count == 1


def test_consumer_skips_stale_delivery_before_external_execution() -> None:
    messages = FakeMessages([command()])

    def stale(*_args, **_kwargs):
        raise ArtifactCallbackHttpError(409, "delivery is stale")

    summary = consume_artifact_commands(messages, executor=stale)

    assert messages.commit_count == 1
    assert summary.skipped_count == 1


def test_invalid_command_is_committed_only_after_durable_dead_letter() -> None:
    messages = FakeMessages(['{"schema_version":"artifact-command.v0"}'])
    sink = FakeDeadLetterSink()

    summary = consume_artifact_commands(messages, dead_letter_sink=sink)

    assert messages.commit_count == 1
    assert summary.dead_letter_count == 1
    assert len(sink.records) == 1
    assert "original_message" not in sink.records[0]


def test_consumer_configuration_disables_auto_commit_and_limits_prefetch(monkeypatch) -> None:
    captured: dict[str, object] = {}

    class FakeConsumer:
        def __init__(self, *topics, **kwargs) -> None:
            captured["topics"] = topics
            captured["kwargs"] = kwargs

    monkeypatch.setitem(__import__("sys").modules, "kafka", type("Kafka", (), {"KafkaConsumer": FakeConsumer}))
    settings = Settings(
        kafka_artifact_group_id="artifact-workers",
        kafka_artifact_max_poll_interval_seconds=4200,
    )

    create_artifact_kafka_consumer(settings)

    assert captured["topics"] == ("noteweave.artifact.job",)
    assert captured["kwargs"]["enable_auto_commit"] is False
    assert captured["kwargs"]["max_poll_records"] == 1
    assert captured["kwargs"]["max_poll_interval_ms"] == 4_200_000


def test_single_message_iteration_does_not_prefetch_the_next_command() -> None:
    messages = FakeMessages([command("task-1"), command("task-2")])

    summary = consume_artifact_commands(
        messages,
        executor=lambda *_args, **_kwargs: None,
        max_messages=1,
    )

    assert summary.consumed_count == 1
    assert messages.next_count == 1
    assert messages.commit_count == 1


def test_artifact_command_topic_cannot_drift_from_java_outbox_contract() -> None:
    with pytest.raises(ValueError, match="must be noteweave.artifact.job"):
        Settings(kafka_artifact_topic="artifact.command")


def test_runtime_rebuilds_kafka_session_after_iteration_failure(monkeypatch) -> None:
    created_consumers: list[object] = []
    created_sinks: list[object] = []
    second_iteration = threading.Event()

    class Closeable:
        def __init__(self) -> None:
            self.close_count = 0

        def close(self, *args, **kwargs) -> None:
            self.close_count += 1

    def create_consumer(_settings):
        consumer = Closeable()
        created_consumers.append(consumer)
        return consumer

    def create_sink(_settings):
        sink = Closeable()
        created_sinks.append(sink)
        return sink

    iteration_count = 0

    def consume_once(*_args, **_kwargs):
        nonlocal iteration_count
        iteration_count += 1
        if iteration_count == 1:
            raise RuntimeError("broker disconnected")
        second_iteration.set()
        runtime._stop.wait(2)

    monkeypatch.setattr("app.artifact_kafka_consumer.create_artifact_kafka_consumer", create_consumer)
    monkeypatch.setattr("app.artifact_kafka_consumer.create_artifact_dead_letter_sink", create_sink)
    monkeypatch.setattr("app.artifact_kafka_consumer.consume_artifact_commands", consume_once)

    runtime = ArtifactKafkaConsumerRuntime(Settings())
    runtime.start()
    try:
        assert second_iteration.wait(3)
        assert runtime.healthy
    finally:
        runtime.stop()

    assert len(created_consumers) == 2
    assert len(created_sinks) == 2
    assert [consumer.close_count for consumer in created_consumers] == [1, 1]
    assert [sink.close_count for sink in created_sinks] == [1, 1]


def test_concurrent_session_close_releases_each_resource_once() -> None:
    class Closeable:
        def __init__(self) -> None:
            self.close_count = 0

        def close(self, *args, **kwargs) -> None:
            time.sleep(0.01)
            self.close_count += 1

    runtime = ArtifactKafkaConsumerRuntime(Settings())
    consumer = Closeable()
    sink = Closeable()
    runtime.consumer = consumer
    runtime.dead_letter_sink = sink

    first = threading.Thread(target=runtime._close_session)
    second = threading.Thread(target=runtime._close_session)
    first.start()
    second.start()
    first.join()
    second.join()

    assert consumer.close_count == 1
    assert sink.close_count == 1
