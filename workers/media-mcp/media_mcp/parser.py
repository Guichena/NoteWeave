from __future__ import annotations

import json
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path, PurePosixPath

from . import bilibili
from .config import Settings
from .frames import capture
from .process import remaining, run
from .timeline import align


def probe(path: Path, directory: Path, deadline: float, settings: Settings) -> tuple[int, bool]:
    info = json.loads(run(["ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)], directory, deadline))
    duration = round(float(info.get("format", {}).get("duration", 0)) * 1000)
    if not 0 < duration <= settings.max_duration_ms:
        raise ValueError("音视频时长为空或超过允许上限")
    return duration, any(stream.get("codec_type") == "video" and not stream.get("disposition", {}).get("attached_pic")
                         for stream in info.get("streams", []))


def parse_part(path: Path, directory: Path, part: int, deadline: float, settings: Settings,
               subtitle_info: dict | None = None) -> tuple[list[dict], dict]:
    duration_ms, has_video = probe(path, directory, deadline, settings)

    def transcript():
        segments = bilibili.subtitles(subtitle_info, deadline) if subtitle_info else []
        if segments:
            return segments, "SUBTITLE"
        output = directory / "transcript.json"
        options = {"model": settings.model, "language": settings.language, "device": settings.device,
                   "model_cache": settings.model_cache, "max_chars": settings.max_chars}
        # cwd 必须包含 media_mcp 包，解析数据始终写入本次临时目录。
        run([sys.executable, "-m", "media_mcp.transcription", str(path), str(output), json.dumps(options)],
            Path(__file__).resolve().parent.parent, deadline)
        result = json.loads(output.read_text(encoding="utf-8"))
        if isinstance(result, dict) and result.get("error"):
            raise ValueError(result["error"])
        return result, "ASR"

    with ThreadPoolExecutor(max_workers=2) as pool:
        words = pool.submit(transcript)
        pictures = pool.submit(capture, path, directory / "frames", part, duration_ms, deadline, settings) if has_video else None
        segments, origin = words.result(timeout=remaining(deadline))
        frames, candidates = pictures.result(timeout=remaining(deadline)) if pictures else ([], 0)
    return align(segments, frames, part, duration_ms), {"candidate_frames": candidates, "kept_frames": len(frames),
        "transcript_origin": origin, "duration_ms": duration_ms,
        "vision_status": "ENABLED" if settings.vision_enabled else "NOT_CONFIGURED"}


def validate(document: dict, settings: Settings) -> dict:
    segments = document["segments"]
    chars = sum(len(row["text"]) + sum(len(frame["caption"]) + len(frame["ocr_text"]) for frame in row["frames"]) for row in segments)
    image_bytes = sum(len(frame["image_ref"]) for row in segments for frame in row["frames"])
    if chars > settings.max_chars or image_bytes > 64_000_000:
        raise ValueError("图文文档超过大小上限")
    if document["stats"]["kept_frames"] > settings.max_frames:
        raise ValueError("解析关键帧总数超过上限")
    if document["stats"]["duration_ms"] > settings.max_duration_ms:
        raise ValueError("所选分 P 的总时长超过上限")
    if not segments:
        raise ValueError("音视频中没有可用的文字或画面")
    return document


def parse_media(file_ref: str, title: str, settings: Settings) -> dict:
    reference = PurePosixPath(file_ref)
    if reference.is_absolute() or not reference.parts or ".." in reference.parts or "\\" in file_ref or ":" in file_ref:
        raise ValueError("上传媒体引用必须为输入目录内的相对路径")
    path = settings.input_root.joinpath(*reference.parts)
    current = settings.input_root
    for part in reference.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("媒体引用不能经过符号链接")
    path = path.resolve()
    if not path.is_relative_to(settings.input_root) or not path.is_file() or not 0 < path.stat().st_size <= 128 * 1024 * 1024:
        raise ValueError("上传媒体不存在或超过 128 MiB 上限")
    with tempfile.TemporaryDirectory(prefix="noteweave-media-") as temporary:
        segments, stats = parse_part(path, Path(temporary), 1, time.monotonic() + settings.timeout, settings)
        return validate({"title": title[:300], "source_url": "", "segments": segments, "stats": stats}, settings)


def parse_video(source_url: str, parts: list[int], settings: Settings) -> dict:
    url = bilibili.video_url(source_url)
    if not parts or len(parts) > 1000 or len(set(parts)) != len(parts) or any(type(part) is not int or not 1 <= part <= 1000 for part in parts):
        raise ValueError("请选择有效且不重复的视频分 P")
    deadline = time.monotonic() + settings.timeout
    stats = {"candidate_frames": 0, "kept_frames": 0, "duration_ms": 0,
             "vision_status": "ENABLED" if settings.vision_enabled else "NOT_CONFIGURED"}
    rows, origins = [], set()
    with tempfile.TemporaryDirectory(prefix="noteweave-video-") as temporary:
        root = Path(temporary)
        info = bilibili.video_info(url, root, deadline, settings)
        if any(part > len(info["parts"]) for part in parts):
            raise ValueError("所选分 P 不存在")
        for part in sorted(parts):
            directory = root / str(part)
            directory.mkdir()
            part_url = bilibili.video_url(url, part)
            metadata = bilibili.metadata(part_url, directory, deadline, settings)
            if float(metadata.get("duration") or 0) * 1000 + stats["duration_ms"] > settings.max_duration_ms:
                raise ValueError("所选分 P 的总时长超过上限")
            media = bilibili.download(part_url, directory, deadline, settings)
            segments, result = parse_part(media, directory, part, deadline, settings, metadata)
            rows.extend(segments)
            origins.add(result["transcript_origin"])
            for key in ("candidate_frames", "kept_frames", "duration_ms"):
                stats[key] += result[key]
            validate({"segments": rows, "stats": stats}, settings)
        stats["transcript_origin"] = next(iter(origins)) if len(origins) == 1 else "MIXED"
        return validate({"title": info["title"], "source_url": info["source_url"], "segments": rows, "stats": stats}, settings)
