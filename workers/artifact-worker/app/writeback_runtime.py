from __future__ import annotations

import hashlib
from datetime import timedelta
from datetime import datetime, timezone
from threading import Lock

from app.models import (
    ArtifactExecutionPlan,
    ArtifactTaskInput,
    ArtifactTaskResult,
    ArtifactVersionSnapshot,
    WritebackExecutionReceipt,
    WritebackPreview,
    WritebackRequest,
)


_writeback_lock = Lock()
_writeback_requests: dict[str, dict[str, object]] = {}
_writeback_receipts: dict[str, dict[str, object]] = {}


def clear_writeback_runtime() -> None:
    with _writeback_lock:
        _writeback_requests.clear()
        _writeback_receipts.clear()


def register_writeback_request(
    *,
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    result: ArtifactTaskResult,
    version_snapshot: ArtifactVersionSnapshot,
    writeback_preview: WritebackPreview,
) -> WritebackRequest | None:
    if writeback_preview.status != "READY_FOR_HOST_WRITEBACK" or not version_snapshot.version_id:
        return None

    request_id = f"writeback-{version_snapshot.version_id}-{writeback_preview.allowed_target.lower()}"
    request = WritebackRequest(
        request_id=request_id,
        status="READY_FOR_HOST_WRITEBACK",
        requested_mode=writeback_preview.requested_mode,
        allowed_target=writeback_preview.allowed_target,
        execution_mode=writeback_preview.execution_mode,
        required_capabilities=list(writeback_preview.required_capabilities),
        task_id=task_input.task_id,
        workspace_id=task_input.workspace_id,
        target_id=task_input.target_id,
        action_key=plan.action_key,
        version_id=version_snapshot.version_id,
        artifact_title=version_snapshot.title,
        target_locator_preview=_build_target_locator_preview(
            workspace_id=task_input.workspace_id,
            target_id=task_input.target_id,
            version_id=version_snapshot.version_id,
            allowed_target=writeback_preview.allowed_target,
        ),
        payload_char_count=len(str(result.result_payload.get("markdown", ""))),
        payload_digest=_build_payload_digest(str(result.result_payload.get("markdown", ""))),
        section_count=len(list(result.result_payload.get("sections", []))),
        dispatch_count=0,
        delivery_id="",
        created_at=_utc_now(),
        callback_deadline_at="",
        delivery_attempts=[],
        notes=list(writeback_preview.notes),
    )
    with _writeback_lock:
        _writeback_requests[request_id] = request.model_dump(mode="json")
    return request


def list_writeback_requests(
    *,
    status: str | None = None,
    allowed_target: str | None = None,
    version_id: str | None = None,
) -> list[dict[str, object]]:
    with _writeback_lock:
        records = [dict(record) for record in _writeback_requests.values()]
    if status is not None:
        records = [record for record in records if record["status"] == status]
    if allowed_target is not None:
        records = [record for record in records if record["allowed_target"] == allowed_target]
    if version_id is not None:
        records = [record for record in records if record["version_id"] == version_id]
    return sorted(records, key=lambda item: str(item["created_at"]))


def list_writeback_receipts(
    *,
    request_id: str | None = None,
    version_id: str | None = None,
) -> list[dict[str, object]]:
    with _writeback_lock:
        records = [dict(record) for record in _writeback_receipts.values()]
    if request_id is not None:
        records = [record for record in records if record["request_id"] == request_id]
    if version_id is not None:
        records = [record for record in records if record["version_id"] == version_id]
    return sorted(records, key=lambda item: str(item["executed_at"]))


def get_writeback_request_detail(*, request_id: str) -> dict[str, object]:
    with _writeback_lock:
        request = _writeback_requests.get(request_id)
    if request is None:
        raise ValueError(f"writeback request not found: {request_id}")
    return dict(request)


def get_writeback_receipt_detail(*, receipt_id: str) -> dict[str, object]:
    with _writeback_lock:
        receipt = _writeback_receipts.get(receipt_id)
    if receipt is None:
        raise ValueError(f"writeback receipt not found: {receipt_id}")
    return dict(receipt)


def execute_writeback_request(request_id: str) -> dict[str, object]:
    with _writeback_lock:
        request = _writeback_requests.get(request_id)
        if request is None:
            raise ValueError(f"writeback request not found: {request_id}")
        if request["status"] != "READY_FOR_HOST_WRITEBACK":
            raise ValueError(f"writeback request is not executable: {request_id}")

        executed_at = _utc_now()
        receipt = WritebackExecutionReceipt(
            receipt_id=f"writeback-receipt-{request_id}",
            request_id=request_id,
            delivery_id="",
            status="EXECUTED",
            requested_mode=str(request["requested_mode"]),
            allowed_target=str(request["allowed_target"]),
            execution_mode=str(request["execution_mode"]),
            version_id=str(request["version_id"]),
            target_locator=_build_target_locator_preview(
                workspace_id=str(request["workspace_id"]),
                target_id=str(request["target_id"]),
                version_id=str(request["version_id"]),
                allowed_target=str(request["allowed_target"]),
            ),
            executed_capability=str((request.get("required_capabilities") or [""])[0]),
            executed_at=executed_at,
            payload_digest=str(request.get("payload_digest", "")),
            dispatch_count=int(request.get("dispatch_count", 0)),
            error_code="",
            error_message="",
            notes=[
                "independent worker executed a simulated host writeback for verification",
            ],
        )
        request["status"] = "EXECUTED"
        request["executed_at"] = executed_at
        request["notes"] = list(request.get("notes", [])) + [
            f"executed_via_debug:{receipt.allowed_target}",
        ]
        _writeback_receipts[receipt.receipt_id] = receipt.model_dump(mode="json")
        return {
            "request": dict(request),
            "receipt": receipt.model_dump(mode="json"),
        }


def dispatch_writeback_request(request_id: str) -> dict[str, object]:
    with _writeback_lock:
        request = _writeback_requests.get(request_id)
        if request is None:
            raise ValueError(f"writeback request not found: {request_id}")
        if request["status"] not in {"READY_FOR_HOST_WRITEBACK", "FAILED"}:
            raise ValueError(f"writeback request is not dispatchable: {request_id}")

        dispatch_count = int(request.get("dispatch_count", 0)) + 1
        delivery_id = f"delivery-{request_id}-{dispatch_count}"
        callback_token = f"callback-{request_id}-{dispatch_count}"
        dispatched_at = _utc_now()
        callback_deadline_at = _utc_future(minutes=15)
        request["status"] = "DISPATCHED_TO_HOST"
        request["dispatch_count"] = dispatch_count
        request["delivery_id"] = delivery_id
        request["callback_token"] = callback_token
        request["dispatched_at"] = dispatched_at
        request["callback_deadline_at"] = callback_deadline_at
        request["acked_at"] = ""
        request["last_error_code"] = ""
        request["last_error_message"] = ""
        delivery_attempts = list(request.get("delivery_attempts", []))
        delivery_attempts.append(
            {
                "delivery_id": delivery_id,
                "dispatch_count": dispatch_count,
                "callback_token": callback_token,
                "dispatched_at": dispatched_at,
                "callback_deadline_at": callback_deadline_at,
                "target_locator_preview": request["target_locator_preview"],
                "payload_digest": request.get("payload_digest", ""),
                "ack_status": "PENDING",
                "acked_at": "",
                "target_locator": "",
                "error_code": "",
                "error_message": "",
            }
        )
        request["delivery_attempts"] = delivery_attempts
        request["notes"] = list(request.get("notes", [])) + [
            f"dispatched_to_host_callback_protocol:{dispatch_count}",
        ]
        return {
            "request": dict(request),
            "dispatch": {
                "request_id": request_id,
                "delivery_id": delivery_id,
                "callback_token": callback_token,
                "ack_endpoint": "/debug/ack-writeback-request",
                "target_locator_preview": request["target_locator_preview"],
                "payload_digest": request.get("payload_digest", ""),
                "callback_deadline_at": callback_deadline_at,
                "required_capabilities": list(request.get("required_capabilities", [])),
            },
        }


def acknowledge_writeback_request(
    *,
    callback_token: str,
    final_status: str,
    target_locator: str = "",
    error_code: str = "",
    error_message: str = "",
) -> dict[str, object]:
    normalized_status = final_status.strip().upper()
    if normalized_status not in {"ACKNOWLEDGED", "FAILED"}:
        raise ValueError(f"unsupported writeback ack status: {final_status}")

    with _writeback_lock:
        request = next(
            (
                record
                for record in _writeback_requests.values()
                if str(record.get("callback_token", "")) == callback_token
            ),
            None,
        )
        if request is None:
            raise ValueError(f"writeback callback token not found: {callback_token}")
        if request["status"] != "DISPATCHED_TO_HOST":
            raise ValueError(
                f"writeback request is not awaiting host ack: {request['request_id']}"
            )

        acked_at = _utc_now()
        request["status"] = normalized_status
        request["acked_at"] = acked_at
        request["last_error_code"] = error_code if normalized_status == "FAILED" else ""
        request["last_error_message"] = error_message if normalized_status == "FAILED" else ""
        delivery_id = str(request.get("delivery_id", ""))
        delivery_attempts = list(request.get("delivery_attempts", []))
        if delivery_attempts:
            delivery_attempt = dict(delivery_attempts[-1])
            delivery_attempt["ack_status"] = normalized_status
            delivery_attempt["acked_at"] = acked_at
            delivery_attempt["target_locator"] = (
                target_locator or str(request["target_locator_preview"])
            )
            delivery_attempt["error_code"] = (
                error_code if normalized_status == "FAILED" else ""
            )
            delivery_attempt["error_message"] = (
                error_message if normalized_status == "FAILED" else ""
            )
            delivery_attempts[-1] = delivery_attempt
            request["delivery_attempts"] = delivery_attempts
        request["notes"] = list(request.get("notes", [])) + [
            f"host_ack:{normalized_status.lower()}",
        ]
        resolved_target_locator = target_locator or str(request["target_locator_preview"])
        receipt = WritebackExecutionReceipt(
            receipt_id=f"writeback-receipt-{request['request_id']}-{normalized_status.lower()}",
            request_id=str(request["request_id"]),
            delivery_id=delivery_id,
            callback_token=callback_token,
            status=normalized_status,
            requested_mode=str(request["requested_mode"]),
            allowed_target=str(request["allowed_target"]),
            execution_mode="HOST_CALLBACK_ACK",
            version_id=str(request["version_id"]),
            target_locator=resolved_target_locator,
            executed_capability=str((request.get("required_capabilities") or [""])[0]),
            executed_at=acked_at,
            payload_digest=str(request.get("payload_digest", "")),
            dispatch_count=int(request.get("dispatch_count", 0)),
            error_code=error_code if normalized_status == "FAILED" else "",
            error_message=error_message if normalized_status == "FAILED" else "",
            notes=[
                "writeback status acknowledged through simulated host callback protocol",
            ],
        )
        _writeback_receipts[receipt.receipt_id] = receipt.model_dump(mode="json")
        return {
            "request": dict(request),
            "receipt": receipt.model_dump(mode="json"),
        }


def _build_target_locator_preview(
    *,
    workspace_id: str,
    target_id: str,
    version_id: str,
    allowed_target: str,
) -> str:
    if allowed_target == "ARTIFACT_VERSION":
        return f"artifact://{target_id}/{version_id}"
    if allowed_target == "EXPORT_FILE":
        return f"export://{target_id}/{version_id}.md"
    if allowed_target == "SAVE_AS_SOURCE":
        return f"workspace-source://{workspace_id}/{target_id}"
    if allowed_target == "WIKI_PAGE":
        return f"wiki://{workspace_id}/{target_id}"
    if allowed_target == "NOTE_PAGE":
        return f"note://{workspace_id}/{target_id}"
    return f"writeback://{workspace_id}/{target_id}/{version_id}"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _utc_future(*, minutes: int) -> str:
    return (datetime.now(timezone.utc) + timedelta(minutes=minutes)).isoformat()


def _build_payload_digest(markdown: str) -> str:
    return hashlib.sha256(markdown.encode("utf-8")).hexdigest()[:16]
