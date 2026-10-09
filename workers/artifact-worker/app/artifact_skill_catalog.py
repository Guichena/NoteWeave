from __future__ import annotations

import hashlib
import json
from pathlib import Path

from app.models import ArtifactSkillDefinition, ProductionAction, PromptRecipe
from app.registry import PRODUCTION_ACTIONS, PROMPT_RECIPES, SKILL_GRAPH_TEMPLATES


_ENTRY_FIELDS = {
    "skill_key", "version", "display_name", "description", "input_schema",
    "output_schema_ref", "graph_key", "prompt_recipe_id", "required_file_roles",
    "capability_allowlist", "action_key",
}
# presentation 是前端卡片的展示信息；definition 让新产物只在目录里声明动作和提示词配方
_OPTIONAL_FIELDS = {"presentation", "definition"}
_FILE_ROLES = {"PRIMARY_MARKDOWN", "PRIMARY_PDF", "PRIMARY_PPTX", "SOURCE_MD", "SLIDE_PREVIEW"}
_CATALOG_GRAPHS = {"generic_artifact_v1"}


def register_catalog_declared_skills(catalog: dict[str, object]) -> list[str]:
    """把目录中带 definition 的产物注册为生产动作和提示词配方，走通用产物流程。

    这样新增一种产物只需要在技能目录里加一个条目：Java 读取同一份目录展示和校验，
    Worker 在这里生成动作与配方，前端从接口读取展示信息。目录声明的产物不能覆盖内置动作。
    """
    registered: list[str] = []
    for entry in catalog.get("skills", []):
        if not isinstance(entry, dict) or "definition" not in entry:
            continue
        definition = entry["definition"]
        action_spec = definition.get("action") if isinstance(definition, dict) else None
        recipe_spec = definition.get("prompt_recipe") if isinstance(definition, dict) else None
        if not isinstance(action_spec, dict) or not isinstance(recipe_spec, dict):
            raise ValueError("catalog-declared Skill needs action and prompt_recipe definitions")
        action_key = str(entry["action_key"])
        recipe_id = str(entry["prompt_recipe_id"])
        existing_action = PRODUCTION_ACTIONS.get(action_key)
        if existing_action is not None and existing_action.action_origin != "CATALOG":
            raise ValueError(f"catalog-declared Skill cannot override built-in action {action_key}")
        if entry["graph_key"] not in _CATALOG_GRAPHS:
            raise ValueError("catalog-declared Skill must use the generic artifact graph")
        declared_type = str(action_spec.get("artifact_type") or action_key)
        if declared_type != action_key:
            raise ValueError("catalog-declared Skill artifact_type must equal its action_key")
        sections = [str(section) for section in action_spec.get("output_sections", [])]
        if not sections or not str(recipe_spec.get("system_intent", "")).strip():
            raise ValueError("catalog-declared Skill needs output sections and a system intent")
        PRODUCTION_ACTIONS[action_key] = ProductionAction(
            action_key=action_key,
            display_name=str(entry["display_name"]),
            # 宿主按 action_key 校验内容 IR 的 artifact_type，与内置 Skill 保持一致
            artifact_type=action_key,
            action_origin="CATALOG",
            resolver_keywords=[str(value) for value in action_spec.get("resolver_keywords", [])],
            default_style_profile_key="DEFAULT",
            default_skill_graph_key=str(entry["graph_key"]),
            default_prompt_recipe_id=recipe_id,
            required_evidence_level=str(action_spec.get("required_evidence_level") or "MEDIUM"),
            allow_writeback=True,
            allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
            supported_capabilities=list(entry["capability_allowlist"]),
            output_sections=sections,
        )
        section_guidance = recipe_spec.get("section_guidance") or {}
        PROMPT_RECIPES[recipe_id] = PromptRecipe(
            recipe_id=recipe_id,
            recipe_name=str(recipe_spec.get("recipe_name") or entry["display_name"]),
            supported_actions=[action_key],
            generation_mode=str(recipe_spec.get("generation_mode") or "STRUCTURED_SYNTHESIS"),
            system_intent=str(recipe_spec["system_intent"]),
            section_guidance={str(key): str(value) for key, value in section_guidance.items()},
            citation_policy=[str(value) for value in recipe_spec.get("citation_policy", [])],
            repair_hints=[str(value) for value in recipe_spec.get("repair_hints", [])],
            recipe_notes=["由技能目录声明的产物配方。"],
        )
        registered.append(str(entry["skill_key"]))
    return registered


def validate_published_catalog(catalog: dict[str, object]) -> None:
    if set(catalog) != {"catalog_version", "skills", "aliases"} or catalog.get("catalog_version") != 2:
        raise ValueError("unsupported artifact Skill catalog shape or version")
    entries = catalog.get("skills")
    aliases = catalog.get("aliases")
    if not isinstance(entries, list) or not isinstance(aliases, dict):
        raise ValueError("artifact Skill catalog entries or aliases are invalid")
    keys: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or not _ENTRY_FIELDS <= set(entry) \
                or not set(entry) <= _ENTRY_FIELDS | _OPTIONAL_FIELDS:
            raise ValueError("unknown or missing published Skill fields")
        if "presentation" in entry and not isinstance(entry["presentation"], dict):
            raise ValueError("published Skill presentation must be an object")
        key = entry["skill_key"]
        action = PRODUCTION_ACTIONS.get(entry["action_key"])
        if not isinstance(key, str) or not key or key in keys or action is None \
                or entry["version"] != "1.0.0":
            raise ValueError("unknown Skill key, action, or publication version")
        keys.add(key)
        if entry["graph_key"] != action.default_skill_graph_key \
                or entry["graph_key"] not in SKILL_GRAPH_TEMPLATES \
                or entry["prompt_recipe_id"] != action.default_prompt_recipe_id \
                or entry["prompt_recipe_id"] not in PROMPT_RECIPES \
                or entry["capability_allowlist"] != action.supported_capabilities:
            raise ValueError("published Skill execution policy disagrees with production action")
        roles = entry["required_file_roles"]
        if not isinstance(roles, list) or not roles or len(roles) != len(set(roles)) \
                or "PRIMARY_MARKDOWN" not in roles or not set(roles) <= _FILE_ROLES:
            raise ValueError("invalid published Skill file roles")
        schema = entry["input_schema"]
        if not isinstance(schema, dict) or not set(schema) <= {"type", "properties", "required"} \
                or schema.get("type") != "object" or not isinstance(schema.get("properties"), dict):
            raise ValueError("unsupported published Skill input schema")
        properties = schema["properties"]
        if not set(schema.get("required", [])) <= set(properties):
            raise ValueError("required Skill input is not declared")
        for field in properties.values():
            if not isinstance(field, dict) or not set(field) <= {"type", "default", "oneOf", "enum"} \
                    or field.get("type") != "string":
                raise ValueError("unsupported published Skill input field")
    if any(not isinstance(alias, str) or not isinstance(target, str) or target not in keys
           for alias, target in aliases.items()):
        raise ValueError("published Skill alias points outside the catalog")


_CATALOG_BYTES = Path(__file__).with_name("artifact-skill-catalog-v2.json").read_bytes()
CATALOG_DIGEST = hashlib.sha256(_CATALOG_BYTES).hexdigest()
_CATALOG = json.loads(_CATALOG_BYTES)
CATALOG_DECLARED_SKILLS = register_catalog_declared_skills(_CATALOG)
validate_published_catalog(_CATALOG)

_ARTIFACT_SKILLS = {
    entry["skill_key"]: ArtifactSkillDefinition(
        skill_key=entry["skill_key"],
        display_name=entry["display_name"],
        description=entry["description"],
        input_schema=entry["input_schema"],
    )
    for entry in _CATALOG["skills"]
}
if len(_ARTIFACT_SKILLS) != len(_CATALOG["skills"]):
    raise RuntimeError("duplicate artifact Skill key in catalog")

_ALIASES = _CATALOG["aliases"]
_SKILL_ACTION_BINDINGS = {
    entry["skill_key"]: entry["action_key"] for entry in _CATALOG["skills"]
}


def list_artifact_skill_definitions() -> list[ArtifactSkillDefinition]:
    return list(_ARTIFACT_SKILLS.values())


def resolve_artifact_skill_definition(skill_key: str) -> ArtifactSkillDefinition:
    canonical_skill_key = _resolve_canonical_skill_key(skill_key)
    skill = _ARTIFACT_SKILLS.get(canonical_skill_key)
    if skill is None:
        raise ValueError("unknown artifact skill")
    return skill


def try_resolve_artifact_skill_definition(skill_key: str) -> ArtifactSkillDefinition | None:
    canonical_skill_key = _resolve_canonical_skill_key(skill_key)
    if not canonical_skill_key:
        return None
    return _ARTIFACT_SKILLS.get(canonical_skill_key)


def resolve_artifact_skill_action_key(skill_key: str) -> str:
    return _SKILL_ACTION_BINDINGS.get(_resolve_canonical_skill_key(skill_key), "")


def _resolve_canonical_skill_key(value: str) -> str:
    normalized_skill_key = value.strip().lower().replace("-", "_").replace(" ", "_")
    return _ALIASES.get(normalized_skill_key, normalized_skill_key)
