from __future__ import annotations

import json
import hashlib
import os
import uuid
from datetime import datetime, timezone
from typing import Any, Protocol
from urllib import error, request

from pydantic import BaseModel

from app.config import load_settings
from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult
from app.runner import run_research_task
from app.trace_security import sanitize_error_message


class ResearchWorkerExecutionResponse(BaseModel):
    task_id: str
    status: str
    progress_events: int
    result_title: str = ""


class ResearchCallbackClient(Protocol):
    def fetch_task_input(self, task_id: str) -> ResearchTaskInput:
        ...

    def send_progress(self, task_id: str, event: ResearchProgressEvent) -> None:
        ...

    def send_complete(self, task_id: str, result: ResearchTaskResult) -> None:
        ...

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str) -> None:
        ...


class ResearchCancelledError(RuntimeError):
    pass


class JavaResearchCallbackClient:
    def __init__(self, java_base_url: str, internal_auth_token: str = "") -> None:
        self.java_base_url = java_base_url.rstrip("/")
        self.internal_auth_token = internal_auth_token.strip()
        self.worker_instance_id = os.getenv("NOTEWEAVE_WORKER_INSTANCE_ID", "research-worker-local")
        self.execution_id = os.getenv("NOTEWEAVE_WORKER_EXECUTION_ID", "").strip() or uuid.uuid4().hex
        self._progress_sequence = 0
        self._heartbeat_sequence = 0

    def fetch_task_input(self, task_id: str) -> ResearchTaskInput:
        response = self._request("GET", f"/internal/worker/research-tasks/{task_id}/input")
        return ResearchTaskInput.model_validate(_unwrap_api_response(response))

    def send_progress(self, task_id: str, event: ResearchProgressEvent) -> None:
        self._progress_sequence += 1
        response = self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/progress",
            event.model_dump(mode="json"),
            idempotency_key=f"{task_id}:{self.execution_id}:progress:{self._progress_sequence}:{event.phase}",
        )
        self._raise_if_cancelled(response)

    def send_checkpoint(self, task_id: str, checkpoint: dict[str, object]) -> None:
        checkpoint_no = int(checkpoint.get("checkpoint_no") or 1)
        digest = hashlib.sha256(
            json.dumps(checkpoint, ensure_ascii=False, sort_keys=True).encode("utf-8")
        ).hexdigest()
        response = self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/progress",
            {
                "phase": "RESEARCH_CHECKPOINT",
                "progress_percent": min(95, 20 + checkpoint_no * 15),
                "message": f"durable research checkpoint {checkpoint_no}",
                "metrics": {"checkpoint_no": checkpoint_no, "payload_sha256": digest},
                "payload": {"research_checkpoint_candidate": checkpoint},
            },
            idempotency_key=f"{task_id}:checkpoint:{checkpoint_no}:{digest}",
        )
        self._raise_if_cancelled(response)

    def send_heartbeat(self, task_id: str, phase: str) -> None:
        self._heartbeat_sequence += 1
        response = self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/heartbeat",
            {
                "worker_type": "research",
                "worker_instance_id": self.worker_instance_id,
                "phase": phase,
                "heartbeat_at": datetime.now(timezone.utc).isoformat(),
            },
            idempotency_key=f"{task_id}:{self.execution_id}:heartbeat:{phase}:{self._heartbeat_sequence}",
        )
        self._raise_if_cancelled(response)

    def check_cancelled(self, task_id: str) -> None:
        self.send_heartbeat(task_id, "RESEARCH_LOOP")

    def send_complete(self, task_id: str, result: ResearchTaskResult) -> None:
        response = self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/complete",
            result.model_dump(mode="json"),
            idempotency_key=f"{task_id}:complete",
        )
        self._raise_if_cancelled(response)

    def send_fail(self, task_id: str, phase: str, error_code: str, error_message: str) -> None:
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/fail",
            {
                "phase": phase,
                "error_code": error_code,
                "error_message": error_message,
                "retryable": _is_retryable_worker_error(error_code),
            },
            idempotency_key=f"{task_id}:fail:{error_code}",
        )

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
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
                **({"X-NoteWeave-Idempotency-Key": idempotency_key} if idempotency_key else {}),
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

    def _raise_if_cancelled(self, response: dict[str, Any]) -> None:
        data = response.get("data") if isinstance(response, dict) else None
        status = str(data.get("status") if isinstance(data, dict) else "").upper()
        if status == "CANCELLED":
            raise ResearchCancelledError("research task cancellation acknowledged by coordinator")


def run_research_task_with_callbacks(
    task_id: str,
    client: ResearchCallbackClient | None = None,
) -> ResearchWorkerExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaResearchCallbackClient(
        settings.java_base_url,
        settings.internal_auth_token,
    )
    try:
        task_input = callback_client.fetch_task_input(task_id)
        heartbeat = getattr(callback_client, "send_heartbeat", None)
        if callable(heartbeat):
            heartbeat(task_id, "STARTING")
        checkpoint_sender = getattr(callback_client, "send_checkpoint", None)
        cancellation_check = getattr(callback_client, "check_cancelled", None)
        events, result = run_research_task(
            task_input,
            progress_callback=lambda event: callback_client.send_progress(task_id, event),
            checkpoint_callback=(
                (lambda checkpoint: checkpoint_sender(task_id, checkpoint))
                if callable(checkpoint_sender)
                else None
            ),
            cancellation_checker=(
                (lambda: cancellation_check(task_id))
                if callable(cancellation_check)
                else None
            ),
        )
        callback_client.send_complete(task_id, result)
        return ResearchWorkerExecutionResponse(
            task_id=task_id,
            status="COMPLETED",
            progress_events=len(events),
            result_title=result.result_title,
        )
    except ResearchCancelledError:
        return ResearchWorkerExecutionResponse(
            task_id=task_id,
            status="CANCELLED",
            progress_events=0,
        )
    except Exception as exc:
        safe_error_message = sanitize_error_message(exc)
        try:
            callback_client.send_progress(
                task_id,
                ResearchProgressEvent(
                    phase="FAILED",
                    progress_percent=100,
                    message="research worker execution failed",
                    metrics={
                        "error_code": type(exc).__name__.upper(),
                        "phase": "WORKER_EXECUTION",
                    },
                    payload={"error_message": safe_error_message},
                ),
            )
        except Exception:
            pass
        callback_client.send_fail(
            task_id,
            phase="WORKER_EXECUTION",
            error_code=type(exc).__name__.upper(),
            error_message=safe_error_message,
        )
        raise


def _unwrap_api_response(response: dict[str, Any]) -> dict[str, Any]:
    if response.get("success") is False:
        raise RuntimeError(
            f"Java API returned {response.get('code', 'ERROR')}: {response.get('message', '')}"
        )
    data = response.get("data")
    if not isinstance(data, dict):
        raise RuntimeError("Java API response does not contain an object data field")
    return data


def _is_retryable_worker_error(error_code: str) -> bool:
    normalized = error_code.strip().upper()
    non_retryable_markers = (
        "AUTH",
        "CONFIG",
        "VALIDATION",
        "SCHEMA",
        "VALUEERROR",
        "TYPEERROR",
        "CANCEL",
        "PERMISSION",
    )
    return not any(marker in normalized for marker in non_retryable_markers)
