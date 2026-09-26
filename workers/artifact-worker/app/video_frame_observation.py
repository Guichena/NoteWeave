from __future__ import annotations

import csv
import hashlib
import io
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
    tesseract_path: str = "", run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
) -> dict[str, object]:
    """Read one frozen frame by ID and report OCR evidence without inferring image meaning."""
    if not SAFE_ID.fullmatch(task_id) or not SAFE_ID.fullmatch(file_id) \
            or not SHA256.fullmatch(checksum_sha256):
        raise ValueError("frame observation identity or digest is invalid")
    root = sandbox_root.resolve()
    export_dir = root / "bilibili-render-pdf" / "exports" / task_id
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
