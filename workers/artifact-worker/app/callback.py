from __future__ import annotations

import json
import hashlib
import hmac
import base64
import logging
import re
from typing import Any, Protocol
from urllib import error, request
from urllib.parse import urlencode, quote

from pydantic import BaseModel

from app.config import load_settings
from app.error_sanitizer import sanitize_error_message
from app.llm_client import credential_safe_urlopen
from app.models import ArtifactProgressEvent, ArtifactTaskInput, ArtifactTaskResult
from app.material_resolver import select_frozen_windows
from app.runner import run_artifact_task


logger = logging.getLogger(__name__)


class ArtifactWorkerExecutionResponse(BaseModel):
    task_id: str
    status: str
    progress_events: int
    result_title: str = ""


class ArtifactCallbackHttpError(RuntimeError):
    def __init__(self, status_code: int, detail: str) -> None:
        self.status_code = status_code
        super().__init__(f"Java callback failed: {status_code} {detail}")


class ArtifactCallbackTransportError(RuntimeError):
    """宿主连接失败；与权限或响应内容错误分开处理。"""


class ArtifactCallbackClient(Protocol):
    def fetch_task_input(self, task_id: str) -> ArtifactTaskInput:
        ...

    def send_progress(self, task_id: str, event: ArtifactProgressEvent) -> None:
        ...

    def send_complete(self, task_id: str, result: ArtifactTaskResult) -> None:
        ...

    def send_fail(
        self,
        task_id: str,
        phase: str,
        error_code: str,
        error_message: str,
        retryable: bool = False,
    ) -> None:
        ...


class JavaArtifactCallbackClient:
    def __init__(
        self,
        java_base_url: str,
        internal_auth_token: str = "",
        callback_secret: str = "",
        delivery_token: str = "",
    ) -> None:
        self.java_base_url = java_base_url.rstrip("/")
        self.internal_auth_token = internal_auth_token.strip()
        self.callback_secret = callback_secret.strip()
        self.delivery_token = delivery_token.strip()

    def fetch_task_input(self, task_id: str) -> ArtifactTaskInput:
        response = self._request("GET", f"/internal/worker/artifact-tasks/{task_id}/input")
        task_input = ArtifactTaskInput.model_validate(_unwrap_api_response(response))
        if task_input.replay_availability != "FULL":
            return task_input
        for source in task_input.source_scope:
            snapshot_id = source.source_snapshot_id or str(
                source.source_metadata.get("source_snapshot_id", ""))
            if not snapshot_id:
                continue  # Historical task input keeps sample_text compatibility.

            def fetch_page(cursor: str) -> dict[str, object]:
                query = urlencode({
                    "sourceSnapshotId": snapshot_id,
                    "cursor": cursor,
                    "maxWindows": 16,
                    "maxBytes": 65_536,
                })
                path = (
                    f"/internal/worker/artifact-tasks/{quote(task_id, safe='')}"
                    f"/sources/{quote(source.source_id, safe='')}/windows?{query}"
                )
                page = _unwrap_api_response(self._request("GET", path))
                if (page.get("source_snapshot_id") != snapshot_id or page.get("source_id") != source.source_id):
                    raise ValueError("source window page does not match the frozen snapshot")
                return page

            try:
                windows, gap, cursor = select_frozen_windows(
                    query=task_input.input_payload.user_requirement + " " + source.title,
                    fetch_page=fetch_page,
                    max_selected_windows=256,
                    max_selected_bytes=1_048_576,
                )
            except ArtifactCallbackHttpError as exc:
                if exc.status_code != 404:
                    raise
                source.material_gap = "WINDOW_ENDPOINT_UNAVAILABLE"
                continue
            source.material_windows = windows
            source.material_gap = gap or ("NO_WINDOWS" if not windows else "")
            if cursor:
                source.source_metadata["material_scan_next_cursor"] = cursor
        return task_input

    def retrieve_passages(self, task_id: str, query: str, limit: int, *, input_snapshot_id: str = "") -> dict[str, object]:
        # 只对服务不可用降级；认证、投递所有权和冻结输入资格失败必须传给调用方。
        try:
            return _unwrap_api_response(self._request("POST",
                f"/internal/worker/artifact-tasks/{quote(task_id, safe='')}/retrieve",
                {"query": query, "limit": limit}))
        except ArtifactCallbackHttpError as exc:
            missing_route = exc.status_code == 404 and not re.search(r'"code"\s*:', str(exc))
            if not missing_route and exc.status_code not in {429, 500, 502, 503, 504}:
                raise
            reason = "ARTIFACT_RETRIEVAL_ENDPOINT_UNAVAILABLE"
        except (ArtifactCallbackTransportError, TimeoutError):
            reason = "ARTIFACT_RETRIEVAL_ENDPOINT_UNAVAILABLE"
        return {"task_id": task_id, "input_snapshot_id": input_snapshot_id,
                "passages": [], "degraded": True, "degradation_reasons": [reason]}

    def read_artifact_frame(self, task_id: str, frame_id: str) -> bytes:
        req = request.Request(
            f"{self.java_base_url}/internal/worker/artifact-tasks/{quote(task_id, safe='')}/frames/{quote(frame_id, safe='')}",
            headers={"Accept": "image/png, image/jpeg",
                     **({"X-NoteWeave-Internal-Token": self.internal_auth_token} if self.internal_auth_token else {}),
                     **({"X-NoteWeave-Outbox-Delivery-Token": self.delivery_token} if self.delivery_token else {})})
        try:
            with credential_safe_urlopen(req, timeout=30) as response:
                if response.headers.get_content_type() not in {"image/png", "image/jpeg"}:
                    raise ValueError("任务关键帧返回了不支持的图片类型")
                content = response.read(16_000_001)
                if not content or len(content) > 16_000_000:
                    raise ValueError("任务关键帧图片大小无效")
                return content
        except error.HTTPError as exc:
            raise ArtifactCallbackHttpError(exc.code, "任务关键帧不可读取") from exc
        except error.URLError as exc:
            raise ArtifactCallbackTransportError("任务关键帧读取服务不可用") from exc

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
            task_id=task_id,
        )

    def send_complete(self, task_id: str, result: ArtifactTaskResult) -> None:
        payload = {
            "result_type": result.result_type,
            "result_title": result.result_title,
            "result_payload": {
                key: value for key, value in result.result_payload.items()
                if key not in {"artifact_version", "artifact_commit", "writeback_request"}
            },
            "trace_summary": result.trace_summary,
            "citations": result.citations,
        }
        preview = payload["result_payload"].get("writeback_preview")
        if isinstance(preview, dict):
            payload["result_payload"]["writeback_preview"] = {**preview, "version_id": ""}
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/complete",
            payload,
            idempotency_key=_callback_idempotency_key(task_id, "complete"),
            task_id=task_id,
        )

    def send_fail(
        self,
        task_id: str,
        phase: str,
        error_code: str,
        error_message: str,
        retryable: bool = False,
    ) -> None:
        self._request(
            "POST",
            f"/internal/worker/tasks/{task_id}/fail",
            {
                "phase": phase,
                "error_code": error_code,
                "error_message": sanitize_error_message(error_message),
                "retryable": retryable,
            },
            idempotency_key=_callback_idempotency_key(task_id, "fail", phase),
            task_id=task_id,
        )

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        *,
        idempotency_key: str = "",
        task_id: str = "",
        callback_task_type: str = "ARTIFACT_JOB",
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
                **(
                    {"X-NoteWeave-Task-Callback-Token": _task_callback_token(
                        self.callback_secret, callback_task_type, task_id
                    )}
                    if self.callback_secret and task_id
                    else {}
                ),
                **(
                    {"X-NoteWeave-Outbox-Delivery-Token": self.delivery_token}
                    if self.delivery_token
                    else {}
                ),
            },
        )
        try:
            with credential_safe_urlopen(req, timeout=30) as response:
                text = response.read().decode("utf-8")
        except error.HTTPError as exc:
            detail = sanitize_error_message(exc.read().decode("utf-8", errors="replace"))
            raise ArtifactCallbackHttpError(exc.code, detail) from exc
        except error.URLError as exc:
            raise ArtifactCallbackTransportError(
                f"Java 回调不可用：{sanitize_error_message(str(exc.reason))}"
            ) from exc
        return json.loads(text) if text else {}


def run_artifact_task_with_callbacks(task_id: str, client: ArtifactCallbackClient | None = None,
                                     delivery_token: str = "") -> ArtifactWorkerExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaArtifactCallbackClient(settings.java_base_url, settings.internal_auth_token,
                                                           settings.callback_secret, delivery_token)
    task_input = callback_client.fetch_task_input(task_id)
    try:
        if isinstance(callback_client, JavaArtifactCallbackClient):
            events, result = run_artifact_task(task_input,
                passage_search=lambda task_id, query, limit: callback_client.retrieve_passages(
                    task_id, query, limit, input_snapshot_id=task_input.input_snapshot_id),
                frame_reader=lambda frame_id: callback_client.read_artifact_frame(task_id, frame_id))
        else:
            events, result = run_artifact_task(task_input)
    except Exception as exc:
        if _report_execution_failure(callback_client, task_id, "WORKER_EXECUTION", exc):
            setattr(exc, "artifact_failure_reported", True)
        raise
    try:
        for event in events:
            callback_client.send_progress(task_id, event)
        callback_client.send_complete(task_id, result)
        return ArtifactWorkerExecutionResponse(task_id=task_id, status=result.job_snapshot.status,
                                               progress_events=len(events), result_title=result.result_title)
    except ArtifactCallbackHttpError as exc:
        # 内容拒收需要上报失败，投递过期则由消费者按原协议处理。
        rejected_code = _rejected_artifact_content_code(exc)
        if rejected_code:
            setattr(exc, "error_code", rejected_code)
            if _report_execution_failure(callback_client, task_id, "WORKER_COMPLETION", exc):
                setattr(exc, "artifact_failure_reported", True)
        raise


def _rejected_artifact_content_code(exc: ArtifactCallbackHttpError) -> str:
    """宿主以 409 拒收产物内容时返回其错误码；投递令牌过期等其他冲突返回空串。"""
    if exc.status_code != 409:
        return ""
    match = re.search(r'"code"\s*:\s*"(ARTIFACT_[A-Z_]+)"', str(exc))
    return match.group(1) if match else ""


def _unwrap_api_response(response: dict[str, Any]) -> dict[str, Any]:
    if response.get("success") is False:
        raise RuntimeError(
            f"Java API returned {response.get('code', 'ERROR')}: {sanitize_error_message(str(response.get('message', '')))}"
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


def _task_callback_token(secret: str, task_type: str, task_id: str) -> str:
    digest = hmac.new(
        secret.strip().encode("utf-8"),
        f"noteweave-worker-callback:v1:{task_type}:{task_id}".encode("utf-8"),
        hashlib.sha256,
    ).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def is_retryable_failure(exc: Exception) -> bool:
    name = type(exc).__name__.upper()
    message = str(exc).lower()
    if name in {"ARTIFACTCONFIGURATIONREQUIREDERROR", "VALUEERROR", "JSONDECODEERROR"}:
        return False
    return not any(marker in message for marker in ("invalid input", "forbidden", "not configured"))


def _report_execution_failure(callback_client: ArtifactCallbackClient, task_id: str, phase: str, exc: Exception) -> bool:
    try:
        error_code = str(getattr(exc, "error_code", type(exc).__name__.upper()))
        message = sanitize_error_message(str(exc))
        try:
            callback_client.send_fail(task_id, phase=phase, error_code=error_code, error_message=message,
                                      retryable=is_retryable_failure(exc))
        except TypeError:
            # 保留现有回调适配器的兼容签名。
            callback_client.send_fail(task_id, phase=phase, error_code=error_code, error_message=message)
        return True
    except Exception:
        logger.exception("产物执行失败且失败回调未确认：task_id=%s phase=%s", task_id, phase)
        return False
