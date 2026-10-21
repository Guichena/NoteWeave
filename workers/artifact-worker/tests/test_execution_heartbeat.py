from __future__ import annotations

import json
import threading
import time

import pytest

import app.callback as callback_module
from app.artifact_kafka_consumer import consume_artifact_commands
from app.callback import (
    ArtifactCallbackHttpError,
    ArtifactCallbackTransportError,
    JavaArtifactCallbackClient,
    run_artifact_task_with_callbacks,
)
from app.config import Settings
from app.execution_heartbeat import ArtifactExecutionSuperseded, checkpoint_artifact_execution
from app.models import ArtifactJobSnapshot, ArtifactTaskInput, ArtifactTaskResult, ArtifactVersionSnapshot


def _task_input() -> ArtifactTaskInput:
    return ArtifactTaskInput.model_validate(
        {
            "task_id": "task-heartbeat",
            "workspace_id": "ws-1",
            "target_id": "artifact-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Heartbeat Source",
                    "summary": "Controlled runtime keeps the lease alive.",
                }
            ],
            "context_snapshot": {"context_snapshot_id": "ctx-heartbeat"},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": "resume_highlight",
                "task_neighborhood": "ARTIFACT_SKILL_RESUME_HIGHLIGHT",
                "style_constraints": [],
                "structure_constraints": [],
                "terminology_policy": [],
                "forbidden_patterns": [],
                "evidence_policy": [],
                "interaction_policy": [],
                "review_checklist": [],
                "memory_object_ids": [],
            },
            "input_payload": {
                "skill_key": "resume_highlight",
                "action_key": "",
                "style_profile_key": "interview",
                "prompt_recipe_id": "",
                "context_snapshot_id": "ctx-heartbeat",
                "user_requirement": "Keep the lease.",
                "generation_brief": "Heartbeat.",
                "inputs": {},
                "requested_capabilities": [],
                "writeback_mode": "NONE",
            },
        }
    )


def _result(task_id: str) -> ArtifactTaskResult:
    return ArtifactTaskResult(
        result_title="Heartbeat",
        result_payload={"markdown": "ok"},
        trace_summary="",
        job_snapshot=ArtifactJobSnapshot(
            task_id=task_id,
            workspace_id="ws-1",
            target_id="artifact-1",
            action_key="RESUME_HIGHLIGHT",
            status="COMPLETED",
        ),
        version_snapshot=ArtifactVersionSnapshot(
            version_id="",
            artifact_type="MARKDOWN",
            title="Heartbeat",
            status="DRAFT",
            summary="",
        ),
    )


class _HeartbeatClient:
    def __init__(self, heartbeat) -> None:
        self.task_input = _task_input()
        self.heartbeats: list[str] = []
        self.completed: list[str] = []
        self.failures: list[str] = []
        self._heartbeat = heartbeat

    def fetch_task_input(self, task_id: str) -> ArtifactTaskInput:
        return self.task_input

    def send_heartbeat(self, task_id: str, *, phase: str = "EXECUTING") -> dict[str, object]:
        self.heartbeats.append(task_id)
        self._heartbeat(task_id, phase)
        return {
            "success": True,
            "code": "OK",
            "data": {"task_id": task_id, "status": "HEARTBEAT_RECORDED", "result_ref": ""},
        }

    def send_progress(self, task_id: str, event: object) -> None:
        return None

    def send_complete(self, task_id: str, result: ArtifactTaskResult) -> None:
        self.completed.append(task_id)

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str, retryable: bool = False) -> None:
        self.failures.append(error_code)


def _enable_fast_heartbeat(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_HEARTBEAT_INTERVAL_SECONDS", "0.05")


def _stop_count(calls: list[str]) -> int:
    time.sleep(0.2)
    return len(calls)


def test_heartbeat_interval_defaults_to_60_seconds(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_HEARTBEAT_INTERVAL_SECONDS", raising=False)
    assert Settings().artifact_heartbeat_interval_seconds == 60
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_HEARTBEAT_INTERVAL_SECONDS", "15")
    assert Settings().artifact_heartbeat_interval_seconds == 15


def test_send_heartbeat_uses_the_host_callback_without_an_idempotency_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self) -> bytes:
            return json.dumps({
                "success": True,
                "data": {"task_id": "task-1", "status": "HEARTBEAT_RECORDED", "result_ref": ""},
            }).encode("utf-8")

    def fake_urlopen(req, timeout):
        captured["url"] = req.full_url
        captured["body"] = json.loads(req.data.decode("utf-8"))
        captured["delivery"] = req.get_header("X-noteweave-outbox-delivery-token")
        captured["callback"] = req.get_header("X-noteweave-task-callback-token")
        captured["idempotency"] = req.get_header("X-noteweave-idempotency-key")
        return FakeResponse()

    monkeypatch.setattr(callback_module, "credential_safe_urlopen", fake_urlopen)
    client = JavaArtifactCallbackClient(
        "http://java-host:8081", "internal-token", "callback-secret", "delivery-1",
    )
    client.send_heartbeat("task-1")

    assert captured["url"] == "http://java-host:8081/internal/worker/tasks/task-1/heartbeat"
    body = captured["body"]
    assert isinstance(body, dict)
    assert body["worker_type"] == "artifact"
    assert body["phase"] == "EXECUTING"
    assert body["worker_instance_id"]
    assert len(str(body["worker_instance_id"])) <= 120
    assert str(body["heartbeat_at"]).endswith("Z")
    assert captured["delivery"] == "delivery-1"
    assert captured["callback"]
    assert captured["idempotency"] is None


def test_heartbeat_is_called_during_execution_and_stops_afterwards(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _enable_fast_heartbeat(monkeypatch)
    seen = threading.Event()

    def record(task_id: str, phase: str) -> None:
        if len(client.heartbeats) >= 2:
            seen.set()

    client = _HeartbeatClient(record)

    def slow(task_input, **kwargs):
        assert seen.wait(2)
        checkpoint_artifact_execution()
        return [], _result(task_input.task_id)

    monkeypatch.setattr(callback_module, "run_artifact_task", slow)
    response = run_artifact_task_with_callbacks("task-heartbeat", client)

    assert response.status == "COMPLETED"
    assert len(client.heartbeats) >= 2
    assert client.completed == ["task-heartbeat"]
    assert client.failures == []
    stopped = len(client.heartbeats)
    assert _stop_count(client.heartbeats) == stopped


def test_heartbeat_stops_after_execution_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    _enable_fast_heartbeat(monkeypatch)
    seen = threading.Event()
    client = _HeartbeatClient(lambda task_id, phase: seen.set() if len(client.heartbeats) >= 2 else None)

    def slow(task_input, **kwargs):
        assert seen.wait(2)
        raise RuntimeError("generation failed")

    monkeypatch.setattr(callback_module, "run_artifact_task", slow)
    with pytest.raises(RuntimeError, match="generation failed"):
        run_artifact_task_with_callbacks("task-heartbeat", client)

    assert len(client.heartbeats) >= 2
    assert client.completed == []
    assert client.failures
    stopped = len(client.heartbeats)
    assert _stop_count(client.heartbeats) == stopped


def test_heartbeat_transport_failure_does_not_stop_the_task(monkeypatch: pytest.MonkeyPatch) -> None:
    _enable_fast_heartbeat(monkeypatch)
    seen = threading.Event()

    def fail(task_id: str, phase: str) -> None:
        if len(client.heartbeats) >= 2:
            seen.set()
        raise ArtifactCallbackTransportError("host unreachable")

    client = _HeartbeatClient(fail)

    def slow(task_input, **kwargs):
        assert seen.wait(2)
        checkpoint_artifact_execution()
        return [], _result(task_input.task_id)

    monkeypatch.setattr(callback_module, "run_artifact_task", slow)
    response = run_artifact_task_with_callbacks("task-heartbeat", client)

    assert response.status == "COMPLETED"
    assert len(client.heartbeats) >= 2
    assert client.completed == ["task-heartbeat"]
    assert client.failures == []


@pytest.mark.parametrize("code", ["WORKER_CALLBACK_DELIVERY_STALE", "WORKLOAD_LEASE_LOST"])
def test_stale_heartbeat_stops_the_task_and_abandons_the_result(
    monkeypatch: pytest.MonkeyPatch,
    code: str,
) -> None:
    _enable_fast_heartbeat(monkeypatch)
    finished = False

    def stale(task_id: str, phase: str) -> None:
        raise ArtifactCallbackHttpError(409, json.dumps({"success": False, "code": code}))

    client = _HeartbeatClient(stale)

    def slow(task_input, **kwargs):
        nonlocal finished
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            checkpoint_artifact_execution()
            time.sleep(0.01)
        finished = True
        return [], _result(task_input.task_id)

    monkeypatch.setattr(callback_module, "run_artifact_task", slow)
    with pytest.raises(ArtifactExecutionSuperseded, match="STALE" if "STALE" in code else code):
        run_artifact_task_with_callbacks("task-heartbeat", client)

    assert finished is False
    assert client.heartbeats
    assert client.completed == []
    assert client.failures == []


def test_cancelled_heartbeat_ack_stops_the_task_and_abandons_the_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _enable_fast_heartbeat(monkeypatch)
    calls: list[str] = []
    client = _HeartbeatClient(lambda task_id, phase: None)

    def wrapped(task_id: str, phase: str = "EXECUTING") -> dict[str, object]:
        calls.append(task_id)
        return {
            "success": True,
            "code": "OK",
            "data": {"task_id": task_id, "status": "CANCELLED", "result_ref": ""},
        }

    client.send_heartbeat = wrapped  # type: ignore[method-assign]

    def slow(task_input, **kwargs):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            checkpoint_artifact_execution()
            time.sleep(0.01)
        return [], _result(task_input.task_id)

    monkeypatch.setattr(callback_module, "run_artifact_task", slow)
    with pytest.raises(ArtifactExecutionSuperseded, match="CANCELLED"):
        run_artifact_task_with_callbacks("task-heartbeat", client)

    assert calls
    assert client.completed == []
    assert client.failures == []


class _Messages:
    def __init__(self) -> None:
        payload = json.dumps({
            "schema_version": "artifact-command.v1",
            "task_id": "task-1",
            "delivery_token": "delivery-1",
        }).encode("utf-8")
        self.values = [type("Message", (), {"value": payload})()]
        self.commit_count = 0

    def __iter__(self):
        return iter(self.values)

    def commit(self) -> None:
        self.commit_count += 1


def test_consumer_abandons_superseded_execution_without_retrying() -> None:
    messages = _Messages()

    def executor(task_id: str, *, delivery_token: str) -> None:
        raise ArtifactExecutionSuperseded(task_id, "WORKER_CALLBACK_DELIVERY_STALE")

    summary = consume_artifact_commands(messages, executor=executor)

    assert summary.skipped_count == 1
    assert summary.completed_count == 0
    assert messages.commit_count == 1
