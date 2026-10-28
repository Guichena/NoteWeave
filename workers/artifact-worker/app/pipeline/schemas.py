"""共享 JSON Schema 的读取与校验。"""

import json
import os
from pathlib import Path

from jsonschema import Draft202012Validator


# 本地从 Worker 目录推算仓库根目录；容器使用显式配置，逐级 parent 也能处理较浅的容器路径。
ROOT = Path(__file__).resolve().parents[2].parent.parent


def schema_directory():
    return Path(os.environ.get("NOTEWEAVE_SCHEMAS_DIR", ROOT / "schemas"))


def load_schema(kind, directory=None):
    if kind not in {"DOC", "QUIZ", "CARDS", "TREE", "TABLE", "DECK"}:
        raise ValueError(f"不支持的产物形态：{kind}")
    schema = json.loads(((directory or schema_directory()) / f"{kind.lower()}.v1.json").read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    if schema["properties"]["kind"] != {"const": kind}:
        raise ValueError("Schema 的 kind 与文件名不一致")
    return schema


def schema_errors(value, schema):
    return [f"{'/'.join(map(str, error.absolute_path))}: {error.message}"
            for error in Draft202012Validator(schema).iter_errors(value)]


def item_schema(kind, schema):
    key = {"DOC": "sections", "QUIZ": "questions", "CARDS": "cards", "TABLE": "rows", "DECK": "slides"}.get(kind)
    item = schema["$defs"]["node"] if kind == "TREE" else schema["properties"][key]["items"]
    return {**item, "$defs": schema.get("$defs", {})}
