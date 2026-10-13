from __future__ import annotations

import hashlib
import json
from pathlib import Path

from app.models import ArtifactSkillDefinition, ProductionAction, PromptRecipe, SkillGraphTemplate
from app.registry import PRODUCTION_ACTIONS, PROMPT_RECIPES, SKILL_GRAPH_TEMPLATES


_ENTRY_FIELDS = {
    "skill_key", "version", "display_name", "description", "input_schema",
    "output_schema_ref", "graph_key", "prompt_recipe_id", "required_file_roles",
    "capability_allowlist", "action_key", "output_contract", "graph_definition",
}
# presentation 是前端卡片的展示信息；definition 让新产物只在目录里声明动作和提示词配方
_OPTIONAL_FIELDS = {"presentation", "definition"}
_FILE_ROLES = {"PRIMARY_MARKDOWN", "PRIMARY_PDF", "PRIMARY_PPTX", "SOURCE_MD", "SLIDE_PREVIEW"}
_CATALOG_GRAPHS = {"generic_artifact_v1"}


def _published_graph(entry):
    from app.node_contracts import node_contract_for_skill
    from app.registry import _validate_skill_graph_template
    raw = entry.get("graph_definition")
    if not isinstance(raw, dict) or set(raw) != {
            "graph_key", "graph_name", "action_type", "nodes", "edges", "schema_contract"}:
        raise ValueError("发布目录必须包含完整的技能图声明")
    if raw["graph_key"] != entry.get("graph_key") or not isinstance(raw["nodes"], list) \
            or not 1 <= len(raw["nodes"]) <= 40 or not isinstance(raw["edges"], list) \
            or len(raw["edges"]) > 160:
        raise ValueError("发布技能图标识或节点数量无效")
    for node in raw["nodes"]:
        if not isinstance(node, dict) or not {
                "node_kind", "prompt_recipe_id", "input_schema", "output_schema"} <= set(node):
            raise ValueError("发布技能图节点缺少类型或输入输出 Schema")
    graph = SkillGraphTemplate.model_validate(raw)
    if any(edge.edge_type != "PREREQUISITE" for edge in graph.edges):
        raise ValueError("发布技能图仅支持明确的先决依赖")
    node_ids = {node.node_id for node in graph.nodes}
    if len(node_ids) != len(graph.nodes) or any(
            edge.from_node not in node_ids or edge.to_node not in node_ids for edge in graph.edges):
        raise ValueError("发布技能图节点或依赖无效")
    edge_ids = {(edge.from_node, edge.to_node) for edge in graph.edges}
    if len(edge_ids) != len(graph.edges):
        raise ValueError("发布技能图包含重复依赖")
    remaining = set(node_ids)
    while remaining:
        ready = {node for node in remaining if not any(
            target == node and source in remaining for source, target in edge_ids)}
        if not ready:
            raise ValueError("发布技能图存在依赖环")
        remaining -= ready
    # 专用视频工具继续由冻结入口执行，仅允许登记过的工具声明。
    if any(node.node_kind == "TOOL" for node in graph.nodes):
        if graph.graph_key not in {"video_deck_v1", "video_derived_text_v1"}:
            raise ValueError("通用产物图不能声明专用视频工具")
        for node in graph.nodes:
            expected = node_contract_for_skill(node.skill_key)
            if any(node.model_dump(mode="json")[field] != expected[field] for field in expected):
                raise ValueError("专用视频节点与冻结执行契约不一致")
    else:
        _validate_skill_graph_template(graph)
    return graph


def register_catalog_graph_definitions(catalog):
    """按目录加载执行图；共享图的重复声明必须完全一致。"""
    graphs = {}
    for entry in catalog.get("skills", []):
        graph = _published_graph(entry)
        existing = graphs.get(graph.graph_key)
        if existing is not None and existing.model_dump(mode="json") != graph.model_dump(mode="json"):
            raise ValueError("发布目录中共享技能图的声明不一致")
        graphs[graph.graph_key] = graph
    SKILL_GRAPH_TEMPLATES.update(graphs)
    return list(graphs)


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
    graphs = {}
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
        graph = _published_graph(entry)
        graph_payload = graph.model_dump(mode="json")
        if graph.graph_key in graphs and graphs[graph.graph_key] != graph_payload:
            raise ValueError("发布目录中共享技能图的声明不一致")
        graphs[graph.graph_key] = graph_payload
        if entry["graph_key"] != action.default_skill_graph_key \
                or entry["graph_key"] not in SKILL_GRAPH_TEMPLATES \
                or entry["prompt_recipe_id"] != action.default_prompt_recipe_id \
                or entry["prompt_recipe_id"] not in PROMPT_RECIPES \
                or entry["capability_allowlist"] != action.supported_capabilities:
            raise ValueError("published Skill execution policy disagrees with production action")
        from app.output_contract import validate_output_contract
        validate_output_contract(entry["output_contract"], {n.node_id for n in SKILL_GRAPH_TEMPLATES[entry["graph_key"]].nodes})
        if entry["output_schema_ref"] != f"artifact-{key}-output.v1":
            raise ValueError("产物输出契约引用与 Skill 不一致")
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
CATALOG_DECLARED_GRAPHS = register_catalog_graph_definitions(_CATALOG)
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


def resolve_output_contract(skill_key, action_key):
    """执行时加载契约，公开 Skill 列表不携带执行策略。"""
    from copy import deepcopy
    canonical = _resolve_canonical_skill_key(skill_key)
    entry = next((e for e in _CATALOG["skills"] if e["skill_key"] == canonical), None)
    if entry is None:
        entry = next((e for e in _CATALOG["skills"] if e["action_key"] == action_key), None)
    return deepcopy(entry["output_contract"]) if entry else {}
