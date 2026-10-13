from __future__ import annotations

from app.models import SkillDefinition
from app.node_contracts import node_contract_for_skill, validate_node_contract


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


def ensure_runtime_node_contract_registered(node, skill_definition, *, error_prefix):
    """按真实执行技能校验节点类型和配方，保留自定义技能别名。"""
    runtime_key = ensure_runtime_node_skill_registered(
        skill_definition, node_skill_key=node.skill_key, error_prefix=error_prefix)
    expected = node_contract_for_skill(node.skill_key, runtime_key)
    declared = node.model_dump(mode="json")
    validate_node_contract(declared)
    if node.node_kind != expected["node_kind"] or node.prompt_recipe_id != expected["prompt_recipe_id"]:
        raise ValueError(f"{error_prefix}: 节点类型或配方与注册执行技能不一致")
    for field in ("input_schema", "output_schema"):
        if set(declared[field]["properties"]) != set(expected[field]["properties"]) \
                or not set(expected[field]["required"]) <= set(declared[field].get("required", [])):
            raise ValueError(f"{error_prefix}: 节点状态读写字段与注册执行技能不一致")
    return runtime_key
