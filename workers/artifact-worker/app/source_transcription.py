"""资料转写：接收 Java Host 发来的音视频原件，通过系统 MCP 服务的 transcribe_local_audio
工具（faster-whisper）生成带时间戳的文字稿，完成后回调 Java 的
/internal/worker/source-transcriptions/{snapshot_id}。

转写在后台线程中执行，接口保存文件后立即返回。同一个快照正在转写时，重复提交直接返回已受理。
"""
from __future__ import annotations

import json
import logging
import os
import re
import shutil
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib import error, request

from app.config import load_settings, resolve_mcp_sandbox_root
from app.custom_mcp_executor import _call_custom_mcp_tool
from app.llm_client import LlmClient, build_default_llm_client
from app.error_sanitizer import sanitize_error_message
from app.system_mcp_registry import SYSTEM_BILIBILI_SERVER_ID, resolve_system_mcp_server

logger = logging.getLogger(__name__)

MEDIA_SUFFIXES = {
    "audio/mpeg": ".mp3",
    "audio/mp4": ".m4a",
    "audio/wav": ".wav",
    "audio/ogg": ".ogg",
    "audio/flac": ".flac",
    "audio/webm": ".webm",
    "video/mp4": ".mp4",
}
_IDENTIFIER = re.compile(r"^[0-9A-Za-z-]{8,64}$")
_CALLBACK_ATTEMPTS = 6
_CORRECTION_BATCH_LINES = 60
_TIMESTAMP = re.compile(r"^\[(?:\d{2}:)?\d{2}:\d{2}\] ")

_running_lock = threading.Lock()
_running: dict[str, threading.Thread] = {}


@dataclass(frozen=True)
class TranscriptionJob:
    workspace_id: str
    source_id: str
    snapshot_id: str
    file_name: str
    mime_type: str
    media_path: Path


def accept_source_transcription(
    *,
    workspace_id: str,
    source_id: str,
    snapshot_id: str,
    file_name: str,
    mime_type: str,
    content: bytes,
    run_async: bool = True,
) -> dict[str, object]:
    for label, value in (("workspace_id", workspace_id), ("source_id", source_id), ("snapshot_id", snapshot_id)):
        if not _IDENTIFIER.match(value or ""):
            raise ValueError(f"{label} is invalid")
    normalized_mime = (mime_type or "").split(";", 1)[0].strip().lower()
    suffix = MEDIA_SUFFIXES.get(normalized_mime)
    if suffix is None:
        raise ValueError(f"unsupported media type: {mime_type}")
    if not content:
        raise ValueError("media content is empty")
    with _running_lock:
        running = _running.get(snapshot_id)
        if running is not None and running.is_alive():
            return {"accepted": True, "snapshot_id": snapshot_id, "duplicate": True}
        input_dir = _sandbox_root() / "inputs" / "source-transcriptions" / snapshot_id
        input_dir.mkdir(parents=True, exist_ok=True)
        media_path = input_dir / f"source{suffix}"
        media_path.write_bytes(content)
        job = TranscriptionJob(workspace_id, source_id, snapshot_id, file_name or media_path.name,
                               normalized_mime, media_path)
        # 任务信息和原件放在一起，Worker 重启后可以继续未完成的转写
        (input_dir / "job.json").write_text(json.dumps({
            "workspace_id": workspace_id, "source_id": source_id, "snapshot_id": snapshot_id,
            "file_name": job.file_name, "mime_type": normalized_mime, "media_file": media_path.name,
        }, ensure_ascii=False), encoding="utf-8")
        if not run_async:
            run_transcription_job(job)
            return {"accepted": True, "snapshot_id": snapshot_id, "duplicate": False}
        thread = threading.Thread(target=run_transcription_job, args=(job,),
                                  name=f"source-transcription-{snapshot_id}", daemon=True)
        _running[snapshot_id] = thread
        thread.start()
    return {"accepted": True, "snapshot_id": snapshot_id, "duplicate": False}


def recover_source_transcriptions() -> list[str]:
    """Worker 启动时继续上次没有完成的转写：原件还在沙箱里，说明还没有回调 Java。"""
    recovered: list[str] = []
    jobs_root = _sandbox_root() / "inputs" / "source-transcriptions"
    if not jobs_root.is_dir():
        return recovered
    for job_dir in sorted(path for path in jobs_root.iterdir() if path.is_dir()):
        try:
            meta = json.loads((job_dir / "job.json").read_text(encoding="utf-8"))
            media_path = job_dir / str(meta["media_file"])
            if not media_path.is_file() or not _IDENTIFIER.match(str(meta["snapshot_id"])):
                continue
            job = TranscriptionJob(str(meta["workspace_id"]), str(meta["source_id"]), str(meta["snapshot_id"]),
                                   str(meta.get("file_name", media_path.name)), str(meta["mime_type"]), media_path)
        except (OSError, ValueError, KeyError) as exc:
            logger.warning("skip unrecoverable transcription job %s: %s", job_dir.name, exc)
            continue
        with _running_lock:
            if job.snapshot_id in _running:
                continue
            thread = threading.Thread(target=run_transcription_job, args=(job,),
                                      name=f"source-transcription-recover-{job.snapshot_id}", daemon=True)
            _running[job.snapshot_id] = thread
            thread.start()
        recovered.append(job.snapshot_id)
    return recovered


def run_transcription_job(
    job: TranscriptionJob,
    *,
    call_tool: Callable[[object, dict[str, object]], dict[str, object]] = _call_custom_mcp_tool,
    post_callback: Callable[[str, dict[str, object]], bool] | None = None,
    llm_client: LlmClient | None | bool = True,
) -> dict[str, object]:
    """执行一次转写并回调 Java；返回回调内容，便于测试。

    llm_client 为 True 时使用 Artifact Worker 配置的大模型校对文字稿；为 None 时跳过校对。
    """
    deliver = post_callback or _post_callback
    corrector = build_default_llm_client() if llm_client is True else llm_client
    output_dir = _sandbox_root() / "bilibili-render-pdf" / "source-transcriptions" / job.snapshot_id
    try:
        server = resolve_system_mcp_server(SYSTEM_BILIBILI_SERVER_ID)
        result = call_tool(server, {
            "tool_name": "transcribe_local_audio",
            "tool_arguments": {
                "input_path": str(job.media_path),
                "output_dir": str(output_dir),
                "model": os.environ.get("NOTEWEAVE_TRANSCRIPTION_MODEL", "small"),
                "language": os.environ.get("NOTEWEAVE_TRANSCRIPTION_LANGUAGE", "zh"),
                "device": os.environ.get("NOTEWEAVE_TRANSCRIPTION_DEVICE", "cpu"),
                "skip_existing": False,
                "json_output": True,
            },
        })
        transcript = _read_transcript(result)
        lines = str(transcript["text"]).split("\n") if transcript["text"] else []
        corrected_lines, corrected_count = correct_transcript(lines, corrector) if corrector else (lines, 0)
        payload: dict[str, object] = {
            "workspace_id": job.workspace_id,
            "source_id": job.source_id,
            "status": "COMPLETED",
            "transcript_text": "\n".join(corrected_lines),
            "corrected_line_count": corrected_count,
            "language": transcript["language"],
            "duration_seconds": transcript["duration"],
            "segment_count": transcript["segment_count"],
        }
    except Exception as exc:  # noqa: BLE001 - 任何失败都要回调 Java，避免资料停在转写中
        logger.warning("source transcription failed: snapshot=%s error=%s", job.snapshot_id, exc)
        payload = {
            "workspace_id": job.workspace_id,
            "source_id": job.source_id,
            "status": "FAILED",
            "error_code": "SOURCE_TRANSCRIPTION_FAILED",
            "error_message": "音视频转写失败：" + sanitize_error_message(str(exc))[:300],
        }
    finally:
        shutil.rmtree(job.media_path.parent, ignore_errors=True)
        with _running_lock:
            _running.pop(job.snapshot_id, None)
    deliver(job.snapshot_id, payload)
    shutil.rmtree(output_dir, ignore_errors=True)
    return payload


def format_transcript(segments: list[dict[str, object]]) -> str:
    """每个语音片段一行，行首是开始时间，方便纪要和问答引用到具体时间点。"""
    lines = []
    for segment in segments:
        text = str(segment.get("text", "")).strip()
        if not text:
            continue
        lines.append(f"[{_clock(float(segment.get('start', 0.0) or 0.0))}] {text}")
    return "\n".join(lines)


def correct_transcript(lines: list[str], client: LlmClient) -> tuple[list[str], int]:
    """让大模型分批校对识别错误（同音字、术语、标点），返回校对后的行和实际改动的行数。

    每批的输出必须与输入行数相同，且每行的时间戳保持不变；不满足时这一批保留原文，
    保证校对只修正识别错误，不会改写、合并或丢失内容。
    """
    corrected: list[str] = []
    changed = 0
    for start in range(0, len(lines), _CORRECTION_BATCH_LINES):
        batch = lines[start:start + _CORRECTION_BATCH_LINES]
        candidate = _correct_batch(batch, client)
        for original, fixed in zip(batch, candidate):
            if fixed != original:
                changed += 1
        corrected.extend(candidate)
    return corrected, changed


def _correct_batch(batch: list[str], client: LlmClient) -> list[str]:
    try:
        raw = client.complete_json("transcript_correction", {
            "lines": batch,
            "instructions": "逐行纠正语音识别错误（同音字、专业术语、标点），保留行首时间戳，不增删、不合并、不改写。",
        })
        start, end = raw.find("{"), raw.rfind("}")
        fixed = json.loads(raw[start:end + 1]).get("lines") if start >= 0 and end > start else None
    except (ValueError, AttributeError) as exc:
        logger.warning("transcript correction batch failed, keeping original lines: %s", exc)
        return batch
    if not isinstance(fixed, list) or len(fixed) != len(batch):
        return batch
    result = []
    for original, line in zip(batch, fixed):
        line = str(line).strip()
        prefix = _TIMESTAMP.match(original)
        # 时间戳被改动或内容被清空时，这一行保留原文
        if not line or (prefix and not line.startswith(prefix.group(0))):
            result.append(original)
        else:
            result.append(line)
    return result


def _read_transcript(result: dict[str, object]) -> dict[str, object]:
    artifacts = result.get("artifacts") if isinstance(result, dict) else None
    json_files = artifacts.get("json_files") if isinstance(artifacts, dict) else None
    if not json_files:
        raise ValueError("transcription tool returned no transcript file")
    document = json.loads(Path(str(json_files[0])).read_text(encoding="utf-8"))
    segments = [segment for segment in document.get("segments", []) if isinstance(segment, dict)]
    return {
        "text": format_transcript(segments),
        "language": str(document.get("language", "")),
        "duration": float(document.get("duration", 0.0) or 0.0),
        "segment_count": len(segments),
    }


def _clock(seconds: float) -> str:
    total = max(0, int(seconds))
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}" if hours else f"{minutes:02d}:{secs:02d}"


def _post_callback(snapshot_id: str, payload: dict[str, object]) -> bool:
    """回调 Java；网络错误按退避重试。Java 返回 accepted=false 时也重试几次，
    以覆盖 Java 还没来得及把快照标记为转写中的情况。"""
    settings = load_settings()
    url = f"{settings.java_base_url.rstrip('/')}/internal/worker/source-transcriptions/{snapshot_id}"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if settings.internal_auth_token:
        headers["X-NoteWeave-Internal-Token"] = settings.internal_auth_token
    for attempt in range(_CALLBACK_ATTEMPTS):
        try:
            with request.urlopen(request.Request(url, data=body, headers=headers, method="POST"), timeout=30) as response:
                result = json.loads(response.read().decode("utf-8") or "{}")
            if (result.get("data") or {}).get("accepted"):
                return True
        except (error.URLError, TimeoutError, ValueError) as exc:
            logger.warning("transcription callback failed: snapshot=%s attempt=%s error=%s", snapshot_id, attempt + 1, exc)
        time.sleep(min(60, 2 ** (attempt + 1)))
    logger.warning("transcription callback was not accepted: snapshot=%s", snapshot_id)
    return False


def _sandbox_root() -> Path:
    return resolve_mcp_sandbox_root()
