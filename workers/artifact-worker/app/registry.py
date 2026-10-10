from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from threading import Lock

from app.models import (
    CapabilityBinding,
    CustomMcpServerRegistration,
    CustomMcpToolRegistration,
    CustomProductionActionRegistration,
    CustomPromptRecipeRegistration,
    CustomSkillDefinitionRegistration,
    CustomSkillGraphTemplateRegistration,
    CustomStyleProfileRegistration,
    PromptRecipe,
    ProductionAction,
    SkillDefinition,
    SkillGraphEdge,
    SkillGraphNode,
    SkillGraphTemplate,
    StyleProfile,
)
from app.runtime_node_registry import ensure_runtime_node_skill_registered


logger = logging.getLogger(__name__)


_custom_action_lock = Lock()
_custom_prompt_recipe_lock = Lock()
_custom_skill_definition_lock = Lock()
_custom_skill_graph_lock = Lock()
_custom_style_profile_lock = Lock()
_custom_mcp_server_lock = Lock()
_custom_store_lock = Lock()
_custom_production_actions: dict[str, ProductionAction] = {}
_custom_prompt_recipes: dict[str, PromptRecipe] = {}
_custom_skill_definitions: dict[str, SkillDefinition] = {}
_custom_skill_graph_templates: dict[str, SkillGraphTemplate] = {}
_custom_style_profiles: dict[str, StyleProfile] = {}
_custom_mcp_servers: dict[str, CustomMcpServerRegistration] = {}
_custom_artifact_config_store_path: Path | None = None


def _default_bilibili_render_pdf_blueprint() -> CustomMcpServerRegistration:
    repo_root = Path(__file__).resolve().parents[2]
    server_script = repo_root / "mcp_servers" / "bilibili_render_pdf_server.py"
    launcher_script = repo_root / "mcp_servers" / "launch_bilibili_render_pdf_mcp.ps1"
    env_prefix = repo_root / ".conda" / "bilibili-render-pdf-mcp"
    return CustomMcpServerRegistration(
        server_id="custom-bilibili-render-pdf",
        display_name="Bilibili Render PDF MCP",
        endpoint_kind="mcp",
        launch_transport="stdio",
        launch_command=r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
        launch_args=[
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(launcher_script),
        ],
        working_directory=str(repo_root),
        launch_env={
            "NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_ENV_PREFIX": str(env_prefix),
            "NOTEWEAVE_BILIBILI_RENDER_PDF_MCP_SERVER_SCRIPT": str(server_script),
        },
        blueprint_key="bilibili_render_pdf_v1",
        registration_origin="BLUEPRINT",
        server_notes=[
            "Launch-ready custom MCP blueprint adapted from the bilibili-render-pdf skill.",
            "Runs as a local stdio JSON-RPC MCP server inside the independent artifact-worker module.",
            "The launcher uses a dedicated conda environment rooted at workers/artifact-worker/.conda/bilibili-render-pdf-mcp.",
        ],
        tools=[
            CustomMcpToolRegistration(
                capability_name="EXTRACT_TRANSCRIPT",
                tool_name="get_bilibili_subtitle",
                supported_routes=["VIDEO_URL"],
                supported_actions=["VIDEO_SUMMARY", "COURSE_NOTES"],
                preference_rank=320,
                selection_reason_hint="custom_bilibili_render_pdf_subtitle",
                notes=[
                    "Normalizes bilibili/b23 links and executes remote subtitle fetch or transcription fallback through the custom MCP runtime.",
                ],
            ),
            CustomMcpToolRegistration(
                capability_name="TRANSCRIBE_AUDIO",
                tool_name="transcribe_local_audio",
                supported_routes=["VIDEO_FILE", "AUDIO_FILE"],
                supported_actions=["AUDIO_MINUTES", "COURSE_NOTES"],
                preference_rank=320,
                selection_reason_hint="custom_bilibili_render_pdf_transcribe",
                notes=[
                    "Wraps the local faster-whisper transcription path from the bilibili-render-pdf skill.",
                ],
            ),
            CustomMcpToolRegistration(
                capability_name="EXPORT_ARTIFACT_FILE",
                tool_name="render_latex_pdf",
                supported_routes=["VIDEO_URL", "VIDEO_FILE", "AUDIO_FILE"],
                supported_actions=["COURSE_NOTES"],
                preference_rank=320,
                selection_reason_hint="custom_bilibili_render_pdf_export",
                notes=[
                    "Builds a controlled LaTeX course note from structured sections and writes a .tex artifact.",
                ],
            ),
        ],
    )


PRODUCTION_ACTIONS = {
    "SLIDE_DECK": ProductionAction(
        action_key="SLIDE_DECK", display_name="视频学习 PPTX",
        artifact_type="SLIDE_DECK", default_style_profile_key="TEACHING",
        default_skill_graph_key="video_deck_v1",
        default_prompt_recipe_id="video_deck_frozen_v1",
        required_evidence_level="HIGH", supported_capabilities=["VERIFY_OUTPUT"],
        output_sections=["原画面与证据页"],
    ),
    "KNOWLEDGE_BLOG": ProductionAction(
        action_key="KNOWLEDGE_BLOG", display_name="视频知识博客",
        artifact_type="KNOWLEDGE_BLOG", default_style_profile_key="TEACHING",
        default_skill_graph_key="video_derived_text_v1",
        default_prompt_recipe_id="knowledge_blog_frozen_v1",
        required_evidence_level="HIGH", supported_capabilities=["VERIFY_OUTPUT"],
        output_sections=["证据章节"],
    ),
    "INTERVIEW_QA": ProductionAction(
        action_key="INTERVIEW_QA", display_name="视频面试问答",
        artifact_type="INTERVIEW_QA", default_style_profile_key="INTERVIEW",
        default_skill_graph_key="video_derived_text_v1",
        default_prompt_recipe_id="interview_qa_frozen_v1",
        required_evidence_level="HIGH", supported_capabilities=["VERIFY_OUTPUT"],
        output_sections=["简要回答", "详细问答", "相关知识"],
    ),
    "REPORT": ProductionAction(
        action_key="REPORT",
        display_name="\u7ed3\u6784\u5316\u62a5\u544a",
        artifact_type="REPORT",
        action_origin="BUILTIN",
        default_style_profile_key="DEFAULT",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="report_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u95ee\u9898\u5b9a\u4e49",
            "\u8bc1\u636e\u7efc\u8ff0",
            "\u5efa\u8bae\u65b9\u6848",
        ],
        required_phrases=[
            "Production Action",
            "Schema-Gated Skill Graph Runtime",
            "Artifact Version",
        ],
    ),
    "FAQ": ProductionAction(
        action_key="FAQ",
        display_name="FAQ \u8349\u7a3f",
        artifact_type="FAQ",
        action_origin="BUILTIN",
        default_style_profile_key="DEFAULT",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="faq_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u95ee\u9898\u96c6",
            "\u6807\u51c6\u56de\u7b54",
            "\u4f7f\u7528\u8bf4\u660e",
        ],
        required_phrases=[
            "Production Action",
            "Capability Union Policy",
            "Verifier / Repair",
        ],
    ),
    "QUIZ": ProductionAction(
        action_key="QUIZ",
        display_name="\u6d4b\u9a8c\u8349\u7a3f",
        artifact_type="QUIZ",
        action_origin="BUILTIN",
        default_style_profile_key="TEACHING",
        default_skill_graph_key="quiz_artifact_v1",
        default_prompt_recipe_id="quiz_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u6d4b\u9a8c\u76ee\u6807",
            "\u9898\u76ee\u8bbe\u8ba1",
            "\u7b54\u6848\u4e0e\u89e3\u6790",
            "\u8bc4\u5206\u8981\u70b9",
        ],
        required_phrases=[
            "Schema-Gated Skill Graph Runtime",
            "Capability Union Policy",
            "Verifier / Repair",
        ],
    ),
    "STUDY_GUIDE": ProductionAction(
        action_key="STUDY_GUIDE",
        display_name="\u5b66\u4e60\u6307\u5357",
        artifact_type="STUDY_GUIDE",
        action_origin="BUILTIN",
        default_style_profile_key="TEACHING",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="study_guide_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u5b66\u4e60\u76ee\u6807",
            "\u6838\u5fc3\u6982\u5ff5",
            "\u7ec3\u4e60\u8def\u5f84",
        ],
        required_phrases=[
            "Skill Graph",
            "Schema Gate",
            "Local Repair",
        ],
    ),
    "WIKI_PAGE": ProductionAction(
        action_key="WIKI_PAGE",
        display_name="Wiki \u9875\u9762\u8349\u7a3f",
        artifact_type="WIKI_PAGE",
        action_origin="BUILTIN",
        default_style_profile_key="WIKI",
        default_skill_graph_key="wiki_artifact_v1",
        default_prompt_recipe_id="wiki_page_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE", "WIKI_PAGE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u6982\u89c8",
            "\u5173\u952e\u673a\u5236",
            "\u76f8\u5173\u9875\u9762",
        ],
        required_phrases=[
            "Controlled Agentic Graph Harness",
            "Skill Graph",
            "Artifact Job",
        ],
    ),
    "MINDMAP": ProductionAction(
        action_key="MINDMAP",
        display_name="思维导图",
        artifact_type="MINDMAP",
        action_origin="BUILTIN",
        default_style_profile_key="DEFAULT",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="mindmap_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE", "NOTE_PAGE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "主题中心",
            "关键概念",
            "证据脉络",
            "核心结论",
            "后续行动",
        ],
        required_phrases=[],
    ),
    "STRUCTURED_NOTE": ProductionAction(
        action_key="STRUCTURED_NOTE",
        display_name="\u7ed3\u6784\u5316\u7b14\u8bb0",
        artifact_type="STRUCTURED_NOTE",
        action_origin="BUILTIN",
        default_style_profile_key="DEFAULT",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="structured_note_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE", "NOTE_PAGE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u4e3b\u9898\u5feb\u7167",
            "\u5173\u952e\u6458\u5f55",
            "\u540e\u7eed\u95ee\u9898",
        ],
        required_phrases=[
            "Artifact Runtime",
            "Evidence Gate",
            "Verifier / Repair",
        ],
    ),
    "VIDEO_SUMMARY": ProductionAction(
        action_key="VIDEO_SUMMARY",
        display_name="\u89c6\u9891\u603b\u7ed3",
        artifact_type="VIDEO_SUMMARY",
        action_origin="BUILTIN",
        default_style_profile_key="DEFAULT",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="video_summary_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "EXTRACT_TRANSCRIPT",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u4e00\u53e5\u8bdd\u603b\u7ed3",
            "\u65f6\u95f4\u7ebf\u6458\u8981",
            "\u6838\u5fc3\u89c2\u70b9",
            "\u5173\u952e\u6982\u5ff5",
            "\u53ef\u6c89\u6dc0\u8981\u70b9",
        ],
        required_phrases=[
            "Controlled Agentic Graph Harness",
            "Schema Gate",
            "Capability Layer",
        ],
    ),
    "AUDIO_MINUTES": ProductionAction(
        action_key="AUDIO_MINUTES",
        display_name="\u97f3\u9891\u7eaa\u8981",
        artifact_type="AUDIO_MINUTES",
        action_origin="BUILTIN",
        default_style_profile_key="DEFAULT",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="audio_minutes_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "TRANSCRIBE_AUDIO",
            "READ_WEB_PAGE",
        ],
        output_sections=[
            "\u603b\u4f53\u6458\u8981",
            "\u4e3b\u8981\u8bae\u9898",
            "\u5173\u952e\u7ed3\u8bba",
            "\u884c\u52a8\u9879",
            "\u5f85\u786e\u8ba4\u95ee\u9898",
        ],
        required_phrases=[
            "Artifact Runtime",
            "Local Repair",
            "Artifact Version",
        ],
    ),
    "COURSE_NOTES": ProductionAction(
        action_key="COURSE_NOTES",
        display_name="\u8bfe\u7a0b\u7b14\u8bb0",
        artifact_type="COURSE_NOTES",
        action_origin="BUILTIN",
        default_style_profile_key="TEACHING",
        default_skill_graph_key="generic_artifact_v1",
        default_prompt_recipe_id="course_notes_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
            "EXTRACT_TRANSCRIPT",
            "TRANSCRIBE_AUDIO",
            "CAPTURE_VIDEO_FRAMES",
            "ANALYZE_FRAME",
        ],
        output_sections=[
            "\u8bfe\u7a0b\u6982\u8981",
            "\u77e5\u8bc6\u70b9",
            "\u91cd\u70b9\u96be\u70b9",
            "\u590d\u4e60\u9898",
        ],
        required_phrases=[
            "Style Profile",
            "Skill Graph",
            "Verifier / Repair",
        ],
    ),
    "RESUME_HIGHLIGHT": ProductionAction(
        action_key="RESUME_HIGHLIGHT",
        display_name="\u7b80\u5386\u4eae\u70b9\u63cf\u8ff0",
        artifact_type="RESUME_HIGHLIGHT",
        action_origin="BUILTIN",
        default_style_profile_key="INTERVIEW",
        default_skill_graph_key="resume_highlight_v1",
        default_prompt_recipe_id="resume_highlight_writer_v1",
        required_evidence_level="MEDIUM",
        allow_custom_mcp=True,
        allow_writeback=True,
        allowed_writeback_modes=["ARTIFACT_VERSION", "EXPORT_FILE", "SAVE_AS_SOURCE"],
        supported_capabilities=[
            "READ_WORKSPACE_DOC",
            "GENERATE_STRUCTURED_TEXT",
            "VERIFY_OUTPUT",
        ],
        output_sections=[
            "\u4e00\u53e5\u8bdd\u5b9a\u4f4d",
            "\u7b80\u5386\u4eae\u70b9",
            "\u5173\u952e\u8bcd",
        ],
        required_phrases=[
            "Controlled Agentic Graph Harness",
            "Schema-Gated Skill Graph Runtime",
            "Capability Union Policy",
            "Artifact Job / Artifact Version",
            "Verifier / Repair",
        ],
    ),
}


STYLE_PROFILES = {
    "DEFAULT": StyleProfile(
        profile_key="DEFAULT",
        profile_name="\u9ed8\u8ba4\u98ce\u683c",
        tone="balanced",
        structure_mode="conclusion_first",
        audience_type="team",
        length_preference="medium",
        citation_density="medium",
        format_constraints=["\u4fdd\u7559\u7ed3\u6784\u5316\u7ae0\u8282\u3002"],
    ),
    "INTERVIEW": StyleProfile(
        profile_key="INTERVIEW",
        profile_name="\u9762\u8bd5\u4eae\u70b9\u98ce\u683c",
        tone="crisp",
        structure_mode="impact_first",
        audience_type="interviewer",
        length_preference="short",
        citation_density="medium",
        format_constraints=[
            "\u4f18\u5148\u4f7f\u7528\u7ed3\u679c\u5bfc\u5411\u8868\u8fbe\u3002",
            "\u6bcf\u6761\u4eae\u70b9\u5c3d\u91cf\u5305\u542b\u65b9\u6cd5\u4e0e\u6536\u76ca\u3002",
            "\u4fdd\u7559\u5173\u952e\u82f1\u6587\u672f\u8bed\u539f\u6587\u3002",
        ],
    ),
    "TEACHING": StyleProfile(
        profile_key="TEACHING",
        profile_name="\u6559\u5b66\u8bf4\u660e\u98ce\u683c",
        tone="didactic",
        structure_mode="progressive",
        audience_type="teammate",
        length_preference="medium",
        citation_density="medium",
        format_constraints=[
            "\u89e3\u91ca\u8981\u6309\u6982\u5ff5\u9012\u8fdb\u5c55\u5f00\u3002",
            "\u6bcf\u8282\u5c3d\u91cf\u5305\u542b\u4e3a\u4ec0\u4e48\u3001\u505a\u4ec0\u4e48\u3001\u600e\u4e48\u9a8c\u8bc1\u3002",
        ],
    ),
    "WIKI": StyleProfile(
        profile_key="WIKI",
        profile_name="Wiki \u6c89\u6dc0\u98ce\u683c",
        tone="neutral",
        structure_mode="reference_first",
        audience_type="workspace",
        length_preference="medium",
        citation_density="high",
        format_constraints=[
            "\u4f18\u5148\u7ed9\u51fa\u5b9a\u4e49\u548c\u8fb9\u754c\u3002",
            "\u7ae0\u8282\u547d\u540d\u4fdd\u6301\u77e5\u8bc6\u5e93\u98ce\u683c\u4e00\u81f4\u3002",
        ],
    ),
    "EXECUTIVE": StyleProfile(
        profile_key="EXECUTIVE",
        profile_name="\u7ba1\u7406\u6458\u8981\u98ce\u683c",
        tone="executive",
        structure_mode="summary_first",
        audience_type="leadership",
        length_preference="short",
        citation_density="low",
        format_constraints=["\u805a\u7126\u51b3\u7b56\u4ef7\u503c\u4e0e\u98ce\u9669\u63a7\u5236\u3002"],
    ),
}


PROMPT_RECIPES = {
    "video_deck_frozen_v1": PromptRecipe(
        recipe_id="video_deck_frozen_v1", recipe_name="Frozen Original-Image Deck",
        supported_actions=["SLIDE_DECK"], generation_mode="DETERMINISTIC_EVIDENCE_DERIVATION",
        system_intent="Place only verified original frames and extracted claims into editable slides.",
        citation_policy=["Every slide keeps its frozen frame and claim evidence references."],
        repair_hints=["Reject a slide that differs from the frozen evidence plan."],
    ),
    "knowledge_blog_frozen_v1": PromptRecipe(
        recipe_id="knowledge_blog_frozen_v1", recipe_name="Frozen Video Blog",
        supported_actions=["KNOWLEDGE_BLOG"], generation_mode="DETERMINISTIC_EVIDENCE_DERIVATION",
        system_intent="Render only extracted claims from the Host-frozen video knowledge plan.",
        citation_policy=["Every claim keeps its exact frozen evidence references."],
        repair_hints=["Rebuild only invalid sections from the frozen plan."],
    ),
    "interview_qa_frozen_v1": PromptRecipe(
        recipe_id="interview_qa_frozen_v1", recipe_name="Frozen Video Interview QA",
        supported_actions=["INTERVIEW_QA"], generation_mode="DETERMINISTIC_EVIDENCE_DERIVATION",
        system_intent="Render three-part answers only from Host-frozen extracted claims.",
        citation_policy=["Detailed answers keep exact frozen evidence references."],
        repair_hints=["Rebuild only invalid questions from the frozen plan."],
    ),
    "report_writer_v1": PromptRecipe(
        recipe_id="report_writer_v1",
        recipe_name="Report Writer",
        supported_actions=["REPORT"],
        generation_mode="SECTION_SYNTHESIS",
        system_intent="Synthesize workspace evidence into a structured problem-evidence-recommendation report.",
        section_guidance={
            "问题定义": "先界定问题边界，再说明为什么需要统一产物生成主链路。",
            "证据综述": "优先归纳 Schema Gate、Skill Graph、Capability Union Policy 等证据点。",
            "建议方案": "收敛到受控 runtime、版本化落库与局部修复方案。",
        },
        node_guidance={},
        citation_policy=["保留工作台来源痕迹。", "优先引用一方资料，不凭空扩展。"],
        repair_hints=["如果章节缺失，只重建该章节，不重跑整篇报告。"],
        recipe_notes=["默认报告配方强调问题、证据、建议三段式结构。"],
    ),
    "report_executive_writer_v1": PromptRecipe(
        recipe_id="report_executive_writer_v1",
        recipe_name="Executive Report Writer",
        supported_actions=["REPORT"],
        generation_mode="SECTION_SYNTHESIS",
        system_intent="Frame the report as an executive-facing decision memo with explicit control and rollout tradeoffs.",
        section_guidance={
            "问题定义": "从决策成本、扩展边界和风险控制角度定义问题。",
            "证据综述": "突出能支持决策的架构证据，而不是罗列全部实现细节。",
            "建议方案": "给出决策摘要、实施优先级和风险缓释路径。",
        },
        node_guidance={},
        citation_policy=["保留关键证据来源。", "避免无证据的结论跳跃。"],
        repair_hints=["优先压缩冗余表述，保留决策摘要。"],
        recipe_notes=["决策摘要优先，适合管理摘要与推进建议场景。"],
    ),
    "faq_writer_v1": PromptRecipe(
        recipe_id="faq_writer_v1",
        recipe_name="FAQ Writer",
        supported_actions=["FAQ"],
        generation_mode="SECTION_SYNTHESIS",
        system_intent="Turn implementation knowledge into reusable question-answer guidance.",
        section_guidance={
            "问题集": "覆盖默认产物、门控策略和扩展边界相关高频问题。",
            "标准回答": "给出统一口径，避免实现歧义。",
            "使用说明": "说明触发、验证和后续修复动作。",
        },
        node_guidance={},
        citation_policy=["回答必须能回溯到工作台资料。"],
        repair_hints=["缺少问答时补齐最小闭环问题。"],
        recipe_notes=["FAQ 配方强调团队可复用口径。"],
    ),
    "study_guide_writer_v1": PromptRecipe(
        recipe_id="study_guide_writer_v1",
        recipe_name="Study Guide Writer",
        supported_actions=["STUDY_GUIDE"],
        generation_mode="SECTION_SYNTHESIS",
        system_intent="Teach the controlled artifact runtime progressively for onboarding and self-study.",
        section_guidance={
            "学习目标": "明确先学主链路，再学扩展边界。",
            "核心概念": "按概念递进解释 Action、Style、Skill Graph、Policy Gate。",
            "练习路径": "给出从运行默认产物到扩展 Action 的练习顺序。",
        },
        node_guidance={},
        citation_policy=["高亮关键概念时保留证据来源。"],
        repair_hints=["如果学习路径缺失，优先补练习步骤。"],
        recipe_notes=["学习指南配方强调递进式教学。"],
    ),
    "quiz_writer_v1": PromptRecipe(
        recipe_id="quiz_writer_v1",
        recipe_name="Quiz Writer",
        supported_actions=["QUIZ"],
        generation_mode="QUIZ_SYNTHESIS",
        system_intent="Turn workspace evidence into a verifiable quiz draft with questions, answers, and scoring guidance.",
        section_guidance={
            "\u6d4b\u9a8c\u76ee\u6807": "\u5148\u754c\u5b9a\u6d4b\u9a8c\u8981\u9a8c\u8bc1\u7684\u6982\u5ff5\u548c\u80fd\u529b\u8fb9\u754c\u3002",
            "\u9898\u76ee\u8bbe\u8ba1": "\u9898\u76ee\u9700\u8986\u76d6\u4e3b\u94fe\u8def\u3001schema gate \u548c capability \u98ce\u9669\u63a7\u5236\u3002",
            "\u7b54\u6848\u4e0e\u89e3\u6790": "\u7ed9\u51fa\u6807\u51c6\u7b54\u6848\uff0c\u5e76\u8bf4\u660e\u4e3a\u4ec0\u4e48\u8fd9\u4e9b\u7b54\u6848\u80fd\u56de\u5230\u8bbe\u8ba1\u8bc1\u636e\u3002",
            "\u8bc4\u5206\u8981\u70b9": "\u660e\u786e\u57fa\u7840\u3001\u8fdb\u9636\u548c\u6311\u6218\u9898\u7684\u5224\u5206\u8981\u70b9\u3002",
        },
        node_guidance={},
        citation_policy=["\u9898\u76ee\u4e0e\u7b54\u6848\u9700\u53ef\u56de\u6eaf\u5230\u5de5\u4f5c\u53f0\u8d44\u6599\u3002"],
        repair_hints=["\u5982\u9898\u76ee\u4e0d\u8db3\uff0c\u4f18\u5148\u8865\u9f50\u6700\u5c0f\u6d4b\u9a8c\u95ed\u73af\u3002"],
        recipe_notes=["\u6d4b\u9a8c\u914d\u65b9\u5f3a\u8c03\u53ef\u9a8c\u8bc1\u7684\u9898\u76ee\u94fe\u8def\u3002"],
    ),
    "wiki_page_writer_v1": PromptRecipe(
        recipe_id="wiki_page_writer_v1",
        recipe_name="Wiki Page Writer",
        supported_actions=["WIKI_PAGE"],
        generation_mode="SECTION_SYNTHESIS",
        system_intent="Normalize architecture knowledge into a stable internal wiki page.",
        section_guidance={
            "概览": "先给定义和边界。",
            "关键机制": "归纳核心对象和关系。",
            "相关页面": "指向施工文档、设计文档和测试入口。",
        },
        node_guidance={},
        citation_policy=["保持知识库式命名和可追溯来源。"],
        repair_hints=["章节过散时重新收敛到定义、机制、相关页面。"],
        recipe_notes=["Wiki 配方强调沉淀与引用一致性。"],
    ),
    "mindmap_writer_v1": PromptRecipe(
        recipe_id="mindmap_writer_v1",
        recipe_name="Mind Map Writer",
        supported_actions=["MINDMAP"],
        generation_mode="SECTION_SYNTHESIS",
        system_intent="Turn workspace evidence into a concise hierarchy for an interactive mind map.",
        section_guidance={
            "主题中心": "用一句短语界定导图中心主题。",
            "关键概念": "用短语提炼最重要的概念和对象。",
            "证据脉络": "把来源中的关键证据组织成可追溯分支。",
            "核心结论": "收敛最值得保留的判断。",
            "后续行动": "列出可以继续推进或追问的方向。",
        },
        node_guidance={},
        citation_policy=["每个事实分支都应保留工作台来源线索。"],
        repair_hints=["分支过长时改写为短语，重复分支应合并。"],
        recipe_notes=["导图配方强调单根、短语化节点与来源回溯。"],
    ),
    "structured_note_writer_v1": PromptRecipe(
        recipe_id="structured_note_writer_v1",
        recipe_name="Structured Note Writer",
        supported_actions=["STRUCTURED_NOTE"],
        generation_mode="SECTION_SYNTHESIS",
        system_intent="Capture the current architecture state as a reusable structured note.",
        section_guidance={
            "主题快照": "用最小篇幅定义当前主题。",
            "关键摘录": "保留关键术语与约束短句。",
            "后续问题": "列出后续待推进事项。",
        },
        node_guidance={},
        citation_policy=["摘录必须来自当前工作台资料。"],
        repair_hints=["缺少后续问题时补齐演进方向。"],
        recipe_notes=["结构化笔记配方强调快照与待办。"],
    ),
    "video_summary_writer_v1": PromptRecipe(
        recipe_id="video_summary_writer_v1",
        recipe_name="Video Summary Writer",
        supported_actions=["VIDEO_SUMMARY"],
        generation_mode="TRANSCRIPT_SUMMARY",
        system_intent="Convert transcript-oriented video evidence into a concise and structured summary artifact.",
        section_guidance={
            "一句话总结": "先用一句话概括视频主张。",
            "时间线摘要": "按主题阶段而非逐秒流水账组织。",
            "核心观点": "提炼最值得复用的观点。",
            "关键概念": "列出关键架构名词。",
            "可沉淀要点": "指出可回流到知识库的要点。",
        },
        node_guidance={},
        citation_policy=["以转写结果为主证据来源。"],
        repair_hints=["如果时间线过散，改按主题分段。"],
        recipe_notes=["视频总结配方强调转写到知识沉淀的压缩。"],
    ),
    "audio_minutes_writer_v1": PromptRecipe(
        recipe_id="audio_minutes_writer_v1",
        recipe_name="Audio Minutes Writer",
        supported_actions=["AUDIO_MINUTES"],
        generation_mode="MEETING_MINUTES",
        system_intent="Turn audio transcription into an action-oriented meeting minutes artifact.",
        section_guidance={
            "总体摘要": "先给会议目标与总体结论。",
            "主要议题": "按讨论主题归并。",
            "关键结论": "明确达成共识。",
            "行动项": "写出后续动作。",
            "待确认问题": "保留未定项与阻塞点。",
        },
        node_guidance={},
        citation_policy=["基于转写证据整理结论和行动项。"],
        repair_hints=["如缺行动项，优先从结论反推行动。"],
        recipe_notes=["音频纪要配方强调行动导向。"],
    ),
    "course_notes_writer_v1": PromptRecipe(
        recipe_id="course_notes_writer_v1",
        recipe_name="Course Notes Writer",
        supported_actions=["COURSE_NOTES"],
        generation_mode="COURSE_SYNTHESIS",
        system_intent="Transform course-like multimedia content into structured study notes.",
        section_guidance={
            "课程概要": "概括课程主线。",
            "知识点": "提炼核心概念和术语。",
            "重点难点": "指出最易误解之处。",
            "复习题": "形成可自测的问题。",
        },
        node_guidance={},
        citation_policy=["保持教学型内容与来源材料一致。"],
        repair_hints=["复习题缺失时补齐自测问题。"],
        recipe_notes=["课程笔记配方强调教学复用。"],
    ),
    "resume_highlight_writer_v1": PromptRecipe(
        recipe_id="resume_highlight_writer_v1",
        recipe_name="Resume Highlight Writer",
        supported_actions=["RESUME_HIGHLIGHT"],
        generation_mode="HIGHLIGHT_EXTRACTION",
        system_intent="Condense architecture work into interview-friendly positioning, bullets, and keywords.",
        section_guidance={
            "一句话定位": "强调主链路、控制力和工程抽象。",
            "简历亮点": "用结果导向 bullet 写法概括贡献。",
            "关键词": "保留核心英文术语原文。",
        },
        node_guidance={},
        citation_policy=["亮点必须能回溯到工作台资料。"],
        repair_hints=["缺少关键词时优先补足核心术语。"],
        recipe_notes=["简历亮点配方强调 impact-first 表达。"],
    ),
}


SKILL_DEFINITIONS = {
    "workspace_material_digest": SkillDefinition(
        skill_key="workspace_material_digest",
        skill_version="1.0.0",
        skill_type="MATERIAL_DIGEST",
        input_contract=["workspace source scope", "control pack"],
        output_contract=["material digest", "focus terms"],
        required_capabilities=["READ_WORKSPACE_DOC"],
        verifier_policy=["must keep source trace"],
        repair_policy=["rebuild digest from source summaries"],
    ),
    "generic_section_writer": SkillDefinition(
        skill_key="generic_section_writer",
        skill_version="1.0.0",
        skill_type="GENERATION",
        input_contract=["material digest", "outline", "style profile"],
        output_contract=["artifact sections"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must preserve outline"],
        repair_policy=["regenerate missing sections only"],
    ),
    "quiz_designer": SkillDefinition(
        skill_key="quiz_designer",
        skill_version="1.0.0",
        skill_type="GENERATION",
        input_contract=["material digest", "quiz outline", "style profile"],
        output_contract=["quiz draft sections", "quiz questions"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must generate questions with evidence-backed answers"],
        repair_policy=["rebuild missing questions from source summaries"],
    ),
    "quiz_difficulty_normalizer": SkillDefinition(
        skill_key="quiz_difficulty_normalizer",
        skill_version="1.0.0",
        skill_type="NORMALIZATION",
        input_contract=["quiz draft sections", "quiz questions"],
        output_contract=["difficulty-balanced quiz sections"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must preserve beginner, intermediate, and challenge tiers"],
        repair_policy=["rebalance question difficulty without rebuilding the graph"],
    ),
    "wiki_structure_enforcer": SkillDefinition(
        skill_key="wiki_structure_enforcer",
        skill_version="1.0.0",
        skill_type="NORMALIZATION",
        input_contract=["wiki draft sections", "style profile"],
        output_contract=["wiki-shaped sections"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must preserve wiki overview, mechanisms, and related pages structure"],
        repair_policy=["tighten wiki headings and related-page references only"],
    ),
    "resume_highlight_extractor": SkillDefinition(
        skill_key="resume_highlight_extractor",
        skill_version="1.0.0",
        skill_type="GENERATION",
        input_contract=["material digest", "generation brief"],
        output_contract=["highlight candidates"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must keep evidence trace"],
        repair_policy=["backfill highlights from remaining source summaries"],
    ),
    "resume_impact_normalizer": SkillDefinition(
        skill_key="resume_impact_normalizer",
        skill_version="1.0.0",
        skill_type="NORMALIZATION",
        input_contract=["highlight candidates", "focus points", "style profile"],
        output_contract=["impact-normalized highlight candidates"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must preserve focus coverage and outcome-oriented language"],
        repair_policy=["normalize weak impact phrasing without rebuilding source digest"],
    ),
    "resume_bullet_writer": SkillDefinition(
        skill_key="resume_bullet_writer",
        skill_version="1.0.0",
        skill_type="GENERATION",
        input_contract=["impact-normalized highlight candidates", "style profile"],
        output_contract=["resume sections"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must contain positioning, highlights, keywords"],
        repair_policy=["regenerate section body only"],
    ),
    "resume_verifier": SkillDefinition(
        skill_key="resume_verifier",
        skill_version="1.0.0",
        skill_type="VERIFY",
        input_contract=["resume sections", "required phrases", "focus points"],
        output_contract=["resume verification report"],
        required_capabilities=["VERIFY_OUTPUT"],
        verifier_policy=["must validate bullet count, focus coverage, and keyword coverage"],
        repair_policy=["surface repairable resume gaps without mutating the draft"],
    ),
    "resume_local_repair": SkillDefinition(
        skill_key="resume_local_repair",
        skill_version="1.0.0",
        skill_type="REPAIR",
        input_contract=["resume sections", "resume verification report"],
        output_contract=["repaired resume sections"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must close verifier-detected gaps with local edits only"],
        repair_policy=["repair missing bullets, focus points, keywords, and evidence refs"],
    ),
    "evidence_guard": SkillDefinition(
        skill_key="evidence_guard",
        skill_version="1.0.0",
        skill_type="VERIFY",
        input_contract=["sections", "source trace"],
        output_contract=["guarded sections"],
        required_capabilities=["VERIFY_OUTPUT"],
        verifier_policy=["must attach source refs when evidence exists"],
        repair_policy=["attach first-party workspace refs"],
    ),
    "style_polisher": SkillDefinition(
        skill_key="style_polisher",
        skill_version="1.0.0",
        skill_type="POLISH",
        input_contract=["sections", "style profile"],
        output_contract=["polished sections"],
        required_capabilities=["GENERATE_STRUCTURED_TEXT"],
        verifier_policy=["must preserve section meaning"],
        repair_policy=["trim wording without rebuilding graph"],
    ),
}


SKILL_GRAPH_TEMPLATES = {
    "video_deck_v1": SkillGraphTemplate(
        graph_key="video_deck_v1", graph_name="Frozen Original-Image Video Deck",
        action_type="SLIDE_DECK", nodes=[
            SkillGraphNode(node_id="layout", skill_key="frozen_video_deck_layout",
                           purpose="Map original frames and extracted claims to ordered slides."),
            SkillGraphNode(node_id="render", skill_key="original_frame_pptx_renderer",
                           purpose="Render editable text with unchanged source frames."),
            SkillGraphNode(node_id="verify", skill_key="evidence_guard",
                           purpose="Verify PPTX and every preview against the frozen slide list."),
        ],
        edges=[SkillGraphEdge(from_node="layout", to_node="render", edge_type="PREREQUISITE"),
               SkillGraphEdge(from_node="render", to_node="verify", edge_type="PREREQUISITE")],
        schema_contract=["video-deck-ir-v1", "artifact-content-v1"],
    ),
    "video_derived_text_v1": SkillGraphTemplate(
        graph_key="video_derived_text_v1", graph_name="Frozen Video Text Derivation",
        action_type="VIDEO_DERIVED_TEXT", nodes=[
            SkillGraphNode(node_id="derive", skill_key="frozen_video_deriver",
                           purpose="Derive text from the frozen Bundle and plan."),
            SkillGraphNode(node_id="verify", skill_key="evidence_guard",
                           purpose="Verify each claim and the deterministic rendering."),
        ],
        edges=[SkillGraphEdge(from_node="derive", to_node="verify", edge_type="PREREQUISITE")],
        schema_contract=["video-derived-text-v1", "artifact-content-v1"],
    ),
    "generic_artifact_v1": SkillGraphTemplate(
        graph_key="generic_artifact_v1",
        graph_name="Generic Artifact Graph",
        action_type="GENERIC",
        nodes=[
            SkillGraphNode(
                node_id="digest",
                skill_key="workspace_material_digest",
                purpose="Compile workspace material digest.",
            ),
            SkillGraphNode(
                node_id="write",
                skill_key="generic_section_writer",
                purpose="Generate outline-aligned sections.",
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
        edges=[
            SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="write", to_node="guard", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="guard", to_node="polish", edge_type="PREREQUISITE"),
        ],
        schema_contract=[
            "Execution graph must preserve requested section outline.",
            "Section drafts must retain source trace when workspace materials exist.",
        ],
    ),
    "quiz_artifact_v1": SkillGraphTemplate(
        graph_key="quiz_artifact_v1",
        graph_name="Quiz Artifact Graph",
        action_type="QUIZ",
        nodes=[
            SkillGraphNode(
                node_id="digest",
                skill_key="workspace_material_digest",
                purpose="Compile workspace material digest for quiz design.",
            ),
            SkillGraphNode(
                node_id="design",
                skill_key="quiz_designer",
                purpose="Draft quiz goals, questions, answers, and scoring notes.",
            ),
            SkillGraphNode(
                node_id="normalize",
                skill_key="quiz_difficulty_normalizer",
                purpose="Balance difficulty tiers and tighten quiz phrasing.",
            ),
            SkillGraphNode(
                node_id="guard",
                skill_key="evidence_guard",
                purpose="Check quiz evidence trace.",
            ),
            SkillGraphNode(
                node_id="polish",
                skill_key="style_polisher",
                purpose="Polish final quiz wording.",
            ),
        ],
        edges=[
            SkillGraphEdge(from_node="digest", to_node="design", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="design", to_node="normalize", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="normalize", to_node="guard", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="guard", to_node="polish", edge_type="PREREQUISITE"),
        ],
        schema_contract=[
            "Quiz graph must preserve goals, questions, answers, and scoring criteria.",
            "Quiz output must expose beginner, intermediate, and challenge difficulty layers.",
        ],
    ),
    "wiki_artifact_v1": SkillGraphTemplate(
        graph_key="wiki_artifact_v1",
        graph_name="Wiki Artifact Graph",
        action_type="WIKI_PAGE",
        nodes=[
            SkillGraphNode(
                node_id="digest",
                skill_key="workspace_material_digest",
                purpose="Compile workspace material digest for wiki drafting.",
            ),
            SkillGraphNode(
                node_id="write",
                skill_key="generic_section_writer",
                purpose="Generate wiki-aligned draft sections.",
            ),
            SkillGraphNode(
                node_id="enforce",
                skill_key="wiki_structure_enforcer",
                purpose="Enforce wiki structure and related-page formatting.",
            ),
            SkillGraphNode(
                node_id="guard",
                skill_key="evidence_guard",
                purpose="Check wiki evidence trace.",
            ),
            SkillGraphNode(
                node_id="polish",
                skill_key="style_polisher",
                purpose="Polish final wiki wording.",
            ),
        ],
        edges=[
            SkillGraphEdge(from_node="digest", to_node="write", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="write", to_node="enforce", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="enforce", to_node="guard", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="guard", to_node="polish", edge_type="PREREQUISITE"),
        ],
        schema_contract=[
            "Wiki graph must preserve overview, mechanisms, and related pages sections.",
            "Wiki related pages section must reference stable documentation entry points.",
        ],
    ),
    "resume_highlight_v1": SkillGraphTemplate(
        graph_key="resume_highlight_v1",
        graph_name="Resume Highlight Controlled Graph",
        action_type="RESUME_HIGHLIGHT",
        nodes=[
            SkillGraphNode(
                node_id="digest",
                skill_key="workspace_material_digest",
                purpose="Digest workspace evidence for resume use.",
            ),
            SkillGraphNode(
                node_id="extract",
                skill_key="resume_highlight_extractor",
                purpose="Extract high-signal accomplishment candidates.",
            ),
            SkillGraphNode(
                node_id="normalize",
                skill_key="resume_impact_normalizer",
                purpose="Normalize candidate bullets into impact-first resume language.",
            ),
            SkillGraphNode(
                node_id="write",
                skill_key="resume_bullet_writer",
                purpose="Render resume positioning, bullets, and keywords.",
            ),
            SkillGraphNode(
                node_id="verify",
                skill_key="resume_verifier",
                purpose="Verify bullet count, focus coverage, and required keyword coverage.",
            ),
            SkillGraphNode(
                node_id="repair",
                skill_key="resume_local_repair",
                purpose="Close verifier-detected gaps with targeted local repair.",
            ),
        ],
        edges=[
            SkillGraphEdge(from_node="digest", to_node="extract", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="extract", to_node="normalize", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="normalize", to_node="write", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="write", to_node="verify", edge_type="PREREQUISITE"),
            SkillGraphEdge(from_node="verify", to_node="repair", edge_type="PREREQUISITE"),
        ],
        schema_contract=[
            "Schema-gated resume plan must include positioning, highlights, and keyword sections.",
            "Resume output must preserve required phrases for architecture and safety keywords.",
            "Resume graph must surface explicit verifier and local repair nodes in runtime trace.",
        ],
    ),
}


CAPABILITY_BINDINGS = {
    "READ_WORKSPACE_DOC": CapabilityBinding(
        capability_name="READ_WORKSPACE_DOC",
        server_id="builtin-workspace",
        tool_name="read_workspace_doc",
        scope_type="WORKSPACE_READ",
        approval_mode="NEVER",
        risk_level="LOW",
        allowed_actions=["*"],
    ),
    "GENERATE_STRUCTURED_TEXT": CapabilityBinding(
        capability_name="GENERATE_STRUCTURED_TEXT",
        server_id="builtin-llm",
        tool_name="generate_structured_text",
        scope_type="LOCAL_GENERATION",
        approval_mode="NEVER",
        risk_level="LOW",
        allowed_actions=["*"],
    ),
    "VERIFY_OUTPUT": CapabilityBinding(
        capability_name="VERIFY_OUTPUT",
        server_id="builtin-verifier",
        tool_name="verify_output",
        scope_type="LOCAL_VERIFY",
        approval_mode="NEVER",
        risk_level="LOW",
        allowed_actions=["*"],
    ),
    "EXTRACT_TRANSCRIPT": CapabilityBinding(
        capability_name="EXTRACT_TRANSCRIPT",
        server_id="builtin-media",
        tool_name="extract_transcript",
        scope_type="MEDIA_PROCESSING",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["VIDEO_SUMMARY", "COURSE_NOTES"],
    ),
    "CAPTURE_VIDEO_FRAMES": CapabilityBinding(
        capability_name="CAPTURE_VIDEO_FRAMES",
        server_id="builtin-bilibili-mcp",
        tool_name="capture_bilibili_frames",
        scope_type="MEDIA_PROCESSING",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["COURSE_NOTES"],
    ),
    "ANALYZE_FRAME": CapabilityBinding(
        capability_name="ANALYZE_FRAME",
        server_id="builtin-bilibili-mcp",
        tool_name="analyze_frames",
        scope_type="MEDIA_PROCESSING",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["COURSE_NOTES"],
    ),
    "TRANSCRIBE_AUDIO": CapabilityBinding(
        capability_name="TRANSCRIBE_AUDIO",
        server_id="builtin-media",
        tool_name="transcribe_audio",
        scope_type="MEDIA_PROCESSING",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["AUDIO_MINUTES", "COURSE_NOTES"],
    ),
    "READ_WEB_PAGE": CapabilityBinding(
        capability_name="READ_WEB_PAGE",
        server_id="builtin-network",
        tool_name="read_web_page",
        scope_type="EXTERNAL_NETWORK",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["REPORT", "FAQ", "QUIZ", "STUDY_GUIDE", "WIKI_PAGE", "MINDMAP", "VIDEO_SUMMARY"],
    ),
    "CUSTOM_MCP_NETWORK": CapabilityBinding(
        capability_name="CUSTOM_MCP_NETWORK",
        server_id="custom-mcp",
        tool_name="custom_network_tool",
        scope_type="EXTERNAL_NETWORK",
        approval_mode="REQUIRED",
        risk_level="HIGH",
        allowed_actions=[
            "REPORT",
            "FAQ",
            "QUIZ",
            "STUDY_GUIDE",
            "WIKI_PAGE",
            "MINDMAP",
            "STRUCTURED_NOTE",
            "VIDEO_SUMMARY",
            "AUDIO_MINUTES",
            "COURSE_NOTES",
            "RESUME_HIGHLIGHT",
        ],
    ),
    "COMMIT_ARTIFACT_VERSION": CapabilityBinding(
        capability_name="COMMIT_ARTIFACT_VERSION",
        server_id="builtin-artifact-repo",
        tool_name="commit_artifact_version",
        scope_type="WRITE",
        approval_mode="NEVER",
        risk_level="LOW",
        allowed_actions=["*"],
    ),
    "EXPORT_ARTIFACT_FILE": CapabilityBinding(
        capability_name="EXPORT_ARTIFACT_FILE",
        server_id="builtin-export",
        tool_name="export_artifact_file",
        scope_type="EXPORT",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["*"],
    ),
    "SAVE_WORKSPACE_SOURCE": CapabilityBinding(
        capability_name="SAVE_WORKSPACE_SOURCE",
        server_id="builtin-workspace",
        tool_name="save_workspace_source",
        scope_type="WRITE",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["*"],
    ),
    "WRITE_WIKI_PAGE": CapabilityBinding(
        capability_name="WRITE_WIKI_PAGE",
        server_id="builtin-workspace",
        tool_name="write_wiki_page",
        scope_type="WRITE",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["*"],
    ),
    "WRITE_NOTE_PAGE": CapabilityBinding(
        capability_name="WRITE_NOTE_PAGE",
        server_id="builtin-workspace",
        tool_name="write_note_page",
        scope_type="WRITE",
        approval_mode="REQUIRED",
        risk_level="MEDIUM",
        allowed_actions=["*"],
    ),
}


def resolve_production_action(action_key: str) -> ProductionAction:
    with _custom_action_lock:
        custom_action = _custom_production_actions.get(action_key)
    if custom_action is not None:
        return custom_action
    try:
        return PRODUCTION_ACTIONS[action_key]
    except KeyError as exc:
        raise ValueError(f"unknown production action: {action_key}") from exc


def resolve_style_profile(profile_key: str) -> StyleProfile:
    with _custom_style_profile_lock:
        custom_profile = _custom_style_profiles.get(profile_key)
    if custom_profile is not None:
        return custom_profile
    try:
        return STYLE_PROFILES[profile_key]
    except KeyError as exc:
        raise ValueError(f"unknown style profile: {profile_key}") from exc


def resolve_skill_definition(skill_key: str) -> SkillDefinition:
    with _custom_skill_definition_lock:
        custom_skill = _custom_skill_definitions.get(skill_key)
    if custom_skill is not None:
        return custom_skill
    try:
        return SKILL_DEFINITIONS[skill_key]
    except KeyError as exc:
        raise ValueError(f"unknown skill definition: {skill_key}") from exc


def resolve_prompt_recipe(recipe_id: str) -> PromptRecipe:
    with _custom_prompt_recipe_lock:
        custom_recipe = _custom_prompt_recipes.get(recipe_id)
    if custom_recipe is not None:
        return custom_recipe
    try:
        return PROMPT_RECIPES[recipe_id]
    except KeyError as exc:
        raise ValueError(f"unknown prompt recipe: {recipe_id}") from exc


def resolve_skill_graph(graph_key: str) -> SkillGraphTemplate:
    with _custom_skill_graph_lock:
        custom_graph = _custom_skill_graph_templates.get(graph_key)
    if custom_graph is not None:
        return custom_graph
    try:
        return SKILL_GRAPH_TEMPLATES[graph_key]
    except KeyError as exc:
        raise ValueError(f"unknown skill graph template: {graph_key}") from exc


def resolve_capability_binding(capability_name: str) -> CapabilityBinding:
    custom_binding = _resolve_custom_capability_binding(capability_name)
    if custom_binding is not None:
        return custom_binding
    try:
        return CAPABILITY_BINDINGS[capability_name]
    except KeyError as exc:
        raise ValueError(f"unknown capability binding: {capability_name}") from exc


def list_default_actions() -> list[ProductionAction]:
    builtin_actions = [
        PRODUCTION_ACTIONS["REPORT"],
        PRODUCTION_ACTIONS["FAQ"],
        PRODUCTION_ACTIONS["QUIZ"],
        PRODUCTION_ACTIONS["STUDY_GUIDE"],
        PRODUCTION_ACTIONS["WIKI_PAGE"],
        PRODUCTION_ACTIONS["MINDMAP"],
        PRODUCTION_ACTIONS["STRUCTURED_NOTE"],
        PRODUCTION_ACTIONS["VIDEO_SUMMARY"],
        PRODUCTION_ACTIONS["AUDIO_MINUTES"],
        PRODUCTION_ACTIONS["COURSE_NOTES"],
        PRODUCTION_ACTIONS["RESUME_HIGHLIGHT"],
    ]
    with _custom_action_lock:
        custom_actions = list(_custom_production_actions.values())
    return builtin_actions + custom_actions


def list_default_prompt_recipes() -> list[PromptRecipe]:
    builtin_recipes = [PROMPT_RECIPES[recipe_id] for recipe_id in PROMPT_RECIPES]
    with _custom_prompt_recipe_lock:
        custom_recipes = list(_custom_prompt_recipes.values())
    return builtin_recipes + custom_recipes


def list_default_style_profiles() -> list[StyleProfile]:
    builtin_profiles = [STYLE_PROFILES[profile_key] for profile_key in STYLE_PROFILES]
    with _custom_style_profile_lock:
        custom_profiles = list(_custom_style_profiles.values())
    return builtin_profiles + custom_profiles


def list_default_skill_definitions() -> list[SkillDefinition]:
    builtin_skills = [SKILL_DEFINITIONS[skill_key] for skill_key in SKILL_DEFINITIONS]
    with _custom_skill_definition_lock:
        custom_skills = list(_custom_skill_definitions.values())
    return builtin_skills + custom_skills


def list_default_skill_graph_templates() -> list[SkillGraphTemplate]:
    builtin_graphs = [SKILL_GRAPH_TEMPLATES[graph_key] for graph_key in SKILL_GRAPH_TEMPLATES]
    with _custom_skill_graph_lock:
        custom_graphs = list(_custom_skill_graph_templates.values())
    return builtin_graphs + custom_graphs


def list_default_capability_bindings() -> list[CapabilityBinding]:
    builtin_bindings = [CAPABILITY_BINDINGS[capability_name] for capability_name in CAPABILITY_BINDINGS]
    return builtin_bindings + _list_custom_capability_bindings()


def list_custom_actions() -> list[ProductionAction]:
    with _custom_action_lock:
        return list(_custom_production_actions.values())


def list_custom_prompt_recipes() -> list[PromptRecipe]:
    with _custom_prompt_recipe_lock:
        return list(_custom_prompt_recipes.values())


def list_custom_style_profiles() -> list[StyleProfile]:
    with _custom_style_profile_lock:
        return list(_custom_style_profiles.values())


def list_custom_skill_definitions() -> list[SkillDefinition]:
    with _custom_skill_definition_lock:
        return list(_custom_skill_definitions.values())


def list_custom_skill_graph_templates() -> list[SkillGraphTemplate]:
    with _custom_skill_graph_lock:
        return list(_custom_skill_graph_templates.values())


def list_custom_mcp_servers() -> list[CustomMcpServerRegistration]:
    with _custom_mcp_server_lock:
        return list(_custom_mcp_servers.values())


def list_custom_mcp_blueprints() -> list[CustomMcpServerRegistration]:
    return [_default_bilibili_render_pdf_blueprint()]


def reset_custom_skill_definitions() -> None:
    with _custom_skill_definition_lock:
        _custom_skill_definitions.clear()
    _persist_custom_artifact_config_store()


def reset_custom_skill_graph_templates() -> None:
    with _custom_skill_graph_lock:
        _custom_skill_graph_templates.clear()
    _persist_custom_artifact_config_store()


def reset_custom_style_profiles() -> None:
    with _custom_style_profile_lock:
        _custom_style_profiles.clear()
    _persist_custom_artifact_config_store()


def reset_custom_actions() -> None:
    with _custom_action_lock:
        _custom_production_actions.clear()
    _persist_custom_artifact_config_store()


def reset_custom_prompt_recipes() -> None:
    with _custom_prompt_recipe_lock:
        _custom_prompt_recipes.clear()
    _persist_custom_artifact_config_store()


def reset_custom_mcp_servers() -> None:
    with _custom_mcp_server_lock:
        _custom_mcp_servers.clear()
    _persist_custom_artifact_config_store()


def register_custom_action(
    registration: CustomProductionActionRegistration,
) -> ProductionAction:
    normalized_action_key = registration.action_key.strip().upper()
    if not normalized_action_key:
        raise ValueError("custom production action requires a non-empty action_key")
    if normalized_action_key in PRODUCTION_ACTIONS:
        raise ValueError(f"custom production action conflicts with builtin action: {normalized_action_key}")
    with _custom_action_lock:
        if normalized_action_key in _custom_production_actions:
            raise ValueError(f"custom production action conflicts with existing custom action: {normalized_action_key}")
    base_action_key = registration.base_action_key.strip().upper()
    base_action = resolve_production_action(base_action_key)
    normalized_keywords = [
        keyword.strip()
        for keyword in registration.resolver_keywords
        if keyword.strip()
    ]
    normalized_routes = [
        route.strip().upper()
        for route in registration.preferred_routes
        if route.strip()
    ]
    normalized_style_profiles = [
        profile_key.strip().upper()
        for profile_key in registration.preferred_style_profiles
        if profile_key.strip()
    ]
    normalized_source_platforms = [
        platform.strip().upper()
        for platform in registration.preferred_source_platforms
        if platform.strip()
    ]
    normalized_structure_keywords = [
        keyword.strip()
        for keyword in registration.preferred_structure_keywords
        if keyword.strip()
    ]
    normalized_task_neighborhoods = [
        neighborhood.strip().upper()
        for neighborhood in registration.preferred_task_neighborhoods
        if neighborhood.strip()
    ]
    resolve_style_profile(
        registration.default_style_profile_key.strip().upper()
        or base_action.default_style_profile_key
    )
    for profile_key in normalized_style_profiles:
        resolve_style_profile(profile_key)
    resolve_skill_graph(
        registration.default_skill_graph_key.strip()
        or base_action.default_skill_graph_key
    )
    prompt_recipe = resolve_prompt_recipe(
        registration.default_prompt_recipe_id.strip()
        or base_action.default_prompt_recipe_id
    )
    compatible_action_keys = {normalized_action_key, base_action.action_key}
    if (
        "*" not in prompt_recipe.supported_actions
        and compatible_action_keys.isdisjoint(set(prompt_recipe.supported_actions))
    ):
        raise ValueError(
            "custom production action prompt recipe is incompatible with base/template action"
        )
    _ensure_custom_action_resolver_conflicts(
        normalized_action_key=normalized_action_key,
        normalized_keywords=normalized_keywords,
        resolver_priority=registration.resolver_priority,
    )

    action = ProductionAction(
        action_key=normalized_action_key,
        display_name=registration.display_name.strip() or base_action.display_name,
        artifact_type=registration.artifact_type.strip().upper() or normalized_action_key,
        action_origin="CUSTOM",
        template_action_key=base_action.action_key,
        resolver_keywords=normalized_keywords,
        resolver_priority=registration.resolver_priority,
        preferred_routes=normalized_routes,
        preferred_style_profiles=normalized_style_profiles,
        preferred_source_platforms=normalized_source_platforms,
        preferred_structure_keywords=normalized_structure_keywords,
        preferred_task_neighborhoods=normalized_task_neighborhoods,
        default_style_profile_key=(
            registration.default_style_profile_key.strip().upper()
            or base_action.default_style_profile_key
        ),
        default_skill_graph_key=(
            registration.default_skill_graph_key.strip()
            or base_action.default_skill_graph_key
        ),
        default_prompt_recipe_id=(
            registration.default_prompt_recipe_id.strip()
            or base_action.default_prompt_recipe_id
        ),
        required_evidence_level=base_action.required_evidence_level,
        allow_custom_mcp=base_action.allow_custom_mcp,
        allow_writeback=base_action.allow_writeback,
        allowed_writeback_modes=list(base_action.allowed_writeback_modes),
        supported_capabilities=list(base_action.supported_capabilities),
        output_sections=(
            list(registration.output_sections)
            if registration.output_sections
            else list(base_action.output_sections)
        ),
        required_phrases=(
            list(registration.required_phrases)
            if registration.required_phrases
            else list(base_action.required_phrases)
        ),
    )
    with _custom_action_lock:
        _custom_production_actions[normalized_action_key] = action
    _persist_custom_artifact_config_store()
    return action


def register_custom_prompt_recipe(
    registration: CustomPromptRecipeRegistration,
) -> PromptRecipe:
    normalized_recipe_id = registration.recipe_id.strip()
    if not normalized_recipe_id:
        raise ValueError("custom prompt recipe requires a non-empty recipe_id")
    if normalized_recipe_id in PROMPT_RECIPES:
        raise ValueError(f"custom prompt recipe conflicts with builtin recipe: {normalized_recipe_id}")
    with _custom_prompt_recipe_lock:
        if normalized_recipe_id in _custom_prompt_recipes:
            raise ValueError(
                f"custom prompt recipe conflicts with existing custom recipe: {normalized_recipe_id}"
            )
    base_recipe = resolve_prompt_recipe(registration.base_recipe_id.strip())

    recipe = PromptRecipe(
        recipe_id=normalized_recipe_id,
        recipe_name=registration.recipe_name.strip() or base_recipe.recipe_name,
        supported_actions=(
            [item.strip().upper() for item in registration.supported_actions if item.strip()]
            or list(base_recipe.supported_actions)
        ),
        generation_mode=registration.generation_mode.strip() or base_recipe.generation_mode,
        system_intent=registration.system_intent.strip() or base_recipe.system_intent,
        section_guidance=(
            dict(registration.section_guidance)
            if registration.section_guidance
            else dict(base_recipe.section_guidance)
        ),
        node_guidance=(
            dict(registration.node_guidance)
            if registration.node_guidance
            else dict(base_recipe.node_guidance)
        ),
        citation_policy=(
            list(registration.citation_policy)
            if registration.citation_policy
            else list(base_recipe.citation_policy)
        ),
        repair_hints=(
            list(registration.repair_hints)
            if registration.repair_hints
            else list(base_recipe.repair_hints)
        ),
        recipe_notes=(
            list(registration.recipe_notes)
            if registration.recipe_notes
            else list(base_recipe.recipe_notes)
        ),
    )
    with _custom_prompt_recipe_lock:
        _custom_prompt_recipes[normalized_recipe_id] = recipe
    _persist_custom_artifact_config_store()
    return recipe


def register_custom_skill_definition(
    registration: CustomSkillDefinitionRegistration | dict[str, object],
) -> SkillDefinition:
    if isinstance(registration, dict):
        registration = CustomSkillDefinitionRegistration.model_validate(registration)

    normalized_skill_key = registration.skill_key.strip()
    if not normalized_skill_key:
        raise ValueError("custom skill definition requires a non-empty skill_key")
    if normalized_skill_key in SKILL_DEFINITIONS:
        raise ValueError(f"custom skill definition conflicts with builtin skill: {normalized_skill_key}")
    with _custom_skill_definition_lock:
        if normalized_skill_key in _custom_skill_definitions:
            raise ValueError(
                f"custom skill definition conflicts with existing custom skill: {normalized_skill_key}"
            )

    base_skill = resolve_skill_definition(registration.base_skill_key.strip())
    skill = SkillDefinition(
        skill_key=normalized_skill_key,
        template_skill_key=base_skill.template_skill_key or base_skill.skill_key,
        skill_version=registration.skill_version.strip() or base_skill.skill_version,
        skill_type=registration.skill_type.strip() or base_skill.skill_type,
        input_contract=(
            list(registration.input_contract)
            if registration.input_contract
            else list(base_skill.input_contract)
        ),
        output_contract=(
            list(registration.output_contract)
            if registration.output_contract
            else list(base_skill.output_contract)
        ),
        required_capabilities=(
            list(dict.fromkeys(registration.required_capabilities))
            if registration.required_capabilities
            else list(base_skill.required_capabilities)
        ),
        verifier_policy=(
            list(registration.verifier_policy)
            if registration.verifier_policy
            else list(base_skill.verifier_policy)
        ),
        repair_policy=(
            list(registration.repair_policy)
            if registration.repair_policy
            else list(base_skill.repair_policy)
        ),
    )
    with _custom_skill_definition_lock:
        _custom_skill_definitions[normalized_skill_key] = skill
    _persist_custom_artifact_config_store()
    return skill


def register_custom_skill_graph_template(
    registration: CustomSkillGraphTemplateRegistration | dict[str, object],
) -> SkillGraphTemplate:
    if isinstance(registration, dict):
        registration = CustomSkillGraphTemplateRegistration.model_validate(registration)

    normalized_graph_key = registration.graph_key.strip()
    if not normalized_graph_key:
        raise ValueError("custom skill graph template requires a non-empty graph_key")
    if normalized_graph_key in SKILL_GRAPH_TEMPLATES:
        raise ValueError(
            f"custom skill graph template conflicts with builtin graph: {normalized_graph_key}"
        )
    with _custom_skill_graph_lock:
        if normalized_graph_key in _custom_skill_graph_templates:
            raise ValueError(
                f"custom skill graph template conflicts with existing custom graph: {normalized_graph_key}"
            )

    base_graph = resolve_skill_graph(registration.base_graph_key.strip())
    graph = SkillGraphTemplate(
        graph_key=normalized_graph_key,
        graph_name=registration.graph_name.strip() or base_graph.graph_name,
        action_type=registration.action_type.strip().upper() or base_graph.action_type,
        nodes=list(registration.nodes) if registration.nodes else list(base_graph.nodes),
        edges=list(registration.edges) if registration.edges else list(base_graph.edges),
        schema_contract=(
            list(registration.schema_contract)
            if registration.schema_contract
            else list(base_graph.schema_contract)
        ),
    )
    _validate_skill_graph_template(graph)
    with _custom_skill_graph_lock:
        _custom_skill_graph_templates[normalized_graph_key] = graph
    _persist_custom_artifact_config_store()
    return graph


def register_custom_style_profile(
    registration: CustomStyleProfileRegistration | dict[str, object],
) -> StyleProfile:
    if isinstance(registration, dict):
        registration = CustomStyleProfileRegistration.model_validate(registration)

    normalized_profile_key = registration.profile_key.strip().upper()
    if not normalized_profile_key:
        raise ValueError("custom style profile requires a non-empty profile_key")
    if normalized_profile_key in STYLE_PROFILES:
        raise ValueError(f"custom style profile conflicts with builtin profile: {normalized_profile_key}")
    with _custom_style_profile_lock:
        if normalized_profile_key in _custom_style_profiles:
            raise ValueError(
                f"custom style profile conflicts with existing custom style profile: {normalized_profile_key}"
            )

    base_profile = resolve_style_profile(registration.base_profile_key.strip().upper())
    profile = StyleProfile(
        profile_key=normalized_profile_key,
        profile_name=registration.profile_name.strip() or base_profile.profile_name,
        tone=registration.tone.strip() or base_profile.tone,
        structure_mode=registration.structure_mode.strip() or base_profile.structure_mode,
        audience_type=registration.audience_type.strip() or base_profile.audience_type,
        length_preference=registration.length_preference.strip() or base_profile.length_preference,
        citation_density=registration.citation_density.strip() or base_profile.citation_density,
        format_constraints=(
            [item.strip() for item in registration.format_constraints if item.strip()]
            or list(base_profile.format_constraints)
        ),
    )
    with _custom_style_profile_lock:
        _custom_style_profiles[normalized_profile_key] = profile
    _persist_custom_artifact_config_store()
    return profile


def register_custom_mcp_server(
    registration: CustomMcpServerRegistration | dict[str, object],
) -> CustomMcpServerRegistration:
    if isinstance(registration, dict):
        registration = CustomMcpServerRegistration.model_validate(registration)

    normalized_server_id = registration.server_id.strip().lower()
    if not normalized_server_id:
        raise ValueError("custom mcp server requires a non-empty server_id")
    if not normalized_server_id.startswith("custom-"):
        raise ValueError("custom mcp server_id must start with 'custom-'")
    if not registration.tools:
        raise ValueError("custom mcp server requires at least one tool registration")
    with _custom_mcp_server_lock:
        if normalized_server_id in _custom_mcp_servers:
            raise ValueError(
                f"custom mcp server conflicts with existing custom server: {normalized_server_id}"
            )

    normalized_tools = [
        _normalize_custom_mcp_tool_registration(tool)
        for tool in registration.tools
    ]
    _validate_custom_mcp_tool_compatibility(normalized_tools)
    server = CustomMcpServerRegistration(
        server_id=normalized_server_id,
        display_name=registration.display_name.strip() or normalized_server_id,
        endpoint_kind=(registration.endpoint_kind.strip().lower() or "mcp"),
        launch_transport=registration.launch_transport.strip().lower() or "stdio",
        launch_command=registration.launch_command.strip(),
        launch_args=[arg for arg in registration.launch_args if str(arg).strip()],
        working_directory=registration.working_directory.strip(),
        launch_env={
            str(key).strip(): str(value)
            for key, value in registration.launch_env.items()
            if str(key).strip()
        },
        blueprint_key=registration.blueprint_key.strip(),
        registration_origin=registration.registration_origin.strip().upper() or "CUSTOM",
        server_notes=[note.strip() for note in registration.server_notes if note.strip()],
        tools=normalized_tools,
    )
    with _custom_mcp_server_lock:
        _custom_mcp_servers[normalized_server_id] = server
    _persist_custom_artifact_config_store()
    return server


def configure_custom_artifact_config_store(storage_path: str | Path | None) -> None:
    global _custom_artifact_config_store_path

    with _custom_store_lock:
        _custom_artifact_config_store_path = (
            None if storage_path is None else Path(storage_path).resolve()
        )
    _load_custom_artifact_config_store()


def get_custom_artifact_config_store_info() -> dict[str, object]:
    with _custom_store_lock:
        return {
            "storage_path": str(_custom_artifact_config_store_path) if _custom_artifact_config_store_path else "",
            "custom_style_profile_count": len(list_custom_style_profiles()),
            "custom_skill_definition_count": len(list_custom_skill_definitions()),
            "custom_skill_graph_count": len(list_custom_skill_graph_templates()),
            "custom_action_count": len(list_custom_actions()),
            "custom_prompt_recipe_count": len(list_custom_prompt_recipes()),
            "custom_mcp_server_count": len(list_custom_mcp_servers()),
        }


def _persist_custom_artifact_config_store() -> None:
    with _custom_store_lock:
        storage_path = _custom_artifact_config_store_path
    if storage_path is None:
        return

    payload = {
        "custom_style_profiles": [
            profile.model_dump(mode="json")
            for profile in list_custom_style_profiles()
        ],
        "custom_skill_definitions": [
            skill.model_dump(mode="json")
            for skill in list_custom_skill_definitions()
        ],
        "custom_skill_graph_templates": [
            graph.model_dump(mode="json")
            for graph in list_custom_skill_graph_templates()
        ],
        "custom_actions": [
            action.model_dump(mode="json")
            for action in list_custom_actions()
        ],
        "custom_prompt_recipes": [
            recipe.model_dump(mode="json")
            for recipe in list_custom_prompt_recipes()
        ],
        "custom_mcp_servers": [
            server.model_dump(mode="json")
            for server in list_custom_mcp_servers()
        ],
    }
    storage_path.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(payload, ensure_ascii=True, indent=2)
    temporary_path = storage_path.with_suffix(f"{storage_path.suffix}.tmp")
    with temporary_path.open("w", encoding="utf-8") as handle:
        handle.write(serialized)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary_path, storage_path)


def _load_custom_artifact_config_store() -> None:
    with _custom_store_lock:
        storage_path = _custom_artifact_config_store_path

    with _custom_action_lock:
        _custom_production_actions.clear()
    with _custom_prompt_recipe_lock:
        _custom_prompt_recipes.clear()
    with _custom_skill_definition_lock:
        _custom_skill_definitions.clear()
    with _custom_skill_graph_lock:
        _custom_skill_graph_templates.clear()
    with _custom_style_profile_lock:
        _custom_style_profiles.clear()
    with _custom_mcp_server_lock:
        _custom_mcp_servers.clear()

    if storage_path is None or not storage_path.exists():
        return

    raw_text = storage_path.read_text(encoding="utf-8").strip()
    if not raw_text:
        return
    try:
        payload = json.loads(raw_text)
    except json.JSONDecodeError:
        logger.warning("Ignoring invalid custom artifact configuration JSON: %s", storage_path)
        return
    for profile_payload in payload.get("custom_style_profiles", []):
        profile = StyleProfile.model_validate(profile_payload)
        with _custom_style_profile_lock:
            _custom_style_profiles[profile.profile_key] = profile
    for skill_payload in payload.get("custom_skill_definitions", []):
        skill = SkillDefinition.model_validate(skill_payload)
        with _custom_skill_definition_lock:
            _custom_skill_definitions[skill.skill_key] = skill
    for graph_payload in payload.get("custom_skill_graph_templates", []):
        graph = SkillGraphTemplate.model_validate(graph_payload)
        _validate_skill_graph_template(graph)
        with _custom_skill_graph_lock:
            _custom_skill_graph_templates[graph.graph_key] = graph
    for recipe_payload in payload.get("custom_prompt_recipes", []):
        recipe = PromptRecipe.model_validate(recipe_payload)
        with _custom_prompt_recipe_lock:
            _custom_prompt_recipes[recipe.recipe_id] = recipe
    for action_payload in payload.get("custom_actions", []):
        action = ProductionAction.model_validate(action_payload)
        with _custom_action_lock:
            _custom_production_actions[action.action_key] = action
    for server_payload in payload.get("custom_mcp_servers", []):
        server = CustomMcpServerRegistration.model_validate(server_payload)
        normalized_tools = [
            _normalize_custom_mcp_tool_registration(tool)
            for tool in server.tools
        ]
        _validate_custom_mcp_tool_compatibility(normalized_tools)
        loaded_server = CustomMcpServerRegistration(
            server_id=server.server_id.strip().lower(),
            display_name=server.display_name.strip() or server.server_id.strip().lower(),
            endpoint_kind=server.endpoint_kind.strip().lower() or "mcp",
            launch_transport=server.launch_transport.strip().lower() or "stdio",
            launch_command=server.launch_command.strip(),
            launch_args=[arg for arg in server.launch_args if str(arg).strip()],
            working_directory=server.working_directory.strip(),
            launch_env={
                str(key).strip(): str(value)
                for key, value in server.launch_env.items()
                if str(key).strip()
            },
            blueprint_key=server.blueprint_key.strip(),
            registration_origin=server.registration_origin.strip().upper() or "CUSTOM",
            server_notes=[note.strip() for note in server.server_notes if note.strip()],
            tools=normalized_tools,
        )
        with _custom_mcp_server_lock:
            _custom_mcp_servers[loaded_server.server_id] = loaded_server


def _resolve_custom_capability_binding(capability_name: str) -> CapabilityBinding | None:
    normalized_capability_name = capability_name.strip().upper()
    if normalized_capability_name in CAPABILITY_BINDINGS:
        return None
    for binding in _list_custom_capability_bindings():
        if binding.capability_name == normalized_capability_name:
            return binding
    return None


def _list_custom_capability_bindings() -> list[CapabilityBinding]:
    bindings: dict[str, CapabilityBinding] = {}
    for server in list_custom_mcp_servers():
        for tool in server.tools:
            if tool.capability_name in CAPABILITY_BINDINGS:
                continue
            bindings.setdefault(
                tool.capability_name,
                CapabilityBinding(
                    capability_name=tool.capability_name,
                    server_id=server.server_id,
                    tool_name=tool.tool_name,
                    scope_type=tool.scope_type,
                    approval_mode=tool.approval_mode,
                    risk_level=tool.risk_level,
                    allowed_actions=list(tool.allowed_actions),
                ),
            )
    return list(bindings.values())


def _normalize_custom_mcp_tool_registration(
    tool: CustomMcpToolRegistration,
) -> CustomMcpToolRegistration:
    normalized_capability_name = tool.capability_name.strip().upper()
    normalized_tool_name = tool.tool_name.strip()
    if not normalized_capability_name:
        raise ValueError("custom mcp tool requires a non-empty capability_name")
    if not normalized_tool_name:
        raise ValueError("custom mcp tool requires a non-empty tool_name")

    if normalized_capability_name in CAPABILITY_BINDINGS:
        binding = CAPABILITY_BINDINGS[normalized_capability_name]
        scope_type = tool.scope_type.strip().upper() or binding.scope_type
        approval_mode = tool.approval_mode.strip().upper() or binding.approval_mode
        risk_level = tool.risk_level.strip().upper() or binding.risk_level
        allowed_actions = (
            [item.strip().upper() for item in tool.allowed_actions if item.strip()]
            or list(binding.allowed_actions)
        )
        if scope_type != binding.scope_type:
            raise ValueError(
                f"custom mcp tool scope_type must match builtin capability binding: {normalized_capability_name}"
            )
        if approval_mode != binding.approval_mode:
            raise ValueError(
                f"custom mcp tool approval_mode must match builtin capability binding: {normalized_capability_name}"
            )
        if risk_level != binding.risk_level:
            raise ValueError(
                f"custom mcp tool risk_level must match builtin capability binding: {normalized_capability_name}"
            )
        if allowed_actions != list(binding.allowed_actions):
            raise ValueError(
                f"custom mcp tool allowed_actions must match builtin capability binding: {normalized_capability_name}"
            )
    else:
        scope_type = tool.scope_type.strip().upper()
        approval_mode = tool.approval_mode.strip().upper()
        risk_level = tool.risk_level.strip().upper()
        allowed_actions = [item.strip().upper() for item in tool.allowed_actions if item.strip()]
        if not scope_type or not approval_mode or not risk_level:
            raise ValueError(
                f"custom-only capability requires scope_type/approval_mode/risk_level: {normalized_capability_name}"
            )
        if not allowed_actions:
            allowed_actions = ["*"]

    return CustomMcpToolRegistration(
        capability_name=normalized_capability_name,
        tool_name=normalized_tool_name,
        scope_type=scope_type,
        approval_mode=approval_mode,
        risk_level=risk_level,
        allowed_actions=allowed_actions,
        supported_routes=[
            item.strip().upper() for item in tool.supported_routes if item.strip()
        ] or ["*"],
        supported_actions=[
            item.strip().upper() for item in tool.supported_actions if item.strip()
        ] or ["*"],
        supported_skill_graphs=[
            item.strip() for item in tool.supported_skill_graphs if item.strip()
        ] or ["*"],
        preference_rank=tool.preference_rank,
        selection_reason_hint=tool.selection_reason_hint.strip(),
        output_kind=tool.output_kind.strip().upper(),
        notes=[note.strip() for note in tool.notes if note.strip()],
    )


def _validate_custom_mcp_tool_compatibility(
    tools: list[CustomMcpToolRegistration],
) -> None:
    custom_only_bindings: dict[str, tuple[str, str, str, tuple[str, ...]]] = {}
    for tool in tools:
        if tool.capability_name in CAPABILITY_BINDINGS:
            continue
        signature = (
            tool.scope_type,
            tool.approval_mode,
            tool.risk_level,
            tuple(tool.allowed_actions),
        )
        existing_signature = custom_only_bindings.get(tool.capability_name)
        if existing_signature is None:
            custom_only_bindings[tool.capability_name] = signature
            continue
        if existing_signature != signature:
            raise ValueError(
                f"custom-only capability binding is inconsistent across tools: {tool.capability_name}"
            )
    existing_custom_bindings = {
        binding.capability_name: (
            binding.scope_type,
            binding.approval_mode,
            binding.risk_level,
            tuple(binding.allowed_actions),
        )
        for binding in _list_custom_capability_bindings()
    }
    for capability_name, signature in custom_only_bindings.items():
        existing_signature = existing_custom_bindings.get(capability_name)
        if existing_signature is not None and existing_signature != signature:
            raise ValueError(
                f"custom-only capability binding conflicts with existing custom mcp capability: {capability_name}"
            )


def _validate_skill_graph_template(graph: SkillGraphTemplate) -> None:
    node_map = {node.node_id: node for node in graph.nodes}
    if len(node_map) != len(graph.nodes):
        raise ValueError("custom skill graph template contains duplicate node_id")
    for node in graph.nodes:
        skill_definition = resolve_skill_definition(node.skill_key)
        ensure_runtime_node_skill_registered(
            skill_definition,
            graph_key=graph.graph_key,
            node_skill_key=node.skill_key,
            error_prefix="custom skill graph template rejected",
        )
    indegree = {node.node_id: 0 for node in graph.nodes}
    adjacency: dict[str, list[str]] = {node.node_id: [] for node in graph.nodes}
    for edge in graph.edges:
        if edge.edge_type != "PREREQUISITE":
            continue
        if edge.from_node not in node_map or edge.to_node not in node_map:
            raise ValueError("custom skill graph template references missing node")
        indegree[edge.to_node] += 1
        adjacency[edge.from_node].append(edge.to_node)
    queue = [node_id for node_id, degree in indegree.items() if degree == 0]
    visited = 0
    while queue:
        current = queue.pop(0)
        visited += 1
        for neighbor in adjacency[current]:
            indegree[neighbor] -= 1
            if indegree[neighbor] == 0:
                queue.append(neighbor)
    if visited != len(graph.nodes):
        raise ValueError("custom skill graph template contains cyclic prerequisite edges")


def _ensure_custom_action_resolver_conflicts(
    *,
    normalized_action_key: str,
    normalized_keywords: list[str],
    resolver_priority: int,
) -> None:
    if not normalized_keywords:
        return

    normalized_keyword_keys = {keyword.lower() for keyword in normalized_keywords}
    with _custom_action_lock:
        existing_actions = list(_custom_production_actions.values())
    for action in existing_actions:
        if action.action_key == normalized_action_key:
            continue
        if action.resolver_priority != resolver_priority:
            continue
        existing_keyword_keys = {keyword.lower() for keyword in action.resolver_keywords}
        overlap = sorted(normalized_keyword_keys.intersection(existing_keyword_keys))
        if overlap:
            raise ValueError(
                "custom production action resolver conflict: "
                f"priority={resolver_priority}, overlapping_keywords={','.join(overlap)}"
            )
