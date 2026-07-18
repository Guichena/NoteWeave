from __future__ import annotations

from threading import Lock


_approval_lock = Lock()
_approval_requests: dict[str, dict[str, object]] = {}


def clear_approval_requests() -> None:
    with _approval_lock:
        _approval_requests.clear()


def create_capability_request(
    *,
    task_id: str,
    workspace_id: str,
    capability_name: str,
    provider_id: str,
    server_id: str,
    tool_name: str,
) -> dict[str, object]:
    request_id = f"approval-{task_id}-{capability_name.lower()}-{provider_id.lower()}"
    record = {
        "request_id": request_id,
        "task_id": task_id,
        "workspace_id": workspace_id,
        "capability_name": capability_name,
        "provider_id": provider_id,
        "server_id": server_id,
        "tool_name": tool_name,
        "status": "PENDING",
    }
    with _approval_lock:
        _approval_requests[request_id] = record
    return record


def list_approval_requests() -> list[dict[str, object]]:
    with _approval_lock:
        return [dict(record) for record in _approval_requests.values()]


def get_approval_request(request_id: str) -> dict[str, object]:
    with _approval_lock:
        record = _approval_requests.get(request_id)
    if record is None:
        raise ValueError(f"approval request not found: {request_id}")
    return dict(record)


def set_capability_request_status(
    *,
    capability_name: str,
    provider_id: str,
    tool_name: str,
    task_id: str = "",
    status: str,
) -> list[dict[str, object]]:
    normalized_capability_name = capability_name.strip().upper()
    normalized_provider_id = provider_id.strip().lower()
    normalized_tool_name = tool_name.strip().lower()
    normalized_task_id = task_id.strip()
    updated_records: list[dict[str, object]] = []
    with _approval_lock:
        for record in _approval_requests.values():
            if str(record.get("capability_name", "")).strip().upper() != normalized_capability_name:
                continue
            if str(record.get("provider_id", "")).strip().lower() != normalized_provider_id:
                continue
            if str(record.get("tool_name", "")).strip().lower() != normalized_tool_name:
                continue
            if normalized_task_id and str(record.get("task_id", "")).strip() != normalized_task_id:
                continue
            record["status"] = status.strip().upper()
            updated_records.append(dict(record))
    return updated_records


def approve_capability_request(request_id: str) -> dict[str, object]:
    with _approval_lock:
        record = _approval_requests.get(request_id)
        if record is None:
            raise ValueError(f"approval request not found: {request_id}")
        record["status"] = "APPROVED"
        approved_record = dict(record)
    from app.capability_provider import set_capability_provider_approval_status

    set_capability_provider_approval_status(
        approved_record["capability_name"],
        "APPROVED",
        provider_id=str(approved_record["provider_id"]),
        tool_name=str(approved_record["tool_name"]),
    )
    from app.capability_wait_queue import wake_waiting_tasks_for_capability

    approved_record["resume_attempts"] = wake_waiting_tasks_for_capability(
        str(approved_record["capability_name"])
    )
    return approved_record
