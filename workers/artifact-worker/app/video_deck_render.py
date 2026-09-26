from __future__ import annotations

import hashlib
import re
from io import BytesIO
from pathlib import Path
from typing import Callable

from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE_TYPE
from pptx.util import Inches, Pt

from app.config import resolve_mcp_sandbox_root
from app.video_deck_ir import VideoDeckIRV1
from app.video_knowledge_plan import VideoKnowledgePlanV1
from app.video_material_bundle import VideoMaterialBundleV1


WIDTH = Inches(13.333)
HEIGHT = Inches(7.5)
IMAGE_BOX = (Inches(0.7), Inches(1.45), Inches(7.65), Inches(5.25))
TEXT_BOX = (Inches(8.7), Inches(1.45), Inches(3.9), Inches(4.9))


def _fit_image(width: int, height: int) -> tuple[int, int, int, int]:
    left, top, box_width, box_height = IMAGE_BOX
    scale = min(box_width / width, box_height / height)
    fitted_width = round(width * scale)
    fitted_height = round(height * scale)
    return (left + (box_width - fitted_width) // 2,
            top + (box_height - fitted_height) // 2,
            fitted_width, fitted_height)


def render_original_video_deck(
    task_id: str, ir: VideoDeckIRV1, bundle: VideoMaterialBundleV1,
    plan: VideoKnowledgePlanV1, read_file: Callable[[str], bytes],
) -> Path:
    """Embed verified original frames without cropping; text remains editable."""
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", task_id):
        raise ValueError("video deck task ID is unsafe")
    ir.verify_against(bundle, plan)
    bundle.verify_file_bytes(read_file)
    by_file = {item.file_id: item for item in bundle.files}
    presentation = Presentation()
    presentation.slide_width = WIDTH
    presentation.slide_height = HEIGHT
    for slide_ir in ir.slides:
        if len(slide_ir.title) > 70 or sum(len(item) for item in slide_ir.claims) > 320:
            raise ValueError("video deck slide text exceeds fixed template bounds")
        frame = by_file[slide_ir.file_id]
        content = read_file(frame.file_id)
        if hashlib.sha256(content).hexdigest() != slide_ir.image_checksum_sha256:
            raise ValueError("video deck image bytes differ from frozen frame")
        with Image.open(BytesIO(content)) as image:
            dimensions = image.size
        slide = presentation.slides.add_slide(presentation.slide_layouts[6])
        slide.background.fill.solid()
        slide.background.fill.fore_color.rgb = RGBColor(250, 249, 246)
        title = slide.shapes.add_textbox(Inches(0.7), Inches(0.42), Inches(11.9), Inches(0.7))
        title.text_frame.text = slide_ir.title
        title_paragraph = title.text_frame.paragraphs[0]
        title_paragraph.font.name = "Aptos"
        title_paragraph.font.size = Pt(30)
        title_paragraph.font.bold = True
        title_paragraph.font.color.rgb = RGBColor(25, 44, 62)
        x, y, width, height = _fit_image(*dimensions)
        picture = slide.shapes.add_picture(BytesIO(content), x, y, width=width, height=height)
        if picture.crop_left or picture.crop_right or picture.crop_top or picture.crop_bottom:
            raise ValueError("original video frame was cropped")
        body = slide.shapes.add_textbox(*TEXT_BOX)
        body.text_frame.word_wrap = True
        body.text_frame.text = "\n\n".join(slide_ir.claims) if slide_ir.claims else \
            "Visual evidence only; meaning remains unverified."
        for paragraph in body.text_frame.paragraphs:
            paragraph.font.name = "Aptos"
            paragraph.font.size = Pt(18)
            paragraph.font.color.rgb = RGBColor(38, 51, 60)
        footer = slide.shapes.add_textbox(Inches(0.7), Inches(6.9), Inches(11.9), Inches(0.35))
        footer.text_frame.text = (
            f"{ir.bvid} · P{ir.part} · {slide_ir.at_ms / 1000:.1f}s · "
            f"{slide_ir.frame_id} · {slide_ir.sequence_no}/{len(ir.slides)}")
        paragraph = footer.text_frame.paragraphs[0]
        paragraph.font.name = "Aptos"
        paragraph.font.size = Pt(10)
        paragraph.font.color.rgb = RGBColor(89, 98, 102)
        slide.notes_slide.notes_text_frame.text = (
            f"Frame: {slide_ir.frame_id}; SHA-256: {slide_ir.image_checksum_sha256}; "
            f"Evidence: {', '.join(slide_ir.evidence_refs)}; "
            f"Gaps: {', '.join(slide_ir.coverage_gaps)}")
    output_dir = resolve_mcp_sandbox_root() / "bilibili-render-pdf" / "exports" / task_id
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "learning-deck.pptx"
    presentation.save(output_path)
    verify_original_video_deck(output_path, ir)
    return output_path


def verify_original_video_deck(path: Path, ir: VideoDeckIRV1) -> None:
    presentation = Presentation(path)
    if len(presentation.slides) != len(ir.slides) \
            or presentation.slide_width != WIDTH or presentation.slide_height != HEIGHT:
        raise ValueError("video deck page count or dimensions differ from IR")
    for index, (slide, spec) in enumerate(zip(presentation.slides, ir.slides), start=1):
        pictures = [shape for shape in slide.shapes
                    if shape.shape_type == MSO_SHAPE_TYPE.PICTURE]
        if len(pictures) != 1 or hashlib.sha256(pictures[0].image.blob).hexdigest() \
                != spec.image_checksum_sha256:
            raise ValueError(f"video deck slide {index} changed its source image")
        picture = pictures[0]
        if any((picture.crop_left, picture.crop_right,
                picture.crop_top, picture.crop_bottom)) \
                or picture.left < 0 or picture.top < 0 \
                or picture.left + picture.width > WIDTH \
                or picture.top + picture.height > HEIGHT:
            raise ValueError(f"video deck slide {index} crops or overflows source image")
        text = "\n".join(shape.text for shape in slide.shapes if shape.has_text_frame)
        if spec.title not in text or any(claim not in text for claim in spec.claims):
            raise ValueError(f"video deck slide {index} lost editable evidence text")
