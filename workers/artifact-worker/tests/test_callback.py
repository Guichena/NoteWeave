from __future__ import annotations

import json
import hashlib
from io import BytesIO
from types import SimpleNamespace
import pytest
from PIL import Image

import app.callback as callback_module
from app.callback import (
    JavaArtifactCallbackClient,
    _dispatch_system_provider_operations,
    acknowledge_acquisition_operation_with_callbacks,
    resume_waiting_artifact_task_with_callbacks,
    run_artifact_task_with_callbacks,
    sanitize_error_message,
)
from app.acquisition_runtime import clear_acquisition_runtime, dispatch_acquisition_operation, list_acquisition_operations
from app.error_sanitizer import sanitize_error_fields
from app.artifact_repository import clear_artifact_repository, get_artifact_version_detail
from app.capability_approval_queue import clear_approval_requests
from app.capability_provider import (
    reset_capability_provider_approval_status,
    reset_capability_provider_discovery_status,
    reset_capability_provider_health_status,
    reset_capability_provider_status,
)
from app.capability_wait_queue import clear_waiting_tasks, get_waiting_task
from app.models import ArtifactProgressEvent, ArtifactTaskInput, ArtifactTaskResult
from app.runner import run_artifact_task
from app.video_subtitle_material import subtitle_only_bundle
from app.video_material_bundle import VideoMaterialBundleV1


def _build_resume_task_input() -> ArtifactTaskInput:
    return ArtifactTaskInput.model_validate(
        {
            "task_id": "task-a-callback",
            "workspace_id": "ws-1",
            "target_id": "artifact-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Callback Source",
                    "summary": "Controlled Agentic Graph Harness drives artifact generation.",
                }
            ],
            "context_snapshot": {"context_snapshot_id": "ctx-task-a-callback"},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": "resume_highlight",
                "task_neighborhood": "ARTIFACT_SKILL_RESUME_HIGHLIGHT",
                "style_constraints": ["Use action verbs and concise bullets."],
                "structure_constraints": ["Output a short resume-ready highlight list."],
                "terminology_policy": ["Keep key runtime terms in English."],
                "forbidden_patterns": [],
                "evidence_policy": ["Anchor every bullet to the provided source scope."],
                "interaction_policy": [],
                "review_checklist": ["Preserve skill-first naming."],
                "memory_object_ids": [],
            },
            "input_payload": {
                "skill_key": "resume_highlight",
                "action_key": "",
                "style_profile_key": "interview",
                "prompt_recipe_id": "",
                "context_snapshot_id": "ctx-task-a-callback",
                "user_requirement": "Emphasize Schema-Gated Skill Graph Runtime and Verifier / Repair.",
                "generation_brief": "Generate concise project highlights for a resume.",
                "inputs": {},
                "requested_capabilities": [],
                "writeback_mode": "NONE",
            },
        }
    )


def _build_waiting_task_input() -> ArtifactTaskInput:
    return ArtifactTaskInput.model_validate(
        {
            "task_id": "task-a-waiting",
            "workspace_id": "ws-1",
            "target_id": "artifact-2",
            "source_scope": [],
            "context_snapshot": {"context_snapshot_id": "ctx-task-a-waiting"},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": "bilibili_course_note_pdf",
                "task_neighborhood": "ARTIFACT_SKILL_BILIBILI_COURSE_NOTE_PDF",
                "style_constraints": [],
                "structure_constraints": ["Export the final result as a lecture note PDF."],
                "terminology_policy": [],
                "forbidden_patterns": [],
                "evidence_policy": ["Keep the note traceable to the transcript and screenshots."],
                "interaction_policy": [],
                "review_checklist": ["Preserve asynchronous provider waiting semantics."],
                "memory_object_ids": [],
            },
            "input_payload": {
                "skill_key": "bilibili_course_note_pdf",
                "action_key": "",
                "style_profile_key": "teaching",
                "prompt_recipe_id": "",
                "context_snapshot_id": "ctx-task-a-waiting",
                "user_requirement": "Wait for subtitle extraction and PDF compilation to finish asynchronously.",
                "generation_brief": "Generate a detailed lecture note PDF from the Bilibili video.",
                "inputs": {
                    "url": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                    "language": "zh-CN",
                },
                "requested_capabilities": [],
                "writeback_mode": "NONE",
            },
        }
    )


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


def test_video_material_client_publishes_and_reads_frozen_bundle(monkeypatch) -> None:
    bundle = subtitle_only_bundle(
        bundle_id="bundle-1", bundle_version=1, workspace_id="ws-1",
        bvid="BV1234567890", part=2, duration_ms=5000,
        input_digest="a" * 64, subtitle_source="MANUAL",
        srt_text="1\n00:00:00,000 --> 00:00:02,000\noriginal",
    )
    requests = []

    class FakeResponse:
        def __init__(self, data):
            self.data = data

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self):
            return json.dumps({"success": True, "data": self.data}).encode("utf-8")

    def fake_urlopen(req, timeout):
        requests.append(req)
        if req.get_method() == "GET":
            return FakeResponse(bundle.model_dump(mode="json"))
        return FakeResponse({
            "id": "material-1", "task_id": "task-1", "workspace_id": "ws-1",
            "bundle_id": bundle.bundle_id, "bundle_version": 1,
            "content_digest": bundle.content_digest(),
        })

    monkeypatch.setattr(callback_module, "credential_safe_urlopen", fake_urlopen)
    client = JavaArtifactCallbackClient("http://java-host:8081", delivery_token="delivery-1")
    receipt = client.publish_video_material("task-1", bundle)
    assert receipt["id"] == "material-1"
    assert client.fetch_video_material("task-1") == bundle
    assert [req.get_method() for req in requests] == ["POST", "GET"]
    assert all("/artifact-tasks/task-1/video-material" in req.full_url for req in requests)
    assert all(req.get_header("X-noteweave-outbox-delivery-token") == "delivery-1"
               for req in requests)
    assert json.loads(requests[0].data)["content_digest"] == bundle.content_digest()


def test_fetch_frozen_material_frames_checks_scope_media_type_and_bytes(monkeypatch) -> None:
    output = BytesIO()
    Image.new("RGB", (2, 2), "blue").save(output, format="PNG")
    image = output.getvalue()
    digest = hashlib.sha256(image).hexdigest()
    bundle = VideoMaterialBundleV1.model_validate({
        "bundle_id": "bundle-1", "bundle_version": 1, "workspace_id": "ws-1",
        "bvid": "BV1234567890", "part": 2, "duration_ms": 5000,
        "input_digest": "a" * 64, "subtitle_source": "NONE",
        "coverage_gaps": ["NO_SUBTITLE"],
        "files": [{"file_id": "frame1", "role": "VIDEO_FRAME", "media_type": "image/png",
                   "size_bytes": len(image), "checksum_sha256": digest}],
        "frames": [{"frame_id": "f1", "part": 2, "at_ms": 1000,
                    "file_id": "frame1", "checksum_sha256": digest}],
    })
    returned = {"bytes": image, "media_type": "image/png"}
    requests = []

    class FakeResponse:
        @property
        def headers(self):
            return {"Content-Type": returned["media_type"]}

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

        def read(self, limit):
            return returned["bytes"][:limit]

    def fake_urlopen(req, timeout):
        requests.append(req)
        return FakeResponse()

    monkeypatch.setattr(callback_module, "credential_safe_urlopen", fake_urlopen)
    client = JavaArtifactCallbackClient(
        "http://java-host:8081", internal_auth_token="internal-1", delivery_token="delivery-1",
    )
    assert client.fetch_video_material_files("task-1", bundle) == {"frame1": image}
    assert requests[0].full_url.endswith("/artifact-tasks/task-1/video-material/files/frame1")
    assert requests[0].get_header("X-noteweave-internal-token") == "internal-1"
    assert requests[0].get_header("X-noteweave-outbox-delivery-token") == "delivery-1"
    returned["bytes"] = b"wrong"
    with pytest.raises(ValueError, match="bytes do not match"):
        client.fetch_video_material_files("task-1", bundle)
    returned["bytes"] = image
    returned["media_type"] = "image/jpeg"
    with pytest.raises(ValueError, match="media type"):
        client.fetch_video_material_files("task-1", bundle)


def test_verified_provider_material_is_bound_to_candidate(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    subtitle = tmp_path / "part-2.srt"
    subtitle.write_text("1\n00:00:00,000 --> 00:00:02,000\nverified subtitle", encoding="utf-8")
    task = _build_waiting_task_input()
    task.input_snapshot_id = "snapshot-1"
    task.input_payload.inputs = {
        "url": "https://www.bilibili.com/video/BV1234567890?p=2", "language": "zh-CN",
    }
    payload = {"normalized_video_id": "BV1234567890", "metadata": {"duration": 5,
               "webpage_url": "https://www.bilibili.com/video/BV1234567890?p=2"},
               "acquisition_mode": "remote_cc_subtitle_fetch",
               "selected_subtitle_path": str(subtitle)}
    monkeypatch.setattr(callback_module, "list_acquisition_operations", lambda **_: [{
        "server_id": "builtin-bilibili-mcp", "operation_key": "EXTRACT_TRANSCRIPT",
        "request_id": "request-1",
    }])
    monkeypatch.setattr(callback_module, "get_acquisition_result_payload", lambda _: payload)
    published = []

    class FakeJavaClient(JavaArtifactCallbackClient):
        def publish_video_material(self, task_id, bundle):
            published.append(bundle)
            return {"id": "material-1", "task_id": task_id,
                    "workspace_id": bundle.workspace_id, "bundle_id": bundle.bundle_id,
                    "bundle_version": bundle.bundle_version,
                    "content_digest": bundle.content_digest()}

    result = SimpleNamespace(result_payload={"candidate": {}})
    callback_module._attach_frozen_video_material(
        task.task_id, task, result, FakeJavaClient("http://java-host:8081"))

    assert len(published) == 1
    assert published[0].part == 2
    assert result.result_payload["candidate"]["video_material"] == {
        "id": "material-1", "bundle_id": "video-material-task-a-waiting",
        "bundle_version": 1, "content_digest": published[0].content_digest(),
    }


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


def test_duplicate_acquisition_ack_should_retry_complete_delivery_after_transport_failure(
    monkeypatch,
) -> None:
    monkeypatch.setenv("NOTEWEAVE_ALLOW_PORTABLE_PDF_FALLBACK", "true")
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_discovery_status()
    reset_capability_provider_health_status()
    reset_capability_provider_approval_status()
    reset_capability_provider_status()

    try:
        client = FakeCallbackClient(
            _build_waiting_task_input(),
            should_fail_complete=True,
        )
        run_artifact_task_with_callbacks("task-a-waiting", client)
        transcript_operation = next(
            operation
            for operation in list_acquisition_operations(task_id="task-a-waiting")
            if operation["operation_key"] == "EXTRACT_TRANSCRIPT"
        )
        dispatch_response = dispatch_acquisition_operation(transcript_operation["request_id"])
        callback_token = dispatch_response["operation"]["callback_token"]
        provider_payload = {
            "subtitle_preview": [
                "Controlled Agentic Graph Harness unifies the artifact runtime.",
            ]
        }

        with pytest.raises(RuntimeError, match="complete callback unavailable"):
            acknowledge_acquisition_operation_with_callbacks(
                callback_token=callback_token,
                final_status="ACKNOWLEDGED",
                provider_payload=provider_payload,
                client=client,
            )

        waiting_record = get_waiting_task("task-a-waiting")
        assert waiting_record is not None
        cached_delivery = waiting_record["resume_delivery"]
        cached_version_id = cached_delivery["result"]["version_snapshot"]["version_id"]
        client.should_fail_complete = False
        retry_response = acknowledge_acquisition_operation_with_callbacks(
            callback_token=callback_token,
            final_status="ACKNOWLEDGED",
            provider_payload=provider_payload,
            client=client,
        )
    finally:
        clear_artifact_repository()
        clear_acquisition_runtime()
        clear_waiting_tasks()
        clear_approval_requests()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()

    assert len(retry_response.resumed_tasks) == 1
    assert retry_response.resumed_tasks[0].status == "COMPLETED"
    assert len(client.completed_results) == 1
    assert client.completed_results[0].version_snapshot.version_id == cached_version_id
    assert client.failures == []


def test_host_deferred_provider_ack_resumes_only_after_fenced_resume(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_ALLOW_PORTABLE_PDF_FALLBACK", "true")
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_discovery_status()
    reset_capability_provider_health_status()
    reset_capability_provider_approval_status()
    reset_capability_provider_status()
    try:
        client = FakeCallbackClient(_build_waiting_task_input())
        waiting = run_artifact_task_with_callbacks("task-a-waiting", client)
        operation = next(op for op in list_acquisition_operations(task_id="task-a-waiting")
                         if op["operation_key"] == "EXTRACT_TRANSCRIPT")
        token = dispatch_acquisition_operation(operation["request_id"])["operation"]["callback_token"]
        ack = acknowledge_acquisition_operation_with_callbacks(
            callback_token=token, final_status="ACKNOWLEDGED",
            provider_payload={"subtitle_preview": ["frozen provider result"]},
            client=client, defer_resume=True,
        )
        assert waiting.status == "WAITING_FOR_PROVIDER"
        assert ack.resumed_tasks == []
        assert get_waiting_task("task-a-waiting") is not None
        assert client.completed_results == []

        resumed = resume_waiting_artifact_task_with_callbacks(
            "task-a-waiting", client=client, request_id=operation["request_id"])
        assert resumed.status == "COMPLETED"
        assert len(client.completed_results) == 1
    finally:
        clear_artifact_repository()
        clear_acquisition_runtime()
        clear_waiting_tasks()
        clear_approval_requests()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()


def test_resumed_video_worker_publishes_full_subtitle_before_candidate_callback(
    tmp_path, monkeypatch,
) -> None:
    monkeypatch.setenv("NOTEWEAVE_ALLOW_PORTABLE_PDF_FALLBACK", "true")
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    monkeypatch.setattr(callback_module, "_dispatch_system_provider_operations", lambda *_: [])
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_discovery_status()
    reset_capability_provider_health_status()
    reset_capability_provider_approval_status()
    reset_capability_provider_status()
    subtitle = tmp_path / "part-2.srt"
    subtitle.write_text("1\n00:00:00,000 --> 00:00:02,000\ncontrolled transcript", encoding="utf-8")
    task = _build_waiting_task_input()
    task.input_snapshot_id = "snapshot-1"
    task.input_payload.inputs = {
        "url": "https://www.bilibili.com/video/BV1234567890?p=2", "language": "zh-CN",
    }

    class FakeJavaClient(JavaArtifactCallbackClient):
        def __init__(self):
            super().__init__("http://java-host:8081", delivery_token="delivery-1")
            self.material = None
            self.completed = None

        def fetch_task_input(self, task_id):
            return task

        def send_progress(self, task_id, event):
            pass

        def publish_video_material(self, task_id, bundle):
            self.material = bundle
            return {"id": "material-1", "task_id": task_id, "workspace_id": bundle.workspace_id,
                    "bundle_id": bundle.bundle_id, "bundle_version": bundle.bundle_version,
                    "content_digest": bundle.content_digest()}

        def send_complete(self, task_id, result):
            self.completed = result

    try:
        client = FakeJavaClient()
        assert run_artifact_task_with_callbacks(task.task_id, client).status == "WAITING_FOR_PROVIDER"
        operation = next(op for op in list_acquisition_operations(task_id=task.task_id)
                         if op["operation_key"] == "EXTRACT_TRANSCRIPT")
        token = dispatch_acquisition_operation(operation["request_id"])["operation"]["callback_token"]
        acknowledge_acquisition_operation_with_callbacks(
            callback_token=token, final_status="ACKNOWLEDGED", defer_resume=True,
            provider_payload={"normalized_video_id": "BV1234567890",
                              "metadata": {"duration": 5,
                                           "webpage_url": "https://www.bilibili.com/video/BV1234567890?p=2"},
                              "acquisition_mode": "remote_cc_subtitle_fetch",
                              "selected_subtitle_path": str(subtitle)},
            client=client,
        )
        assert resume_waiting_artifact_task_with_callbacks(
            task.task_id, client=client, request_id=operation["request_id"]
        ).status == "COMPLETED"
        assert client.material is not None
        assert client.material.part == 2
        assert client.completed.result_payload["candidate"]["video_material"]["id"] == "material-1"
    finally:
        clear_artifact_repository()
        clear_acquisition_runtime()
        clear_waiting_tasks()
        clear_approval_requests()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()


def test_run_artifact_task_with_callbacks_should_stop_at_waiting_progress_without_completing() -> None:
    client = FakeCallbackClient(_build_waiting_task_input())

    response = run_artifact_task_with_callbacks("task-a-waiting", client)

    assert response.status == "WAITING_FOR_PROVIDER"
    assert response.progress_events == 1
    assert client.fetched_task_ids == ["task-a-waiting"]
    assert client.completed_results == []
    assert client.failures == []
    wait_event = client.progress_events[0]
    assert wait_event.phase == "WAITING_FOR_PROVIDER"
    assert wait_event.payload["provider_job"]["provider_id"] == "builtin-bilibili-mcp"
    assert wait_event.payload["provider_job"]["operation_key"] == "EXTRACT_TRANSCRIPT"
    assert wait_event.payload["provider_job"]["request_id"].startswith("fetch-task-a-waiting-input-url-1-")
    assert wait_event.payload["provider_job"]["provider_receipt_id"].startswith(
        "provider-receipt-fetch-task-a-waiting-input-url-1-"
    )
    assert wait_event.payload["provider_job"]["provider_job_id"].startswith("provider-job-builtin-bilibili-mcp-")
    assert wait_event.payload["provider_job"]["delivery_id"].startswith(
        "acq-delivery-fetch-task-a-waiting-input-url-1-"
    )
    assert wait_event.payload["provider_job"]["callback_token"].startswith(
        "acq-callback-token-fetch-task-a-waiting-input-url-1-"
    )
    assert wait_event.payload["provider_job"]["provider_status"] == "AVAILABLE"
    assert wait_event.payload["provider_job"]["health_status"] == "HEALTHY"
    assert wait_event.payload["provider_job"]["provider_job_status"] == "WAITING_FOR_PROVIDER"
    assert wait_event.payload["provider_job"]["callback_status"] == "WAITING_FOR_PROVIDER"
    assert wait_event.payload["provider_job"]["dispatch_count"] == 1
    assert wait_event.payload["provider_job"]["previous_failed_delivery_count"] == 0
    assert wait_event.payload["provider_job"]["has_previous_failed_delivery"] is False
    assert wait_event.payload["provider_job"]["provider_delivery_attempts"][0]["delivery_id"].startswith(
        "acq-delivery-fetch-task-a-waiting-input-url-1-"
    )
    assert wait_event.payload["provider_job"]["provider_delivery_attempts"][0]["dispatch_count"] == 1
    assert wait_event.payload["provider_job"]["provider_delivery_attempts"][0]["ack_status"] == "PENDING"


def test_formal_callback_path_should_dispatch_system_bilibili_provider_after_waiting_progress(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _, result = run_artifact_task(_build_waiting_task_input())
    calls: list[tuple[str, str]] = []

    monkeypatch.setattr(
        callback_module,
        "submit_system_mcp_acquisition_operation",
        lambda request_id, java_base_url: calls.append((request_id, java_base_url))
        or {"request_id": request_id, "status": "DISPATCHED"},
    )

    dispatches = _dispatch_system_provider_operations(result, "http://java-host:8081")

    assert len(dispatches) == 1
    assert calls[0][0].startswith("fetch-task-a-waiting-input-url-1-")
    assert calls[0][1] == "http://java-host:8081"


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


def test_acquisition_ack_with_callbacks_should_resume_waiting_task_and_complete_to_java(
    monkeypatch,
) -> None:
    monkeypatch.setenv("NOTEWEAVE_ALLOW_PORTABLE_PDF_FALLBACK", "true")
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_discovery_status()
    reset_capability_provider_health_status()
    reset_capability_provider_approval_status()
    reset_capability_provider_status()

    try:
        client = FakeCallbackClient(_build_waiting_task_input())
        waiting_response = run_artifact_task_with_callbacks("task-a-waiting", client)
        transcript_operation = next(
            operation
            for operation in list_acquisition_operations(task_id="task-a-waiting")
            if operation["operation_key"] == "EXTRACT_TRANSCRIPT"
        )
        dispatch_response = dispatch_acquisition_operation(transcript_operation["request_id"])

        ack_response = acknowledge_acquisition_operation_with_callbacks(
            callback_token=dispatch_response["operation"]["callback_token"],
            final_status="ACKNOWLEDGED",
            result_locator=(
                "provider://builtin-bilibili-mcp/get_subtitle/"
                "fetch-task-a-waiting-input-url-1-extract_transcript"
            ),
            provider_payload={
                "title": "Bilibili Transcript",
                "plain_text": "Controlled Agentic Graph Harness unifies Action, Style, Graph and Runtime.",
                "segments": [
                    {
                        "segment_id": "seg-1",
                        "text": "Schema-Gated Skill Graph Runtime constrains the execution plan.",
                    }
                ],
                "source_refs": ["provider://builtin-bilibili-mcp/get_subtitle/demo"],
            },
            client=client,
        )
        completed_version = get_artifact_version_detail(
            target_id=client.completed_results[0].job_snapshot.target_id,
            version_id=client.completed_results[0].version_snapshot.version_id,
        )
    finally:
        clear_artifact_repository()
        clear_acquisition_runtime()
        clear_waiting_tasks()
        clear_approval_requests()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()

    assert waiting_response.status == "WAITING_FOR_PROVIDER"
    assert ack_response.operation["provider_job_status"] == "SUCCEEDED"
    assert ack_response.operation["callback_status"] == "ACKNOWLEDGED"
    assert ack_response.operation["provider_id"] == "builtin-bilibili-mcp"
    assert ack_response.operation["server_id"] == "builtin-bilibili-mcp"
    assert ack_response.operation["tool_name"] == "get_bilibili_subtitle"
    assert ack_response.operation["provider_status"] == "AVAILABLE"
    assert ack_response.operation["health_status"] == "HEALTHY"
    assert ack_response.operation["callback_token"].startswith("acq-callback-token-fetch-task-a-waiting-input-url-1-")
    assert ack_response.receipt["request_id"] == ack_response.operation["request_id"]
    assert ack_response.receipt["task_id"] == "task-a-waiting"
    assert ack_response.receipt["source_id"] == "input-url-1"
    assert ack_response.receipt["operation_key"] == "EXTRACT_TRANSCRIPT"
    assert (
        ack_response.receipt["result_locator"]
        == "provider://builtin-bilibili-mcp/get_subtitle/fetch-task-a-waiting-input-url-1-extract_transcript"
    )
    assert ack_response.receipt["completed_at"]
    assert ack_response.receipt["dispatch_count"] == 1
    assert len(ack_response.resumed_tasks) == 1
    assert ack_response.resumed_tasks[0].status == "COMPLETED"
    assert len(client.progress_events) == 6
    assert [event.phase for event in client.progress_events[1:]] == [
        "RESOLVING",
        "ACQUIRING",
        "COMPOSING",
        "VERIFYING",
        "EXPORTING",
    ]
    assert len(client.completed_results) == 1
    assert client.completed_results[0].job_snapshot.status == "COMPLETED"
    assert client.completed_results[0].result_payload["resume_scope"]["matched_operation_key"] == "EXTRACT_TRANSCRIPT"
    assert client.completed_results[0].result_payload["acquisition_callback_trace"]["status"] == "ATTACHED"
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["receipt"]["receipt_id"]
        == ack_response.receipt["receipt_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["receipt"]["delivery_id"]
        == ack_response.receipt["delivery_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["receipt"]["provider_job_id"]
        == ack_response.receipt["provider_job_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["receipt"]["request_id"]
        == ack_response.receipt["request_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["receipt"]["task_id"]
        == ack_response.receipt["task_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["receipt"]["source_id"]
        == ack_response.receipt["source_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["receipt"]["operation_key"]
        == ack_response.receipt["operation_key"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["operation"]["provider_id"]
        == ack_response.operation["provider_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["operation"]["server_id"]
        == ack_response.operation["server_id"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["operation"]["tool_name"]
        == ack_response.operation["tool_name"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["operation"]["callback_token"]
        == ack_response.operation["callback_token"]
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["operation"]["provider_status"]
        == "AVAILABLE"
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["operation"]["health_status"]
        == "HEALTHY"
    )
    assert (
        client.completed_results[0].result_payload["acquisition_callback_trace"]["operation"]["provider_job_status"]
        == "SUCCEEDED"
    )
    assert client.completed_results[0].result_payload["lifecycle_trace"]["current_phase"] == "EXPORTING"
    assert client.completed_results[0].result_payload["lifecycle_trace"]["steps"][0]["phase"] == "RESUMING"
    assert client.completed_results[0].result_payload["lifecycle_trace"]["steps"][0]["status"] == "COMPLETED"
    assert (
        client.completed_results[0].result_payload["lifecycle_trace"]["resume_scope"]["matched_request_id"]
        == client.completed_results[0].result_payload["resume_scope"]["matched_request_id"]
    )
    assert completed_version["runtime_trace"]["acquisition_callback_trace"]["status"] == "ATTACHED"
    assert (
        completed_version["runtime_trace"]["acquisition_callback_trace"]["receipt"]["receipt_id"]
        == ack_response.receipt["receipt_id"]
    )
    assert client.failures == []
