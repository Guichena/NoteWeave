"""产物 Worker 的鉴权、导出和媒体处理入口。"""

from contextlib import asynccontextmanager
import hmac
from pathlib import Path
import re

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, FileResponse
from pydantic import BaseModel

from app.config import load_settings, resolve_mcp_sandbox_root
from app.artifact_kafka_consumer import ArtifactKafkaConsumerRuntime
from app.source_transcription import (accept_source_transcription, accept_video_link, recover_source_transcriptions,
    start_source_transcription_pool, shutdown_source_transcriptions, TranscriptionCapacityExceeded)


settings = load_settings()


@asynccontextmanager
async def artifact_worker_lifespan(_: FastAPI):
    consumer_runtime = None
    try:
        start_source_transcription_pool(settings)
        recover_source_transcriptions()
        consumer_runtime = ArtifactKafkaConsumerRuntime(settings)
        consumer_runtime.start()
        app.state.artifact_consumer_runtime = consumer_runtime
        yield
    finally:
        try:
            if consumer_runtime is not None:
                consumer_runtime.stop()
        finally:
            shutdown_source_transcriptions()


app = FastAPI(title="NoteWeave Artifact Worker", lifespan=artifact_worker_lifespan)


@app.middleware("http")
async def protect_internal_routes(request: Request, call_next):
    if request.url.path.startswith(("/tasks/", "/internal/")):
        if not settings.internal_auth_token:
            return JSONResponse(status_code=503, content={"detail": "internal service authentication is not configured"})
        supplied_token = request.headers.get("X-NoteWeave-Internal-Token", "")
        if not hmac.compare_digest(supplied_token, settings.internal_auth_token):
            return JSONResponse(status_code=401, content={"detail": "internal service authentication failed"})
    return await call_next(request)


@app.get("/health")
def health() -> dict[str, str]:
    consumer_runtime = getattr(app.state, "artifact_consumer_runtime", None)
    if consumer_runtime is not None and not consumer_runtime.healthy:
        raise HTTPException(status_code=503, detail="artifact Kafka consumer is not healthy")
    return {"status": "ok", "worker_type": settings.worker_type}


@app.get("/tasks/{task_id}/exports/{file_name}", response_class=FileResponse)
def download_task_export(task_id: str, file_name: str) -> FileResponse:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", task_id):
        raise HTTPException(status_code=404, detail="artifact export not found")
    if not re.fullmatch(r"[^/\\\x00-\x1f]{1,160}\.(?:pdf|png|pptx|md|csv)", file_name, re.IGNORECASE):
        raise HTTPException(status_code=404, detail="artifact export not found")
    export_root = (
        resolve_mcp_sandbox_root(settings)
        / "bilibili-render-pdf"
        / "exports"
        / task_id
    ).resolve()
    export_path = (export_root / file_name).resolve()
    if not export_path.is_relative_to(export_root) or not export_path.is_file() \
            or export_path.stat().st_size > 100_000_000:
        raise HTTPException(status_code=404, detail="artifact export not found")
    media_type = {
        ".pdf": "application/pdf",
        ".png": "image/png",
        ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ".md": "text/markdown; charset=UTF-8",
        ".csv": "text/csv; charset=UTF-8",
    }[export_path.suffix.lower()]
    return FileResponse(export_path, media_type=media_type, filename=file_name)


_MAX_TRANSCRIPTION_BYTES = 128 * 1024 * 1024


class VideoLinkRequest(BaseModel):
    workspace_id: str
    source_id: str
    snapshot_id: str
    task_id: str
    source_url: str
    parts: list[int]
    title: str = "视频资料"


class VideoInfoRequest(BaseModel):
    source_url: str


@app.post("/internal/source-video-info")
def preview_source_video(body: VideoInfoRequest) -> dict:
    from app.media_mcp_client import call_media_tool
    try:
        return call_media_tool("get_video_info", {"source_url": body.source_url})
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/internal/source-video-parses", status_code=202)
def submit_source_video(body: VideoLinkRequest) -> dict:
    try:
        return accept_video_link(**body.model_dump())
    except TranscriptionCapacityExceeded as exc:
        raise HTTPException(status_code=429, detail=str(exc), headers={"Retry-After": "5"}) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/internal/source-transcriptions", status_code=202)
async def submit_source_transcription(
    request: Request,
    workspace_id: str,
    source_id: str,
    snapshot_id: str,
    mime_type: str,
    file_name: str = "",
    task_id: str = "",
) -> dict[str, object]:
    """Java Host 提交资料的音视频原件；保存后在后台通过 MCP 转写，完成后回调 Java。"""
    content = bytearray()
    async for piece in request.stream():
        if len(content) + len(piece) > _MAX_TRANSCRIPTION_BYTES:
            raise HTTPException(status_code=413, detail="媒体文件超过 128 MiB 上限")
        content.extend(piece)
    try:
        return accept_source_transcription(
            workspace_id=workspace_id,
            source_id=source_id,
            snapshot_id=snapshot_id,
            file_name=file_name,
            mime_type=mime_type,
            content=bytes(content),
            task_id=task_id,
        )
    except TranscriptionCapacityExceeded as exc:
        raise HTTPException(status_code=429, detail={"code": "SOURCE_TRANSCRIPTION_CAPACITY_EXHAUSTED", "message": str(exc)},
                            headers={"Retry-After": "5"}) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
