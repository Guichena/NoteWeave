from __future__ import annotations

import hashlib
import json
from pathlib import Path

from app.models import ArtifactSkillDefinition
from app.registry import PRODUCTION_ACTIONS, PROMPT_RECIPES, SKILL_GRAPH_TEMPLATES


_ENTRY_FIELDS = {
    "skill_key", "version", "display_name", "description", "input_schema",
    "output_schema_ref", "graph_key", "prompt_recipe_id", "required_file_roles",
    "capability_allowlist", "action_key",
}
_FILE_ROLES = {"PRIMARY_MARKDOWN", "PRIMARY_PDF", "PRIMARY_PPTX", "SOURCE_MD", "SLIDE_PREVIEW"}


def validate_published_catalog(catalog: dict[str, object]) -> None:
    if set(catalog) != {"catalog_version", "skills", "aliases"} or catalog.get("catalog_version") != 2:
        raise ValueError("unsupported artifact Skill catalog shape or version")
    entries = catalog.get("skills")
    aliases = catalog.get("aliases")
    if not isinstance(entries, list) or not isinstance(aliases, dict):
        raise ValueError("artifact Skill catalog entries or aliases are invalid")
    keys: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != _ENTRY_FIELDS:
            raise ValueError("unknown or missing published Skill fields")
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
