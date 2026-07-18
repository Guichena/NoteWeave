import re
from pathlib import Path
import asyncio
from starlette.requests import Request
from starlette.responses import JSONResponse

from app.config import load_settings
from app.capability_wait_queue import clear_waiting_tasks, enqueue_waiting_task
from app.models import (
    ArtifactTaskInput,
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
    debug_get_artifact_skills,
    debug_ack_acquisition_operation,
    debug_dispatch_acquisition_operation,
    debug_get_acquisition_callback_receipt_detail,
    debug_get_acquisition_operation_detail,
    debug_list_acquisition_callback_receipts,
    debug_list_acquisition_operations,
    debug_reset_acquisition_runtime,
    debug_ack_writeback_request,
    debug_dispatch_writeback_request,
    app,
    debug_execute_writeback_request,
    debug_get_writeback_receipt_detail,
    debug_get_writeback_request_detail,
    debug_register_custom_action,
    debug_register_custom_mcp_server,
    debug_register_custom_prompt_recipe,
    debug_register_custom_skill_definition,
    debug_register_custom_skill_graph,
    debug_register_custom_style_profile,
    debug_resolve_action,
    debug_get_custom_artifact_config_store,
    debug_get_custom_mcp_blueprints,
    debug_get_custom_mcp_servers,
    debug_reset_custom_actions,
    debug_reset_custom_mcp_servers,
    debug_reset_custom_prompt_recipes,
    debug_reset_custom_skill_definitions,
    debug_reset_custom_skill_graphs,
    debug_reset_custom_style_profiles,
    debug_get_artifact_repository_backend,
    debug_get_capability_mappings,
    debug_get_capability_bindings,
    debug_get_capability_approval_request_detail,
    debug_get_capability_provider_discovery,
    debug_get_capability_mapping_detail,
    debug_get_capability_provider_detail,
    debug_get_capability_provider_health,
    debug_get_prompt_recipes,
    debug_get_skill_definitions,
    debug_get_skill_graphs,
    debug_get_style_profiles,
    debug_run_task,
    debug_get_artifact_version_detail,
    debug_get_retrieval_entry_detail,
    debug_get_waiting_task_detail,
    debug_list_artifact_versions,
    debug_get_capability_providers,
    debug_list_capability_approval_requests,
    debug_list_default_actions,
    debug_list_retrieval_entries,
    debug_list_waiting_tasks,
    debug_wake_waiting_task,
    debug_list_writeback_receipts,
    debug_list_writeback_requests,
    debug_reset_writeback_runtime,
    debug_run_capability_provider_discovery,
)
from app.registry import configure_custom_artifact_config_store


def _contains_nested_key(value: object, key: str) -> bool:
    if isinstance(value, dict):
        if key in value:
            return True
        return any(_contains_nested_key(item, key) for item in value.values())
    if isinstance(value, list):
        return any(_contains_nested_key(item, key) for item in value)
    return False


def test_artifact_worker_imports() -> None:
    settings = load_settings()
    assert settings.worker_type == "artifact"
    assert app.title == "NoteWeave Artifact Worker"


def test_artifact_worker_preserves_input_snapshot_and_upstream_revision_refs() -> None:
    task_input = ArtifactTaskInput.model_validate(
        {
            "task_id": "task-1",
            "workspace_id": "workspace-1",
            "target_id": "artifact-1",
            "input_snapshot_id": "snapshot-1",
            "replay_availability": "METADATA_ONLY",
            "source_scope": [],
            "upstream_refs": [
                {
                    "ref_type": "RESEARCH_REPORT",
                    "ref_id": "research-run-1",
                    "revision_id": "source-snapshot-1",
                }
            ],
            "context_snapshot": {"context_snapshot_id": ""},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": "report_draft",
                "task_neighborhood": "ARTIFACT_SKILL_REPORT_DRAFT",
            },
            "input_payload": {"skill_key": "report_draft"},
        }
    )

    assert task_input.input_snapshot_id == "snapshot-1"
    assert task_input.replay_availability == "METADATA_ONLY"
    assert task_input.upstream_refs[0].ref_type == "RESEARCH_REPORT"
    assert task_input.upstream_refs[0].revision_id == "source-snapshot-1"


def test_artifact_worker_should_use_only_its_namespaced_llm_configuration(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_API_KEY", "artifact-key")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_BASE_URL", "https://artifact.example/v1")
    monkeypatch.setenv("NOTEWEAVE_ARTIFACT_LLM_MODEL", "artifact-model")
    monkeypatch.setenv("NOTEWEAVE_LLM_API_KEY", "backend-key-must-not-leak")
    monkeypatch.setenv("NOTEWEAVE_LLM_BASE_URL", "https://backend.example/v1")
    monkeypatch.setenv("NOTEWEAVE_LLM_MODEL", "backend-model")

    settings = load_settings()

    assert settings.llm_api_key == "artifact-key"
    assert settings.llm_base_url == "https://artifact.example/v1"
    assert settings.llm_model == "artifact-model"

    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_LLM_API_KEY")
    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_LLM_BASE_URL")
    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_LLM_MODEL")
    isolated_settings = load_settings()

    assert isolated_settings.llm_api_key == ""
    assert isolated_settings.llm_base_url == ""
    assert isolated_settings.llm_model == ""


def test_formal_artifact_worker_routes_should_include_run_resume_and_provider_ack() -> None:
    registered_routes = {route.path for route in app.routes}

    assert "/tasks/{task_id}/run" in registered_routes
    assert "/tasks/{task_id}/resume" in registered_routes
    assert "/callbacks/acquisition/ack" in registered_routes


def test_artifact_worker_formal_routes_should_require_internal_token_when_configured() -> None:
    from app import main as main_module

    original_token = main_module.settings.internal_auth_token
    main_module.settings.internal_auth_token = "worker-shared-secret"
    try:
        unauthorized_request = Request({
            "type": "http",
            "method": "GET",
            "path": "/internal/default-actions",
            "headers": [],
        })
        unauthorized = asyncio.run(
            main_module.protect_debug_routes(
                unauthorized_request,
                lambda _: JSONResponse({"ok": True}),
            )
        )
        authenticated_request = Request({
            "type": "http",
            "method": "GET",
            "path": "/internal/default-actions",
            "headers": [(b"x-noteweave-internal-token", b"worker-shared-secret")],
        })

        async def authenticated_next(_):
            return JSONResponse({"ok": True})

        authenticated = asyncio.run(
            main_module.protect_debug_routes(authenticated_request, authenticated_next)
        )

        assert unauthorized.status_code == 401
        assert authenticated.status_code == 200
    finally:
        main_module.settings.internal_auth_token = original_token


def test_default_action_catalog_should_remain_internal_legacy_surface() -> None:
    registered_debug_routes = {
        route.path
        for route in app.routes
        if getattr(route, "path", "").startswith("/debug/")
    }
    response = debug_list_default_actions()

    assert "/debug/default-actions" not in registered_debug_routes
    assert len(response.actions) >= 6
    assert response.actions[0]["action_key"] == "REPORT"
    assert any(action["action_key"] == "QUIZ" for action in response.actions)
    assert any(action["action_key"] == "WIKI_PAGE" for action in response.actions)
    assert any(action["action_key"] == "RESUME_HIGHLIGHT" for action in response.actions)


def test_artifact_skill_catalog_should_be_available_as_skill_first_debug_surface() -> None:
    response = debug_get_artifact_skills()

    assert len(response.skills) >= 5
    assert response.skills[0]["skill_key"] == "resume_highlight"
    assert any(skill["skill_key"] == "quiz_pack" for skill in response.skills)
    assert any(skill["skill_key"] == "wiki_page" for skill in response.skills)
    bilibili_skill = next(
        skill
        for skill in response.skills
        if skill["skill_key"] == "bilibili_course_note_pdf"
    )
    assert "default_action_key" not in bilibili_skill
    assert "requires_url_input" not in bilibili_skill
    assert "url_input_keys" not in bilibili_skill
    assert bilibili_skill["input_schema"]["properties"]["language"]["default"] == "zh-CN"
    assert bilibili_skill["input_schema"]["properties"]["language"]["oneOf"][0]["const"] == "zh-CN"
    assert bilibili_skill["input_schema"]["properties"]["url"]["type"] == "string"
    assert "video_url" not in bilibili_skill["input_schema"]["properties"]
    assert "bilibili_url" not in bilibili_skill["input_schema"]["properties"]


def test_public_skill_runtime_catalogs_should_hide_legacy_action_binding_fields() -> None:
    graph_response = debug_get_skill_graphs()
    recipe_response = debug_get_prompt_recipes()

    assert len(graph_response.graphs) >= 3
    assert "action_type" not in graph_response.graphs[0]
    assert all("action_type" not in graph for graph in graph_response.graphs)

    assert len(recipe_response.recipes) >= 3
    assert "supported_actions" not in recipe_response.recipes[0]
    assert all("supported_actions" not in recipe for recipe in recipe_response.recipes)


def test_documented_debug_routes_should_match_registered_fastapi_routes() -> None:
    doc_path = (
        Path(__file__).resolve().parents[3]
        / "docs"
        / "产物生成Agent独立模块施工文档.md"
    )
    doc_text = doc_path.read_text(encoding="utf-8")
    documented_routes = set(re.findall(r"/debug/[a-z0-9-]+", doc_text))
    registered_routes = {
        route.path
        for route in app.routes
        if getattr(route, "path", "").startswith("/debug/")
    }

    assert documented_routes == registered_routes


def test_debug_run_task_should_hide_legacy_action_keys_from_public_result() -> None:
    response = debug_run_task(
        ArtifactTaskInput.model_validate(
            {
                "task_id": "debug-run-task-skill-first-test",
                "workspace_id": "ws-artifact-debug-run",
                "target_id": "artifact-debug-run-skill-first",
                "skill_key": "resume_highlight",
                "source_scope": [
                    {
                        "source_id": "src-resume-1",
                        "title": "NoteWeave 产物生成模块",
                        "summary": "统一主链路承接 Skill Graph、Verifier / Repair 和 Capability Union Policy。",
                        "plain_text": (
                            "实现 Controlled Agentic Graph Harness，"
                            "把 Production Action、Style Profile、Skill Graph "
                            "和 Artifact Runtime 统一到异步产物主链路。"
                        ),
                    }
                ],
                "context_snapshot": {"context_snapshot_id": "ctx-debug-run-task-skill-first-test"},
                "control_pack": {
                    "pack_type": "artifact",
                    "target_key": "resume_highlight",
                    "task_neighborhood": "ARTIFACT_RESUME_HIGHLIGHT",
                    "style_constraints": [],
                    "structure_constraints": [],
                    "terminology_policy": [],
                    "forbidden_patterns": [],
                    "evidence_policy": [],
                    "interaction_policy": [],
                    "review_checklist": [],
                    "memory_object_ids": [],
                },
                "input_payload": {
                    "action_key": "",
                    "style_profile_key": "",
                    "prompt_recipe_id": "",
                    "context_snapshot_id": "ctx-debug-run-task-skill-first-test",
                    "generation_brief": "生成适合简历亮点描述的项目总结。",
                    "requested_capabilities": [],
                    "writeback_mode": "NONE",
                },
            }
        )
    )

    assert "action_key" not in response.result["job_snapshot"]
    assert "action_key" not in response.result["result_payload"]["execution_plan"]
    assert not _contains_nested_key(
        response.result["result_payload"],
        "action_resolution",
    )
    assert not _contains_nested_key(
        response.result["result_payload"],
        "requested_action_key",
    )
    assert not _contains_nested_key(
        response.result["result_payload"],
        "resolved_action_key",
    )
    assert not _contains_nested_key(
        response.result["result_payload"],
        "explicit_requested_action_key",
    )
    assert not _contains_nested_key(
        response.result["result_payload"],
        "effective_action_key",
    )
    assert not _contains_nested_key(
        response.result["result_payload"]["output_contract_trace"],
        "action_key",
    )
    assert "contract_checks" in response.result["result_payload"]["output_contract_trace"]
    assert "action_checks" not in response.result["result_payload"]["output_contract_trace"]
    assert not _contains_nested_key(
        response.result["result_payload"].get("capability_union_trace", {}),
        "action_scope",
    )
    assert not _contains_nested_key(
        response.result["result_payload"].get("capability_union_trace", {}),
        "action_basis",
    )
    assert not _contains_nested_key(
        response.result["result_payload"].get("capability_union_trace", {}),
        "skill_graph_basis",
    )


def test_debug_waiting_task_views_should_hide_legacy_action_key_from_task_input() -> None:
    clear_waiting_tasks()
    enqueue_waiting_task(
        task_input=ArtifactTaskInput.model_validate(
            {
                "task_id": "debug-waiting-task-skill-first-test",
                "workspace_id": "ws-artifact-debug-waiting",
                "target_id": "artifact-debug-waiting-skill-first",
                "skill_key": "bilibili_course_note_pdf",
                "source_scope": [],
                "context_snapshot": {"context_snapshot_id": "ctx-debug-waiting-task-skill-first-test"},
                "control_pack": {
                    "pack_type": "artifact",
                    "target_key": "bilibili_course_note_pdf",
                    "task_neighborhood": "ARTIFACT_SKILL_BILIBILI_COURSE_NOTE_PDF",
                    "style_constraints": [],
                    "structure_constraints": ["Export the final result as a lecture note PDF."],
                    "terminology_policy": [],
                    "forbidden_patterns": [],
                    "evidence_policy": ["Keep the note traceable to transcript and screenshots."],
                    "interaction_policy": [],
                    "review_checklist": ["Preserve asynchronous provider waiting semantics."],
                    "memory_object_ids": [],
                },
                "input_payload": {
                    "action_key": "",
                    "style_profile_key": "",
                    "prompt_recipe_id": "",
                    "context_snapshot_id": "ctx-debug-waiting-task-skill-first-test",
                    "generation_brief": "Generate a detailed lecture note PDF from the Bilibili video.",
                    "inputs": {
                        "url": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                        "language": "zh-CN",
                    },
                    "requested_capabilities": [],
                    "writeback_mode": "NONE",
                },
            }
        ),
        unavailable_capabilities=["EXTRACT_TRANSCRIPT"],
        status="WAITING_FOR_PROVIDER",
    )
    waiting_tasks = debug_list_waiting_tasks()
    waiting_detail = debug_get_waiting_task_detail("debug-waiting-task-skill-first-test")

    assert waiting_tasks.tasks
    assert "action_key" not in waiting_tasks.tasks[0]["task_input"]["input_payload"]
    assert "action_key" not in waiting_detail.task["task_input"]["input_payload"]


def test_debug_repository_views_should_exist() -> None:
    registered_debug_routes = {
        route.path
        for route in app.routes
        if getattr(route, "path", "").startswith("/debug/")
    }
    debug_reset_custom_skill_definitions()
    debug_reset_custom_skill_graphs()
    debug_reset_custom_style_profiles()
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    debug_reset_custom_mcp_servers()
    versions = debug_list_artifact_versions()
    retrieval_entries = debug_list_retrieval_entries()
    providers = debug_get_capability_providers()
    provider_discovery = debug_get_capability_provider_discovery()
    provider_health = debug_get_capability_provider_health()
    capability_mappings = debug_get_capability_mappings()
    capability_bindings = debug_get_capability_bindings()
    artifact_skills = debug_get_artifact_skills()
    style_profiles = debug_get_style_profiles()
    skill_definitions = debug_get_skill_definitions()
    skill_graphs = debug_get_skill_graphs()
    prompt_recipes = debug_get_prompt_recipes()
    acquisition_operations = debug_list_acquisition_operations()
    acquisition_receipts = debug_list_acquisition_callback_receipts()
    acquisition_reset = debug_reset_acquisition_runtime()
    action_resolution = debug_resolve_action(
        ArtifactTaskInput.model_validate(
            {
                "task_id": "resolve-action-import-test",
                "workspace_id": "ws-artifact-1",
                "target_id": "artifact-import-test-1",
                "source_scope": [],
                "context_snapshot": {"context_snapshot_id": "ctx-resolve-action-import-test"},
                "control_pack": {
                    "pack_type": "artifact",
                    "target_key": "AUTO",
                    "task_neighborhood": "ARTIFACT_AUTO",
                    "style_constraints": [],
                    "structure_constraints": [],
                    "terminology_policy": [],
                    "forbidden_patterns": [],
                    "evidence_policy": [],
                    "interaction_policy": [],
                    "review_checklist": [],
                    "memory_object_ids": [],
                },
                "input_payload": {
                    "action_key": "",
                    "style_profile_key": "",
                    "prompt_recipe_id": "",
                    "context_snapshot_id": "ctx-resolve-action-import-test",
                    "generation_brief": "输出结构化报告草稿。",
                    "requested_capabilities": [],
                    "writeback_mode": "NONE",
                },
            }
        )
    )
    assert "/debug/resolve-action" not in registered_debug_routes
    assert "/debug/register-custom-action" not in registered_debug_routes
    assert "/debug/reset-custom-actions" not in registered_debug_routes
    custom_action = debug_register_custom_action(
        CustomProductionActionRegistration.model_validate(
            {
                "action_key": "executive_summary",
                "display_name": "管理决策摘要",
                "base_action_key": "report",
                "default_style_profile_key": "executive",
                "default_prompt_recipe_id": "report_executive_writer_v1",
                "artifact_type": "EXECUTIVE_SUMMARY",
                "output_sections": ["决策摘要", "关键证据", "推进建议"],
                "required_phrases": ["Production Action", "Capability Union Policy"],
                "resolver_keywords": ["管理摘要", "决策摘要"],
            }
        )
    )
    custom_style_profile = debug_register_custom_style_profile(
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
    custom_skill_definition = debug_register_custom_skill_definition(
        CustomSkillDefinitionRegistration.model_validate(
            {
                "skill_key": "generic_section_writer_executive",
                "base_skill_key": "generic_section_writer",
            }
        )
    )
    custom_skill_graph = debug_register_custom_skill_graph(
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
                    SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE")
                ],
            }
        )
    )
    custom_prompt_recipe = debug_register_custom_prompt_recipe(
        CustomPromptRecipeRegistration.model_validate(
            {
                "recipe_id": "executive_summary_writer_v2",
                "recipe_name": "Executive Summary Writer",
                "base_recipe_id": "report_executive_writer_v1",
                "supported_actions": ["EXECUTIVE_SUMMARY"],
                "system_intent": "将结构化报告改写成面向管理层的决策摘要。",
                "section_guidance": {
                    "决策摘要": "先给决策结论。",
                    "关键证据": "只保留支撑决策的证据。",
                    "推进建议": "给出下一步推进优先级。",
                },
                "recipe_notes": ["强调管理层决策与风险控制。"],
            }
        )
    )
    custom_mcp_server = debug_register_custom_mcp_server(
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
    custom_mcp_servers = debug_get_custom_mcp_servers()
    custom_mcp_blueprints = debug_get_custom_mcp_blueprints()
    approval_requests = debug_list_capability_approval_requests()
    backend = debug_get_artifact_repository_backend()
    waiting_tasks = debug_list_waiting_tasks()
    assert callable(debug_wake_waiting_task)
    assert callable(debug_get_artifact_version_detail)
    assert callable(debug_get_retrieval_entry_detail)
    assert callable(debug_get_acquisition_operation_detail)
    assert callable(debug_get_acquisition_callback_receipt_detail)
    assert callable(debug_get_writeback_request_detail)
    assert callable(debug_get_writeback_receipt_detail)
    assert callable(debug_get_waiting_task_detail)
    assert callable(debug_get_capability_approval_request_detail)
    assert callable(debug_get_capability_provider_detail)
    assert callable(debug_get_capability_mapping_detail)
    discovery_scan = debug_run_capability_provider_discovery()
    writeback_requests = debug_list_writeback_requests()
    writeback_receipts = debug_list_writeback_receipts()
    writeback_reset = debug_reset_writeback_runtime()

    assert isinstance(versions.versions, list)
    assert isinstance(retrieval_entries.entries, list)
    assert isinstance(providers.providers, list)
    assert isinstance(provider_discovery.snapshot, dict)
    assert isinstance(provider_health.snapshot, dict)
    assert isinstance(capability_mappings.snapshot, dict)
    assert all("supported_actions" not in provider for provider in providers.providers)
    assert all("action_basis" not in provider for provider in providers.providers)
    assert all(
        "supported_actions" not in provider and "action_basis" not in provider
        for provider in provider_discovery.snapshot.get("providers", [])
    )
    assert all(
        "supported_actions" not in provider and "action_basis" not in provider
        for provider in provider_health.snapshot.get("providers", [])
    )
    assert all(
        "supported_actions" not in mapping and "action_basis" not in mapping
        for mapping in capability_mappings.snapshot.get("mappings", [])
    )
    assert isinstance(capability_bindings.bindings, list)
    assert all("allowed_actions" not in binding for binding in capability_bindings.bindings)
    assert isinstance(artifact_skills.skills, list)
    assert isinstance(style_profiles.profiles, list)
    assert isinstance(skill_definitions.skills, list)
    assert isinstance(skill_graphs.graphs, list)
    assert isinstance(prompt_recipes.recipes, list)
    assert isinstance(acquisition_operations.operations, list)
    assert isinstance(acquisition_receipts.receipts, list)
    assert isinstance(acquisition_reset.reset, dict)
    assert isinstance(action_resolution.action_resolution, dict)
    assert isinstance(custom_action.action, dict)
    assert isinstance(custom_style_profile.profile, dict)
    assert isinstance(custom_skill_definition.skill, dict)
    assert isinstance(custom_skill_graph.graph, dict)
    assert isinstance(custom_prompt_recipe.recipe, dict)
    assert "action_type" not in custom_skill_graph.graph
    assert "supported_actions" not in custom_prompt_recipe.recipe
    assert isinstance(custom_mcp_server.server, dict)
    assert isinstance(custom_mcp_servers.servers, list)
    assert isinstance(custom_mcp_blueprints.blueprints, list)
    assert all(
        "allowed_actions" not in tool and "supported_actions" not in tool
        for tool in custom_mcp_server.server.get("tools", [])
    )
    assert all(
        "allowed_actions" not in tool and "supported_actions" not in tool
        for server in custom_mcp_servers.servers
        for tool in server.get("tools", [])
    )
    assert all(
        "allowed_actions" not in tool and "supported_actions" not in tool
        for blueprint in custom_mcp_blueprints.blueprints
        for tool in blueprint.get("tools", [])
    )
    assert custom_mcp_blueprints.blueprints[0]["server_id"] == "custom-bilibili-render-pdf"
    assert custom_mcp_blueprints.blueprints[0]["launch_transport"] == "stdio"
    assert custom_mcp_blueprints.blueprints[0]["blueprint_key"] == "bilibili_render_pdf_v1"
    assert (
        "launch_bilibili_render_pdf_mcp.ps1"
        in " ".join(custom_mcp_blueprints.blueprints[0]["launch_args"])
    )
    assert isinstance(approval_requests.requests, list)
    assert isinstance(backend.backend, dict)
    assert isinstance(waiting_tasks.tasks, list)
    assert isinstance(discovery_scan.snapshot, dict)
    assert all(
        "supported_actions" not in provider and "action_basis" not in provider
        for provider in discovery_scan.snapshot.get("providers", [])
    )
    assert isinstance(writeback_requests.requests, list)
    assert isinstance(writeback_receipts.receipts, list)
    assert isinstance(writeback_reset.reset, dict)
    debug_reset_custom_skill_graphs()
    debug_reset_custom_skill_definitions()
    debug_reset_custom_style_profiles()
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    debug_reset_custom_mcp_servers()


def test_debug_reset_routes_should_clear_runtime_visible_custom_catalogs(tmp_path: Path) -> None:
    storage_path = tmp_path / "artifact-customizations.json"
    try:
        configure_custom_artifact_config_store(storage_path)
        debug_reset_custom_skill_definitions()
        debug_reset_custom_skill_graphs()
        debug_reset_custom_style_profiles()
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()
        debug_reset_custom_mcp_servers()

        debug_register_custom_skill_definition(
            CustomSkillDefinitionRegistration.model_validate(
                {
                    "skill_key": "generic_section_writer_executive",
                    "base_skill_key": "generic_section_writer",
                }
            )
        )
        debug_register_custom_skill_graph(
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
                        SkillGraphEdge(
                            from_node="digest",
                            to_node="write",
                            edge_type="PREREQUISITE",
                        )
                    ],
                }
            )
        )
        debug_register_custom_style_profile(
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

        debug_register_custom_prompt_recipe(
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
        debug_register_custom_action(
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
        debug_register_custom_mcp_server(
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
                        )
                    ],
                }
            )
        )
        store_before_reset = debug_get_custom_artifact_config_store().store

        skills_before_reset = debug_get_skill_definitions().skills
        graphs_before_reset = debug_get_skill_graphs().graphs
        profiles_before_reset = debug_get_style_profiles().profiles
        actions_before_reset = debug_list_default_actions().actions
        recipes_before_reset = debug_get_prompt_recipes().recipes

        assert store_before_reset["custom_skill_definition_count"] == 1
        assert store_before_reset["custom_skill_graph_count"] == 1
        assert store_before_reset["custom_style_profile_count"] == 1
        assert store_before_reset["custom_action_count"] == 1
        assert store_before_reset["custom_prompt_recipe_count"] == 1
        assert store_before_reset["custom_mcp_server_count"] == 1
        assert any(
            skill["skill_key"] == "generic_section_writer_executive"
            for skill in skills_before_reset
        )
        assert any(graph["graph_key"] == "executive_artifact_v1" for graph in graphs_before_reset)
        assert all("action_type" not in graph for graph in graphs_before_reset)
        assert any(profile["profile_key"] == "FOUNDER_PITCH" for profile in profiles_before_reset)
        assert any(action["action_key"] == "RESUME_FOCUS" for action in actions_before_reset)
        assert any(recipe["recipe_id"] == "resume_focus_writer_v1" for recipe in recipes_before_reset)
        assert all("supported_actions" not in recipe for recipe in recipes_before_reset)
        custom_mcp_servers_before_reset = debug_get_custom_mcp_servers().servers
        assert all(
            "allowed_actions" not in tool and "supported_actions" not in tool
            for server in custom_mcp_servers_before_reset
            for tool in server.get("tools", [])
        )

        skill_reset = debug_reset_custom_skill_definitions()
        graph_reset = debug_reset_custom_skill_graphs()
        style_reset = debug_reset_custom_style_profiles()
        action_reset = debug_reset_custom_actions()
        recipe_reset = debug_reset_custom_prompt_recipes()
        mcp_reset = debug_reset_custom_mcp_servers()
        store_after_reset = debug_get_custom_artifact_config_store().store
        skills_after_reset = debug_get_skill_definitions().skills
        graphs_after_reset = debug_get_skill_graphs().graphs
        profiles_after_reset = debug_get_style_profiles().profiles
        actions_after_reset = debug_list_default_actions().actions
        recipes_after_reset = debug_get_prompt_recipes().recipes

        assert skill_reset.reset == {"status": "OK"}
        assert graph_reset.reset == {"status": "OK"}
        assert style_reset.reset == {"status": "OK"}
        assert action_reset.reset == {"status": "OK"}
        assert recipe_reset.reset == {"status": "OK"}
        assert mcp_reset.reset == {"status": "OK"}
        assert store_after_reset["custom_skill_definition_count"] == 0
        assert store_after_reset["custom_skill_graph_count"] == 0
        assert store_after_reset["custom_style_profile_count"] == 0
        assert store_after_reset["custom_action_count"] == 0
        assert store_after_reset["custom_prompt_recipe_count"] == 0
        assert store_after_reset["custom_mcp_server_count"] == 0
        assert not any(
            skill["skill_key"] == "generic_section_writer_executive"
            for skill in skills_after_reset
        )
        assert not any(graph["graph_key"] == "executive_artifact_v1" for graph in graphs_after_reset)
        assert all("action_type" not in graph for graph in graphs_after_reset)
        assert not any(profile["profile_key"] == "FOUNDER_PITCH" for profile in profiles_after_reset)
        assert not any(action["action_key"] == "RESUME_FOCUS" for action in actions_after_reset)
        assert not any(recipe["recipe_id"] == "resume_focus_writer_v1" for recipe in recipes_after_reset)
        assert all("supported_actions" not in recipe for recipe in recipes_after_reset)
        assert any(skill["skill_key"] == "workspace_material_digest" for skill in skills_after_reset)
        assert any(graph["graph_key"] == "generic_artifact_v1" for graph in graphs_after_reset)
        assert any(profile["profile_key"] == "EXECUTIVE" for profile in profiles_after_reset)
        assert any(action["action_key"] == "RESUME_HIGHLIGHT" for action in actions_after_reset)
    finally:
        configure_custom_artifact_config_store(None)
        debug_reset_custom_skill_definitions()
        debug_reset_custom_skill_graphs()
        debug_reset_custom_style_profiles()
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()
        debug_reset_custom_mcp_servers()
