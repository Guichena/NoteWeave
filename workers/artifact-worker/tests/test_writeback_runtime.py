from __future__ import annotations

from app.main import (
    debug_ack_writeback_request,
    debug_dispatch_writeback_request,
    debug_execute_writeback_request,
    debug_get_writeback_receipt_detail,
    debug_get_writeback_request_detail,
    debug_list_writeback_receipts,
    debug_list_writeback_requests,
    debug_reset_writeback_runtime,
)
from app.runner import run_artifact_task
from app.writeback_runtime import clear_writeback_runtime
from tests.test_runner import _build_generic_task_input


def test_run_artifact_task_should_register_writeback_request_for_allowed_target() -> None:
    clear_writeback_runtime()
    task_input = _build_generic_task_input("structured_note")
    task_input.input_payload.writeback_mode = "SAVE_AS_SOURCE"

    try:
        _, result = run_artifact_task(task_input)
        preview = result.result_payload["writeback_preview"]
        request = result.result_payload["writeback_request"]
        requests = debug_list_writeback_requests(status="READY_FOR_HOST_WRITEBACK")
        request_detail = debug_get_writeback_request_detail(request_id=request["request_id"])
    finally:
        clear_writeback_runtime()

    assert preview["status"] == "READY_FOR_HOST_WRITEBACK"
    assert preview["execution_mode"] == "HOST_MANAGED_PREVIEW"
    assert preview["request_id"] == request["request_id"]
    assert preview["target_locator_preview"] == request["target_locator_preview"]
    assert request["allowed_target"] == "SAVE_AS_SOURCE"
    assert request["payload_char_count"] > 0
    assert request["payload_digest"]
    assert request["section_count"] == len(result.result_payload["sections"])
    assert request["dispatch_count"] == 0
    assert request["delivery_attempts"] == []
    assert request["target_locator_preview"].startswith("workspace-source://")
    assert requests.requests
    assert requests.requests[0]["request_id"] == request["request_id"]
    assert request_detail.request == request
    assert requests.requests[0] == request


def test_debug_execute_writeback_request_should_emit_receipt_and_update_status() -> None:
    clear_writeback_runtime()
    task_input = _build_generic_task_input("wiki_page", style_profile_key="wiki")
    task_input.input_payload.writeback_mode = "WIKI_PAGE"

    try:
        _, result = run_artifact_task(task_input)
        request = result.result_payload["writeback_request"]
        execute_response = debug_execute_writeback_request(request["request_id"])
        request_detail = debug_get_writeback_request_detail(request_id=request["request_id"])
        receipt_detail = debug_get_writeback_receipt_detail(
            receipt_id=execute_response.receipt["receipt_id"]
        )
        receipts = debug_list_writeback_receipts(version_id=result.version_snapshot.version_id)
        requests = debug_list_writeback_requests(version_id=result.version_snapshot.version_id)
    finally:
        clear_writeback_runtime()

    assert execute_response.request["status"] == "EXECUTED"
    assert execute_response.request["executed_at"]
    assert execute_response.receipt["status"] == "EXECUTED"
    assert execute_response.receipt["allowed_target"] == "WIKI_PAGE"
    assert execute_response.receipt["target_locator"].startswith("wiki://")
    assert execute_response.receipt["executed_capability"] == "WRITE_WIKI_PAGE"
    assert request_detail.request == execute_response.request
    assert receipt_detail.receipt == execute_response.receipt
    assert receipts.receipts
    assert receipts.receipts[0]["request_id"] == request["request_id"]
    assert requests.requests[0]["status"] == "EXECUTED"


def test_debug_dispatch_and_ack_writeback_request_should_complete_callback_protocol() -> None:
    clear_writeback_runtime()
    task_input = _build_generic_task_input("wiki_page", style_profile_key="wiki")
    task_input.input_payload.writeback_mode = "WIKI_PAGE"

    try:
        _, result = run_artifact_task(task_input)
        request = result.result_payload["writeback_request"]
        dispatch_response = debug_dispatch_writeback_request(request["request_id"])
        ack_response = debug_ack_writeback_request(
            callback_token=dispatch_response.request["callback_token"],
            final_status="ACKNOWLEDGED",
            target_locator="wiki://ws-artifact-1/artifact-wiki_page-1/published",
        )
        request_detail = debug_get_writeback_request_detail(request_id=request["request_id"])
        receipt_detail = debug_get_writeback_receipt_detail(
            receipt_id=ack_response.receipt["receipt_id"]
        )
        requests = debug_list_writeback_requests(version_id=result.version_snapshot.version_id)
        receipts = debug_list_writeback_receipts(version_id=result.version_snapshot.version_id)
    finally:
        clear_writeback_runtime()

    assert dispatch_response.request["status"] == "DISPATCHED_TO_HOST"
    assert dispatch_response.request["dispatch_count"] == 1
    assert dispatch_response.request["callback_token"]
    assert dispatch_response.request["delivery_id"]
    assert dispatch_response.request["callback_deadline_at"]
    assert dispatch_response.request["dispatched_at"]
    assert dispatch_response.dispatch["ack_endpoint"] == "/debug/ack-writeback-request"
    assert dispatch_response.dispatch["callback_token"] == dispatch_response.request["callback_token"]
    assert dispatch_response.dispatch["delivery_id"] == dispatch_response.request["delivery_id"]
    assert dispatch_response.dispatch["payload_digest"] == request["payload_digest"]
    assert ack_response.request["status"] == "ACKNOWLEDGED"
    assert ack_response.request["acked_at"]
    assert ack_response.receipt["status"] == "ACKNOWLEDGED"
    assert ack_response.receipt["delivery_id"] == dispatch_response.request["delivery_id"]
    assert ack_response.receipt["callback_token"] == dispatch_response.request["callback_token"]
    assert ack_response.receipt["target_locator"].endswith("/published")
    assert ack_response.request["delivery_attempts"][0]["ack_status"] == "ACKNOWLEDGED"
    assert request_detail.request == ack_response.request
    assert receipt_detail.receipt == ack_response.receipt
    assert requests.requests[0]["status"] == "ACKNOWLEDGED"
    assert any(receipt["status"] == "ACKNOWLEDGED" for receipt in receipts.receipts)


def test_debug_ack_writeback_request_should_support_failed_host_callback() -> None:
    clear_writeback_runtime()
    task_input = _build_generic_task_input("structured_note")
    task_input.input_payload.writeback_mode = "SAVE_AS_SOURCE"

    try:
        _, result = run_artifact_task(task_input)
        request = result.result_payload["writeback_request"]
        dispatch_response = debug_dispatch_writeback_request(request["request_id"])
        ack_response = debug_ack_writeback_request(
            callback_token=dispatch_response.request["callback_token"],
            final_status="FAILED",
            target_locator="",
            error_code="HOST_WRITE_TIMEOUT",
            error_message="host callback token=top-secret failed at C:\\private\\writeback.log",
        )
        requests = debug_list_writeback_requests(version_id=result.version_snapshot.version_id)
    finally:
        clear_writeback_runtime()

    assert ack_response.request["status"] == "FAILED"
    assert ack_response.request["last_error_code"] == "HOST_WRITE_TIMEOUT"
    assert "top-secret" not in ack_response.request["last_error_message"]
    assert "[REDACTED]" in ack_response.request["last_error_message"]
    assert "[PATH_REDACTED]" in ack_response.request["last_error_message"]
    assert ack_response.receipt["status"] == "FAILED"
    assert ack_response.receipt["error_code"] == "HOST_WRITE_TIMEOUT"
    assert ack_response.receipt["error_message"] == ack_response.request["last_error_message"]
    assert ack_response.request["delivery_attempts"][0]["ack_status"] == "FAILED"
    assert requests.requests[0]["status"] == "FAILED"


def test_failed_writeback_request_should_support_redelivery_with_attempt_history() -> None:
    clear_writeback_runtime()
    task_input = _build_generic_task_input("structured_note")
    task_input.input_payload.writeback_mode = "SAVE_AS_SOURCE"

    try:
        _, result = run_artifact_task(task_input)
        request = result.result_payload["writeback_request"]

        first_dispatch = debug_dispatch_writeback_request(request["request_id"])
        first_ack = debug_ack_writeback_request(
            callback_token=first_dispatch.request["callback_token"],
            final_status="FAILED",
            error_code="HOST_RETRYABLE_TIMEOUT",
            error_message="first delivery timed out",
        )

        second_dispatch = debug_dispatch_writeback_request(request["request_id"])
        second_ack = debug_ack_writeback_request(
            callback_token=second_dispatch.request["callback_token"],
            final_status="ACKNOWLEDGED",
            target_locator="workspace-source://ws-artifact-1/artifact-structured_note-1/published",
        )
    finally:
        clear_writeback_runtime()

    assert first_ack.request["status"] == "FAILED"
    assert second_dispatch.request["status"] == "DISPATCHED_TO_HOST"
    assert second_dispatch.request["dispatch_count"] == 2
    assert second_dispatch.request["delivery_id"] != first_dispatch.request["delivery_id"]
    assert second_dispatch.request["callback_token"] != first_dispatch.request["callback_token"]
    assert len(second_dispatch.request["delivery_attempts"]) == 2
    assert second_dispatch.request["delivery_attempts"][0]["ack_status"] == "FAILED"
    assert second_dispatch.request["delivery_attempts"][1]["ack_status"] == "PENDING"
    assert second_ack.request["status"] == "ACKNOWLEDGED"
    assert second_ack.request["delivery_attempts"][1]["ack_status"] == "ACKNOWLEDGED"
    assert second_ack.receipt["delivery_id"] == second_dispatch.request["delivery_id"]


def test_debug_reset_writeback_runtime_should_clear_registered_requests_and_receipts() -> None:
    clear_writeback_runtime()
    task_input = _build_generic_task_input("wiki_page", style_profile_key="wiki")
    task_input.input_payload.writeback_mode = "WIKI_PAGE"

    try:
        _, result = run_artifact_task(task_input)
        request = result.result_payload["writeback_request"]
        execute_response = debug_execute_writeback_request(request["request_id"])
        requests_before = debug_list_writeback_requests(version_id=result.version_snapshot.version_id)
        receipts_before = debug_list_writeback_receipts(version_id=result.version_snapshot.version_id)
        reset_response = debug_reset_writeback_runtime()
        requests_after = debug_list_writeback_requests(version_id=result.version_snapshot.version_id)
        receipts_after = debug_list_writeback_receipts(version_id=result.version_snapshot.version_id)
    finally:
        clear_writeback_runtime()

    assert execute_response.receipt["status"] == "EXECUTED"
    assert requests_before.requests
    assert receipts_before.receipts
    assert reset_response.reset["status"] == "ok"
    assert requests_after.requests == []
    assert receipts_after.receipts == []
