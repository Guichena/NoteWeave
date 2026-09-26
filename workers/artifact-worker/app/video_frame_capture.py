from __future__ import annotations

import hashlib
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from PIL import Image


PTS = re.compile(r"\bpts_time:([0-9]+(?:\.[0-9]+)?)\b")
MAX_FILE_BYTES = 16_000_000
MAX_PIXELS = 50_000_000


@dataclass(frozen=True)
class CapturedFrame:
    frame_id: str
    part: int
    at_ms: int
    file_id: str
    checksum_sha256: str
    size_bytes: int
    path: Path
    dedupe_of: str = ""


@dataclass(frozen=True)
class CaptureResult:
    frames: tuple[CapturedFrame, ...]
    missing_requested_ms: tuple[int, ...]


def sample_times(duration_ms: int, *, interval_ms: int = 30_000,
                 max_frames: int = 32) -> tuple[int, ...]:
    """Include the opening and a near-end frame without exceeding the file gate."""
    if duration_ms < 1 or duration_ms > 86_400_000 or interval_ms < 1_000 \
            or not 1 <= max_frames <= 32:
        raise ValueError("video duration or frame policy is invalid")
    end = max(0, duration_ms - min(500, duration_ms // 2))
    candidates = sorted(set(range(0, end + 1, interval_ms)) | {end})
    if len(candidates) <= max_frames:
        return tuple(candidates)
    if max_frames == 1:
        return (end,)
    step = (len(candidates) - 1) / (max_frames - 1)
    return tuple(candidates[round(index * step)] for index in range(max_frames))


def capture_local_video(
    *, video_path: Path, output_dir: Path, sandbox_root: Path,
    part: int, duration_ms: int, interval_ms: int = 30_000,
    max_frames: int = 32, ffmpeg_path: str = "",
    run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> CaptureResult:
    """Extract bounded PNGs and record actual ffmpeg frame PTS, never inferred timestamps."""
    sandbox = sandbox_root.resolve()
    video = video_path.resolve()
    output = output_dir.resolve()
    if not video.is_file() or not video.is_relative_to(sandbox) \
            or not output.is_relative_to(sandbox) or part < 1 or part > 1_000:
        raise ValueError("video capture requires an in-sandbox video and part")
    ffmpeg = ffmpeg_path or shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("ffmpeg is required for video frame capture")
    output.mkdir(parents=True, exist_ok=True)
    frames: list[CapturedFrame] = []
    missing: list[int] = []
    seen_pixels: dict[str, CapturedFrame] = {}
    for index, requested_ms in enumerate(sample_times(
            duration_ms, interval_ms=interval_ms, max_frames=max_frames)):
        file_id = f"frame-{part}-{index:03d}"
        image_path = output / f"{file_id}.png"
        image_path.unlink(missing_ok=True)
        completed = run([
            ffmpeg, "-hide_banner", "-loglevel", "info", "-nostdin", "-y",
            "-i", str(video), "-ss", f"{requested_ms / 1000:.3f}",
            "-frames:v", "1", "-vf", "scale='min(1280,iw)':-2,showinfo",
            str(image_path),
        ], cwd=str(output), text=True, capture_output=True, timeout=90, check=False)
        pts = PTS.findall(completed.stderr or "")
        if completed.returncode != 0 or not image_path.is_file() or not pts:
            image_path.unlink(missing_ok=True)
            missing.append(requested_ms)
            continue
        at_ms = round(float(pts[-1]) * 1000)
        size = image_path.stat().st_size
        if at_ms < 0 or at_ms >= duration_ms or size < 1 or size > MAX_FILE_BYTES:
            image_path.unlink(missing_ok=True)
            missing.append(requested_ms)
            continue
        try:
            with Image.open(image_path) as image:
                if image.format != "PNG" or image.width * image.height > MAX_PIXELS:
                    raise ValueError("frame format or pixel count is invalid")
                image.verify()
            with Image.open(image_path) as image:
                pixels = hashlib.sha256(image.convert("RGB").tobytes()).hexdigest()
        except (OSError, ValueError):
            image_path.unlink(missing_ok=True)
            missing.append(requested_ms)
            continue
        previous = seen_pixels.get(pixels)
        if previous is not None:
            image_path.unlink()
            frames.append(CapturedFrame(
                f"f-{part}-{index:03d}", part, at_ms, previous.file_id,
                previous.checksum_sha256, previous.size_bytes, previous.path,
                previous.frame_id,
            ))
            continue
        checksum = hashlib.sha256(image_path.read_bytes()).hexdigest()
        frame = CapturedFrame(
            f"f-{part}-{index:03d}", part, at_ms, file_id, checksum, size, image_path,
        )
        seen_pixels[pixels] = frame
        frames.append(frame)
    return CaptureResult(tuple(frames), tuple(missing))
