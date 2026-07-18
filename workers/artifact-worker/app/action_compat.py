from __future__ import annotations

from dataclasses import dataclass

from app.artifact_skill_catalog import resolve_artifact_skill_action_key
from app.models import ArtifactTaskInput


@dataclass(frozen=True)
class ArtifactActionCompatibility:
    skill_key: str
    requested_action_key: str
    explicit_requested_action_key: str
    resolved_from_skill: str
    effective_action_key: str
    legacy_action_conflict: bool


def resolve_action_compatibility(task_input: ArtifactTaskInput) -> ArtifactActionCompatibility:
    skill_key = normalize_skill_key(task_input.input_payload.skill_key)
    requested_action_key = normalize_requested_action_key(task_input.input_payload.action_key)
    explicit_requested_action_key = (
        requested_action_key if requested_action_key != "AUTO" else ""
    )
    resolved_from_skill = resolve_action_key_from_skill_key(skill_key)

    if resolved_from_skill:
        if explicit_requested_action_key and explicit_requested_action_key != resolved_from_skill:
            return ArtifactActionCompatibility(
                skill_key=skill_key,
                requested_action_key=requested_action_key,
                explicit_requested_action_key=explicit_requested_action_key,
                resolved_from_skill=resolved_from_skill,
                effective_action_key=resolved_from_skill,
                legacy_action_conflict=True,
            )
        return ArtifactActionCompatibility(
            skill_key=skill_key,
            requested_action_key=requested_action_key,
            explicit_requested_action_key=explicit_requested_action_key,
            resolved_from_skill=resolved_from_skill,
            effective_action_key=explicit_requested_action_key or resolved_from_skill,
            legacy_action_conflict=False,
        )

    return ArtifactActionCompatibility(
        skill_key=skill_key,
        requested_action_key=requested_action_key,
        explicit_requested_action_key=explicit_requested_action_key,
        resolved_from_skill="",
        effective_action_key=explicit_requested_action_key or "AUTO",
        legacy_action_conflict=False,
    )


def resolve_action_key_from_skill_key(skill_key: str) -> str:
    return resolve_artifact_skill_action_key(skill_key)


def normalize_requested_action_key(value: str) -> str:
    normalized = value.strip().upper()
    return normalized or "AUTO"


def normalize_skill_key(value: str) -> str:
    return value.strip().lower().replace("-", "_").replace(" ", "_")
