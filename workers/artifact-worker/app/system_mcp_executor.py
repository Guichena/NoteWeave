from __future__ import annotations

import json
import logging
import threading
import time
from urllib import error, request

from app.acquisition_runtime import (
    dispatch_acquisition_operation,
    get_acquisition_result_payload,
    list_acquisition_operations,
    peek_acquisition_operation,
    record_acquisition_provider_outcome,
)
from app.config import load_settings
from app.error_sanitizer import sanitize_error_message
from app.custom_mcp_executor import _call_custom_mcp_tool
from app.system_mcp_registry import resolve_system_mcp_server
from app.io_limits import validate_json_payload_size
from app.llm_client import credential_safe_urlopen


logger = logging.getLogger(__name__)


_executor_threads_lock = threading.Lock()
_executor_threads: dict[str, threading.Thread] = {}
_callback_threads_lock = threading.Lock()
_callback_threads: dict[str, threading.Thread] = {}


def submit_system_mcp_acquisition_operation(
    request_id: str,
    *,
    java_base_url: str | None = None,
) -> dict[str, object]:
    operation = peek_acquisition_operation(request_id)
    if operation is None:
        raise ValueError(f"acquisition operation not found: {request_id}")
    resolve_system_mcp_server(str(operation.get("server_id", "")))
    dispatch_response = dispatch_acquisition_operation(request_id)
    thread = threading.Thread(
        target=_run_system_mcp_acquisition_operation,
        args=(request_id, (java_base_url or load_settings().java_base_url).rstrip("/")),
        name=f"system-mcp-acq-{request_id}",
        daemon=True,
    )
    with _executor_threads_lock:
        _executor_threads[request_id] = thread
    thread.start()
    return {**dispatch_response, "execution_mode": "SYSTEM_MCP_ASYNC_RUNTIME"}


def wait_for_system_mcp_operation(request_id: str, timeout: float = 30.0) -> dict[str, object]:
    with _executor_threads_lock:
        thread = _executor_threads.get(request_id)
    if thread is not None:
        thread.join(timeout=timeout)
    operation = peek_acquisition_operation(request_id)
    if operation is None:
        raise ValueError(f"acquisition operation not found: {request_id}")
    return operation


def recover_system_mcp_acquisition_operations(
    *,
    java_base_url: str | None = None,
) -> dict[str, object]:
    recovered_request_ids: list[str] = []
    base_url = (java_base_url or load_settings().java_base_url).rstrip("/")
    for operation in list_acquisition_operations():
        if str(operation.get("server_id", "")) != "builtin-bilibili-mcp":
            continue
        request_id = str(operation.get("request_id", ""))
        callback_status = str(operation.get("callback_status", ""))
        status = str(operation.get("status", ""))
        if not request_id or callback_status in {"ACKNOWLEDGED", "FAILED"} or status in {"COMPLETED", "FAILED"}:
            continue
        with _executor_threads_lock:
            if request_id in _executor_threads:
                continue
        if callback_status == "PROVIDER_OUTCOME_READY":
            _schedule_persisted_outcome_delivery(request_id, base_url)
            recovered_request_ids.append(request_id)
            continue
        if callback_status == "DISPATCHED_TO_PROVIDER":
            thread = threading.Thread(
                target=_run_system_mcp_acquisition_operation,
                args=(request_id, base_url),
                name=f"system-mcp-recover-{request_id}",
                daemon=True,
            )
            with _executor_threads_lock:
                _executor_threads[request_id] = thread
            thread.start()
            recovered_request_ids.append(request_id)
            continue
        if status.startswith("WAITING_FOR_"):
            submit_system_mcp_acquisition_operation(request_id, java_base_url=base_url)
            recovered_request_ids.append(request_id)
    return {
        "recovered_count": len(recovered_request_ids),
        "request_ids": recovered_request_ids,
    }


def _run_system_mcp_acquisition_operation(request_id: str, java_base_url: str) -> None:
    operation = peek_acquisition_operation(request_id)
    if operation is None:
        return
    try:
        try:
            server = resolve_system_mcp_server(str(operation.get("server_id", "")))
            executable_operation = dict(operation)
            if executable_operation.get("tool_name") == "get_subtitle":
                executable_operation["tool_name"] = "get_bilibili_subtitle"
            provider_payload = _call_custom_mcp_tool(server, executable_operation)
            validate_json_payload_size(provider_payload, label="system MCP provider payload")
        except Exception as exc:  # pragma: no cover - exercised through observable callback state
            record_acquisition_provider_outcome(
                request_id=request_id,
                final_status="FAILED",
                result_locator="",
                error_code="SYSTEM_MCP_EXECUTION_FAILED",
                error_message=sanitize_error_message(str(exc)),
                provider_payload={},
            )
            _deliver_persisted_outcome_or_schedule(request_id, java_base_url)
            return

        record_acquisition_provider_outcome(
            request_id=request_id,
            final_status="ACKNOWLEDGED",
            result_locator=_result_locator(provider_payload),
            error_code="",
            error_message="",
            provider_payload=provider_payload,
        )
        _deliver_persisted_outcome_or_schedule(request_id, java_base_url)
    finally:
        with _executor_threads_lock:
            _executor_threads.pop(request_id, None)


def _deliver_persisted_outcome_or_schedule(
    request_id: str, java_base_url: str
) -> None:
    try:
        _send_host_ack(
            java_base_url=java_base_url,
            payload=_persisted_outcome_ack_payload(request_id),
        )
    except Exception:
        logger.exception(
            "System MCP outcome acknowledgement could not be delivered; scheduling persisted outcome retry: request_id=%s",
            request_id,
        )
        _schedule_persisted_outcome_delivery(request_id, java_base_url)


def _persisted_outcome_ack_payload(request_id: str) -> dict[str, object]:
    operation = peek_acquisition_operation(request_id)
    if operation is None:
        raise ValueError(f"acquisition operation not found: {request_id}")
    if str(operation.get("callback_status", "")) != "PROVIDER_OUTCOME_READY":
        raise ValueError(f"provider outcome is not pending delivery: {request_id}")
    return {
        "callback_token": str(operation.get("callback_token", "")),
        "final_status": str(operation.get("provider_outcome_status", "")),
        "result_locator": str(operation.get("provider_outcome_result_locator", "")),
        "error_code": str(operation.get("provider_outcome_error_code", "")),
        "error_message": str(operation.get("provider_outcome_error_message", "")),
        "provider_payload": get_acquisition_result_payload(request_id) or {},
    }


def _schedule_persisted_outcome_delivery(
    request_id: str, java_base_url: str
) -> None:
    with _callback_threads_lock:
        existing = _callback_threads.get(request_id)
        if existing is not None and existing.is_alive():
            return
        thread = threading.Thread(
            target=_retry_persisted_outcome_delivery,
            args=(request_id, java_base_url),
            name=f"system-mcp-callback-{request_id}",
            daemon=True,
        )
        _callback_threads[request_id] = thread
    thread.start()


def _retry_persisted_outcome_delivery(
    request_id: str, java_base_url: str
) -> None:
    delay_seconds = 1.0
    try:
        while True:
            operation = peek_acquisition_operation(request_id)
            if operation is None or str(operation.get("callback_status", "")) != "PROVIDER_OUTCOME_READY":
                return
            try:
                _send_host_ack(
                    java_base_url=java_base_url,
                    payload=_persisted_outcome_ack_payload(request_id),
                )
                return
            except Exception:
                logger.exception(
                    "Persisted System MCP outcome delivery failed; retrying: request_id=%s",
                    request_id,
                )
                time.sleep(delay_seconds)
                delay_seconds = min(30.0, delay_seconds * 2)
    finally:
        with _callback_threads_lock:
            _callback_threads.pop(request_id, None)


def _send_host_ack(*, java_base_url: str, payload: dict[str, object]) -> None:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(3):
        internal_auth_token = load_settings().internal_auth_token.strip()
        req = request.Request(
            url=f"{java_base_url}/internal/worker/artifact-callbacks/acquisition/ack",
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                **(
                    {"X-NoteWeave-Internal-Token": internal_auth_token}
                    if internal_auth_token
                    else {}
                ),
            },
        )
        try:
            with credential_safe_urlopen(req, timeout=30) as response:
                response.read()
            return
        except (error.HTTPError, error.URLError, TimeoutError) as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(0.5 * (2**attempt))
    raise RuntimeError(f"system MCP host callback failed: {last_error}")


def _result_locator(provider_payload: dict[str, object]) -> str:
    for key in ("result_locator", "selected_subtitle_path", "output_pdf_path", "output_tex_path"):
        value = str(provider_payload.get(key) or "").strip()
        if value:
            return value
    artifacts = provider_payload.get("artifacts")
    if isinstance(artifacts, dict):
        for value in artifacts.values():
            if isinstance(value, str) and value.strip():
                return value.strip()
            if isinstance(value, list) and value:
                return str(value[0]).strip()
    return ""
