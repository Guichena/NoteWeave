from __future__ import annotations

from app.models import SkillDefinition


SUPPORTED_RUNTIME_NODE_SKILL_KEYS = frozenset(
    {
        "workspace_material_digest",
        "resume_highlight_extractor",
        "resume_impact_normalizer",
        "resume_bullet_writer",
        "resume_verifier",
        "resume_local_repair",
        "quiz_designer",
        "quiz_difficulty_normalizer",
        "wiki_structure_enforcer",
        "generic_section_writer",
        "evidence_guard",
        "style_polisher",
    }
)


def resolve_runtime_node_skill_key(skill_definition: SkillDefinition) -> str:
    return skill_definition.template_skill_key or skill_definition.skill_key


def ensure_runtime_node_skill_registered(
    skill_definition: SkillDefinition,
    *,
    graph_key: str = "",
    node_skill_key: str = "",
    error_prefix: str,
) -> str:
    runtime_skill_key = resolve_runtime_node_skill_key(skill_definition)
    if runtime_skill_key in SUPPORTED_RUNTIME_NODE_SKILL_KEYS:
        return runtime_skill_key
    graph_suffix = f" graph={graph_key}" if graph_key else ""
    node_suffix = (
        f" node_skill={node_skill_key or skill_definition.skill_key}"
        if (node_skill_key or skill_definition.skill_key)
        else ""
    )
    raise ValueError(
        f"{error_prefix}: graph node references unregistered runtime skill"
        f"{graph_suffix}{node_suffix} runtime_skill={runtime_skill_key}"
    )
