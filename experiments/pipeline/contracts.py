"""本地严格校验和有界读取，不修改样例或标注。"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import re

MAX_JSON_BYTES = 64 * 1024 * 1024
MAX_INPUT_BYTES = 256 * 1024 * 1024
ELEMENT_TYPES = {"TITLE", "SECTION_HEADER", "TEXT", "TABLE", "CAPTION", "FORMULA", "CODE", "LIST_ITEM", "FOOTNOTE", "PICTURE"}


class ContractError(ValueError):
    pass


def _pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ContractError("JSON 字段重复: " + key)
        value[key] = item
    return value


def loads(data: bytes | str):
    if isinstance(data, bytes):
        data = data.decode("utf-8-sig")
    def reject_constant(value):
        raise ContractError("JSON 包含非有限值: " + value)
    return json.loads(data, object_pairs_hook=_pairs, parse_constant=reject_constant)


def read_bytes(path: Path, maximum=MAX_JSON_BYTES):
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ContractError("文件超过读取上限")
    return data


def file_sha256(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def fields(value, required, optional=()):
    if not isinstance(value, dict) or set(value) - set(required) - set(optional) or set(required) - set(value):
        raise ContractError("对象字段缺失或包含未知字段")


def integer(value, minimum=0, maximum=1_000_000):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ContractError("整数超出范围")


def string(value, maximum=1024, *, empty=False):
    if not isinstance(value, str) or len(value) > maximum or (not empty and not value):
        raise ContractError("字符串无效")


def identifier(value, maximum=128):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1," + str(maximum) + "}", value):
        raise ContractError("标识无效")


def sha256(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        raise ContractError("摘要格式无效")


def validate_manifest(value):
    fields(value, ("schema_version", "suite_id", "expected_sample_count", "sample_root", "samples"))
    if (value["schema_version"] != 1 or type(value["schema_version"]) is not int
            or value["expected_sample_count"] != 30 or type(value["expected_sample_count"]) is not int):
        raise ContractError("样例清单版本或数量目标无效")
    identifier(value["suite_id"])
    string(value["sample_root"])
    if not isinstance(value["samples"], list) or len(value["samples"]) > 30:
        raise ContractError("最多接受 30 份样例")
    ids = set()
    for sample in value["samples"]:
        fields(sample, ("sample_id", "input_path", "sha256", "mime_type", "gold_text_path", "gold_layout_path"))
        identifier(sample["sample_id"], 64)
        if sample["sample_id"] in ids:
            raise ContractError("样例标识重复")
        ids.add(sample["sample_id"])
        string(sample["input_path"])
        string(sample["mime_type"], 255)
        if sample["sha256"] is not None:
            sha256(sample["sha256"])
        for field in ("gold_text_path", "gold_layout_path"):
            if sample[field] is not None:
                string(sample[field])
    return value


def local_path(root: Path, relative: str):
    string(relative)
    if any(character in relative for character in ("\x00", "\n", "\r", ":")) or Path(relative).is_absolute():
        raise ContractError("样例路径必须为本地相对路径")
    target = (root / relative).resolve()
    if not target.is_relative_to(root.resolve()):
        raise ContractError("样例路径超出指定根目录")
    return target


def validate_document(value):
    fields(value, ("schema_version", "sample_id", "input_sha256", "parser", "parser_version", "page_count", "complete", "elements", "warnings"))
    if type(value["schema_version"]) is not int or value["schema_version"] != 1:
        raise ContractError("结构产物版本无效")
    identifier(value["sample_id"], 64)
    sha256(value["input_sha256"])
    if value["parser"] not in {"GOLD", "DOCLING", "MINERU"}:
        raise ContractError("解析器标识无效")
    if value["parser_version"] is not None:
        string(value["parser_version"], 300)
    integer(value["page_count"])
    if type(value["complete"]) is not bool or not isinstance(value["elements"], list) or len(value["elements"]) > 100_000:
        raise ContractError("结构元素或完整标记无效")
    if not isinstance(value["warnings"], list) or len(value["warnings"]) > 1000:
        raise ContractError("告警列表无效")
    for warning in value["warnings"]:
        string(warning, 1000)
    characters = 0
    for element in value["elements"]:
        fields(element, ("element_type", "text", "page", "bbox", "heading_level", "text_origin", "ocr_confidence", "table_cells", "table_structure_known", "reading_order_known"))
        if element["element_type"] not in ELEMENT_TYPES:
            raise ContractError("结构元素类型无效")
        string(element["text"], 20_000_000, empty=True)
        characters += len(element["text"])
        if element["page"] is not None:
            integer(element["page"], 1)
            if value["page_count"] and element["page"] > value["page_count"]:
                raise ContractError("元素页码超出总页数")
        if element["heading_level"] is not None:
            integer(element["heading_level"], 1, 100)
        bbox = element["bbox"]
        if bbox is not None:
            fields(bbox, ("left", "top", "right", "bottom", "coord_origin"))
            for field in ("left", "top", "right", "bottom"):
                if type(bbox[field]) not in (int, float) or not math.isfinite(bbox[field]):
                    raise ContractError("坐标必须为有限数值")
            if bbox["coord_origin"] not in {"TOPLEFT", "BOTTOMLEFT", "UNKNOWN"} or bbox["left"] > bbox["right"]:
                raise ContractError("坐标方向或水平范围无效")
            if bbox["coord_origin"] == "TOPLEFT" and bbox["top"] > bbox["bottom"]:
                raise ContractError("垂直坐标顺序无效")
            if bbox["coord_origin"] == "BOTTOMLEFT" and bbox["top"] < bbox["bottom"]:
                raise ContractError("垂直坐标顺序无效")
        if element["text_origin"] not in {None, "TEXT_LAYER", "OCR"}:
            raise ContractError("文字来源无效")
        confidence = element["ocr_confidence"]
        if confidence is not None and (type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1):
            raise ContractError("OCR 置信度无效")
        if confidence is not None and element["text_origin"] != "OCR":
            raise ContractError("未确认来自 OCR 的文字不能附识别置信度")
        if type(element["table_structure_known"]) is not bool or type(element["reading_order_known"]) is not bool:
            raise ContractError("结构已知标记无效")
        cells = element["table_cells"]
        if element["table_structure_known"] != (cells is not None) or (cells is not None and element["element_type"] != "TABLE"):
            raise ContractError("表格结构标记与内容不一致")
        if cells is not None:
            if not isinstance(cells, list) or not 1 <= len(cells) <= 1000 or not isinstance(cells[0], list) or not 1 <= len(cells[0]) <= 1000:
                raise ContractError("表格大小无效")
            for row in cells:
                if not isinstance(row, list) or len(row) != len(cells[0]):
                    raise ContractError("表格不是矩形")
                for cell in row:
                    string(cell, 1_000_000, empty=True)
                    characters += len(cell)
    if characters > 40_000_000:
        raise ContractError("结构产物字符总量超出上限")
    return value


def document(sample, parser, elements, *, version=None, pages=0, complete=True, warnings=()):
    return validate_document({"schema_version": 1, "sample_id": sample["sample_id"], "input_sha256": sample["sha256"],
        "parser": parser, "parser_version": version, "page_count": pages, "complete": complete,
        "elements": elements, "warnings": list(warnings)})


def element(kind, text, *, page=None, bbox=None, heading=None, origin=None, confidence=None, cells=None, order_known=True):
    return {"element_type": kind, "text": text, "page": page, "bbox": bbox, "heading_level": heading,
        "text_origin": origin, "ocr_confidence": confidence, "table_cells": cells,
        "table_structure_known": cells is not None, "reading_order_known": order_known}
