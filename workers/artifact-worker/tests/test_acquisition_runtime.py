from __future__ import annotations

import pytest

from app.main import (
    debug_ack_acquisition_operation,
    debug_dispatch_acquisition_operation,
    debug_get_acquisition_callback_receipt_detail,
    debug_get_acquisition_operation_detail,
    debug_list_acquisition_callback_receipts,
    debug_list_acquisition_operations,
    debug_reset_acquisition_runtime,
    debug_wake_waiting_task,
)
from app.runner import run_artifact_task
from app.acquisition_runtime import (
    acknowledge_acquisition_operation,
    clear_acquisition_runtime,
    dispatch_acquisition_operation,
)
from app.artifact_repository import (
    clear_artifact_repository,
    get_artifact_version_detail,
    list_artifact_versions,
)
from app.capability_wait_queue import get_waiting_task, list_waiting_tasks
from app.capability_approval_queue import clear_approval_requests
from app.capability_provider import (
    reset_capability_provider_discovery_status,
    reset_capability_provider_health_status,
    reset_capability_provider_status,
    reset_capability_provider_approval_status,
    set_capability_provider_status,
    set_capability_provider_approval_status,
)
from app.capability_wait_queue import clear_waiting_tasks
from tests.test_runner import _build_media_task_input, _build_mixed_context_task_input


def test_run_artifact_task_should_register_acquisition_runtime_operations_and_callback_receipts() -> None:
    clear_acquisition_runtime()

    try:
        _, result = run_artifact_task(_build_mixed_context_task_input())
        snapshot = result.result_payload["acquisition_runtime_snapshot"]
        operations = debug_list_acquisition_operations(task_id=result.job_snapshot.task_id)
        receipts = debug_list_acquisition_callback_receipts(task_id=result.job_snapshot.task_id)
        operation_detail = debug_get_acquisition_operation_detail(
            request_id=operations.operations[0]["request_id"]
        )
        receipt_detail = debug_get_acquisition_callback_receipt_detail(
            receipt_id=receipts.receipts[0]["receipt_id"]
        )
    finally:
        clear_acquisition_runtime()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()

    assert snapshot["task_id"] == result.job_snapshot.task_id
    assert snapshot["runtime_status"] == "COMPLETED"
    assert snapshot["registered_operation_count"] >= 4
    assert snapshot["registered_callback_receipt_count"] >= 1
    read_operation = next(
        operation
        for operation in operations.operations
        if operation["operation_key"] == "READ_EXTERNAL_CONTENT"
    )
    assert read_operation["callback_status"] == "ACKNOWLEDGED"
    assert read_operation["input_digest"]
    assert read_operation["provider_delivery_attempts"][0]["ack_status"] == "ACKNOWLEDGED"
    assert read_operation["provider_receipt_id"].startswith("provider-receipt-")
    assert receipts.receipts
    assert receipts.receipts[0]["callback_status"] == "ACKNOWLEDGED"
    matched_operation = next(
        operation
        for operation in operations.operations
        if operation["request_id"] == operation_detail.operation["request_id"]
    )
    matched_receipt = next(
        receipt
        for receipt in receipts.receipts
        if receipt["receipt_id"] == receipt_detail.receipt["receipt_id"]
    )
    assert operation_detail.operation == matched_operation
    assert receipt_detail.receipt == matched_receipt


def test_waiting_acquisition_runtime_should_register_operation_without_callback_receipt() -> None:
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        _, result = run_artifact_task(_build_media_task_input("video_summary"))
        snapshot = result.result_payload["acquisition_runtime_snapshot"]
        operations = debug_list_acquisition_operations(
            task_id=result.job_snapshot.task_id,
            capability_name="EXTRACT_TRANSCRIPT",
        )
        receipts = debug_list_acquisition_callback_receipts(task_id=result.job_snapshot.task_id)
        wait_queue_entry = result.result_payload["wait_queue_entry"]
    finally:
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert snapshot["runtime_status"] == "WAITING_FOR_APPROVAL"
    assert operations.operations
    assert operations.operations[0]["status"] == "WAITING_FOR_APPROVAL"
    assert operations.operations[0]["callback_status"] == "WAITING_FOR_APPROVAL"
    assert wait_queue_entry["blocked_operations"][0]["request_id"] == operations.operations[0]["request_id"]
    assert wait_queue_entry["blocked_operations"][0]["operation_key"] == "EXTRACT_TRANSCRIPT"
    assert receipts.receipts == []


def test_acquisition_ack_should_be_idempotent_for_same_token_and_terminal_status() -> None:
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        _, result = run_artifact_task(_build_media_task_input("video_summary"))
        operation = debug_list_acquisition_operations(
            task_id=result.job_snapshot.task_id,
            capability_name="EXTRACT_TRANSCRIPT",
        ).operations[0]
        dispatch = dispatch_acquisition_operation(operation["request_id"])
        callback_token = dispatch["operation"]["callback_token"]
        first = acknowledge_acquisition_operation(
            callback_token=callback_token,
            final_status="ACKNOWLEDGED",
            provider_payload={"subtitle_preview": ["stable provider result"]},
            auto_resume=False,
        )
        duplicate = acknowledge_acquisition_operation(
            callback_token=callback_token,
            final_status="ACKNOWLEDGED",
            provider_payload={"subtitle_preview": ["ignored duplicate payload"]},
            auto_resume=False,
        )

        with pytest.raises(ValueError, match="terminal status conflicts"):
            acknowledge_acquisition_operation(
                callback_token=callback_token,
                final_status="FAILED",
                auto_resume=False,
            )
    finally:
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert duplicate["operation"] == first["operation"]
    assert duplicate["receipt"] == first["receipt"]
    assert duplicate["resume_attempts"] == []


def test_debug_dispatch_and_ack_acquisition_operation_should_complete_callback_protocol() -> None:
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        _, result = run_artifact_task(_build_media_task_input("video_summary"))
        operation = debug_list_acquisition_operations(
            task_id=result.job_snapshot.task_id,
            capability_name="EXTRACT_TRANSCRIPT",
        ).operations[0]
        dispatch_response = debug_dispatch_acquisition_operation(operation["request_id"])
        ack_response = debug_ack_acquisition_operation(
            callback_token=dispatch_response.operation["callback_token"],
            final_status="ACKNOWLEDGED",
            result_locator="provider://builtin-bilibili-mcp/get_subtitle/fetch-video_summary-job-src-video-1-extract_transcript",
        )
        operation_detail = debug_get_acquisition_operation_detail(request_id=operation["request_id"])
        receipt_detail = debug_get_acquisition_callback_receipt_detail(
            receipt_id=ack_response.receipt["receipt_id"]
        )
        operations = debug_list_acquisition_operations(task_id=result.job_snapshot.task_id)
        receipts = debug_list_acquisition_callback_receipts(task_id=result.job_snapshot.task_id)
        committed_versions = list_artifact_versions(target_id=result.job_snapshot.target_id)
        committed_version = get_artifact_version_detail(
            target_id=result.job_snapshot.target_id,
            version_id=committed_versions[0]["version_id"],
        )
    finally:
        clear_artifact_repository()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert dispatch_response.operation["callback_status"] == "DISPATCHED_TO_PROVIDER"
    assert dispatch_response.operation["provider_status"] == "AVAILABLE"
    assert dispatch_response.operation["health_status"] == "HEALTHY"
    assert dispatch_response.operation["provider_job_status"] == "DISPATCHED"
    assert dispatch_response.operation["dispatch_count"] == 1
    assert dispatch_response.operation["delivery_id"]
    assert dispatch_response.operation["callback_token"]
    assert dispatch_response.operation["callback_deadline_at"]
    assert dispatch_response.operation["input_digest"]
    assert dispatch_response.operation["dispatched_at"]
    assert dispatch_response.dispatch["ack_endpoint"] == "/debug/ack-acquisition-operation"
    assert dispatch_response.dispatch["delivery_id"] == dispatch_response.operation["delivery_id"]
    assert dispatch_response.dispatch["input_digest"] == dispatch_response.operation["input_digest"]
    assert ack_response.operation["callback_status"] == "ACKNOWLEDGED"
    assert ack_response.operation["provider_status"] == "AVAILABLE"
    assert ack_response.operation["health_status"] == "HEALTHY"
    assert ack_response.operation["provider_job_status"] == "SUCCEEDED"
    assert ack_response.operation["callback_received_at"]
    assert ack_response.operation["provider_delivery_attempts"][0]["ack_status"] == "ACKNOWLEDGED"
    assert ack_response.resume_attempts
    assert ack_response.resume_attempts[0]["task_id"] == result.job_snapshot.task_id
    assert ack_response.resume_attempts[0]["matched_request_id"] == operation["request_id"]
    assert ack_response.resume_attempts[0]["matched_operation_key"] == "EXTRACT_TRANSCRIPT"
    assert ack_response.resume_attempts[0]["result_status"] == "COMPLETED"
    assert ack_response.receipt["callback_status"] == "ACKNOWLEDGED"
    assert ack_response.receipt["provider_job_status"] == "SUCCEEDED"
    assert ack_response.receipt["request_id"] == operation["request_id"]
    assert ack_response.receipt["task_id"] == result.job_snapshot.task_id
    assert ack_response.receipt["source_id"] == "src-video-1"
    assert ack_response.receipt["operation_key"] == "EXTRACT_TRANSCRIPT"
    assert ack_response.receipt["delivery_id"] == dispatch_response.operation["delivery_id"]
    assert ack_response.receipt["callback_token"] == dispatch_response.operation["callback_token"]
    assert (
        ack_response.receipt["result_locator"]
        == "provider://builtin-bilibili-mcp/get_subtitle/fetch-video_summary-job-src-video-1-extract_transcript"
    )
    assert ack_response.receipt["completed_at"]
    assert ack_response.receipt["dispatch_count"] == 1
    matched_operation = next(
        operation_item
        for operation_item in operations.operations
        if operation_item["request_id"] == ack_response.operation["request_id"]
    )
    assert operation_detail.operation == matched_operation
    assert receipt_detail.receipt == ack_response.receipt
    assert any(operation["provider_job_status"] == "SUCCEEDED" for operation in operations.operations)
    assert any(receipt["provider_job_status"] == "SUCCEEDED" for receipt in receipts.receipts)
    assert committed_version["runtime_trace"]["acquisition_callback_trace"]["status"] == "ATTACHED"
    assert (
        committed_version["runtime_trace"]["acquisition_callback_trace"]["receipt"]["receipt_id"]
        == ack_response.receipt["receipt_id"]
    )
    assert (
        committed_version["runtime_trace"]["acquisition_callback_trace"]["receipt"]["provider_receipt_id"]
        == ack_response.receipt["provider_receipt_id"]
    )
    assert get_waiting_task(result.job_snapshot.task_id) is None
    assert committed_versions


def test_debug_ack_acquisition_operation_should_support_failed_provider_callback() -> None:
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")

    try:
        _, result = run_artifact_task(_build_media_task_input("video_summary"))
        operation = debug_list_acquisition_operations(
            task_id=result.job_snapshot.task_id,
            capability_name="EXTRACT_TRANSCRIPT",
        ).operations[0]
        dispatch_response = debug_dispatch_acquisition_operation(operation["request_id"])
        ack_response = debug_ack_acquisition_operation(
            callback_token=dispatch_response.operation["callback_token"],
            final_status="FAILED",
            error_code="PROVIDER_TIMEOUT",
            error_message="provider callback timed out while extracting transcript",
        )
        operations = debug_list_acquisition_operations(task_id=result.job_snapshot.task_id)
        waiting_task = get_waiting_task(result.job_snapshot.task_id)
    finally:
        clear_artifact_repository()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_status()
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert ack_response.operation["callback_status"] == "FAILED"
    assert ack_response.operation["provider_status"] == "UNAVAILABLE"
    assert ack_response.operation["health_status"] == "HEALTHY"
    assert ack_response.operation["provider_job_status"] == "FAILED"
    assert ack_response.operation["last_error_code"] == "PROVIDER_TIMEOUT"
    assert "timed out" in ack_response.operation["last_error_message"]
    assert ack_response.operation["provider_delivery_attempts"][0]["ack_status"] == "FAILED"
    assert ack_response.resume_attempts == []
    assert ack_response.receipt["callback_status"] == "FAILED"
    assert ack_response.receipt["provider_job_status"] == "FAILED"
    assert ack_response.receipt["request_id"] == operation["request_id"]
    assert ack_response.receipt["task_id"] == result.job_snapshot.task_id
    assert ack_response.receipt["source_id"] == "src-video-1"
    assert ack_response.receipt["operation_key"] == "EXTRACT_TRANSCRIPT"
    assert ack_response.receipt["error_code"] == "PROVIDER_TIMEOUT"
    assert ack_response.receipt["completed_at"]
    assert ack_response.receipt["dispatch_count"] == 1
    assert any(operation["provider_job_status"] == "FAILED" for operation in operations.operations)
    assert waiting_task is not None


def test_acquisition_ack_should_resume_only_matching_waiting_task() -> None:
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_approval_status()
    set_capability_provider_approval_status("EXTRACT_TRANSCRIPT", "PENDING")

    try:
        first_task = _build_media_task_input("video_summary")
        first_task.task_id = "video-summary-job-a"
        first_task.target_id = "artifact-video-summary-a"
        first_task.source_scope[0].source_id = "src-video-a"

        second_task = _build_media_task_input("video_summary")
        second_task.task_id = "video-summary-job-b"
        second_task.target_id = "artifact-video-summary-b"
        second_task.source_scope[0].source_id = "src-video-b"

        _, first_result = run_artifact_task(first_task)
        _, second_result = run_artifact_task(second_task)

        first_operation = debug_list_acquisition_operations(
            task_id=first_result.job_snapshot.task_id,
            capability_name="EXTRACT_TRANSCRIPT",
        ).operations[0]
        dispatch_response = debug_dispatch_acquisition_operation(first_operation["request_id"])
        ack_response = debug_ack_acquisition_operation(
            callback_token=dispatch_response.operation["callback_token"],
            final_status="ACKNOWLEDGED",
            result_locator="provider://builtin-bilibili-mcp/get_subtitle/fetch-video-summary-job-a-src-video-a-extract_transcript",
        )
        waiting_tasks = list_waiting_tasks()
        second_waiting_task = get_waiting_task(second_result.job_snapshot.task_id)
    finally:
        clear_artifact_repository()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert ack_response.resume_attempts
    assert ack_response.resume_attempts[0]["task_id"] == first_result.job_snapshot.task_id
    assert ack_response.resume_attempts[0]["result_status"] == "COMPLETED"
    assert get_waiting_task(first_result.job_snapshot.task_id) is None
    assert second_waiting_task is not None
    assert len(waiting_tasks) == 1
    assert waiting_tasks[0]["task_id"] == second_result.job_snapshot.task_id


def test_debug_wake_waiting_task_should_support_operation_targeted_manual_resume() -> None:
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")

    try:
        first_task = _build_media_task_input("video_summary")
        first_task.task_id = "video-summary-manual-wake-a"
        first_task.target_id = "artifact-video-summary-manual-a"
        first_task.source_scope[0].source_id = "src-video-manual-a"

        second_task = _build_media_task_input("video_summary")
        second_task.task_id = "video-summary-manual-wake-b"
        second_task.target_id = "artifact-video-summary-manual-b"
        second_task.source_scope[0].source_id = "src-video-manual-b"

        _, first_result = run_artifact_task(first_task)
        _, second_result = run_artifact_task(second_task)

        first_operation = debug_list_acquisition_operations(
            task_id=first_result.job_snapshot.task_id,
            capability_name="EXTRACT_TRANSCRIPT",
        ).operations[0]

        set_capability_provider_status("EXTRACT_TRANSCRIPT", "AVAILABLE")
        resumed = debug_wake_waiting_task(
            first_result.job_snapshot.task_id,
            request_id=first_operation["request_id"],
        )
        resumed_version = get_artifact_version_detail(
            target_id=first_result.job_snapshot.target_id,
            version_id=resumed.result["version_snapshot"]["version_id"],
        )
        waiting_tasks = list_waiting_tasks()
        second_waiting_task = get_waiting_task(second_result.job_snapshot.task_id)
    finally:
        clear_artifact_repository()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert resumed.result["job_snapshot"]["status"] == "COMPLETED"
    assert resumed.result["result_payload"]["resume_scope"]["matched_request_id"] == first_operation["request_id"]
    assert resumed.result["result_payload"]["resume_scope"]["matched_operation_key"] == "EXTRACT_TRANSCRIPT"
    assert resumed.result["result_payload"]["resume_scope"]["matched_source_id"] == "src-video-manual-a"
    assert resumed.result["result_payload"]["lifecycle_trace"]["steps"][0]["phase"] == "RESUMING"
    assert resumed.result["result_payload"]["lifecycle_trace"]["resume_scope"]["matched_request_id"] == first_operation["request_id"]
    assert resumed_version["runtime_trace"]["lifecycle_trace"]["steps"][0]["phase"] == "RESUMING"
    assert (
        resumed_version["runtime_trace"]["lifecycle_trace"]["resume_scope"]["matched_request_id"]
        == first_operation["request_id"]
    )
    assert get_waiting_task(first_result.job_snapshot.task_id) is None
    assert second_waiting_task is not None
    assert len(waiting_tasks) == 1
    assert waiting_tasks[0]["task_id"] == second_result.job_snapshot.task_id


def test_failed_acquisition_operation_should_support_redelivery_with_attempt_history() -> None:
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")

    try:
        _, result = run_artifact_task(_build_media_task_input("video_summary"))
        operation = debug_list_acquisition_operations(
            task_id=result.job_snapshot.task_id,
            capability_name="EXTRACT_TRANSCRIPT",
        ).operations[0]
        first_dispatch = debug_dispatch_acquisition_operation(operation["request_id"])
        first_ack = debug_ack_acquisition_operation(
            callback_token=first_dispatch.operation["callback_token"],
            final_status="FAILED",
            error_code="PROVIDER_TIMEOUT",
            error_message="first provider delivery timed out",
        )
        second_dispatch = debug_dispatch_acquisition_operation(operation["request_id"])
        second_ack = debug_ack_acquisition_operation(
            callback_token=second_dispatch.operation["callback_token"],
            final_status="ACKNOWLEDGED",
            result_locator="provider://builtin-bilibili-mcp/get_subtitle/fetch-video_summary-job-src-video-1-extract_transcript",
        )
        committed_versions = list_artifact_versions(target_id=result.job_snapshot.target_id)
    finally:
        clear_artifact_repository()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_status()
        reset_capability_provider_approval_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert first_ack.operation["callback_status"] == "FAILED"
    assert first_ack.operation["provider_job_status"] == "FAILED"
    assert second_dispatch.operation["callback_status"] == "DISPATCHED_TO_PROVIDER"
    assert second_dispatch.operation["provider_job_status"] == "DISPATCHED"
    assert second_dispatch.operation["dispatch_count"] == 2
    assert second_dispatch.operation["delivery_id"] != first_dispatch.operation["delivery_id"]
    assert second_dispatch.operation["callback_token"] != first_dispatch.operation["callback_token"]
    assert len(second_dispatch.operation["provider_delivery_attempts"]) == 2
    assert second_dispatch.operation["provider_delivery_attempts"][0]["ack_status"] == "FAILED"
    assert second_dispatch.operation["provider_delivery_attempts"][1]["ack_status"] == "PENDING"
    assert second_ack.operation["callback_status"] == "ACKNOWLEDGED"
    assert second_ack.operation["provider_job_status"] == "SUCCEEDED"
    assert second_ack.operation["provider_delivery_attempts"][1]["ack_status"] == "ACKNOWLEDGED"
    assert second_ack.receipt["delivery_id"] == second_dispatch.operation["delivery_id"]
    assert second_ack.resume_attempts
    assert committed_versions


def test_debug_wake_waiting_task_should_reject_unknown_blocked_request() -> None:
    clear_artifact_repository()
    clear_acquisition_runtime()
    clear_waiting_tasks()
    clear_approval_requests()
    reset_capability_provider_status()
    set_capability_provider_status("EXTRACT_TRANSCRIPT", "UNAVAILABLE")

    try:
        _, waiting_result = run_artifact_task(_build_media_task_input("video_summary"))

        with pytest.raises(ValueError) as exc_info:
            debug_wake_waiting_task(
                waiting_result.job_snapshot.task_id,
                request_id="fetch-video_summary-job-src-video-1-unknown",
            )
    finally:
        clear_artifact_repository()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()
        clear_approval_requests()
        clear_waiting_tasks()
        clear_acquisition_runtime()

    assert "waiting task does not contain blocked operation" in str(exc_info.value)


def test_debug_reset_acquisition_runtime_should_clear_registered_operations_and_receipts() -> None:
    clear_acquisition_runtime()

    try:
        _, result = run_artifact_task(_build_mixed_context_task_input())
        operations_before = debug_list_acquisition_operations(task_id=result.job_snapshot.task_id)
        receipts_before = debug_list_acquisition_callback_receipts(task_id=result.job_snapshot.task_id)
        reset_response = debug_reset_acquisition_runtime()
        operations_after = debug_list_acquisition_operations(task_id=result.job_snapshot.task_id)
        receipts_after = debug_list_acquisition_callback_receipts(task_id=result.job_snapshot.task_id)
    finally:
        clear_acquisition_runtime()
        reset_capability_provider_discovery_status()
        reset_capability_provider_health_status()
        reset_capability_provider_approval_status()
        reset_capability_provider_status()

    assert operations_before.operations
    assert receipts_before.receipts
    assert reset_response.reset["status"] == "ok"
    assert operations_after.operations == []
    assert receipts_after.receipts == []
