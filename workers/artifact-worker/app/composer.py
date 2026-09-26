from __future__ import annotations

from app.models import ArtifactExecutionPlan, ArtifactSectionDraft, ArtifactTaskInput


MINDMAP_SKILL_KEY = "mindmap_from_workspace"


def render_markdown(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    sections: list[ArtifactSectionDraft],
) -> str:
    if plan.execution_spec.skill_key == MINDMAP_SKILL_KEY:
        return _render_mindmap_markdown(task_input, plan, sections)

    artifact_title = resolve_artifact_title(task_input, plan)
    source_titles = [item.title for item in task_input.source_scope[:3]]
    lines = [
        f"# {artifact_title}",
        "",
        "## Generation Goal",
        f"- Skill: {plan.skill_key or task_input.input_payload.skill_key or 'legacy_action_request'}",
        f"- Goal: {plan.execution_spec.goal or task_input.input_payload.generation_brief or plan.action_display_name}",
        f"- Style profile: {plan.style_profile_key}",
        f"- Writeback gate: {plan.writeback_gate.decision} ({plan.writeback_gate.requested_mode})",
        "",
        "## Sections",
    ]

    for section in sections:
        lines.extend(
            [
                f"### {section.heading}",
                section.body,
            ]
        )
        if section.source_refs:
            lines.append(f"Source refs: {', '.join(section.source_refs)}")
        lines.append("")

    lines.extend(
        [
            "## Source Scope",
            f"- {', '.join(source_titles) if source_titles else 'workspace source pool'}",
        ]
    )

    if plan.notes:
        lines.extend(["", "## Control Notes"])
        for note in plan.notes:
            lines.append(f"- {note}")

    lines.extend(
        [
            "",
            "## Runtime Keywords",
            "- " + " / ".join(plan.required_phrases or ["Controlled Agentic Graph Harness"]),
        ]
    )

    return "\n".join(lines).strip() + "\n"


def resolve_artifact_title(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
) -> str:
    if plan.execution_spec.skill_key != MINDMAP_SKILL_KEY:
        return plan.action_display_name
    language = str(task_input.input_payload.inputs.get("language", "zh-CN")).strip().lower()
    return "Mind Map" if language == "en" else "工作台知识导图"


def _render_mindmap_markdown(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    sections: list[ArtifactSectionDraft],
) -> str:
    title = resolve_artifact_title(task_input, plan)
    requested_depth = str(task_input.input_payload.inputs.get("depth", "3")).strip()
    requested_layout = str(task_input.input_payload.inputs.get("layout", "balanced")).strip().lower()
    compact = requested_layout == "compact"
    max_sections = 5 if compact else 7
    max_phrases = 2 if compact or requested_depth == "2" else 4
    source_ref_limit = 0 if compact or requested_depth == "2" else 1
    lines = [f"# {title}"]
    for section in _order_mindmap_sections(plan, sections)[:max_sections]:
        lines.extend(["", f"## {_short_node_text(section.heading, 40)}"])
        for phrase in _mindmap_phrases(section.body, max_phrases, section.heading):
            lines.append(f"- {phrase}")
        for source_ref in section.source_refs[:source_ref_limit]:
            lines.append(f"  - 来源：{_short_node_text(source_ref, 52)}")

    source_titles = [item.title for item in task_input.source_scope[:5] if item.title.strip()]
    if source_titles:
        lines.extend(["", "## 来源"])
        lines.extend(f"- {_short_node_text(title, 52)}" for title in source_titles)
    return "\n".join(lines).strip() + "\n"


def _order_mindmap_sections(
    plan: ArtifactExecutionPlan,
    sections: list[ArtifactSectionDraft],
) -> list[ArtifactSectionDraft]:
    ordered: list[ArtifactSectionDraft] = []
    used_indexes: set[int] = set()
    for required_heading in plan.outline:
        for index, section in enumerate(sections):
            if index in used_indexes or section.heading != required_heading:
                continue
            ordered.append(section)
            used_indexes.add(index)
            break
    ordered.extend(section for index, section in enumerate(sections) if index not in used_indexes)
    return ordered


def _mindmap_phrases(body: str, limit: int, heading: str) -> list[str]:
    normalized = body.replace("；", "\n").replace("。", "\n")
    phrases: list[str] = []
    for raw_line in normalized.splitlines():
        line = raw_line.strip().lstrip("-*").strip()
        if not line:
            continue
        if line.startswith("输出单一根节点") and "”，" in line:
            line = line.split("”，", 1)[1].strip()
        if line.startswith((
            "配方重点：",
            "任务重点：",
            "节点侧重：",
            "输出语言：",
            "风格：",
            "技能：",
            "围绕“技能：",
            "补充说明：",
            "导图布局：",
            "内容层级：",
            "输出单一根节点",
        )):
            continue
        phrase = _short_node_text(line, 56)
        if phrase and phrase not in phrases:
            phrases.append(phrase)
        if len(phrases) >= limit:
            break
    fallback_by_heading = {
        "主题中心": "梳理当前工作台资料的主题全景",
        "关键概念": "提炼核心概念、关键对象与关系",
        "证据脉络": "按来源线索组织关键证据",
        "核心结论": "汇总可以直接复用的主要结论",
        "后续行动": "继续追问、验证并沉淀到 Wiki",
    }
    return phrases or [fallback_by_heading.get(heading, "待补充结构化要点")]


def _short_node_text(value: str, limit: int) -> str:
    normalized = " ".join(str(value).split()).strip("：:，,。.;； ")
    if len(normalized) <= limit:
        return normalized
    return normalized[: max(1, limit - 1)].rstrip() + "…"
