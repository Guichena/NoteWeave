from __future__ import annotations

import json
import hashlib
import hmac
import base64
import logging
import re
from pathlib import Path
from typing import Any, Protocol
from urllib import error, request
from urllib.parse import urlencode, quote

from pydantic import BaseModel

from app.config import load_settings, resolve_mcp_sandbox_root
from app.error_sanitizer import sanitize_error_message
from app.llm_client import credential_safe_urlopen
from app.acquisition_runtime import (
    acknowledge_acquisition_operation, get_acquisition_result_payload,
    list_acquisition_operations,
)
from app.capability_wait_queue import (
    cache_waiting_task_delivery,
    get_waiting_task,
    list_waiting_tasks,
    remove_waiting_task,
    release_waiting_task_claim,
    to_public_callback_operation,
    wake_waiting_task,
)
from app.models import ArtifactProgressEvent, ArtifactTaskInput, ArtifactTaskResult, ArtifactSectionDraft
from app.candidate_file_manifest import build_required_files
from app.export_runtime import export_artifact_if_required, validate_frozen_video_scope
from app.video_material_bundle import VideoMaterialBundleV1
from app.video_subtitle_material import subtitle_bundle_from_provider
from app.video_visual_material import merge_captured_video_frames
from app.material_resolver import select_frozen_windows
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


class ArtifactCallbackHttpError(RuntimeError):
    def __init__(self, status_code: int, detail: str) -> None:
        self.status_code = status_code
        super().__init__(f"Java callback failed: {status_code} {detail}")


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

    def publish_video_material(self, task_id: str, bundle: VideoMaterialBundleV1) -> dict[str, object]:
        digest = bundle.content_digest()
        receipt = _unwrap_api_response(self._request(
            "POST", f"/internal/worker/artifact-tasks/{quote(task_id, safe='')}/video-material",
            {"bundle": bundle.model_dump(mode="json"), "content_digest": digest},
        ))
        if (receipt.get("task_id") != task_id or receipt.get("bundle_id") != bundle.bundle_id
                or receipt.get("workspace_id") != bundle.workspace_id
                or receipt.get("bundle_version") != bundle.bundle_version
                or receipt.get("content_digest") != digest):
            raise ValueError("Host video material receipt does not match the submitted bundle")
        return receipt

    def fetch_video_material(self, task_id: str, bundle_row_id: str = "") -> VideoMaterialBundleV1:
        path = f"/internal/worker/artifact-tasks/{quote(task_id, safe='')}/video-material"
        if bundle_row_id:
            path += f"/references/{quote(bundle_row_id, safe='')}"
        material = _unwrap_api_response(self._request(
            "GET", path
        ))
        return VideoMaterialBundleV1.model_validate(material)

    def fetch_video_material_files(
        self, task_id: str, bundle: VideoMaterialBundleV1, bundle_row_id: str = "",
    ) -> dict[str, bytes]:
        """Read immutable frame IDs from Host and verify every byte against the frozen Bundle."""
        if len(bundle.files) > 32 or sum(file.size_bytes for file in bundle.files) > 100_000_000:
            raise ValueError("video material file manifest exceeds Host limits")
        content: dict[str, bytes] = {}
        for file in bundle.files:
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", file.file_id) \
                    or file.size_bytes > 16_000_000:
                raise ValueError("video material file ID or size is unsupported")
            path = f"/internal/worker/artifact-tasks/{quote(task_id, safe='')}/video-material"
            if bundle_row_id:
                path += f"/references/{quote(bundle_row_id, safe='')}"
            path += f"/files/{quote(file.file_id, safe='')}"
            headers = {
                **({"X-NoteWeave-Internal-Token": self.internal_auth_token}
                   if self.internal_auth_token else {}),
                **({"X-NoteWeave-Outbox-Delivery-Token": self.delivery_token}
                   if self.delivery_token else {}),
            }
            req = request.Request(url=f"{self.java_base_url}{path}", method="GET", headers=headers)
            try:
                with credential_safe_urlopen(req, timeout=30) as response:
                    if response.headers.get("Content-Type", "").split(";", 1)[0] != file.media_type:
                        raise ValueError("Host material file media type differs from frozen manifest")
                    content[file.file_id] = response.read(file.size_bytes + 1)
            except error.HTTPError as exc:
                detail = sanitize_error_message(exc.read().decode("utf-8", errors="replace"))
                raise ArtifactCallbackHttpError(exc.code, detail) from exc
            except error.URLError as exc:
                raise RuntimeError(
                    f"Java callback unavailable: {sanitize_error_message(str(exc.reason))}"
                ) from exc
        bundle.verify_file_bytes(content.__getitem__)
        return content

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
                        self.callback_secret, "ARTIFACT_JOB", task_id
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
            raise RuntimeError(f"Java callback unavailable: {sanitize_error_message(str(exc.reason))}") from exc
        return json.loads(text) if text else {}


def run_artifact_task_with_callbacks(
    task_id: str,
    client: ArtifactCallbackClient | None = None,
    delivery_token: str = "",
) -> ArtifactWorkerExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaArtifactCallbackClient(
        settings.java_base_url,
        settings.internal_auth_token,
        settings.callback_secret,
        delivery_token,
    )
    task_input = callback_client.fetch_task_input(task_id)
    try:
        referenced_id = str(task_input.input_payload.inputs.get("video_material_bundle_id") or "").strip()
        if referenced_id:
            if not isinstance(callback_client, JavaArtifactCallbackClient):
                raise ValueError("referenced video material requires a Host callback client")
            bundle = callback_client.fetch_video_material(task_id, referenced_id)
            validate_frozen_video_scope(task_input, bundle)
            task_input.frozen_video_material = bundle.model_dump(mode="json")
        events, result = run_artifact_task(task_input)
    except Exception as exc:
        if _report_execution_failure(callback_client, task_id, "WORKER_EXECUTION", exc):
            setattr(exc, "artifact_failure_reported", True)
        raise
    return _emit_callbacks_for_result(task_id, events, result, callback_client, task_input)


def resume_waiting_artifact_task_with_callbacks(
    task_id: str,
    *,
    client: ArtifactCallbackClient | None = None,
    request_id: str = "",
    callback_receipt: dict[str, object] | None = None,
    callback_operation: dict[str, object] | None = None,
    delivery_token: str = "",
) -> ArtifactWorkerExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaArtifactCallbackClient(
        settings.java_base_url,
        settings.internal_auth_token,
        settings.callback_secret,
        delivery_token,
    )
    waiting_record = get_waiting_task(task_id)
    task_input = (ArtifactTaskInput.model_validate(waiting_record["task_input"])
                  if waiting_record is not None else None)
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
        if not _is_waiting_status(result.job_snapshot.status):
            cache_waiting_task_delivery(task_id, events, result)
        response = _emit_callbacks_for_result(task_id, events, result, callback_client, task_input)
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
    defer_resume: bool = False,
) -> ArtifactAcquisitionAckExecutionResponse:
    settings = load_settings()
    callback_client = client or JavaArtifactCallbackClient(
        settings.java_base_url,
        settings.internal_auth_token,
        settings.callback_secret,
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
    if not defer_resume and str(ack_result["receipt"].get("callback_status", "")).upper() == "ACKNOWLEDGED":
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
    task_input: ArtifactTaskInput | None = None,
) -> ArtifactWorkerExecutionResponse:
    for event in events:
        callback_client.send_progress(task_id, event)
    if _is_waiting_status(result.job_snapshot.status):
        if isinstance(callback_client, JavaArtifactCallbackClient):
            _dispatch_system_provider_operations(result, callback_client.java_base_url)
    else:
        _attach_frozen_video_material(task_id, task_input, result, callback_client)
        callback_client.send_complete(task_id, result)
    return ArtifactWorkerExecutionResponse(
        task_id=task_id,
        status=result.job_snapshot.status,
        progress_events=len(events),
        result_title=result.result_title,
    )


def _attach_frozen_video_material(
    task_id: str, task_input: ArtifactTaskInput | None,
    result: ArtifactTaskResult, callback_client: ArtifactCallbackClient,
) -> None:
    if task_input is None or not isinstance(callback_client, JavaArtifactCallbackClient):
        return
    referenced_id = str(task_input.input_payload.inputs.get("video_material_bundle_id") or "").strip()
    if referenced_id:
        bundle = callback_client.fetch_video_material(task_id, referenced_id)
        candidate = result.result_payload.get("candidate")
        if not isinstance(candidate, dict):
            raise ValueError("referenced video material requires a Worker Candidate")
        if bundle.frames:
            contents = callback_client.fetch_video_material_files(task_id, bundle, referenced_id)
            frame_paths = _stage_frozen_video_frames(task_id, referenced_id, bundle, contents)
            _rerender_pdf_with_frozen_frames(task_input, result, bundle, frame_paths)
        candidate["video_material"] = {
            "id": referenced_id, "bundle_id": bundle.bundle_id,
            "bundle_version": bundle.bundle_version,
            "content_digest": bundle.content_digest(),
        }
        return
    capture_payload: dict[str, object] | None = None
    subtitle_payload: dict[str, object] | None = None
    has_capture_stage = False
    for operation in list_acquisition_operations(task_id=task_id):
        operation_key = operation.get("operation_key")
        payload = get_acquisition_result_payload(str(operation.get("request_id", "")))
        if operation_key == "CAPTURE_FRAMES" \
                and operation.get("server_id") == SYSTEM_BILIBILI_SERVER_ID:
            has_capture_stage = True
            if isinstance(payload, dict):
                capture_payload = payload
        elif operation_key == "EXTRACT_TRANSCRIPT" and isinstance(payload, dict):
            subtitle_payload = payload
    if subtitle_payload is None:
        return
    if has_capture_stage and capture_payload is None:
        raise ValueError("frame capture stage is missing its acknowledged receipt")
    bundle = subtitle_bundle_from_provider(task_input, subtitle_payload)
    if bundle is None:
        return
    if capture_payload is not None:
        bundle = merge_captured_video_frames(task_input, bundle, capture_payload)
    receipt = callback_client.publish_video_material(task_id, bundle)
    candidate = result.result_payload.get("candidate")
    if not isinstance(candidate, dict):
        raise ValueError("video material requires a Worker Candidate")
    if bundle.frames:
        root = resolve_mcp_sandbox_root() / "bilibili-render-pdf" / "exports" / task_id
        frame_paths = {file.file_id: root / f"{file.file_id}.png" for file in bundle.files}
        _rerender_pdf_with_frozen_frames(task_input, result, bundle, frame_paths)
    candidate["video_material"] = {
        "id": receipt["id"], "bundle_id": receipt["bundle_id"],
        "bundle_version": receipt["bundle_version"],
        "content_digest": receipt["content_digest"],
    }


def _rerender_pdf_with_frozen_frames(
    task_input: ArtifactTaskInput, result: ArtifactTaskResult,
    bundle: VideoMaterialBundleV1, frame_paths: dict[str, Path],
) -> None:
    candidate = result.result_payload.get("candidate")
    if not isinstance(candidate, dict):
        raise ValueError("frozen frame PDF requires a Worker Candidate")
    sections = [ArtifactSectionDraft.model_validate(item)
                for item in result.result_payload.get("sections", [])]
    headings = {section.heading.strip().casefold() for section in sections}
    segments = {segment.segment_id: segment for segment in bundle.transcript_segments}
    for node in bundle.knowledge_nodes:
        if not node.frame_ids or node.title.strip().casefold() in headings:
            continue
        evidence = [segments[segment_id].corrected_text
                    for segment_id in node.transcript_segment_ids]
        sections.append(ArtifactSectionDraft(
            heading=node.title,
            body="\n\n".join(evidence) if evidence else
                 f"Video frame evidence at {node.start_ms / 1000:.1f}–{node.end_ms / 1000:.1f} s.",
            source_refs=[f"video-material:{bundle.bundle_id}:{node.node_id}"],
        ))
        headings.add(node.title.strip().casefold())
    export_trace = export_artifact_if_required(
        task_input=task_input, title=result.result_title,
        sections=sections, video_material=bundle, frame_files=frame_paths,
    )
    if export_trace.get("status") != "COMPILED":
        raise ValueError("frozen frame PDF did not compile")
    result.result_payload["export_trace"] = export_trace
    markdown = str(result.result_payload.get("markdown") or "")
    candidate["required_files"] = build_required_files(markdown, export_trace)


def _stage_frozen_video_frames(
    task_id: str, bundle_row_id: str, bundle: VideoMaterialBundleV1,
    contents: dict[str, bytes],
) -> dict[str, Path]:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", bundle_row_id) \
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", task_id):
        raise ValueError("frozen video material identity is unsafe for sandbox staging")
    root = resolve_mcp_sandbox_root() / "inputs" / "video-material" / task_id / bundle_row_id
    root.mkdir(parents=True, exist_ok=True)
    paths: dict[str, Path] = {}
    for file in bundle.files:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", file.file_id):
            raise ValueError("frozen frame file ID is unsafe")
        suffix = ".png" if file.media_type == "image/png" else ".jpg"
        path = root / f"{file.file_id}{suffix}"
        if path.is_symlink():
            raise ValueError("frozen frame staging cannot follow a symlink")
        content = contents[file.file_id]
        if path.exists() and path.read_bytes() != content:
            raise ValueError("frozen frame staging conflicts with existing bytes")
        if not path.exists():
            path.write_bytes(content)
        paths[file.file_id] = path
    return paths


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


def _report_execution_failure(
    callback_client: ArtifactCallbackClient,
    task_id: str,
    phase: str,
    exc: Exception,
) -> bool:
    try:
        error_code = str(getattr(exc, "error_code", type(exc).__name__.upper()))
        try:
            callback_client.send_fail(
                task_id,
                phase=phase,
                error_code=error_code,
                error_message=sanitize_error_message(str(exc)),
                retryable=is_retryable_failure(exc),
            )
        except TypeError:
            # Keep test doubles and older callback adapters source-compatible.
            callback_client.send_fail(
                task_id,
                phase=phase,
                error_code=error_code,
                error_message=sanitize_error_message(str(exc)),
            )
        return True
    except Exception:
        logger.exception(
            "Artifact execution failed and the failure callback could not be delivered: task_id=%s phase=%s",
            task_id,
            phase,
        )
        return False


def is_retryable_failure(exc: Exception) -> bool:
    name = type(exc).__name__.upper()
    message = str(exc).lower()
    if name in {"ARTIFACTCONFIGURATIONREQUIREDERROR", "VALUEERROR", "JSONDECODEERROR"}:
        return False
    return not any(marker in message for marker in ("invalid input", "forbidden", "not configured"))
