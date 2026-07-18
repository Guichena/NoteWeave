from __future__ import annotations

import json
from pathlib import Path

from app.artifact_repository import (
    clear_artifact_repository,
    configure_artifact_repository_backend,
    get_artifact_version_detail,
    get_retrieval_entry_detail,
    get_artifact_repository_backend_info,
    list_artifact_versions,
    list_retrieval_entries,
    rollback_artifact_version,
)
from app.models import (
    CustomMcpServerRegistration,
    CustomMcpToolRegistration,
    CustomProductionActionRegistration,
    CustomPromptRecipeRegistration,
    CustomSkillDefinitionRegistration,
    CustomSkillGraphTemplateRegistration,
    CustomStyleProfileRegistration,
    SkillGraphEdge,
    SkillGraphNode,
)
from app.main import (
    debug_get_artifact_repository_backend,
    debug_get_custom_artifact_config_store,
)
from app.registry import (
    configure_custom_artifact_config_store,
    list_custom_actions,
    list_custom_mcp_servers,
    list_custom_prompt_recipes,
    list_custom_skill_definitions,
    list_custom_skill_graph_templates,
    list_default_prompt_recipes,
    list_default_skill_definitions,
    list_default_skill_graph_templates,
    list_default_style_profiles,
    list_custom_style_profiles,
    register_custom_action,
    register_custom_mcp_server,
    register_custom_prompt_recipe,
    register_custom_skill_definition,
    register_custom_skill_graph_template,
    register_custom_style_profile,
    reset_custom_actions,
    reset_custom_mcp_servers,
    reset_custom_prompt_recipes,
    reset_custom_skill_definitions,
    reset_custom_skill_graph_templates,
    reset_custom_style_profiles,
)
from app.runner import run_artifact_task
from tests.test_runner import _build_generic_task_input, _build_resume_task_input


def test_file_repository_backend_should_persist_records_to_disk(tmp_path: Path) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    try:
        configure_artifact_repository_backend("file", storage_path=storage_path)
        clear_artifact_repository()

        _, result = run_artifact_task(_build_generic_task_input("report"))

        backend_info = get_artifact_repository_backend_info()
        versions = list_artifact_versions(target_id=result.job_snapshot.target_id)
        retrieval_entries = list_retrieval_entries(target_id=result.job_snapshot.target_id)
        persisted_state = json.loads(storage_path.read_text(encoding="utf-8"))
        persisted_version = persisted_state["versions"][0]
        persisted_retrieval = persisted_state["retrieval_entries"][0]

        assert storage_path.exists()
        assert backend_info["backend_type"] == "file"
        assert backend_info["storage_path"] == str(storage_path)
        assert versions[0]["version_id"] == result.version_snapshot.version_id
        assert retrieval_entries[0]["version_id"] == result.version_snapshot.version_id
        assert persisted_version["execution_plan_summary"] == {
            "skill_key": result.result_payload["execution_plan"]["skill_key"],
            "action_key": result.result_payload["execution_plan"]["action_key"],
            "style_profile_key": result.result_payload["execution_plan"]["style_profile_key"],
            "skill_graph_key": result.result_payload["execution_plan"]["skill_graph_key"],
            "prompt_recipe_id": result.result_payload["execution_plan"]["prompt_recipe"]["recipe_id"],
            "schema_gate_status": result.result_payload["execution_plan"]["schema_gate_status"],
        }
        assert persisted_version["artifact_preview"] == {
            "markdown": result.result_payload["markdown"],
            "section_count": len(result.result_payload["sections"]),
            "section_headings": [
                section["heading"] for section in result.result_payload["sections"]
            ],
        }
        assert persisted_version["runtime_trace"]["verification"]["status"] == "PASS"
        assert persisted_version["runtime_trace"]["node_traces"][0]["skill_key"] == "workspace_material_digest"
        assert persisted_version["runtime_trace"]["node_traces"] == result.result_payload["node_traces"]
        assert persisted_version["runtime_trace"]["verification"] == result.result_payload["verification"]
        assert (
            persisted_version["runtime_trace"]["capability_union_trace"]
            == result.result_payload["capability_union_trace"]
        )
        assert (
            persisted_version["runtime_trace"]["approval_trace"]
            == result.result_payload["approval_trace"]
        )
        assert (
            persisted_version["runtime_trace"]["evidence_coverage"]
            == result.result_payload["evidence_coverage"]
        )
        assert (
            persisted_version["runtime_trace"]["writeback_preview"]
            == result.result_payload["writeback_preview"]
        )
        assert (
            persisted_version["runtime_trace"]["output_contract_trace"]
            == result.result_payload["output_contract_trace"]
        )
        assert (
            persisted_version["runtime_trace"]["lifecycle_trace"]
            == result.result_payload["lifecycle_trace"]
        )
        assert persisted_retrieval["derived_passages"] == result.result_payload["retrieval_feedback"]["derived_passages"]
        assert (
            persisted_retrieval["memory_promotion_preview"]
            == result.result_payload["memory_promotion_preview"]
        )
        assert (
            "source trace captured for digested materials"
            in persisted_version["runtime_trace"]["node_traces"][0]["verification_checks"]
        )
    finally:
        configure_artifact_repository_backend("memory")
        clear_artifact_repository()


def test_file_repository_backend_should_reload_existing_records(tmp_path: Path) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    try:
        configure_artifact_repository_backend("file", storage_path=storage_path)
        clear_artifact_repository()
        _, result = run_artifact_task(_build_generic_task_input("faq"))

        configure_artifact_repository_backend("file", storage_path=storage_path)
        versions = list_artifact_versions(target_id=result.job_snapshot.target_id)
        retrieval_entries = list_retrieval_entries(target_id=result.job_snapshot.target_id)
        version_detail = get_artifact_version_detail(
            target_id=result.job_snapshot.target_id,
            version_id=result.version_snapshot.version_id,
        )
        retrieval_detail = get_retrieval_entry_detail(
            target_id=result.job_snapshot.target_id,
            retrieval_entry_id=f"retrieval-{result.version_snapshot.version_id}",
        )
        backend_response = debug_get_artifact_repository_backend()

        assert versions
        assert versions[0]["action_key"] == "FAQ"
        assert retrieval_entries
        assert version_detail["result_title"] == result.result_title
        assert version_detail["execution_plan_summary"] == {
            "skill_key": result.result_payload["execution_plan"]["skill_key"],
            "action_key": result.result_payload["execution_plan"]["action_key"],
            "style_profile_key": result.result_payload["execution_plan"]["style_profile_key"],
            "skill_graph_key": result.result_payload["execution_plan"]["skill_graph_key"],
            "prompt_recipe_id": result.result_payload["execution_plan"]["prompt_recipe"]["recipe_id"],
            "schema_gate_status": result.result_payload["execution_plan"]["schema_gate_status"],
        }
        assert version_detail["artifact_preview"] == {
            "markdown": result.result_payload["markdown"],
            "section_count": len(result.result_payload["sections"]),
            "section_headings": [
                section["heading"] for section in result.result_payload["sections"]
            ],
        }
        assert version_detail["runtime_trace"]["verification"]["status"] == "PASS"
        assert version_detail["runtime_trace"]["node_traces"][0]["skill_key"] == "workspace_material_digest"
        assert version_detail["runtime_trace"]["node_traces"] == result.result_payload["node_traces"]
        assert version_detail["runtime_trace"]["verification"] == result.result_payload["verification"]
        assert (
            version_detail["runtime_trace"]["capability_union_trace"]
            == result.result_payload["capability_union_trace"]
        )
        assert (
            version_detail["runtime_trace"]["approval_trace"]
            == result.result_payload["approval_trace"]
        )
        assert (
            version_detail["runtime_trace"]["evidence_coverage"]
            == result.result_payload["evidence_coverage"]
        )
        assert (
            version_detail["runtime_trace"]["writeback_preview"]
            == result.result_payload["writeback_preview"]
        )
        assert (
            version_detail["runtime_trace"]["output_contract_trace"]
            == result.result_payload["output_contract_trace"]
        )
        assert (
            version_detail["runtime_trace"]["lifecycle_trace"]
            == result.result_payload["lifecycle_trace"]
        )
        assert retrieval_detail["derived_passage_count"] >= 1
        assert retrieval_detail["memory_promotion_preview"]["candidate_memory_count"] >= 1
        assert retrieval_detail["derived_passages"] == result.result_payload["retrieval_feedback"]["derived_passages"]
        assert (
            retrieval_detail["memory_promotion_preview"]
            == result.result_payload["memory_promotion_preview"]
        )
        assert backend_response.backend["backend_type"] == "file"
    finally:
        configure_artifact_repository_backend("memory")
        clear_artifact_repository()


def test_file_repository_backend_should_persist_and_reload_rollback_records(
    tmp_path: Path,
) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    try:
        configure_artifact_repository_backend("file", storage_path=storage_path)
        clear_artifact_repository()
        task_input = _build_generic_task_input("report")
        _, result = run_artifact_task(task_input)

        rollback_receipt = rollback_artifact_version(
            target_id=task_input.target_id,
            version_id=result.version_snapshot.version_id,
        )
        persisted_state = json.loads(storage_path.read_text(encoding="utf-8"))
        persisted_versions = persisted_state["versions"]
        persisted_retrievals = persisted_state["retrieval_entries"]
        persisted_rollback_version = next(
            item for item in persisted_versions if item["version_id"] == rollback_receipt["version_id"]
        )
        persisted_rollback_retrieval = next(
            item
            for item in persisted_retrievals
            if item["retrieval_entry_id"] == rollback_receipt["retrieval_entry_id"]
        )

        configure_artifact_repository_backend("file", storage_path=storage_path)
        versions = list_artifact_versions(target_id=task_input.target_id, action_key="REPORT")
        original_version = get_artifact_version_detail(
            target_id=task_input.target_id,
            version_id=result.version_snapshot.version_id,
        )
        rolled_back_version = get_artifact_version_detail(
            target_id=task_input.target_id,
            version_id=rollback_receipt["version_id"],
        )
        original_retrieval = get_retrieval_entry_detail(
            target_id=task_input.target_id,
            retrieval_entry_id=f"retrieval-{result.version_snapshot.version_id}",
        )
        rolled_back_retrieval = get_retrieval_entry_detail(
            target_id=task_input.target_id,
            retrieval_entry_id=rollback_receipt["retrieval_entry_id"],
        )

        assert len(versions) == 2
        assert rolled_back_version["status"] == "ROLLED_BACK"
        assert rolled_back_version["parent_version_id"] == result.version_snapshot.version_id
        assert rolled_back_version["rollback_of_version_id"] == result.version_snapshot.version_id
        assert rolled_back_version["artifact_preview"] == original_version["artifact_preview"]
        assert rolled_back_version["runtime_trace"] == original_version["runtime_trace"]
        assert rolled_back_retrieval["derived_passages"] == original_retrieval["derived_passages"]
        assert (
            rolled_back_retrieval["memory_promotion_preview"]
            == original_retrieval["memory_promotion_preview"]
        )
        assert f"rollback_of:{result.version_snapshot.version_id}" in rolled_back_retrieval["notes"]
        assert persisted_rollback_version["artifact_preview"] == original_version["artifact_preview"]
        assert persisted_rollback_version["runtime_trace"] == original_version["runtime_trace"]
        assert (
            persisted_rollback_retrieval["memory_promotion_preview"]
            == original_retrieval["memory_promotion_preview"]
        )
    finally:
        configure_artifact_repository_backend("memory")
        clear_artifact_repository()


def test_file_repository_backend_should_persist_skill_key_for_skill_first_versions(
    tmp_path: Path,
) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    try:
        configure_artifact_repository_backend("file", storage_path=storage_path)
        clear_artifact_repository()
        _, result = run_artifact_task(_build_resume_task_input())

        versions = list_artifact_versions(
            target_id=result.job_snapshot.target_id,
            skill_key="resume_highlight",
        )
        persisted_state = json.loads(storage_path.read_text(encoding="utf-8"))
        persisted_version = persisted_state["versions"][0]

        assert len(versions) == 1
        assert versions[0]["skill_key"] == "resume_highlight"
        assert persisted_version["skill_key"] == "resume_highlight"
        assert persisted_version["execution_plan_summary"]["skill_key"] == "resume_highlight"
    finally:
        configure_artifact_repository_backend("memory")
        clear_artifact_repository()


def test_file_repository_backend_should_prefer_skill_key_over_conflicting_action_key_filter(
    tmp_path: Path,
) -> None:
    storage_path = tmp_path / "artifact-repository.json"
    try:
        configure_artifact_repository_backend("file", storage_path=storage_path)
        clear_artifact_repository()
        _, result = run_artifact_task(_build_resume_task_input())
        run_artifact_task(_build_generic_task_input("faq"))

        versions = list_artifact_versions(
            target_id=result.job_snapshot.target_id,
            skill_key="resume_highlight",
            action_key="FAQ",
        )

        assert len(versions) == 1
        assert versions[0]["version_id"] == result.version_snapshot.version_id
        assert versions[0]["skill_key"] == "resume_highlight"
    finally:
        configure_artifact_repository_backend("memory")
        clear_artifact_repository()


def test_custom_artifact_config_store_should_persist_prompt_recipes_and_actions(tmp_path: Path) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    try:
        configure_custom_artifact_config_store(storage_path)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()
        reset_custom_mcp_servers()

        register_custom_prompt_recipe(
            CustomPromptRecipeRegistration.model_validate(
                {
                    "recipe_id": "executive_summary_writer_v2",
                    "recipe_name": "Executive Summary Writer",
                    "base_recipe_id": "report_executive_writer_v1",
                    "supported_actions": ["EXECUTIVE_SUMMARY"],
                    "system_intent": "将结构化报告改写成面向管理层的决策摘要。",
                    "node_guidance": {
                        "generic_section_writer": "优先以管理层决策语言生成章节。"
                    },
                }
            )
        )
        register_custom_action(
            CustomProductionActionRegistration.model_validate(
                {
                    "action_key": "executive_summary",
                    "display_name": "管理决策摘要",
                    "base_action_key": "report",
                    "default_style_profile_key": "executive",
                    "default_prompt_recipe_id": "executive_summary_writer_v2",
                    "artifact_type": "EXECUTIVE_SUMMARY",
                    "output_sections": ["决策摘要", "关键证据", "推进建议"],
                    "required_phrases": ["Production Action", "Capability Union Policy"],
                    "resolver_keywords": ["管理摘要", "决策摘要"],
                    "preferred_source_platforms": ["WEB"],
                    "preferred_structure_keywords": ["决策摘要", "关键证据"],
                    "preferred_task_neighborhoods": ["RESEARCH_SYNTHESIS"],
                }
            )
        )

        configure_custom_artifact_config_store(None)
        configure_custom_artifact_config_store(storage_path)

        recipes = list_default_prompt_recipes()
        actions = list_custom_actions()

        assert storage_path.exists()
        loaded_recipe = next(
            recipe for recipe in recipes if recipe.recipe_id == "executive_summary_writer_v2"
        )
        loaded_action = next(
            action for action in actions if action.action_key == "EXECUTIVE_SUMMARY"
        )

        assert loaded_recipe.node_guidance["generic_section_writer"] == "优先以管理层决策语言生成章节。"
        assert loaded_action.preferred_source_platforms == ["WEB"]
        assert loaded_action.preferred_structure_keywords == ["决策摘要", "关键证据"]
        assert loaded_action.preferred_task_neighborhoods == ["RESEARCH_SYNTHESIS"]
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()
        reset_custom_mcp_servers()


def test_custom_artifact_config_store_reset_should_clear_persisted_config_and_reload_empty(
    tmp_path: Path,
) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    try:
        configure_custom_artifact_config_store(storage_path)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()

        register_custom_prompt_recipe(
            CustomPromptRecipeRegistration.model_validate(
                {
                    "recipe_id": "resume_focus_writer_v1",
                    "recipe_name": "Resume Focus Writer",
                    "base_recipe_id": "resume_highlight_writer_v1",
                    "supported_actions": ["RESUME_FOCUS"],
                    "system_intent": "Render concise resume-ready highlights.",
                    "section_guidance": {
                        "Positioning": "Lead with architecture control and delivery impact.",
                    },
                }
            )
        )
        register_custom_action(
            CustomProductionActionRegistration.model_validate(
                {
                    "action_key": "resume_focus",
                    "display_name": "Resume Focus",
                    "base_action_key": "resume_highlight",
                    "default_style_profile_key": "interview",
                    "default_prompt_recipe_id": "resume_focus_writer_v1",
                    "artifact_type": "RESUME_FOCUS",
                    "output_sections": ["Positioning", "Highlights", "Keywords"],
                    "required_phrases": ["Controlled Agentic Graph Harness"],
                    "resolver_keywords": ["resume focus"],
                }
            )
        )

        persisted_before_reset = json.loads(storage_path.read_text(encoding="utf-8"))

        assert len(persisted_before_reset["custom_style_profiles"]) == 0
        assert len(persisted_before_reset["custom_skill_definitions"]) == 0
        assert len(persisted_before_reset["custom_skill_graph_templates"]) == 0
        assert len(persisted_before_reset["custom_actions"]) == 1
        assert len(persisted_before_reset["custom_prompt_recipes"]) == 1

        reset_custom_actions()
        reset_custom_prompt_recipes()

        persisted_after_reset = json.loads(storage_path.read_text(encoding="utf-8"))

        assert persisted_after_reset == {
            "custom_style_profiles": [],
            "custom_skill_definitions": [],
            "custom_skill_graph_templates": [],
            "custom_actions": [],
            "custom_prompt_recipes": [],
            "custom_mcp_servers": [],
        }
        assert list_custom_actions() == []
        assert list_custom_prompt_recipes() == []

        configure_custom_artifact_config_store(None)
        configure_custom_artifact_config_store(storage_path)

        assert list_custom_actions() == []
        assert list_custom_prompt_recipes() == []
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()
        reset_custom_mcp_servers()


def test_custom_artifact_config_store_should_persist_custom_style_profiles(tmp_path: Path) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    try:
        configure_custom_artifact_config_store(storage_path)
        reset_custom_style_profiles()

        register_custom_style_profile(
            CustomStyleProfileRegistration.model_validate(
                {
                    "profile_key": "founder_pitch",
                    "profile_name": "Founder Pitch",
                    "base_profile_key": "executive",
                    "tone": "investor-ready",
                    "structure_mode": "value_first",
                    "audience_type": "founder",
                    "length_preference": "short",
                    "citation_density": "low",
                    "format_constraints": [
                        "开头先给价值主张。",
                        "每节都尽量保留控制性架构关键词。",
                    ],
                }
            )
        )

        configure_custom_artifact_config_store(None)
        configure_custom_artifact_config_store(storage_path)

        profiles = list_default_style_profiles()
        custom_profiles = list_custom_style_profiles()

        assert storage_path.exists()
        loaded_profile = next(
            profile for profile in profiles if profile.profile_key == "FOUNDER_PITCH"
        )

        assert loaded_profile.structure_mode == "value_first"
        assert loaded_profile.format_constraints == [
            "开头先给价值主张。",
            "每节都尽量保留控制性架构关键词。",
        ]
        assert len(custom_profiles) == 1
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()


def test_custom_artifact_config_store_should_persist_custom_skill_definitions_and_graphs(
    tmp_path: Path,
) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    try:
        configure_custom_artifact_config_store(storage_path)
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()

        register_custom_skill_definition(
            CustomSkillDefinitionRegistration.model_validate(
                {
                    "skill_key": "generic_section_writer_executive",
                    "base_skill_key": "generic_section_writer",
                    "skill_version": "1.1.0",
                    "verifier_policy": ["must preserve executive section framing"],
                }
            )
        )
        register_custom_skill_graph_template(
            CustomSkillGraphTemplateRegistration.model_validate(
                {
                    "graph_key": "executive_artifact_v1",
                    "graph_name": "Executive Artifact Graph",
                    "base_graph_key": "generic_artifact_v1",
                    "action_type": "EXECUTIVE_SUMMARY_GRAPH",
                    "nodes": [
                        SkillGraphNode(
                            node_id="digest",
                            skill_key="workspace_material_digest",
                            purpose="Compile workspace material digest.",
                        ),
                        SkillGraphNode(
                            node_id="write",
                            skill_key="generic_section_writer_executive",
                            purpose="Generate executive-facing sections.",
                        ),
                        SkillGraphNode(
                            node_id="guard",
                            skill_key="evidence_guard",
                            purpose="Check section evidence trace.",
                        ),
                        SkillGraphNode(
                            node_id="polish",
                            skill_key="style_polisher",
                            purpose="Polish final section wording.",
                        ),
                    ],
                    "edges": [
                        SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
                        SkillGraphEdge(from_node="write", to_node="guard", edge_type="PREREQUISITE"),
                        SkillGraphEdge(from_node="guard", to_node="polish", edge_type="PREREQUISITE"),
                    ],
                }
            )
        )

        configure_custom_artifact_config_store(None)
        configure_custom_artifact_config_store(storage_path)

        skills = list_default_skill_definitions()
        graphs = list_default_skill_graph_templates()
        custom_skills = list_custom_skill_definitions()
        custom_graphs = list_custom_skill_graph_templates()

        loaded_skill = next(
            skill for skill in skills if skill.skill_key == "generic_section_writer_executive"
        )
        loaded_graph = next(
            graph for graph in graphs if graph.graph_key == "executive_artifact_v1"
        )

        assert loaded_skill.template_skill_key == "generic_section_writer"
        assert loaded_skill.skill_version == "1.1.0"
        assert loaded_graph.action_type == "EXECUTIVE_SUMMARY_GRAPH"
        assert loaded_graph.nodes[1].skill_key == "generic_section_writer_executive"
        assert len(custom_skills) == 1
        assert len(custom_graphs) == 1
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()


def test_custom_artifact_config_store_reset_should_clear_all_custom_catalogs_and_reload_empty(
    tmp_path: Path,
) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    try:
        configure_custom_artifact_config_store(storage_path)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()

        register_custom_style_profile(
            CustomStyleProfileRegistration.model_validate(
                {
                    "profile_key": "founder_pitch",
                    "profile_name": "Founder Pitch",
                    "base_profile_key": "executive",
                    "tone": "investor-ready",
                    "structure_mode": "value_first",
                    "audience_type": "founder",
                    "length_preference": "short",
                    "citation_density": "low",
                }
            )
        )
        register_custom_skill_definition(
            CustomSkillDefinitionRegistration.model_validate(
                {
                    "skill_key": "generic_section_writer_executive",
                    "base_skill_key": "generic_section_writer",
                }
            )
        )
        register_custom_skill_graph_template(
            CustomSkillGraphTemplateRegistration.model_validate(
                {
                    "graph_key": "executive_artifact_v1",
                    "graph_name": "Executive Artifact Graph",
                    "base_graph_key": "generic_artifact_v1",
                    "action_type": "EXECUTIVE_SUMMARY_GRAPH",
                    "nodes": [
                        SkillGraphNode(
                            node_id="digest",
                            skill_key="workspace_material_digest",
                            purpose="Compile workspace material digest.",
                        ),
                        SkillGraphNode(
                            node_id="write",
                            skill_key="generic_section_writer_executive",
                            purpose="Generate executive-facing sections.",
                        ),
                    ],
                    "edges": [
                        SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
                    ],
                }
            )
        )
        register_custom_prompt_recipe(
            CustomPromptRecipeRegistration.model_validate(
                {
                    "recipe_id": "resume_focus_writer_v1",
                    "recipe_name": "Resume Focus Writer",
                    "base_recipe_id": "resume_highlight_writer_v1",
                    "supported_actions": ["RESUME_FOCUS"],
                    "system_intent": "Render concise resume-ready highlights.",
                }
            )
        )
        register_custom_action(
            CustomProductionActionRegistration.model_validate(
                {
                    "action_key": "resume_focus",
                    "display_name": "Resume Focus",
                    "base_action_key": "resume_highlight",
                    "default_style_profile_key": "interview",
                    "default_skill_graph_key": "executive_artifact_v1",
                    "default_prompt_recipe_id": "resume_focus_writer_v1",
                    "artifact_type": "RESUME_FOCUS",
                    "output_sections": ["Positioning", "Highlights", "Keywords"],
                    "required_phrases": ["Controlled Agentic Graph Harness"],
                }
            )
        )

        persisted_before_reset = json.loads(storage_path.read_text(encoding="utf-8"))

        assert len(persisted_before_reset["custom_style_profiles"]) == 1
        assert len(persisted_before_reset["custom_skill_definitions"]) == 1
        assert len(persisted_before_reset["custom_skill_graph_templates"]) == 1
        assert len(persisted_before_reset["custom_actions"]) == 1
        assert len(persisted_before_reset["custom_prompt_recipes"]) == 1

        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()

        persisted_after_reset = json.loads(storage_path.read_text(encoding="utf-8"))

        assert persisted_after_reset == {
            "custom_style_profiles": [],
            "custom_skill_definitions": [],
            "custom_skill_graph_templates": [],
            "custom_actions": [],
            "custom_prompt_recipes": [],
            "custom_mcp_servers": [],
        }
        assert list_custom_style_profiles() == []
        assert list_custom_skill_definitions() == []
        assert list_custom_skill_graph_templates() == []
        assert list_custom_actions() == []
        assert list_custom_prompt_recipes() == []

        configure_custom_artifact_config_store(None)
        configure_custom_artifact_config_store(storage_path)

        assert list_custom_style_profiles() == []
        assert list_custom_skill_definitions() == []
        assert list_custom_skill_graph_templates() == []
        assert list_custom_actions() == []
        assert list_custom_prompt_recipes() == []
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()
        reset_custom_mcp_servers()


def test_custom_artifact_config_store_should_persist_and_reload_all_custom_catalogs(
    tmp_path: Path,
) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    try:
        configure_custom_artifact_config_store(storage_path)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()

        register_custom_style_profile(
            CustomStyleProfileRegistration.model_validate(
                {
                    "profile_key": "schema_controlled_resume",
                    "profile_name": "Schema Controlled Resume",
                    "base_profile_key": "interview",
                    "tone": "high-signal",
                    "structure_mode": "impact_first",
                    "audience_type": "recruiter",
                    "length_preference": "short",
                    "citation_density": "medium",
                    "format_constraints": [
                        "Lead with controlled architecture outcomes.",
                        "Keep every bullet evidence-backed and resume-ready.",
                    ],
                }
            )
        )
        register_custom_skill_definition(
            CustomSkillDefinitionRegistration.model_validate(
                {
                    "skill_key": "resume_highlight_strengthener",
                    "base_skill_key": "generic_section_writer",
                    "skill_version": "1.0.1",
                    "verifier_policy": [
                        "must mention Controlled Agentic Graph Harness",
                        "must preserve resume highlight scannability",
                    ],
                }
            )
        )
        register_custom_skill_graph_template(
            CustomSkillGraphTemplateRegistration.model_validate(
                {
                    "graph_key": "controlled_resume_highlight_v1",
                    "graph_name": "Controlled Resume Highlight Graph",
                    "base_graph_key": "resume_highlight_v1",
                    "action_type": "RESUME_CASE_HIGHLIGHT_GRAPH",
                    "nodes": [
                        SkillGraphNode(
                            node_id="digest",
                            skill_key="workspace_material_digest",
                            purpose="Digest product and implementation context.",
                        ),
                        SkillGraphNode(
                            node_id="strengthen",
                            skill_key="resume_highlight_strengthener",
                            purpose="Convert architecture work into resume bullets.",
                        ),
                        SkillGraphNode(
                            node_id="guard",
                            skill_key="evidence_guard",
                            purpose="Verify that each highlight stays evidence-backed.",
                        ),
                    ],
                    "edges": [
                        SkillGraphEdge(
                            from_node="digest",
                            to_node="strengthen",
                            edge_type="PREREQUISITE",
                        ),
                        SkillGraphEdge(
                            from_node="strengthen",
                            to_node="guard",
                            edge_type="PREREQUISITE",
                        ),
                    ],
                }
            )
        )
        register_custom_prompt_recipe(
            CustomPromptRecipeRegistration.model_validate(
                {
                    "recipe_id": "resume_highlight_case_writer_v1",
                    "recipe_name": "Resume Highlight Case Writer",
                    "base_recipe_id": "resume_highlight_writer_v1",
                    "supported_actions": ["RESUME_CASE_HIGHLIGHT"],
                    "system_intent": "Render concise, recruiter-facing architecture highlights.",
                    "section_guidance": {
                        "Highlights": "Emphasize Production Action, Style Profile, and Skill Graph."
                    },
                }
            )
        )
        register_custom_action(
            CustomProductionActionRegistration.model_validate(
                {
                    "action_key": "resume_case_highlight",
                    "display_name": "Resume Case Highlight",
                    "base_action_key": "resume_highlight",
                    "default_style_profile_key": "schema_controlled_resume",
                    "default_skill_graph_key": "controlled_resume_highlight_v1",
                    "default_prompt_recipe_id": "resume_highlight_case_writer_v1",
                    "artifact_type": "RESUME_CASE_HIGHLIGHT",
                    "output_sections": ["Positioning", "Highlights", "Keywords"],
                    "required_phrases": [
                        "Controlled Agentic Graph Harness",
                        "Schema-Gated Skill Graph Runtime",
                        "Capability Union Policy",
                    ],
                    "resolver_keywords": ["resume case highlight", "resume bullets"],
                }
            )
        )
        register_custom_mcp_server(
            CustomMcpServerRegistration.model_validate(
                {
                    "server_id": "custom-bilibili-render-pdf",
                    "display_name": "Bilibili Render PDF MCP",
                    "tools": [
                        CustomMcpToolRegistration(
                            capability_name="EXTRACT_TRANSCRIPT",
                            tool_name="get_bilibili_subtitle",
                            supported_routes=["VIDEO_URL"],
                            supported_actions=["VIDEO_SUMMARY", "COURSE_NOTES"],
                            preference_rank=320,
                            selection_reason_hint="custom_bilibili_render_pdf_subtitle",
                        ),
                        CustomMcpToolRegistration(
                            capability_name="EXPORT_ARTIFACT_FILE",
                            tool_name="render_latex_pdf",
                            supported_routes=["VIDEO_URL", "VIDEO_FILE"],
                            supported_actions=["COURSE_NOTES"],
                            preference_rank=320,
                            selection_reason_hint="custom_bilibili_render_pdf_export",
                        ),
                    ],
                }
            )
        )

        persisted_state = json.loads(storage_path.read_text(encoding="utf-8"))

        assert len(persisted_state["custom_style_profiles"]) == 1
        assert len(persisted_state["custom_skill_definitions"]) == 1
        assert len(persisted_state["custom_skill_graph_templates"]) == 1
        assert len(persisted_state["custom_actions"]) == 1
        assert len(persisted_state["custom_prompt_recipes"]) == 1
        assert len(persisted_state["custom_mcp_servers"]) == 1
        assert (
            persisted_state["custom_style_profiles"][0]["profile_key"]
            == "SCHEMA_CONTROLLED_RESUME"
        )
        assert (
            persisted_state["custom_skill_definitions"][0]["skill_key"]
            == "resume_highlight_strengthener"
        )
        assert (
            persisted_state["custom_skill_graph_templates"][0]["graph_key"]
            == "controlled_resume_highlight_v1"
        )
        assert (
            persisted_state["custom_skill_graph_templates"][0]["nodes"][1]["skill_key"]
            == "resume_highlight_strengthener"
        )
        assert (
            persisted_state["custom_actions"][0]["action_key"] == "RESUME_CASE_HIGHLIGHT"
        )
        assert (
            persisted_state["custom_actions"][0]["default_skill_graph_key"]
            == "controlled_resume_highlight_v1"
        )
        assert (
            persisted_state["custom_actions"][0]["default_prompt_recipe_id"]
            == "resume_highlight_case_writer_v1"
        )
        assert (
            persisted_state["custom_prompt_recipes"][0]["recipe_id"]
            == "resume_highlight_case_writer_v1"
        )
        assert (
            persisted_state["custom_mcp_servers"][0]["server_id"]
            == "custom-bilibili-render-pdf"
        )
        assert (
            persisted_state["custom_mcp_servers"][0]["tools"][0]["capability_name"]
            == "EXTRACT_TRANSCRIPT"
        )

        configure_custom_artifact_config_store(None)
        configure_custom_artifact_config_store(storage_path)

        default_profiles = list_default_style_profiles()
        default_skills = list_default_skill_definitions()
        default_graphs = list_default_skill_graph_templates()
        default_recipes = list_default_prompt_recipes()
        custom_profiles = list_custom_style_profiles()
        custom_skills = list_custom_skill_definitions()
        custom_graphs = list_custom_skill_graph_templates()
        custom_actions = list_custom_actions()
        custom_mcp_servers = list_custom_mcp_servers()
        debug_store = debug_get_custom_artifact_config_store().store

        loaded_profile = next(
            profile
            for profile in default_profiles
            if profile.profile_key == "SCHEMA_CONTROLLED_RESUME"
        )
        loaded_skill = next(
            skill for skill in default_skills if skill.skill_key == "resume_highlight_strengthener"
        )
        loaded_graph = next(
            graph for graph in default_graphs if graph.graph_key == "controlled_resume_highlight_v1"
        )
        loaded_recipe = next(
            recipe
            for recipe in default_recipes
            if recipe.recipe_id == "resume_highlight_case_writer_v1"
        )
        loaded_action = next(
            action for action in custom_actions if action.action_key == "RESUME_CASE_HIGHLIGHT"
        )

        assert loaded_profile.structure_mode == "impact_first"
        assert loaded_skill.template_skill_key == "generic_section_writer"
        assert loaded_graph.nodes[1].skill_key == "resume_highlight_strengthener"
        assert loaded_recipe.supported_actions == ["RESUME_CASE_HIGHLIGHT"]
        assert loaded_action.default_style_profile_key == "SCHEMA_CONTROLLED_RESUME"
        assert loaded_action.default_skill_graph_key == "controlled_resume_highlight_v1"
        assert loaded_action.default_prompt_recipe_id == "resume_highlight_case_writer_v1"
        assert len(custom_profiles) == 1
        assert len(custom_skills) == 1
        assert len(custom_graphs) == 1
        assert len(custom_actions) == 1
        assert len(custom_mcp_servers) == 1
        assert custom_mcp_servers[0].tools[1].tool_name == "render_latex_pdf"
        assert debug_store["storage_path"] == str(storage_path.resolve())
        assert debug_store["custom_style_profile_count"] == 1
        assert debug_store["custom_skill_definition_count"] == 1
        assert debug_store["custom_skill_graph_count"] == 1
        assert debug_store["custom_action_count"] == 1
        assert debug_store["custom_prompt_recipe_count"] == 1
        assert debug_store["custom_mcp_server_count"] == 1
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()
        reset_custom_mcp_servers()


def test_custom_artifact_config_store_should_ignore_empty_file(tmp_path: Path) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    storage_path.write_text("", encoding="utf-8")

    try:
        configure_custom_artifact_config_store(storage_path)
        profiles = list_default_style_profiles()
        recipes = list_default_prompt_recipes()
        actions = list_custom_actions()

        assert profiles
        assert recipes
        assert actions == []
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()


def test_custom_artifact_config_store_should_ignore_invalid_json_file(tmp_path: Path) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    storage_path.write_text("{ invalid json", encoding="utf-8")

    try:
        configure_custom_artifact_config_store(storage_path)
        profiles = list_default_style_profiles()
        recipes = list_default_prompt_recipes()
        actions = list_custom_actions()

        assert profiles
        assert recipes
        assert actions == []
    finally:
        configure_custom_artifact_config_store(None)
        reset_custom_style_profiles()
        reset_custom_skill_definitions()
        reset_custom_skill_graph_templates()
        reset_custom_actions()
        reset_custom_prompt_recipes()
