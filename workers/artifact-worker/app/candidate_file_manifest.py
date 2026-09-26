from __future__ import annotations

import hashlib
from pathlib import Path

from app.config import resolve_mcp_sandbox_root


MAX_PDF_BYTES = 100_000_000


def build_required_files(markdown: str, export_trace: dict[str, object]) -> list[dict[str, object]]:
    """Declare bytes the Host must fetch and verify before publishing a Version."""
    markdown_bytes = markdown.encode("utf-8")
    if not markdown_bytes:
        raise ValueError("artifact markdown is empty")
    files: list[dict[str, object]] = [{
        "role": "PRIMARY_MARKDOWN",
        "variant": "",
        "sequence_no": 0,
        "file_name": "content.md",
        "media_type": "text/markdown; charset=UTF-8",
        "size_bytes": len(markdown_bytes),
        "checksum_sha256": hashlib.sha256(markdown_bytes).hexdigest(),
    }]
    if export_trace.get("format") != "PDF":
        return files
    if export_trace.get("status") != "COMPILED":
        raise ValueError("required PDF export did not compile")
    file_name = str(export_trace.get("file_name") or "")
    pdf_path = Path(str(export_trace.get("pdf_path") or "")).resolve()
    sandbox_root = resolve_mcp_sandbox_root().resolve()
    if not file_name or pdf_path.name != file_name or not pdf_path.is_relative_to(sandbox_root):
        raise ValueError("PDF export is outside the controlled sandbox")
    pdf = pdf_path.read_bytes()
    if len(pdf) < 16 or len(pdf) > MAX_PDF_BYTES or not pdf.startswith(b"%PDF-") \
            or b"%%EOF" not in pdf[-2048:]:
        raise ValueError("PDF export has an invalid signature or size")
    files.append({
        "role": "PRIMARY_PDF",
        "variant": "",
        "sequence_no": 0,
        "file_name": file_name,
        "media_type": "application/pdf",
        "size_bytes": len(pdf),
        "checksum_sha256": hashlib.sha256(pdf).hexdigest(),
    })
    return files
