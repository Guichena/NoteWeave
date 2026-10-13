"""复用固定版本 Docling OCR，只识别缺少文本且确有位图的页面。"""

import math
import threading

from app.models import PageSource


_AUDIT_ATTRIBUTE = "_noteweave_ocr_audit"
_ATTACHMENT_LOCK = threading.Lock()


def recognized_cells(cells):
    return [cell for cell in cells if isinstance(getattr(cell, "text", None), str) and cell.text.strip()]


def confidence(cells):
    scores = [getattr(cell, "confidence", None) for cell in cells]
    if not scores or any(not isinstance(score, (int, float)) or isinstance(score, bool)
            or not math.isfinite(score) or not 0 <= score <= 1 for score in scores):
        return None
    return sum(scores) / len(scores)


class DocumentOcrAudit:
    """仅属于本次 ConversionResult；内部 OCR 线程直接记录该文档的真实页结果。"""

    def __init__(self):
        self.lock = threading.Lock()
        self.pages = {}
        self.warnings = []

    def record(self, page, native_count, has_images, attempted):
        number = getattr(page, "page_no", None)
        if not isinstance(number, int) or isinstance(number, bool) or number < 1:
            with self.lock:
                self.warnings.append("OCR 阶段未提供有效的真实页码，未补造页面来源")
            return
        cells = recognized_cells(getattr(page, "cells", []) or [])
        ocr = [cell for cell in cells if getattr(cell, "from_ocr", None) is True]
        native = [cell for cell in cells if getattr(cell, "from_ocr", None) is False]
        unknown = any(not isinstance(getattr(cell, "from_ocr", None), bool) for cell in cells)
        origin = "OCR" if ocr else "TEXT_LAYER" if native and not unknown else None
        score = confidence(ocr) if ocr else None
        source = PageSource(page=number, text_origin=origin, ocr_confidence=score,
            native_char_count=native_count, has_images=has_images, ocr_attempted=attempted)
        with self.lock:
            if number in self.pages:
                raise RuntimeError("同一文档的 OCR 阶段出现重复页码")
            self.pages[number] = source
            if ocr and native:
                self.warnings.append(f"第 {number} 页同时含文本层与 OCR；页面标记为 OCR，元素来源按各自真实文本单独判断")
            if unknown:
                self.warnings.append(f"第 {number} 页部分文本缺少真实来源标记")
            if native_count is None:
                self.warnings.append(f"第 {number} 页缺少可核对的原生文本字符数，未据此启用 OCR")
            if has_images is None:
                self.warnings.append(f"第 {number} 页后端未提供真实位图信息，未据此启用 OCR")
            if ocr and score is None:
                self.warnings.append(f"第 {number} 页 OCR 未提供完整有效的置信度，未补造分数")

    def snapshot(self):
        with self.lock:
            return [self.pages[number] for number in sorted(self.pages)], list(self.warnings)


def audit_for(result, *, create=False):
    audit = getattr(result, _AUDIT_ATTRIBUTE, None)
    if audit is None and create:
        with _ATTACHMENT_LOCK:
            audit = getattr(result, _AUDIT_ATTRIBUTE, None)
            if audit is None:
                audit = DocumentOcrAudit()
                # 下划线私有状态不进入 SDK 的公开 Pydantic 字段或序列化 schema。
                object.__setattr__(result, _AUDIT_ATTRIBUTE, audit)
    return audit


def _native_chars(page):
    cells = getattr(page, "cells", None)
    if cells is None:
        return None
    nonempty = recognized_cells(cells)
    if any(not isinstance(getattr(cell, "from_ocr", None), bool) for cell in nonempty):
        return None
    return sum(len("".join(cell.text.split())) for cell in nonempty if cell.from_ocr is False)


def _has_images(page):
    backend = getattr(page, "_backend", None)
    getter = getattr(backend, "get_bitmap_rects", None)
    if not callable(getter):
        return None
    rectangles = getter()
    if rectangles is None:
        return None
    for rectangle in rectangles:
        area = getattr(rectangle, "area", None)
        value = area() if callable(area) else None
        if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0:
            return True
    return False


class SelectiveOcrModel:
    def __init__(self, factory, *, enabled=True, min_native_chars=20):
        self.factory = factory
        self.enabled = enabled
        self.min_native_chars = min_native_chars
        self.model = None
        self.model_lock = threading.Lock()
        self.execution_lock = threading.Lock()

    def _model(self):
        # 原生文本 PDF 不会初始化 OCR 引擎或提前下载 OCR 模型。
        with self.model_lock:
            if self.model is None:
                self.model = self.factory()
            return self.model

    def __call__(self, result, pages):
        audit = audit_for(result, create=True)
        for page in pages:
            native_count = _native_chars(page)
            images = _has_images(page)
            attempted = self.enabled and native_count is not None and native_count < self.min_native_chars and images is True
            if attempted:
                model = self._model()
                # SDK 超时可能仍有内部线程在执行，实际引擎调用始终独占，初始化锁保持独立。
                with self.execution_lock:
                    converted = list(model(result, [page]))
                if len(converted) != 1 or converted[0] is not page:
                    raise RuntimeError("官方 OCR 阶段没有返回对应的真实页面")
            audit.record(page, native_count, images, attempted)
            yield page


def selective_pipeline_type(settings):
    from docling.pipeline.standard_pdf_pipeline import StandardPdfPipeline

    class SelectivePdfPipeline(StandardPdfPipeline):
        def _make_ocr_model(self, art_path):
            return SelectiveOcrModel(
                lambda: super(SelectivePdfPipeline, self)._make_ocr_model(art_path),
                enabled=settings.ocr_enabled, min_native_chars=settings.ocr_min_native_chars)

    return SelectivePdfPipeline
