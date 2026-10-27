"""资料转写：接收 Java Host 发来的音视频原件，通过系统 MCP 服务的 transcribe_local_audio
工具（faster-whisper）生成带时间戳的文字稿，完成后回调 Java 的
/internal/worker/source-transcriptions/{snapshot_id}。

转写在固定工作线程和有界队列中执行；完成结果落盘后回调，确认回调后才清理原件。
"""
from __future__ import annotations

import json
import hashlib
import logging
import os
import queue
import re
import shutil
import threading
import time
from dataclasses import dataclass
from dataclasses import replace
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
_running: dict[str, "_Entry"] = {}
_pool: "TranscriptionPool | None" = None
_shutdown_requested = False
_MAX_MEDIA_BYTES = 128 * 1024 * 1024
_MAX_CHECKPOINT_BYTES = 64 * 1024 * 1024
_RECOVERY_RETRY_SECONDS = 30
# 宿主明确丢弃这次投递（例如已被更新代次取代）时，任务目录与文字稿保留，状态改成这个值：
# 既不会被恢复流程重新投递，也不会被当成正常完成清掉。
_ORPHANED_STATE = "ORPHANED_CALLBACK_DISCARDED"
# 孤儿转写（宿主丢弃的回调）默认保留 30 天，给人工取回文字稿留窗口；可用
# NOTEWEAVE_TRANSCRIPTION_ORPHAN_RETENTION_DAYS 调整。
_DEFAULT_ORPHAN_RETENTION_DAYS = 30


@dataclass(frozen=True)
class TranscriptionJob:
    workspace_id: str
    source_id: str
    snapshot_id: str
    file_name: str
    mime_type: str
    media_path: Path
    task_id: str = ""
    content_sha256: str = ""
    file_size: int = 0
    model: str = "small"
    language: str = "zh"
    device: str = "cpu"


@dataclass
class _Entry:
    job: TranscriptionJob
    ready: bool = False


class TranscriptionCapacityExceeded(RuntimeError):
    """容量暂满或停机时不接纳新任务，调用方稍后重试。"""


@dataclass(frozen=True)
class CallbackAcknowledgement:
    accepted: bool = False
    discarded: bool = False

    @property
    def confirmed(self) -> bool:
        return (self.accepted is True) != (self.discarded is True)


def _directory_name(job: TranscriptionJob) -> str:
    return job.snapshot_id + ("--" + job.task_id if job.task_id else "")


def _jobs_root() -> Path:
    return _sandbox_root() / "inputs" / "source-transcriptions"


def _owned_jobs_root() -> Path:
    root = _jobs_root()
    if root.is_symlink() or not root.resolve().is_relative_to(_sandbox_root().resolve()):
        raise ValueError("转写根目录超出指定沙箱")
    return root


def _output_root(job: TranscriptionJob) -> Path:
    return _sandbox_root() / "bilibili-render-pdf" / "source-transcriptions" / _directory_name(job)


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for content in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(content)
    return digest.hexdigest()


def _strict_json(path: Path, maximum: int = 64 * 1024):
    with path.open("rb") as stream:
        content = stream.read(maximum + 1)
    if len(content) > maximum:
        raise ValueError("转写检查点超过读取上限")
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("转写检查点字段重复")
            result[key] = value
        return result
    def invalid_constant(_):
        raise ValueError("转写检查点包含非有限数值")
    return json.loads(content.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid_constant)


def _atomic_json(path: Path, value) -> None:
    import uuid
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _identity(job: TranscriptionJob) -> tuple:
    return (job.workspace_id, job.source_id, job.snapshot_id, job.task_id, job.file_name,
            job.mime_type, job.content_sha256, job.file_size, job.model, job.language, job.device)


def _metadata(job: TranscriptionJob, *, state="QUEUED", next_attempt_at=0.0) -> dict:
    return {"schema_version": 2, "workspace_id": job.workspace_id, "source_id": job.source_id,
            "snapshot_id": job.snapshot_id, "task_id": job.task_id, "file_name": job.file_name,
            "mime_type": job.mime_type, "media_file": job.media_path.name, "sha256": job.content_sha256,
            "file_size": job.file_size, "model": job.model, "language": job.language, "device": job.device,
            "state": state, "next_attempt_at": next_attempt_at}


def _validate_owned(job: TranscriptionJob) -> None:
    for label, value in (("workspace_id", job.workspace_id), ("source_id", job.source_id), ("snapshot_id", job.snapshot_id)):
        if not isinstance(value, str) or not _IDENTIFIER.fullmatch(value):
            raise ValueError(label + " 无效")
    if job.task_id and not _IDENTIFIER.fullmatch(job.task_id):
        raise ValueError("task_id 无效")
    jobs = _owned_jobs_root().resolve()
    directory = job.media_path.parent
    if directory.is_symlink() or directory.resolve() != jobs / _directory_name(job) or job.media_path.is_symlink():
        raise ValueError("转写任务目录不属于指定沙箱")
    suffix = MEDIA_SUFFIXES.get(job.mime_type)
    if suffix is None or job.media_path.name != "source" + suffix or job.media_path.resolve().parent != directory.resolve():
        raise ValueError("转写媒体路径无效")
    if not job.media_path.is_file() or not 0 < job.media_path.stat().st_size <= _MAX_MEDIA_BYTES:
        raise ValueError("转写媒体缺失或超过大小上限")
    if job.file_size != job.media_path.stat().st_size or _hash_file(job.media_path) != job.content_sha256:
        raise ValueError("转写媒体与冻结摘要不一致")


def _load_job(directory: Path, *, verify_media=True) -> TranscriptionJob:
    import math
    if directory.is_symlink() or directory.resolve().parent != _owned_jobs_root().resolve():
        raise ValueError("恢复目录超出沙箱")
    meta = _strict_json(directory / "job.json")
    if not isinstance(meta, dict):
        raise ValueError("转写检查点无效")
    legacy_fields = {"workspace_id", "source_id", "snapshot_id", "file_name", "mime_type", "media_file"}
    current_fields = legacy_fields | {"schema_version", "task_id", "sha256", "file_size", "model", "language", "device", "state", "next_attempt_at"}
    legacy = "schema_version" not in meta
    if set(meta) != (legacy_fields if legacy else current_fields) or (not legacy and meta["schema_version"] != 2):
        raise ValueError("转写检查点字段或版本无效")
    for key in legacy_fields:
        if not isinstance(meta[key], str):
            raise ValueError("转写检查点字符串无效")
    filename = meta["media_file"]
    if Path(filename).name != filename or any(character in filename for character in ("/", "\\", ":", "\x00")):
        raise ValueError("恢复媒体不能使用外部路径")
    media = directory / filename
    if legacy:
        if media.is_symlink() or not media.is_file() or media.stat().st_size > _MAX_MEDIA_BYTES:
            raise ValueError("旧转写媒体无效")
        job = TranscriptionJob(meta["workspace_id"], meta["source_id"], meta["snapshot_id"], meta["file_name"], meta["mime_type"], media,
                content_sha256=_hash_file(media), file_size=media.stat().st_size)
    else:
        if (not isinstance(meta["task_id"], str) or not isinstance(meta["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", meta["sha256"])
                or type(meta["file_size"]) is not int or not 0 < meta["file_size"] <= _MAX_MEDIA_BYTES
                or meta["state"] not in {"QUEUED", "RUNNING", "CALLBACK_PENDING", _ORPHANED_STATE}
                or type(meta["next_attempt_at"]) not in (int, float) or not math.isfinite(meta["next_attempt_at"]) or meta["next_attempt_at"] < 0):
            raise ValueError("转写检查点状态或摘要无效")
        for field in ("model", "language", "device"):
            if not isinstance(meta[field], str) or not 0 < len(meta[field]) <= 300:
                raise ValueError("转写模型配置无效")
        job = TranscriptionJob(meta["workspace_id"], meta["source_id"], meta["snapshot_id"], meta["file_name"], meta["mime_type"], media,
                meta["task_id"], meta["sha256"], meta["file_size"], meta["model"], meta["language"], meta["device"])
    if directory.name != _directory_name(job):
        raise ValueError("恢复目录与冻结任务身份不符")
    if verify_media:
        _validate_owned(job)
    if legacy:
        _atomic_json(directory / "job.json", _metadata(job))
    return job


class TranscriptionPool:
    """固定线程数量；总接纳容量为执行槽加等待槽，写文件前即预留。"""

    def __init__(self, pool_size: int, queue_capacity: int):
        if type(pool_size) is not int or not 1 <= pool_size <= 8 or type(queue_capacity) is not int or not 1 <= queue_capacity <= 64:
            raise ValueError("转写线程池或队列配置超出允许范围")
        self.root = _sandbox_root().resolve()
        self.capacity = threading.BoundedSemaphore(pool_size + queue_capacity)
        self.execution = threading.BoundedSemaphore(pool_size)
        # 缓冲包含已预留执行槽的任务，避免线程尚未调度时在保存原件后才发现队列满。
        self.pending = queue.Queue(maxsize=pool_size + queue_capacity)
        self.stop_event = threading.Event()
        self.recovery_enabled = False
        self.closed = False
        self.threads = [threading.Thread(target=self._worker, name=f"source-transcription-pool-{index + 1}", daemon=True)
                        for index in range(pool_size)]
        for thread in self.threads:
            thread.start()

    def reserve(self, job: TranscriptionJob) -> _Entry:
        with _running_lock:
            if self.closed or _shutdown_requested or not self.capacity.acquire(blocking=False):
                raise TranscriptionCapacityExceeded("转写容量暂满或正在停机，请稍后重试")
            current = _running.get(job.snapshot_id)
            if current is not None:
                self.capacity.release()
                raise TranscriptionCapacityExceeded("该快照还有未完成的转写任务")
            entry = _Entry(job)
            _running[job.snapshot_id] = entry
            return entry

    def release(self, entry: _Entry):
        with _running_lock:
            if _running.get(entry.job.snapshot_id) is entry:
                _running.pop(entry.job.snapshot_id, None)
                self.capacity.release()

    def submit_reserved(self, entry: _Entry, *, run_async=True):
        with _running_lock:
            if self.closed:
                if _running.get(entry.job.snapshot_id) is entry:
                    _running.pop(entry.job.snapshot_id, None)
                    self.capacity.release()
                raise TranscriptionCapacityExceeded("转写服务正在停机，请稍后重试")
            entry.ready = True
            if run_async:
                self.pending.put_nowait(entry)
        if not run_async:
            self._execute(entry)

    def _execute(self, entry: _Entry):
        try:
            with self.execution:
                if self.closed:
                    return
                _atomic_json(entry.job.media_path.parent / "job.json", _metadata(entry.job, state="RUNNING"))
                run_transcription_job(entry.job)
        except Exception as exc:
            logger.warning("转写任务执行或检查点提交失败: snapshot=%s error=%s", entry.job.snapshot_id, sanitize_error_message(str(exc)))
        finally:
            # 回调未确认时保留磁盘任务；避免在下一次队列循环立刻重复请求 Java。
            checkpoint = entry.job.media_path.parent / "job.json"
            try:
                if checkpoint.is_file():
                    meta = _strict_json(checkpoint)
                    if not isinstance(meta, dict):
                        raise ValueError("转写恢复检查点必须为对象")
                    meta["next_attempt_at"] = time.time() + _RECOVERY_RETRY_SECONDS
                    _atomic_json(checkpoint, meta)
            except (OSError, ValueError, TypeError):
                logger.warning("无法保存转写恢复时间: snapshot=%s", entry.job.snapshot_id)
            finally:
                self.release(entry)

    def _worker(self):
        while not self.stop_event.is_set():
            if self.recovery_enabled:
                try:
                    self.fill_backlog()
                except (OSError, ValueError, TypeError) as exc:
                    logger.warning("转写恢复扫描暂时失败，将继续重试: %s", sanitize_error_message(str(exc)))
            try:
                entry = self.pending.get(timeout=1)
            except queue.Empty:
                continue
            try:
                self._execute(entry)
            finally:
                self.pending.task_done()

    def fill_backlog(self) -> list[str]:
        recovered = []
        try:
            jobs = _owned_jobs_root()
        except ValueError:
            return recovered
        if self.closed or not jobs.is_dir():
            return recovered
        for directory in jobs.iterdir():
            if not directory.is_dir() or directory.is_symlink():
                continue
            try:
                meta = _strict_json(directory / "job.json")
                if not isinstance(meta, dict):
                    raise ValueError("转写检查点必须为对象")
                if meta.get("state") == _ORPHANED_STATE:
                    # 被宿主丢弃的孤儿保留在原处，等保留策略或人工处理，不再重新转写
                    continue
                if meta.get("next_attempt_at", 0) > time.time():
                    continue
                snapshot = meta.get("snapshot_id")
                with _running_lock:
                    if snapshot in _running:
                        continue
                job = _load_job(directory)
                entry = self.reserve(job)
                try:
                    self.submit_reserved(entry)
                except Exception:
                    self.release(entry)
                    raise
                recovered.append(job.snapshot_id)
            except TranscriptionCapacityExceeded:
                break
            except (OSError, ValueError, KeyError, TypeError) as exc:
                logger.warning("跳过无法恢复的转写目录: name=%s reason=%s", directory.name, sanitize_error_message(str(exc)))
        return recovered

    def close(self, timeout=5.0):
        with _running_lock:
            self.closed = True
            self.stop_event.set()
        # 未执行任务保留 QUEUED 检查点，重启后再接纳，不清理媒体。
        while True:
            try:
                entry = self.pending.get_nowait()
            except queue.Empty:
                break
            self.release(entry)
            self.pending.task_done()
        deadline = time.monotonic() + timeout
        for thread in self.threads:
            thread.join(max(0, deadline - time.monotonic()))


def start_source_transcription_pool(settings=None) -> TranscriptionPool:
    global _pool, _shutdown_requested
    configured = settings or load_settings()
    with _running_lock:
        if _pool is not None and not _pool.closed:
            return _pool
        if _pool is not None and (any(thread.is_alive() for thread in _pool.threads) or _running):
            raise TranscriptionCapacityExceeded("上一组转写线程仍在结束，请稍后重试")
        _shutdown_requested = False
        _pool = TranscriptionPool(configured.transcription_pool_size, configured.transcription_queue_capacity)
        return _pool


def _get_pool() -> TranscriptionPool:
    if _shutdown_requested:
        raise TranscriptionCapacityExceeded("转写服务正在停机，请稍后重试")
    return _pool if _pool is not None else start_source_transcription_pool()


def shutdown_source_transcriptions(timeout=5.0) -> None:
    global _shutdown_requested
    _shutdown_requested = True
    if _pool is not None:
        _pool.close(timeout)


def wait_for_source_transcriptions(timeout=5.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with _running_lock:
            if not _running:
                return True
        time.sleep(0.01)
    return False


def accept_source_transcription(
    *,
    workspace_id: str,
    source_id: str,
    snapshot_id: str,
    file_name: str,
    mime_type: str,
    content: bytes,
    run_async: bool = True,
    task_id: str = "",
) -> dict[str, object]:
    for label, value in (("workspace_id", workspace_id), ("source_id", source_id), ("snapshot_id", snapshot_id)):
        if not isinstance(value, str) or not _IDENTIFIER.fullmatch(value):
            raise ValueError(f"{label} 无效")
    if task_id and not _IDENTIFIER.fullmatch(task_id):
        raise ValueError("task_id 无效")
    normalized_mime = (mime_type or "").split(";", 1)[0].strip().lower()
    suffix = MEDIA_SUFFIXES.get(normalized_mime)
    if suffix is None:
        raise ValueError(f"不支持的媒体类型: {mime_type}")
    if not isinstance(content, bytes) or not 0 < len(content) <= _MAX_MEDIA_BYTES:
        raise ValueError("媒体内容为空或超过大小上限")
    name = file_name or "source" + suffix
    if len(name) > 300 or any(character in name for character in ("/", "\\", ":", "\x00", "\n", "\r")):
        raise ValueError("媒体文件名无效")
    directory = _jobs_root() / (snapshot_id + ("--" + task_id if task_id else ""))
    job = TranscriptionJob(workspace_id, source_id, snapshot_id, name, normalized_mime, directory / ("source" + suffix),
            task_id, hashlib.sha256(content).hexdigest(), len(content), os.environ.get("NOTEWEAVE_TRANSCRIPTION_MODEL", "small"),
            os.environ.get("NOTEWEAVE_TRANSCRIPTION_LANGUAGE", "zh"), os.environ.get("NOTEWEAVE_TRANSCRIPTION_DEVICE", "cpu"))
    pool = _get_pool()
    with _running_lock:
        current = _running.get(snapshot_id)
        if current is not None:
            if current.job.task_id != task_id:
                raise TranscriptionCapacityExceeded("该快照的旧任务仍在处理，请稍后重试")
            if _identity(current.job) != _identity(job):
                raise ValueError("重复转写请求与冻结身份或摘要不一致")
            if not current.ready:
                raise TranscriptionCapacityExceeded("该转写请求仍在保存，请稍后重试")
            return {"accepted": True, "snapshot_id": snapshot_id, "duplicate": True}
    if (directory / "job.json").is_file():
        existing = _load_job(directory)
        if _identity(existing) != _identity(job):
            raise ValueError("重复转写请求与持久检查点身份或摘要不一致")
        pool.recovery_enabled = True
        pool.fill_backlog()
        return {"accepted": True, "snapshot_id": snapshot_id, "duplicate": True}
    entry = pool.reserve(job)
    try:
        if directory.is_symlink() or directory.resolve().parent != _owned_jobs_root().resolve():
            raise ValueError("任务保存目录超出指定沙箱")
        directory.mkdir(parents=True, exist_ok=True)
        if job.media_path.is_symlink():
            raise ValueError("媒体路径不能使用符号链接")
        job.media_path.write_bytes(content)
        _atomic_json(directory / "job.json", _metadata(job))
        pool.submit_reserved(entry, run_async=run_async)
    except Exception:
        pool.release(entry)
        raise
    return {"accepted": True, "snapshot_id": snapshot_id, "duplicate": False}


def recover_source_transcriptions() -> list[str]:
    """接纳首批磁盘任务；工作线程会持续补入后续 backlog。"""
    purged = purge_orphaned_transcriptions()
    if purged:
        logger.info("已清理过期孤儿转写: count=%d", len(purged))
    pool = start_source_transcription_pool()
    recovered = pool.fill_backlog()
    pool.recovery_enabled = True
    return recovered


def _orphan_retention_days() -> float:
    raw = os.environ.get("NOTEWEAVE_TRANSCRIPTION_ORPHAN_RETENTION_DAYS", "")
    if not raw:
        return float(_DEFAULT_ORPHAN_RETENTION_DAYS)
    try:
        value = float(raw)
    except ValueError:
        logger.warning("孤儿转写保留天数配置无效，回退默认值: value=%s", raw)
        return float(_DEFAULT_ORPHAN_RETENTION_DAYS)
    # NaN 与无穷在下面这个链式比较里都为假，一并回退默认值
    if not 0 <= value < 1_000_000:
        logger.warning("孤儿转写保留天数配置越界，回退默认值: value=%s", raw)
        return float(_DEFAULT_ORPHAN_RETENTION_DAYS)
    return value


def purge_orphaned_transcriptions(*, retention_days: float | None = None, now: float | None = None) -> list[str]:
    """清理超过保留期的孤儿转写（任务目录与文字稿输出），返回被清理的目录名。

    只动状态为 ORPHANED 的任务目录，以及已经没有对应任务目录的遗留输出目录；
    排队中、运行中、等待回调的任务一律不碰。
    """
    window = (retention_days if retention_days is not None else _orphan_retention_days()) * 86400
    moment = time.time() if now is None else now
    purged: list[str] = []
    try:
        jobs = _owned_jobs_root()
    except ValueError:
        return purged
    if jobs.is_dir():
        for directory in jobs.iterdir():
            if not directory.is_dir() or directory.is_symlink():
                continue
            checkpoint = directory / "job.json"
            try:
                meta = _strict_json(checkpoint)
            except (OSError, ValueError):
                continue
            if not isinstance(meta, dict) or meta.get("state") != _ORPHANED_STATE:
                continue
            if moment - checkpoint.stat().st_mtime < window:
                continue
            _remove_owned_tree(directory)
            _remove_owned_tree(_output_dir_by_name(directory.name))
            purged.append(directory.name)
    outputs = _sandbox_root() / "bilibili-render-pdf" / "source-transcriptions"
    if outputs.is_dir():
        for directory in outputs.iterdir():
            if not directory.is_dir() or directory.is_symlink():
                continue
            if (jobs / directory.name).exists():
                continue
            if moment - directory.stat().st_mtime < window:
                continue
            _remove_owned_tree(directory)
            purged.append(directory.name)
    return purged


def _output_dir_by_name(name: str) -> Path:
    return _sandbox_root() / "bilibili-render-pdf" / "source-transcriptions" / name


def _remove_owned_tree(directory: Path) -> None:
    """只删沙箱内、非符号链接的目录树。"""
    try:
        if directory.is_symlink() or not directory.is_dir():
            return
        if not directory.resolve().is_relative_to(_sandbox_root().resolve()):
            return
        shutil.rmtree(directory)
    except OSError as exc:
        logger.warning("清理孤儿转写目录失败: name=%s reason=%s", directory.name, sanitize_error_message(str(exc)))


def run_transcription_job(
    job: TranscriptionJob,
    *,
    call_tool: Callable[[object, dict[str, object]], dict[str, object]] = _call_custom_mcp_tool,
    post_callback: Callable[[str, dict[str, object]], bool | CallbackAcknowledgement] | None = None,
    llm_client: LlmClient | None | bool = True,
) -> dict[str, object]:
    """执行一次转写并回调 Java；返回回调内容，便于测试。

    llm_client 为 True 时使用 Artifact Worker 配置的大模型校对文字稿；为 None 时跳过校对。
    """
    deliver = post_callback or _post_callback
    if not job.content_sha256:
        job = replace(job, content_sha256=_hash_file(job.media_path), file_size=job.media_path.stat().st_size)
    _validate_owned(job)
    if not (job.media_path.parent / "job.json").is_file():
        _atomic_json(job.media_path.parent / "job.json", _metadata(job, state="RUNNING"))
    output_dir = _output_root(job)
    if output_dir.is_symlink() or not output_dir.resolve().is_relative_to(_sandbox_root().resolve()):
        raise ValueError("转写输出目录超出指定沙箱")
    pending_path = job.media_path.parent / "callback-pending.json"
    if pending_path.is_file():
        pending = _strict_json(pending_path, _MAX_CHECKPOINT_BYTES)
        if (not isinstance(pending, dict) or set(pending) != {"schema_version", "identity", "payload"}
                or pending["schema_version"] != 1 or pending["identity"] != list(_identity(job)) or not isinstance(pending["payload"], dict)):
            raise ValueError("回调检查点与冻结身份不一致")
        payload = pending["payload"]
    else:
        payload = _transcribe_payload(job, output_dir, call_tool, llm_client)
        if job.task_id:
            payload["task_id"] = job.task_id
        _validate_callback_payload(job, payload)
        _atomic_json(pending_path, {"schema_version": 1, "identity": list(_identity(job)), "payload": payload})
    _atomic_json(job.media_path.parent / "job.json", _metadata(job, state="CALLBACK_PENDING"))
    _validate_callback_payload(job, payload)
    try:
        acknowledgement = deliver(job.snapshot_id, payload)
    except Exception as exc:
        logger.warning("转写回调失败，保留检查点: snapshot=%s reason=%s", job.snapshot_id, sanitize_error_message(str(exc)))
        acknowledgement = False
    accepted = acknowledgement is True or (
        isinstance(acknowledgement, CallbackAcknowledgement) and acknowledgement.accepted is True)
    discarded = isinstance(acknowledgement, CallbackAcknowledgement) and acknowledgement.discarded is True
    if accepted:
        _validate_owned(job)
        shutil.rmtree(job.media_path.parent)
        if output_dir.exists() and not output_dir.is_symlink() and output_dir.resolve().parent == output_dir.parent.resolve():
            shutil.rmtree(output_dir)
    elif discarded:
        # SRC-02：宿主丢弃说明这次投递已被更新代次取代，不代表文字稿没有价值——
        # 它是这次转写唯一的产物，直接删掉等于把已经付费的结果丢掉。保留任务目录与文字稿，
        # 状态标成孤儿，交给人工或后续保留策略处理；恢复流程不会重新投递它。
        _atomic_json(job.media_path.parent / "job.json", _metadata(job, state=_ORPHANED_STATE))
        logger.warning("转写回调被丢弃，保留文字稿并标记孤儿: snapshot=%s output=%s", job.snapshot_id, output_dir.name)
        return {**payload, "callback_disposition": "DISCARDED_RETAINED", "retained_output": output_dir.name}
    return payload


def _transcribe_payload(job, output_dir, call_tool, llm_client):
    try:
        server = resolve_system_mcp_server(SYSTEM_BILIBILI_SERVER_ID)
        result = call_tool(server, {
            "tool_name": "transcribe_local_audio",
            "tool_arguments": {
                "input_path": str(job.media_path),
                "output_dir": str(output_dir),
                "model": job.model,
                "language": job.language,
                "device": job.device,
                "skip_existing": False,
                "json_output": True,
            },
        })
        transcript = _read_transcript(result, expected_dir=output_dir)
        lines = str(transcript["text"]).split("\n") if transcript["text"] else []
        corrector = build_default_llm_client() if llm_client is True else llm_client
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
        _validate_callback_payload(job, {**payload, **({"task_id": job.task_id} if job.task_id else {})})
    except Exception as exc:  # noqa: BLE001 - 任何失败都要回调 Java，避免资料停在转写中
        logger.warning("source transcription failed: snapshot=%s error=%s", job.snapshot_id, exc)
        payload = {
            "workspace_id": job.workspace_id,
            "source_id": job.source_id,
            "status": "FAILED",
            "error_code": "SOURCE_TRANSCRIPTION_FAILED",
            "error_message": "音视频转写失败：" + sanitize_error_message(str(exc))[:300],
        }
    return payload


def _validate_callback_payload(job, payload):
    import math
    identity_fields = {"workspace_id", "source_id", "status"} | ({"task_id"} if job.task_id else set())
    complete_fields = {"transcript_text", "corrected_line_count", "language", "duration_seconds", "segment_count"}
    failure_fields = {"error_code", "error_message"}
    if (not isinstance(payload, dict) or payload.get("workspace_id") != job.workspace_id or payload.get("source_id") != job.source_id
            or payload.get("task_id", "") != job.task_id or payload.get("status") not in {"COMPLETED", "FAILED"}):
        raise ValueError("转写回调检查点身份或状态无效")
    if set(payload) != identity_fields | (complete_fields if payload["status"] == "COMPLETED" else failure_fields):
        raise ValueError("转写回调检查点字段无效")
    if payload["status"] == "COMPLETED":
        if not isinstance(payload["transcript_text"], str) or not payload["transcript_text"].strip() or len(payload["transcript_text"]) > 20_000_000:
            raise ValueError("转写文字稿为空或超过字符上限")
        if not isinstance(payload["language"], str) or len(payload["language"]) > 100:
            raise ValueError("转写语言无效")
        for field in ("segment_count", "corrected_line_count"):
            if type(payload[field]) is not int or not 0 <= payload[field] <= 5_000_000:
                raise ValueError("转写片段或校对计数无效")
        duration = payload["duration_seconds"]
        if type(duration) not in (int, float) or not math.isfinite(duration) or duration < 0:
            raise ValueError("转写时长无效")
    elif payload["error_code"] != "SOURCE_TRANSCRIPTION_FAILED" or not isinstance(payload["error_message"], str) or not 0 < len(payload["error_message"]) <= 1000:
        raise ValueError("转写失败信息无效")


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


def _read_transcript(result: dict[str, object], *, expected_dir: Path | None = None) -> dict[str, object]:
    artifacts = result.get("artifacts") if isinstance(result, dict) else None
    json_files = artifacts.get("json_files") if isinstance(artifacts, dict) else None
    if not json_files:
        raise ValueError("transcription tool returned no transcript file")
    path = Path(str(json_files[0]))
    if path.is_symlink() or (expected_dir is not None and not path.resolve().is_relative_to(expected_dir.resolve())):
        raise ValueError("转写结果路径超出本任务输出目录")
    document = _strict_json(path, _MAX_CHECKPOINT_BYTES)
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


def _post_callback(snapshot_id: str, payload: dict[str, object]) -> CallbackAcknowledgement:
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
            data = result.get("data") if isinstance(result, dict) else None
            if (isinstance(data, dict) and data.get("snapshot_id") == snapshot_id
                    and set(data).issubset({"snapshot_id", "accepted", "discarded"})
                    and type(data.get("accepted")) is bool and type(data.get("discarded", False)) is bool):
                acknowledgement = CallbackAcknowledgement(data["accepted"], data.get("discarded", False))
                if acknowledgement.confirmed:
                    return acknowledgement
        except (error.URLError, TimeoutError, ValueError) as exc:
            logger.warning("transcription callback failed: snapshot=%s attempt=%s error=%s", snapshot_id, attempt + 1, exc)
        time.sleep(min(60, 2 ** (attempt + 1)))
    logger.warning("transcription callback was not accepted: snapshot=%s", snapshot_id)
    return CallbackAcknowledgement()


def _sandbox_root() -> Path:
    return resolve_mcp_sandbox_root()
