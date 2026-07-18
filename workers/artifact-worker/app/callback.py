from __future__ import annotations

import json
import hashlib
import logging
from typing import Any, Protocol
from urllib import error, request

from pydantic import BaseModel

from app.config import load_settings
from app.acquisition_runtime import acknowledge_acquisition_operation
from app.capability_wait_queue import (
    list_waiting_tasks,
    remove_waiting_task,
    release_waiting_task_claim,
    to_public_callback_operation,
    wake_waiting_task,
)
from app.models import ArtifactProgressEvent, ArtifactTaskInput, ArtifactTaskResult
from app.runner import run_artifact_task
from app.system_mcp_executor import submit_system_mcp_acquisition_operation
from app.system_mcp_registry import SYSTEM_BILIBILI_SERVER_ID


logger = logging.getLogger(__name__)


class ArtifactWorkerExecutionResponse(BaseModel):
    task_id: str
    status: str
    progress_events: int
    result_title: str = ""


class ArtifactAcquisitionAckExecutionResponse(BaseModel):
    operation: dict[str, object]
    receipt: dict[str, object]
    resumed_tasks: list[ArtifactWorkerExecutionResponse]


class ArtifactCallbackClient(Protocol):
    def fetch_task_input(self, task_id: str) -> ArtifactTaskInput:
        ...

    def send_progress(self, task_id: str, event: ArtifactProgressEvent) -> None:
        ...

    def send_complete(self, task_id: str, result: ArtifactTaskResult) -> None:
        ...

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str) -> None:
        ...


class JavaArtifactCallbackClient:
    def __init__(self, java_base_url: str, internal_auth_token: str = "") -> None:
        self.java_base_url = java_base_url.rstrip("/")
        self.internal_auth_token = internal_auth_token.strip()

    def fetch_task_input(self, task_id: str) -> ArtifactTaskInput:
        response = self._request("GET", f"/internal/worker/artifact-tasks/{task_id}/input")
        return ArtifactTaskInput.model_validate(_unwrap_api_response(response))

    def send_progress(self, task_id: str, event: ArtifactProgressEvent) -> None:
        payload = event.model_dump(mode="json")
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/progress",
            payload,
            idempotency_key=_callback_idempotency_key(
                task_id,
                "progress",
                event.phase,
            ),
        )

    def send_complete(self, task_id: str, result: ArtifactTaskResult) -> None:
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/complete",
            result.model_dump(mode="json"),
            idempotency_key=_callback_idempotency_key(task_id, "complete"),
        )

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str) -> None:
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/fail",
            {
                "phase": phase,
                "error_code": error_code,
                "error_message": error_message,
                "retryable": True,
            },
            idempotency_key=_callback_idempotency_key(task_id, "fail", phase),
        )

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        *,
        idempotency_key: str = "",
    ) -> dict[str, Any]:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        req = request.Request(
            url=f"{self.java_base_url}{path}",
            data=body,
            method=method,
            headers={
                "Content-Type": "application/json",
                **(
                    {"X-NoteWeave-Internal-Token": self.internal_auth_token}
                    if self.internal_auth_token
                    else {}
                ),
                **(
                    {"X-NoteWeave-Idempotency-Key": idempotency_key}
                    if idempotency_key
                    else {}
                ),
            },
        )
        try:
            with request.urlopen(req, timeout=30) as response:
                text = response.read().decode("utf-8")
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Java callback failed: {exc.code} {detail}") from exc
        except error.URLError as exc:
            raise RuntimeError(f"Java callback unavailable: {exc.reason}") from exc
        return json.loads(text) if text else {}


def run_artifact_task_with_callbacks(
    task_id: str,
    client: ArtifactCallbackClient | None = None,
) -> ArtifactWorkerExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaArtifactCallbackClient(
        settings.java_base_url,
        settings.internal_auth_token,
    )
    task_input = callback_client.fetch_task_input(task_id)
    try:
        events, result = run_artifact_task(task_input)
    except Exception as exc:
        _report_execution_failure(callback_client, task_id, "WORKER_EXECUTION", exc)
        raise
    return _emit_callbacks_for_result(task_id, events, result, callback_client)


def resume_waiting_artifact_task_with_callbacks(
    task_id: str,
    *,
    client: ArtifactCallbackClient | None = None,
    request_id: str = "",
    callback_receipt: dict[str, object] | None = None,
    callback_operation: dict[str, object] | None = None,
) -> ArtifactWorkerExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaArtifactCallbackClient(
        settings.java_base_url,
        settings.internal_auth_token,
    )
    try:
        events, result = wake_waiting_task(
            task_id,
            request_id=request_id,
            callback_receipt=callback_receipt,
            callback_operation=callback_operation,
            remove_on_success=False,
        )
    except ValueError:
        raise
    except Exception as exc:
        _report_execution_failure(callback_client, task_id, "WORKER_RESUME", exc)
        raise
    try:
        response = _emit_callbacks_for_result(task_id, events, result, callback_client)
    except Exception:
        release_waiting_task_claim(task_id)
        raise
    if not _is_waiting_status(result.job_snapshot.status):
        remove_waiting_task(task_id)
    return response


def acknowledge_acquisition_operation_with_callbacks(
    *,
    callback_token: str,
    final_status: str,
    result_locator: str = "",
    error_code: str = "",
    error_message: str = "",
    provider_payload: dict[str, object] | None = None,
    client: ArtifactCallbackClient | None = None,
) -> ArtifactAcquisitionAckExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaArtifactCallbackClient(
        settings.java_base_url,
        settings.internal_auth_token,
    )
    ack_result = acknowledge_acquisition_operation(
        callback_token=callback_token,
        final_status=final_status,
        result_locator=result_locator,
        error_code=error_code,
        error_message=error_message,
        provider_payload=provider_payload,
        auto_resume=False,
    )
    resumed_tasks: list[ArtifactWorkerExecutionResponse] = []
    if str(ack_result["receipt"].get("callback_status", "")).upper() == "ACKNOWLEDGED":
        request_id = str(ack_result["operation"].get("request_id", ""))
        capability_name = str(ack_result["operation"].get("capability_name", "")).strip().upper()
        for record in _matching_waiting_tasks(request_id=request_id, capability_name=capability_name):
            resumed_tasks.append(
                resume_waiting_artifact_task_with_callbacks(
                    str(record["task_id"]),
                    client=callback_client,
                    request_id=request_id,
                    callback_receipt=ack_result["receipt"],
                    callback_operation=ack_result["operation"],
                )
            )
    return ArtifactAcquisitionAckExecutionResponse(
        operation=to_public_callback_operation(ack_result["operation"]),
        receipt=ack_result["receipt"],
        resumed_tasks=resumed_tasks,
    )


def _is_waiting_status(status: str) -> bool:
    return status.startswith("WAITING_FOR_")


def _emit_callbacks_for_result(
    task_id: str,
    events: list[ArtifactProgressEvent],
    result: ArtifactTaskResult,
    callback_client: ArtifactCallbackClient,
) -> ArtifactWorkerExecutionResponse:
    for event in events:
        callback_client.send_progress(task_id, event)
    if _is_waiting_status(result.job_snapshot.status):
        if isinstance(callback_client, JavaArtifactCallbackClient):
            _dispatch_system_provider_operations(result, callback_client.java_base_url)
    else:
        callback_client.send_complete(task_id, result)
    return ArtifactWorkerExecutionResponse(
        task_id=task_id,
        status=result.job_snapshot.status,
        progress_events=len(events),
        result_title=result.result_title,
    )


def _dispatch_system_provider_operations(
    result: ArtifactTaskResult,
    java_base_url: str,
) -> list[dict[str, object]]:
    receipt = result.result_payload.get("acquisition_receipt")
    if not isinstance(receipt, dict):
        return []
    dispatches: list[dict[str, object]] = []
    for source_receipt in receipt.get("source_receipts", []):
        if not isinstance(source_receipt, dict):
            continue
        for operation in source_receipt.get("operations", []):
            if not isinstance(operation, dict):
                continue
            if operation.get("status") != "WAITING_FOR_PROVIDER":
                continue
            if str(operation.get("server_id", "")) != SYSTEM_BILIBILI_SERVER_ID:
                continue
            dispatches.append(
                submit_system_mcp_acquisition_operation(
                    str(operation.get("request_id", "")),
                    java_base_url=java_base_url,
                )
            )
    return dispatches


def _matching_waiting_tasks(
    *,
    request_id: str,
    capability_name: str,
) -> list[dict[str, object]]:
    normalized_request_id = request_id.strip()
    normalized_capability_name = capability_name.strip().upper()
    matched_records: list[dict[str, object]] = []
    for record in list_waiting_tasks():
        blocked_operations = record.get("blocked_operations", [])
        for operation in blocked_operations:
            operation_request_id = str(operation.get("request_id", "")).strip()
            operation_capability_name = str(operation.get("capability_name", "")).strip().upper()
            if operation_request_id != normalized_request_id:
                continue
            if normalized_capability_name and operation_capability_name and operation_capability_name != normalized_capability_name:
                continue
            matched_records.append(record)
            break
    return matched_records


def _unwrap_api_response(response: dict[str, Any]) -> dict[str, Any]:
    if response.get("success") is False:
        raise RuntimeError(
            f"Java API returned {response.get('code', 'ERROR')}: {response.get('message', '')}"
        )
    data = response.get("data")
    if not isinstance(data, dict):
        raise RuntimeError("Java API response does not contain an object data field")
    return data


def _callback_idempotency_key(
    task_id: str,
    callback_type: str,
    discriminator: str = "",
) -> str:
    digest = hashlib.sha256(
        "|".join([task_id, callback_type, discriminator]).encode("utf-8")
    ).hexdigest()[:32]
    return f"artifact-worker-{callback_type}-{digest}"


def _report_execution_failure(
    callback_client: ArtifactCallbackClient,
    task_id: str,
    phase: str,
    exc: Exception,
) -> None:
    try:
        callback_client.send_fail(
            task_id,
            phase=phase,
            error_code=type(exc).__name__.upper(),
            error_message=str(exc),
        )
    except Exception:
        logger.exception(
            "Artifact execution failed and the failure callback could not be delivered: task_id=%s phase=%s",
            task_id,
            phase,
        )
