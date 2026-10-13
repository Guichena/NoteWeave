"""从真实 Docling 元素与来源坐标构建输出，不推算缺失的页码或位置。"""

import math
import re

from app.errors import ParseFailure
from app.models import BoundingBox, ParsedElement, ParseResponse
from app.text_provenance import TextProvenance


_LABELS = {"title": "TITLE", "section_header": "SECTION_HEADER", "table": "TABLE", "caption": "CAPTION",
    "formula": "FORMULA", "code": "CODE", "list_item": "LIST_ITEM", "footnote": "FOOTNOTE", "picture": "PICTURE"}


def _enum(value):
    return str(getattr(value, "value", value))


def _location(item, warnings):
    provenance = getattr(item, "prov", [])
    if not provenance:
        return None, None
    if len(provenance) > 1:
        warnings.append("存在多个来源位置的元素；完整文本保留，位置字段仅记录首个真实来源")
    first = provenance[0]
    page = getattr(first, "page_no", None)
    if not isinstance(page, int) or isinstance(page, bool) or page < 1:
        page = None
        warnings.append("元素的来源页码不可用，未推测页码")
    source_box = getattr(first, "bbox", None)
    if source_box is None:
        return page, None
    origin = _enum(getattr(source_box, "coord_origin", ""))
    coordinates = [getattr(source_box, key, None) for key in ("l", "t", "r", "b")]
    if origin not in {"TOPLEFT", "BOTTOMLEFT"} or any(
            not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value)
            for value in coordinates):
        warnings.append("元素的来源坐标不可用，未补造坐标")
        return page, None
    left, top, right, bottom = coordinates
    if left > right or (origin == "TOPLEFT" and top > bottom) or (origin == "BOTTOMLEFT" and top < bottom):
        warnings.append("元素的来源坐标方向无效，未交换或补造坐标")
        return page, None
    return page, BoundingBox(left=float(coordinates[0]), top=float(coordinates[1]),
        right=float(coordinates[2]), bottom=float(coordinates[3]), coord_origin=origin)


def _block(element, item):
    text = element.text
    if element.element_type == "TITLE":
        return "# " + text
    if element.element_type == "SECTION_HEADER":
        level = element.heading_level
        return ("#" * min(level, 6) + " " + text) if level else text
    if element.element_type == "LIST_ITEM":
        marker = getattr(item, "marker", "")
        return (marker + " " + text) if isinstance(marker, str) and marker.strip() else "- " + text
    if element.element_type == "CODE":
        raw_language = getattr(item, "code_language", None)
        language = _enum(raw_language) if raw_language is not None else ""
        language = language if re.fullmatch(r"[A-Za-z0-9_+#.-]*", language) else ""
        return "```" + language + "\n" + text + "\n```"
    if element.element_type == "FORMULA":
        return text if text.strip().startswith("$$") else "$$\n" + text + "\n$$"
    if element.element_type == "PICTURE":
        return text if text.strip() else "<!-- image -->"
    return text


def _inside_table(item, document):
    if _enum(getattr(item, "label", "")) in {"caption", "footnote"}:
        return False
    parent, visited = getattr(item, "parent", None), set()
    for _ in range(64):
        if parent is None or not callable(getattr(parent, "resolve", None)):
            return False
        key = getattr(parent, "cref", id(parent))
        if key in visited:
            raise ParseFailure(422, "INVALID_DOCUMENT_TREE", "Docling 文档元素层级存在循环引用")
        visited.add(key)
        value = parent.resolve(document)
        if _enum(getattr(value, "label", "")) == "table":
            return True
        parent = getattr(value, "parent", None)
    raise ParseFailure(422, "INVALID_DOCUMENT_TREE", "Docling 文档元素层级超过可读取范围")


def serialize_document(document, metadata, max_chars, max_elements=100_000, *, conversion_result=None):
    warnings, blocks, elements, used_chars = [], [], [], 0
    provenance = TextProvenance(conversion_result, document)
    truncated = False
    for item, _tree_depth in document.iterate_items(with_groups=False, traverse_pictures=True):
        if _inside_table(item, document):
            continue  # 表格单元格已通过真实表格导出保留，不能再作为独立正文重复输出。
        label = _enum(getattr(item, "label", ""))
        element_type = _LABELS.get(label, "TEXT")
        if element_type == "TABLE":
            text = item.export_to_markdown(doc=document)
        else:
            text = getattr(item, "text", "")
        if not isinstance(text, str):
            raise ParseFailure(422, "INVALID_DOCLING_ELEMENT", "Docling 元素缺少可读取的真实文本")
        if element_type == "FORMULA" and not text.strip():
            # ReadingOrderModel 可将公式的 text 留空，真实识别原文保存在 orig。
            text = getattr(item, "orig", "")
            if not isinstance(text, str):
                raise ParseFailure(422, "INVALID_DOCLING_ELEMENT", "Docling 公式缺少可读取的真实原文")
        if not text.strip() and element_type != "PICTURE":
            continue
        page, bbox = _location(item, warnings)
        level = getattr(item, "level", None) if element_type == "SECTION_HEADER" else None
        if not isinstance(level, int) or isinstance(level, bool) or level < 1:
            level = None
        if level and level > 6:
            warnings.append("存在超过 Markdown 六级的标题；元素保留真实级别，Markdown 按六级呈现")
        text_origin, ocr_confidence = provenance.element(item)
        element = ParsedElement(element_type=element_type, text=text, page=page, bbox=bbox, heading_level=level,
            text_origin=text_origin, ocr_confidence=ocr_confidence)
        block = _block(element, item)
        added_chars = len(block) + (2 if blocks else 0)
        if len(elements) >= max_elements or used_chars + added_chars > max_chars:
            truncated = True
            warnings.append("文本或元素数量达到上限；仅在完整元素边界截断，未截断表格、图注或公式")
            break
        blocks.append(block)
        elements.append(element)
        used_chars += added_chars
    if not any(element.text.strip() for element in elements if element.element_type != "PICTURE"):
        if truncated:
            raise ParseFailure(413, "PARSE_OUTPUT_LIMIT_EXCEEDED", "首个完整文本元素超过输出上限，无法返回可用正文")
        raise ParseFailure(422, "NO_TEXT_CONTENT", "文档没有可读取的文本；未启用 OCR 或 OCR 没有识别出可用文本")
    pages = getattr(document, "pages", {})
    page_count = len(pages) if isinstance(pages, (dict, list)) else 0
    if not page_count:
        warnings.append("Docling 未提供真实页面记录，page_count 为 0，页码字段按原始来源保留")
    return ParseResponse(**metadata, markdown="\n\n".join(blocks), elements=elements,
        page_count=page_count, truncated=truncated, warnings=list(dict.fromkeys(warnings + provenance.warnings)),
        page_sources=provenance.page_sources or [])
