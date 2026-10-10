from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from threading import Lock

from app.models import ArtifactProgressEvent, ArtifactTaskInput, ArtifactTaskResult
from app.artifact_repository import sync_artifact_runtime_trace
from app.file_store_lease import FileStoreLease


_waiting_tasks_lock = Lock()
_waiting_tasks: dict[str, dict[str, object]] = {}
_waiting_tasks_storage_path: Path | None = None
_waiting_tasks_store_lease: FileStoreLease | None = None
logger = logging.getLogger(__name__)


class WaitingTaskClaimConflict(ValueError):
    def __init__(self, task_id: str, claim_status: str) -> None:
        self.task_id = task_id
        self.claim_status = claim_status
        message = (
            f"waiting task not found: {task_id}"
            if claim_status == "NOT_FOUND"
            else f"waiting task is already being resumed: {task_id}"
        )
        super().__init__(message)


class WaitingTaskReplayRequired(ValueError):
    def __init__(
        self,
        task_id: str,
        reason: str,
        replay_record: dict[str, object],
    ) -> None:
        self.task_id = task_id
        self.replay_record = dict(replay_record)
        super().__init__(reason)


def configure_waiting_task_store(storage_path: str | Path | None) -> None:
    global _waiting_tasks_storage_path, _waiting_tasks_store_lease
    with _waiting_tasks_lock:
        if _waiting_tasks_store_lease is not None:
            _waiting_tasks_store_lease.release()
            _waiting_tasks_store_lease = None
        _waiting_tasks_storage_path = Path(storage_path) if storage_path else None
        if _waiting_tasks_storage_path is not None:
            _waiting_tasks_store_lease = FileStoreLease(_waiting_tasks_storage_path)
            _waiting_tasks_store_lease.acquire()
        _waiting_tasks.clear()
        if _waiting_tasks_storage_path and _waiting_tasks_storage_path.exists():
            try:
                payload = json.loads(_waiting_tasks_storage_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                logger.error("Ignoring corrupt artifact wait queue file: path=%s error=%s", _waiting_tasks_storage_path, exc)
                _quarantine_corrupt_file(_waiting_tasks_storage_path)
                payload = {}
            records = payload.get("waiting_tasks", {}) if isinstance(payload, dict) else {}
            if isinstance(records, dict):
                _waiting_tasks.update(
                    {str(key): dict(value) for key, value in records.items() if isinstance(value, dict)}
                )
                recovered_claim = False
                for task_id, record in list(_waiting_tasks.items()):
                    if str(record.get("status", "")).upper() != "RESUMING":
                        continue
                    restored_record = dict(record)
                    restored_record["status"] = str(
                        restored_record.pop("resume_from_status", "WAITING_FOR_PROVIDER")
                    )
                    _waiting_tasks[task_id] = restored_record
                    recovered_claim = True
                if recovered_claim:
                    _persist_waiting_tasks_locked()


def _persist_waiting_tasks_locked() -> None:
    if _waiting_tasks_storage_path is None:
        return
    _waiting_tasks_storage_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = _waiting_tasks_storage_path.with_suffix(
        f"{_waiting_tasks_storage_path.suffix}.tmp"
    )
    serialized = json.dumps({"waiting_tasks": _waiting_tasks}, ensure_ascii=False, indent=2)
    temporary_path.write_text(serialized, encoding="utf-8")
    try:
        os.replace(temporary_path, _waiting_tasks_storage_path)
    except PermissionError:
        # Preserve the complete temporary file. A non-atomic overwrite can corrupt
        # the queue and is worse than retrying persistence on the next mutation.
        logger.exception(
            "Could not atomically replace artifact wait queue: path=%s",
            _waiting_tasks_storage_path,
        )
        raise


def _quarantine_corrupt_file(path: Path) -> None:
    try:
        quarantine = path.with_suffix(f"{path.suffix}.corrupt")
        os.replace(path, quarantine)
    except OSError:
        logger.exception("Could not quarantine corrupt artifact wait queue: path=%s", path)


def clear_waiting_tasks() -> None:
    with _waiting_tasks_lock:
        _waiting_tasks.clear()
        _persist_waiting_tasks_locked()


def enqueue_waiting_task(
    *,
    task_input: ArtifactTaskInput,
    unavailable_capabilities: list[str],
    status: str = "WAITING_FOR_CAPABILITY",
    approval_request: dict[str, object] | None = None,
    blocked_operations: list[dict[str, object]] | None = None,
    resume_checkpoint: dict[str, object] | None = None,
) -> dict[str, object]:
    record = {
        "task_id": task_input.task_id,
        "workspace_id": task_input.workspace_id,
        "target_id": task_input.target_id,
        "status": status,
        "unavailable_capabilities": unavailable_capabilities,
        "approval_request": approval_request or {},
        "blocked_operations": blocked_operations or [],
        "resume_checkpoint": resume_checkpoint or {},
        "task_input": task_input.model_dump(mode="json"),
    }
    with _waiting_tasks_lock:
        _waiting_tasks[task_input.task_id] = record
        _persist_waiting_tasks_locked()
    return record


def get_waiting_task(task_id: str) -> dict[str, object] | None:
    with _waiting_tasks_lock:
        record = _waiting_tasks.get(task_id)
        return dict(record) if record is not None else None


def list_waiting_tasks() -> list[dict[str, object]]:
    with _waiting_tasks_lock:
        return [dict(record) for record in _waiting_tasks.values()]


def remove_waiting_task(task_id: str) -> None:
    with _waiting_tasks_lock:
        _waiting_tasks.pop(task_id, None)
        _persist_waiting_tasks_locked()


def cache_waiting_task_delivery(
    task_id: str,
    events: list[ArtifactProgressEvent],
    result: ArtifactTaskResult,
) -> None:
    with _waiting_tasks_lock:
        record = _waiting_tasks.get(task_id)
        if record is None or str(record.get("status", "")).upper() != "RESUMING":
            raise ValueError(f"waiting task is not claimed for delivery: {task_id}")
        cached_record = dict(record)
        cached_record["resume_delivery"] = {
            "events": [event.model_dump(mode="json") for event in events],
            "result": result.model_dump(mode="json"),
        }
        _waiting_tasks[task_id] = cached_record
        _persist_waiting_tasks_locked()


def release_waiting_task_claim(task_id: str) -> None:
    with _waiting_tasks_lock:
        record = _waiting_tasks.get(task_id)
        if record is None or str(record.get("status", "")).upper() != "RESUMING":
            return
        released_record = dict(record)
        released_record["status"] = str(
            released_record.pop("resume_from_status", "WAITING_FOR_PROVIDER")
        )
        _waiting_tasks[task_id] = released_record
        _persist_waiting_tasks_locked()


def mark_waiting_task_replay_required(
    task_id: str,
    reason: str,
) -> dict[str, object] | None:
    with _waiting_tasks_lock:
        record = _waiting_tasks.get(task_id)
        if record is None or str(record.get("status", "")).upper() != "RESUMING":
            return None
        replay_record = dict(record)
        replay_record.pop("resume_from_status", None)
        replay_record["status"] = "REPLAY_REQUIRED"
        replay_record["resume_error"] = str(reason)[:1000]
        _waiting_tasks[task_id] = replay_record
        _persist_waiting_tasks_locked()
        return dict(replay_record)


def _claim_waiting_task(task_id: str) -> dict[str, object]:
    with _waiting_tasks_lock:
        record = _waiting_tasks.get(task_id)
        if record is None:
            raise WaitingTaskClaimConflict(task_id, "NOT_FOUND")
        current_status = str(record.get("status", ""))
        if current_status.upper() == "RESUMING":
            raise WaitingTaskClaimConflict(task_id, "ALREADY_RESUMING")
        claimed_record = dict(record)
        claimed_record["resume_from_status"] = current_status or "WAITING_FOR_PROVIDER"
        claimed_record["status"] = "RESUMING"
        _waiting_tasks[task_id] = claimed_record
        _persist_waiting_tasks_locked()
        return dict(record)


def wake_waiting_task(
    task_id: str,
    request_id: str = "",
    callback_receipt: dict[str, object] | None = None,
    callback_operation: dict[str, object] | None = None,
    remove_on_success: bool = True,
) -> tuple[list[object], object]:
    return _wake_waiting_task(
        task_id,
        request_id=request_id,
        callback_receipt=callback_receipt,
        callback_operation=callback_operation,
        remove_on_success=remove_on_success,
    )


def _wake_waiting_task(
    task_id: str,
    *,
    request_id: str = "",
    callback_receipt: dict[str, object] | None = None,
    callback_operation: dict[str, object] | None = None,
    remove_on_success: bool = True,
) -> tuple[list[object], object]:
    record = _claim_waiting_task(task_id)
    matched_operation = _match_blocked_operation(record, request_id=request_id)
    if request_id and not matched_operation:
        release_waiting_task_claim(task_id)
        raise ValueError(f"waiting task does not contain blocked operation: {request_id}")

    cached_delivery = record.get("resume_delivery")
    if isinstance(cached_delivery, dict):
        raw_events = cached_delivery.get("events", [])
        raw_result = cached_delivery.get("result")
        if isinstance(raw_events, list) and isinstance(raw_result, dict):
            events = [ArtifactProgressEvent.model_validate(event) for event in raw_events]
            result = ArtifactTaskResult.model_validate(raw_result)
            if remove_on_success:
                remove_waiting_task(task_id)
            return events, result

    from app.runner import resume_artifact_task

    try:
        task_input = ArtifactTaskInput.model_validate(record["task_input"])
        resume_checkpoint = record.get("resume_checkpoint")
        if not isinstance(resume_checkpoint, dict) or not resume_checkpoint:
            raise ValueError(
                "artifact waiting record is missing resume_checkpoint; legacy full-task restart is disabled"
            )
        events, result = resume_artifact_task(task_input, resume_checkpoint)
    except ValueError as exc:
        replay_record = mark_waiting_task_replay_required(task_id, str(exc))
        if replay_record is None:
            raise
        raise WaitingTaskReplayRequired(task_id, str(exc), replay_record) from exc
    except Exception:
        release_waiting_task_claim(task_id)
        raise
    try:
        if matched_operation:
            resume_scope = {
                "matched_request_id": str(matched_operation.get("request_id", "")),
                "matched_source_id": str(matched_operation.get("source_id", "")),
                "matched_operation_key": str(matched_operation.get("operation_key", "")),
                "matched_capability_name": str(matched_operation.get("capability_name", "")),
            }
            result.result_payload["resume_scope"] = resume_scope
            _attach_resume_lifecycle_trace(
                result=result,
                record=record,
                resume_scope=resume_scope,
            )
            if callback_receipt:
                result.result_payload["acquisition_callback_trace"] = {
                    "status": "ATTACHED",
                    "matched_request_id": str(callback_receipt.get("request_id", "")),
                    "receipt": dict(callback_receipt),
                    "operation": to_public_callback_operation(callback_operation),
                }
            if result.version_snapshot.version_id:
                sync_artifact_runtime_trace(
                    target_id=task_input.target_id,
                    version_id=result.version_snapshot.version_id,
                    result=result,
                )
    except Exception:
        release_waiting_task_claim(task_id)
        raise
    if not str(result.job_snapshot.status).startswith("WAITING_FOR_"):
        if remove_on_success:
            remove_waiting_task(task_id)
    return events, result


def wake_waiting_tasks_for_capability(capability_name: str) -> list[dict[str, object]]:
    normalized_capability_name = capability_name.strip().upper()
    resume_attempts: list[dict[str, object]] = []

    for record in list_waiting_tasks():
        if not str(record.get("status", "")).upper().startswith("WAITING_FOR_"):
            continue
        approval_request = record.get("approval_request") or {}
        unavailable_capabilities = [
            str(item).strip().upper()
            for item in record.get("unavailable_capabilities", [])
        ]
        approval_capability_name = str(
            approval_request.get("capability_name", "")
        ).strip().upper()
        blocked_operations = [
            dict(item)
            for item in record.get("blocked_operations", [])
            if str(item.get("capability_name", "")).strip().upper() == normalized_capability_name
        ]
        if normalized_capability_name not in unavailable_capabilities and normalized_capability_name != approval_capability_name:
            continue

        try:
            events, result = wake_waiting_task(str(record["task_id"]))
        except WaitingTaskClaimConflict as exc:
            _log_batch_claim_conflict(exc)
            continue
        except WaitingTaskReplayRequired as exc:
            resume_attempts.append(_build_replay_required_attempt(record, exc.replay_record))
            continue
        resume_attempts.append(
            _build_resume_attempt(
                record=record,
                events=events,
                result=result,
                matched_operation=blocked_operations[0] if blocked_operations else {},
            )
        )

    return resume_attempts


def to_public_callback_operation(
    callback_operation: dict[str, object] | None,
) -> dict[str, object]:
    operation = dict(callback_operation or {})
    provider_id = str(operation.get("provider_id", ""))
    server_id = str(operation.get("server_id", ""))
    tool_name = str(operation.get("tool_name", ""))
    return {
        "request_id": str(operation.get("request_id", "")),
        "operation_key": str(operation.get("operation_key", "")),
        "capability_name": str(operation.get("capability_name", "")),
        "provider_id": provider_id,
        "server_id": server_id,
        "tool_name": _to_public_tool_name(
            provider_id=provider_id,
            server_id=server_id,
            tool_name=tool_name,
        ),
        "provider_status": str(operation.get("provider_status", "")),
        "health_status": str(operation.get("health_status", "")),
        "delivery_id": str(operation.get("delivery_id", "")),
        "provider_job_id": str(operation.get("provider_job_id", "")),
        "provider_receipt_id": str(operation.get("provider_receipt_id", "")),
        "callback_token": str(operation.get("callback_token", "")),
        "provider_job_status": str(operation.get("provider_job_status", "")),
        "callback_status": str(operation.get("callback_status", "")),
    }


def wake_waiting_tasks_for_acquisition_request(
    request_id: str,
    *,
    capability_name: str = "",
    callback_receipt: dict[str, object] | None = None,
    callback_operation: dict[str, object] | None = None,
) -> list[dict[str, object]]:
    normalized_request_id = request_id.strip()
    normalized_capability_name = capability_name.strip().upper()
    resume_attempts: list[dict[str, object]] = []

    for record in list_waiting_tasks():
        if not str(record.get("status", "")).upper().startswith("WAITING_FOR_"):
            continue
        blocked_operations = [dict(item) for item in record.get("blocked_operations", [])]
        matched_operation = _match_blocked_operation(record, request_id=normalized_request_id)
        if not matched_operation:
            continue
        if normalized_capability_name:
            matched_capability_name = str(matched_operation.get("capability_name", "")).strip().upper()
            if matched_capability_name and matched_capability_name != normalized_capability_name:
                continue

        try:
            events, result = _wake_waiting_task(
                str(record["task_id"]),
                request_id=normalized_request_id,
                callback_receipt=callback_receipt,
                callback_operation=callback_operation,
            )
        except WaitingTaskClaimConflict as exc:
            _log_batch_claim_conflict(exc)
            continue
        except WaitingTaskReplayRequired as exc:
            resume_attempts.append(_build_replay_required_attempt(record, exc.replay_record))
            continue
        resume_attempts.append(
            _build_resume_attempt(
                record=record,
                events=events,
                result=result,
                matched_operation=matched_operation,
            )
        )

    return resume_attempts


def _log_batch_claim_conflict(conflict: WaitingTaskClaimConflict) -> None:
    if conflict.claim_status == "NOT_FOUND":
        logger.info(
            "Skipping waiting task already resumed by another worker: task_id=%s",
            conflict.task_id,
        )
        return
    logger.info(
        "Skipping waiting task claimed by another worker: task_id=%s",
        conflict.task_id,
    )


def _build_resume_attempt(
    *,
    record: dict[str, object],
    events: list[object],
    result: object,
    matched_operation: dict[str, object],
) -> dict[str, object]:
    return {
        "task_id": record["task_id"],
        "previous_status": record["status"],
        "result_status": result.job_snapshot.status,
        "last_phase": events[-1].phase if events else "",
        "still_waiting": str(result.job_snapshot.status).startswith("WAITING_FOR_"),
        "matched_request_id": str(matched_operation.get("request_id", "")),
        "matched_source_id": str(matched_operation.get("source_id", "")),
        "matched_operation_key": str(matched_operation.get("operation_key", "")),
    }


def _build_replay_required_attempt(
    record: dict[str, object],
    replay_record: dict[str, object],
) -> dict[str, object]:
    return {
        "task_id": record["task_id"],
        "previous_status": record["status"],
        "result_status": "REPLAY_REQUIRED",
        "last_phase": "",
        "still_waiting": False,
        "matched_request_id": "",
        "matched_source_id": "",
        "matched_operation_key": "",
        "resume_error": str(replay_record.get("resume_error", "")),
    }


def _to_public_tool_name(
    *,
    provider_id: str,
    server_id: str,
    tool_name: str,
) -> str:
    normalized_provider_id = provider_id.strip().lower()
    normalized_server_id = server_id.strip().lower()
    normalized_tool_name = tool_name.strip()
    if (
        normalized_tool_name == "get_subtitle"
        and normalized_provider_id == "builtin-bilibili-mcp"
        and normalized_server_id == "builtin-bilibili-mcp"
    ):
        return "get_bilibili_subtitle"
    return normalized_tool_name


def _attach_resume_lifecycle_trace(
    *,
    result: object,
    record: dict[str, object],
    resume_scope: dict[str, object],
) -> None:
    lifecycle_trace = dict(result.result_payload.get("lifecycle_trace", {}))
    existing_steps = lifecycle_trace.get("steps", [])
    normalized_steps = (
        [dict(step) for step in existing_steps if isinstance(step, dict)]
        if isinstance(existing_steps, list)
        else []
    )
    resume_step = {
        "phase": "RESUMING",
        "status": "COMPLETED",
        "progress_percent": 40,
        "message": "waiting task resumed after blocked provider operation matched resume request",
        "metrics": {
            "previous_status": str(record.get("status", "")),
            "matched_request_id": resume_scope.get("matched_request_id", ""),
            "matched_operation_key": resume_scope.get("matched_operation_key", ""),
        },
    }
    if not normalized_steps or normalized_steps[0].get("phase") != "RESUMING":
        normalized_steps.insert(0, resume_step)
    lifecycle_trace["steps"] = normalized_steps
    lifecycle_trace["resume_scope"] = dict(resume_scope)
    notes = lifecycle_trace.get("notes", [])
    normalized_notes = (
        [str(note) for note in notes if str(note).strip()]
        if isinstance(notes, list)
        else []
    )
    normalized_notes.append(
        f"resumed_from={record.get('status', '')}"
    )
    lifecycle_trace["notes"] = normalized_notes
    result.result_payload["lifecycle_trace"] = lifecycle_trace


def _match_blocked_operation(
    record: dict[str, object],
    *,
    request_id: str,
) -> dict[str, object]:
    normalized_request_id = request_id.strip()
    if not normalized_request_id:
        return {}
    blocked_operations = [dict(item) for item in record.get("blocked_operations", [])]
    return next(
        (
            item
            for item in blocked_operations
            if str(item.get("request_id", "")).strip() == normalized_request_id
        ),
        {},
    )
