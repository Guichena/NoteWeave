from __future__ import annotations

from app.models import ArtifactSkillDefinition

_LANGUAGE_OPTIONS = [
    {"const": "zh-CN", "title": "中文（简体）"},
    {"const": "en", "title": "English"},
    {"const": "zh-EN", "title": "中英双语"},
]


def _language_schema() -> dict[str, object]:
    return {
        "type": "string",
        "default": "zh-CN",
        "oneOf": list(_LANGUAGE_OPTIONS),
    }


def _language_only_schema() -> dict[str, object]:
    return {
        "type": "object",
        "properties": {
            "language": _language_schema(),
        },
    }


def _language_and_url_schema(
    *,
    required: bool,
    alias_keys: list[str],
) -> dict[str, object]:
    properties: dict[str, object] = {
        "url": {"type": "string"},
        "language": _language_schema(),
    }
    for alias_key in alias_keys:
        properties[alias_key] = {"type": "string"}
    schema: dict[str, object] = {
        "type": "object",
        "properties": properties,
    }
    if required:
        schema["required"] = ["url"]
    return schema


_ARTIFACT_SKILLS = {
    "resume_highlight": ArtifactSkillDefinition(
        skill_key="resume_highlight",
        display_name="简历亮点描述",
        description="从当前工作台资料中生成适合简历书写的项目亮点",
        input_schema=_language_only_schema(),
    ),
    "study_guide": ArtifactSkillDefinition(
        skill_key="study_guide",
        display_name="学习指南",
        description="按知识点、关键概念和练习建议生成结构化学习材料",
        input_schema=_language_only_schema(),
    ),
    "quiz_pack": ArtifactSkillDefinition(
        skill_key="quiz_pack",
        display_name="测验题集",
        description="围绕当前资料生成题目、答案解析和评分要点",
        input_schema=_language_only_schema(),
    ),
    "wiki_page": ArtifactSkillDefinition(
        skill_key="wiki_page",
        display_name="Wiki 页面",
        description="沉淀成定义、机制、引用和相关页面齐全的知识页草稿",
        input_schema=_language_only_schema(),
    ),
    "bilibili_course_note_pdf": ArtifactSkillDefinition(
        skill_key="bilibili_course_note_pdf",
        display_name="B站讲义 PDF",
        description="面向 B 站视频链接生成图文讲义与 PDF 讲义任务",
        input_schema=_language_and_url_schema(required=True, alias_keys=["video_url", "bilibili_url"]),
    ),
    "report_draft": ArtifactSkillDefinition(
        skill_key="report_draft",
        display_name="结构化报告",
        description="生成结构化报告草稿",
        input_schema=_language_only_schema(),
    ),
    "faq_draft": ArtifactSkillDefinition(
        skill_key="faq_draft",
        display_name="FAQ 草稿",
        description="生成 FAQ 草稿",
        input_schema=_language_only_schema(),
    ),
    "structured_note": ArtifactSkillDefinition(
        skill_key="structured_note",
        display_name="结构化笔记",
        description="生成结构化笔记",
        input_schema=_language_only_schema(),
    ),
    "video_summary": ArtifactSkillDefinition(
        skill_key="video_summary",
        display_name="视频总结",
        description="生成视频总结",
        input_schema=_language_and_url_schema(required=True, alias_keys=["video_url"]),
    ),
    "audio_minutes": ArtifactSkillDefinition(
        skill_key="audio_minutes",
        display_name="音频纪要",
        description="生成音频纪要",
        input_schema=_language_only_schema(),
    ),
    "course_notes": ArtifactSkillDefinition(
        skill_key="course_notes",
        display_name="课程笔记",
        description="生成课程笔记",
        input_schema=_language_and_url_schema(required=False, alias_keys=["video_url"]),
    ),
}

_ALIASES = {
    "resume_highlights": "resume_highlight",
    "bilibili_pdf": "bilibili_course_note_pdf",
}

_SKILL_ACTION_BINDINGS = {
    "resume_highlight": "RESUME_HIGHLIGHT",
    "study_guide": "STUDY_GUIDE",
    "quiz_pack": "QUIZ",
    "wiki_page": "WIKI_PAGE",
    "bilibili_course_note_pdf": "COURSE_NOTES",
    "report_draft": "REPORT",
    "faq_draft": "FAQ",
    "structured_note": "STRUCTURED_NOTE",
    "video_summary": "VIDEO_SUMMARY",
    "audio_minutes": "AUDIO_MINUTES",
    "course_notes": "COURSE_NOTES",
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
    canonical_skill_key = _resolve_canonical_skill_key(skill_key)
    return _SKILL_ACTION_BINDINGS.get(canonical_skill_key, "")


def _resolve_canonical_skill_key(value: str) -> str:
    normalized_skill_key = _normalize_skill_key(value)
    return _ALIASES.get(normalized_skill_key, normalized_skill_key)


def _normalize_skill_key(value: str) -> str:
    return value.strip().lower().replace("-", "_").replace(" ", "_")
