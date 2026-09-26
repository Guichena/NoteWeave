from __future__ import annotations

import re
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image

from app.video_deck_ir import VideoDeckIRV1
from app.video_deck_render import verify_original_video_deck


def _run(command: list[str], timeout: int) -> str:
    result = subprocess.run(command, capture_output=True, text=True,
                            encoding="utf-8", errors="replace",
                            timeout=timeout, check=False)
    if result.returncode != 0:
        raise ValueError(f"video deck preview tool failed: {command[0]} "
                         f"(exit {result.returncode}): {result.stderr[-500:]}")
    return result.stdout


def rasterize_deck_pdf(pdf: Path, ir: VideoDeckIRV1, output_dir: Path) -> list[Path]:
    """Rasterize a converted deck PDF; missing or extra pages fail closed."""
    metadata = _run(["pdfinfo", str(pdf)], 30)
    pages = re.search(r"^Pages:\s+(\d+)\s*$", metadata, flags=re.MULTILINE)
    if pages is None or int(pages.group(1)) != len(ir.slides):
        raise ValueError("video deck preview page count differs from frozen IR")
    output_dir.mkdir(parents=True, exist_ok=True)
    result: list[Path] = []
    for slide in ir.slides:
        prefix = output_dir / f"slide-{slide.sequence_no:03d}"
        _run(["pdftoppm", "-f", str(slide.sequence_no), "-l",
              str(slide.sequence_no), "-singlefile", "-r", "120",
              "-png", str(pdf), str(prefix)], 90)
        path = prefix.with_suffix(".png")
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError(f"video deck preview {slide.sequence_no} is missing")
        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            if image.width < 1000 or image.height < 500:
                raise ValueError(f"video deck preview {slide.sequence_no} is too small")
        result.append(path)
    return result


def render_original_video_deck_previews(pptx: Path, ir: VideoDeckIRV1) -> list[Path]:
    """Convert with LibreOffice and inspect every rendered page before delivery."""
    verify_original_video_deck(pptx, ir)
    with TemporaryDirectory(prefix="noteweave-deck-") as directory:
        temporary = Path(directory)
        profile = (temporary / "lo-profile").as_uri()
        _run(["soffice", f"-env:UserInstallation={profile}", "--headless",
              "--convert-to", "pdf", "--outdir", str(temporary), str(pptx)], 180)
        pdf = temporary / f"{pptx.stem}.pdf"
        if not pdf.is_file() or pdf.stat().st_size == 0:
            raise ValueError("video deck converter did not produce a PDF")
        return rasterize_deck_pdf(pdf, ir, pptx.parent / "previews")
