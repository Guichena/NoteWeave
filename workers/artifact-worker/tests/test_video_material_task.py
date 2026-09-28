from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.video_material_bundle import frozen_video_input_digest
from app.video_material_task import VideoMaterialTaskInput
from app.runner import run_artifact_task
from app import callback as callback_module
from app.callback import ArtifactCallbackHttpError, JavaArtifactCallbackClient
from app.callback import ArtifactWorkerExecutionResponse
from app.models import ArtifactJobSnapshot, ArtifactTaskResult, ArtifactVersionSnapshot
from app.acquisition_runtime import clear_acquisition_runtime, list_acquisition_operations
from app.capability_wait_queue import clear_waiting_tasks


def input_record() -> dict[str, object]:
    return {
        "schema_version": "video-material-input.v1",
        "task_id": "material-task-1", "request_id": "parent-1",
        "workspace_id": "workspace-1", "input_snapshot_id": "parent-1",
        "template_version": "original-v1",
        "inputs": {
            "url": "https://www.bilibili.com/video/BV1234567890?p=2",
            "part": "2", "language": "zh-CN",
            "frame_density": "STANDARD", "asr_fallback": "ALLOW",
        },
    }


def test_parent_input_reuses_acquisition_graph_without_artifact_identity() -> None:
    parent = VideoMaterialTaskInput.model_validate(input_record())
    task = parent.to_acquisition_input()
    assert task.target_id == "parent-1"
    assert task.input_snapshot_id == "parent-1"
    assert task.input_payload.writeback_mode == "MATERIAL_ONLY"
    assert task.input_payload.skill_key == "bilibili_course_note_pdf"
    assert frozen_video_input_digest(task.input_snapshot_id, task.input_payload.inputs)


def test_material_input_rejects_mismatched_snapshot_and_policy() -> None:
    different = input_record()
    different["input_snapshot_id"] = "another-parent"
    with pytest.raises(ValidationError, match="snapshot"):
        VideoMaterialTaskInput.model_validate(different)
    invalid = input_record()
    invalid["inputs"] = {**invalid["inputs"], "frame_density": "UNLIMITED"}
    with pytest.raises(ValidationError, match="policy"):
        VideoMaterialTaskInput.model_validate(invalid)


def test_material_only_runner_does_not_generate_pdf_candidate(monkeypatch) -> None:
    monkeypatch.setattr("app.runner._resolve_async_provider_capabilities",
                        lambda **_kwargs: [])
    monkeypatch.setattr("app.runner.execute_skill_graph",
                        lambda *_args, **_kwargs: pytest.fail("Artifact generation was invoked"))
    _events, result = run_artifact_task(
        VideoMaterialTaskInput.model_validate(input_record()).to_acquisition_input())
    assert result.job_snapshot.status == "COMPLETED"
    assert "candidate" not in result.result_payload
    assert "markdown" not in result.result_payload
    assert result.result_payload["acquisition_receipt"]


def test_material_task_waits_for_frozen_transcript_before_generation() -> None:
    clear_acquisition_runtime()
    clear_waiting_tasks()
    try:
        task = VideoMaterialTaskInput.model_validate(input_record()).to_acquisition_input()
        _events, result = run_artifact_task(task)
        assert result.job_snapshot.status == "WAITING_FOR_PROVIDER"
        operations = list_acquisition_operations(task_id=task.task_id)
        assert any(item["operation_key"] == "EXTRACT_TRANSCRIPT" for item in operations)
        assert "candidate" not in result.result_payload
    finally:
        clear_acquisition_runtime()
        clear_waiting_tasks()


def test_material_callback_freezes_bundle_and_plan_without_pdf(monkeypatch) -> None:
    task = VideoMaterialTaskInput.model_validate(input_record()).to_acquisition_input()
    monkeypatch.setattr(callback_module, "list_acquisition_operations", lambda **_kwargs: [{
        "operation_key": "EXTRACT_TRANSCRIPT", "request_id": "subtitle-1",
    }])
    monkeypatch.setattr(callback_module, "get_acquisition_result_payload", lambda _request_id: {
        "normalized_video_id": "BV1234567890",
        "metadata": {"duration": 5,
                     "webpage_url": "https://www.bilibili.com/video/BV1234567890?p=2"},
        "acquisition_mode": "remote_cc_subtitle_fetch",
        "selected_subtitle_path": "frozen-subtitle.srt",
    })
    monkeypatch.setattr("app.video_subtitle_material.read_provider_text_in_sandbox",
                        lambda _path: "1\n00:00:00,000 --> 00:00:02,000\nControlled subtitle")

    class Client(JavaArtifactCallbackClient):
        def __init__(self) -> None:
            super().__init__("http://java-host:8081", delivery_token="delivery-1")
            self.bundle = None
            self.plan = None
            self.completed = None

        def publish_parent_material(self, task_id, bundle):
            self.bundle = bundle
            return {"id": "bundle-row-1", "task_id": task_id,
                    "content_digest": bundle.content_digest()}

        def publish_parent_plan(self, task_id, bundle_row_id, plan):
            plan.verify_against_bundle(self.bundle)
            self.plan = plan
            return {"id": "plan-row-1", "bundle_row_id": bundle_row_id,
                    "content_digest": plan.content_digest()}

        def complete_parent_material(self, task_id, bundle_row_id, plan_id):
            self.completed = (task_id, bundle_row_id, plan_id)

    result = ArtifactTaskResult(
        result_title="Video material", result_payload={}, trace_summary="material only",
        job_snapshot=ArtifactJobSnapshot(task_id=task.task_id,
            workspace_id=task.workspace_id, target_id=task.target_id,
            action_key="VIDEO_MATERIAL", status="COMPLETED"),
        version_snapshot=ArtifactVersionSnapshot(version_id="", artifact_type="VIDEO_MATERIAL",
            title="Video material", status="NOT_APPLICABLE", summary="No version"),
    )
    client = Client()
    response = callback_module._emit_material_callbacks_for_result(
        task.task_id, [], result, client, task)
    assert response.status == "COMPLETED"
    assert client.bundle.input_digest == frozen_video_input_digest(
        task.input_snapshot_id, task.input_payload.inputs)
    assert client.bundle.transcript_segments[0].corrected_text == "Controlled subtitle"
    assert client.plan is not None
    assert client.completed == (task.task_id, "bundle-row-1", "plan-row-1")


def test_waiting_material_resume_uses_material_completion_path(monkeypatch) -> None:
    task = VideoMaterialTaskInput.model_validate(input_record()).to_acquisition_input()
    result = ArtifactTaskResult(
        result_title="Video material", result_payload={}, trace_summary="material only",
        job_snapshot=ArtifactJobSnapshot(task_id=task.task_id,
            workspace_id=task.workspace_id, target_id=task.target_id,
            action_key="VIDEO_MATERIAL", status="COMPLETED"),
        version_snapshot=ArtifactVersionSnapshot(version_id="", artifact_type="VIDEO_MATERIAL",
            title="Video material", status="NOT_APPLICABLE", summary="No version"),
    )
    monkeypatch.setattr(callback_module, "get_waiting_task", lambda _task_id: {
        "task_input": task.model_dump(mode="json")})
    monkeypatch.setattr(callback_module, "wake_waiting_task", lambda *_args, **_kwargs: ([], result))
    monkeypatch.setattr(callback_module, "cache_waiting_task_delivery", lambda *_args: None)
    removed: list[str] = []
    monkeypatch.setattr(callback_module, "remove_waiting_task", removed.append)
    monkeypatch.setattr(callback_module, "_emit_callbacks_for_result",
                        lambda *_args: pytest.fail("Artifact completion path was invoked"))
    monkeypatch.setattr(callback_module, "_emit_material_callbacks_for_result",
                        lambda *_args: ArtifactWorkerExecutionResponse(
                            task_id=task.task_id, status="COMPLETED", progress_events=0))
    response = callback_module.resume_waiting_artifact_task_with_callbacks(
        task.task_id, client=JavaArtifactCallbackClient("http://java-host:8081"))
    assert response.status == "COMPLETED"
    assert removed == [task.task_id]


def test_content_conflict_reports_material_failure_before_consumer_skips_replay(monkeypatch) -> None:
    parent = VideoMaterialTaskInput.model_validate(input_record())

    class Client(JavaArtifactCallbackClient):
        def __init__(self) -> None:
            super().__init__("http://java-host:8081")
            self.failures: list[tuple[str, str]] = []

        def fetch_parent_material_input(self, task_id):
            return parent

        def fail_parent_material(self, task_id, error_code):
            self.failures.append((task_id, error_code))

    monkeypatch.setattr(callback_module, "run_artifact_task",
                        lambda _task: ([], object()))
    monkeypatch.setattr(callback_module, "_emit_material_callbacks_for_result",
                        lambda *_args: (_ for _ in ()).throw(
                            ArtifactCallbackHttpError(409, "invalid material")))
    client = Client()
    with pytest.raises(ArtifactCallbackHttpError) as failure:
        callback_module.run_video_material_task_with_callbacks(parent.task_id, client=client)
    assert getattr(failure.value, "artifact_failure_reported", False)
    assert client.failures == [(parent.task_id, "VIDEO_MATERIAL_WORKER_FAILED")]
