from __future__ import annotations

import json
import pytest

import app.callback as callback_module
from app.callback import JavaArtifactCallbackClient, run_artifact_task_with_callbacks, sanitize_error_message
from app.error_sanitizer import sanitize_error_fields
from app.models import ArtifactProgressEvent, ArtifactTaskInput, ArtifactTaskResult
from app.runner import run_artifact_task
from app.pipeline.skills import SkillCatalog
from tests.pipeline.test_pipeline import task


def _build_resume_task_input():
    value = task("briefing", SkillCatalog())
    value.task_id = "task-a-callback"
    return value


class FakeCallbackClient:
    def __init__(
        self,
        task_input: ArtifactTaskInput,
        should_fail_fetch: bool = False,
        should_fail_progress: bool = False,
        should_fail_complete: bool = False,
    ) -> None:
        self.task_input = task_input
        self.should_fail_fetch = should_fail_fetch
        self.should_fail_progress = should_fail_progress
        self.should_fail_complete = should_fail_complete
        self.fetched_task_ids: list[str] = []
        self.progress_events: list[ArtifactProgressEvent] = []
        self.completed_results: list[ArtifactTaskResult] = []
        self.failures: list[dict[str, str]] = []

    def fetch_task_input(self, task_id: str) -> ArtifactTaskInput:
        self.fetched_task_ids.append(task_id)
        if self.should_fail_fetch:
            raise RuntimeError("java input unavailable")
        return self.task_input

    def send_progress(self, task_id: str, event: ArtifactProgressEvent) -> None:
        if self.should_fail_progress:
            raise RuntimeError("java progress callback unavailable")
        self.progress_events.append(event)

    def send_complete(self, task_id: str, result: ArtifactTaskResult) -> None:
        if self.should_fail_complete:
            raise RuntimeError("java complete callback unavailable")
        self.completed_results.append(result)

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str) -> None:
        self.failures.append(
            {
                "task_id": task_id,
                "phase": phase,
                "error_code": error_code,
                "error_message": error_message,
            }
        )


def test_java_artifact_callback_client_should_send_internal_auth_token(monkeypatch) -> None:
    captured_headers: dict[str, str] = {}

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self) -> bytes:
            return b'{"success":true,"data":{}}'

    def fake_urlopen(req, timeout):
        captured_headers.update({key.lower(): value for key, value in req.header_items()})
        return FakeResponse()

    monkeypatch.setattr(callback_module, "credential_safe_urlopen", fake_urlopen)
    client = JavaArtifactCallbackClient(
        "http://java-host:8081",
        "shared-secret",
        "artifact-callback-secret",
        "delivery-token-1",
    )

    client._request("GET", "/internal/worker/artifact-outbox/metrics")

    assert captured_headers["x-noteweave-internal-token"] == "shared-secret"


def test_java_artifact_callback_client_should_send_stable_callback_idempotency_keys(
    monkeypatch,
) -> None:
    captured_headers: list[dict[str, str]] = []

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self) -> bytes:
            return b'{"success":true,"data":{}}'

    def fake_urlopen(req, timeout):
        captured_headers.append({key.lower(): value for key, value in req.header_items()})
        return FakeResponse()

    monkeypatch.setattr(callback_module, "credential_safe_urlopen", fake_urlopen)
    client = JavaArtifactCallbackClient(
        "http://java-host:8081",
        "shared-secret",
        "artifact-callback-secret",
        "delivery-token-1",
    )
    event = ArtifactProgressEvent(
        phase="COMPOSING",
        progress_percent=60,
        message="sections generated",
    )
    _, result = run_artifact_task(_build_resume_task_input())

    client.send_progress("task-a-callback", event)
    client.send_progress("task-a-callback", event)
    client.send_complete("task-a-callback", result)
    client.send_fail("task-a-callback", "WORKER_EXECUTION", "VALUEERROR", "invalid result")

    keys = [headers["x-noteweave-idempotency-key"] for headers in captured_headers]
    assert keys[0] == keys[1]
    assert len(set(keys[1:])) == 3
    assert all(key.startswith("artifact-worker-") for key in keys)
    assert all(
        headers["x-noteweave-outbox-delivery-token"] == "delivery-token-1"
        for headers in captured_headers
    )
    callback_tokens = [
        headers["x-noteweave-task-callback-token"] for headers in captured_headers
    ]
    assert len(set(callback_tokens)) == 1


def test_failure_callback_should_redact_secrets_and_classify_retryability(monkeypatch) -> None:
    captured_payload: dict[str, object] = {}

    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self) -> bytes:
            return b'{"success":true,"data":{}}'

    def fake_urlopen(req, timeout):
        captured_payload.update(json.loads(req.data.decode("utf-8")))
        return FakeResponse()

    monkeypatch.setattr(callback_module, "credential_safe_urlopen", fake_urlopen)
    client = JavaArtifactCallbackClient("http://java-host:8081", "shared-secret")
    client.send_fail(
        "task-a-callback",
        "WORKER_EXECUTION",
        "VALUEERROR",
        "Authorization: Bearer secret-value",
        retryable=False,
    )

    assert captured_payload["retryable"] is False
    assert captured_payload["error_message"] == "Authorization: Bearer [REDACTED]"
    assert sanitize_error_message("api_key=abc token=xyz") == "api_key=[REDACTED] token=[REDACTED]"
    header_message = sanitize_error_message(
        "Authorization: Basic dXNlcjpwYXNz\n"
        "Proxy-Authorization: Digest private-response\n"
        "Cookie: session=private-cookie; Path=/\n"
        "Set-Cookie: refresh=private-refresh; HttpOnly"
    )
    assert "dXNlcjpwYXNz" not in header_message
    assert "private-response" not in header_message
    assert "private-cookie" not in header_message
    assert "private-refresh" not in header_message
    assert header_message.count("[REDACTED]") == 4
    nested = sanitize_error_fields({
        "error_message": "password=secret at C:\\private\\worker.log",
        "attempts": [{"last_error_message": "token=another-secret"}],
    })
    assert "secret" not in str(nested)
    assert "[PATH_REDACTED]" in str(nested)


def test_run_artifact_task_with_callbacks_should_fetch_emit_progress_and_complete() -> None:
    client = FakeCallbackClient(_build_resume_task_input())

    response = run_artifact_task_with_callbacks("task-a-callback", client)

    assert response.status == "COMPLETED"
    assert response.progress_events == 5
    assert client.fetched_task_ids == ["task-a-callback"]
    assert [event.phase for event in client.progress_events] == [
        "RESOLVING",
        "ACQUIRING",
        "COMPOSING",
        "VERIFYING",
        "EXPORTING",
    ]
    assert len(client.completed_results) == 1
    assert client.completed_results[0].job_snapshot.status == "COMPLETED"
    assert client.failures == []


def test_run_artifact_task_with_callbacks_should_not_report_input_transport_failure_as_execution_failure() -> None:
    client = FakeCallbackClient(_build_resume_task_input(), should_fail_fetch=True)

    with pytest.raises(RuntimeError):
        run_artifact_task_with_callbacks("task-a-callback", client)

    assert client.completed_results == []
    assert client.failures == []


def test_run_artifact_task_with_callbacks_should_report_generation_failures_to_java(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = FakeCallbackClient(_build_resume_task_input())
    monkeypatch.setattr(
        callback_module,
        "run_artifact_task",
        lambda task_input: (_ for _ in ()).throw(ValueError("generation failed")),
    )

    with pytest.raises(ValueError, match="generation failed"):
        run_artifact_task_with_callbacks("task-a-callback", client)

    assert client.failures[0]["phase"] == "WORKER_EXECUTION"
    assert client.failures[0]["error_code"] == "VALUEERROR"


@pytest.mark.parametrize("failure_point", ["progress", "complete"])
def test_run_artifact_task_with_callbacks_should_not_turn_callback_delivery_failure_into_task_failure(
    failure_point: str,
) -> None:
    client = FakeCallbackClient(
        _build_resume_task_input(),
        should_fail_progress=failure_point == "progress",
        should_fail_complete=failure_point == "complete",
    )

    with pytest.raises(RuntimeError, match="callback unavailable"):
        run_artifact_task_with_callbacks("task-a-callback", client)

    assert client.failures == []


@pytest.mark.parametrize(
    ("host_code", "reported"),
    [("ARTIFACT_CONTENT_IR_INVALID", True), ("WORKER_CALLBACK_DELIVERY_STALE", False)],
)
def test_run_artifact_task_with_callbacks_should_report_host_rejected_content_as_task_failure(
    host_code: str, reported: bool,
) -> None:
    class RejectingClient(FakeCallbackClient):
        def send_complete(self, task_id: str, result: ArtifactTaskResult) -> None:
            raise callback_module.ArtifactCallbackHttpError(
                409, '{"success":false,"code":"' + host_code + '","message":"rejected"}')

    client = RejectingClient(_build_resume_task_input())

    with pytest.raises(callback_module.ArtifactCallbackHttpError) as raised:
        run_artifact_task_with_callbacks("task-a-callback", client)

    # 内容被拒要落到失败终态；投递令牌过期属于旧投递，仍交给消费者跳过
    assert bool(getattr(raised.value, "artifact_failure_reported", False)) is reported
    if reported:
        assert client.failures[0]["phase"] == "WORKER_COMPLETION"
        assert client.failures[0]["error_code"] == host_code
    else:
        assert client.failures == []
