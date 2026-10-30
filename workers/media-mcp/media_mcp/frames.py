from __future__ import annotations

import base64
import csv
import hashlib
import io
import re
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import imagehash
from PIL import Image

from .config import Settings
from .process import remaining, run


def ocr(path: Path, deadline: float) -> str:
    # 从旧 observe_staged_frame 复制 TSV 行聚合方式；实际图片仍做解码和大小校验。
    output = run(["tesseract", str(path), "stdout", "-l", "eng+chi_sim", "tsv"], path.parent, deadline)
    if len(output.encode("utf-8")) > 1_000_000:
        raise ValueError("关键帧 OCR 输出超过上限")
    grouped: dict[tuple, list[str]] = {}
    for row in csv.DictReader(io.StringIO(output), delimiter="\t"):
        text = (row.get("text") or "").strip()
        if text and float(row.get("conf") or -1) >= 0:
            key = tuple(row.get(field) for field in ("page_num", "block_num", "par_num", "line_num"))
            grouped.setdefault(key, []).append(text)
    return "\n".join(" ".join(words) for words in grouped.values())


def describe(image_ref: str, text: str, deadline: float, settings: Settings) -> str:
    if not settings.vision_enabled:
        return ""
    if not settings.vision_api_key or settings.vision_model == "placeholder" or ".invalid" in settings.vision_endpoint:
        raise ValueError("画面描述仍使用占位配置，请配置视觉模型或关闭画面描述")
    with httpx.Client(timeout=min(60, remaining(deadline)), follow_redirects=False) as client:
        response = client.post(settings.vision_endpoint,
            headers={"Authorization": "Bearer " + settings.vision_api_key},
            json={"model": settings.vision_model, "max_tokens": 500, "messages": [
                {"role": "user", "content": [
                    {"type": "text", "text": "用简体中文简要描述画面中的图表、结构和内容。只描述可见内容，不推测。OCR：" + text[:8000]},
                    {"type": "image_url", "image_url": {"url": image_ref}}]}]})
        response.raise_for_status()
        if len(response.content) > 100_000:
            raise ValueError("画面描述响应超过上限")
        return str(response.json()["choices"][0]["message"]["content"]).strip()[:4000]


def _caption(image_path: Path, image_ref: str, deadline: float, settings: Settings) -> tuple[str, str]:
    """单帧的 OCR 与画面描述，供并发执行。"""
    text = ocr(image_path, deadline)
    return text, describe(image_ref, text, deadline, settings)


def capture(path: Path, directory: Path, part: int, duration_ms: int, deadline: float, settings: Settings) -> tuple[list[dict], int]:
    directory.mkdir()
    # 保留首帧，其他候选由场景变化筛选；showinfo 记录实际 PTS，不能用抽样序号推算。
    output = run(["ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", str(path), "-an",
                  "-vf", f"select='eq(n,0)+gt(scene,{settings.scene_threshold})',scale='min(1280,iw)':-2,showinfo",
                  "-vsync", "vfr", "-frames:v", str(settings.max_candidates + 1),
                  str(directory / "candidate-%06d.png")], directory, deadline)
    images = sorted(directory.glob("candidate-*.png"))
    timestamps = re.findall(r"\bn:\s*\d+.*?pts_time:([0-9]+(?:\.[0-9]+)?(?:e[-+]?\d+)?)", output, re.IGNORECASE)
    if len(images) > settings.max_candidates:
        raise ValueError("场景候选帧数量超过上限")
    if len(images) != len(timestamps) or not images:
        raise ValueError("无法取得带真实时间戳的关键帧")
    kept = []
    previous = None
    total_bytes = 0
    for index, (image_path, stamp) in enumerate(zip(images, timestamps)):
        remaining(deadline)
        if not 0 < image_path.stat().st_size <= 16_000_000:
            raise ValueError("关键帧图片超过大小上限")
        with Image.open(image_path) as image:
            if image.width * image.height > 50_000_000:
                raise ValueError("关键帧图片像素数量超过上限")
            value = imagehash.phash(image.convert("RGB"))
        if previous is not None and value - previous < settings.phash_threshold:
            continue
        previous = value
        if len(kept) >= settings.max_frames:
            # 场景切换密集的视频（MV、快剪）会产生大量候选帧。这里截断而不是让整次解析失败，
            # 已保留的帧按时间顺序覆盖开头，足够支撑摘要与讲义。
            break
        data = image_path.read_bytes()
        total_bytes += len(data)
        if total_bytes > 48_000_000:
            raise ValueError("关键帧图片总大小超过上限")
        kept.append({"frame_id": f"f_{part}_{index:06d}_{hashlib.sha256(data).hexdigest()[:16]}",
                     "at_ms": min(round(float(stamp) * 1000), duration_ms - 1),
                     "image_ref": "data:image/png;base64," + base64.b64encode(data).decode("ascii"),
                     "path": image_path})
    frames = []
    if kept:
        # OCR 与画面描述都是外部调用，串行时 128 帧要二十多分钟，这里按配置并发执行。
        workers = max(1, min(settings.vision_concurrency, len(kept)))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = [future.result() for future in
                       [pool.submit(_caption, item["path"], item["image_ref"], deadline, settings) for item in kept]]
        for item, (text, caption) in zip(kept, results):
            frames.append({"frame_id": item["frame_id"], "at_ms": item["at_ms"],
                           "image_ref": item["image_ref"], "caption": caption, "ocr_text": text})
    return frames, len(images)
