from __future__ import annotations

import pytest

import app.action_compat as action_compat_module
import app.compiler as compiler_module
import app.intent_compiler as intent_compiler_module
import app.registry as registry_module
import app.runner as runner_module
from app.action_compat import resolve_action_compatibility, resolve_action_key_from_skill_key
from app.compiler import build_execution_plan
from app.artifact_skill_catalog import CATALOG_DIGEST, list_artifact_skill_definitions, resolve_artifact_skill_definition
from app.content_runtime import build_canonical_content_objects
from app.composer import render_markdown, resolve_artifact_title
from app.intent_compiler import compile_execution_spec
from app.llm_client import FakeLlmClient
from app.generation_runtime import ArtifactConfigurationRequiredError
from app.registry import resolve_skill_definition
from app.models import (
    ArtifactExecutionPlan,
    ArtifactJobSnapshot,
    ArtifactSectionDraft,
    ArtifactSkillDefinition,
    ArtifactTaskInput,
    ArtifactTaskResult,
    ArtifactVersionSnapshot,
    ArtifactVerificationResult,
    CustomProductionActionRegistration,
    CustomPromptRecipeRegistration,
    CustomSkillDefinitionRegistration,
    CustomSkillGraphTemplateRegistration,
    CustomStyleProfileRegistration,
    SkillGraphEdge,
    SkillGraphNode,
    SkillGraphTemplate,
    SkillDefinition,
)
from app.runner import run_artifact_task
from app.skill_graph import _verify_and_repair_skill_output
from app.verifier import build_output_contract_trace, verify_artifact_output
from app.main import (
    debug_get_capability_bindings,
    debug_get_prompt_recipes,
    debug_get_skill_definitions,
    debug_get_skill_graphs,
    debug_get_style_profiles,
    debug_list_default_actions,
    debug_register_custom_action,
    debug_register_custom_prompt_recipe,
    debug_register_custom_skill_definition,
    debug_register_custom_skill_graph,
    debug_register_custom_style_profile,
    debug_reset_custom_actions,
    debug_reset_custom_prompt_recipes,
    debug_reset_custom_skill_definitions,
    debug_reset_custom_skill_graphs,
    debug_reset_custom_style_profiles,
    debug_resolve_action,
)


def _build_task_input(
    *,
    task_id: str,
    target_id: str,
    action_key: str,
    skill_key: str = "",
    style_profile_key: str,
    structure_constraints: list[str],
    generation_brief: str,
    user_requirement: str = "",
    source_scope: list[dict[str, object]],
    requested_capabilities: list[str] | None = None,
    prompt_recipe_id: str = "",
    inputs: dict[str, object] | None = None,
) -> ArtifactTaskInput:
    normalized_action_key = action_key.strip().upper()
    normalized_skill_key = skill_key.strip().lower()
    control_pack_target_key = normalized_skill_key or normalized_action_key
    control_pack_task_neighborhood = (
        f"ARTIFACT_SKILL_{normalized_skill_key.upper()}"
        if normalized_skill_key
        else f"ARTIFACT_{normalized_action_key}"
    )
    return ArtifactTaskInput.model_validate(
        {
            "task_id": task_id,
            "workspace_id": "ws-artifact-1",
            "target_id": target_id,
            "source_scope": source_scope,
            "context_snapshot": {"context_snapshot_id": f"ctx-{task_id}"},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": control_pack_target_key,
                "task_neighborhood": control_pack_task_neighborhood,
                "style_constraints": ["强调工程抽象、证据约束和可扩展性。"],
                "structure_constraints": structure_constraints,
                "terminology_policy": ["保留关键英文术语原文。"],
                "forbidden_patterns": ["失控"],
                "evidence_policy": ["所有输出都必须能回溯到工作台资料。"],
                "interaction_policy": [],
                "review_checklist": ["保留 Artifact Job / Artifact Version 语义。"],
                "memory_object_ids": ["mem-1"],
            },
            "input_payload": {
                "skill_key": skill_key,
                "action_key": action_key,
                "style_profile_key": style_profile_key,
                "prompt_recipe_id": prompt_recipe_id,
                "context_snapshot_id": f"ctx-{task_id}",
                "user_requirement": user_requirement,
                "generation_brief": generation_brief,
                "inputs": inputs or {},
                "requested_capabilities": requested_capabilities or [],
                "writeback_mode": "NONE",
            },
        }
    )


def _build_resume_task_input(
    requested_capabilities: list[str] | None = None,
) -> ArtifactTaskInput:
    return _build_task_input(
        task_id="artifact-job-1",
        target_id="artifact-resume-1",
        action_key="resume_highlight",
        skill_key="resume_highlight",
        style_profile_key="interview",
        structure_constraints=["输出一句话定位、亮点条目和关键词。"],
        user_requirement="强调 Controlled Agentic Graph Harness、Schema-Gated Skill Graph Runtime 和 MCP 集成。",
        generation_brief=(
            "突出 Controlled Agentic Graph Harness、Schema-Gated Skill Graph Runtime、"
            "Capability Union Policy 和 Verifier / Repair。"
        ),
        source_scope=[
            {
                "source_id": "src-1",
                "title": "产物生成 Agent 编排升级设计",
                "summary": (
                    "设计并实现受控式异步产物生成 Agent，采用 Controlled Agentic Graph "
                    "Harness 主链路，并以 Schema-Gated Skill Graph Runtime 约束执行计划。"
                ),
            },
            {
                "source_id": "src-2",
                "title": "用户自定义 MCP 权限治理",
                "summary": (
                    "引入 Capability Union Policy 评估 Action、Skill Graph 与自定义 MCP "
                    "组合权限风险，避免系统演化为失控的通用 Agent 平台。"
                ),
            },
        ],
        requested_capabilities=requested_capabilities,
    )


def test_artifact_worker_should_preserve_java_sample_text_as_generation_source() -> None:
    task_input = _build_resume_task_input()
    task_input.source_scope[0].sample_text = "Unique source fact: dispatch is protected by an outbox claim lease."

    ccos = build_canonical_content_objects(task_input)

    assert "outbox claim lease" in ccos[0].plain_text


def test_run_artifact_task_should_use_configured_llm_and_trace_generation_mode(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = FakeLlmClient(
        {
            "artifact.generate": r"""
            {
              "sections": [
                {
                  "heading": "一句话定位",
                  "body": "基于冻结资料范围实现可审计的异步产物生成闭环。",
                  "source_refs": ["产物生成 Agent 编排升级设计"]
                },
                {
                  "heading": "简历亮点",
                  "body": "- 使用 Controlled Agentic Graph Harness 与 Schema-Gated Skill Graph Runtime 约束执行。\n- 通过 Capability Union Policy 和 Verifier / Repair 管理能力组合与质量。",
                  "source_refs": ["产物生成 Agent 编排升级设计", "不存在的来源"]
                },
                {
                  "heading": "关键词",
                  "body": "Artifact Job / Artifact Version",
                  "source_refs": ["产物生成 Agent 编排升级设计"]
                }
              ]
            }
            """
        }
    )
    monkeypatch.setattr(runner_module, "build_default_llm_client", lambda: fake)

    _, result = run_artifact_task(_build_resume_task_input())

    assert result.result_payload["generation_trace"] == {
        "mode": "LLM_GENERATION",
        "provider": "fake",
        "model": "fake-artifact-model",
        "attempted": True,
        "applied": True,
        "fallback_reason": "",
        "generated_section_count": 3,
        "source_count": 3,
    }
    assert "基于冻结资料范围" in result.result_payload["markdown"]
    generated_sections = result.result_payload["sections"]
    assert generated_sections[1]["source_refs"] == ["产物生成 Agent 编排升级设计"]
    assert fake.calls and fake.calls[0][0] == "artifact.generate"


def test_run_artifact_task_should_fail_closed_without_llm(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runner_module, "build_default_llm_client", lambda: None)

    with pytest.raises(ArtifactConfigurationRequiredError) as error:
        run_artifact_task(_build_resume_task_input())

    assert error.value.error_code == "CONFIGURATION_REQUIRED"
    assert "not configured" in str(error.value)


def test_run_artifact_task_should_fail_closed_on_invalid_llm_output(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        runner_module,
        "build_default_llm_client",
        lambda: FakeLlmClient({"artifact.generate": '{"sections": []}'}),
    )

    with pytest.raises(ArtifactConfigurationRequiredError) as error:
        run_artifact_task(_build_resume_task_input())

    assert error.value.error_code == "CONFIGURATION_REQUIRED"
    assert "no usable content" in str(error.value)


def test_failed_final_content_gate_never_invokes_renderer(monkeypatch: pytest.MonkeyPatch) -> None:
    task_input = _build_resume_task_input()
    render_calls: list[str] = []
    monkeypatch.setattr(runner_module, "export_artifact_if_required",
                        lambda **kwargs: render_calls.append("render"))
    monkeypatch.setattr(runner_module, "verify_artifact_output",
                        lambda *args: ArtifactVerificationResult(
                            status="FAIL", failed_checks=["missing evidence"]))

    with pytest.raises(runner_module.ArtifactOutputContractViolationError):
        run_artifact_task(task_input)

    assert render_calls == []


def test_candidate_is_bound_to_task_snapshot_and_markdown() -> None:
    task_input = _build_resume_task_input()
    task_input.input_snapshot_id = "snapshot-fixed"
    task_input.catalog_digest = CATALOG_DIGEST
    _, result = run_artifact_task(task_input)
    candidate = result.result_payload["candidate"]

    assert candidate["task_id"] == task_input.task_id
    assert candidate["input_snapshot_id"] == "snapshot-fixed"
    assert candidate["catalog_digest"] == CATALOG_DIGEST
    assert candidate["content_sha256"]
    assert candidate["candidate_id"]
    assert result.version_snapshot.version_id == ""
    assert result.result_payload["artifact_commit"]["commit_status"] == "DEFERRED_TO_HOST"
    assert "writeback_request" not in result.result_payload


def test_run_artifact_task_without_llm_or_source_content_should_be_blocked(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(runner_module, "build_default_llm_client", lambda: None)
    task_input = _build_task_input(
        task_id="artifact-no-content",
        target_id="artifact-no-content",
        action_key="report",
        skill_key="report_draft",
        style_profile_key="default",
        structure_constraints=[],
        generation_brief="",
        source_scope=[],
    )

    with pytest.raises(ArtifactConfigurationRequiredError) as error:
        run_artifact_task(task_input)

    assert error.value.error_code == "CONFIGURATION_REQUIRED"


def _resolve_builtin_skill_key(action_key: str) -> str:
    return {
        "report": "report_draft",
        "faq": "faq_draft",
        "quiz": "quiz_pack",
        "study_guide": "study_guide",
        "wiki_page": "wiki_page",
        "structured_note": "structured_note",
        "video_summary": "video_summary",
        "audio_minutes": "audio_minutes",
        "course_notes": "course_notes",
    }[action_key]


def _build_generic_task_input(
    action_key: str,
    style_profile_key: str = "default",
) -> ArtifactTaskInput:
    source_scope = [
        {
            "source_id": "src-1",
            "title": "产物模块总设计",
            "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
        },
        {
            "source_id": "src-2",
            "title": "技能图运行时",
            "summary": "通过 Schema Gate、Skill Graph 和 Local Repair 控制生成过程。",
        },
    ]

    action_payloads = {
        "report": {
            "target_id": "artifact-report-1",
            "structure_constraints": ["保留问题、证据、建议三段。"],
            "generation_brief": "输出结构化报告草稿，适合项目方案沉淀。",
        },
        "faq": {
            "target_id": "artifact-faq-1",
            "structure_constraints": ["输出问题集、标准回答、使用说明。"],
            "generation_brief": "把产物生成模块整理成常见问题与标准回答。",
        },
        "quiz": {
            "target_id": "artifact-quiz-1",
            "structure_constraints": ["Output quiz goals, questions, answers, and scoring criteria."],
            "generation_brief": "Generate a verifiable quiz draft from the artifact architecture materials.",
        },
        "study_guide": {
            "target_id": "artifact-study-guide-1",
            "structure_constraints": ["输出学习目标、核心概念、练习路径。"],
            "generation_brief": "面向新同学生成受控式产物生成模块学习指南。",
        },
        "wiki_page": {
            "target_id": "artifact-wiki-page-1",
            "structure_constraints": ["输出概览、关键机制、相关页面。"],
            "generation_brief": "沉淀受控式产物生成模块的 wiki 草稿。",
        },
        "structured_note": {
            "target_id": "artifact-note-1",
            "structure_constraints": ["输出主题快照、关键摘录、后续问题。"],
            "generation_brief": "生成结构化笔记，方便后续二次整理。",
        },
    }
    payload = action_payloads[action_key]
    skill_key = _resolve_builtin_skill_key(action_key)
    return _build_task_input(
        task_id=f"{action_key}-job",
        target_id=payload["target_id"],
        action_key="",
        skill_key=skill_key,
        style_profile_key=style_profile_key,
        structure_constraints=payload["structure_constraints"],
        generation_brief=payload["generation_brief"],
        source_scope=source_scope,
    )


def _build_media_task_input(action_key: str) -> ArtifactTaskInput:
    payloads = {
        "video_summary": {
            "target_id": "artifact-video-summary-1",
            "style_profile_key": "default",
            "structure_constraints": ["输出一句话总结、时间线摘要、核心观点、关键概念、可沉淀要点。"],
            "generation_brief": "基于视频资料生成结构化视频总结。",
            "source_scope": [
                {
                    "source_id": "src-video-1",
                    "title": "B 站产物生成架构讲解",
                    "summary": "视频链接，介绍 Controlled Agentic Graph Harness 与 Skill Graph Runtime。",
                    "source_type": "URL",
                    "source_uri": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                }
            ],
        },
        "audio_minutes": {
            "target_id": "artifact-audio-minutes-1",
            "style_profile_key": "default",
            "structure_constraints": ["输出总体摘要、主要议题、关键结论、行动项、待确认问题。"],
            "generation_brief": "基于音频资料生成结构化会议纪要。",
            "source_scope": [
                {
                    "source_id": "src-audio-1",
                    "title": "组会录音",
                    "summary": "音频文件，包含产物生成模块的设计讨论与后续行动安排。",
                    "source_type": "AUDIO_FILE",
                }
            ],
        },
        "course_notes": {
            "target_id": "artifact-course-notes-1",
            "style_profile_key": "teaching",
            "structure_constraints": ["输出课程概要、知识点、重点难点、复习题。"],
            "generation_brief": "基于课程视频资料生成课程笔记。",
            "source_scope": [
                {
                    "source_id": "src-course-1",
                    "title": "受控式 Agent 编排课程",
                    "summary": "课程视频文件，讲解 Capability Layer、Schema Gate 和 Local Repair。",
                    "source_type": "VIDEO_FILE",
                }
            ],
        },
    }
    payload = payloads[action_key]
    skill_key = _resolve_builtin_skill_key(action_key)
    return _build_task_input(
        task_id=f"{action_key}-job",
        target_id=payload["target_id"],
        action_key="",
        skill_key=skill_key,
        style_profile_key=payload["style_profile_key"],
        structure_constraints=payload["structure_constraints"],
        generation_brief=payload["generation_brief"],
        source_scope=payload["source_scope"],
    )


def _build_mixed_context_task_input() -> ArtifactTaskInput:
    return _build_task_input(
        task_id="mixed-context-job",
        target_id="artifact-mixed-context-1",
        action_key="",
        skill_key="study_guide",
        style_profile_key="teaching",
        structure_constraints=["输出学习目标、关键概念和练习路径。"],
        generation_brief="把文档、知识卡片和网页资料整理成可复用的学习指南。",
        source_scope=[
            {
                "source_id": "src-doc-1",
                "title": "受控式产物生成架构说明",
                "summary": "统一主链路承接 Production Action、Style Profile、Skill Graph 和 Artifact Runtime。",
                "source_type": "DOCUMENT_TEXT",
            },
            {
                "source_id": "src-card-1",
                "title": "能力治理卡片",
                "summary": "强调 Capability Union Policy、Approval Gate 和 Evidence Gate 的边界控制。",
                "source_type": "CARD",
                "related_source_ids": ["src-doc-1"],
                "source_metadata": {"card_type": "INSIGHT", "tag_count": 3},
            },
            {
                "source_id": "src-url-1",
                "title": "架构综述网页",
                "summary": "网页内容补充了 Schema-Gated Skill Graph Runtime 与 Verifier / Repair 的协同方式。",
                "source_type": "URL",
                "source_uri": "https://example.com/noteweave-artifact-architecture",
                "source_metadata": {"platform": "WEB", "content_type": "article"},
            },
        ],
    )


def test_build_execution_plan_should_compile_resume_highlight_graph() -> None:
    task_input = _build_resume_task_input()

    plan = build_execution_plan(task_input)

    assert isinstance(plan, ArtifactExecutionPlan)
    assert plan.skill_key == "resume_highlight"
    assert plan.action_key == "RESUME_HIGHLIGHT"
    assert plan.action_resolution.reason_code == "explicit_action_requested"
    assert plan.action_display_name == "简历亮点描述"
    assert plan.style_profile_key == "INTERVIEW"
    assert plan.skill_graph_key == "resume_highlight_v1"
    assert plan.prompt_recipe.recipe_id == "resume_highlight_writer_v1"
    assert plan.prompt_recipe.recipe_name == "Resume Highlight Writer"
    assert plan.prompt_recipe.generation_mode == "HIGHLIGHT_EXTRACTION"
    assert plan.schema_gate_status == "PASSED"
    assert plan.capability_union_policy.decision == "ALLOW"
    assert "READ_WORKSPACE_DOC" in plan.required_capabilities
    assert plan.lazy_loaded_capabilities == [
        "READ_WORKSPACE_DOC",
        "GENERATE_STRUCTURED_TEXT",
        "VERIFY_OUTPUT",
    ]
    assert plan.deferred_capabilities == []
    assert plan.content_acquisition_plan.primary_strategy == "MIXED_CONTEXT_FUSION"
    assert plan.content_acquisition_plan.steps[0].step_type == "COLLECT_WORKSPACE_MATERIALS"
    assert plan.approval_gate.decision == "NOT_REQUIRED"
    assert plan.evidence_gate.decision == "ENFORCE"
    assert plan.writeback_gate.decision == "SKIP"
    assert plan.execution_spec.skill_key == "resume_highlight"
    assert plan.execution_spec.resolved_action_key == "RESUME_HIGHLIGHT"
    assert "MCP 集成" in plan.execution_spec.goal
    assert "resume graph binding: source_digest -> fact_extractor -> impact_normalizer -> bullet_writer -> verifier -> repair" in plan.execution_spec.notes
    assert plan.runtime_plan.graph_key == "resume_highlight_v1"
    assert plan.runtime_plan.activated_nodes == [
        "workspace_material_digest",
        "resume_highlight_extractor",
        "resume_impact_normalizer",
        "resume_bullet_writer",
        "resume_verifier",
        "resume_local_repair",
    ]
    assert "READ_WORKSPACE_DOC" in plan.runtime_plan.allowed_capabilities
    assert plan.runtime_plan.output_contract == [
        "一句话定位",
        "简历亮点",
        "关键词",
    ]
    assert "Schema-gated execution plan must resolve known action, style, and skill graph." in plan.schema_gate_rules
    assert [node.skill_key for node in plan.node_sequence] == [
        "workspace_material_digest",
        "resume_highlight_extractor",
        "resume_impact_normalizer",
        "resume_bullet_writer",
        "resume_verifier",
        "resume_local_repair",
    ]


def test_compile_execution_spec_should_structure_resume_requirement_into_focus_style_and_constraints() -> None:
    task_input = _build_task_input(
        task_id="artifact-resume-spec-1",
        target_id="artifact-resume-spec-target-1",
        action_key="AUTO",
        skill_key="resume_highlight",
        style_profile_key="interview",
        structure_constraints=["输出一句话定位、亮点条目和关键词。"],
        generation_brief="",
        user_requirement=(
            "强调异步任务编排、system MCP 集成、provider waiting / resume，"
            "适合校招简历，动词开头，尽量量化，控制在3条以内。"
        ),
        source_scope=[
            {
                "source_id": "src-1",
                "title": "Resume Runtime Notes",
                "summary": "用于验证 execution spec 会把用户需求编译成结构化 focus/style/constraint。",
            }
        ],
        inputs={"language": "zh-CN"},
    )

    spec = compile_execution_spec(task_input)

    assert spec.skill_key == "resume_highlight"
    assert spec.resolved_action_key == "RESUME_HIGHLIGHT"
    assert spec.audience == "INTERVIEWER"
    assert spec.focus_points == [
        "异步任务编排",
        "system MCP 集成",
        "provider waiting / resume",
    ]
    assert "动词开头" in spec.style_directives
    assert "尽量量化" in spec.style_directives
    assert "language=zh-CN" in spec.style_directives
    assert "max_bullets=3" in spec.constraints
    assert "execution spec compiled from user requirement" in spec.notes


def test_run_artifact_task_should_apply_execution_spec_focus_points_to_resume_output() -> None:
    task_input = _build_task_input(
        task_id="artifact-resume-focus-1",
        target_id="artifact-resume-focus-target-1",
        action_key="AUTO",
        skill_key="resume_highlight",
        style_profile_key="interview",
        structure_constraints=["输出一句话定位、亮点条目和关键词。"],
        generation_brief="",
        user_requirement=(
            "强调异步任务编排、system MCP 集成、provider waiting / resume，"
            "适合校招简历，动词开头，尽量量化，控制在3条以内。"
        ),
        source_scope=[
            {
                "source_id": "src-1",
                "title": "Artifact Runtime Brain",
                "summary": "Python runtime brain 统一处理 skill graph、verifier / repair 与异步恢复。",
            },
            {
                "source_id": "src-2",
                "title": "System MCP Integration",
                "summary": "system MCP only，provider waiting / resume 与 callback contract 统一纳入控制面。",
            },
        ],
        inputs={"language": "zh-CN"},
    )

    _, result = run_artifact_task(task_input)

    markdown = result.result_payload["markdown"]
    assert "异步任务编排" in markdown
    assert "system MCP 集成" in markdown
    assert "provider waiting / resume" in markdown

    highlight_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_highlight_extractor"
    )
    normalizer_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_impact_normalizer"
    )
    verifier_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_verifier"
    )
    repair_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_local_repair"
    )
    assert "user focus points applied to resume highlight extraction" in highlight_trace["verification_checks"]
    assert "异步任务编排" in highlight_trace["output_summary"]
    assert normalizer_trace["verification_status"] == "PASS"
    assert "resume impact statements normalized" in normalizer_trace["verification_checks"]
    assert verifier_trace["verification_status"] == "PASS"
    assert "resume verifier inspected bullet count, focus coverage, and required phrases" in verifier_trace["verification_checks"]
    assert repair_trace["verification_status"] == "PASS_WITH_REPAIR"
    assert "resume local repair confirmed verifier gaps are closed" in repair_trace["verification_checks"]
    assert repair_trace["repair_actions"]
    assert repair_trace["repaired"] is True

    assert any(
        check["label"] == "resume focus point coverage"
        and check["status"] == "PASS"
        and check["metadata"]["matched_focus_point_count"] == 3
        for check in result.result_payload["output_contract_trace"]["action_checks"]
    )


def test_build_execution_plan_should_bind_skill_key_when_action_is_auto() -> None:
    task_input = _build_task_input(
        task_id="artifact-wiki-skill-1",
        target_id="artifact-wiki-1",
        action_key="AUTO",
        skill_key="wiki_page",
        style_profile_key="wiki",
        structure_constraints=["输出概览、关键机制、相关页面。"],
        generation_brief="",
        user_requirement="沉淀为 Wiki 页面草稿，强调定义、关键机制和引用提示。",
        source_scope=[
            {
                "source_id": "src-1",
                "title": "Wiki 架构资料",
                "summary": "解释工作台 Wiki 的定义、机制、回链和沉淀方式。",
            }
        ],
    )

    plan = build_execution_plan(task_input)

    assert plan.skill_key == "wiki_page"
    assert plan.action_key == "WIKI_PAGE"
    assert plan.action_resolution.resolution_mode == "SKILL_BINDING"
    assert plan.execution_spec.skill_key == "wiki_page"
    assert plan.execution_spec.resolved_action_key == "WIKI_PAGE"
    assert plan.execution_spec.goal == "沉淀为 Wiki 页面草稿，强调定义、关键机制和引用提示。"


def test_build_execution_plan_should_bind_builtin_report_skill_when_generic_helper_uses_skill_first() -> None:
    task_input = _build_generic_task_input("report")

    plan = build_execution_plan(task_input)

    assert task_input.input_payload.skill_key == "report_draft"
    assert task_input.input_payload.action_key == ""
    assert task_input.control_pack.target_key == "report_draft"
    assert task_input.control_pack.task_neighborhood == "ARTIFACT_SKILL_REPORT_DRAFT"
    assert plan.skill_key == "report_draft"
    assert plan.action_key == "REPORT"
    assert plan.action_resolution.resolution_mode == "SKILL_BINDING"
    assert plan.action_resolution.reason_code == "skill_key_bound_action"


def test_build_execution_plan_should_default_missing_action_key_to_auto_for_skill_first_request() -> None:
    task_input = ArtifactTaskInput.model_validate(
        {
            "task_id": "artifact-skill-only-1",
            "workspace_id": "ws-artifact-1",
            "target_id": "artifact-skill-only-target-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Resume Runtime Notes",
                    "summary": "Skill-first request should not require an explicit action key in worker input.",
                }
            ],
            "context_snapshot": {"context_snapshot_id": "ctx-artifact-skill-only-1"},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": "RESUME_HIGHLIGHT",
                "task_neighborhood": "ARTIFACT_RESUME_HIGHLIGHT",
                "style_constraints": ["强调工程抽象与运行时约束。"],
                "structure_constraints": ["输出一句话定位、亮点条目和关键词。"],
                "terminology_policy": ["保留关键英文术语原文。"],
                "forbidden_patterns": ["失控"],
                "evidence_policy": ["所有输出都必须能回溯到工作台资料。"],
                "interaction_policy": [],
                "review_checklist": ["保留 Artifact Job / Artifact Version 语义。"],
                "memory_object_ids": ["mem-1"],
            },
            "input_payload": {
                "skill_key": "resume_highlight",
                "style_profile_key": "interview",
                "context_snapshot_id": "ctx-artifact-skill-only-1",
                "user_requirement": "强调 skill-first runtime、Schema Gate 与 Artifact Version。",
                "generation_brief": "",
                "inputs": {"language": "zh-CN"},
                "requested_capabilities": [],
                "writeback_mode": "NONE",
            },
        }
    )

    assert task_input.input_payload.action_key == ""

    plan = build_execution_plan(task_input)

    assert plan.skill_key == "resume_highlight"
    assert plan.action_key == "RESUME_HIGHLIGHT"
    assert plan.action_resolution.requested_action_key == "AUTO"
    assert plan.action_resolution.resolution_mode == "SKILL_BINDING"
    assert plan.execution_spec.requested_action_key == "AUTO"
    assert plan.execution_spec.resolved_action_key == "RESUME_HIGHLIGHT"


def test_resolve_action_compatibility_should_unify_skill_first_defaults() -> None:
    compatibility = resolve_action_compatibility(_build_generic_task_input("report"))

    assert compatibility.skill_key == "report_draft"
    assert compatibility.requested_action_key == "AUTO"
    assert compatibility.explicit_requested_action_key == ""
    assert compatibility.resolved_from_skill == "REPORT"
    assert compatibility.effective_action_key == "REPORT"
    assert compatibility.legacy_action_conflict is False


def test_build_execution_plan_should_prefer_skill_binding_over_conflicting_legacy_action() -> None:
    task_input = _build_task_input(
        task_id="artifact-skill-conflict-1",
        target_id="artifact-skill-conflict-target-1",
        action_key="audio_minutes",
        skill_key="resume_highlight",
        style_profile_key="interview",
        structure_constraints=["输出一句话定位、亮点条目和关键词。"],
        generation_brief="强调受控生成架构与运行时约束。",
        user_requirement="强调 skill-first runtime 与版本化结果。",
        source_scope=[
            {
                "source_id": "src-1",
                "title": "Resume Runtime Notes",
                "summary": "Skill-first request should override conflicting legacy action keys.",
            }
        ],
    )

    plan = build_execution_plan(task_input)

    assert plan.skill_key == "resume_highlight"
    assert plan.action_key == "RESUME_HIGHLIGHT"
    assert plan.action_resolution.requested_action_key == "AUDIO_MINUTES"
    assert plan.action_resolution.resolution_mode == "SKILL_BINDING"
    assert plan.action_resolution.reason_code == "skill_key_overrode_conflicting_action"
    assert plan.execution_spec.requested_action_key == "AUDIO_MINUTES"
    assert plan.execution_spec.resolved_action_key == "RESUME_HIGHLIGHT"
    assert plan.content_acquisition_plan.primary_strategy == "WORKSPACE_SOURCE_DIGEST"
    assert plan.content_acquisition_plan.required_capabilities == []


def test_resolve_action_compatibility_should_override_conflicting_legacy_action() -> None:
    compatibility = resolve_action_compatibility(
        _build_task_input(
            task_id="artifact-skill-conflict-compat-1",
            target_id="artifact-skill-conflict-compat-target-1",
            action_key="audio_minutes",
            skill_key="resume_highlight",
            style_profile_key="interview",
            structure_constraints=["输出一句话定位、亮点条目和关键词。"],
            generation_brief="强调受控生成架构与运行时约束。",
            user_requirement="强调 skill-first runtime 与版本化结果。",
            source_scope=[
                {
                    "source_id": "src-1",
                    "title": "Resume Runtime Notes",
                    "summary": "Skill-first request should override conflicting legacy action keys.",
                }
            ],
        )
    )

    assert compatibility.skill_key == "resume_highlight"
    assert compatibility.requested_action_key == "AUDIO_MINUTES"
    assert compatibility.explicit_requested_action_key == "AUDIO_MINUTES"
    assert compatibility.resolved_from_skill == "RESUME_HIGHLIGHT"
    assert compatibility.effective_action_key == "RESUME_HIGHLIGHT"
    assert compatibility.legacy_action_conflict is True


def test_build_execution_plan_should_bind_builtin_video_summary_skill_when_media_helper_uses_skill_first() -> None:
    task_input = _build_media_task_input("video_summary")

    plan = build_execution_plan(task_input)

    assert task_input.input_payload.skill_key == "video_summary"
    assert task_input.input_payload.action_key == ""
    assert task_input.control_pack.target_key == "video_summary"
    assert task_input.control_pack.task_neighborhood == "ARTIFACT_SKILL_VIDEO_SUMMARY"
    assert plan.skill_key == "video_summary"
    assert plan.action_key == "VIDEO_SUMMARY"
    assert plan.action_resolution.resolution_mode == "SKILL_BINDING"
    assert plan.content_acquisition_plan.primary_strategy == "VIDEO_TRANSCRIPT_PIPELINE"


def test_build_execution_plan_should_allow_skill_first_bilibili_request_with_url_input_only() -> None:
    task_input = _build_task_input(
        task_id="artifact-bilibili-skill-input-url-1",
        target_id="artifact-bilibili-skill-input-url-target-1",
        action_key="AUTO",
        skill_key="bilibili_course_note_pdf",
        style_profile_key="teaching",
        structure_constraints=["输出课程概要、知识点、重点难点、复习题。"],
        generation_brief="基于这个 B 站链接生成讲义 PDF。",
        user_requirement="整理成图文讲义并保留章节结构。",
        source_scope=[],
        inputs={"url": "https://www.bilibili.com/video/BV1NoteWeaveDemo"},
    )

    plan = build_execution_plan(task_input)

    assert plan.skill_key == "bilibili_course_note_pdf"
    assert plan.action_key == "COURSE_NOTES"
    assert plan.content_acquisition_plan.primary_strategy == "VIDEO_TRANSCRIPT_PIPELINE"
    assert plan.content_acquisition_plan.route_summary == {"VIDEO_URL": 1}
    assert plan.content_acquisition_plan.required_capabilities == ["EXTRACT_TRANSCRIPT"]
    assert plan.content_acquisition_plan.source_plans[0].source_id == "input-url-1"
    assert plan.content_acquisition_plan.source_plans[0].adapter_route == "VIDEO_URL"


def test_build_canonical_content_objects_should_materialize_skill_input_url_as_virtual_source() -> None:
    task_input = _build_task_input(
        task_id="artifact-video-summary-input-url-1",
        target_id="artifact-video-summary-input-url-target-1",
        action_key="AUTO",
        skill_key="video_summary",
        style_profile_key="default",
        structure_constraints=["输出一句话总结、时间线摘要、核心观点。"],
        generation_brief="总结这个视频。",
        user_requirement="强调核心观点和关键概念。",
        source_scope=[],
        inputs={"video_url": "https://www.bilibili.com/video/BV1NoteWeaveDemo"},
    )

    objects = build_canonical_content_objects(task_input)

    assert any(cco.kind == "TRANSCRIPT" for cco in objects)
    assert any(cco.metadata.get("source_uri") == "https://www.bilibili.com/video/BV1NoteWeaveDemo" for cco in objects)
    assert any("source:input-url-1" in cco.source_trace for cco in objects)


def test_artifact_skill_catalog_should_expose_builtin_skill_metadata() -> None:
    skills = list_artifact_skill_definitions()

    assert any(skill.skill_key == "resume_highlight" for skill in skills)
    assert any(skill.skill_key == "bilibili_course_note_pdf" for skill in skills)
    assert any(skill.skill_key == "mindmap_from_workspace" for skill in skills)
    bilibili_skill = resolve_artifact_skill_definition("bilibili_course_note_pdf")
    course_notes_skill = resolve_artifact_skill_definition("course_notes")
    video_summary_skill = resolve_artifact_skill_definition("video_summary")
    assert "default_action_key" not in bilibili_skill.model_dump()
    assert "default_action_key" not in course_notes_skill.model_dump()
    assert "default_action_key" not in video_summary_skill.model_dump()
    assert resolve_action_key_from_skill_key("bilibili_course_note_pdf") == "COURSE_NOTES"
    assert "requires_url_input" not in bilibili_skill.model_dump()
    assert "url_input_keys" not in bilibili_skill.model_dump()
    assert bilibili_skill.requires_url_input is True
    assert bilibili_skill.url_input_keys == ["url", "video_url", "bilibili_url"]
    assert bilibili_skill.input_schema["required"] == ["url"]
    assert bilibili_skill.input_schema["properties"]["language"]["default"] == "zh-CN"
    assert bilibili_skill.input_schema["properties"]["language"]["oneOf"][1]["const"] == "en"
    assert resolve_action_key_from_skill_key("course_notes") == "COURSE_NOTES"
    assert course_notes_skill.requires_url_input is False
    assert course_notes_skill.url_input_keys == ["url", "video_url"]
    assert resolve_action_key_from_skill_key("video_summary") == "VIDEO_SUMMARY"
    assert video_summary_skill.requires_url_input is True
    assert video_summary_skill.url_input_keys == ["url", "video_url"]
    assert video_summary_skill.input_schema["required"] == ["url"]


def test_mindmap_skill_should_compile_to_safe_hierarchical_markdown() -> None:
    task_input = _build_task_input(
        task_id="mindmap-job",
        target_id="artifact-mindmap-1",
        action_key="",
        skill_key="mindmap_from_workspace",
        style_profile_key="default",
        structure_constraints=["输出单一根节点、主分支和短语化节点。"],
        generation_brief="把当前资料整理为思维导图。",
        user_requirement="突出证据、结论与后续行动。",
        source_scope=[{
            "source_id": "src-mindmap-1",
            "title": "产物可视化设计",
            "summary": "用受控 Markdown 真源生成可交互的知识导图。",
        }],
        inputs={"language": "zh-CN", "layout": "compact", "depth": "3"},
    )
    plan = build_execution_plan(task_input)
    markdown = render_markdown(task_input, plan, [
        ArtifactSectionDraft(
            heading="核心能力",
            body="交互式缩放。分支折叠。来源可追溯。额外细节。",
            source_refs=["产物可视化设计"],
        ),
        ArtifactSectionDraft(
            heading="后续升级",
            body="加入 Mermaid；加入 Vega-Lite；接入演示文稿。",
            source_refs=[],
        ),
        ArtifactSectionDraft(
            heading="核心结论",
            body=(
                "输出单一根节点、4 到 7 条主分支和短语化节点”，"
                "当前资料已经形成可追溯的工作流。"
            ),
            source_refs=[],
        ),
    ])

    assert resolve_artifact_title(task_input, plan) == "工作台知识导图"
    assert resolve_action_key_from_skill_key("mindmap_from_workspace") == "MINDMAP"
    assert plan.action_key == "MINDMAP"
    assert plan.artifact_type == "MINDMAP"
    assert plan.execution_spec.output_shape == "MINDMAP_MARKDOWN"
    assert markdown.startswith("# 工作台知识导图\n")
    assert "## 核心能力" in markdown
    assert "- 交互式缩放" in markdown
    assert "- 额外细节" not in markdown
    assert "输出单一根节点" not in markdown
    assert "- 当前资料已经形成可追溯的工作流" in markdown
    assert "## 来源" in markdown
    assert "- 产物可视化设计" in markdown
    assert "Generation Goal" not in markdown


def test_run_mindmap_skill_should_pass_its_own_output_contract(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fake = FakeLlmClient(
        {
            "artifact.generate": r"""
            {
              "sections": [
                {"heading": "产品入口", "body": "工作台入口与空状态。", "source_refs": ["产品入口与空状态 QA"]},
                {"heading": "资料状态", "body": "资料范围与索引状态。", "source_refs": ["产品入口与空状态 QA"]},
                {"heading": "跨模块检查点", "body": "Research、Wiki 与 Artifact。", "source_refs": ["产品入口与空状态 QA"]}
              ]
            }
            """
        }
    )
    monkeypatch.setattr(runner_module, "build_default_llm_client", lambda: fake)
    task_input = _build_task_input(
        task_id="mindmap-contract-job",
        target_id="artifact-mindmap-contract-1",
        action_key="",
        skill_key="mindmap_from_workspace",
        style_profile_key="default",
        structure_constraints=["输出单一根节点、主分支和短语化节点。"],
        generation_brief="把当前资料整理为思维导图。",
        user_requirement="围绕产品入口与资料状态，保留来源线索。",
        source_scope=[{
            "source_id": "src-mindmap-contract-1",
            "title": "产品入口与空状态 QA",
            "summary": "验证工作台入口、资料状态和跨模块检查点。",
        }],
        inputs={"language": "zh-CN", "layout": "compact", "depth": "3"},
    )

    _, result = run_artifact_task(task_input)

    assert result.job_snapshot.action_key == "MINDMAP"
    assert result.version_snapshot.artifact_type == "MINDMAP"
    assert result.result_payload["verification"]["status"] == "PASS"
    assert result.result_payload["verification"]["failed_checks"] == []
    markdown = result.result_payload["markdown"]
    assert markdown.startswith("# 工作台知识导图\n")
    assert "## 关键概念" in markdown
    assert "## 证据脉络" in markdown
    assert "## 后续行动" in markdown
    assert "## 产品入口" not in markdown
    assert "### 主题快照" not in markdown
    assert "Node-level repair inserted" not in markdown
    assert "配方重点" not in markdown
    assert "任务重点" not in markdown
    assert "- 提炼核心概念、关键对象与关系" in markdown


def test_outline_repair_should_use_task_source_and_recipe_context() -> None:
    task_input = _build_generic_task_input("report")
    plan = build_execution_plan(task_input)
    skill = resolve_skill_definition("generic_section_writer")

    verified_output, verification_status, _, repair_actions = (
        _verify_and_repair_skill_output(
            skill=skill,
            output={"sections": []},
            task_input=task_input,
            plan=plan,
            state={"prompt_recipe": plan.prompt_recipe},
        )
    )

    repaired_sections = verified_output["sections"]
    evidence_section = next(
        section for section in repaired_sections if section.heading == "证据综述"
    )
    assert verification_status == "PASS_WITH_REPAIR"
    assert repair_actions
    assert evidence_section.source_refs == ["产物模块总设计", "技能图运行时"]
    assert "产物模块总设计、技能图运行时" in evidence_section.body
    assert "配方重点" not in evidence_section.body
    assert "任务重点" not in evidence_section.body
    assert "Node-level repair inserted" not in evidence_section.body


def test_build_execution_plan_should_reject_unknown_skill_key() -> None:
    task_input = _build_task_input(
        task_id="unknown-skill-job",
        target_id="artifact-unknown-1",
        action_key="AUTO",
        skill_key="unknown_skill",
        style_profile_key="default",
        structure_constraints=["输出结构化结果。"],
        generation_brief="",
        user_requirement="尝试运行一个不存在的 skill。",
        source_scope=[
            {
                "source_id": "src-1",
                "title": "任意资料",
                "summary": "用于触发未知 skill 校验。",
            }
        ],
    )

    with pytest.raises(ValueError, match="unknown artifact skill"):
        build_execution_plan(task_input)


def test_build_execution_plan_should_reject_missing_url_for_bilibili_skill() -> None:
    task_input = _build_task_input(
        task_id="bilibili-skill-job",
        target_id="artifact-bilibili-1",
        action_key="AUTO",
        skill_key="bilibili_course_note_pdf",
        style_profile_key="teaching",
        structure_constraints=["输出课程概要、知识点和讲义要求。"],
        generation_brief="",
        user_requirement="根据 B 站视频生成讲义。",
        source_scope=[],
        inputs={"language": "zh-CN"},
    )

    with pytest.raises(ValueError, match="requires a url input"):
        build_execution_plan(task_input)


def test_build_execution_plan_should_reject_missing_required_schema_input(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    patched_skill = ArtifactSkillDefinition.model_validate(
        {
            "skill_key": "resume_highlight",
            "display_name": "简历亮点描述",
            "description": "从当前工作台资料中生成适合简历书写的项目亮点",
            "default_action_key": "RESUME_HIGHLIGHT",
            "input_schema": {
                "type": "object",
                "properties": {
                    "language": {"type": "string"},
                    "topic": {"type": "string"},
                },
                "required": ["topic"],
            },
        }
    )

    monkeypatch.setattr(
        action_compat_module,
        "resolve_action_key_from_skill_key",
        lambda skill_key: "RESUME_HIGHLIGHT" if skill_key == "resume_highlight" else "",
    )
    monkeypatch.setattr(
        intent_compiler_module,
        "resolve_artifact_skill_definition",
        lambda skill_key: patched_skill,
    )

    task_input = _build_task_input(
        task_id="resume-required-input-job",
        target_id="artifact-resume-required-input-1",
        action_key="AUTO",
        skill_key="resume_highlight",
        style_profile_key="interview",
        structure_constraints=["输出一句话定位、亮点条目和关键词。"],
        generation_brief="",
        user_requirement="强调架构设计与异步编排。",
        source_scope=[
            {
                "source_id": "src-1",
                "title": "项目资料",
                "summary": "用于验证 schema gate 对必填输入的约束。",
            }
        ],
        inputs={"language": "zh-CN"},
    )

    with pytest.raises(ValueError, match="missing required input: topic"):
        build_execution_plan(task_input)


def test_build_execution_plan_should_accept_required_schema_input_when_provided(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    patched_skill = ArtifactSkillDefinition.model_validate(
        {
            "skill_key": "resume_highlight",
            "display_name": "简历亮点描述",
            "description": "从当前工作台资料中生成适合简历书写的项目亮点",
            "default_action_key": "RESUME_HIGHLIGHT",
            "input_schema": {
                "type": "object",
                "properties": {
                    "language": {"type": "string"},
                    "topic": {"type": "string"},
                },
                "required": ["topic"],
            },
        }
    )

    monkeypatch.setattr(
        action_compat_module,
        "resolve_action_key_from_skill_key",
        lambda skill_key: "RESUME_HIGHLIGHT" if skill_key == "resume_highlight" else "",
    )
    monkeypatch.setattr(
        intent_compiler_module,
        "resolve_artifact_skill_definition",
        lambda skill_key: patched_skill,
    )

    task_input = _build_task_input(
        task_id="resume-required-input-pass-job",
        target_id="artifact-resume-required-input-pass-1",
        action_key="AUTO",
        skill_key="resume_highlight",
        style_profile_key="interview",
        structure_constraints=["输出一句话定位、亮点条目和关键词。"],
        generation_brief="",
        user_requirement="强调架构设计与异步编排。",
        source_scope=[
            {
                "source_id": "src-1",
                "title": "项目资料",
                "summary": "用于验证 schema gate 对必填输入的统一约束。",
            }
        ],
        inputs={"language": "zh-CN", "topic": "skill-first refactor"},
    )

    plan = build_execution_plan(task_input)

    assert plan.skill_key == "resume_highlight"
    assert plan.execution_spec.inputs["topic"] == "skill-first refactor"


def test_run_artifact_task_should_generate_quiz_artifact() -> None:
    task_input = _build_generic_task_input("quiz", style_profile_key="teaching")

    _, result = run_artifact_task(task_input)

    assert result.job_snapshot.action_key == "QUIZ"
    assert result.version_snapshot.artifact_type == "QUIZ"
    assert result.result_payload["execution_plan"]["skill_graph_key"] == "quiz_artifact_v1"
    assert result.result_payload["runtime_plan"]["graph_key"] == "quiz_artifact_v1"
    assert result.result_payload["runtime_plan"]["activated_nodes"] == [
        "workspace_material_digest",
        "quiz_designer",
        "quiz_difficulty_normalizer",
        "evidence_guard",
        "style_polisher",
    ]
    assert result.result_payload["execution_plan"]["prompt_recipe"]["recipe_id"] == "quiz_writer_v1"
    assert result.result_payload["verification"]["status"] == "PASS"
    assert [trace["skill_key"] for trace in result.result_payload["node_traces"]] == [
        "workspace_material_digest",
        "quiz_designer",
        "quiz_difficulty_normalizer",
        "evidence_guard",
        "style_polisher",
    ]
    assert len(result.result_payload["context_pack"]["output_contract"]) == 4
    assert result.result_payload["approval_trace"]["status"] == "NOT_REQUIRED"
    assert result.result_payload["evidence_coverage"]["covered_section_count"] == 4
    question_section = next(
        section
        for section in result.result_payload["sections"]
        if section["heading"] == result.result_payload["context_pack"]["output_contract"][1]
    )
    scoring_section = next(
        section
        for section in result.result_payload["sections"]
        if section["heading"] == result.result_payload["context_pack"]["output_contract"][3]
    )
    assert sum(
        1
        for line in question_section["body"].splitlines()
        if line.lstrip().startswith(("1.", "2.", "3.", "4.", "5."))
    ) >= 3
    assert all(keyword in scoring_section["body"] for keyword in ("基础", "进阶", "挑战"))
    markdown = result.result_payload["markdown"]
    assert markdown.count("### ") >= 4
    assert "- Skill: quiz_pack" in markdown
    assert "- Prompt recipe:" not in markdown
    assert "- Skill graph:" not in markdown
    assert "- Schema gate:" not in markdown
    assert "Schema-Gated Skill Graph Runtime" in markdown
    quiz_design_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "quiz_designer"
    )
    quiz_normalize_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "quiz_difficulty_normalizer"
    )
    assert quiz_design_trace["verification_status"] == "PASS"
    assert "quiz question contract preserved" in quiz_design_trace["verification_checks"]
    assert quiz_normalize_trace["verification_status"] == "PASS"
    assert "quiz difficulty tiers preserved" in quiz_normalize_trace["verification_checks"]


def test_run_artifact_task_should_generate_wiki_page_artifact_with_wiki_structure_enforcer() -> None:
    task_input = _build_generic_task_input("wiki_page", style_profile_key="wiki")

    _, result = run_artifact_task(task_input)

    assert result.job_snapshot.action_key == "WIKI_PAGE"
    assert result.version_snapshot.artifact_type == "WIKI_PAGE"
    assert result.result_payload["execution_plan"]["skill_graph_key"] == "wiki_artifact_v1"
    assert [trace["skill_key"] for trace in result.result_payload["node_traces"]] == [
        "workspace_material_digest",
        "generic_section_writer",
        "wiki_structure_enforcer",
        "evidence_guard",
        "style_polisher",
    ]
    related_pages_section = next(
        section
        for section in result.result_payload["sections"]
        if section["heading"] == "相关页面"
    )
    assert "施工文档" in related_pages_section["body"]
    assert "设计文档" in related_pages_section["body"]
    assert result.result_payload["verification"]["status"] == "PASS"
    assert result.result_payload["verification"]["failed_checks"] == []
    wiki_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "wiki_structure_enforcer"
    )
    assert wiki_trace["verification_status"] == "PASS_WITH_REPAIR"
    assert "wiki structure contract preserved" in wiki_trace["verification_checks"]
    assert wiki_trace["repair_actions"]
    assert wiki_trace["repaired"] is True


@pytest.mark.parametrize(
    ("action_key", "expected_title", "expected_outline"),
    [
        ("report", "结构化报告", ["问题定义", "证据综述", "建议方案"]),
        ("faq", "FAQ 草稿", ["问题集", "标准回答", "使用说明"]),
        ("quiz", "测验草稿", ["测验目标", "题目设计", "答案与解析", "评分要点"]),
        ("study_guide", "学习指南", ["学习目标", "核心概念", "练习路径"]),
        ("wiki_page", "Wiki 页面草稿", ["概览", "关键机制", "相关页面"]),
        ("structured_note", "结构化笔记", ["主题快照", "关键摘录", "后续问题"]),
        ("video_summary", "视频总结", ["一句话总结", "时间线摘要", "核心观点", "关键概念", "可沉淀要点"]),
        ("audio_minutes", "音频纪要", ["总体摘要", "主要议题", "关键结论", "行动项", "待确认问题"]),
        ("course_notes", "课程笔记", ["课程概要", "知识点", "重点难点", "复习题"]),
    ],
)
def test_build_execution_plan_should_support_multiple_default_actions(
    action_key: str,
    expected_title: str,
    expected_outline: list[str],
) -> None:
    task_input = (
        _build_generic_task_input(action_key)
        if action_key in {"report", "faq", "quiz", "study_guide", "wiki_page", "structured_note"}
        else _build_media_task_input(action_key)
    )

    plan = build_execution_plan(task_input)

    assert plan.action_key == action_key.upper()
    assert plan.action_display_name == expected_title
    if action_key == "quiz":
        assert plan.skill_graph_key == "quiz_artifact_v1"
    elif action_key == "wiki_page":
        assert plan.skill_graph_key == "wiki_artifact_v1"
    else:
        assert plan.skill_graph_key == "generic_artifact_v1"
    assert plan.outline == expected_outline
    assert "READ_WORKSPACE_DOC" in plan.lazy_loaded_capabilities
    assert plan.deferred_capabilities
    assert plan.content_acquisition_plan.steps
    if action_key in {"video_summary", "audio_minutes", "course_notes"}:
        assert plan.approval_gate.decision == "APPROVAL_REQUIRED"
    else:
        assert plan.approval_gate.decision == "NOT_REQUIRED"
    if action_key == "quiz":
        assert [node.skill_key for node in plan.node_sequence] == [
            "workspace_material_digest",
            "quiz_designer",
            "quiz_difficulty_normalizer",
            "evidence_guard",
            "style_polisher",
        ]
    elif action_key == "wiki_page":
        assert [node.skill_key for node in plan.node_sequence] == [
            "workspace_material_digest",
            "generic_section_writer",
            "wiki_structure_enforcer",
            "evidence_guard",
            "style_polisher",
        ]
    else:
        assert [node.skill_key for node in plan.node_sequence] == [
            "workspace_material_digest",
            "generic_section_writer",
            "evidence_guard",
            "style_polisher",
        ]


def test_build_execution_plan_should_support_quiz_default_action() -> None:
    task_input = _build_generic_task_input("quiz", style_profile_key="teaching")

    plan = build_execution_plan(task_input)

    assert plan.action_key == "QUIZ"
    assert plan.style_profile_key == "TEACHING"
    assert plan.skill_graph_key == "quiz_artifact_v1"
    assert plan.prompt_recipe.recipe_id == "quiz_writer_v1"
    assert plan.prompt_recipe.generation_mode == "QUIZ_SYNTHESIS"
    assert len(plan.outline) == 4
    assert plan.approval_gate.decision == "NOT_REQUIRED"
    assert "READ_WORKSPACE_DOC" in plan.lazy_loaded_capabilities
    assert [node.skill_key for node in plan.node_sequence] == [
        "workspace_material_digest",
        "quiz_designer",
        "quiz_difficulty_normalizer",
        "evidence_guard",
        "style_polisher",
    ]


def test_build_execution_plan_should_support_wiki_page_default_action() -> None:
    task_input = _build_generic_task_input("wiki_page", style_profile_key="wiki")

    plan = build_execution_plan(task_input)

    assert plan.action_key == "WIKI_PAGE"
    assert plan.style_profile_key == "WIKI"
    assert plan.skill_graph_key == "wiki_artifact_v1"
    assert plan.prompt_recipe.recipe_id == "wiki_page_writer_v1"
    assert plan.prompt_recipe.generation_mode == "SECTION_SYNTHESIS"
    assert [node.skill_key for node in plan.node_sequence] == [
        "workspace_material_digest",
        "generic_section_writer",
        "wiki_structure_enforcer",
        "evidence_guard",
        "style_polisher",
    ]


def test_build_execution_plan_should_adapt_mixed_sources_into_fusion_plan() -> None:
    task_input = _build_mixed_context_task_input()

    plan = build_execution_plan(task_input)

    assert plan.action_key == "STUDY_GUIDE"
    assert plan.content_acquisition_plan.primary_strategy == "MIXED_CONTEXT_FUSION"
    assert plan.content_acquisition_plan.required_capabilities == ["READ_WEB_PAGE"]
    assert [step.step_type for step in plan.content_acquisition_plan.steps] == [
        "COLLECT_WORKSPACE_MATERIALS",
        "READ_CARD_CONTEXT",
        "READ_EXTERNAL_CONTENT",
        "NORMALIZE_TO_CCO",
        "MERGE_CONTEXT_BUNDLE",
    ]
    assert any("input adapter" in note.lower() for note in plan.content_acquisition_plan.notes)


def test_build_execution_plan_should_expose_source_level_acquisition_plan() -> None:
    task_input = _build_mixed_context_task_input()

    plan = build_execution_plan(task_input)

    assert plan.content_acquisition_plan.route_summary == {
        "CARD_CONTEXT": 1,
        "DOCUMENT_TEXT": 1,
        "WEB_URL": 1,
    }
    assert len(plan.content_acquisition_plan.source_plans) == 3

    doc_plan = next(
        source_plan
        for source_plan in plan.content_acquisition_plan.source_plans
        if source_plan.source_id == "src-doc-1"
    )
    card_plan = next(
        source_plan
        for source_plan in plan.content_acquisition_plan.source_plans
        if source_plan.source_id == "src-card-1"
    )
    url_plan = next(
        source_plan
        for source_plan in plan.content_acquisition_plan.source_plans
        if source_plan.source_id == "src-url-1"
    )

    assert doc_plan.source_platform == "WORKSPACE_DOC"
    assert doc_plan.adapter_route == "DOCUMENT_TEXT"
    assert doc_plan.normalization_target_kind == "DOCUMENT_TEXT"
    assert doc_plan.required_capabilities == []

    assert card_plan.source_platform == "WORKSPACE_CARD"
    assert card_plan.adapter_route == "CARD_CONTEXT"
    assert card_plan.planned_operations == ["READ_CARD_CONTEXT", "NORMALIZE_TO_CCO"]
    assert card_plan.related_source_ids == ["src-doc-1"]

    assert url_plan.source_platform == "WEB"
    assert url_plan.adapter_route == "WEB_URL"
    assert url_plan.required_capabilities == ["READ_WEB_PAGE"]
    assert url_plan.normalization_target_kind == "WEB_PAGE"


def test_build_execution_plan_should_expose_source_platform_for_bilibili_video() -> None:
    task_input = _build_media_task_input("video_summary")

    plan = build_execution_plan(task_input)

    assert plan.content_acquisition_plan.route_summary == {"VIDEO_URL": 1}
    assert len(plan.content_acquisition_plan.source_plans) == 1

    video_plan = plan.content_acquisition_plan.source_plans[0]

    assert video_plan.source_id == "src-video-1"
    assert video_plan.source_platform == "BILIBILI"
    assert video_plan.adapter_route == "VIDEO_URL"
    assert video_plan.planned_operations == [
        "RESOLVE_URL_SOURCE",
        "EXTRACT_TRANSCRIPT",
        "NORMALIZE_TO_CCO",
    ]
    assert video_plan.required_capabilities == ["EXTRACT_TRANSCRIPT"]
    assert video_plan.normalization_target_kind == "TRANSCRIPT"


def test_run_artifact_task_should_generate_resume_highlight_artifact() -> None:
    task_input = _build_resume_task_input()

    events, result = run_artifact_task(task_input)

    assert [event.phase for event in events] == [
        "RESOLVING",
        "ACQUIRING",
        "COMPOSING",
        "VERIFYING",
        "EXPORTING",
    ]
    assert result.result_title == "简历亮点描述"
    assert result.job_snapshot.action_key == "RESUME_HIGHLIGHT"
    assert result.result_payload["execution_plan"]["action_resolution"]["reason_code"] == "explicit_action_requested"
    assert result.version_snapshot.artifact_type == "RESUME_HIGHLIGHT"
    assert result.result_payload["execution_plan"]["capability_union_policy"]["decision"] == "ALLOW"
    assert result.result_payload["execution_plan"]["prompt_recipe"]["recipe_id"] == "resume_highlight_writer_v1"
    assert result.result_payload["context_pack"]["prompt_recipe"]["recipe_name"] == "Resume Highlight Writer"
    assert result.result_payload["context_pack"]["user_preference"]["action_resolution"]["resolved_action_key"] == "RESUME_HIGHLIGHT"
    assert len(result.result_payload["node_traces"]) == 6
    assert result.result_payload["verification"]["status"] == "PASS"
    assert len(result.result_payload["canonical_content_objects"]) == 3
    assert result.result_payload["canonical_content_objects"][-1]["kind"] == "MIXED_CONTEXT"
    assert result.result_payload["context_pack"]["task_brief"]
    assert result.result_payload["capability_resolution"]["resolved_bindings"][0]["server_id"] == "builtin-workspace"
    assert result.result_payload["capability_union_trace"]["status"] == "ALLOW"
    assert result.result_payload["capability_union_trace"]["decision"] == "ALLOW"
    assert result.result_payload["capability_union_trace"]["external_network_capabilities"] == []
    assert result.result_payload["capability_union_trace"]["blocked_capabilities"] == []
    assert result.result_payload["capability_union_trace"]["capability_decisions"][0]["capability_name"] == "READ_WORKSPACE_DOC"
    assert result.result_payload["approval_trace"]["status"] == "NOT_REQUIRED"
    assert result.result_payload["approval_trace"]["required_capabilities"] == []
    assert result.result_payload["approval_trace"]["pending_capabilities"] == []
    assert result.result_payload["evidence_coverage"]["status"] == "PASS"
    assert result.result_payload["evidence_coverage"]["section_count"] == 3
    assert result.result_payload["evidence_coverage"]["covered_section_count"] == 3
    assert result.result_payload["evidence_coverage"]["sections_missing_evidence"] == []
    assert result.result_payload["evidence_coverage"]["supporting_source_ids"] == ["src-1", "src-2"]
    assert result.result_payload["output_contract_trace"]["status"] == "PASS"
    assert result.result_payload["lifecycle_trace"]["status"] == "COMPLETED"
    assert result.result_payload["lifecycle_trace"]["current_phase"] == "EXPORTING"
    assert [
        step["phase"] for step in result.result_payload["lifecycle_trace"]["steps"]
    ] == [
        "RESOLVING",
        "ACQUIRING",
        "COMPOSING",
        "VERIFYING",
        "EXPORTING",
    ]
    assert all(
        step["status"] == "COMPLETED"
        for step in result.result_payload["lifecycle_trace"]["steps"]
    )
    assert result.result_payload["retrieval_feedback"]["index_status"] == "DRAFT"
    assert result.result_payload["retrieval_feedback"]["supporting_source_ids"] == ["src-1", "src-2"]
    assert result.result_payload["retrieval_feedback"]["derived_passage_count"] >= 2
    assert result.result_payload["retrieval_feedback"]["derived_passages"][0]["chunk_id"].startswith(
        "artifact-job-1:"
    )
    assert result.result_payload["retrieval_feedback"]["derived_passages"][0]["source_refs"]
    assert result.result_payload["memory_promotion_preview"]["eligible"] is True
    assert (
        result.result_payload["memory_promotion_preview"]["candidate_memory_count"]
        == len(result.result_payload["memory_promotion_preview"]["candidate_memories"])
    )
    assert result.result_payload["memory_promotion_preview"]["candidate_memories"][0]["memory_key"].startswith(
        "memory-candidate-artifact-job-1-"
    )
    assert result.result_payload["memory_promotion_preview"]["candidate_memories"][0]["source_ids"]
    digest_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "workspace_material_digest"
    )
    highlight_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_highlight_extractor"
    )
    normalizer_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_impact_normalizer"
    )
    bullet_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_bullet_writer"
    )
    verifier_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_verifier"
    )
    repair_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "resume_local_repair"
    )
    assert digest_trace["verification_status"] == "PASS"
    assert "source trace captured for digested materials" in digest_trace["verification_checks"]
    assert highlight_trace["verification_status"] == "PASS"
    assert "resume highlight candidate count satisfied" in highlight_trace["verification_checks"]
    assert normalizer_trace["verification_status"] == "PASS"
    assert "resume impact statements normalized" in normalizer_trace["verification_checks"]
    assert bullet_trace["verification_status"] == "PASS"
    assert "resume section draft generated" in bullet_trace["verification_checks"]
    assert verifier_trace["verification_status"] == "PASS"
    assert "resume verifier inspected bullet count, focus coverage, and required phrases" in verifier_trace["verification_checks"]
    assert repair_trace["verification_status"] == "PASS_WITH_REPAIR"
    assert "resume local repair confirmed verifier gaps are closed" in repair_trace["verification_checks"]
    assert all(
        trace["repair_actions"] == []
        for trace in [digest_trace, highlight_trace, normalizer_trace, bullet_trace, verifier_trace]
    )
    assert repair_trace["repair_actions"]
    assert repair_trace["repaired"] is True
    assert any(
        check["label"] == "一句话定位" and check["status"] == "PASS"
        for check in result.result_payload["output_contract_trace"]["outline_checks"]
    )
    assert any(
        check["label"] == "Controlled Agentic Graph Harness" and check["status"] == "PASS"
        for check in result.result_payload["output_contract_trace"]["phrase_checks"]
    )
    assert any(
        check["label"] == "resume highlight bullet count"
        and check["status"] == "PASS"
        for check in result.result_payload["output_contract_trace"]["action_checks"]
    )
    assert any(
        check["label"] == "evidence coverage"
        and check["status"] == "PASS"
        for check in result.result_payload["output_contract_trace"]["evidence_checks"]
    )

    markdown = result.result_payload["markdown"]
    assert "### 一句话定位" in markdown
    assert "### 简历亮点" in markdown
    assert "### 关键词" in markdown
    assert "- Skill: resume_highlight" in markdown
    assert "- Skill binding:" not in markdown
    assert "- Skill request resolved via:" not in markdown
    assert "- Action:" not in markdown
    assert "- Prompt recipe:" not in markdown
    assert "- Skill graph:" not in markdown
    assert "- Schema gate:" not in markdown
    assert "Controlled Agentic Graph Harness" in markdown
    assert "Schema-Gated Skill Graph Runtime" in markdown
    assert "Capability Union Policy" in markdown
    assert "resolve skill request -> execution spec planning -> skill graph runtime" in result.trace_summary
    assert "internal production action binding" not in result.trace_summary
    assert result.citations[0]["title"] == "产物生成 Agent 编排升级设计"


def test_node_level_verifier_should_repair_resume_local_repair_output() -> None:
    task_input = _build_resume_task_input()
    plan = build_execution_plan(task_input)
    skill = resolve_skill_definition("resume_local_repair")

    verified_output, verification_status, verification_checks, repair_actions = (
        _verify_and_repair_skill_output(
            skill=skill,
            output={
                "sections": [
                    ArtifactSectionDraft(
                        heading=plan.outline[0],
                        body="设计并实现受控式异步产物生成模块。",
                        source_refs=["产物生成 Agent 编排升级设计"],
                    ),
                    ArtifactSectionDraft(
                        heading=plan.outline[1],
                        body="- 统一产物生成主链路。",
                        source_refs=["产物生成 Agent 编排升级设计"],
                    ),
                    ArtifactSectionDraft(
                        heading=plan.outline[2],
                        body="Controlled Agentic Graph Harness",
                        source_refs=["产物生成 Agent 编排升级设计"],
                    ),
                ],
                "resume_verification_report": {
                    "status": "FAIL",
                    "bullet_count": 1,
                    "missing_focus_points": ["Schema-Gated Skill Graph Runtime"],
                    "missing_required_phrases": ["Capability Union Policy"],
                    "missing_headings": [],
                },
            },
            task_input=task_input,
            plan=plan,
            state={"sections": []},
        )
    )

    repaired_highlight_section = next(
        section
        for section in verified_output["sections"]
        if section.heading == plan.outline[1]
    )
    repaired_keyword_section = next(
        section
        for section in verified_output["sections"]
        if section.heading == plan.outline[2]
    )

    assert verification_status == "PASS_WITH_REPAIR"
    assert "resume local repair confirmed verifier gaps are closed" in verification_checks
    assert "resume highlight bullet backfilled at node level" in repair_actions
    assert "resume focus point backfilled at node level: Schema-Gated Skill Graph Runtime" in repair_actions
    assert "resume keyword backfilled at node level: Capability Union Policy" in repair_actions
    assert sum(
        1
        for line in repaired_highlight_section.body.splitlines()
        if line.lstrip().startswith("- ")
    ) >= 3
    assert "Capability Union Policy" in repaired_keyword_section.body


@pytest.mark.parametrize(
    ("action_key", "expected_title", "expected_headings"),
    [
        ("report", "结构化报告", ["问题定义", "证据综述", "建议方案"]),
        ("faq", "FAQ 草稿", ["问题集", "标准回答", "使用说明"]),
        ("quiz", "测验草稿", ["测验目标", "题目设计", "答案与解析", "评分要点"]),
        ("study_guide", "学习指南", ["学习目标", "核心概念", "练习路径"]),
        ("wiki_page", "Wiki 页面草稿", ["概览", "关键机制", "相关页面"]),
        ("structured_note", "结构化笔记", ["主题快照", "关键摘录", "后续问题"]),
        ("video_summary", "视频总结", ["一句话总结", "时间线摘要", "核心观点", "关键概念", "可沉淀要点"]),
        ("audio_minutes", "音频纪要", ["总体摘要", "主要议题", "关键结论", "行动项", "待确认问题"]),
        ("course_notes", "课程笔记", ["课程概要", "知识点", "重点难点", "复习题"]),
    ],
)
def test_run_artifact_task_should_generate_multiple_default_artifacts(
    action_key: str,
    expected_title: str,
    expected_headings: list[str],
) -> None:
    task_input = (
        _build_generic_task_input(action_key)
        if action_key in {"report", "faq", "quiz", "study_guide", "wiki_page", "structured_note"}
        else _build_media_task_input(action_key)
    )

    _, result = run_artifact_task(task_input)

    assert result.result_title == expected_title
    assert result.job_snapshot.action_key == action_key.upper()
    expected_verification_status = "PASS"
    assert result.result_payload["verification"]["status"] == expected_verification_status
    assert result.result_payload["output_contract_trace"]["status"] == expected_verification_status
    assert result.result_payload["lifecycle_trace"]["status"] == "COMPLETED"
    assert result.result_payload["artifact_version"]["artifact_type"] == action_key.upper()
    assert len(result.result_payload["canonical_content_objects"]) >= 2
    assert result.result_payload["capability_resolution"]["lazy_loaded_capabilities"]
    assert result.result_payload["capability_resolution"]["deferred_capabilities"]
    assert result.result_payload["capability_resolution"]["resolved_bindings"]
    if action_key == "quiz":
        assert result.result_payload["execution_plan"]["skill_graph_key"] == "quiz_artifact_v1"
        assert [trace["skill_key"] for trace in result.result_payload["node_traces"]] == [
            "workspace_material_digest",
            "quiz_designer",
            "quiz_difficulty_normalizer",
            "evidence_guard",
            "style_polisher",
        ]
    elif action_key == "wiki_page":
        assert result.result_payload["execution_plan"]["skill_graph_key"] == "wiki_artifact_v1"
        assert [trace["skill_key"] for trace in result.result_payload["node_traces"]] == [
            "workspace_material_digest",
            "generic_section_writer",
            "wiki_structure_enforcer",
            "evidence_guard",
            "style_polisher",
        ]
    assert result.result_payload["context_pack"]["output_contract"] == expected_headings
    assert result.result_payload["capability_union_trace"]["status"] == "ALLOW"
    assert result.result_payload["capability_union_trace"]["decision"] == "ALLOW"
    if action_key in {"video_summary", "audio_minutes", "course_notes"}:
        assert result.result_payload["capability_union_trace"]["external_network_capabilities"] or result.result_payload["capability_union_trace"]["capability_decisions"]
        assert result.result_payload["approval_trace"]["status"] == "SATISFIED"
        assert result.result_payload["approval_trace"]["required_capabilities"]
        assert result.result_payload["approval_trace"]["capability_decisions"][0]["approval_status"] == "APPROVED"
    else:
        assert result.result_payload["approval_trace"]["status"] == "NOT_REQUIRED"
    assert result.result_payload["evidence_coverage"]["status"] == "PASS"
    assert (
        result.result_payload["evidence_coverage"]["covered_section_count"]
        == len(expected_headings)
    )
    assert result.result_payload["retrieval_feedback"]["retrieval_label"] == "ARTIFACT_DERIVED"
    assert (
        result.result_payload["retrieval_feedback"]["derived_passage_count"]
        == len(result.result_payload["retrieval_feedback"]["derived_passages"])
    )
    assert result.result_payload["retrieval_feedback"]["derived_passages"][0]["retrieval_tags"]
    assert result.result_payload["memory_promotion_preview"]["candidate_memory_count"] >= 1
    assert (
        result.result_payload["memory_promotion_preview"]["candidate_memory_count"]
        == len(result.result_payload["memory_promotion_preview"]["candidate_memories"])
    )
    assert result.result_payload["memory_promotion_preview"]["candidate_memories"][0]["promotion_reason"]
    if action_key == "video_summary":
        assert result.result_payload["canonical_content_objects"][0]["kind"] == "TRANSCRIPT"
        assert result.result_payload["execution_plan"]["content_acquisition_plan"]["steps"][0]["step_type"] == "RESOLVE_URL_SOURCE"
        assert result.result_payload["capability_resolution"]["resolved_bindings"][1]["tool_name"] == "get_subtitle"
        assert result.result_payload["capability_resolution"]["resolved_bindings"][1]["server_id"] == "builtin-bilibili-mcp"
    if action_key == "audio_minutes":
        assert result.result_payload["canonical_content_objects"][0]["kind"] == "TRANSCRIPT"
        assert result.result_payload["capability_resolution"]["resolved_bindings"][1]["tool_name"] == "transcribe_audio"
    if action_key == "course_notes":
        assert result.result_payload["execution_plan"]["content_acquisition_plan"]["primary_strategy"] == "VIDEO_AUDIO_TRANSCRIPTION_PIPELINE"
        assert result.result_payload["capability_resolution"]["resolved_bindings"][1]["tool_name"] == "transcribe_video_audio_track"
        assert result.result_payload["capability_resolution"]["resolved_bindings"][1]["server_id"] == "builtin-asr"
    if action_key in {"report", "faq", "study_guide", "wiki_page", "structured_note"}:
        assert result.result_payload["canonical_content_objects"][-1]["kind"] == "MIXED_CONTEXT"

    markdown = result.result_payload["markdown"]
    for heading in expected_headings:
        assert f"### {heading}" in markdown


def test_run_artifact_task_should_emit_card_web_and_mixed_context_ccos() -> None:
    task_input = _build_mixed_context_task_input()

    _, result = run_artifact_task(task_input)

    canonical_content_objects = result.result_payload["canonical_content_objects"]
    kinds = [item["kind"] for item in canonical_content_objects]

    assert kinds == ["DOCUMENT_TEXT", "CARD_CONTEXT", "WEB_PAGE", "MIXED_CONTEXT"]
    assert canonical_content_objects[1]["metadata"]["related_source_ids"] == ["src-doc-1"]
    assert canonical_content_objects[2]["metadata"]["source_uri"] == "https://example.com/noteweave-artifact-architecture"
    assert canonical_content_objects[3]["metadata"]["source_count"] == 3
    assert canonical_content_objects[3]["metadata"]["source_kinds"] == [
        "DOCUMENT_TEXT",
        "CARD_CONTEXT",
        "WEB_PAGE",
    ]
    assert "source:src-card-1" in canonical_content_objects[3]["source_trace"]
    web_binding = next(
        binding
        for binding in result.result_payload["capability_resolution"]["resolved_bindings"]
        if binding["capability_name"] == "READ_WEB_PAGE"
    )
    assert web_binding["server_id"] == "builtin-browser"
    assert web_binding["tool_name"] == "read_article_page"
    assert web_binding["selection_reason"] == "action_skill_graph_preferred_provider"
    assert web_binding["action_basis"] == "STUDY_GUIDE"
    assert web_binding["skill_graph_basis"] == "generic_artifact_v1"


def test_run_artifact_task_should_emit_acquisition_receipt_with_provider_provenance() -> None:
    task_input = _build_mixed_context_task_input()

    _, result = run_artifact_task(task_input)

    receipt = result.result_payload["acquisition_receipt"]

    assert receipt["status"] == "COMPLETED"
    assert receipt["execution_mode"] == "SIMULATED_RUNTIME"
    assert receipt["primary_strategy"] == "MIXED_CONTEXT_FUSION"
    assert receipt["route_summary"] == {
        "CARD_CONTEXT": 1,
        "DOCUMENT_TEXT": 1,
        "WEB_URL": 1,
    }

    url_receipt = next(
        item for item in receipt["source_receipts"] if item["source_id"] == "src-url-1"
    )
    read_operation = next(
        operation
        for operation in url_receipt["operations"]
        if operation["operation_key"] == "READ_EXTERNAL_CONTENT"
    )

    assert url_receipt["status"] == "COMPLETED"
    assert url_receipt["source_platform"] == "WEB"
    assert url_receipt["adapter_route"] == "WEB_URL"
    assert url_receipt["produced_cco_ids"]
    assert read_operation["status"] == "COMPLETED"
    assert read_operation["capability_name"] == "READ_WEB_PAGE"
    assert read_operation["provider_id"] == "builtin-browser"
    assert read_operation["server_id"] == "builtin-browser"
    assert read_operation["tool_name"] == "read_article_page"
    assert read_operation["selection_reason"] == "action_skill_graph_preferred_provider"
    assert read_operation["request_id"].startswith("fetch-mixed-context-job-src-url-1-")
    assert read_operation["input_locator"] == "https://example.com/noteweave-artifact-architecture"
    assert read_operation["requested_at"]
    assert read_operation["completed_at"]
    assert read_operation["provider_receipt_id"].startswith("provider-receipt-fetch-mixed-context-job-src-url-1-")
    assert read_operation["provider_job_id"].startswith("provider-job-builtin-browser-")
    assert read_operation["adapter_callback_token"].startswith("adapter-callback-fetch-mixed-context-job-src-url-1-")
    assert read_operation["provider_job_status"] == "SUCCEEDED"
    assert read_operation["callback_status"] == "ACKNOWLEDGED"
    assert read_operation["callback_received_at"] == read_operation["completed_at"]
    assert read_operation["result_locator"].startswith("provider://builtin-browser/read_article_page/")
    assert "Schema-Gated Skill Graph Runtime" in read_operation["output_summary"]
    assert len(read_operation["content_digest"]) == 12
    assert read_operation["payload_char_count"] > 20
    assert read_operation["segment_count"] == 1
    assert read_operation["source_ref_count"] == 1
    assert read_operation["error_code"] == ""
    assert read_operation["error_message"] == ""
    assert read_operation["retryable"] is False
    assert len(read_operation["provider_attempts"]) == 2
    assert read_operation["provider_attempts"][0]["provider_id"] == "builtin-browser"
    assert read_operation["provider_attempts"][0]["attempt_result"] == "SELECTED"
    assert read_operation["provider_attempts"][1]["provider_id"] == "builtin-network"
    assert read_operation["provider_attempts"][1]["attempt_result"] == "SKIPPED_LOWER_PRIORITY"
    assert read_operation["discovery_status"] in {"REGISTERED", "DISCOVERED"}
    assert read_operation["provider_status"] == "AVAILABLE"
    assert read_operation["health_status"] == "HEALTHY"
    assert read_operation["approval_status"] == "APPROVED"
    assert read_operation["last_discovered_at"] or read_operation["discovery_status"] == "REGISTERED"
    assert receipt["wait_reason"] == {}


def test_run_artifact_task_should_emit_bilibili_acquisition_provenance() -> None:
    task_input = _build_media_task_input("video_summary")

    _, result = run_artifact_task(task_input)

    receipt = result.result_payload["acquisition_receipt"]
    video_receipt = receipt["source_receipts"][0]
    transcript_operation = next(
        operation
        for operation in video_receipt["operations"]
        if operation["operation_key"] == "EXTRACT_TRANSCRIPT"
    )
    normalize_operation = next(
        operation
        for operation in video_receipt["operations"]
        if operation["operation_key"] == "NORMALIZE_TO_CCO"
    )

    assert receipt["status"] == "COMPLETED"
    assert video_receipt["source_platform"] == "BILIBILI"
    assert video_receipt["adapter_route"] == "VIDEO_URL"
    assert transcript_operation["capability_name"] == "EXTRACT_TRANSCRIPT"
    assert transcript_operation["provider_id"] == "builtin-bilibili-mcp"
    assert transcript_operation["tool_name"] == "get_subtitle"
    assert transcript_operation["selection_reason"] == "preferred_bilibili_subtitle_provider"
    assert transcript_operation["request_id"].startswith("fetch-video_summary-job-src-video-1-")
    assert transcript_operation["input_locator"] == "https://www.bilibili.com/video/BV1NoteWeaveDemo"
    assert transcript_operation["requested_at"]
    assert transcript_operation["completed_at"]
    assert transcript_operation["provider_receipt_id"].startswith("provider-receipt-fetch-video_summary-job-src-video-1-")
    assert transcript_operation["provider_job_id"].startswith("provider-job-builtin-bilibili-mcp-")
    assert transcript_operation["adapter_callback_token"].startswith("adapter-callback-fetch-video_summary-job-src-video-1-")
    assert transcript_operation["provider_job_status"] == "SUCCEEDED"
    assert transcript_operation["callback_status"] == "ACKNOWLEDGED"
    assert transcript_operation["callback_received_at"] == transcript_operation["completed_at"]
    assert transcript_operation["result_locator"].startswith("provider://builtin-bilibili-mcp/get_subtitle/")
    assert "Controlled Agentic Graph Harness" in transcript_operation["output_summary"]
    assert len(transcript_operation["content_digest"]) == 12
    assert transcript_operation["payload_char_count"] > 20
    assert transcript_operation["segment_count"] == 1
    assert transcript_operation["source_ref_count"] == 1
    assert transcript_operation["error_code"] == ""
    assert transcript_operation["error_message"] == ""
    assert transcript_operation["retryable"] is False
    assert transcript_operation["provider_attempts"][0]["provider_id"] == "builtin-bilibili-mcp"
    assert transcript_operation["provider_attempts"][0]["attempt_result"] == "SELECTED"
    assert transcript_operation["discovery_status"] in {"REGISTERED", "DISCOVERED"}
    assert transcript_operation["provider_status"] == "AVAILABLE"
    assert transcript_operation["health_status"] == "HEALTHY"
    assert transcript_operation["approval_status"] == "APPROVED"
    assert normalize_operation["output_kind"] == "TRANSCRIPT"
    assert normalize_operation["provider_receipt_id"] == ""
    assert normalize_operation["provider_job_id"] == ""
    assert normalize_operation["adapter_callback_token"] == ""
    assert normalize_operation["provider_job_status"] == "NOT_REQUIRED"
    assert normalize_operation["callback_status"] == "NOT_REQUIRED"
    assert normalize_operation["callback_received_at"] == ""
    assert normalize_operation["completed_at"]
    assert normalize_operation["result_locator"].startswith("cco://")
    assert "TRANSCRIPT" in normalize_operation["output_summary"]
    assert len(normalize_operation["content_digest"]) == 12
    assert normalize_operation["payload_char_count"] > 20
    assert normalize_operation["segment_count"] == 2
    assert normalize_operation["source_ref_count"] == 1
    assert normalize_operation["error_code"] == ""
    assert normalize_operation["retryable"] is False


def test_build_execution_plan_should_allow_prompt_recipe_override_for_report() -> None:
    task_input = _build_task_input(
        task_id="report-executive-job",
        target_id="artifact-report-executive-1",
        action_key="report",
        style_profile_key="executive",
        structure_constraints=["保留问题、证据、建议三段。"],
        generation_brief="输出更偏管理摘要风格的结构化报告。",
        source_scope=[
            {
                "source_id": "src-1",
                "title": "产物模块总设计",
                "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
            }
        ],
        requested_capabilities=[],
        prompt_recipe_id="report_executive_writer_v1",
    )

    plan = build_execution_plan(task_input)

    assert plan.prompt_recipe.recipe_id == "report_executive_writer_v1"
    assert plan.prompt_recipe.recipe_name == "Executive Report Writer"
    assert "决策摘要" in plan.prompt_recipe.recipe_notes[0]


def test_action_resolver_should_auto_select_video_summary_for_bilibili_url() -> None:
    task_input = _build_task_input(
        task_id="auto-video-summary-job",
        target_id="artifact-auto-video-summary-1",
        action_key="",
        style_profile_key="default",
        structure_constraints=["输出一句话总结、时间线摘要、核心观点。"],
        generation_brief="帮我总结这个 B 站视频。",
        source_scope=[
            {
                "source_id": "src-video-1",
                "title": "B 站架构讲解",
                "summary": "视频链接，介绍 Controlled Agentic Graph Harness 与 Skill Graph Runtime。",
                "source_type": "URL",
                "source_uri": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
            }
        ],
    )

    resolution = debug_resolve_action(task_input).action_resolution
    plan = build_execution_plan(task_input)

    assert resolution["resolved_action_key"] == "VIDEO_SUMMARY"
    assert resolution["resolution_mode"] == "AUTO"
    assert resolution["reason_code"] in {
        "keyword_video_summary",
        "route_video_url_default_action",
    }
    assert plan.action_key == "VIDEO_SUMMARY"


def test_action_resolver_should_auto_select_audio_minutes_for_audio_file() -> None:
    task_input = _build_task_input(
        task_id="auto-audio-minutes-job",
        target_id="artifact-auto-audio-minutes-1",
        action_key="AUTO",
        style_profile_key="default",
        structure_constraints=["输出总体摘要、主要议题、关键结论。"],
        generation_brief="把这段会议录音整理成纪要。",
        source_scope=[
            {
                "source_id": "src-audio-1",
                "title": "组会录音",
                "summary": "音频文件，包含产物生成模块的设计讨论。",
                "source_type": "AUDIO_FILE",
            }
        ],
    )

    plan = build_execution_plan(task_input)

    assert plan.action_key == "AUDIO_MINUTES"
    assert plan.action_resolution.resolution_mode == "AUTO"
    assert plan.action_resolution.reason_code in {
        "keyword_audio_minutes",
        "route_audio_file_default_action",
    }


def test_action_resolver_should_auto_select_faq_from_generation_brief_keywords() -> None:
    task_input = _build_task_input(
        task_id="auto-faq-job",
        target_id="artifact-auto-faq-1",
        action_key="",
        style_profile_key="default",
        structure_constraints=["输出问题集、标准回答、使用说明。"],
        generation_brief="把这套产物生成能力整理成 FAQ 和标准回答。",
        source_scope=[
            {
                "source_id": "src-doc-1",
                "title": "产物模块总设计",
                "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
            }
        ],
    )

    plan = build_execution_plan(task_input)
    _, result = run_artifact_task(task_input)

    assert plan.action_key == "FAQ"
    assert plan.action_resolution.reason_code == "keyword_faq"
    assert plan.action_resolution.winning_signals == ["BRIEF_KEYWORD"]
    assert plan.action_resolution.candidate_scores == []
    assert result.result_title == "FAQ 草稿"
    assert result.job_snapshot.action_key == "FAQ"


def test_action_resolver_should_auto_select_quiz_from_generation_brief_keywords() -> None:
    task_input = _build_task_input(
        task_id="auto-quiz-job",
        target_id="artifact-auto-quiz-1",
        action_key="",
        style_profile_key="teaching",
        structure_constraints=["Output quiz goals, questions, answers, and scoring criteria."],
        generation_brief="Turn this artifact architecture material into a reusable onboarding quiz draft.",
        source_scope=[
            {
                "source_id": "src-doc-1",
                "title": "Artifact Module Architecture",
                "summary": "The worker unifies Production Action, Style Profile, Skill Graph, and Artifact Runtime.",
            }
        ],
    )

    plan = build_execution_plan(task_input)
    _, result = run_artifact_task(task_input)

    assert plan.action_key == "QUIZ"
    assert plan.action_resolution.reason_code == "keyword_quiz"
    assert plan.action_resolution.winning_signals == ["BRIEF_KEYWORD"]
    assert plan.action_resolution.candidate_scores == []
    assert result.job_snapshot.action_key == "QUIZ"
    assert result.result_payload["execution_plan"]["action_resolution"]["resolved_action_key"] == "QUIZ"


def test_custom_action_registration_should_extend_action_catalog_and_plan() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    registration = CustomProductionActionRegistration.model_validate(
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

    try:
        action = debug_register_custom_action(registration).action
        catalog = debug_list_default_actions()
        task_input = _build_task_input(
            task_id="executive-summary-job",
            target_id="artifact-executive-summary-1",
            action_key="executive_summary",
            style_profile_key="",
            structure_constraints=["输出决策摘要、关键证据、推进建议。"],
            generation_brief="输出更偏管理摘要风格的报告。",
            source_scope=[
                {
                    "source_id": "src-1",
                    "title": "产物模块总设计",
                    "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                }
            ],
        )

        plan = build_execution_plan(task_input)
        _, result = run_artifact_task(task_input)
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert action["action_key"] == "EXECUTIVE_SUMMARY"
    assert action["action_origin"] == "CUSTOM"
    assert action["template_action_key"] == "REPORT"
    assert any(item["action_key"] == "EXECUTIVE_SUMMARY" for item in catalog.actions)
    assert plan.action_key == "EXECUTIVE_SUMMARY"
    assert plan.action_origin == "CUSTOM"
    assert plan.template_action_key == "REPORT"
    assert plan.prompt_recipe.recipe_id == "report_executive_writer_v1"
    assert plan.outline == ["决策摘要", "关键证据", "推进建议"]
    assert result.result_title == "管理决策摘要"
    assert result.job_snapshot.action_key == "EXECUTIVE_SUMMARY"
    assert result.result_payload["artifact_version"]["artifact_type"] == "EXECUTIVE_SUMMARY"
    markdown = result.result_payload["markdown"]
    assert "### 决策摘要" in markdown
    assert "### 关键证据" in markdown
    assert "### 推进建议" in markdown


def test_action_resolver_should_match_custom_action_keywords() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    registration = CustomProductionActionRegistration.model_validate(
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

    try:
        debug_register_custom_action(registration)
        task_input = _build_task_input(
            task_id="auto-executive-summary-job",
            target_id="artifact-auto-executive-summary-1",
            action_key="AUTO",
            style_profile_key="",
            structure_constraints=["输出决策摘要、关键证据、推进建议。"],
            generation_brief="请输出一份管理摘要和推进建议。",
            source_scope=[
                {
                    "source_id": "src-1",
                    "title": "产物模块总设计",
                    "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                }
            ],
        )

        resolution = debug_resolve_action(task_input).action_resolution
        plan = build_execution_plan(task_input)
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "EXECUTIVE_SUMMARY"
    assert resolution["reason_code"] == "custom_action_keyword_match"
    assert plan.action_key == "EXECUTIVE_SUMMARY"
    assert plan.action_origin == "CUSTOM"


def test_action_resolver_should_prefer_higher_priority_custom_action() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    lower_priority = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_summary_low",
            "display_name": "管理决策摘要低优先级",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_prompt_recipe_id": "report_executive_writer_v1",
            "artifact_type": "EXECUTIVE_SUMMARY_LOW",
            "output_sections": ["决策摘要", "关键证据", "推进建议"],
            "required_phrases": ["Production Action"],
            "resolver_keywords": ["管理摘要"],
            "resolver_priority": 100,
        }
    )
    higher_priority = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_summary_high",
            "display_name": "管理决策摘要高优先级",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_prompt_recipe_id": "report_executive_writer_v1",
            "artifact_type": "EXECUTIVE_SUMMARY_HIGH",
            "output_sections": ["决策摘要", "关键证据", "推进建议"],
            "required_phrases": ["Capability Union Policy"],
            "resolver_keywords": ["管理摘要"],
            "resolver_priority": 200,
        }
    )

    try:
        debug_register_custom_action(lower_priority)
        debug_register_custom_action(higher_priority)
        resolution = debug_resolve_action(
            _build_task_input(
                task_id="priority-action-job",
                target_id="artifact-priority-action-1",
                action_key="AUTO",
                style_profile_key="",
                structure_constraints=["输出决策摘要。"],
                generation_brief="请给我一份管理摘要。",
                source_scope=[
                    {
                        "source_id": "src-1",
                        "title": "产物模块总设计",
                        "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                    }
                ],
            )
        ).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "EXECUTIVE_SUMMARY_HIGH"
    assert resolution["reason_code"] == "custom_action_keyword_match"


def test_action_resolver_should_match_custom_action_style_hint() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    style_registered = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_briefing",
            "display_name": "管理简报",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_prompt_recipe_id": "report_executive_writer_v1",
            "artifact_type": "EXECUTIVE_BRIEFING",
            "output_sections": ["决策摘要", "关键证据", "推进建议"],
            "required_phrases": ["Production Action"],
            "preferred_style_profiles": ["EXECUTIVE"],
            "resolver_priority": 110,
        }
    )

    try:
        debug_register_custom_action(style_registered)
        resolution = debug_resolve_action(
            _build_task_input(
                task_id="style-hint-action-job",
                target_id="artifact-style-hint-action-1",
                action_key="AUTO",
                style_profile_key="executive",
                structure_constraints=["输出决策摘要、关键证据、推进建议。"],
                generation_brief="请整理成管理层可读的简报。",
                source_scope=[
                    {
                        "source_id": "src-1",
                        "title": "产物模块总设计",
                        "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                    }
                ],
            )
        ).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "EXECUTIVE_BRIEFING"
    assert resolution["reason_code"] == "custom_action_style_match"
    assert any("preferred style profiles" in note for note in resolution["notes"])


def test_action_resolver_should_match_custom_action_source_platform_hint() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    platform_registered = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "web_reference_digest",
            "display_name": "网页参考摘要",
            "base_action_key": "wiki_page",
            "default_style_profile_key": "wiki",
            "default_prompt_recipe_id": "wiki_page_writer_v1",
            "artifact_type": "WEB_REFERENCE_DIGEST",
            "output_sections": ["概览", "关键机制", "相关页面"],
            "required_phrases": ["Skill Graph"],
            "preferred_source_platforms": ["WEB"],
            "resolver_priority": 110,
        }
    )

    try:
        debug_register_custom_action(platform_registered)
        resolution = debug_resolve_action(
            _build_task_input(
                task_id="platform-hint-action-job",
                target_id="artifact-platform-hint-action-1",
                action_key="AUTO",
                style_profile_key="wiki",
                structure_constraints=["输出概览、关键机制、相关页面。"],
                generation_brief="请处理这份网页资料。",
                source_scope=[
                    {
                        "source_id": "src-url-1",
                        "title": "架构综述网页",
                        "summary": "网页内容补充了 Schema-Gated Skill Graph Runtime 与 Verifier / Repair 的协同方式。",
                        "source_type": "URL",
                        "source_uri": "https://example.com/noteweave-artifact-architecture",
                        "source_metadata": {"platform": "WEB", "content_type": "article"},
                    }
                ],
            )
        ).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "WEB_REFERENCE_DIGEST"
    assert resolution["reason_code"] == "custom_action_source_platform_match"
    assert any("preferred source platforms" in note for note in resolution["notes"])


def test_action_resolver_should_match_custom_action_structure_keywords() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    structure_registered = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "guided_study_drill",
            "display_name": "学习演练指南",
            "base_action_key": "study_guide",
            "default_style_profile_key": "teaching",
            "default_prompt_recipe_id": "study_guide_writer_v1",
            "artifact_type": "GUIDED_STUDY_DRILL",
            "output_sections": ["学习目标", "核心概念", "练习路径"],
            "required_phrases": ["Schema Gate"],
            "preferred_structure_keywords": ["学习目标", "练习路径"],
            "resolver_priority": 115,
        }
    )

    try:
        debug_register_custom_action(structure_registered)
        resolution = debug_resolve_action(
            _build_task_input(
                task_id="structure-hint-action-job",
                target_id="artifact-structure-hint-action-1",
                action_key="AUTO",
                style_profile_key="teaching",
                structure_constraints=["输出学习目标、核心概念和练习路径。"],
                generation_brief="请整理成教学材料。",
                source_scope=[
                    {
                        "source_id": "src-doc-1",
                        "title": "受控式产物生成架构说明",
                        "summary": "统一主链路承接 Production Action、Style Profile、Skill Graph 和 Artifact Runtime。",
                    }
                ],
            )
        ).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "GUIDED_STUDY_DRILL"
    assert resolution["reason_code"] == "custom_action_structure_match"
    assert any("preferred structure keywords" in note for note in resolution["notes"])


def test_action_resolver_should_match_custom_action_workspace_context_hint() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    workspace_registered = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "research_context_note",
            "display_name": "研究上下文笔记",
            "base_action_key": "structured_note",
            "default_style_profile_key": "default",
            "default_prompt_recipe_id": "structured_note_writer_v1",
            "artifact_type": "RESEARCH_CONTEXT_NOTE",
            "output_sections": ["主题快照", "关键摘录", "后续问题"],
            "required_phrases": ["Artifact Runtime"],
            "preferred_task_neighborhoods": ["RESEARCH_SYNTHESIS"],
            "resolver_priority": 105,
        }
    )

    try:
        debug_register_custom_action(workspace_registered)
        task_input = _build_task_input(
            task_id="workspace-hint-action-job",
            target_id="artifact-workspace-hint-action-1",
            action_key="AUTO",
            style_profile_key="default",
            structure_constraints=["输出主题快照、关键摘录、后续问题。"],
            generation_brief="请整理当前研究上下文。",
            source_scope=[
                {
                    "source_id": "src-doc-1",
                    "title": "受控式产物生成架构说明",
                    "summary": "统一主链路承接 Production Action、Style Profile、Skill Graph 和 Artifact Runtime。",
                }
            ],
        )
        task_input.control_pack.task_neighborhood = "RESEARCH_SYNTHESIS"
        resolution = debug_resolve_action(task_input).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "RESEARCH_CONTEXT_NOTE"
    assert resolution["reason_code"] == "custom_action_workspace_context_match"
    assert any("preferred task neighborhoods" in note for note in resolution["notes"])


def test_custom_action_registration_should_reject_same_priority_keyword_conflict() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    existing = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_summary",
            "display_name": "管理决策摘要",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_prompt_recipe_id": "report_executive_writer_v1",
            "artifact_type": "EXECUTIVE_SUMMARY",
            "output_sections": ["决策摘要", "关键证据", "推进建议"],
            "required_phrases": ["Production Action"],
            "resolver_keywords": ["管理摘要"],
            "resolver_priority": 100,
        }
    )
    conflicting = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_summary_conflict",
            "display_name": "管理决策摘要冲突",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_prompt_recipe_id": "report_executive_writer_v1",
            "artifact_type": "EXECUTIVE_SUMMARY_CONFLICT",
            "output_sections": ["决策摘要", "关键证据", "推进建议"],
            "required_phrases": ["Capability Union Policy"],
            "resolver_keywords": ["管理摘要"],
            "resolver_priority": 100,
        }
    )

    try:
        debug_register_custom_action(existing)
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_action(conflicting)
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert "resolver conflict" in str(exc_info.value)
    assert "overlapping_keywords=管理摘要" in str(exc_info.value)


def test_action_resolver_should_match_custom_action_route_hints() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    route_registered = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "bilibili_digest",
            "display_name": "Bilibili 摘要",
            "base_action_key": "video_summary",
            "default_style_profile_key": "default",
            "default_prompt_recipe_id": "video_summary_writer_v1",
            "artifact_type": "BILIBILI_DIGEST",
            "output_sections": ["一句话总结", "时间线摘要", "核心观点"],
            "required_phrases": ["Controlled Agentic Graph Harness"],
            "preferred_routes": ["VIDEO_URL"],
            "resolver_priority": 120,
        }
    )

    try:
        debug_register_custom_action(route_registered)
        resolution = debug_resolve_action(
            _build_task_input(
                task_id="route-hint-action-job",
                target_id="artifact-route-hint-action-1",
                action_key="AUTO",
                style_profile_key="",
                structure_constraints=["输出一句话总结、时间线摘要、核心观点。"],
                generation_brief="请处理这个链接。",
                source_scope=[
                    {
                        "source_id": "src-video-1",
                        "title": "B 站架构讲解",
                        "summary": "视频链接，介绍 Controlled Agentic Graph Harness 与 Skill Graph Runtime。",
                        "source_type": "URL",
                        "source_uri": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                    }
                ],
            )
        ).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "BILIBILI_DIGEST"
    assert resolution["reason_code"] == "custom_action_route_match"


def test_action_resolver_should_prefer_multi_signal_custom_action_over_route_only_candidate() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    route_only = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "bilibili_digest_route_only",
            "display_name": "Bilibili 路由摘要",
            "base_action_key": "video_summary",
            "default_style_profile_key": "default",
            "default_prompt_recipe_id": "video_summary_writer_v1",
            "artifact_type": "BILIBILI_DIGEST_ROUTE_ONLY",
            "output_sections": ["一句话总结", "时间线摘要", "核心观点"],
            "required_phrases": ["Controlled Agentic Graph Harness"],
            "preferred_routes": ["VIDEO_URL"],
            "resolver_priority": 120,
        }
    )
    multi_signal = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "bilibili_digest_multi_signal",
            "display_name": "Bilibili 多信号摘要",
            "base_action_key": "video_summary",
            "default_style_profile_key": "teaching",
            "default_prompt_recipe_id": "video_summary_writer_v1",
            "artifact_type": "BILIBILI_DIGEST_MULTI_SIGNAL",
            "output_sections": ["一句话总结", "时间线摘要", "核心观点"],
            "required_phrases": ["Schema Gate"],
            "preferred_routes": ["VIDEO_URL"],
            "preferred_style_profiles": ["TEACHING"],
            "resolver_priority": 120,
        }
    )

    try:
        debug_register_custom_action(route_only)
        debug_register_custom_action(multi_signal)
        resolution = debug_resolve_action(
            _build_task_input(
                task_id="multi-signal-action-job",
                target_id="artifact-multi-signal-action-1",
                action_key="AUTO",
                style_profile_key="teaching",
                structure_constraints=["输出一句话总结、时间线摘要、核心观点。"],
                generation_brief="请处理这个视频链接。",
                source_scope=[
                    {
                        "source_id": "src-video-1",
                        "title": "B 站架构讲解",
                        "summary": "视频链接，介绍 Controlled Agentic Graph Harness 与 Skill Graph Runtime。",
                        "source_type": "URL",
                        "source_uri": "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                    }
                ],
            )
        ).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "BILIBILI_DIGEST_MULTI_SIGNAL"
    assert resolution["reason_code"] == "custom_action_multi_signal_match"
    assert resolution["winning_signals"] == ["ROUTE", "STYLE_PROFILE"]
    assert resolution["candidate_scores"][0]["action_key"] == "BILIBILI_DIGEST_MULTI_SIGNAL"
    assert resolution["candidate_scores"][0]["selected"] is True
    assert resolution["candidate_scores"][0]["matched_signal_types"] == ["ROUTE", "STYLE_PROFILE"]
    assert resolution["candidate_scores"][1]["action_key"] == "BILIBILI_DIGEST_ROUTE_ONLY"
    assert resolution["candidate_scores"][1]["selected"] is False
    assert any("highest-scoring candidate" in note for note in resolution["notes"])


def test_action_resolver_should_prefer_platform_and_structure_multi_signal_candidate() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    platform_only = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "web_reference_digest_platform_only",
            "display_name": "网页参考摘要平台优先",
            "base_action_key": "wiki_page",
            "default_style_profile_key": "wiki",
            "default_prompt_recipe_id": "wiki_page_writer_v1",
            "artifact_type": "WEB_REFERENCE_DIGEST_PLATFORM_ONLY",
            "output_sections": ["概览", "关键机制", "相关页面"],
            "required_phrases": ["Skill Graph"],
            "preferred_source_platforms": ["WEB"],
            "resolver_priority": 110,
        }
    )
    platform_and_structure = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "web_reference_digest_multi_signal",
            "display_name": "网页参考摘要多信号",
            "base_action_key": "wiki_page",
            "default_style_profile_key": "wiki",
            "default_prompt_recipe_id": "wiki_page_writer_v1",
            "artifact_type": "WEB_REFERENCE_DIGEST_MULTI_SIGNAL",
            "output_sections": ["概览", "关键机制", "相关页面"],
            "required_phrases": ["Schema Gate"],
            "preferred_source_platforms": ["WEB"],
            "preferred_structure_keywords": ["概览", "关键机制"],
            "resolver_priority": 110,
        }
    )

    try:
        debug_register_custom_action(platform_only)
        debug_register_custom_action(platform_and_structure)
        resolution = debug_resolve_action(
            _build_task_input(
                task_id="platform-structure-multi-signal-job",
                target_id="artifact-platform-structure-multi-signal-1",
                action_key="AUTO",
                style_profile_key="wiki",
                structure_constraints=["输出概览、关键机制和相关页面。"],
                generation_brief="请处理这份网页资料。",
                source_scope=[
                    {
                        "source_id": "src-url-1",
                        "title": "架构综述网页",
                        "summary": "网页内容补充了 Schema-Gated Skill Graph Runtime 与 Verifier / Repair 的协同方式。",
                        "source_type": "URL",
                        "source_uri": "https://example.com/noteweave-artifact-architecture",
                        "source_metadata": {"platform": "WEB", "content_type": "article"},
                    }
                ],
            )
        ).action_resolution
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert resolution["resolved_action_key"] == "WEB_REFERENCE_DIGEST_MULTI_SIGNAL"
    assert resolution["reason_code"] == "custom_action_multi_signal_match"
    assert resolution["winning_signals"] == ["SOURCE_PLATFORM", "STRUCTURE"]
    assert resolution["candidate_scores"][0]["action_key"] == "WEB_REFERENCE_DIGEST_MULTI_SIGNAL"
    assert resolution["candidate_scores"][0]["matched_signal_types"] == [
        "SOURCE_PLATFORM",
        "STRUCTURE",
    ]
    assert resolution["candidate_scores"][0]["score_breakdown"]["signal_type_count"] == 2
    assert resolution["candidate_scores"][1]["action_key"] == "WEB_REFERENCE_DIGEST_PLATFORM_ONLY"


def test_custom_prompt_recipe_registration_should_extend_catalog_and_runtime() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    recipe_registration = CustomPromptRecipeRegistration.model_validate(
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
            "citation_policy": ["保留核心证据来源。"],
            "recipe_notes": ["强调管理层决策与风险控制。"],
        }
    )
    action_registration = CustomProductionActionRegistration.model_validate(
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
        }
    )

    try:
        recipe = debug_register_custom_prompt_recipe(recipe_registration).recipe
        debug_register_custom_action(action_registration)
        catalog = debug_get_prompt_recipes()
        task_input = _build_task_input(
            task_id="custom-prompt-recipe-job",
            target_id="artifact-custom-prompt-recipe-1",
            action_key="executive_summary",
            style_profile_key="",
            structure_constraints=["输出决策摘要、关键证据、推进建议。"],
            generation_brief="请生成一份管理摘要与推进建议。",
            source_scope=[
                {
                    "source_id": "src-1",
                    "title": "产物模块总设计",
                    "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                }
            ],
        )

        plan = build_execution_plan(task_input)
        _, result = run_artifact_task(task_input)
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert recipe["recipe_id"] == "executive_summary_writer_v2"
    assert any(item["recipe_id"] == "executive_summary_writer_v2" for item in catalog.recipes)
    assert plan.prompt_recipe.recipe_id == "executive_summary_writer_v2"
    assert plan.prompt_recipe.supported_actions == ["EXECUTIVE_SUMMARY"]
    assert plan.prompt_recipe.section_guidance["决策摘要"] == "先给决策结论。"
    assert result.result_payload["context_pack"]["prompt_recipe"]["recipe_id"] == "executive_summary_writer_v2"
    assert result.result_payload["execution_plan"]["prompt_recipe"]["recipe_name"] == "Executive Summary Writer"


def test_custom_prompt_recipe_should_apply_section_and_node_level_overrides() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    recipe_registration = CustomPromptRecipeRegistration.model_validate(
        {
            "recipe_id": "executive_summary_writer_v3",
            "recipe_name": "Executive Summary Writer V3",
            "base_recipe_id": "report_executive_writer_v1",
            "supported_actions": ["EXECUTIVE_SUMMARY"],
            "system_intent": "将结构化报告改写成面向管理层的决策摘要。",
            "section_guidance": {
                "决策摘要": "先给最终决策，再补背景。",
                "关键证据": "只保留支撑决策的证据。",
                "推进建议": "给出下一步推进优先级。",
            },
            "node_guidance": {
                "generic_section_writer": "优先以管理层决策语言生成章节。",
                "style_polisher": "压缩成长句，保留高管摘要语气。",
            },
            "recipe_notes": ["验证 section/node 级 recipe 覆盖。"],
        }
    )
    action_registration = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_summary",
            "display_name": "管理决策摘要",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_prompt_recipe_id": "executive_summary_writer_v3",
            "artifact_type": "EXECUTIVE_SUMMARY",
            "output_sections": ["决策摘要", "关键证据", "推进建议"],
            "required_phrases": ["Production Action", "Capability Union Policy"],
        }
    )

    try:
        debug_register_custom_prompt_recipe(recipe_registration)
        debug_register_custom_action(action_registration)
        _, result = run_artifact_task(
            _build_task_input(
                task_id="custom-node-recipe-job",
                target_id="artifact-custom-node-recipe-1",
                action_key="executive_summary",
                style_profile_key="",
                structure_constraints=["输出决策摘要、关键证据、推进建议。"],
                generation_brief="请生成一份管理摘要与推进建议。",
                source_scope=[
                    {
                        "source_id": "src-1",
                        "title": "产物模块总设计",
                        "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                    }
                ],
            )
        )
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert (
        result.result_payload["context_pack"]["prompt_recipe"]["node_guidance"][
            "generic_section_writer"
        ]
        == "优先以管理层决策语言生成章节。"
    )
    summary_section = next(
        section
        for section in result.result_payload["sections"]
        if section["heading"] == "决策摘要"
    )
    assert "配方重点：先给最终决策，再补背景。" in summary_section["body"]
    assert "节点侧重：优先以管理层决策语言生成章节。" in summary_section["body"]
    node_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "generic_section_writer"
    )
    assert "node guidance applied" in node_trace["output_summary"]
    polish_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "style_polisher"
    )
    assert "node guidance applied" in polish_trace["output_summary"]


def test_custom_prompt_recipe_registration_should_reject_duplicate_custom_recipe() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    recipe_registration = CustomPromptRecipeRegistration.model_validate(
        {
            "recipe_id": "executive_summary_writer_v2",
            "recipe_name": "Executive Summary Writer",
            "base_recipe_id": "report_executive_writer_v1",
            "supported_actions": ["EXECUTIVE_SUMMARY"],
            "system_intent": "将结构化报告改写成面向管理层的决策摘要。",
        }
    )

    try:
        debug_register_custom_prompt_recipe(recipe_registration)
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_prompt_recipe(recipe_registration)
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert "existing custom recipe" in str(exc_info.value)


def test_build_execution_plan_should_accept_custom_prompt_recipe_for_template_action() -> None:
    debug_reset_custom_actions()
    debug_reset_custom_prompt_recipes()
    recipe_registration = CustomPromptRecipeRegistration.model_validate(
        {
            "recipe_id": "executive_summary_writer_v2",
            "recipe_name": "Executive Summary Writer",
            "base_recipe_id": "report_executive_writer_v1",
            "supported_actions": ["REPORT"],
            "system_intent": "将结构化报告改写成面向管理层的决策摘要。",
            "recipe_notes": ["沿用 REPORT 模板 action 兼容性。"],
        }
    )
    action_registration = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_summary",
            "display_name": "管理决策摘要",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_prompt_recipe_id": "executive_summary_writer_v2",
            "artifact_type": "EXECUTIVE_SUMMARY",
            "output_sections": ["决策摘要", "关键证据", "推进建议"],
            "required_phrases": ["Production Action", "Capability Union Policy"],
        }
    )

    try:
        debug_register_custom_prompt_recipe(recipe_registration)
        debug_register_custom_action(action_registration)
        plan = build_execution_plan(
            _build_task_input(
                task_id="custom-template-action-job",
                target_id="artifact-custom-template-action-1",
                action_key="executive_summary",
                style_profile_key="",
                structure_constraints=["输出决策摘要、关键证据、推进建议。"],
                generation_brief="请输出一份管理摘要。",
                source_scope=[
                    {
                        "source_id": "src-1",
                        "title": "产物模块总设计",
                        "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                    }
                ],
            )
        )
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_prompt_recipes()

    assert plan.prompt_recipe.recipe_id == "executive_summary_writer_v2"
    assert plan.template_action_key == "REPORT"
    assert plan.action_key == "EXECUTIVE_SUMMARY"
    assert plan.action_origin == "CUSTOM"


def test_build_execution_plan_should_reject_mismatched_prompt_recipe_override() -> None:
    task_input = _build_task_input(
        task_id="resume-mismatch-job",
        target_id="artifact-resume-mismatch-1",
        action_key="resume_highlight",
        style_profile_key="interview",
        structure_constraints=["输出一句话定位、亮点条目和关键词。"],
        generation_brief=(
            "突出 Controlled Agentic Graph Harness、Schema-Gated Skill Graph Runtime、"
            "Capability Union Policy 和 Verifier / Repair。"
        ),
        source_scope=[
            {
                "source_id": "src-1",
                "title": "产物生成 Agent 编排升级设计",
                "summary": (
                    "设计并实现受控式异步产物生成 Agent，采用 Controlled Agentic Graph "
                    "Harness 主链路，并以 Schema-Gated Skill Graph Runtime 约束执行计划。"
                ),
            }
        ],
        prompt_recipe_id="video_summary_writer_v1",
    )

    with pytest.raises(ValueError) as exc_info:
        build_execution_plan(task_input)

    assert "prompt-recipe mismatch" in str(exc_info.value)


def test_debug_prompt_recipes_should_expose_default_catalog() -> None:
    response = debug_get_prompt_recipes()

    assert response.recipes
    report_recipe = next(
        recipe for recipe in response.recipes if recipe["recipe_id"] == "report_writer_v1"
    )
    quiz_recipe = next(
        recipe for recipe in response.recipes if recipe["recipe_id"] == "quiz_writer_v1"
    )
    assert "supported_actions" not in report_recipe
    assert report_recipe["generation_mode"] == "SECTION_SYNTHESIS"
    assert "supported_actions" not in quiz_recipe
    assert quiz_recipe["generation_mode"] == "QUIZ_SYNTHESIS"


def test_debug_skill_registries_should_expose_default_catalogs() -> None:
    skill_response = debug_get_skill_definitions()
    graph_response = debug_get_skill_graphs()

    assert skill_response.skills
    assert graph_response.graphs
    material_digest = next(
        skill for skill in skill_response.skills if skill["skill_key"] == "workspace_material_digest"
    )
    quiz_designer = next(
        skill for skill in skill_response.skills if skill["skill_key"] == "quiz_designer"
    )
    wiki_structure_enforcer = next(
        skill for skill in skill_response.skills if skill["skill_key"] == "wiki_structure_enforcer"
    )
    generic_graph = next(
        graph for graph in graph_response.graphs if graph["graph_key"] == "generic_artifact_v1"
    )
    quiz_graph = next(
        graph for graph in graph_response.graphs if graph["graph_key"] == "quiz_artifact_v1"
    )
    wiki_graph = next(
        graph for graph in graph_response.graphs if graph["graph_key"] == "wiki_artifact_v1"
    )

    assert material_digest["required_capabilities"] == ["READ_WORKSPACE_DOC"]
    assert quiz_designer["required_capabilities"] == ["GENERATE_STRUCTURED_TEXT"]
    assert wiki_structure_enforcer["required_capabilities"] == ["GENERATE_STRUCTURED_TEXT"]
    assert "action_type" not in generic_graph
    assert "action_type" not in quiz_graph
    assert "action_type" not in wiki_graph
    assert len(generic_graph["nodes"]) >= 3
    assert [node["skill_key"] for node in quiz_graph["nodes"]] == [
        "workspace_material_digest",
        "quiz_designer",
        "quiz_difficulty_normalizer",
        "evidence_guard",
        "style_polisher",
    ]
    assert [node["skill_key"] for node in wiki_graph["nodes"]] == [
        "workspace_material_digest",
        "generic_section_writer",
        "wiki_structure_enforcer",
        "evidence_guard",
        "style_polisher",
    ]


def test_debug_capability_bindings_should_expose_default_catalog() -> None:
    response = debug_get_capability_bindings()

    assert response.bindings
    workspace_read = next(
        binding for binding in response.bindings if binding["capability_name"] == "READ_WORKSPACE_DOC"
    )
    custom_network = next(
        binding for binding in response.bindings if binding["capability_name"] == "CUSTOM_MCP_NETWORK"
    )

    assert workspace_read["scope_type"] == "WORKSPACE_READ"
    assert workspace_read["approval_mode"] == "NEVER"
    assert custom_network["risk_level"] == "HIGH"
    assert "allowed_actions" not in custom_network


def test_custom_skill_definition_and_graph_registration_should_extend_runtime() -> None:
    debug_reset_custom_skill_definitions()
    debug_reset_custom_skill_graphs()
    debug_reset_custom_actions()
    skill_registration = CustomSkillDefinitionRegistration.model_validate(
        {
            "skill_key": "generic_section_writer_executive",
            "base_skill_key": "generic_section_writer",
            "skill_version": "1.1.0",
            "output_contract": ["executive artifact sections"],
            "verifier_policy": ["must preserve executive section framing"],
        }
    )
    graph_registration = CustomSkillGraphTemplateRegistration.model_validate(
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
            "schema_contract": [
                "Executive graph must preserve problem-evidence-recommendation structure."
            ],
        }
    )
    action_registration = CustomProductionActionRegistration.model_validate(
        {
            "action_key": "executive_summary_graph",
            "display_name": "管理决策摘要图谱版",
            "base_action_key": "report",
            "default_style_profile_key": "executive",
            "default_skill_graph_key": "executive_artifact_v1",
            "default_prompt_recipe_id": "report_executive_writer_v1",
            "artifact_type": "EXECUTIVE_SUMMARY_GRAPH",
            "output_sections": ["问题定义", "证据综述", "建议方案"],
            "required_phrases": ["Production Action", "Capability Union Policy"],
        }
    )

    try:
        skill = debug_register_custom_skill_definition(skill_registration).skill
        graph = debug_register_custom_skill_graph(graph_registration).graph
        debug_register_custom_action(action_registration)
        task_input = _build_task_input(
            task_id="custom-skill-graph-job",
            target_id="artifact-custom-skill-graph-1",
            action_key="executive_summary_graph",
            style_profile_key="",
            structure_constraints=["输出问题定义、证据综述、建议方案。"],
            generation_brief="请输出一份管理摘要风格的结构化报告。",
            source_scope=[
                {
                    "source_id": "src-1",
                    "title": "产物模块总设计",
                    "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                }
            ],
        )

        skill_catalog = debug_get_skill_definitions()
        graph_catalog = debug_get_skill_graphs()
        plan = build_execution_plan(task_input)
        _, result = run_artifact_task(task_input)
    finally:
        debug_reset_custom_actions()
        debug_reset_custom_skill_graphs()
        debug_reset_custom_skill_definitions()

    assert skill["skill_key"] == "generic_section_writer_executive"
    assert skill["template_skill_key"] == "generic_section_writer"
    assert graph["graph_key"] == "executive_artifact_v1"
    assert any(
        item["skill_key"] == "generic_section_writer_executive"
        for item in skill_catalog.skills
    )
    assert any(item["graph_key"] == "executive_artifact_v1" for item in graph_catalog.graphs)
    assert plan.skill_graph_key == "executive_artifact_v1"
    assert [node.skill_key for node in plan.node_sequence] == [
        "workspace_material_digest",
        "generic_section_writer_executive",
        "evidence_guard",
        "style_polisher",
    ]
    node_trace = next(
        trace
        for trace in result.result_payload["node_traces"]
        if trace["skill_key"] == "generic_section_writer_executive"
    )
    assert "generated 3 generic artifact sections" in node_trace["output_summary"]


def test_custom_skill_definition_registration_should_reject_duplicate_custom_skill() -> None:
    debug_reset_custom_skill_definitions()
    registration = CustomSkillDefinitionRegistration.model_validate(
        {
            "skill_key": "generic_section_writer_executive",
            "base_skill_key": "generic_section_writer",
        }
    )

    try:
        debug_register_custom_skill_definition(registration)
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_skill_definition(registration)
    finally:
        debug_reset_custom_skill_definitions()

    assert "existing custom skill" in str(exc_info.value)


def test_custom_skill_graph_registration_should_reject_missing_skill_reference() -> None:
    debug_reset_custom_skill_graphs()
    registration = CustomSkillGraphTemplateRegistration.model_validate(
        {
            "graph_key": "broken_graph_v1",
            "graph_name": "Broken Graph",
            "base_graph_key": "generic_artifact_v1",
            "nodes": [
                SkillGraphNode(
                    node_id="digest",
                    skill_key="workspace_material_digest",
                    purpose="Compile workspace material digest.",
                ),
                SkillGraphNode(
                    node_id="write",
                    skill_key="missing_custom_skill",
                    purpose="Broken custom skill reference.",
                ),
            ],
            "edges": [
                SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
            ],
        }
    )

    try:
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_skill_graph(registration)
    finally:
        debug_reset_custom_skill_graphs()

    assert "unknown skill definition" in str(exc_info.value)


def test_custom_skill_graph_registration_should_reject_duplicate_node_id() -> None:
    debug_reset_custom_skill_graphs()
    registration = CustomSkillGraphTemplateRegistration.model_validate(
        {
            "graph_key": "duplicate-node-graph_v1",
            "graph_name": "Duplicate Node Graph",
            "base_graph_key": "generic_artifact_v1",
            "nodes": [
                SkillGraphNode(
                    node_id="digest",
                    skill_key="workspace_material_digest",
                    purpose="Compile workspace material digest.",
                ),
                SkillGraphNode(
                    node_id="digest",
                    skill_key="generic_section_writer",
                    purpose="Generate sections with a conflicting node id.",
                ),
            ],
            "edges": [],
        }
    )

    try:
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_skill_graph(registration)
    finally:
        debug_reset_custom_skill_graphs()

    assert "duplicate node_id" in str(exc_info.value)


def test_custom_skill_graph_registration_should_reject_cyclic_prerequisite_edges() -> None:
    debug_reset_custom_skill_graphs()
    registration = CustomSkillGraphTemplateRegistration.model_validate(
        {
            "graph_key": "cyclic-graph_v1",
            "graph_name": "Cyclic Graph",
            "base_graph_key": "generic_artifact_v1",
            "nodes": [
                SkillGraphNode(
                    node_id="digest",
                    skill_key="workspace_material_digest",
                    purpose="Compile workspace material digest.",
                ),
                SkillGraphNode(
                    node_id="write",
                    skill_key="generic_section_writer",
                    purpose="Generate sections.",
                ),
            ],
            "edges": [
                SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
                SkillGraphEdge(from_node="write", to_node="digest", edge_type="PREREQUISITE"),
            ],
        }
    )

    try:
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_skill_graph(registration)
    finally:
        debug_reset_custom_skill_graphs()

    assert "cyclic prerequisite edges" in str(exc_info.value)


def test_custom_skill_graph_registration_should_reject_unregistered_runtime_node_skill(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    debug_reset_custom_skill_graphs()
    original_resolve_skill_definition = registry_module.resolve_skill_definition

    def patched_resolve_skill_definition(skill_key: str) -> SkillDefinition:
        if skill_key == "unsupported_custom_skill":
            return SkillDefinition(
                skill_key="unsupported_custom_skill",
                template_skill_key="unsupported_runtime_node",
                skill_version="1.0.0",
                skill_type="GENERATION",
                input_contract=["materials"],
                output_contract=["sections"],
                required_capabilities=["GENERATE_STRUCTURED_TEXT"],
                verifier_policy=[],
                repair_policy=[],
            )
        return original_resolve_skill_definition(skill_key)

    monkeypatch.setattr(registry_module, "resolve_skill_definition", patched_resolve_skill_definition)

    registration = CustomSkillGraphTemplateRegistration.model_validate(
        {
            "graph_key": "unsupported-runtime-node-graph_v1",
            "graph_name": "Unsupported Runtime Node Graph",
            "base_graph_key": "generic_artifact_v1",
            "nodes": [
                SkillGraphNode(
                    node_id="digest",
                    skill_key="workspace_material_digest",
                    purpose="Compile workspace material digest.",
                ),
                SkillGraphNode(
                    node_id="write",
                    skill_key="unsupported_custom_skill",
                    purpose="References a skill without a registered runtime node executor.",
                ),
            ],
            "edges": [
                SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
            ],
        }
    )

    try:
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_skill_graph(registration)
    finally:
        debug_reset_custom_skill_graphs()

    assert "unregistered runtime skill" in str(exc_info.value)


def test_build_execution_plan_should_reject_graph_node_with_unregistered_runtime_skill(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_resolve_skill_definition = compiler_module.resolve_skill_definition

    def patched_resolve_skill_definition(skill_key: str) -> SkillDefinition:
        if skill_key == "unsupported_custom_skill":
            return SkillDefinition(
                skill_key="unsupported_custom_skill",
                template_skill_key="unsupported_runtime_node",
                skill_version="1.0.0",
                skill_type="GENERATION",
                input_contract=["materials"],
                output_contract=["sections"],
                required_capabilities=["GENERATE_STRUCTURED_TEXT"],
                verifier_policy=[],
                repair_policy=[],
            )
        return original_resolve_skill_definition(skill_key)

    monkeypatch.setattr(compiler_module, "resolve_skill_definition", patched_resolve_skill_definition)
    monkeypatch.setattr(
        compiler_module,
        "resolve_skill_graph",
        lambda graph_key: SkillGraphTemplate(
            graph_key="unsupported-runtime-node-graph_v1",
            graph_name="Unsupported Runtime Node Graph",
            action_type="GENERIC",
            nodes=[
                SkillGraphNode(
                    node_id="digest",
                    skill_key="workspace_material_digest",
                    purpose="Compile workspace material digest.",
                ),
                SkillGraphNode(
                    node_id="write",
                    skill_key="unsupported_custom_skill",
                    purpose="References a skill without a registered runtime node executor.",
                ),
            ],
            edges=[
                SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
            ],
            schema_contract=[],
        ),
    )

    with pytest.raises(ValueError) as exc_info:
        build_execution_plan(_build_generic_task_input("report"))

    assert "schema gate rejected plan: graph node references unregistered runtime skill" in str(exc_info.value)


def test_custom_style_profile_registration_should_extend_catalog_and_runtime() -> None:
    debug_reset_custom_style_profiles()
    registration = CustomStyleProfileRegistration.model_validate(
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

    try:
        profile = debug_register_custom_style_profile(registration).profile
        catalog = debug_get_style_profiles()
        task_input = _build_task_input(
            task_id="custom-style-profile-job",
            target_id="artifact-custom-style-profile-1",
            action_key="report",
            style_profile_key="founder_pitch",
            structure_constraints=["输出问题定义、证据综述、建议方案。"],
            generation_brief="请生成一份面向创业者路演的架构报告。",
            source_scope=[
                {
                    "source_id": "src-1",
                    "title": "产物模块总设计",
                    "summary": "统一主链路承接 Action、Style、Skill Graph 和 Artifact Runtime。",
                }
            ],
        )

        plan = build_execution_plan(task_input)
        _, result = run_artifact_task(task_input)
    finally:
        debug_reset_custom_style_profiles()

    assert profile["profile_key"] == "FOUNDER_PITCH"
    assert profile["tone"] == "investor-ready"
    assert any(item["profile_key"] == "FOUNDER_PITCH" for item in catalog.profiles)
    assert plan.style_profile_key == "FOUNDER_PITCH"
    assert (
        result.result_payload["context_pack"]["style_profile"]["profile_key"] == "FOUNDER_PITCH"
    )
    assert (
        result.result_payload["context_pack"]["style_profile"]["structure_mode"]
        == "value_first"
    )
    assert (
        result.result_payload["context_pack"]["style_profile"]["format_constraints"]
        == ["开头先给价值主张。", "每节都尽量保留控制性架构关键词。"]
    )
    assert (
        result.result_payload["context_pack"]["user_preference"]["style_profile_key"]
        == "FOUNDER_PITCH"
    )
    assert (
        result.result_payload["context_pack"]["user_preference"]["style_profile_name"]
        == "Founder Pitch"
    )
    assert (
        result.result_payload["execution_plan"]["style_profile_name"] == "Founder Pitch"
    )


def test_custom_style_profile_registration_should_reject_duplicate_custom_profile() -> None:
    debug_reset_custom_style_profiles()
    registration = CustomStyleProfileRegistration.model_validate(
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

    try:
        debug_register_custom_style_profile(registration)
        with pytest.raises(ValueError) as exc_info:
            debug_register_custom_style_profile(registration)
    finally:
        debug_reset_custom_style_profiles()

    assert "existing custom style profile" in str(exc_info.value)


def test_build_execution_plan_should_route_video_file_to_audio_transcription_pipeline() -> None:
    task_input = _build_media_task_input("course_notes")

    plan = build_execution_plan(task_input)

    assert plan.content_acquisition_plan.primary_strategy == "VIDEO_AUDIO_TRANSCRIPTION_PIPELINE"
    assert plan.content_acquisition_plan.required_capabilities == ["TRANSCRIBE_AUDIO"]
    assert [step.step_type for step in plan.content_acquisition_plan.steps] == [
        "RESOLVE_VIDEO_SOURCE",
        "EXTRACT_AUDIO_TRACK",
        "TRANSCRIBE_AUDIO",
        "NORMALIZE_TO_CCO",
    ]


def test_run_artifact_task_should_dynamically_select_bilibili_subtitle_provider() -> None:
    task_input = _build_media_task_input("video_summary")

    _, result = run_artifact_task(task_input)

    selected_binding = result.result_payload["capability_resolution"]["resolved_bindings"][1]

    assert selected_binding["capability_name"] == "EXTRACT_TRANSCRIPT"
    assert selected_binding["server_id"] == "builtin-bilibili-mcp"
    assert selected_binding["tool_name"] == "get_subtitle"
    assert selected_binding["selection_reason"] == "preferred_bilibili_subtitle_provider"


def test_final_contract_should_reject_missing_resume_sections_and_keywords() -> None:
    task_input = _build_resume_task_input()
    plan = build_execution_plan(task_input)
    sections = [
        ArtifactSectionDraft(
            heading="简历亮点",
            body="- 设计并实现受控式异步产物生成 Agent 主链路。",
            source_refs=["产物生成 Agent 编排升级设计"],
        )
    ]
    verification = verify_artifact_output(
        ArtifactTaskResult(
            result_title=plan.action_display_name,
            result_payload={
                "markdown": "### 简历亮点\n- 设计并实现受控式异步产物生成 Agent 主链路。",
                "sections": [section.model_dump(mode="json") for section in sections],
            },
            trace_summary="resume final-contract rejection test",
            citations=[],
            job_snapshot=ArtifactJobSnapshot(
                task_id=task_input.task_id,
                workspace_id=task_input.workspace_id,
                target_id=task_input.target_id,
                action_key=plan.action_key,
                status="COMPLETED",
            ),
            version_snapshot=ArtifactVersionSnapshot(
                version_id="artifact-resume-invalid-v1",
                artifact_type=plan.artifact_type,
                title=plan.action_display_name,
                status="DRAFT",
                summary="resume final-contract rejection test",
            ),
        ),
        plan,
        [],
    )

    assert verification.status == "FAIL"
    assert "section missing after repair: 一句话定位" in verification.failed_checks
    assert "section missing after repair: 关键词" in verification.failed_checks
    assert "required phrase missing after repair: Controlled Agentic Graph Harness" in verification.failed_checks


def test_node_level_verifier_should_repair_quiz_node_output() -> None:
    task_input = _build_generic_task_input("quiz", style_profile_key="teaching")
    plan = build_execution_plan(task_input)
    skill = resolve_skill_definition("quiz_designer")

    verified_output, verification_status, verification_checks, repair_actions = (
        _verify_and_repair_skill_output(
            skill=skill,
            output={
                "sections": [
                    ArtifactSectionDraft(
                        heading="测验目标",
                        body="验证统一主链路理解。",
                        source_refs=["产物模块总设计"],
                    ),
                    ArtifactSectionDraft(
                        heading="题目设计",
                        body="1. [基础题] Production Action 负责什么？",
                        source_refs=["产物模块总设计"],
                    ),
                    ArtifactSectionDraft(
                        heading="答案与解析",
                        body="- 基础题：定义生成目标。",
                        source_refs=["产物模块总设计"],
                    ),
                    ArtifactSectionDraft(
                        heading="评分要点",
                        body="- 基础：解释对象职责。",
                        source_refs=["产物模块总设计"],
                    ),
                ]
            },
            task_input=task_input,
            plan=plan,
            state={"sections": []},
        )
    )

    repaired_question_section = next(
        section
        for section in verified_output["sections"]
        if section.heading == "题目设计"
    )
    assert verification_status == "PASS_WITH_REPAIR"
    assert "quiz question contract preserved" in verification_checks
    assert "quiz question backfilled at node level" in repair_actions
    assert sum(
        1
        for line in repaired_question_section.body.splitlines()
        if line.lstrip().startswith(("1.", "2.", "3.", "4.", "5."))
    ) >= 3


def test_final_contract_should_reject_incomplete_quiz_structure() -> None:
    task_input = _build_generic_task_input("quiz", style_profile_key="teaching")
    plan = build_execution_plan(task_input)
    sections = [
        ArtifactSectionDraft(
            heading=heading,
            body=(
                "1. [基础题] Production Action 负责什么？"
                if heading == plan.outline[1]
                else "- 基础：只检查对象定义。"
            ),
            source_refs=["产物模块总设计"],
        )
        for heading in plan.outline
    ]

    quiz_markdown = "\n\n".join(
        [f"### {section.heading}\n{section.body}" for section in sections]
        + ["\n".join(plan.required_phrases)]
    )
    verification = verify_artifact_output(
        ArtifactTaskResult(
            result_title=plan.action_display_name,
            result_payload={
                "markdown": quiz_markdown,
                "sections": [section.model_dump(mode="json") for section in sections],
                "evidence_coverage": {
                    "status": "PASS",
                    "required_citation_density": "MEDIUM",
                    "section_count": 4,
                    "covered_section_count": 4,
                    "coverage_ratio": 1.0,
                    "supporting_source_ids": ["src-1"],
                    "sections_missing_evidence": [],
                },
            },
            trace_summary="quiz final-contract rejection test",
            citations=[{"title": "产物模块总设计", "source_id": "src-1"}],
            job_snapshot=ArtifactJobSnapshot(
                task_id=task_input.task_id,
                workspace_id=task_input.workspace_id,
                target_id=task_input.target_id,
                action_key=plan.action_key,
                status="COMPLETED",
            ),
            version_snapshot=ArtifactVersionSnapshot(
                version_id="artifact-quiz-1-v1",
                artifact_type=plan.artifact_type,
                title=plan.action_display_name,
                status="DRAFT",
                summary="quiz final-contract rejection test",
            ),
        ),
        plan,
        [],
    )

    assert verification.status == "FAIL"
    assert "quiz question count below minimum" in verification.failed_checks
    assert "quiz scoring tiers missing" in verification.failed_checks


def test_final_contract_should_reject_incomplete_wiki_structure() -> None:
    task_input = _build_generic_task_input("wiki_page", style_profile_key="wiki")
    plan = build_execution_plan(task_input)
    sections = [
        ArtifactSectionDraft(
            heading=plan.outline[0],
            body="这是一个受控产物模块的简介。",
            source_refs=["产物模块总设计"],
        ),
        ArtifactSectionDraft(
            heading=plan.outline[1],
            body="Skill Graph 用于组织执行步骤。",
            source_refs=["产物模块总设计"],
        ),
        ArtifactSectionDraft(
            heading=plan.outline[2],
            body="- 延伸阅读：运行时草图",
            source_refs=["产物模块总设计"],
        ),
    ]

    wiki_markdown = "\n\n".join(
        [f"### {section.heading}\n{section.body}" for section in sections]
        + ["\n".join(plan.required_phrases)]
    )
    verification = verify_artifact_output(
        ArtifactTaskResult(
            result_title=plan.action_display_name,
            result_payload={
                "markdown": wiki_markdown,
                "sections": [section.model_dump(mode="json") for section in sections],
                "evidence_coverage": {
                    "status": "PASS",
                    "required_citation_density": "MEDIUM",
                    "section_count": 3,
                    "covered_section_count": 3,
                    "coverage_ratio": 1.0,
                    "supporting_source_ids": ["src-1"],
                    "sections_missing_evidence": [],
                },
            },
            trace_summary="wiki final-contract rejection test",
            citations=[{"title": "产物模块总设计", "source_id": "src-1"}],
            job_snapshot=ArtifactJobSnapshot(
                task_id=task_input.task_id,
                workspace_id=task_input.workspace_id,
                target_id=task_input.target_id,
                action_key=plan.action_key,
                status="COMPLETED",
            ),
            version_snapshot=ArtifactVersionSnapshot(
                version_id="artifact-wiki-1-v1",
                artifact_type=plan.artifact_type,
                title=plan.action_display_name,
                status="DRAFT",
                summary="wiki final-contract rejection test",
            ),
        ),
        plan,
        [],
    )

    assert verification.status == "FAIL"
    assert "wiki overview definition or boundary missing" in verification.failed_checks
    assert "wiki key mechanisms missing" in verification.failed_checks
    assert "wiki related page count below minimum" in verification.failed_checks


def test_verify_artifact_output_should_fail_when_evidence_coverage_is_unsatisfied() -> None:
    task_input = _build_generic_task_input("report")
    plan = build_execution_plan(task_input)
    result = ArtifactTaskResult(
        result_title=plan.action_display_name,
        result_payload={
            "markdown": (
                "### 问题定义\n可读草稿，包含 Production Action。\n\n"
                "### 证据综述\n缺少来源标注的证据段，但提到 Schema-Gated Skill Graph Runtime。\n\n"
                "### 建议方案\n建议继续完善 Artifact Version。"
            ),
            "sections": [
                {
                    "heading": "问题定义",
                    "body": "可读草稿。",
                    "source_refs": ["产物模块总设计"],
                },
                {
                    "heading": "证据综述",
                    "body": "缺少来源标注的证据段。",
                    "source_refs": [],
                },
                {
                    "heading": "建议方案",
                    "body": "建议继续完善。",
                    "source_refs": [],
                },
            ],
            "evidence_coverage": {
                "status": "FAIL",
                "required_citation_density": "MEDIUM",
                "section_count": 3,
                "covered_section_count": 1,
                "coverage_ratio": 0.33,
                "supporting_source_ids": ["src-1"],
                "sections_missing_evidence": ["证据综述", "建议方案"],
                "notes": ["evidence coverage fell below required density"],
            },
        },
        trace_summary="verification-only test",
        citations=[{"title": "产物模块总设计", "source_id": "src-1"}],
        job_snapshot=ArtifactJobSnapshot(
            task_id=task_input.task_id,
            workspace_id=task_input.workspace_id,
            target_id=task_input.target_id,
            action_key=plan.action_key,
            status="COMPLETED",
        ),
        version_snapshot=ArtifactVersionSnapshot(
            version_id="artifact-report-1-v1",
            artifact_type=plan.artifact_type,
            title=plan.action_display_name,
            status="DRAFT",
            summary="verification-only test version",
        ),
    )

    verification = verify_artifact_output(result, plan, [])
    contract_trace = build_output_contract_trace(result, plan, [])

    assert verification.status == "FAIL"
    assert "evidence coverage unsatisfied: MEDIUM" in verification.failed_checks
    assert "sections missing evidence: 证据综述, 建议方案" in verification.failed_checks
    assert contract_trace.status == "FAIL"
    assert any(
        check["label"] == "evidence coverage" and check["status"] == "FAIL"
        for check in contract_trace.model_dump(mode="json")["evidence_checks"]
    )


def test_build_output_contract_trace_should_aggregate_repair_summary() -> None:
    task_input = _build_resume_task_input()
    plan = build_execution_plan(task_input)
    result = ArtifactTaskResult(
        result_title=plan.action_display_name,
        result_payload={
            "markdown": (
                "### 一句话定位\n受控运行时定位。\n\n"
                "### 简历亮点\n- 亮点一\n- 亮点二\n- 亮点三\n\n"
                "### 关键词\nControlled Agentic Graph Harness / Schema-Gated Skill Graph Runtime / Capability Union Policy"
            ),
            "sections": [
                {
                    "heading": "一句话定位",
                    "body": "受控运行时定位。",
                    "source_refs": ["src-1"],
                },
                {
                    "heading": "简历亮点",
                    "body": "- 亮点一\n- 亮点二\n- 亮点三",
                    "source_refs": ["src-1"],
                },
                {
                    "heading": "关键词",
                    "body": "Controlled Agentic Graph Harness / Schema-Gated Skill Graph Runtime / Capability Union Policy",
                    "source_refs": ["src-1"],
                },
            ],
            "evidence_coverage": {
                "status": "PASS",
                "required_citation_density": "MEDIUM",
                "section_count": 3,
                "covered_section_count": 3,
                "coverage_ratio": 1.0,
                "supporting_source_ids": ["src-1"],
                "sections_missing_evidence": [],
                "notes": [],
            },
            "node_traces": [
                {
                    "node_id": "extract",
                    "skill_key": "resume_highlight_extractor",
                    "output_summary": "extracted 3 highlight candidates",
                    "verification_status": "PASS_WITH_REPAIR",
                    "verification_checks": ["resume highlight candidate count satisfied"],
                    "repair_actions": ["resume highlight candidate backfilled"],
                    "repaired": True,
                },
                {
                    "node_id": "write",
                    "skill_key": "resume_local_repair",
                    "output_summary": "closed resume verifier gaps with targeted local repair",
                    "verification_status": "PASS_WITH_REPAIR",
                    "verification_checks": ["resume local repair confirmed verifier gaps are closed"],
                    "repair_actions": ["resume keyword backfilled at node level: Capability Union Policy"],
                    "repaired": True,
                },
            ],
        },
        trace_summary="repair summary contract test",
        citations=[{"title": "Artifact Runtime Brain", "source_id": "src-1"}],
        job_snapshot=ArtifactJobSnapshot(
            task_id=task_input.task_id,
            workspace_id=task_input.workspace_id,
            target_id=task_input.target_id,
            action_key=plan.action_key,
            status="COMPLETED",
        ),
        version_snapshot=ArtifactVersionSnapshot(
            version_id="artifact-resume-repair-summary-v1",
            artifact_type=plan.artifact_type,
            title=plan.action_display_name,
            status="DRAFT",
            summary="repair summary contract version",
        ),
    )

    local_repairs = [
        "missing section repaired: 一句话定位",
        "keyword repaired: Capability Union Policy",
    ]
    contract_trace = build_output_contract_trace(result, plan, local_repairs)

    repair_summary = contract_trace.model_dump(mode="json")["repair_summary"]
    assert repair_summary["total_repair_count"] == 4
    assert repair_summary["local_repair_count"] == 2
    assert repair_summary["node_repair_count"] == 2
    assert repair_summary["affected_sections"] == ["一句话定位", "关键词"]
    assert repair_summary["affected_nodes"] == ["resume_highlight_extractor", "resume_local_repair"]
    assert repair_summary["local_repair_checks"] == local_repairs
    assert repair_summary["node_repair_actions"] == [
        "resume highlight candidate backfilled",
        "resume keyword backfilled at node level: Capability Union Policy",
    ]
    assert repair_summary["category_counts"]["missing_section"] == 1
    assert repair_summary["category_counts"]["keyword"] == 1
    assert repair_summary["category_counts"]["node_resume_highlight_candidate"] == 1
    assert repair_summary["category_counts"]["node_resume_keyword"] == 1


def test_build_execution_plan_should_reject_high_risk_custom_mcp_union() -> None:
    task_input = _build_resume_task_input(requested_capabilities=["CUSTOM_MCP_NETWORK"])

    with pytest.raises(ValueError) as exc_info:
        build_execution_plan(task_input)

    assert "capability union policy rejected plan" in str(exc_info.value)
    assert "workspace_material_external_network_union" in str(exc_info.value)


def test_build_execution_plan_should_flag_writeback_gate_for_disallowed_action() -> None:
    task_input = _build_generic_task_input("report")
    task_input.input_payload.writeback_mode = "WIKI_PAGE"

    plan = build_execution_plan(task_input)

    assert plan.writeback_gate.decision == "DENY"
    assert plan.writeback_gate.reason_code == "writeback_target_not_allowed_for_action"


def test_build_execution_plan_should_allow_export_file_writeback_for_wiki_page() -> None:
    task_input = _build_generic_task_input("wiki_page", style_profile_key="wiki")
    task_input.input_payload.writeback_mode = "EXPORT_FILE"

    plan = build_execution_plan(task_input)

    assert "EXPORT_ARTIFACT_FILE" in plan.lazy_loaded_capabilities
    assert plan.approval_gate.decision == "APPROVAL_REQUIRED"
    assert "EXPORT_ARTIFACT_FILE" in plan.approval_gate.required_capabilities
    assert plan.writeback_gate.decision == "ALLOW"
    assert plan.writeback_gate.allowed_target == "EXPORT_FILE"
    assert plan.writeback_gate.execution_mode == "HOST_MANAGED_PREVIEW"
    assert plan.writeback_gate.required_capabilities == ["EXPORT_ARTIFACT_FILE"]


def test_build_execution_plan_should_allow_save_as_source_writeback_for_structured_note() -> None:
    task_input = _build_generic_task_input("structured_note")
    task_input.input_payload.writeback_mode = "SAVE_AS_SOURCE"

    plan = build_execution_plan(task_input)

    assert "SAVE_WORKSPACE_SOURCE" in plan.lazy_loaded_capabilities
    assert plan.writeback_gate.decision == "ALLOW"
    assert plan.writeback_gate.allowed_target == "SAVE_AS_SOURCE"
    assert plan.writeback_gate.required_capabilities == ["SAVE_WORKSPACE_SOURCE"]


def test_build_execution_plan_should_deny_unknown_writeback_mode() -> None:
    task_input = _build_generic_task_input("report")
    task_input.input_payload.writeback_mode = "SPACE_PORTAL"

    plan = build_execution_plan(task_input)

    assert plan.writeback_gate.decision == "DENY"
    assert plan.writeback_gate.reason_code == "unknown_writeback_mode"


def test_run_artifact_task_should_emit_writeback_preview_for_allowed_mode() -> None:
    task_input = _build_generic_task_input("structured_note")
    task_input.input_payload.writeback_mode = "SAVE_AS_SOURCE"

    _, result = run_artifact_task(task_input)

    preview = result.result_payload["writeback_preview"]

    assert preview["status"] == "READY_FOR_HOST_WRITEBACK"
    assert preview["requested_mode"] == "SAVE_AS_SOURCE"
    assert preview["allowed_target"] == "SAVE_AS_SOURCE"
    assert preview["execution_mode"] == "HOST_MANAGED_PREVIEW"
    assert preview["required_capabilities"] == ["SAVE_WORKSPACE_SOURCE"]
    assert preview["version_id"] == result.version_snapshot.version_id
    assert preview["request_id"].startswith("writeback-")
    assert preview["target_locator_preview"].startswith("workspace-source://")
    assert result.result_payload["writeback_request"]["request_id"] == preview["request_id"]
    assert "- Writeback gate: ALLOW (SAVE_AS_SOURCE)" in result.result_payload["markdown"]
