from __future__ import annotations

import hashlib
import subprocess

import pytest
from PIL import Image

from app.video_frame_observation import observe_staged_frame


TSV = (
    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tconf\ttext\n"
    "5\t1\t1\t1\t1\t1\t96\tHello\n"
    "5\t1\t1\t1\t1\t2\t90\tworld\n"
    "5\t1\t1\t1\t2\t1\t45\tmaybe\n"
)


def test_observe_staged_frame_returns_grounded_text_and_uncertainty(tmp_path) -> None:
    export_dir = tmp_path / "bilibili-render-pdf" / "exports" / "task-1"
    export_dir.mkdir(parents=True)
    path = export_dir / "frame-1.png"
    Image.new("RGB", (4, 3), "white").save(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    calls = []

    def fake_ocr(command, **kwargs):
        calls.append((command, kwargs))
        return subprocess.CompletedProcess(command, 0, TSV, "")

    result = observe_staged_frame(
        sandbox_root=tmp_path, task_id="task-1", file_id="frame-1",
        checksum_sha256=digest, tesseract_path="tesseract", run=fake_ocr,
    )
    assert result["schema_version"] == "frame-observation-v1"
    assert result["checksum_sha256"] == digest
    assert (result["width"], result["height"]) == (4, 3)
    assert result["observations"] == [
        {"kind": "TEXT", "text": "Hello world", "confidence": 93.0, "uncertain": False},
        {"kind": "TEXT", "text": "maybe", "confidence": 45.0, "uncertain": True},
    ]
    assert result["coverage_gaps"] == ["VISUAL_SEMANTICS_UNVERIFIED"]
    assert calls[0][0][1] == str(path)


def test_observe_staged_frame_rejects_wrong_digest_and_unsafe_identity(tmp_path) -> None:
    export_dir = tmp_path / "bilibili-render-pdf" / "exports" / "task-1"
    export_dir.mkdir(parents=True)
    path = export_dir / "frame-1.png"
    Image.new("RGB", (2, 2), "blue").save(path)
    with pytest.raises(ValueError, match="frozen digest"):
        observe_staged_frame(
            sandbox_root=tmp_path, task_id="task-1", file_id="frame-1",
            checksum_sha256="a" * 64, tesseract_path="tesseract",
        )
    with pytest.raises(ValueError, match="identity"):
        observe_staged_frame(
            sandbox_root=tmp_path, task_id="../other-task", file_id="frame-1",
            checksum_sha256="a" * 64, tesseract_path="tesseract",
        )


def test_observe_staged_frame_reports_no_text_and_rejects_invalid_ocr(tmp_path) -> None:
    export_dir = tmp_path / "bilibili-render-pdf" / "exports" / "task-1"
    export_dir.mkdir(parents=True)
    path = export_dir / "frame-1.png"
    Image.new("RGB", (2, 2), "white").save(path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()

    def empty_ocr(command, **kwargs):
        return subprocess.CompletedProcess(command, 0, TSV.splitlines()[0] + "\n", "")

    result = observe_staged_frame(
        sandbox_root=tmp_path, task_id="task-1", file_id="frame-1",
        checksum_sha256=digest, tesseract_path="tesseract", run=empty_ocr,
    )
    assert result["observations"] == []
    assert result["coverage_gaps"] == ["NO_READABLE_TEXT", "VISUAL_SEMANTICS_UNVERIFIED"]

    def broken_ocr(command, **kwargs):
        return subprocess.CompletedProcess(command, 1, "", "missing language pack")

    with pytest.raises(ValueError, match="failed"):
        observe_staged_frame(
            sandbox_root=tmp_path, task_id="task-1", file_id="frame-1",
            checksum_sha256=digest, tesseract_path="tesseract", run=broken_ocr,
        )
