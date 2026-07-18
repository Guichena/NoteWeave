from __future__ import annotations

import hashlib
import json
import os
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Lock

from app.models import (
    AcquisitionReceipt,
    AcquisitionRuntimeCallbackReceipt,
    AcquisitionRuntimeOperation,
)
from app.provider_job_status import resolve_provider_job_status
from app.io_limits import ContentSizeLimitError, read_text_file_limited, validate_json_payload_size


_acquisition_lock = Lock()
_acquisition_operations: dict[str, dict[str, object]] = {}
_acquisition_callback_receipts: dict[str, dict[str, object]] = {}
_acquisition_result_payloads: dict[str, dict[str, object]] = {}
_acquisition_storage_path: Path | None = None


def configure_acquisition_runtime_store(storage_path: str | Path | None) -> None:
    global _acquisition_storage_path
    with _acquisition_lock:
        _acquisition_storage_path = Path(storage_path) if storage_path else None
        _acquisition_operations.clear()
        _acquisition_callback_receipts.clear()
        _acquisition_result_payloads.clear()
        if _acquisition_storage_path and _acquisition_storage_path.exists():
            payload = json.loads(_acquisition_storage_path.read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                _restore_mapping(_acquisition_operations, payload.get("operations"))
                _restore_mapping(_acquisition_callback_receipts, payload.get("callback_receipts"))
                _restore_mapping(_acquisition_result_payloads, payload.get("result_payloads"))


def _restore_mapping(target: dict[str, dict[str, object]], value: object) -> None:
    if not isinstance(value, dict):
        return
    target.update(
        {str(key): dict(item) for key, item in value.items() if isinstance(item, dict)}
    )


def _persist_acquisition_runtime_locked() -> None:
    if _acquisition_storage_path is None:
        return
    _acquisition_storage_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = _acquisition_storage_path.with_suffix(
        f"{_acquisition_storage_path.suffix}.tmp"
    )
    serialized = json.dumps(
        {
            "operations": _acquisition_operations,
            "callback_receipts": _acquisition_callback_receipts,
            "result_payloads": _acquisition_result_payloads,
        },
        ensure_ascii=False,
        indent=2,
    )
    temporary_path.write_text(serialized, encoding="utf-8")
    try:
        os.replace(temporary_path, _acquisition_storage_path)
    except PermissionError:
        _acquisition_storage_path.write_text(serialized, encoding="utf-8")
        temporary_path.unlink(missing_ok=True)


def clear_acquisition_runtime() -> None:
    with _acquisition_lock:
        _acquisition_operations.clear()
        _acquisition_callback_receipts.clear()
        _acquisition_result_payloads.clear()
        _persist_acquisition_runtime_locked()


def register_acquisition_runtime(
    *,
    task_id: str,
    acquisition_receipt: AcquisitionReceipt,
) -> dict[str, object]:
    registered_operation_count = 0
    registered_callback_receipt_count = 0

    with _acquisition_lock:
        for source_receipt in acquisition_receipt.source_receipts:
            for operation in source_receipt.operations:
                runtime_operation = AcquisitionRuntimeOperation(
                    request_id=operation.request_id,
                    task_id=task_id,
                    source_id=source_receipt.source_id,
                    operation_key=operation.operation_key,
                    status=operation.status,
                    capability_name=operation.capability_name,
                    provider_id=operation.provider_id,
                    server_id=operation.server_id,
                    tool_name=operation.tool_name,
                    provider_status=operation.provider_status,
                    health_status=operation.health_status,
                    input_locator=operation.input_locator,
                    input_digest=_build_input_digest(
                        request_id=operation.request_id,
                        input_locator=operation.input_locator,
                        capability_name=operation.capability_name,
                        source_id=source_receipt.source_id,
                    ),
                    requested_at=operation.requested_at,
                    completed_at=operation.completed_at,
                    provider_receipt_id=operation.provider_receipt_id,
                    provider_job_id=operation.provider_job_id,
                    adapter_callback_token=operation.adapter_callback_token,
                    delivery_id=(
                        f"acq-delivery-{operation.request_id}-1"
                        if operation.callback_status == "ACKNOWLEDGED" and operation.capability_name
                        else ""
                    ),
                    callback_token="",
                    provider_job_status=resolve_provider_job_status(
                        capability_name=operation.capability_name,
                        operation_status=operation.status,
                        callback_status=operation.callback_status,
                    ),
                    callback_status=operation.callback_status,
                    dispatch_count=(
                        1
                        if operation.callback_status == "ACKNOWLEDGED" and operation.capability_name
                        else 0
                    ),
                    dispatched_at="",
                    callback_deadline_at="",
                    callback_received_at=operation.callback_received_at,
                    result_locator=operation.result_locator,
                    output_summary=operation.output_summary,
                    content_digest=operation.content_digest,
                    payload_char_count=operation.payload_char_count,
                    segment_count=operation.segment_count,
                    source_ref_count=operation.source_ref_count,
                    error_code=operation.error_code,
                    error_message=operation.error_message,
                    retryable=operation.retryable,
                    last_error_code="",
                    last_error_message="",
                    provider_delivery_attempts=_build_initial_delivery_attempts(
                        operation=operation,
                        source_id=source_receipt.source_id,
                    ),
                    notes=list(operation.notes),
                )
                existing_operation = _acquisition_operations.get(operation.request_id)
                runtime_operation_payload = runtime_operation.model_dump(mode="json")
                if existing_operation is not None and _should_preserve_existing_runtime_operation(
                    existing_operation
                ):
                    preserved_operation = dict(existing_operation)
                    preserved_operation["notes"] = list(
                        dict.fromkeys(
                            [*list(preserved_operation.get("notes", [])), *list(operation.notes)]
                        )
                    )
                    _acquisition_operations[operation.request_id] = preserved_operation
                else:
                    _acquisition_operations[operation.request_id] = runtime_operation_payload
                registered_operation_count += 1

                if operation.callback_status == "ACKNOWLEDGED" and operation.capability_name:
                    receipt = AcquisitionRuntimeCallbackReceipt(
                        receipt_id=f"acq-callback-{operation.request_id}",
                        request_id=operation.request_id,
                        task_id=task_id,
                        source_id=source_receipt.source_id,
                        operation_key=operation.operation_key,
                        delivery_id=f"acq-delivery-{operation.request_id}-1",
                        callback_token=operation.adapter_callback_token,
                        provider_job_status=resolve_provider_job_status(
                            capability_name=operation.capability_name,
                            operation_status=operation.status,
                            callback_status=operation.callback_status,
                        ),
                        callback_status=operation.callback_status,
                        provider_receipt_id=operation.provider_receipt_id,
                        provider_job_id=operation.provider_job_id,
                        result_locator=operation.result_locator,
                        completed_at=operation.callback_received_at or operation.completed_at,
                        input_digest=_build_input_digest(
                            request_id=operation.request_id,
                            input_locator=operation.input_locator,
                            capability_name=operation.capability_name,
                            source_id=source_receipt.source_id,
                        ),
                        dispatch_count=1,
                        error_code="",
                        error_message="",
                        notes=[
                            "simulated provider callback receipt registered from acquisition runtime",
                        ],
                    )
                    _acquisition_callback_receipts[receipt.receipt_id] = receipt.model_dump(mode="json")
                    registered_callback_receipt_count += 1
        _persist_acquisition_runtime_locked()

    return {
        "task_id": task_id,
        "runtime_status": acquisition_receipt.status,
        "registered_operation_count": registered_operation_count,
        "registered_callback_receipt_count": registered_callback_receipt_count,
    }


def peek_acquisition_operation(request_id: str) -> dict[str, object] | None:
    with _acquisition_lock:
        operation = _acquisition_operations.get(request_id)
    return dict(operation) if operation is not None else None


def get_acquisition_result_payload(request_id: str) -> dict[str, object] | None:
    with _acquisition_lock:
        payload = _acquisition_result_payloads.get(request_id)
    return dict(payload) if payload is not None else None


def list_acquisition_operations(
    *,
    task_id: str | None = None,
    status: str | None = None,
    capability_name: str | None = None,
) -> list[dict[str, object]]:
    with _acquisition_lock:
        records = [dict(record) for record in _acquisition_operations.values()]
    if task_id is not None:
        records = [record for record in records if record["task_id"] == task_id]
    if status is not None:
        records = [record for record in records if record["status"] == status]
    if capability_name is not None:
        records = [record for record in records if record["capability_name"] == capability_name]
    return sorted(records, key=lambda item: (str(item["task_id"]), str(item["request_id"])))


def list_acquisition_callback_receipts(
    *,
    task_id: str | None = None,
    request_id: str | None = None,
) -> list[dict[str, object]]:
    with _acquisition_lock:
        records = [dict(record) for record in _acquisition_callback_receipts.values()]
    if task_id is not None:
        records = [record for record in records if record["task_id"] == task_id]
    if request_id is not None:
        records = [record for record in records if record["request_id"] == request_id]
    return sorted(records, key=lambda item: (str(item["task_id"]), str(item["request_id"])))


def get_acquisition_operation_detail(*, request_id: str) -> dict[str, object]:
    with _acquisition_lock:
        operation = _acquisition_operations.get(request_id)
    if operation is None:
        raise ValueError(f"acquisition operation not found: {request_id}")
    return dict(operation)


def get_acquisition_callback_receipt_detail(*, receipt_id: str) -> dict[str, object]:
    with _acquisition_lock:
        receipt = _acquisition_callback_receipts.get(receipt_id)
    if receipt is None:
        raise ValueError(f"acquisition callback receipt not found: {receipt_id}")
    return dict(receipt)


def dispatch_acquisition_operation(request_id: str) -> dict[str, object]:
    with _acquisition_lock:
        operation = _acquisition_operations.get(request_id)
        if operation is None:
            raise ValueError(f"acquisition operation not found: {request_id}")
        if not operation.get("capability_name"):
            raise ValueError(f"acquisition operation is not dispatchable: {request_id}")
        if operation.get("callback_status") in {"ACKNOWLEDGED", "DISPATCHED_TO_PROVIDER"}:
            raise ValueError(f"acquisition operation is not dispatchable: {request_id}")

        dispatch_count = int(operation.get("dispatch_count", 0)) + 1
        delivery_id = f"acq-delivery-{request_id}-{dispatch_count}"
        callback_token = (
            f"acq-callback-token-{request_id}-{dispatch_count}-"
            f"{secrets.token_urlsafe(24)}"
        )
        dispatched_at = _utc_now()
        callback_deadline_at = _utc_future(minutes=10)
        operation.setdefault("provider_receipt_id", f"provider-receipt-{request_id}")
        if not operation.get("provider_job_id"):
            normalized_server_id = str(operation.get("server_id") or "unknown-provider")
            operation["provider_job_id"] = f"provider-job-{normalized_server_id}-{request_id}"
        operation["delivery_id"] = delivery_id
        operation["callback_token"] = callback_token
        operation["callback_status"] = "DISPATCHED_TO_PROVIDER"
        operation["provider_job_status"] = resolve_provider_job_status(
            capability_name=str(operation.get("capability_name", "")),
            operation_status="RUNNING",
            callback_status="DISPATCHED_TO_PROVIDER",
        )
        operation["dispatch_count"] = dispatch_count
        operation["dispatched_at"] = dispatched_at
        operation["callback_deadline_at"] = callback_deadline_at
        operation["status"] = "RUNNING"
        operation["callback_received_at"] = ""
        operation["last_error_code"] = ""
        operation["last_error_message"] = ""
        delivery_attempts = list(operation.get("provider_delivery_attempts", []))
        delivery_attempts.append(
            {
                "delivery_id": delivery_id,
                "dispatch_count": dispatch_count,
                "callback_token": callback_token,
                "dispatched_at": dispatched_at,
                "callback_deadline_at": callback_deadline_at,
                "provider_job_id": operation.get("provider_job_id", ""),
                "provider_receipt_id": operation.get("provider_receipt_id", ""),
                "input_digest": operation.get("input_digest", ""),
                "ack_status": "PENDING",
                "callback_received_at": "",
                "result_locator": "",
                "error_code": "",
                "error_message": "",
            }
        )
        operation["provider_delivery_attempts"] = delivery_attempts
        operation["notes"] = list(operation.get("notes", [])) + [
            f"dispatched_to_simulated_provider_callback_protocol:{dispatch_count}",
        ]
        _persist_acquisition_runtime_locked()
        return {
            "operation": dict(operation),
            "dispatch": {
                "request_id": request_id,
                "delivery_id": delivery_id,
                "callback_token": callback_token,
                "ack_endpoint": "/debug/ack-acquisition-operation",
                "input_digest": operation.get("input_digest", ""),
                "callback_deadline_at": callback_deadline_at,
                "provider_job_id": operation.get("provider_job_id", ""),
                "provider_receipt_id": operation.get("provider_receipt_id", ""),
            },
        }


def acknowledge_acquisition_operation(
    *,
    callback_token: str,
    final_status: str,
    result_locator: str = "",
    error_code: str = "",
    error_message: str = "",
    provider_payload: dict[str, object] | None = None,
    auto_resume: bool = True,
) -> dict[str, object]:
    normalized_status = final_status.strip().upper()
    if normalized_status not in {"ACKNOWLEDGED", "FAILED"}:
        raise ValueError(f"unsupported acquisition ack status: {final_status}")
    if provider_payload:
        validate_json_payload_size(provider_payload, label="acquisition provider payload")

    with _acquisition_lock:
        operation = next(
            (
                record
                for record in _acquisition_operations.values()
                if str(record.get("callback_token", "")) == callback_token
            ),
            None,
        )
        if operation is None:
            raise ValueError(f"acquisition callback token not found: {callback_token}")
        current_callback_status = str(operation.get("callback_status", "")).upper()
        if current_callback_status in {"ACKNOWLEDGED", "FAILED"}:
            if current_callback_status != normalized_status:
                raise ValueError(
                    "acquisition callback terminal status conflicts with existing acknowledgement: "
                    f"{operation['request_id']}"
                )
            receipt_id = f"acq-callback-{operation['request_id']}-{normalized_status.lower()}"
            existing_receipt = _acquisition_callback_receipts.get(receipt_id)
            if existing_receipt is None:
                raise ValueError(
                    f"acquisition callback receipt is missing for terminal operation: {operation['request_id']}"
                )
            return {
                "operation": dict(operation),
                "receipt": dict(existing_receipt),
                "resume_attempts": [],
            }
        if operation.get("callback_status") != "DISPATCHED_TO_PROVIDER":
            raise ValueError(
                f"acquisition operation is not awaiting provider ack: {operation['request_id']}"
            )

        completed_at = _utc_now()
        operation["callback_status"] = normalized_status
        delivery_id = str(operation.get("delivery_id", ""))
        if normalized_status == "ACKNOWLEDGED":
            operation["status"] = "COMPLETED"
            operation["completed_at"] = operation.get("completed_at") or completed_at
            if provider_payload:
                _acquisition_result_payloads[str(operation["request_id"])] = dict(provider_payload)
            resolved_locator = result_locator or _resolve_provider_result_locator(
                request_id=str(operation["request_id"]),
                provider_payload=provider_payload or {},
            )
            operation["result_locator"] = resolved_locator or operation.get("result_locator", "")
            summary = _summarize_provider_payload(provider_payload or {})
            if summary["output_summary"]:
                operation["output_summary"] = summary["output_summary"]
            if summary["content_digest"]:
                operation["content_digest"] = summary["content_digest"]
            if summary["payload_char_count"]:
                operation["payload_char_count"] = summary["payload_char_count"]
            if summary["segment_count"]:
                operation["segment_count"] = summary["segment_count"]
            if summary["source_ref_count"]:
                operation["source_ref_count"] = summary["source_ref_count"]
            operation["last_error_code"] = ""
            operation["last_error_message"] = ""
        else:
            operation["status"] = "FAILED"
            operation["error_code"] = error_code or "PROVIDER_CALLBACK_FAILED"
            operation["error_message"] = error_message or "provider callback reported failure"
            operation["last_error_code"] = operation["error_code"]
            operation["last_error_message"] = operation["error_message"]
        operation["provider_job_status"] = resolve_provider_job_status(
            capability_name=str(operation.get("capability_name", "")),
            operation_status=str(operation.get("status", "")),
            callback_status=normalized_status,
        )
        operation["callback_received_at"] = completed_at
        delivery_attempts = list(operation.get("provider_delivery_attempts", []))
        if delivery_attempts:
            delivery_attempt = dict(delivery_attempts[-1])
            delivery_attempt["ack_status"] = normalized_status
            delivery_attempt["callback_received_at"] = completed_at
            delivery_attempt["result_locator"] = (
                result_locator if normalized_status == "ACKNOWLEDGED" else ""
            )
            delivery_attempt["error_code"] = (
                operation.get("last_error_code", "") if normalized_status == "FAILED" else ""
            )
            delivery_attempt["error_message"] = (
                operation.get("last_error_message", "") if normalized_status == "FAILED" else ""
            )
            delivery_attempts[-1] = delivery_attempt
            operation["provider_delivery_attempts"] = delivery_attempts
        operation["notes"] = list(operation.get("notes", [])) + [
            f"provider_ack:{normalized_status.lower()}",
        ]

        receipt = AcquisitionRuntimeCallbackReceipt(
            receipt_id=f"acq-callback-{operation['request_id']}-{normalized_status.lower()}",
            request_id=str(operation["request_id"]),
            task_id=str(operation["task_id"]),
            source_id=str(operation["source_id"]),
            operation_key=str(operation["operation_key"]),
            delivery_id=delivery_id,
            callback_token=callback_token,
            provider_job_status=resolve_provider_job_status(
                capability_name=str(operation.get("capability_name", "")),
                operation_status=str(operation.get("status", "")),
                callback_status=normalized_status,
            ),
            callback_status=normalized_status,
            provider_receipt_id=str(operation.get("provider_receipt_id", "")),
            provider_job_id=str(operation.get("provider_job_id", "")),
            result_locator=str(operation.get("result_locator", "") or result_locator),
            completed_at=completed_at,
            input_digest=str(operation.get("input_digest", "")),
            dispatch_count=int(operation.get("dispatch_count", 0)),
            error_code=operation.get("last_error_code", "") if normalized_status == "FAILED" else "",
            error_message=operation.get("last_error_message", "") if normalized_status == "FAILED" else "",
            notes=[
                "simulated provider callback ack registered through acquisition runtime",
            ],
        )
        _acquisition_callback_receipts[receipt.receipt_id] = receipt.model_dump(mode="json")
        finalized_operation = dict(operation)
        _persist_acquisition_runtime_locked()

    resume_attempts: list[dict[str, object]] = []
    if normalized_status == "ACKNOWLEDGED":
        _apply_provider_callback_success(finalized_operation)
        if auto_resume:
            from app.capability_wait_queue import wake_waiting_tasks_for_acquisition_request

            resume_attempts = wake_waiting_tasks_for_acquisition_request(
                str(finalized_operation["request_id"]),
                capability_name=str(finalized_operation["capability_name"]),
                callback_receipt=receipt.model_dump(mode="json"),
                callback_operation=finalized_operation,
            )

    return {
        "operation": finalized_operation,
        "receipt": receipt.model_dump(mode="json"),
        "resume_attempts": resume_attempts,
    }


def _apply_provider_callback_success(operation: dict[str, object]) -> None:
    capability_name = str(operation.get("capability_name", ""))
    provider_id = str(operation.get("provider_id", ""))
    tool_name = str(operation.get("tool_name", ""))
    task_id = str(operation.get("task_id", ""))
    if not capability_name:
        return

    from app.capability_provider import (
        set_capability_provider_approval_status,
        set_capability_provider_discovery_status,
        set_capability_provider_health_status,
        set_capability_provider_status,
    )
    from app.capability_approval_queue import set_capability_request_status

    set_capability_provider_discovery_status(
        capability_name,
        "DISCOVERED",
        provider_id=provider_id,
        tool_name=tool_name,
    )
    set_capability_provider_status(
        capability_name,
        "AVAILABLE",
        provider_id=provider_id,
        tool_name=tool_name,
    )
    set_capability_provider_health_status(
        capability_name,
        "HEALTHY",
        provider_id=provider_id,
        tool_name=tool_name,
    )
    set_capability_provider_approval_status(
        capability_name,
        "APPROVED",
        provider_id=provider_id,
        tool_name=tool_name,
    )
    set_capability_request_status(
        capability_name=capability_name,
        provider_id=provider_id,
        tool_name=tool_name,
        task_id=task_id,
        status="APPROVED",
    )


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _utc_future(*, minutes: int) -> str:
    return (datetime.now(timezone.utc) + timedelta(minutes=minutes)).isoformat()


def _build_input_digest(
    *,
    request_id: str,
    input_locator: str,
    capability_name: str,
    source_id: str,
) -> str:
    raw = "|".join([request_id, input_locator, capability_name, source_id])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def _build_initial_delivery_attempts(
    *,
    operation: object,
    source_id: str,
) -> list[dict[str, object]]:
    if operation.callback_status != "ACKNOWLEDGED" or not operation.capability_name:
        return []
    return [
        {
            "delivery_id": f"acq-delivery-{operation.request_id}-1",
            "dispatch_count": 1,
            "callback_token": operation.adapter_callback_token,
            "dispatched_at": operation.requested_at,
            "callback_deadline_at": "",
            "provider_job_id": operation.provider_job_id,
            "provider_receipt_id": operation.provider_receipt_id,
            "input_digest": _build_input_digest(
                request_id=operation.request_id,
                input_locator=operation.input_locator,
                capability_name=operation.capability_name,
                source_id=source_id,
            ),
            "ack_status": "ACKNOWLEDGED",
            "callback_received_at": operation.callback_received_at or operation.completed_at,
            "result_locator": operation.result_locator,
            "error_code": "",
            "error_message": "",
        }
    ]


def _should_preserve_existing_runtime_operation(existing_operation: dict[str, object]) -> bool:
    callback_status = str(existing_operation.get("callback_status", "")).strip().upper()
    status = str(existing_operation.get("status", "")).strip().upper()
    return callback_status in {
        "DISPATCHED_TO_PROVIDER",
        "ACKNOWLEDGED",
        "FAILED",
    } or status in {"RUNNING", "COMPLETED", "FAILED"}


def _resolve_provider_result_locator(
    *,
    request_id: str,
    provider_payload: dict[str, object],
) -> str:
    for key in ("selected_subtitle_path", "tex_path", "target_locator", "metadata_path"):
        value = str(provider_payload.get(key, "")).strip()
        if value:
            return value
    transcription = provider_payload.get("transcription") or {}
    artifacts = transcription.get("artifacts") or {}
    for key in ("txt_files", "srt_files", "json_files"):
        values = artifacts.get(key) or []
        if values:
            return str(values[0])
    return f"runtime://acquisition/{request_id}"


def _summarize_provider_payload(provider_payload: dict[str, object]) -> dict[str, object]:
    text = _extract_provider_payload_text(provider_payload)
    if not text:
        return {
            "output_summary": "",
            "content_digest": "",
            "payload_char_count": 0,
            "segment_count": 0,
            "source_ref_count": 0,
        }
    normalized_text = text.strip()
    return {
        "output_summary": normalized_text[:120],
        "content_digest": hashlib.sha256(normalized_text.encode("utf-8")).hexdigest()[:12],
        "payload_char_count": len(normalized_text),
        "segment_count": max(1, len([line for line in normalized_text.splitlines() if line.strip()])),
        "source_ref_count": 1,
    }


def _extract_provider_payload_text(provider_payload: dict[str, object]) -> str:
    preview = provider_payload.get("subtitle_preview")
    if isinstance(preview, list):
        preview_lines = [str(line).strip() for line in preview if str(line).strip()]
        if preview_lines:
            return "\n".join(preview_lines)
    transcription = provider_payload.get("transcription") or {}
    artifacts = transcription.get("artifacts") or {}
    for key in ("txt_files", "srt_files"):
        values = artifacts.get(key) or []
        if values:
            try:
                return read_text_file_limited(values[0])
            except (OSError, ContentSizeLimitError):
                continue
    selected_subtitle_path = str(provider_payload.get("selected_subtitle_path", "")).strip()
    if selected_subtitle_path:
        try:
            return read_text_file_limited(selected_subtitle_path)
        except (OSError, ContentSizeLimitError):
            return ""
    return json.dumps(provider_payload, ensure_ascii=False)
