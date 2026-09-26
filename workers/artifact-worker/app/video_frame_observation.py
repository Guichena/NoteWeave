from __future__ import annotations

import csv
import hashlib
import io
import json
import re
import shutil
import subprocess
from pathlib import Path
from typing import Callable

from PIL import Image


SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,100}")
SHA256 = re.compile(r"[0-9a-f]{64}")
MAX_FRAME_BYTES = 16_000_000
MAX_PIXELS = 50_000_000
MAX_OCR_BYTES = 1_000_000


def observe_staged_frame(
    *, sandbox_root: Path, task_id: str, file_id: str, checksum_sha256: str,
    stage: str = "STAGED",
    tesseract_path: str = "", run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> dict[str, object]:
    """Read one frozen frame by ID and report OCR evidence without inferring image meaning."""
    if not SAFE_ID.fullmatch(task_id) or not SAFE_ID.fullmatch(file_id) \
            or not SHA256.fullmatch(checksum_sha256):
        raise ValueError("frame observation identity or digest is invalid")
    root = sandbox_root.resolve()
    if stage == "STAGED":
        export_dir = root / "bilibili-render-pdf" / "exports" / task_id
    elif stage == "CAPTURED":
        export_dir = root / "bilibili-render-pdf" / "frames" / task_id / "images"
    else:
        raise ValueError("frame observation stage is invalid")
    if export_dir.resolve() != export_dir or not export_dir.is_dir():
        raise ValueError("frame observation is outside the Worker sandbox")
    candidates = [export_dir / f"{file_id}{suffix}" for suffix in (".png", ".jpg")]
    present = [path for path in candidates if path.exists() or path.is_symlink()]
    if len(present) != 1 or present[0].is_symlink() \
            or not present[0].resolve().is_relative_to(root) \
            or not present[0].is_file():
        raise ValueError("frame observation requires one staged frame file")
    path = present[0]
    size = path.stat().st_size
    if not 0 < size <= MAX_FRAME_BYTES:
        raise ValueError("frame observation exceeds the file size limit")
    data = path.read_bytes()
    if len(data) != size or hashlib.sha256(data).hexdigest() != checksum_sha256:
        raise ValueError("frame observation bytes do not match the frozen digest")
    try:
        with Image.open(io.BytesIO(data)) as image:
            media_type = {"PNG": "image/png", "JPEG": "image/jpeg"}.get(image.format)
            width, height = image.size
            if media_type is None or width < 1 or height < 1 \
                    or width * height > MAX_PIXELS \
                    or (media_type == "image/png") != (path.suffix == ".png"):
                raise ValueError("frame observation image format is invalid")
            image.verify()
    except (OSError, ValueError) as exc:
        raise ValueError("frame observation image is not decodable") from exc
    executable = tesseract_path or shutil.which("tesseract")
    if not executable:
        raise RuntimeError("tesseract is required for frame text observation")
    completed = run(
        [executable, str(path), "stdout", "-l", "eng+chi_sim", "tsv"],
        cwd=str(export_dir), text=True, capture_output=True, timeout=45, check=False,
    )
    if completed.returncode != 0 or len(completed.stdout.encode("utf-8")) > MAX_OCR_BYTES:
        raise ValueError("frame text observation failed or exceeded the output limit")
    observations = _parse_ocr_lines(completed.stdout)
    return {
        "schema_version": "frame-observation-v1", "task_id": task_id,
        "file_id": file_id, "checksum_sha256": checksum_sha256,
        "media_type": media_type, "width": width, "height": height,
        "observations": observations,
        "coverage_gaps": (["NO_READABLE_TEXT"] if not observations else [])
            + ["VISUAL_SEMANTICS_UNVERIFIED"],
    }


def _parse_ocr_lines(tsv: str) -> list[dict[str, object]]:
    grouped: dict[tuple[str, str, str, str], list[tuple[str, float]]] = {}
    for row in csv.DictReader(io.StringIO(tsv), delimiter="\t"):
        if not {"text", "conf", "page_num", "block_num", "par_num", "line_num"} <= set(row):
            raise ValueError("frame OCR output is missing required columns")
        word = str(row["text"] or "").strip()
        if not word:
            continue
        try:
            confidence = float(row["conf"])
        except (TypeError, ValueError) as exc:
            raise ValueError("frame OCR confidence is invalid") from exc
        if not 0 <= confidence <= 100:
            continue
        key = tuple(str(row[name]) for name in ("page_num", "block_num", "par_num", "line_num"))
        grouped.setdefault(key, []).append((word[:200], confidence))
        if len(grouped) > 64 or sum(len(words) for words in grouped.values()) > 512:
            raise ValueError("frame OCR output exceeds the observation limit")
    result = []
    for words in grouped.values():
        text = " ".join(word for word, _ in words)[:1_000]
        confidence = round(sum(score for _, score in words) / len(words), 1)
        result.append({"kind": "TEXT", "text": text, "confidence": confidence,
                       "uncertain": confidence < 80})
    return result


def verify_frame_observation_batch(
    *, task_id: str, files: list[object], payload: dict[str, object],
) -> str:
    """Bind an acknowledged OCR receipt to every byte-verified frame manifest entry."""
    if set(payload) != {"schema_version", "task_id", "frames", "coverage_gaps"} \
            or payload.get("schema_version") != "frame-observation-batch-v1" \
            or payload.get("task_id") != task_id \
            or not isinstance(payload.get("frames"), list):
        raise ValueError("frame observation receipt identity is invalid")
    expected = {str(getattr(file, "file_id")): (
        str(getattr(file, "checksum_sha256")), str(getattr(file, "media_type")))
                for file in files}
    frames = payload["frames"]
    if len(frames) != len(expected) or len(frames) > 32:
        raise ValueError("frame observation receipt does not cover the captured files")
    seen: set[str] = set()
    for frame in frames:
        if not isinstance(frame, dict) or set(frame) != {
            "schema_version", "task_id", "file_id", "checksum_sha256",
            "media_type", "width", "height", "observations", "coverage_gaps",
        } or frame["schema_version"] != "frame-observation-v1" \
                or frame["task_id"] != task_id:
            raise ValueError("frame observation entry has an invalid schema or task")
        file_id = frame["file_id"]
        if not isinstance(file_id, str) or file_id in seen \
                or expected.get(file_id) != (frame["checksum_sha256"], frame["media_type"]):
            raise ValueError("frame observation entry does not match a captured file")
        seen.add(file_id)
        width, height = frame["width"], frame["height"]
        if frame["media_type"] not in {"image/png", "image/jpeg"} \
                or isinstance(width, bool) or not isinstance(width, int) \
                or isinstance(height, bool) or not isinstance(height, int) \
                or width < 1 or height < 1 or width * height > MAX_PIXELS:
            raise ValueError("frame observation image metadata is invalid")
        observations = frame["observations"]
        gaps = frame["coverage_gaps"]
        if not isinstance(observations, list) or len(observations) > 64 \
                or not isinstance(gaps, list) or any(not isinstance(gap, str) for gap in gaps) \
                or len(gaps) != len(set(gaps)) \
                or "VISUAL_SEMANTICS_UNVERIFIED" not in gaps \
                or not set(gaps) <= {"NO_READABLE_TEXT", "VISUAL_SEMANTICS_UNVERIFIED"} \
                or (not observations) != ("NO_READABLE_TEXT" in gaps):
            raise ValueError("frame observation evidence and gaps disagree")
        for item in observations:
            if not isinstance(item, dict) or set(item) != {
                "kind", "text", "confidence", "uncertain",
            } or item["kind"] != "TEXT" or not isinstance(item["text"], str) \
                    or not 0 < len(item["text"]) <= 1_000 \
                    or isinstance(item["confidence"], bool) \
                    or not isinstance(item["confidence"], (int, float)) \
                    or not 0 <= item["confidence"] <= 100 \
                    or not isinstance(item["uncertain"], bool) \
                    or item["uncertain"] != (item["confidence"] < 80):
                raise ValueError("frame observation text evidence is invalid")
    if set(seen) != set(expected) \
            or payload.get("coverage_gaps") != (["NO_FRAMES"] if not expected else []):
        raise ValueError("frame observation batch coverage is invalid")
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True,
                           separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
