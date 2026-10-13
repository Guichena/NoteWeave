"""节点发布、状态边界和旧图迁移的行为测试。"""

from copy import deepcopy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.artifact_skill_catalog import list_artifact_skill_definitions, validate_published_catalog
from app.compiler import build_execution_plan
from app.models import ArtifactSkillNodePlan, ArtifactTaskInput, SkillGraphNode
from app.node_contracts import hydrate_legacy_node, node_contract_for_skill, validate_node_contract
from app.registry import (
    PRODUCTION_ACTIONS, register_custom_skill_definition, reset_custom_skill_definitions,
    resolve_skill_definition,
)
from app.runtime_node_registry import ensure_runtime_node_contract_registered


def _catalog():
    root = Path(__file__).resolve().parents[3]
    return json.loads((root / "reference/artifact-skill-catalog-v2.json").read_text(encoding="utf-8"))


def _legacy_node(skill="resume_highlight_extractor"):
    return {"node_id": "existing-node", "skill_key": skill, "purpose": "抽取有证据的事实"}


def test_old_checkpoint_node_receives_explicit_contract_without_changing_identity():
    node = ArtifactSkillNodePlan.model_validate(_legacy_node())
    assert node.node_id == "existing-node"
    assert node.node_kind == "LLM_STEP"
    assert node.prompt_recipe_id == "node.resume_highlight_extractor.v1"
    assert set(node.output_schema["properties"]) == {"facts"}
    assert set(node.input_schema["properties"]) == {"generation_context", "material_passages"}


@pytest.mark.parametrize("schema_field,state_field", [
    ("output_schema", "sections"),
    ("input_schema", "material_passages"),
])
def test_node_contract_rejects_weakening_registered_nested_field_schema(schema_field, state_field):
    node = node_contract_for_skill("generic_section_writer")
    node[schema_field]["properties"][state_field] = {"type": "array"}
    with pytest.raises(ValueError, match="基础 Schema 不允许削弱"):
        validate_node_contract(node)


@pytest.mark.parametrize("schema_field,state_field", [
    ("output_schema", "sections"),
    ("input_schema", "material_passages"),
])
def test_node_contract_rejects_removing_nested_required_fields(schema_field, state_field):
    node = node_contract_for_skill("generic_section_writer")
    node[schema_field]["properties"][state_field]["items"]["required"] = []
    with pytest.raises(ValueError, match="基础 Schema 不允许削弱"):
        validate_node_contract(node)


@pytest.mark.parametrize("schema_field,state_field", [
    ("output_schema", "sections"),
    ("input_schema", "material_passages"),
])
def test_node_contract_allows_extra_constraints_with_complete_base_schema(schema_field, state_field):
    node = node_contract_for_skill("generic_section_writer")
    base_schema = deepcopy(node[schema_field]["properties"][state_field])
    node[schema_field]["properties"][state_field] = {"allOf": [base_schema, {"maxItems": 1}]}
    validate_node_contract(node)
    # 定制的约束与原有必填字段同时生效，不能接受结构不完整的列表项。
    from jsonschema import Draft202012Validator
    validator = Draft202012Validator(node[schema_field]["properties"][state_field])
    assert list(validator.iter_errors([{}]))
    if state_field == "sections":
        valid_item = {"heading": "章节", "body": "正文", "source_refs": []}
    else:
        valid_item = {"chunk_id": "chunk-1", "title": "资料", "content": "原文"}
    assert not list(validator.iter_errors([valid_item]))
    assert list(validator.iter_errors([valid_item, valid_item]))


def test_legacy_custom_alias_uses_registered_executor_contract():
    reset_custom_skill_definitions()
    try:
        register_custom_skill_definition({"skill_key": "custom_facts_4c", "base_skill_key": "resume_highlight_extractor"})
        node = SkillGraphNode.model_validate(_legacy_node("custom_facts_4c"))
        assert node.skill_key == "custom_facts_4c"
        assert node.prompt_recipe_id == "node.resume_highlight_extractor.v1"
        ensure_runtime_node_contract_registered(node, resolve_skill_definition(node.skill_key), error_prefix="测试")
    finally:
        reset_custom_skill_definitions()


@pytest.mark.parametrize("mutation", [
    lambda node: node.update({"node_kind": "LLM_STEP"}),
    lambda node: node.update({"skill_key": "run_unknown_tool"}),
    lambda node: node.update({"node_kind": "TOOL", **{k: v for k, v in node_contract_for_skill("resume_highlight_extractor").items() if k != "node_kind"}}),
    lambda node: node.update({**node_contract_for_skill("resume_highlight_extractor"), "prompt_recipe_id": "node.unregistered.v1"}),
])
def test_unknown_or_partial_legacy_node_is_rejected(mutation):
    node = _legacy_node()
    mutation(node)
    with pytest.raises(ValueError):
        hydrate_legacy_node(node)


@pytest.mark.parametrize("mutation", [
    lambda graph: graph["nodes"][0].pop("node_kind"),
    lambda graph: graph["nodes"][1]["input_schema"]["properties"].update({"host_permissions": {"type": "object"}}),
    lambda graph: graph["nodes"][1]["output_schema"].update({"additionalProperties": True}),
    lambda graph: graph["nodes"][1]["input_schema"]["properties"]["material_passages"].update({"$ref": "https://invalid.example/schema"}),
    lambda graph: graph["nodes"][1].update({"node_kind": "TOOL"}),
    lambda graph: graph["edges"].append({"from_node": "repair", "to_node": "material_retrieve", "edge_type": "PREREQUISITE"}),
    lambda graph: graph["edges"].append(deepcopy(graph["edges"][0])),
])
def test_publication_rejects_unbounded_nodes_and_invalid_dependencies(mutation):
    catalog = _catalog()
    mutation(catalog["skills"][0]["graph_definition"])
    with pytest.raises((ValueError, ValidationError)):
        validate_published_catalog(catalog)


def test_shared_graph_publication_requires_identical_definitions():
    catalog = _catalog()
    shared = [entry for entry in catalog["skills"] if entry["graph_key"] == "generic_artifact_v1"]
    shared[1]["graph_definition"]["graph_name"] = "其他图"
    with pytest.raises(ValueError, match="共享技能图"):
        validate_published_catalog(catalog)


def test_public_skill_metadata_excludes_graph_prompts_and_contracts():
    metadata = json.dumps([skill.model_dump(mode="json") for skill in list_artifact_skill_definitions()])
    assert "graph_definition" not in metadata
    assert "prompt_recipe" not in metadata
    assert "node_kind" not in metadata
    assert "output_contract" not in metadata


def test_resume_plan_uses_fact_pipeline_without_project_specific_required_phrases():
    task = ArtifactTaskInput.model_validate({
        "task_id": "fact-plan", "workspace_id": "workspace", "target_id": "target",
        "input_snapshot_id": "snapshot", "source_scope": [{"source_id": "source", "title": "项目", "sample_text": "优化数据处理耗时20%。"}],
        "control_pack": {"pack_type": "artifact", "target_key": "resume_highlight", "task_neighborhood": "ARTIFACT_SKILL_RESUME_HIGHLIGHT"},
        "input_payload": {"skill_key": "resume_highlight", "user_requirement": "提炼项目贡献"},
    })
    plan = build_execution_plan(task)
    assert [node.node_id for node in plan.node_sequence] == ["material_retrieve", "fact_extractor", "normalize", "write", "verify", "repair"]
    assert [node.node_kind for node in plan.node_sequence] == ["RETRIEVE", "LLM_STEP", "LLM_STEP", "LLM_STEP", "VERIFY", "REPAIR"]
    assert not plan.required_phrases
    assert all(not action.required_phrases for action in PRODUCTION_ACTIONS.values() if action.action_origin == "BUILTIN")
