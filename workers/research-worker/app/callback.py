from __future__ import annotations

import json
from typing import Any, Protocol
from urllib import error, request

from pydantic import BaseModel

from app.config import load_settings
from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult
from app.runner import run_research_task


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


class JavaResearchCallbackClient:
    def __init__(self, java_base_url: str) -> None:
        self.java_base_url = java_base_url.rstrip("/")

    def fetch_task_input(self, task_id: str) -> ResearchTaskInput:
        response = self._request("GET", f"/internal/worker/research-tasks/{task_id}/input")
        return ResearchTaskInput.model_validate(_unwrap_api_response(response))

    def send_progress(self, task_id: str, event: ResearchProgressEvent) -> None:
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/progress",
            event.model_dump(mode="json"),
        )

    def send_complete(self, task_id: str, result: ResearchTaskResult) -> None:
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/complete",
            result.model_dump(mode="json"),
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
        )

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        req = request.Request(
            url=f"{self.java_base_url}{path}",
            data=body,
            method=method,
            headers={"Content-Type": "application/json"},
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


def run_research_task_with_callbacks(
    task_id: str,
    client: ResearchCallbackClient | None = None,
) -> ResearchWorkerExecutionResponse:
    callback_client = client or JavaResearchCallbackClient(load_settings().java_base_url)
    try:
        task_input = callback_client.fetch_task_input(task_id)
        events, result = run_research_task(task_input)
        for event in events:
            callback_client.send_progress(task_id, event)
        callback_client.send_complete(task_id, result)
        return ResearchWorkerExecutionResponse(
            task_id=task_id,
            status="COMPLETED",
            progress_events=len(events),
            result_title=result.result_title,
        )
    except Exception as exc:
        callback_client.send_fail(
            task_id,
            phase="WORKER_EXECUTION",
            error_code=type(exc).__name__.upper(),
            error_message=str(exc),
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
