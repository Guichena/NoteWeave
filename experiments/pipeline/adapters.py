"""真实解析入口。模块导入、准备检查和单元测试不会启动模型。"""
from __future__ import annotations

import hashlib
import http.client
import json
import math
import os
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
from urllib.parse import urlencode, urlsplit
import zipfile

try:
    from .contracts import MAX_INPUT_BYTES, MAX_JSON_BYTES, ContractError, document, element, fields, integer, loads, read_bytes, string
    from .tables import table_cells, visible_table_text
except ImportError:
    from contracts import MAX_INPUT_BYTES, MAX_JSON_BYTES, ContractError, document, element, fields, integer, loads, read_bytes, string
    from tables import table_cells, visible_table_text


class AdapterError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def normalize_docling(sample, payload):
    fields(payload, ("schema_version", "workspace_id", "source_id", "snapshot_id", "sha256", "mime_type", "markdown", "page_count", "elements", "parser", "truncated", "warnings"), ("page_sources",))
    if type(payload["schema_version"]) is not int or payload["schema_version"] != 1 or payload["parser"] != "DOCLING":
        raise ContractError("Docling 响应协议无效")
    if payload["sha256"] != sample["sha256"] or payload["mime_type"] != sample["mime_type"]:
        raise ContractError("Docling 响应摘要或 MIME 不符")
    expected = {"workspace_id": "pipeline-eval", "source_id": sample["sample_id"], "snapshot_id": sample["sample_id"]}
    if any(payload[key] != value for key, value in expected.items()):
        raise ContractError("Docling 响应身份不符")
    if (not isinstance(payload["elements"], list) or len(payload["elements"]) > 100_000 or type(payload["truncated"]) is not bool
            or not isinstance(payload["warnings"], list)):
        raise ContractError("Docling 元素数量或截断标记无效")
    string(payload["markdown"], 20_000_000)
    page_sources = payload.get("page_sources")
    if page_sources is not None:
        if not isinstance(page_sources, list) or len(page_sources) > 100_000:
            raise ContractError("Docling 按页来源记录无效")
        seen = set()
        for record in page_sources:
            fields(record, ("page", "text_origin", "ocr_confidence", "native_char_count", "has_images", "ocr_attempted"))
            integer(record["page"], 1)
            if record["native_char_count"] is not None:
                integer(record["native_char_count"], 0, 20_000_000)
            if record["page"] in seen or (payload["page_count"] and record["page"] > payload["page_count"]):
                raise ContractError("Docling 按页来源记录重复或越界")
            seen.add(record["page"])
            origin, confidence = record["text_origin"], record["ocr_confidence"]
            if (origin not in {None, "TEXT_LAYER", "OCR"} or (record["has_images"] is not None and type(record["has_images"]) is not bool)
                    or type(record["ocr_attempted"]) is not bool):
                raise ContractError("Docling 按页来源字段无效")
            if confidence is not None and (origin != "OCR" or type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1):
                raise ContractError("Docling 按页 OCR 置信度无效")
    elements, warnings = [], list(payload["warnings"])
    for item in payload["elements"]:
        fields(item, ("element_type", "text", "page", "bbox", "heading_level"), ("text_origin", "ocr_confidence"))
        string(item["text"], 20_000_000, empty=True)
        cells = table_cells(item["text"]) if item["element_type"] == "TABLE" else None
        text = visible_table_text(item["text"], cells) if item["element_type"] == "TABLE" else item["text"]
        elements.append(element(item["element_type"], text, page=item["page"], bbox=item["bbox"], heading=item["heading_level"],
            origin=item.get("text_origin"), confidence=item.get("ocr_confidence"), cells=cells))
    return document(sample, "DOCLING", elements, pages=payload["page_count"], complete=not payload["truncated"], warnings=warnings)


class DoclingHttpAdapter:
    def __init__(self, endpoint="http://localhost:18103", token=None, timeout=180):
        self.endpoint, self.token, self.timeout = endpoint, token, timeout

    def unavailable_reason(self):
        return None if self.token else "DOCLING_TOKEN_MISSING"

    def parse(self, sample, path, raw_output):
        if not self.token:
            raise AdapterError("DOCLING_TOKEN_MISSING", "Docling 专用令牌未配置")
        endpoint = urlsplit(self.endpoint)
        if endpoint.scheme not in {"http", "https"} or not endpoint.hostname or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment:
            raise AdapterError("DOCLING_ENDPOINT_INVALID", "Docling 地址无效")
        size = path.stat().st_size
        if not 0 < size <= MAX_INPUT_BYTES:
            raise AdapterError("INPUT_SIZE_LIMIT", "输入文件大小无效")
        query = urlencode({"workspace_id": "pipeline-eval", "source_id": sample["sample_id"], "snapshot_id": sample["sample_id"],
            "file_name": path.name, "mime_type": sample["mime_type"], "sha256": sample["sha256"], "max_chars": 20_000_000})
        connection_class = http.client.HTTPSConnection if endpoint.scheme == "https" else http.client.HTTPConnection
        connection = connection_class(endpoint.hostname, endpoint.port, timeout=self.timeout)
        try:
            with path.open("rb") as stream:
                connection.request("POST", endpoint.path.rstrip("/") + "/internal/parse?" + query, body=stream,
                    headers={"Content-Type": "application/octet-stream", "Content-Length": str(size), "X-NoteWeave-Internal-Token": self.token})
            response = connection.getresponse()
            data = response.read(MAX_JSON_BYTES + 1)
            if len(data) > MAX_JSON_BYTES:
                raise AdapterError("DOCLING_RESPONSE_LIMIT", "Docling 响应超过读取上限")
            raw_output.write_bytes(data)
            if response.status != 200:
                raise AdapterError("DOCLING_HTTP_" + str(response.status), "Docling 返回错误状态")
            return normalize_docling(sample, loads(data))
        finally:
            connection.close()


_MINERU_TYPES = {
    "text": "TEXT", "paragraph": "TEXT", "ref_text": "TEXT", "title": "SECTION_HEADER", "paragraph_title": "SECTION_HEADER",
    "doc_title": "TITLE", "table": "TABLE", "equation": "FORMULA", "interline_equation": "FORMULA", "code": "CODE",
    "list": "LIST_ITEM", "index": "LIST_ITEM", "image": "PICTURE", "chart": "PICTURE", "caption": "CAPTION",
    "footnote": "FOOTNOTE", "page_footnote": "FOOTNOTE", "header": "FOOTNOTE", "footer": "FOOTNOTE", "page_number": "FOOTNOTE",
    "aside_text": "TEXT", "list_item": "LIST_ITEM",
}


def _mineru_bbox(value):
    if value is None:
        return None
    if not isinstance(value, list) or len(value) != 4 or any(type(number) not in (int, float) or not math.isfinite(number) for number in value):
        raise ContractError("MinerU 坐标无效")
    # 只保留源坐标，不猜测坐标单位或方向，不用这些坐标计算跨解析器 IoU。
    return dict(zip(("left", "top", "right", "bottom"), value), coord_origin="UNKNOWN")


def _source_text(value, depth=0):
    if depth > 64:
        raise ContractError("MinerU 内联结构嵌套过深")
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(_source_text(item, depth + 1) for item in value)
    if (isinstance(value, dict) and value.get("type") in {"text", "ref_text", "list", "code_inline", "equation_inline", "hyperlink"}
            and "content" in value):
        return _source_text(value["content"], depth + 1)
    raise ContractError("MinerU 原始内联结构尚不支持，不能猜测纯文本")


def _source_body(block):
    if block["type"] in {"table", "code", "image", "chart"}:
        content = block.get("content")
        if not isinstance(content, list):
            raise ContractError("MinerU 原始视觉结构无效")
        bodies = [child for child in content if isinstance(child, dict) and child.get("type") == block["type"] + "_body"]
        if block["type"] in {"image", "chart"}:
            return ""
        if len(bodies) != 1:
            raise ContractError("MinerU 原始表格或代码正文有歧义")
        return _source_text(bodies[0].get("content"))
    return _source_text(block.get("content"))


def _source_annotations(block, suffix):
    content = block.get("content", [])
    if not isinstance(content, list):
        return []
    children = [(position, child) for position, child in enumerate(content)
        if isinstance(child, dict) and str(child.get("type", "")).endswith("_" + suffix)]
    for _, child in children:
        if child.get("index") is not None:
            integer(child["index"])
    return [child for _, child in sorted(children, key=lambda pair: (pair[1].get("index") is None,
        pair[1].get("index") or 0, pair[0]))]


def normalize_mineru(sample, payload, middle=None):
    if not isinstance(payload, dict) or "pages" not in payload or "metadata" not in payload or payload.get("is_full_document") is not True:
        raise ContractError("MinerU 没有返回完整结构产物")
    producer = payload["metadata"].get("producer", {}) if isinstance(payload["metadata"], dict) else {}
    version = producer.get("version")
    if producer.get("name", "").lower() != "mineru" or not isinstance(version, str) or not version.startswith("4."):
        raise ContractError("需要带真实 MinerU 4 生产版本的结构产物")
    pages = payload["pages"]
    if not isinstance(pages, list) or not pages or len(pages) > 100_000:
        raise ContractError("MinerU 页列表无效")
    if middle is not None:
        if (not isinstance(middle, dict) or middle.get("schema") != "docvortex.middle" or middle.get("schema_version") != "2.0"
                or not isinstance(middle.get("pages"), list) or len(middle["pages"]) != len(pages)):
            raise ContractError("MinerU 原始中间协议或页数不符")
    elements, indexes, warnings = [], [], []
    for page_position, page in enumerate(pages):
        if not isinstance(page, dict) or not isinstance(page.get("blocks"), list):
            raise ContractError("MinerU 页块结构无效")
        integer(page.get("page_idx"), 0, 999_999)
        index = page["page_idx"]
        if indexes and index <= indexes[-1]:
            raise ContractError("MinerU 页码重复或顺序错误")
        indexes.append(index)
        source_page = middle["pages"][page_position] if middle is not None else None
        if source_page is not None and (source_page.get("page_idx") != index or len(source_page.get("blocks", [])) != len(page["blocks"])):
            raise ContractError("MinerU 原始页和渲染页没有对齐")
        for block_position, block in enumerate(page["blocks"]):
            if not isinstance(block, dict) or block.get("type") not in _MINERU_TYPES or not isinstance(block.get("content", ""), str):
                raise ContractError("MinerU 元素类型或内容协议尚不支持")
            kind, content = _MINERU_TYPES[block["type"]], block.get("content", "")
            source_block = source_page["blocks"][block_position] if source_page is not None else None
            if source_block is not None:
                if not isinstance(source_block, dict) or source_block.get("type") != block["type"]:
                    raise ContractError("MinerU 原始元素类型与渲染结果不符")
                content = _source_body(source_block)
            cells = table_cells(content) if kind == "TABLE" else None
            text = visible_table_text(content, cells) if kind == "TABLE" else content
            level = block.get("level", block.get("text_level"))
            if level == 0:
                level = None
            if kind not in {"TITLE", "SECTION_HEADER"}:
                level = None
            elements.append(element(kind, text, page=index + 1, bbox=_mineru_bbox(block.get("bbox")), heading=level,
                origin=block.get("text_origin"), confidence=block.get("ocr_confidence"), cells=cells))
            # 当前 Structured Content 的视觉说明没有源 index，保留文字与位置，明确顺序未知。
            for field, child_kind in (("captions", "CAPTION"), ("footnotes", "FOOTNOTE")):
                children = block.get(field, [])
                if not isinstance(children, list):
                    raise ContractError("MinerU 视觉说明结构无效")
                source_children = _source_annotations(source_block, "caption" if child_kind == "CAPTION" else "footnote") if source_block is not None else None
                if source_children is not None and len(source_children) != len(children):
                    raise ContractError("MinerU 原始说明和渲染说明没有对齐")
                for child_position, child in enumerate(children):
                    if not isinstance(child, dict) or not isinstance(child.get("content"), str):
                        raise ContractError("MinerU 视觉说明文本无效")
                    child_text = _source_text(source_children[child_position].get("content")) if source_children is not None else child["content"]
                    elements.append(element(child_kind, child_text, page=index + 1,
                        bbox=_mineru_bbox(child.get("bbox")), order_known=False))
                    if "VISUAL_ANNOTATION_ORDER_UNKNOWN" not in warnings:
                        warnings.append("VISUAL_ANNOTATION_ORDER_UNKNOWN")
            if len(elements) > 100_000:
                raise ContractError("MinerU 元素数量超出上限")
    return document(sample, "MINERU", elements, version=version, pages=max(indexes) + 1, warnings=warnings)


def read_mineru_zip(path, *, include_middle=False):
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise ContractError("MinerU 输出压缩包过大")
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 10_000:
            raise ContractError("MinerU 压缩包成员过多")
        selected, middle_entries = [], []
        for entry in entries:
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in entry.filename:
                raise ContractError("MinerU 压缩包成员路径无效")
            if name.name == "structured_content.json":
                selected.append(entry)
            if name.name == "middle_json.json":
                middle_entries.append(entry)
        if len(selected) != 1 or selected[0].file_size > MAX_JSON_BYTES:
            raise ContractError("MinerU 结构产物缺失、歧义或过大")
        with archive.open(selected[0]) as stream:
            data = stream.read(MAX_JSON_BYTES + 1)
        if len(data) > MAX_JSON_BYTES:
            raise ContractError("MinerU 解压后的结构产物过大")
        structured = loads(data)
        if not include_middle:
            return structured
        if len(middle_entries) != 1 or middle_entries[0].file_size > MAX_JSON_BYTES:
            raise ContractError("MinerU 原始中间产物缺失、歧义或过大")
        with archive.open(middle_entries[0]) as stream:
            raw_middle = stream.read(MAX_JSON_BYTES + 1)
        if len(raw_middle) > MAX_JSON_BYTES:
            raise ContractError("MinerU 原始中间产物超出读取上限")
        return structured, loads(raw_middle)


class MineruCliAdapter:
    def __init__(self, executable="mineru-kit", timeout=1800, tier="standard"):
        if tier not in {"flash", "standard"}:
            raise AdapterError("MINERU_TIER_INVALID", "MinerU tier 无效")
        self.executable, self.timeout, self.tier = executable, timeout, tier

    def unavailable_reason(self):
        import shutil
        return None if shutil.which(self.executable) else "MINERU_EXECUTABLE_MISSING"

    def parse(self, sample, path, raw_output):
        # 输出和进程日志只写本次目录；不使用 shell，不安装模型，也不自动启动远端服务。
        with tempfile.TemporaryDirectory(prefix="mineru-pipeline-") as directory:
            output = Path(directory) / "result.zip"
            with (Path(directory) / "stdout.log").open("wb") as stdout, (Path(directory) / "stderr.log").open("wb") as stderr:
                try:
                    result = subprocess.run([self.executable, "parse", str(path), "-o", str(output), "--format", "zip",
                        "--tier", self.tier, "--pages", "all"], stdout=stdout, stderr=stderr, timeout=self.timeout, check=False)
                except FileNotFoundError as exc:
                    raise AdapterError("MINERU_EXECUTABLE_MISSING", "找不到 MinerU 命令") from exc
                except subprocess.TimeoutExpired as exc:
                    raise AdapterError("MINERU_TIMEOUT", "MinerU 解析超时") from exc
            if result.returncode:
                raise AdapterError("MINERU_EXIT_FAILED", "MinerU 进程返回失败状态")
            if not output.is_file():
                raise AdapterError("MINERU_OUTPUT_MISSING", "MinerU 没有生成压缩包")
            if output.stat().st_size > MAX_INPUT_BYTES:
                raise AdapterError("MINERU_OUTPUT_LIMIT", "MinerU 输出压缩包过大")
            # 仅复制原始 ZIP 留证，不向文件系统解压任何成员。
            import shutil
            shutil.copyfile(output, raw_output)
        structured, middle = read_mineru_zip(raw_output, include_middle=True)
        return normalize_mineru(sample, structured, middle)
