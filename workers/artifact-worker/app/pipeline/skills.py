"""从同一目录读取 Skill 头部与生成指令。"""

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re

import yaml

from app.pipeline.schemas import ROOT, load_schema, schema_directory
from app.pipeline.rules import validate_contract


class _HeaderLoader(yaml.SafeLoader):
    """与 Host 一致，拒绝 YAML 头部中的重复字段。"""


def _unique_mapping(loader, node):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node)
        if key in result:
            raise ValueError(f"Skill YAML 字段重复：{key}")
        result[key] = loader.construct_object(value_node)
    return result


_HeaderLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _unique_mapping)


@dataclass(frozen=True)
class Skill:
    name: str
    description: str
    kind: str
    version: int
    contract: dict
    options: dict
    renderers: list
    aliases: list
    path: Path

    def instructions(self):
        return self.path.read_text(encoding="utf-8").split("---", 2)[2].strip()

    def normalize_options(self, values):
        if not isinstance(values, dict) or set(values) - set(self.options):
            raise ValueError("Skill 选项包含未声明的字段")
        result = {}
        for key, option in self.options.items():
            value = values.get(key, option.get("default"))
            if value is not None:
                if not isinstance(value, str) or value not in option["choices"]:
                    raise ValueError(f"Skill 选项取值无效：{key}")
                result[key] = value
        return result


class SkillCatalog:
    def __init__(self, directory=None, schemas=None):
        self.directory = Path(directory or os.environ.get("NOTEWEAVE_SKILLS_DIR", ROOT / "skills"))
        self.schemas = Path(schemas or schema_directory())
        self.skills = {}
        self.aliases = {}
        digest = hashlib.sha256()
        paths = sorted(self.directory.glob("*/SKILL.md"))
        if not paths:
            raise ValueError("Skill 目录为空或不存在")
        for path in paths:
            raw = path.read_bytes()
            parts = raw.decode("utf-8").split("---", 2)
            if len(parts) != 3 or parts[0].strip() or not parts[2].strip():
                raise ValueError(f"Skill 缺少 YAML 头部或生成指令：{path}")
            header = yaml.load(parts[1], Loader=_HeaderLoader)
            if not isinstance(header, dict):
                raise ValueError(f"Skill 头部必须是对象：{path}")
            name = header.get("name")
            kind = header.get("kind")
            if name != path.parent.name or not re.fullmatch(r"[a-z][a-z0-9-]*", str(name)):
                raise ValueError("Skill 名称与目录名不一致")
            if not isinstance(header.get("description"), str) or not header["description"].strip():
                raise ValueError("Skill 缺少描述")
            if type(header.get("version")) is not int or header["version"] < 1:
                raise ValueError("Skill 版本必须是正整数")
            load_schema(kind, self.schemas)
            contract = header.get("contract", {})
            validate_contract(kind, contract)
            options = header.get("options", {})
            if not isinstance(options, dict):
                raise ValueError("Skill 选项必须是对象")
            for key, option in options.items():
                if not isinstance(key, str) or not isinstance(option, dict) or set(option) - {"choices", "default"}:
                    raise ValueError("Skill 选项格式错误")
                choices = option.get("choices")
                if not isinstance(choices, list) or not choices or any(not isinstance(v, str) or not v for v in choices) \
                        or len(choices) != len(set(choices)) or option.get("default") not in choices:
                    raise ValueError("Skill 选项或默认值无效")
            renderers = header.get("renderers")
            if not isinstance(renderers, list) or not renderers or len(renderers) != len(set(renderers)) \
                    or "MARKDOWN" not in renderers or set(renderers) - ({"MARKDOWN", "CSV"} if kind == "TABLE" else {"MARKDOWN"}):
                raise ValueError("Skill 渲染器与形态不匹配")
            aliases = header.get("aliases", [])
            if not isinstance(aliases, list) or any(not isinstance(v, str) or not re.fullmatch(r"[a-z][a-z0-9_-]*", v) for v in aliases):
                raise ValueError("Skill 别名格式错误")
            self.skills[name] = Skill(name, header["description"], kind, header["version"], contract, options, renderers, aliases, path)
            for alias in aliases:
                if alias in self.aliases:
                    raise ValueError(f"Skill 别名重复：{alias}")
                self.aliases[alias] = name
            digest.update(f"skills/{name}/SKILL.md\0".encode())
            digest.update(raw.replace(b"\r\n", b"\n"))
            digest.update(b"\0")
        if set(self.skills) & set(self.aliases):
            raise ValueError("Skill 别名覆盖了正式名称")
        for path in sorted(self.schemas.glob("*.v1.json")):
            digest.update(f"schemas/{path.name}\0".encode())
            digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
            digest.update(b"\0")
        self.digest = digest.hexdigest()

    def resolve(self, name):
        name = str(name).strip().lower()
        canonical = self.aliases.get(name, name)
        if canonical not in self.skills:
            raise ValueError(f"未找到产物 Skill：{name}")
        return self.skills[canonical]
