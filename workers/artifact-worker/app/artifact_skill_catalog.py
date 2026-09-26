from __future__ import annotations

import hashlib
import json
from pathlib import Path

from app.models import ArtifactSkillDefinition


_CATALOG_BYTES = Path(__file__).with_name("artifact-skill-catalog-v2.json").read_bytes()
CATALOG_DIGEST = hashlib.sha256(_CATALOG_BYTES).hexdigest()
_CATALOG = json.loads(_CATALOG_BYTES)
if _CATALOG.get("catalog_version") != 2:
    raise RuntimeError("unsupported artifact Skill catalog version")

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
