from __future__ import annotations

import hashlib
import math
import re
import unicodedata
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
from app.video_deck_runtime import DECK_LABELS
from app.video_knowledge_plan import VideoKnowledgePlanV1
from app.video_material_bundle import VideoMaterialBundleV1


WIDTH = Inches(13.333)
HEIGHT = Inches(7.5)
IMAGE_BOX = (Inches(0.7), Inches(1.45), Inches(7.65), Inches(5.25))
TEXT_BOX = (Inches(8.7), Inches(1.45), Inches(3.9), Inches(4.9))


def _display_units(text: str) -> int:
    return sum(2 if unicodedata.east_asian_width(char) in {"W", "F"} else 1
               for char in text)


def _title_font_size(title: str) -> int:
    units = _display_units(title)
    if units > 80:
        raise ValueError("video deck title exceeds fixed template width")
    return 30 if units <= 45 else 24 if units <= 70 else 20


def _body_font_size(claims: list[str]) -> int:
    text = claims or [""]
    width_pt = TEXT_BOX[2] / 12700
    height_pt = TEXT_BOX[3] / 12700
    for size in (18, 16, 14):
        lines = sum(max(1, math.ceil(_display_units(claim) * size * 0.6 / width_pt))
                    for claim in text) + 2 * (len(text) - 1)
        if lines * size * 1.35 <= height_pt:
            return size
    raise ValueError("video deck claims exceed fixed template height")


def _fit_image(width: int, height: int) -> tuple[int, int, int, int]:
    left, top, box_width, box_height = IMAGE_BOX
    scale = min(box_width / width, box_height / height)
    fitted_width = round(width * scale)
    fitted_height = round(height * scale)
    return (left + (box_width - fitted_width) // 2,
            top + (box_height - fitted_height) // 2,
            fitted_width, fitted_height)


def render_original_video_deck(
    task_id: str, ir: VideoDeckIRV1 | dict, bundle: VideoMaterialBundleV1 | None = None,
    plan: VideoKnowledgePlanV1 | None = None, read_file: Callable[[str], bytes] | None = None,
    *, evidence: dict | None = None,
) -> Path:
    """嵌入原始关键帧，不裁剪图片，保留可编辑正文。"""
    if isinstance(ir, dict):
        return _render_deck(task_id, ir, evidence, read_file)
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
        title_paragraph.font.size = Pt(_title_font_size(slide_ir.title))
        title_paragraph.font.bold = True
        title_paragraph.font.color.rgb = RGBColor(25, 44, 62)
        x, y, width, height = _fit_image(*dimensions)
        picture = slide.shapes.add_picture(BytesIO(content), x, y, width=width, height=height)
        if picture.crop_left or picture.crop_right or picture.crop_top or picture.crop_bottom:
            raise ValueError("original video frame was cropped")
        body = slide.shapes.add_textbox(*TEXT_BOX)
        body.text_frame.word_wrap = True
        body.text_frame.text = "\n\n".join(slide_ir.claims) if slide_ir.claims else \
            DECK_LABELS[ir.language][4]
        body_font_size = _body_font_size(slide_ir.claims)
        for paragraph in body.text_frame.paragraphs:
            paragraph.font.name = "Aptos"
            paragraph.font.size = Pt(body_font_size)
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


def _render_deck(task_id, ir, evidence, frame_reader):
    from app.pipeline.renderers.frames import read_frame, time_label
    if ir.get("kind") != "DECK" or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", task_id):
        raise ValueError("幻灯片形态或任务编号无效")
    presentation = Presentation()
    presentation.slide_width, presentation.slide_height = WIDTH, HEIGHT
    images = []
    for index, spec in enumerate(ir["slides"], 1):
        slide = presentation.slides.add_slide(presentation.slide_layouts[6])
        slide.background.fill.solid()
        slide.background.fill.fore_color.rgb = RGBColor(250, 249, 246)
        title = slide.shapes.add_textbox(Inches(0.7), Inches(0.42), Inches(11.9), Inches(0.7))
        title.text_frame.text = spec["title"]
        p = title.text_frame.paragraphs[0]
        p.font.name, p.font.size, p.font.bold = "Noto Sans CJK SC", Pt(_title_font_size(spec["title"])), True
        p.font.color.rgb = RGBColor(25, 44, 62)
        content = None
        figure = spec.get("figure")
        if figure:
            content = read_frame(figure["frame_id"], evidence, frame_reader)
            with Image.open(BytesIO(content)) as image:
                box = _fit_image(*image.size)
            slide.shapes.add_picture(BytesIO(content), *box[:2], width=box[2], height=box[3])
        images.append(content)
        claims = [bullet["text"] for bullet in spec["bullets"]]
        body = slide.shapes.add_textbox(*(TEXT_BOX if figure else (Inches(1.1), Inches(1.6), Inches(11.1), Inches(4.7))))
        body.text_frame.word_wrap = True
        body.text_frame.text = "\n\n".join(claims)
        # 无配图的页面仍按较窄栏宽估算，避免文本溢出。
        size = _body_font_size(claims)
        for paragraph in body.text_frame.paragraphs:
            paragraph.font.name, paragraph.font.size = "Noto Sans CJK SC", Pt(size)
            paragraph.font.color.rgb = RGBColor(38, 51, 60)
        footer = slide.shapes.add_textbox(Inches(0.7), Inches(6.9), Inches(11.9), Inches(0.35))
        footer.text_frame.text = (time_label(figure, evidence) + " · " if figure else "") + f"{index}/{len(ir['slides'])}"
        footer.text_frame.paragraphs[0].font.size = Pt(10)
        slide.notes_slide.notes_text_frame.text = spec.get("speaker_notes", {}).get("text", "")
    output = resolve_mcp_sandbox_root() / "bilibili-render-pdf" / "exports" / task_id
    output.mkdir(parents=True, exist_ok=True)
    path = output / "learning-deck.pptx"
    presentation.save(path)
    saved = Presentation(path)
    if len(saved.slides) != len(ir["slides"]):
        raise ValueError("幻灯片保存后页数与 IR 不一致")
    for slide, spec, content in zip(saved.slides, ir["slides"], images):
        pictures = [shape for shape in slide.shapes if shape.shape_type == MSO_SHAPE_TYPE.PICTURE]
        if len(pictures) != (1 if content else 0) or (content and (pictures[0].image.blob != content
                or any((pictures[0].crop_left, pictures[0].crop_right, pictures[0].crop_top, pictures[0].crop_bottom)))):
            raise ValueError("幻灯片改变或裁剪了原始关键帧")
        text = "\n".join(shape.text for shape in slide.shapes if shape.has_text_frame)
        if spec["title"] not in text or any(b["text"] not in text for b in spec["bullets"]):
            raise ValueError("幻灯片丢失可编辑正文")
    return path
