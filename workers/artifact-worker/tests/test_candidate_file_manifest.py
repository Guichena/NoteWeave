from __future__ import annotations

import hashlib

import pytest

from app.candidate_file_manifest import build_required_files


def test_manifest_binds_markdown_and_controlled_pdf_bytes(tmp_path, monkeypatch) -> None:
    monkeypatch.setattr("app.candidate_file_manifest.resolve_mcp_sandbox_root", lambda: tmp_path)
    pdf_path = tmp_path / "exports" / "lesson.pdf"
    pdf_path.parent.mkdir()
    pdf = b"%PDF-1.4\n" + b"x" * 20 + b"\n%%EOF"
    pdf_path.write_bytes(pdf)

    files = build_required_files("# Lesson", {
        "format": "PDF", "status": "COMPILED",
        "file_name": "lesson.pdf", "pdf_path": str(pdf_path),
    })

    assert [item["role"] for item in files] == ["PRIMARY_MARKDOWN", "PRIMARY_PDF"]
    assert files[0]["checksum_sha256"] == hashlib.sha256(b"# Lesson").hexdigest()
    assert files[1]["checksum_sha256"] == hashlib.sha256(pdf).hexdigest()
    assert files[1]["size_bytes"] == len(pdf)


def test_manifest_rejects_pdf_outside_sandbox_and_missing_pdf(tmp_path, monkeypatch) -> None:
    sandbox = tmp_path / "sandbox"
    sandbox.mkdir()
    monkeypatch.setattr("app.candidate_file_manifest.resolve_mcp_sandbox_root", lambda: sandbox)
    outside = tmp_path / "outside.pdf"
    outside.write_bytes(b"%PDF-1.4\n" + b"x" * 20 + b"\n%%EOF")

    with pytest.raises(ValueError, match="outside the controlled sandbox"):
        build_required_files("# Lesson", {
            "format": "PDF", "status": "COMPILED",
            "file_name": "outside.pdf", "pdf_path": str(outside),
        })
    with pytest.raises(ValueError, match="did not compile"):
        build_required_files("# Lesson", {"format": "PDF", "status": "FAILED"})
