from __future__ import annotations

from types import SimpleNamespace

import pytest

import app.capability_wait_queue as capability_wait_queue

from app.artifact_repository import clear_artifact_repository, list_artifact_versions
from app.capability_resolver import resolve_capability_bindings
from app.capability_provider import (
    debug_capability_mapping_snapshot,
    debug_list_capability_providers,
    debug_provider_discovery_snapshot,
    debug_provider_health_snapshot,
    reset_capability_provider_discovery_status,
    reset_capability_provider_probe_results,
    reset_capability_provider_approval_status,
    reset_capability_provider_health_status,
    reset_capability_provider_status,
    run_capability_provider_discovery_scan,
    run_capability_provider_health_checks,
    set_capability_provider_discovery_status,
    set_capability_provider_approval_status,
    set_capability_provider_health_status,
    set_capability_provider_probe_result,
    set_capability_provider_status,
)
from app.capability_approval_queue import (
    approve_capability_request,
    clear_approval_requests,
    list_approval_requests,
)
from app.capability_wait_queue import (
    clear_waiting_tasks,
    enqueue_waiting_task,
    get_waiting_task,
    list_waiting_tasks,
    wake_waiting_task,
)
from app.main import (
    debug_approve_capability_request,
    debug_get_capability_approval_request_detail,
    debug_get_capability_mappings,
    debug_get_capability_mapping_detail,
    debug_get_custom_mcp_servers,
    debug_get_capability_provider_detail,
    debug_get_capability_provider_discovery,
    debug_register_custom_mcp_server,
    debug_register_custom_skill_graph,
    debug_reset_custom_mcp_servers,
    debug_reset_custom_skill_graphs,
    debug_get_waiting_task_detail,
    debug_list_waiting_tasks,
    debug_run_capability_provider_discovery,
    debug_run_capability_provider_health_checks,
    debug_wake_waiting_task,
)
from app.models import (
    CustomMcpServerRegistration,
    CustomMcpToolRegistration,
    CustomSkillGraphTemplateRegistration,
    SkillGraphEdge,
    SkillGraphNode,
)
from app.runner import run_artifact_task
from app.acquisition_runtime import clear_acquisition_runtime
from app.custom_mcp_executor import wait_for_custom_mcp_operation
from tests.test_runner import _build_media_task_input, _build_mixed_context_task_input


def _register_bilibili_render_pdf_mcp() -> None:
    debug_register_custom_mcp_server(
        CustomMcpServerRegistration.model_validate(
            {
                "server_id": "custom-bilibili-render-pdf",
                "display_name": "Bilibili Render PDF MCP",
                "server_notes": [
                    "Adapted from the bilibili-render-pdf skill through the custom MCP registry."
                ],
                "tools": [
                    CustomMcpToolRegistration(
                        capability_name="EXTRACT_TRANSCRIPT",
                        tool_name="get_bilibili_subtitle",
                        supported_routes=["VIDEO_URL"],
                        supported_actions=["VIDEO_SUMMARY", "COURSE_NOTES"],
                        preference_rank=320,
                        selection_reason_hint="custom_bilibili_render_pdf_subtitle",
                    ),
                    CustomMcpToolRegistration(
                        capability_name="TRANSCRIBE_AUDIO",
                        tool_name="transcribe_local_audio",
                        supported_routes=["VIDEO_FILE", "AUDIO_FILE"],
                        supported_actions=["AUDIO_MINUTES", "COURSE_NOTES"],
                        preference_rank=320,
                        selection_reason_hint="custom_bilibili_render_pdf_transcribe",
                    ),
                    CustomMcpToolRegistration(
                        capability_name="EXPORT_ARTIFACT_FILE",
                        tool_name="render_latex_pdf",
                        supported_routes=["VIDEO_URL", "VIDEO_FILE", "AUDIO_FILE"],
                        supported_actions=["COURSE_NOTES"],
                        preference_rank=320,
                        selection_reason_hint="custom_bilibili_render_pdf_export",
                    ),
                ],
            }
        )
    )


def test_capability_provider_catalog_should_expose_registered_providers() -> None:
    providers = debug_list_capability_providers()
    provider_detail = debug_get_capability_provider_detail(
        "EXTRACT_TRANSCRIPT:builtin-bilibili-mcp:get_subtitle"
    )

    assert providers
    transcript_providers = [
        provider for provider in providers if provider["capability_name"] == "EXTRACT_TRANSCRIPT"
    ]
    assert len(transcript_providers) >= 2
    transcript_provider = next(
        provider for provider in transcript_providers if provider["provider_id"] == "builtin-bilibili-mcp"
    )
    assert transcript_provider["tool_name"] == "get_subtitle"
    assert transcript_provider["discovery_status"] in {"REGISTERED", "DISCOVERED"}
    assert provider_detail.provider["provider_id"] == "builtin-bilibili-mcp"
    assert provider_detail.provider["tool_name"] == "get_subtitle"
    assert any(provider["capability_name"] == "TRANSCRIBE_AUDIO" for provider in providers)


def test_custom_mcp_server_registration_should_extend_provider_catalog_and_mapping() -> None:
    debug_reset_custom_mcp_servers()

    try:
        _register_bilibili_render_pdf_mcp()
        custom_servers = debug_get_custom_mcp_servers()
        providers = debug_list_capability_providers()
        mappings = debug_get_capability_mappings()
        provider_detail = debug_get_capability_provider_detail(
            "EXPORT_ARTIFACT_FILE:custom-bilibili-render-pdf:render_latex_pdf"
        )
        mapping_detail = debug_get_capability_mapping_detail(
            "EXTRACT_TRANSCRIPT:custom-bilibili-render-pdf:get_bilibili_subtitle"
        )
    finally:
        debug_reset_custom_mcp_servers()

    assert len(custom_servers.servers) == 1
    assert custom_servers.servers[0]["server_id"] == "custom-bilibili-render-pdf"
    assert custom_servers.servers[0]["launch_transport"] == "stdio"
    assert custom_servers.servers[0]["blueprint_key"] in {"", "bilibili_render_pdf_v1"}
    assert any(
        provider["provider_id"] == "custom-bilibili-render-pdf"
        and provider["capability_name"] == "EXTRACT_TRANSCRIPT"
        and provider["tool_name"] == "get_bilibili_subtitle"
        for provider in providers
    )
    assert any(
        provider["provider_id"] == "custom-bilibili-render-pdf"
        and provider["capability_name"] == "EXPORT_ARTIFACT_FILE"
        and provider["tool_name"] == "render_latex_pdf"
        for provider in providers
    )
    assert any(
        mapping["provider_id"] == "custom-bilibili-render-pdf"
        and mapping["capability_name"] == "EXTRACT_TRANSCRIPT"
        for mapping in mappings.snapshot["mappings"]
    )
    assert provider_detail.provider["mapping_source"] == "custom_mcp_registration"
    assert provider_detail.provider["endpoint_kind"] == "mcp"
    assert mapping_detail.mapping["server_id"] == "custom-bilibili-render-pdf"


def test_course_notes_should_select_bilibili_render_pdf_mcp_when_registered() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    debug_reset_custom_mcp_servers()
    task_input = _build_media_task_input("course_notes")
    task_input.source_scope[0].source_type = "URL"
    task_input.source_scope[0].source_uri = "https://www.bilibili.com/video/BV1NoteWeaveDemo"
    task_input.source_scope[0].summary = "B 站课程视频，使用 bilibili-render-pdf MCP 适配器生成课程笔记。"
    task_input.input_payload.writeback_mode = "EXPORT_FILE"

    try:
        _register_bilibili_render_pdf_mcp()
        _, result = run_artifact_task(task_input)
    finally:
        debug_reset_custom_mcp_servers()
        clear_waiting_tasks()
        clear_approval_requests()
        clear_acquisition_runtime()

    resolved_binding_map = {
        item["capability_name"]: item
        for item in result.result_payload["capability_resolution"]["resolved_bindings"]
    }
    provider_binding_map = {
        item["capability_name"]: item
        for item in result.result_payload["capability_resolution"]["provider_bindings"]
    }
    transcript_operation = next(
        operation
        for operation in result.result_payload["acquisition_receipt"]["source_receipts"][0]["operations"]
        if operation["operation_key"] == "EXTRACT_TRANSCRIPT"
    )
    capability_decisions = {
        item["capability_name"]: item
        for item in result.result_payload["capability_union_trace"]["capability_decisions"]
    }

    assert resolved_binding_map["EXTRACT_TRANSCRIPT"]["server_id"] == "custom-bilibili-render-pdf"
    assert resolved_binding_map["EXTRACT_TRANSCRIPT"]["tool_name"] == "get_bilibili_subtitle"
    assert (
        resolved_binding_map["EXTRACT_TRANSCRIPT"]["selection_reason"]
        == "custom_bilibili_render_pdf_subtitle"
    )
    assert resolved_binding_map["EXPORT_ARTIFACT_FILE"]["server_id"] == "custom-bilibili-render-pdf"
    assert resolved_binding_map["EXPORT_ARTIFACT_FILE"]["tool_name"] == "render_latex_pdf"
    assert (
        resolved_binding_map["EXPORT_ARTIFACT_FILE"]["selection_reason"]
        == "custom_bilibili_render_pdf_export"
    )
    assert provider_binding_map["EXTRACT_TRANSCRIPT"]["provider_id"] == "custom-bilibili-render-pdf"
    assert provider_binding_map["EXPORT_ARTIFACT_FILE"]["provider_id"] == "custom-bilibili-render-pdf"
    assert transcript_operation["provider_id"] == "custom-bilibili-render-pdf"
    assert transcript_operation["tool_name"] == "get_bilibili_subtitle"
    assert transcript_operation["selection_reason"] == "custom_bilibili_render_pdf_subtitle"
    assert capability_decisions["EXTRACT_TRANSCRIPT"]["server_id"] == "custom-bilibili-render-pdf"
    assert capability_decisions["EXPORT_ARTIFACT_FILE"]["server_id"] == "custom-bilibili-render-pdf"
    assert result.result_payload["capability_union_trace"]["status"] == "ALLOW"
    assert result.job_snapshot.status == "WAITING_FOR_PROVIDER"
    assert result.result_payload["acquisition_receipt"]["status"] == "WAITING_FOR_PROVIDER"
    assert result.result_payload["acquisition_receipt"]["execution_mode"] == "CUSTOM_MCP_ASYNC_RUNTIME"
    assert result.result_payload["acquisition_runtime_dispatches"]


def test_custom_bilibili_mcp_should_auto_resume_after_async_subtitle_fetch() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    clear_acquisition_runtime()
    debug_reset_custom_mcp_servers()
    task_input = _build_media_task_input("course_notes")
    task_input.source_scope[0].source_type = "URL"
    task_input.source_scope[0].source_uri = "https://www.bilibili.com/video/BV1NoteWeaveDemo"
    task_input.source_scope[0].summary = "waiting for remote subtitle fetch"

    try:
        _register_bilibili_render_pdf_mcp()
        from app import custom_mcp_executor

        original_call = custom_mcp_executor._call_custom_mcp_tool

        def _fake_call_custom_mcp_tool(server: object, operation: dict[str, object]) -> dict[str, object]:
            return {
                "video_url": operation["input_locator"],
                "acquisition_mode": "remote_cc_subtitle_fetch",
                "selected_subtitle_path": "D:/mock/subtitle.srt",
                "subtitle_files": ["D:/mock/subtitle.srt"],
                "subtitle_preview": [
                    "Controlled Agentic Graph Harness 负责统一主链路。",
                    "Schema-Gated Skill Graph Runtime 约束执行计划。",
                    "Capability Union Policy 控制自定义 MCP 风险。",
                ],
                "notes": ["mocked async subtitle fetch"],
            }

        custom_mcp_executor._call_custom_mcp_tool = _fake_call_custom_mcp_tool
        _, waiting_result = run_artifact_task(task_input)
        request_id = waiting_result.result_payload["wait_queue_entry"]["blocked_operations"][0]["request_id"]
        operation = wait_for_custom_mcp_operation(request_id, timeout=5.0)
    finally:
        try:
            custom_mcp_executor._call_custom_mcp_tool = original_call
        except Exception:
            pass
        debug_reset_custom_mcp_servers()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert waiting_result.job_snapshot.status == "WAITING_FOR_PROVIDER"
    assert operation["callback_status"] == "ACKNOWLEDGED"
    assert operation["status"] == "COMPLETED"
    assert get_waiting_task(waiting_result.job_snapshot.task_id) is None
    versions = list_artifact_versions(target_id=waiting_result.job_snapshot.target_id)
    assert versions


def test_capability_provider_health_snapshot_should_reflect_overrides() -> None:
    reset_capability_provider_health_status()
    set_capability_provider_health_status("EXTRACT_TRANSCRIPT", "DEGRADED")

    try:
        snapshot = debug_provider_health_snapshot()
        provider_detail = debug_get_capability_provider_detail(
            "EXTRACT_TRANSCRIPT:builtin-bilibili-mcp:get_subtitle"
        )
    finally:
        reset_capability_provider_health_status()

    provider = next(item for item in snapshot["providers"] if item["capability_name"] == "EXTRACT_TRANSCRIPT")
    assert provider["health_status"] == "DEGRADED"
    assert provider_detail.provider["health_status"] == "DEGRADED"


def test_capability_provider_health_checks_should_update_probe_status_and_timestamp() -> None:
    reset_capability_provider_status()
    reset_capability_provider_health_status()
    reset_capability_provider_probe_results()
    set_capability_provider_probe_result("EXTRACT_TRANSCRIPT", "DOWN")

    try:
        snapshot = run_capability_provider_health_checks(["EXTRACT_TRANSCRIPT"])
    finally:
        reset_capability_provider_probe_results()

    provider = next(item for item in snapshot["providers"] if item["capability_name"] == "EXTRACT_TRANSCRIPT")
    assert provider["health_status"] == "DOWN"
    assert provider["provider_status"] == "UNAVAILABLE"
    assert provider["last_checked_at"]


def test_run_artifact_task_should_wait_for_missing_capability() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")

    try:
        events, result = run_artifact_task(_build_media_task_input("video_summary"))
    finally:
        reset_capability_provider_status()
        clear_approval_requests()

    assert events[-1].phase == "WAITING_FOR_CAPABILITY"
    assert result.job_snapshot.status == "WAITING_FOR_CAPABILITY"
    assert result.version_snapshot.status == "WAITING_FOR_CAPABILITY"
    assert result.result_payload["lifecycle_trace"]["status"] == "WAITING_FOR_CAPABILITY"
    assert result.result_payload["lifecycle_trace"]["current_phase"] == "WAITING_FOR_CAPABILITY"
    assert result.result_payload["lifecycle_trace"]["steps"][0]["phase"] == "WAITING_FOR_CAPABILITY"
    assert result.result_payload["lifecycle_trace"]["steps"][0]["status"] == "WAITING_FOR_CAPABILITY"
    assert result.result_payload["capability_resolution"]["unavailable_capabilities"] == [
        "EXTRACT_TRANSCRIPT"
    ]
    assert result.result_payload["capability_resolution"]["provider_bindings"][1]["provider_status"] == "UNAVAILABLE"
    assert result.result_payload["wait_queue_entry"]["task_id"] == result.job_snapshot.task_id
    assert "artifact_commit" not in result.result_payload
    assert list_artifact_versions(target_id=result.job_snapshot.target_id) == []
    assert list_waiting_tasks()


def test_waiting_task_should_resume_after_provider_recovers() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_probe_results()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")
    task_input = _build_media_task_input("video_summary")

    try:
        _, waiting_result = run_artifact_task(task_input)
        waiting_task = get_waiting_task(waiting_result.job_snapshot.task_id)

        assert waiting_task is not None
        assert waiting_task["status"] == "WAITING_FOR_CAPABILITY"

        set_capability_provider_probe_result("EXTRACT_TRANSCRIPT", "HEALTHY")
        snapshot = run_capability_provider_health_checks(["EXTRACT_TRANSCRIPT"])
    finally:
        reset_capability_provider_status()
        reset_capability_provider_probe_results()
        clear_waiting_tasks()
        clear_approval_requests()

    assert snapshot["resumed_tasks"]
    assert snapshot["resumed_tasks"][0]["task_id"] == waiting_result.job_snapshot.task_id
    assert snapshot["resumed_tasks"][0]["last_phase"] == "EXPORTING"
    assert snapshot["resumed_tasks"][0]["result_status"] == "COMPLETED"
    assert list_artifact_versions(target_id=waiting_result.job_snapshot.target_id)
    assert get_waiting_task(waiting_result.job_snapshot.task_id) is None


def test_capability_batch_wake_continues_after_concurrent_claim(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    clear_waiting_tasks()
    removed_task = _build_media_task_input("course_notes")
    claimed_task = _build_media_task_input("video_summary")
    ready_task = _build_media_task_input("audio_minutes")
    for task_input in (removed_task, claimed_task, ready_task):
        enqueue_waiting_task(
            task_input=task_input,
            unavailable_capabilities=["EXTRACT_TRANSCRIPT"],
        )

    records = list_waiting_tasks()
    capability_wait_queue.remove_waiting_task(removed_task.task_id)
    capability_wait_queue._claim_waiting_task(claimed_task.task_id)
    original_wake = capability_wait_queue.wake_waiting_task
    wake_calls: list[str] = []

    monkeypatch.setattr(capability_wait_queue, "list_waiting_tasks", lambda: records)

    def wake(task_id: str) -> tuple[list[object], object]:
        wake_calls.append(task_id)
        if task_id in {removed_task.task_id, claimed_task.task_id}:
            try:
                return original_wake(task_id)
            except capability_wait_queue.WaitingTaskClaimConflict:
                if task_id == claimed_task.task_id:
                    capability_wait_queue.release_waiting_task_claim(task_id)
                raise
        return (
            [SimpleNamespace(phase="EXPORTING")],
            SimpleNamespace(job_snapshot=SimpleNamespace(status="COMPLETED")),
        )

    monkeypatch.setattr(capability_wait_queue, "wake_waiting_task", wake)

    attempts = capability_wait_queue.wake_waiting_tasks_for_capability("EXTRACT_TRANSCRIPT")

    assert wake_calls == [removed_task.task_id, claimed_task.task_id, ready_task.task_id]
    assert [attempt["task_id"] for attempt in attempts] == [ready_task.task_id]
    assert attempts[0]["result_status"] == "COMPLETED"
    claimed_record = get_waiting_task(claimed_task.task_id)
    assert claimed_record is not None
    assert claimed_record["status"] == "WAITING_FOR_CAPABILITY"
    clear_waiting_tasks()


def test_waiting_task_debug_views_should_expose_queue_and_wake() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")

    try:
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))
        queued = debug_list_waiting_tasks()
        waiting_detail = debug_get_waiting_task_detail(waiting_result.job_snapshot.task_id)

        assert queued.tasks
        assert queued.tasks[0]["task_id"] == waiting_result.job_snapshot.task_id
        assert waiting_detail.task["task_id"] == waiting_result.job_snapshot.task_id
        assert waiting_detail.task["blocked_operations"][0]["operation_key"] == "EXTRACT_TRANSCRIPT"
        assert waiting_detail.task["task_input"]["task_id"] == waiting_result.job_snapshot.task_id

        set_capability_provider_status("EXTRACT_TRANSCRIPT", "AVAILABLE")
        resumed = debug_wake_waiting_task(waiting_result.job_snapshot.task_id)
    finally:
        reset_capability_provider_status()
        clear_waiting_tasks()
        clear_approval_requests()

    assert resumed.result["job_snapshot"]["status"] == "COMPLETED"


def test_run_artifact_task_should_wait_for_approval_when_provider_not_approved() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        events, result = run_artifact_task(_build_media_task_input("video_summary"))
        requests = list_approval_requests()
        request_detail = debug_get_capability_approval_request_detail(requests[0]["request_id"])
    finally:
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()

    assert events[-1].phase == "WAITING_FOR_APPROVAL"
    assert result.job_snapshot.status == "WAITING_FOR_APPROVAL"
    assert result.result_payload["lifecycle_trace"]["status"] == "WAITING_FOR_APPROVAL"
    assert result.result_payload["lifecycle_trace"]["current_phase"] == "WAITING_FOR_APPROVAL"
    assert result.result_payload["lifecycle_trace"]["steps"][0]["phase"] == "WAITING_FOR_APPROVAL"
    assert result.result_payload["approval_request"]["capability_name"] == "EXTRACT_TRANSCRIPT"
    assert result.result_payload["capability_union_trace"]["status"] == "ALLOW"
    assert result.result_payload["capability_union_trace"]["decision"] == "ALLOW"
    assert "EXTRACT_TRANSCRIPT" in result.result_payload["capability_union_trace"]["capability_scope"]
    assert result.result_payload["approval_trace"]["status"] == "WAITING_FOR_APPROVAL"
    assert result.result_payload["approval_trace"]["required_capabilities"] == ["EXTRACT_TRANSCRIPT"]
    assert result.result_payload["approval_trace"]["pending_capabilities"] == ["EXTRACT_TRANSCRIPT"]
    assert result.result_payload["approval_trace"]["approval_request"]["request_id"] == requests[0]["request_id"]
    assert result.result_payload["approval_trace"]["capability_decisions"][0]["approval_status"] == "PENDING"
    assert requests[0]["status"] == "PENDING"
    assert request_detail.request["request_id"] == requests[0]["request_id"]
    assert request_detail.request["status"] == "PENDING"
    assert request_detail.request["capability_name"] == "EXTRACT_TRANSCRIPT"
    receipt = result.result_payload["acquisition_receipt"]
    transcript_operation = next(
        operation
        for operation in receipt["source_receipts"][0]["operations"]
        if operation["operation_key"] == "EXTRACT_TRANSCRIPT"
    )
    normalize_operation = next(
        operation
        for operation in receipt["source_receipts"][0]["operations"]
        if operation["operation_key"] == "NORMALIZE_TO_CCO"
    )
    assert receipt["status"] == "WAITING_FOR_APPROVAL"
    assert receipt["execution_mode"] == "SIMULATED_RUNTIME"
    assert receipt["wait_reason"]["status"] == "WAITING_FOR_APPROVAL"
    assert receipt["wait_reason"]["capabilities"] == ["EXTRACT_TRANSCRIPT"]
    assert transcript_operation["status"] == "WAITING_FOR_APPROVAL"
    assert transcript_operation["request_id"].startswith("fetch-video_summary-job-src-video-1-")
    assert transcript_operation["requested_at"]
    assert transcript_operation["completed_at"] == ""
    assert transcript_operation["provider_receipt_id"] == ""
    assert transcript_operation["provider_job_id"] == ""
    assert transcript_operation["adapter_callback_token"] == ""
    assert transcript_operation["provider_job_status"] == "WAITING_FOR_APPROVAL"
    assert transcript_operation["callback_status"] == "WAITING_FOR_APPROVAL"
    assert transcript_operation["callback_received_at"] == ""
    assert transcript_operation["result_locator"] == ""
    assert transcript_operation["output_summary"] == ""
    assert transcript_operation["content_digest"] == ""
    assert transcript_operation["payload_char_count"] == 0
    assert transcript_operation["segment_count"] == 0
    assert transcript_operation["source_ref_count"] == 0
    assert transcript_operation["error_code"] == "CAPABILITY_APPROVAL_PENDING"
    assert "EXTRACT_TRANSCRIPT" in transcript_operation["error_message"]
    assert transcript_operation["retryable"] is True
    assert transcript_operation["provider_attempts"][0]["provider_id"] == "builtin-bilibili-mcp"
    assert transcript_operation["provider_attempts"][0]["attempt_result"] == "WAITING_FOR_APPROVAL"
    assert transcript_operation["approval_status"] == "PENDING"
    assert transcript_operation["provider_status"] == "AVAILABLE"
    assert transcript_operation["health_status"] == "HEALTHY"
    assert normalize_operation["status"] == "PLANNED"
    assert normalize_operation["error_code"] == "UPSTREAM_OPERATION_PENDING"
    assert normalize_operation["retryable"] is True
    assert normalize_operation["provider_job_status"] == "PENDING_UPSTREAM"
    assert normalize_operation["callback_status"] == "PENDING_UPSTREAM"


def test_run_artifact_task_should_emit_capability_wait_receipt_snapshot() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")

    try:
        _, result = run_artifact_task(_build_media_task_input("video_summary"))
    finally:
        reset_capability_provider_status()
        clear_approval_requests()
        clear_waiting_tasks()

    receipt = result.result_payload["acquisition_receipt"]
    transcript_operation = next(
        operation
        for operation in receipt["source_receipts"][0]["operations"]
        if operation["operation_key"] == "EXTRACT_TRANSCRIPT"
    )

    assert receipt["status"] == "WAITING_FOR_CAPABILITY"
    assert receipt["wait_reason"]["status"] == "WAITING_FOR_CAPABILITY"
    assert receipt["wait_reason"]["capabilities"] == ["EXTRACT_TRANSCRIPT"]
    assert transcript_operation["status"] == "WAITING_FOR_CAPABILITY"
    assert transcript_operation["request_id"].startswith("fetch-video_summary-job-src-video-1-")
    assert transcript_operation["requested_at"]
    assert transcript_operation["completed_at"] == ""
    assert transcript_operation["provider_receipt_id"] == ""
    assert transcript_operation["provider_job_id"] == ""
    assert transcript_operation["adapter_callback_token"] == ""
    assert transcript_operation["provider_job_status"] == "WAITING_FOR_CAPABILITY"
    assert transcript_operation["callback_status"] == "WAITING_FOR_CAPABILITY"
    assert transcript_operation["callback_received_at"] == ""
    assert transcript_operation["result_locator"] == ""
    assert transcript_operation["output_summary"] == ""
    assert transcript_operation["content_digest"] == ""
    assert transcript_operation["payload_char_count"] == 0
    assert transcript_operation["segment_count"] == 0
    assert transcript_operation["source_ref_count"] == 0
    assert transcript_operation["error_code"] == "CAPABILITY_UNAVAILABLE"
    assert "EXTRACT_TRANSCRIPT" in transcript_operation["error_message"]
    assert transcript_operation["retryable"] is True
    assert transcript_operation["provider_attempts"][0]["provider_id"] == "builtin-bilibili-mcp"
    assert transcript_operation["provider_attempts"][0]["attempt_result"] == "WAITING_FOR_CAPABILITY"
    assert transcript_operation["provider_status"] == "UNAVAILABLE"


def test_approved_capability_request_should_resume_waiting_task() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))
        request = list_approval_requests()[0]
        approved_request = approve_capability_request(request["request_id"])
    finally:
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()

    assert approved_request["resume_attempts"]
    assert approved_request["resume_attempts"][0]["task_id"] == waiting_result.job_snapshot.task_id
    assert approved_request["resume_attempts"][0]["last_phase"] == "EXPORTING"
    assert approved_request["resume_attempts"][0]["result_status"] == "COMPLETED"


def test_approved_capability_request_should_auto_resume_waiting_task() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_approval_status()

    try:
        set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))
        request = list_approval_requests()[0]

        approved_request = approve_capability_request(request["request_id"])
    finally:
        reset_capability_provider_approval_status()
        clear_approval_requests()

    assert approved_request["status"] == "APPROVED"
    assert approved_request["resume_attempts"]
    assert approved_request["resume_attempts"][0]["task_id"] == waiting_result.job_snapshot.task_id
    assert approved_request["resume_attempts"][0]["result_status"] == "COMPLETED"
    assert get_waiting_task(waiting_result.job_snapshot.task_id) is None
    assert list_artifact_versions(target_id=waiting_result.job_snapshot.target_id)
    clear_waiting_tasks()


def test_waiting_task_should_stay_queued_if_woken_before_approval() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))
        resumed_events, resumed_result = wake_waiting_task(waiting_result.job_snapshot.task_id)

        assert resumed_events[-1].phase == "WAITING_FOR_APPROVAL"
        assert resumed_result.job_snapshot.status == "WAITING_FOR_APPROVAL"
        assert get_waiting_task(waiting_result.job_snapshot.task_id) is not None
    finally:
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()


def test_waiting_task_resume_should_reuse_compiled_plan_checkpoint(monkeypatch) -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))
        waiting_record = get_waiting_task(waiting_result.job_snapshot.task_id)
        assert waiting_record is not None
        assert waiting_record["resume_checkpoint"]["stage"] == "CAPABILITY_GATE"

        monkeypatch.setattr(
            "app.runner.build_execution_plan",
            lambda task_input: (_ for _ in ()).throw(
                AssertionError("resume must not recompile the execution plan")
            ),
        )
        set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "APPROVED")
        resumed_events, resumed_result = wake_waiting_task(
            waiting_result.job_snapshot.task_id
        )

        assert resumed_events[-1].phase == "EXPORTING"
        assert resumed_result.job_snapshot.status == "COMPLETED"
    finally:
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()


def test_waiting_task_resume_should_reject_missing_checkpoint_without_restarting() -> None:
    clear_waiting_tasks()
    task_input = _build_media_task_input("video_summary")
    enqueue_waiting_task(
        task_input=task_input,
        unavailable_capabilities=["EXTRACT_TRANSCRIPT"],
        resume_checkpoint={},
    )

    try:
        with pytest.raises(ValueError, match="missing resume_checkpoint"):
            wake_waiting_task(task_input.task_id)
        waiting_record = get_waiting_task(task_input.task_id)
        assert waiting_record is not None
        assert waiting_record["status"] == "REPLAY_REQUIRED"
        assert "legacy full-task restart is disabled" in waiting_record["resume_error"]
    finally:
        clear_waiting_tasks()


def test_waiting_task_resume_should_reject_legacy_checkpoint_schema() -> None:
    clear_waiting_tasks()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")
    task_input = _build_media_task_input("video_summary")

    try:
        _, waiting_result = run_artifact_task(task_input)
        waiting_record = get_waiting_task(waiting_result.job_snapshot.task_id)
        assert waiting_record is not None
        legacy_checkpoint = dict(waiting_record["resume_checkpoint"])
        legacy_checkpoint["schema_version"] = 1
        legacy_checkpoint.pop("canonical_content_objects", None)
        legacy_checkpoint.pop("context_pack", None)

        clear_waiting_tasks()
        enqueue_waiting_task(
            task_input=task_input,
            unavailable_capabilities=["EXTRACT_TRANSCRIPT"],
            resume_checkpoint=legacy_checkpoint,
        )
        with pytest.raises(ValueError, match="schema v2 is required"):
            wake_waiting_task(task_input.task_id)
        waiting_record = get_waiting_task(task_input.task_id)
        assert waiting_record is not None
        assert waiting_record["status"] == "REPLAY_REQUIRED"
    finally:
        reset_capability_provider_status()
        clear_waiting_tasks()


def test_debug_health_check_and_approval_actions_should_update_runtime_state() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_health_status()
    reset_capability_provider_probe_results()
    reset_capability_provider_approval_status()
    set_capability_provider_probe_result("EXTRACT_TRANSCRIPT", "DOWN")

    try:
        health_response = debug_run_capability_provider_health_checks(["EXTRACT_TRANSCRIPT"])
        provider = next(
            item
            for item in health_response.snapshot["providers"]
            if item["capability_name"] == "EXTRACT_TRANSCRIPT"
        )
        assert provider["health_status"] == "DOWN"
        assert provider["provider_status"] == "UNAVAILABLE"

        reset_capability_provider_status()
        reset_capability_provider_health_status()
        set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))
        request = list_approval_requests()[0]
        pending_request_detail = debug_get_capability_approval_request_detail(request["request_id"])

        approval_response = debug_approve_capability_request(request["request_id"])
        approved_request_detail = debug_get_capability_approval_request_detail(request["request_id"])

        assert approval_response.request["request_id"] == request["request_id"]
        assert approval_response.request["status"] == "APPROVED"
        assert approval_response.request["resume_attempts"]
        assert pending_request_detail.request["status"] == "PENDING"
        assert approved_request_detail.request["status"] == "APPROVED"
        assert get_waiting_task(waiting_result.job_snapshot.task_id) is None
    finally:
        reset_capability_provider_status()
        reset_capability_provider_health_status()
        reset_capability_provider_probe_results()
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()


def test_run_artifact_task_should_fallback_when_preferred_bilibili_provider_is_unhealthy() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_health_status()

    try:
        set_capability_provider_health_status(
            "EXTRACT_TRANSCRIPT",
            "DOWN",
            provider_id="builtin-bilibili-mcp",
            tool_name="get_subtitle",
        )
        _, result = run_artifact_task(_build_media_task_input("video_summary"))
    finally:
        reset_capability_provider_status()
        reset_capability_provider_health_status()
        clear_approval_requests()
        clear_waiting_tasks()

    selected_binding = result.result_payload["capability_resolution"]["resolved_bindings"][1]
    selected_provider = result.result_payload["capability_resolution"]["provider_bindings"][1]
    assert result.job_snapshot.status == "COMPLETED"
    assert selected_binding["server_id"] == "builtin-media"
    assert selected_binding["tool_name"] == "extract_transcript"
    assert selected_binding["selection_reason"] == "fallback_provider_selected"
    assert selected_provider["provider_status"] == "AVAILABLE"
    receipt = result.result_payload["acquisition_receipt"]
    transcript_operation = next(
        operation
        for operation in receipt["source_receipts"][0]["operations"]
        if operation["operation_key"] == "EXTRACT_TRANSCRIPT"
    )
    assert transcript_operation["provider_attempts"][0]["provider_id"] == "builtin-bilibili-mcp"
    assert transcript_operation["provider_attempts"][0]["attempt_result"] == "SKIPPED_UNHEALTHY"
    assert transcript_operation["provider_attempts"][1]["provider_id"] == "builtin-media"
    assert transcript_operation["provider_attempts"][1]["attempt_result"] == "SELECTED"


def test_provider_health_snapshot_should_show_candidate_level_health_overrides() -> None:
    reset_capability_provider_health_status()

    try:
        set_capability_provider_health_status(
            "EXTRACT_TRANSCRIPT",
            "DOWN",
            provider_id="builtin-bilibili-mcp",
            tool_name="get_subtitle",
        )
        snapshot = debug_provider_health_snapshot()
    finally:
        reset_capability_provider_health_status()

    bilibili_provider = next(
        item
        for item in snapshot["providers"]
        if item["capability_name"] == "EXTRACT_TRANSCRIPT"
        and item["provider_id"] == "builtin-bilibili-mcp"
    )
    generic_provider = next(
        item
        for item in snapshot["providers"]
        if item["capability_name"] == "EXTRACT_TRANSCRIPT"
        and item["provider_id"] == "builtin-media"
        and item["tool_name"] == "extract_transcript"
    )
    assert bilibili_provider["health_status"] == "DOWN"
    assert generic_provider["health_status"] == "HEALTHY"


def test_capability_provider_discovery_scan_should_mark_candidates_discovered_and_timestamp() -> None:
    reset_capability_provider_discovery_status()

    try:
        set_capability_provider_discovery_status(
            "READ_WEB_PAGE",
            "UNDISCOVERED",
            provider_id="builtin-browser",
            tool_name="read_article_page",
        )
        set_capability_provider_discovery_status(
            "READ_WEB_PAGE",
            "UNDISCOVERED",
            provider_id="builtin-network",
            tool_name="read_web_page",
        )

        scan_response = debug_run_capability_provider_discovery(["READ_WEB_PAGE"])
        snapshot = debug_get_capability_provider_discovery().snapshot
    finally:
        reset_capability_provider_discovery_status()

    providers = [
        item for item in scan_response.snapshot["providers"] if item["capability_name"] == "READ_WEB_PAGE"
    ]
    assert providers
    assert all(provider["discovery_status"] == "DISCOVERED" for provider in providers)
    assert all(provider["last_discovered_at"] for provider in providers)
    assert snapshot["summary"]["discovered_count"] >= len(providers)


def test_capability_provider_health_check_should_auto_resume_waiting_task(monkeypatch) -> None:
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_probe_results()

    try:
        set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))
        waiting_record = get_waiting_task(waiting_result.job_snapshot.task_id)
        assert waiting_record is not None
        checkpoint = waiting_record["resume_checkpoint"]
        assert checkpoint["schema_version"] == 2
        assert checkpoint["stage"] == "CAPABILITY_GATE"
        assert checkpoint["canonical_content_objects"]
        assert checkpoint["context_pack"]
        assert checkpoint["capability_resolution"]
        assert checkpoint["acquisition_receipt"]

        def fail_early_stage(*_args, **_kwargs):
            raise AssertionError("resume re-entered an already checkpointed stage")

        monkeypatch.setattr("app.runner.build_execution_plan", fail_early_stage)
        monkeypatch.setattr("app.runner.build_canonical_content_objects", fail_early_stage)
        monkeypatch.setattr("app.runner.build_context_pack", fail_early_stage)

        set_capability_provider_probe_result("EXTRACT_TRANSCRIPT", "HEALTHY")
        snapshot = run_capability_provider_health_checks(["EXTRACT_TRANSCRIPT"])
    finally:
        reset_capability_provider_status()
        reset_capability_provider_probe_results()
        clear_approval_requests()
        clear_acquisition_runtime()

    assert snapshot["resumed_tasks"]
    assert snapshot["resumed_tasks"][0]["task_id"] == waiting_result.job_snapshot.task_id
    assert snapshot["resumed_tasks"][0]["result_status"] == "COMPLETED"
    assert get_waiting_task(waiting_result.job_snapshot.task_id) is None
    assert list_artifact_versions(target_id=waiting_result.job_snapshot.target_id)
    clear_waiting_tasks()


def test_capability_provider_discovery_scan_should_auto_resume_waiting_task() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_discovery_status()

    try:
        set_capability_provider_discovery_status(
            "READ_WEB_PAGE",
            "UNDISCOVERED",
            provider_id="builtin-browser",
            tool_name="read_article_page",
        )
        set_capability_provider_discovery_status(
            "READ_WEB_PAGE",
            "UNDISCOVERED",
            provider_id="builtin-network",
            tool_name="read_web_page",
        )
        from tests.test_runner import _build_mixed_context_task_input

        _, waiting_result = run_artifact_task(_build_mixed_context_task_input())
        snapshot = run_capability_provider_discovery_scan(["READ_WEB_PAGE"])
    finally:
        reset_capability_provider_status()
        reset_capability_provider_discovery_status()
        clear_approval_requests()

    assert snapshot["resumed_tasks"]
    assert snapshot["resumed_tasks"][0]["task_id"] == waiting_result.job_snapshot.task_id
    assert snapshot["resumed_tasks"][0]["result_status"] == "COMPLETED"
    assert get_waiting_task(waiting_result.job_snapshot.task_id) is None
    assert list_artifact_versions(target_id=waiting_result.job_snapshot.target_id)
    clear_waiting_tasks()


def test_capability_mapping_snapshot_should_expose_mapping_sources_and_constraints() -> None:
    direct_snapshot = debug_capability_mapping_snapshot()
    api_snapshot = debug_get_capability_mappings().snapshot
    mapping_detail = debug_get_capability_mapping_detail(
        "READ_WEB_PAGE:builtin-browser:read_article_page"
    )

    browser_mapping = next(
        item
        for item in direct_snapshot["mappings"]
        if item["capability_name"] == "READ_WEB_PAGE"
        and item["provider_id"] == "builtin-browser"
    )
    assert browser_mapping["mapping_source"] == "capability_mapping"
    assert "WEB_URL" in browser_mapping["supported_routes"]
    assert "REPORT" in browser_mapping["supported_actions"]
    assert "generic_artifact_v1" in browser_mapping["supported_skill_graphs"]
    assert mapping_detail.mapping["mapping_key"] == "READ_WEB_PAGE:builtin-browser:read_article_page"
    assert mapping_detail.mapping["mapping_source"] == "capability_mapping"
    assert api_snapshot["summary"]["capability_count"] >= 1


def test_capability_resolver_should_use_skill_graph_constraint_to_change_web_provider_selection() -> None:
    debug_reset_custom_skill_graphs()
    registration = CustomSkillGraphTemplateRegistration.model_validate(
        {
            "graph_key": "network_fallback_graph_v1",
            "graph_name": "Network Fallback Graph",
            "base_graph_key": "generic_artifact_v1",
            "action_type": "GENERIC",
            "nodes": [
                SkillGraphNode(
                    node_id="digest",
                    skill_key="workspace_material_digest",
                    purpose="Compile workspace material digest.",
                ),
                SkillGraphNode(
                    node_id="write",
                    skill_key="generic_section_writer",
                    purpose="Generate outline-aligned sections.",
                ),
                SkillGraphNode(
                    node_id="guard",
                    skill_key="evidence_guard",
                    purpose="Check section evidence trace.",
                ),
                SkillGraphNode(
                    node_id="polish",
                    skill_key="style_polisher",
                    purpose="Polish final section wording.",
                ),
            ],
            "edges": [
                SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
                SkillGraphEdge(from_node="write", to_node="guard", edge_type="PREREQUISITE"),
                SkillGraphEdge(from_node="guard", to_node="polish", edge_type="PREREQUISITE"),
            ],
        }
    )
    task_input = _build_mixed_context_task_input()

    try:
        graph = debug_register_custom_skill_graph(registration).graph
        generic_binding = resolve_capability_bindings(
            ["READ_WEB_PAGE"],
            task_input,
            action_key="STUDY_GUIDE",
            skill_graph_key="generic_artifact_v1",
        )[0]
        custom_binding = resolve_capability_bindings(
            ["READ_WEB_PAGE"],
            task_input,
            action_key="STUDY_GUIDE",
            skill_graph_key=graph["graph_key"],
        )[0]
    finally:
        debug_reset_custom_skill_graphs()

    assert generic_binding["provider_id"] == "builtin-browser"
    assert generic_binding["tool_name"] == "read_article_page"
    assert generic_binding["selection_reason"] == "action_skill_graph_preferred_provider"
    assert generic_binding["skill_graph_basis"] == "generic_artifact_v1"
    assert custom_binding["provider_id"] == "builtin-network"
    assert custom_binding["tool_name"] == "read_web_page"
    assert custom_binding["selection_reason"] == "external_web_reader"
    assert custom_binding["skill_graph_basis"] == "network_fallback_graph_v1"


def test_run_artifact_task_should_fallback_when_action_preferred_web_provider_is_unhealthy() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_health_status()

    try:
        set_capability_provider_health_status(
            "READ_WEB_PAGE",
            "DOWN",
            provider_id="builtin-browser",
            tool_name="read_article_page",
        )
        from tests.test_runner import _build_mixed_context_task_input

        _, result = run_artifact_task(_build_mixed_context_task_input())
    finally:
        reset_capability_provider_status()
        reset_capability_provider_health_status()
        clear_approval_requests()
        clear_waiting_tasks()

    selected_binding = next(
        binding
        for binding in result.result_payload["capability_resolution"]["resolved_bindings"]
        if binding["capability_name"] == "READ_WEB_PAGE"
    )
    assert selected_binding["server_id"] == "builtin-network"
    assert selected_binding["tool_name"] == "read_web_page"
    assert selected_binding["selection_reason"] == "fallback_provider_selected"


def test_run_artifact_task_should_wait_when_no_discovered_web_provider_matches() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_health_status()
    reset_capability_provider_discovery_status()

    try:
        set_capability_provider_discovery_status(
            "READ_WEB_PAGE",
            "UNDISCOVERED",
            provider_id="builtin-browser",
            tool_name="read_article_page",
        )
        set_capability_provider_discovery_status(
            "READ_WEB_PAGE",
            "UNDISCOVERED",
            provider_id="builtin-network",
            tool_name="read_web_page",
        )
        from tests.test_runner import _build_mixed_context_task_input

        events, result = run_artifact_task(_build_mixed_context_task_input())
    finally:
        reset_capability_provider_status()
        reset_capability_provider_health_status()
        reset_capability_provider_discovery_status()
        clear_approval_requests()
        clear_waiting_tasks()

    selected_binding = next(
        binding
        for binding in result.result_payload["capability_resolution"]["provider_bindings"]
        if binding["capability_name"] == "READ_WEB_PAGE"
    )
    assert events[-1].phase == "WAITING_FOR_CAPABILITY"
    assert result.job_snapshot.status == "WAITING_FOR_CAPABILITY"
    assert selected_binding["discovery_status"] == "UNDISCOVERED"


def test_run_artifact_task_should_fallback_to_approved_web_candidate_when_preferred_is_pending() -> None:
    clear_artifact_repository()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    reset_capability_provider_health_status()
    reset_capability_provider_approval_status()

    try:
        set_capability_provider_approval_status(
            "READ_WEB_PAGE",
            "PENDING",
            provider_id="builtin-browser",
            tool_name="read_article_page",
        )
        from tests.test_runner import _build_mixed_context_task_input

        _, result = run_artifact_task(_build_mixed_context_task_input())
    finally:
        reset_capability_provider_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()

    selected_binding = next(
        binding
        for binding in result.result_payload["capability_resolution"]["resolved_bindings"]
        if binding["capability_name"] == "READ_WEB_PAGE"
    )
    assert result.job_snapshot.status == "COMPLETED"
    assert selected_binding["server_id"] == "builtin-network"
    assert selected_binding["tool_name"] == "read_web_page"
    assert selected_binding["selection_reason"] == "fallback_provider_selected"
    receipt = result.result_payload["acquisition_receipt"]
    url_receipt = next(
        item for item in receipt["source_receipts"] if item["source_id"] == "src-url-1"
    )
    read_operation = next(
        operation
        for operation in url_receipt["operations"]
        if operation["operation_key"] == "READ_EXTERNAL_CONTENT"
    )
    assert read_operation["provider_attempts"][0]["provider_id"] == "builtin-browser"
    assert read_operation["provider_attempts"][0]["attempt_result"] == "SKIPPED_APPROVAL_PENDING"
    assert read_operation["provider_attempts"][1]["provider_id"] == "builtin-network"
    assert read_operation["provider_attempts"][1]["attempt_result"] == "SELECTED"
