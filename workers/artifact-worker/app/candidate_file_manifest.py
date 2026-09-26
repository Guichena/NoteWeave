from __future__ import annotations

import hashlib
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile, BadZipFile

from PIL import Image

from app.config import resolve_mcp_sandbox_root


MAX_PDF_BYTES = 100_000_000


def build_video_deck_required_files(markdown: str, pptx: Path,
                                    previews: list[Path]) -> list[dict[str, object]]:
    """Bind one rendered deck and every slide preview to a Candidate."""
    files = build_required_files(markdown, {"format": "MARKDOWN"})
    root = resolve_mcp_sandbox_root().resolve()
    if not 1 <= len(previews) <= 32 or pptx.name != "learning-deck.pptx" \
            or not pptx.resolve().is_relative_to(root):
        raise ValueError("video deck files are outside the controlled sandbox")
    deck = pptx.read_bytes()
    if not 16 <= len(deck) <= 100_000_000:
        raise ValueError("video deck PPTX size is invalid")
    try:
        with ZipFile(BytesIO(deck)) as archive:
            names = archive.namelist()
            if len(names) > 1000 or sum(item.file_size for item in archive.infolist()) > 200_000_000 \
                    or archive.testzip() is not None or "[Content_Types].xml" not in names \
                    or "ppt/presentation.xml" not in names or sum(
                        name.startswith("ppt/slides/slide") and name.endswith(".xml")
                        and name[16:-4].isdigit() for name in names) != len(previews):
                raise ValueError("video deck PPTX page structure is invalid")
    except BadZipFile as exc:
        raise ValueError("video deck PPTX archive is invalid") from exc
    files.append({
        "role": "PRIMARY_PPTX", "variant": "original", "sequence_no": 0,
        "file_name": pptx.name,
        "media_type": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "size_bytes": len(deck), "checksum_sha256": hashlib.sha256(deck).hexdigest(),
    })
    for index, path in enumerate(previews, start=1):
        if path.name != f"slide-{index:03d}.png" \
                or not path.resolve().is_relative_to(root):
            raise ValueError("video deck preview path or order is invalid")
        content = path.read_bytes()
        if not 32 <= len(content) <= 20_000_000:
            raise ValueError("video deck preview size is invalid")
        with Image.open(BytesIO(content)) as image:
            if image.format != "PNG" or image.width < 1000 or image.height < 500:
                raise ValueError("video deck preview format or dimensions are invalid")
            image.verify()
        files.append({
            "role": "SLIDE_PREVIEW", "variant": "original", "sequence_no": index,
            "file_name": path.name, "media_type": "image/png",
            "size_bytes": len(content),
            "checksum_sha256": hashlib.sha256(content).hexdigest(),
        })
    return files


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
