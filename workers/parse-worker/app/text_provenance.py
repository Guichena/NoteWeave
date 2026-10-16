"""从组装元素的真实文本单元与逐项来源位置读取 OCR 来源，不用页级标记代替元素来源。"""

import math

from app.ocr_pipeline import audit_for, confidence, recognized_cells


def _enum(value):
    return str(getattr(value, "value", value))


def _box_key(page, box, height):
    values = [getattr(box, name, None) for name in ("l", "t", "r", "b")]
    if any(not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value) for value in values):
        return None
    origin = _enum(getattr(box, "coord_origin", ""))
    if values[0] > values[2] or (origin == "TOPLEFT" and values[1] > values[3]) \
            or (origin == "BOTTOMLEFT" and values[1] < values[3]):
        return None
    if origin == "TOPLEFT" and isinstance(height, (int, float)) and not isinstance(height, bool) \
            and math.isfinite(height) and height > 0:
        values[1], values[3] = height - values[1], height - values[3]
        origin = "BOTTOMLEFT"
    if origin not in {"TOPLEFT", "BOTTOMLEFT"}:
        return None
    return page, origin, *values


class TextProvenance:
    def __init__(self, result, document):
        self.locations = {}
        self.heights = {}
        self.warnings = []
        self.page_sources = None
        self.has_result = result is not None
        pages = getattr(document, "pages", {})
        if isinstance(pages, dict):
            for number, page in pages.items():
                height = getattr(getattr(page, "size", None), "height", None)
                if isinstance(number, int) and not isinstance(number, bool) and number >= 1:
                    self.heights[number] = height
        audit = audit_for(result) if result is not None else None
        if audit is not None:
            actual, warnings = audit.snapshot()
            self.page_sources = actual or None
            self.warnings.extend(warnings)
        elements = getattr(getattr(result, "assembled", None), "elements", []) if result is not None else []
        visited = set()
        for element in elements:
            number = getattr(element, "page_no", None)
            if not isinstance(number, int) or isinstance(number, bool) or number < 1:
                continue
            stack = [getattr(element, "cluster", None)]
            while stack:
                cluster = stack.pop()
                if cluster is None or id(cluster) in visited:
                    continue
                visited.add(id(cluster))
                if len(visited) > 100_000:
                    self.warnings.append("来源关联的结构元素数量达到上限，未关联的元素来源留空")
                    return
                key = _box_key(number, getattr(cluster, "bbox", None), self.heights.get(number))
                cells = tuple(recognized_cells(getattr(cluster, "cells", []) or []))
                if key is not None and cells:
                    if key in self.locations and self.locations[key] != cells:
                        self.locations[key] = None
                    else:
                        self.locations[key] = cells
                stack.extend(getattr(cluster, "children", []) or [])

    def element(self, item):
        if not self.has_result:
            return None, None
        sources = getattr(item, "prov", []) or []
        if not sources:
            return None, None
        cells = {}
        for source in sources:
            number = getattr(source, "page_no", None)
            if not isinstance(number, int) or isinstance(number, bool) or number < 1:
                return None, None
            key = _box_key(number, getattr(source, "bbox", None), self.heights.get(number))
            matched = self.locations.get(key) if key is not None else None
            if not matched:
                self.warnings.append("元素无法与真实组装文本位置精确关联，来源与 OCR 置信度留空")
                return None, None
            cells.update({id(cell): cell for cell in matched})
        raw_flags = [getattr(cell, "from_ocr", None) for cell in cells.values()]
        flags = set(raw_flags) if all(isinstance(flag, bool) for flag in raw_flags) else {None}
        if flags == {False}:
            return "TEXT_LAYER", None
        if flags == {True}:
            score = confidence(list(cells.values()))
            if score is None:
                self.warnings.append("元素的真实 OCR 置信度缺失或无效，未补造分数")
            return "OCR", score
        self.warnings.append("元素混合文本层与 OCR 或缺少真实来源标记，来源与 OCR 置信度留空")
        return None, None
