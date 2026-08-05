from __future__ import annotations

import json
from threading import Event

import pytest

from app.agent_task_client import (
    AgentCompletionReceipt,
    AgentTaskApiError,
    AgentTaskClaim,
    AgentTaskNotClaimableError,
)
from app.kafka_primitives import DeadLetterPublishError
from app.research_agent_completion_contract import build_completion_envelope

_TASK_ID = "00000000-0000-0000-0000-000000000001"


def _claim(task_id: str = _TASK_ID, lease_epoch: int = 2, fencing_token: int = 7,
           role: str = "DEEP_CELL") -> AgentTaskClaim:
    from app.task_snapshot_contract import snapshot_digest

    payload: dict[str, object] = {
        "schema_version": "research-agent-task-snapshot.v1",
        "task_id": task_id,
        "research_run_id": "run-1",
        "workspace_id": "workspace-1",
        "role": role,
        "entity_id": "entity-1",
        "branch_id": "branch-main",
        "plan_revision": 2,
        "entity_set_version": 3,
        "lease_epoch": lease_epoch,
        "fencing_token": fencing_token,
        "target_cells": [{"cell_id": "entity-1:method", "expected_version": 3}],
        "budget": {"llm_calls": 1},
        "provider_key": "research-default",
        "source_policy": {"allow_workspace_sources": True},
        "query_policy": {"query": "test question"},
    }
    digest = snapshot_digest(payload)
    return AgentTaskClaim(task_id, lease_epoch, fencing_token, "[\"entity-1:method\"]", "{\"llm_calls\":1}",
                          json.dumps({**payload, "snapshot_digest": digest}), digest)


class FakeAgentClient:
    def __init__(self) -> None:
        self.claims: list[str] = []
        self.heartbeats: list[str] = []
        self.completions: list[object] = []

    def claim(self, task_id: str, *, lease_seconds: int = 60) -> AgentTaskClaim:
        self.claims.append(task_id)
        return _claim(task_id)

    def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
        self.heartbeats.append(claim.agent_task_id)
        return claim

    def complete(self, completion) -> AgentCompletionReceipt:
        self.completions.append(completion)
        return AgentCompletionReceipt(
            schema_version="research-agent-completion-receipt.v1",
            completion_id="completion-db-1",
            execution_id="execution-db-1",
            task_id=completion.task_id,
            completion_digest=completion.envelope_digest,
            receipt_digest="sha256:" + "9" * 64,
            idempotent_replay=False,
        )


def _command() -> str:
    return json.dumps({
        "schema_version": "research-agent-command.v1",
        "command_id": "command-1",
        "research_run_id": "run-1",
        "agent_task_id": _TASK_ID,
        "idempotency_key": "outbox-1",
        "delivery_attempt": 1,
    })


def test_consumer_should_reject_executor_result_with_stale_claim_identity() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command

    client = FakeAgentClient()

    def stale_executor(_command, claim):
        return _completion(claim).model_copy(update={"lease_epoch": 1})

    with pytest.raises(RuntimeError, match="claim identity"):
        handle_research_agent_command(_command(), client, stale_executor, worker_instance_id="worker-a")
    assert client.completions == []


def test_consumer_should_reject_non_contract_message_before_claim() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command

    client = FakeAgentClient()
    with pytest.raises(Exception):
        handle_research_agent_command("{}", client, lambda *_: None, worker_instance_id="worker-a")
    assert client.claims == []


def test_consumer_should_reject_missing_or_mismatched_snapshot_before_executor() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command
    from app.task_snapshot_contract import TaskSnapshotTrustError

    client = FakeAgentClient()
    client.claim = lambda task_id, **_kwargs: AgentTaskClaim(task_id, 2, 7, "[]", "{}")  # type: ignore[method-assign]
    called = False

    def executor(*_args):
        nonlocal called
        called = True
        raise AssertionError("executor must not run")

    with pytest.raises(TaskSnapshotTrustError):
        handle_research_agent_command(_command(), client, executor, worker_instance_id="worker-a")
    assert called is False


class _FakeKafkaMessage:
    def __init__(self, value: object) -> None:
        self.value = value


class _FakeKafkaConsumer:
    def __init__(self, messages: list[_FakeKafkaMessage]) -> None:
        self.messages = messages
        self.commit_count = 0

    def __iter__(self):
        return iter(self.messages)

    def commit(self) -> None:
        self.commit_count += 1


class _RecordingDlq:
    def __init__(self, succeeds: bool = True) -> None:
        self.succeeds = succeeds
        self.records: list[dict[str, object]] = []

    def publish(self, record: dict[str, object]) -> bool:
        self.records.append(record)
        return self.succeeds


class _RecordingFailureReporter:
    def __init__(self, fails: bool = False) -> None:
        self.fails = fails
        self.calls: list[tuple[object, dict[str, object]]] = []

    def report_delivery_failure(self, command, failure: dict[str, object]) -> None:
        self.calls.append((command, failure))
        if self.fails:
            raise ConnectionError("backend unavailable")


def _completion(claim: AgentTaskClaim, *, snapshot_digest: str | None = None):
    return build_completion_envelope({
        "schema_version": "research-agent-completion.v1",
        "task_id": claim.agent_task_id,
        "worker_instance_id": "worker-a",
        "lease_epoch": claim.lease_epoch,
        "fencing_token": claim.fencing_token,
        "execution_key": f"deep-cell:{claim.agent_task_id}:{claim.lease_epoch}:{claim.fencing_token}",
        "task_snapshot_digest": snapshot_digest or str(claim.snapshot_digest),
        "termination_reason": "NO_SUPPORTED_CANDIDATE",
        "budget_usage": {
            "llm_calls": 0,
            "search_calls": 1, "fetch_calls": 1, "read_calls": 1, "extract_calls": 1,
            "evidence_cards": 0, "candidates_submitted": 0,
        },
        "telemetry": {"search_hits": 0, "documents": 0, "windows": 0},
        "trace_digest": "sha256:" + "8" * 64,
        "evidence": [],
        "candidates": [],
    })


def test_consumer_should_send_atomic_executor_result_to_complete_exactly_once() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command

    client = FakeAgentClient()
    result = handle_research_agent_command(
        _command(), client, lambda _command, claim: _completion(claim), worker_instance_id="worker-a"
    )

    assert result.status == "COMPLETED"
    assert result.execution_id == "execution-db-1"
    assert len(client.completions) == 1


def test_consumer_should_stop_before_executor_and_complete_when_initial_heartbeat_is_stale() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command
    from app.execution_control import ExecutionStopped

    class StaleHeartbeatClient(FakeAgentClient):
        def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
            raise AgentTaskApiError(409, "RESEARCH_AGENT_TASK_STALE_LEASE")

    client = StaleHeartbeatClient()
    called = False

    def executor(*_args):
        nonlocal called
        called = True
        raise AssertionError("stale heartbeat must stop before tool execution")

    with pytest.raises(ExecutionStopped, match="STALE_LEASE"):
        handle_research_agent_command(_command(), client, executor, worker_instance_id="worker-a")
    assert called is False
    assert client.completions == []


def test_consumer_should_leave_offset_uncommitted_when_lease_control_stops() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands
    from app.execution_control import ExecutionStopped

    class StaleHeartbeatClient(FakeAgentClient):
        def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
            raise AgentTaskApiError(409, "RESEARCH_AGENT_TASK_STALE_LEASE")

    client = StaleHeartbeatClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(_command())])
    dlq = _RecordingDlq()

    with pytest.raises(ExecutionStopped, match="STALE_LEASE"):
        consume_research_agent_commands(
            consumer,
            client,
            lambda _command, claim: _completion(claim),
            worker_instance_id="worker-a",
            dead_letter_sink=dlq,
        )

    assert consumer.commit_count == 0
    assert dlq.records == []
    assert client.completions == []


def test_consumer_should_keep_slow_execution_alive_with_multiple_heartbeats() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command

    second_heartbeat = Event()

    class RecordingHeartbeatClient(FakeAgentClient):
        def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
            result = super().heartbeat(claim, lease_seconds=lease_seconds)
            if len(self.heartbeats) >= 2:
                second_heartbeat.set()
            return result

    client = RecordingHeartbeatClient()

    def slow_executor(_command, claim):
        assert second_heartbeat.wait(timeout=1.0)
        return _completion(claim)

    result = handle_research_agent_command(
        _command(),
        client,
        slow_executor,
        worker_instance_id="worker-a",
        lease_seconds=4,
        heartbeat_interval_seconds=0.05,
    )

    assert result.status == "COMPLETED"
    assert len(client.heartbeats) >= 2
    assert len(client.completions) == 1


def test_consumer_should_not_complete_when_background_heartbeat_loses_lease() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command
    from app.execution_control import ExecutionStopped

    stale_observed = Event()

    class LosingLeaseClient(FakeAgentClient):
        def heartbeat(self, claim: AgentTaskClaim, *, lease_seconds: int = 60) -> AgentTaskClaim:
            self.heartbeats.append(claim.agent_task_id)
            if len(self.heartbeats) == 1:
                return claim
            stale_observed.set()
            raise AgentTaskApiError(409, "RESEARCH_AGENT_TASK_STALE_LEASE")

    client = LosingLeaseClient()

    def slow_executor(_command, claim):
        assert stale_observed.wait(timeout=1.0)
        return _completion(claim)

    with pytest.raises(ExecutionStopped, match="STALE_LEASE"):
        handle_research_agent_command(
            _command(),
            client,
            slow_executor,
            worker_instance_id="worker-a",
            lease_seconds=4,
            heartbeat_interval_seconds=0.05,
        )

    assert len(client.heartbeats) >= 2
    assert client.completions == []


def test_drain_after_active_completion_should_commit_without_polling_or_claiming_next_message() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands
    from app.drain_control import DrainCoordinator

    coordinator = DrainCoordinator(start_watchdog=False)

    class OneMessageThenForbiddenPoll:
        def __init__(self) -> None:
            self.next_calls = 0
            self.commit_count = 0

        def __iter__(self):
            return self

        def __next__(self):
            self.next_calls += 1
            if self.next_calls == 1:
                return _FakeKafkaMessage(_command())
            raise AssertionError("drain must stop before the next poll")

        def commit(self) -> None:
            self.commit_count += 1

    source = OneMessageThenForbiddenPoll()
    client = FakeAgentClient()

    def executor(_command, claim):
        coordinator.request_drain(grace_seconds=30)
        return _completion(claim)

    summary = consume_research_agent_commands(
        source,
        client,
        executor,
        worker_instance_id="worker-a",
        drain_coordinator=coordinator,
    )

    assert summary.completed_count == 1
    assert source.next_calls == 1
    assert source.commit_count == 1
    assert client.claims == [_TASK_ID]
    assert len(client.completions) == 1


def test_drain_grace_expiry_should_not_complete_or_commit_even_if_executor_returns() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands
    from app.drain_control import DrainCoordinator, DrainGraceExpired

    clock = [100.0]
    coordinator = DrainCoordinator(clock=lambda: clock[0], start_watchdog=False)
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(_command())])
    client = FakeAgentClient()

    def executor(_command, claim):
        coordinator.request_drain(grace_seconds=5)
        clock[0] = 105.0
        assert coordinator.expire_grace() == 1
        return _completion(claim)

    with pytest.raises(DrainGraceExpired):
        consume_research_agent_commands(
            consumer,
            client,
            executor,
            worker_instance_id="worker-a",
            drain_coordinator=coordinator,
        )

    assert client.completions == []
    assert consumer.commit_count == 0


def test_consumer_should_bind_atomic_completion_to_claim_snapshot_digest() -> None:
    from app.agent_kafka_consumer import handle_research_agent_command

    client = FakeAgentClient()
    with pytest.raises(RuntimeError, match="claim identity"):
        handle_research_agent_command(
            _command(),
            client,
            lambda _command, claim: _completion(claim, snapshot_digest="sha256:" + "7" * 64),
            worker_instance_id="worker-a",
        )
    assert client.completions == []


def test_consumer_should_reuse_one_immutable_completion_across_transient_complete_retry() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    class ResponseLossClient(FakeAgentClient):
        def complete(self, completion) -> AgentCompletionReceipt:
            self.completions.append(completion)
            if len(self.completions) == 1:
                raise AgentTaskApiError(503, "RESEARCH_AGENT_API_UNAVAILABLE")
            return AgentCompletionReceipt(
                schema_version="research-agent-completion-receipt.v1",
                completion_id="completion-db-1", execution_id="execution-db-1", task_id=completion.task_id,
                completion_digest=completion.envelope_digest, receipt_digest="sha256:" + "9" * 64,
                idempotent_replay=True,
            )

    client = ResponseLossClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(_command())])
    executor_calls = 0

    def executor(_command, claim):
        nonlocal executor_calls
        executor_calls += 1
        return _completion(claim)

    summary = consume_research_agent_commands(
        consumer, client, executor, worker_instance_id="worker-a", max_attempts=2
    )

    assert summary.completed_count == 1
    assert executor_calls == 1
    assert client.claims == [_TASK_ID]
    assert len(client.completions) == 2
    assert client.completions[0] is client.completions[1]
    assert client.completions[0].envelope_digest == client.completions[1].envelope_digest
    assert consumer.commit_count == 1


def test_agent_consumer_should_dead_letter_contract_poison_before_claim_then_commit() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    client = FakeAgentClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage('{"schema_version":"research-agent-command.v1","unknown":true}')])
    dlq = _RecordingDlq()

    summary = consume_research_agent_commands(
        consumer, client, lambda *_: None, worker_instance_id="worker-a", dead_letter_sink=dlq
    )

    assert summary.dead_letter_count == 1
    assert summary.completed_count == 0
    assert client.claims == []
    assert consumer.commit_count == 1
    assert dlq.records[0]["retryable"] is False


def test_agent_consumer_should_redact_credentials_from_dead_letter_payload() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    secret = "super-secret-agent-token"
    poison = json.dumps({
        "schema_version": "research-agent-command.v1",
        "authorization": f"Bearer {secret}",
        "api_key": secret,
        "unknown": True,
    })
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(poison)])
    dlq = _RecordingDlq()

    consume_research_agent_commands(
        consumer, FakeAgentClient(), lambda *_: None,
        worker_instance_id="worker-a", dead_letter_sink=dlq,
    )

    serialized_record = json.dumps(dlq.records[0], sort_keys=True)
    assert secret not in serialized_record
    assert "[REDACTED]" in serialized_record


def test_agent_consumer_should_not_commit_poison_message_when_dlq_fails() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    client = FakeAgentClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage("not-json")])
    with pytest.raises(DeadLetterPublishError):
        consume_research_agent_commands(
            consumer, client, lambda *_: None, worker_instance_id="worker-a", dead_letter_sink=_RecordingDlq(False)
        )

    assert client.claims == []
    assert consumer.commit_count == 0


def test_agent_consumer_should_retry_transient_executor_failure_then_complete_and_commit() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    client = FakeAgentClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(_command())])
    attempts = 0

    def flaky_executor(_command, claim):
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise TimeoutError("provider timeout")
        return _completion(claim)

    summary = consume_research_agent_commands(
        consumer, client, flaky_executor, worker_instance_id="worker-a", max_attempts=2
    )

    assert attempts == 2
    assert summary.completed_count == 1
    assert summary.failed_attempt_count == 1
    assert client.claims == [_TASK_ID]
    assert client.heartbeats == [_TASK_ID]
    assert consumer.commit_count == 1
    assert len(client.completions) == 1


def test_agent_consumer_should_commit_normal_claim_race_without_dlq_or_complete() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    class NotClaimableClient(FakeAgentClient):
        def claim(self, task_id: str, *, lease_seconds: int = 60) -> AgentTaskClaim:
            self.claims.append(task_id)
            raise AgentTaskNotClaimableError("RESEARCH_AGENT_TASK_NOT_CLAIMABLE")

    client = NotClaimableClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(_command())])
    dlq = _RecordingDlq()
    summary = consume_research_agent_commands(
        consumer, client, lambda *_: None, worker_instance_id="worker-a", dead_letter_sink=dlq
    )

    assert summary.skipped_count == 1
    assert summary.dead_letter_count == 0
    assert client.completions == []
    assert consumer.commit_count == 1


def test_agent_consumer_should_project_valid_dlq_failure_after_broker_ack_before_commit() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    client = FakeAgentClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(_command())])
    dlq = _RecordingDlq()
    reporter = _RecordingFailureReporter()

    def invalid_identity(_command, claim):
        completion = _completion(claim)
        return completion.model_copy(update={"fencing_token": claim.fencing_token + 1})

    summary = consume_research_agent_commands(
        consumer, client, invalid_identity, worker_instance_id="worker-a", dead_letter_sink=dlq, failure_reporter=reporter
    )

    assert summary.dead_letter_count == 1
    assert consumer.commit_count == 1
    assert len(reporter.calls) == 1
    assert reporter.calls[0][0].agent_task_id == _TASK_ID
    assert reporter.calls[0][1]["trace_digest"].startswith("sha256:")


def test_agent_consumer_should_not_commit_after_dlq_ack_when_failure_projection_fails() -> None:
    from app.agent_kafka_consumer import consume_research_agent_commands

    client = FakeAgentClient()
    consumer = _FakeKafkaConsumer([_FakeKafkaMessage(_command())])

    def invalid_identity(_command, claim):
        return _completion(claim).model_copy(update={"fencing_token": claim.fencing_token + 1})

    with pytest.raises(DeadLetterPublishError, match="projection failed"):
        consume_research_agent_commands(
            consumer, client, invalid_identity, worker_instance_id="worker-a", dead_letter_sink=_RecordingDlq(),
            failure_reporter=_RecordingFailureReporter(fails=True)
        )

    assert consumer.commit_count == 0


def test_agent_consumer_factory_should_use_dedicated_topic_and_group(monkeypatch) -> None:
    import sys
    import types
    from app import agent_kafka_consumer as module

    captured: dict[str, object] = {}

    class FakeSettings:
        kafka_research_agent_topic = "research.agent.command"
        kafka_bootstrap_servers = "kafka:9092"
        kafka_research_agent_group_id = "research-agent-worker"

    class FakeConsumer:
        def __init__(self, *topics, **kwargs) -> None:
            captured["topics"] = topics
            captured["kwargs"] = kwargs

    monkeypatch.setattr(module, "load_settings", lambda: FakeSettings())
    monkeypatch.setitem(sys.modules, "kafka", types.SimpleNamespace(KafkaConsumer=FakeConsumer))

    module.create_research_agent_kafka_consumer()

    assert captured["topics"] == ("research.agent.command",)
    assert captured["kwargs"]["group_id"] == "research-agent-worker"
    assert captured["kwargs"]["enable_auto_commit"] is False


def test_agent_consumer_factory_should_fail_closed_when_sasl_credentials_are_missing(monkeypatch) -> None:
    import sys
    import types
    from app import agent_kafka_consumer as module

    class FakeSettings:
        kafka_research_agent_topic = "research.agent.command"
        kafka_bootstrap_servers = "kafka:9092"
        kafka_research_agent_group_id = "research-agent-worker"
        kafka_security_protocol = "SASL_PLAINTEXT"
        kafka_sasl_username = ""
        kafka_sasl_password = ""

    monkeypatch.setattr(module, "load_settings", lambda: FakeSettings())
    monkeypatch.setitem(sys.modules, "kafka", types.SimpleNamespace(KafkaConsumer=object))
    with pytest.raises(RuntimeError, match="SASL Kafka requires"):
        module.create_research_agent_kafka_consumer()


def test_agent_dead_letter_factory_should_use_dedicated_dlq_topic(monkeypatch) -> None:
    import sys
    import types
    from app import agent_kafka_consumer as module

    captured: dict[str, object] = {}

    class FakeSettings:
        kafka_bootstrap_servers = "kafka:9092"
        kafka_research_agent_dlq_topic = "research.agent.command.dlq"
        kafka_consume_max_attempts = 5

    class FakeProducer:
        def __init__(self, **kwargs) -> None:
            captured["kwargs"] = kwargs

    monkeypatch.setattr(module, "load_settings", lambda: FakeSettings())
    monkeypatch.setitem(sys.modules, "kafka", types.SimpleNamespace(KafkaProducer=FakeProducer))

    sink = module.create_research_agent_kafka_dead_letter_sink()

    assert sink.topic == "research.agent.command.dlq"
    assert captured["kwargs"]["retries"] == 5


def test_agent_consumer_forever_should_exit_cleanly_when_disabled(monkeypatch) -> None:
    from app import agent_kafka_consumer as module

    class FakeSettings:
        research_agent_consumer_enabled = False

    monkeypatch.setattr(module, "load_settings", lambda: FakeSettings())
    assert module.run_research_agent_kafka_consumer_forever(lambda *_: None) is None


def test_agent_consumer_should_not_create_default_deep_cell_executor_without_explicit_flag(monkeypatch) -> None:
    from app import agent_kafka_consumer as module

    class FakeSettings:
        research_agent_consumer_enabled = True
        research_agent_deep_cell_executor_enabled = False
        research_agent_worker_instance_id = "worker-a"
        java_base_url = "http://backend"
        internal_auth_token = "token"

    monkeypatch.setattr(module, "load_settings", lambda: FakeSettings())
    with pytest.raises(RuntimeError, match="DEEP_CELL executor is disabled"):
        module.run_research_agent_kafka_consumer_forever()


def test_completion_retry_setting_should_be_bounded_and_worker_scoped(monkeypatch) -> None:
    from pydantic import ValidationError
    from app.config import Settings

    assert Settings(research_agent_completion_max_attempts=1).research_agent_completion_max_attempts == 1
    assert Settings(research_agent_completion_max_attempts=10).research_agent_completion_max_attempts == 10
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_COMPLETION_MAX_ATTEMPTS", "4")
    assert Settings().research_agent_completion_max_attempts == 4
    with pytest.raises(ValidationError):
        Settings(research_agent_completion_max_attempts=0)
    with pytest.raises(ValidationError):
        Settings(research_agent_completion_max_attempts=11)


def test_worker_instance_id_should_be_unique_when_not_explicit(monkeypatch) -> None:
    from app.config import Settings

    monkeypatch.delenv("NOTEWEAVE_RESEARCH_AGENT_WORKER_INSTANCE_ID", raising=False)
    first = Settings().research_agent_worker_instance_id
    second = Settings().research_agent_worker_instance_id
    assert first != second
    assert first.startswith("research-agent-")
    assert second.startswith("research-agent-")


def test_production_worker_settings_should_require_secure_transports(monkeypatch) -> None:
    from pydantic import ValidationError
    from app.config import Settings

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN", "x" * 32)
    with pytest.raises(ValidationError, match="HTTPS"):
        Settings(environment="production", java_base_url="http://backend", internal_auth_token="x" * 32)
    with pytest.raises(ValidationError, match="Kafka SSL"):
        Settings(environment="production", java_base_url="https://backend", internal_auth_token="x" * 32,
                 kafka_security_protocol="SASL_PLAINTEXT")


def test_heartbeat_settings_should_preserve_lease_safety_margin() -> None:
    from pydantic import ValidationError
    from app.config import Settings

    settings = Settings(
        research_agent_lease_seconds=12,
        research_agent_heartbeat_interval_seconds=3,
        research_agent_heartbeat_failure_budget_seconds=5,
        research_agent_heartbeat_request_timeout_seconds=1,
    )
    assert settings.research_agent_heartbeat_interval_seconds == 3
    assert settings.research_agent_heartbeat_failure_budget_seconds == 5
    assert settings.research_agent_heartbeat_request_timeout_seconds == 1

    with pytest.raises(ValidationError, match="less than lease_seconds / 3"):
        Settings(research_agent_lease_seconds=12, research_agent_heartbeat_interval_seconds=4)
    with pytest.raises(ValidationError, match="safety margin"):
        Settings(
            research_agent_lease_seconds=12,
            research_agent_heartbeat_interval_seconds=3,
            research_agent_heartbeat_failure_budget_seconds=9,
        )
    with pytest.raises(ValidationError, match="less than heartbeat interval"):
        Settings(
            research_agent_lease_seconds=12,
            research_agent_heartbeat_interval_seconds=3,
            research_agent_heartbeat_request_timeout_seconds=3,
        )


def test_drain_grace_setting_should_be_bounded_and_worker_scoped(monkeypatch) -> None:
    from pydantic import ValidationError
    from app.config import Settings

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_DRAIN_GRACE_SECONDS", "45")
    assert Settings().research_agent_drain_grace_seconds == 45
    with pytest.raises(ValidationError):
        Settings(research_agent_drain_grace_seconds=0)
    with pytest.raises(ValidationError):
        Settings(research_agent_drain_grace_seconds=301)


def test_drain_poll_timeout_should_be_bounded_and_worker_scoped(monkeypatch) -> None:
    from pydantic import ValidationError
    from app.config import Settings

    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_DRAIN_POLL_TIMEOUT_MS", "250")
    assert Settings().research_agent_drain_poll_timeout_ms == 250
    with pytest.raises(ValidationError):
        Settings(research_agent_drain_poll_timeout_ms=49)
    with pytest.raises(ValidationError):
        Settings(research_agent_drain_poll_timeout_ms=5001)


def test_consumer_runner_should_turn_sigterm_into_drain_before_another_poll(monkeypatch) -> None:
    import signal
    from app import agent_kafka_consumer as module

    captured_handlers: dict[int, object] = {}

    class FakeSettings:
        research_agent_consumer_enabled = True
        research_agent_worker_instance_id = "worker-a"
        research_agent_deep_cell_executor_enabled = True
        java_base_url = "http://backend"
        internal_auth_token = "token"
        research_agent_completion_max_attempts = 3
        research_agent_lease_seconds = 60
        research_agent_heartbeat_interval_seconds = 10
        research_agent_heartbeat_failure_budget_seconds = 30
        research_agent_heartbeat_request_timeout_seconds = 2
        research_agent_drain_grace_seconds = 30
        research_agent_drain_poll_timeout_ms = 50
        kafka_consume_max_attempts = 1

    class PollingConsumer:
        def __init__(self) -> None:
            self.poll_calls = 0
            self.closed_with: object = None

        def poll(self, *, timeout_ms: int):
            self.poll_calls += 1
            if self.poll_calls == 1:
                captured_handlers[signal.SIGTERM](signal.SIGTERM, None)  # type: ignore[operator]
            return {}

        def close(self, *, autocommit: bool) -> None:
            self.closed_with = autocommit

    consumer = PollingConsumer()
    monkeypatch.setattr(module, "load_settings", lambda: FakeSettings())
    monkeypatch.setattr(module, "JavaResearchAgentTaskClient", lambda *_args, **_kwargs: object())
    monkeypatch.setattr(module, "create_research_agent_kafka_consumer", lambda: consumer)
    monkeypatch.setattr(module, "create_research_agent_kafka_dead_letter_sink", lambda: object())
    monkeypatch.setattr(module.signal, "signal", lambda sig, handler: captured_handlers.setdefault(sig, handler))

    module.run_research_agent_kafka_consumer_forever(lambda *_args: None)

    assert signal.SIGTERM in captured_handlers
    assert consumer.poll_calls == 1
    assert consumer.closed_with is False


def test_consumer_main_should_use_distinct_exit_code_after_drain_grace_expiry(monkeypatch) -> None:
    from app import agent_kafka_consumer as module
    from app.drain_control import DrainGraceExpired

    monkeypatch.setattr(module, "run_research_agent_kafka_consumer_forever", lambda: (_ for _ in ()).throw(DrainGraceExpired()))
    with pytest.raises(SystemExit) as exc:
        module.main()
    assert exc.value.code == 75


def test_consumer_forever_should_wire_atomic_completion_retry_setting_to_client(monkeypatch) -> None:
    from app import agent_kafka_consumer as module

    class FakeSettings:
        research_agent_consumer_enabled = True
        research_agent_worker_instance_id = "worker-a"
        java_base_url = "http://backend"
        internal_auth_token = "token"
        research_agent_completion_max_attempts = 7
        kafka_consume_max_attempts = 2
        research_agent_lease_seconds = 60
        research_agent_heartbeat_interval_seconds = 10
        research_agent_heartbeat_failure_budget_seconds = 30
        research_agent_heartbeat_request_timeout_seconds = 2

    captured: dict[str, object] = {}

    def client_factory(base_url, token, worker, *, completion_max_attempts, heartbeat_request_timeout_seconds):
        captured.update({
            "base_url": base_url, "token": token, "worker": worker,
            "completion_max_attempts": completion_max_attempts,
            "heartbeat_request_timeout_seconds": heartbeat_request_timeout_seconds,
        })
        return object()

    monkeypatch.setattr(module, "load_settings", lambda: FakeSettings())
    monkeypatch.setattr(module, "JavaResearchAgentTaskClient", client_factory)
    monkeypatch.setattr(module, "create_research_agent_kafka_consumer", lambda: [])
    monkeypatch.setattr(module, "create_research_agent_kafka_dead_letter_sink", lambda: object())
    monkeypatch.setattr(module, "consume_research_agent_commands", lambda *_args, **_kwargs: None)

    module.run_research_agent_kafka_consumer_forever(lambda *_args: None)

    assert captured["completion_max_attempts"] == 7
    assert captured["heartbeat_request_timeout_seconds"] == 2
