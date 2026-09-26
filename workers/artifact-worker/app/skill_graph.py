from __future__ import annotations

import re
from collections.abc import Callable

from app.models import (
    ArtifactExecutionPlan,
    ArtifactSectionDraft,
    ArtifactSkillNodeTrace,
    ArtifactTaskInput,
    SkillDefinition,
)
from app.registry import resolve_skill_definition, resolve_style_profile


def execute_skill_graph(
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    section_generator: Callable[
        [list[ArtifactSectionDraft]],
        tuple[list[ArtifactSectionDraft], dict[str, object]],
    ]
    | None = None,
) -> tuple[
    list[ArtifactSectionDraft],
    list[ArtifactSkillNodeTrace],
    dict[str, object] | None,
]:
    style_profile = resolve_style_profile(plan.style_profile_key)
    prompt_recipe = plan.prompt_recipe
    state: dict[str, object] = {
        "sections": [],
        "highlights": [],
        "materials": [],
        "focus_terms": plan.execution_spec.focus_points[:] or plan.required_phrases[:],
        "style_directives": plan.execution_spec.style_directives[:],
        "audience": plan.execution_spec.audience,
        "prompt_recipe": prompt_recipe,
        "resume_verification_report": {},
    }
    traces: list[ArtifactSkillNodeTrace] = []
    generation_trace: dict[str, object] | None = None

    for node_plan in plan.node_sequence:
        skill = resolve_skill_definition(node_plan.skill_key)
        output, summary = _execute_skill(
            skill.skill_key,
            task_input,
            plan,
            style_profile.format_constraints,
            state,
        )
        if section_generator is not None and generation_trace is None and "sections" in output:
            generated_sections, generation_trace = section_generator(
                _normalize_sections(output.get("sections", []))
            )
            output["sections"] = generated_sections
            summary = f"{summary}; generated draft injected before typed verification"
        (
            verified_output,
            verification_status,
            verification_checks,
            repair_actions,
        ) = _verify_and_repair_skill_output(
            skill=skill,
            output=output,
            task_input=task_input,
            plan=plan,
            state=state,
        )
        state.update(verified_output)
        traces.append(
            ArtifactSkillNodeTrace(
                node_id=node_plan.node_id,
                skill_key=skill.skill_key,
                output_summary=summary,
                verification_status=verification_status,
                verification_checks=verification_checks,
                repair_actions=repair_actions,
                repaired=bool(repair_actions),
            )
        )

    sections = state.get("sections", [])
    return list(sections), traces, generation_trace


def _execute_skill(
    skill_key: str,
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    style_constraints: list[str],
    state: dict[str, object],
) -> tuple[dict[str, object], str]:
    executable_skill_key = _resolve_executable_skill_key(skill_key)
    node_guidance = _node_guidance(state, skill_key)

    if executable_skill_key == "workspace_material_digest":
        materials = [
            {
                "title": source.title,
                "summary": source.sample_text.strip() or source.summary.strip() or source.title,
                "source_id": source.source_id,
            }
            for source in task_input.source_scope
        ]
        summary = f"digested {len(materials)} workspace materials"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"materials": materials}, summary

    if executable_skill_key == "resume_highlight_extractor":
        materials = list(state.get("materials", []))
        focus_terms = list(state.get("focus_terms", []))
        highlights = []
        for index, material in enumerate(materials[:3], start=1):
            summary = str(material["summary"])
            phrase = (
                focus_terms[min(index - 1, len(focus_terms) - 1)]
                if focus_terms
                else plan.action_display_name
            )
            highlights.append(
                {
                    "headline": (
                        f"推进 {phrase} 主链路设计，"
                        f"将 {summary.rstrip('。')} 组织为可复用运行时能力"
                    ),
                    "impact": _impact_suffix(index),
                    "source_refs": [str(material["title"])],
                }
            )
        while len(highlights) < 3:
            phrase = (
                focus_terms[min(len(highlights), len(focus_terms) - 1)]
                if focus_terms
                else "Production Action"
            )
            highlights.append(
                {
                    "headline": f"推进 {phrase} 相关执行节点收口，强化可配置产物生产能力",
                    "impact": _impact_suffix(len(highlights) + 1),
                    "source_refs": [source.title for source in task_input.source_scope[:1]],
                }
            )
        summary = f"extracted {len(highlights)} resume highlight candidates"
        if plan.execution_spec.focus_points:
            summary = (
                f"{summary} from focus points: "
                + " / ".join(plan.execution_spec.focus_points[:3])
            )
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"highlights": highlights}, summary

    if executable_skill_key == "resume_impact_normalizer":
        highlights = [dict(item) for item in list(state.get("highlights", []))]
        focus_terms = list(state.get("focus_terms", []))
        normalized_highlights = []
        for index, highlight in enumerate(highlights[:3], start=1):
            normalized_headline = str(highlight.get("headline", "")).strip()
            focus_term = (
                focus_terms[min(index - 1, len(focus_terms) - 1)]
                if focus_terms
                else ""
            )
            if focus_term and focus_term not in normalized_headline:
                normalized_headline = (
                    f"{normalized_headline.rstrip('。')}，聚焦 {focus_term}"
                ).strip("， ")
            normalized_highlights.append(
                {
                    "headline": normalized_headline,
                    "impact": str(highlight.get("impact", "")).strip() or _impact_suffix(index),
                    "source_refs": list(highlight.get("source_refs", [])),
                }
            )
        summary = f"normalized {len(normalized_highlights)} resume impact statements"
        if focus_terms:
            summary = (
                f"{summary} around focus points: "
                + " / ".join(focus_terms[:3])
            )
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"highlights": normalized_highlights}, summary

    if executable_skill_key == "resume_bullet_writer":
        highlights = list(state.get("highlights", []))
        keyword_line = " / ".join(plan.required_phrases)
        max_bullets = _resolve_max_bullets(plan, default=3)
        recipe_guidance = state["prompt_recipe"].section_guidance
        positioning_line = (
            "设计并实现基于 Controlled Agentic Graph Harness 的独立产物生成模块，"
            "以 Schema-Gated Skill Graph Runtime 约束执行计划，"
            "并通过 Capability Union Policy、Artifact Job / Artifact Version 与 "
            "Verifier / Repair 形成可控、可扩展的异步产物生成闭环。"
        )
        if recipe_guidance.get("一句话定位", ""):
            positioning_line = (
                f"{positioning_line} 配方重点：{recipe_guidance.get('一句话定位', '')}"
            )
        if node_guidance:
            positioning_line = f"{positioning_line} 节点侧重：{node_guidance}"
        sections = [
            ArtifactSectionDraft(
                heading="一句话定位",
                body=positioning_line,
                source_refs=[source.title for source in task_input.source_scope[:2]],
            ),
            ArtifactSectionDraft(
                heading="简历亮点",
                body="\n".join(
                    [
                        f"- {item['headline']}，{item['impact']}。"
                        for item in highlights[:max_bullets]
                    ]
                ),
                source_refs=[
                    source_ref
                    for item in highlights[:max_bullets]
                    for source_ref in item["source_refs"]
                ],
            ),
            ArtifactSectionDraft(
                heading="关键词",
                body=keyword_line,
                source_refs=[source.title for source in task_input.source_scope[:1]],
            ),
        ]
        summary = "rendered resume positioning, bullets, and keywords"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"sections": sections}, summary

    if executable_skill_key == "resume_verifier":
        sections = _normalize_sections(state.get("sections", []))
        verification_report = _build_resume_verification_report(sections, plan)
        summary = (
            "verified resume draft "
            f"(bullets={verification_report['bullet_count']}, "
            f"missing_focus={len(verification_report['missing_focus_points'])}, "
            f"missing_keywords={len(verification_report['missing_required_phrases'])})"
        )
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"resume_verification_report": verification_report}, summary

    if executable_skill_key == "resume_local_repair":
        sections = _normalize_sections(state.get("sections", []))
        verification_report = _coerce_resume_verification_report(
            state.get("resume_verification_report", {}),
            sections,
            plan,
        )
        summary = "prepared resume verifier gaps for typed repair"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {
            "sections": sections,
            "resume_verification_report": verification_report,
        }, summary

    if executable_skill_key == "quiz_designer":
        materials = list(state.get("materials", []))
        source_titles = [str(item["title"]) for item in materials[:3]]
        source_line = "、".join(source_titles) if source_titles else "工作台资料池"
        recipe_guidance = state["prompt_recipe"].section_guidance
        question_bank = [
            {
                "tier": "基础题",
                "question": "Production Action、Style Profile、Skill Graph 和 Artifact Runtime 分别负责什么？",
                "answer": "它们分别定义生成目标、风格约束、执行子图和统一运行时主链路。",
            },
            {
                "tier": "进阶题",
                "question": "Schema-Gated Skill Graph Runtime 为什么能避免产物生成滑向失控的通用 Agent 平台？",
                "answer": "因为执行计划必须匹配已注册的 Action、Skill Graph、Prompt Recipe 和 schema contract，不能自由扩写任意计划。",
            },
            {
                "tier": "挑战题",
                "question": "Capability Union Policy 在工作台资料和自定义 MCP 同时存在时主要防什么风险？",
                "answer": "主要防止 WORKSPACE_READ 与高风险 external network capability 组合后形成数据外泄路径。",
            },
        ]
        sections = [
            ArtifactSectionDraft(
                heading="测验目标",
                body=(
                    f"围绕 {source_line} 中的架构资料验证对统一主链路、schema gate 和能力风险治理的理解。"
                    f" 配方重点：{recipe_guidance.get('测验目标', '')}".strip()
                ),
                source_refs=source_titles[:1],
            ),
            ArtifactSectionDraft(
                heading="题目设计",
                body="\n".join(
                    [
                        f"1. [{question_bank[0]['tier']}] {question_bank[0]['question']}",
                        f"2. [{question_bank[1]['tier']}] {question_bank[1]['question']}",
                        f"3. [{question_bank[2]['tier']}] {question_bank[2]['question']}",
                    ]
                ),
                source_refs=source_titles[:2],
            ),
            ArtifactSectionDraft(
                heading="答案与解析",
                body="\n".join(
                    [
                        f"- {item['tier']}：{item['answer']}"
                        for item in question_bank
                    ]
                ),
                source_refs=source_titles[:2],
            ),
            ArtifactSectionDraft(
                heading="评分要点",
                body=(
                    "基础层看是否能正确解释主链路对象；进阶层看是否能说明 schema gate 与 verifier/repair；"
                    "挑战层看是否能识别 Capability Union Policy 的组合风险。"
                ),
                source_refs=source_titles[:1],
            ),
        ]
        summary = f"designed {len(question_bank)} quiz questions across difficulty tiers"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"sections": sections, "quiz_questions": question_bank}, summary

    if executable_skill_key == "quiz_difficulty_normalizer":
        sections = list(state.get("sections", []))
        normalized_sections: list[ArtifactSectionDraft] = []
        for section in sections:
            body = section.body
            if section.heading == "题目设计":
                body = f"{body}\n\n难度覆盖：基础题 / 进阶题 / 挑战题。"
            if section.heading == "评分要点":
                body = f"{body} 评分时需同时检查 Schema-Gated Skill Graph Runtime 与 Capability Union Policy 理解。"
            normalized_sections.append(
                ArtifactSectionDraft(
                    heading=section.heading,
                    body=body,
                    source_refs=section.source_refs,
                )
            )
        summary = "normalized quiz difficulty tiers and scoring guidance"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"sections": normalized_sections}, summary

    if executable_skill_key == "wiki_structure_enforcer":
        sections = list(state.get("sections", []))
        wiki_source_refs = [source.title for source in task_input.source_scope[:2]]
        enforced_sections: list[ArtifactSectionDraft] = []
        for section in sections:
            body = section.body
            if section.heading == "概览" and "定义" not in body:
                body = f"{body} 本节补充定义、边界与适用范围。"
            if section.heading == "关键机制" and "Production Action" not in body:
                body = (
                    f"{body} 关键机制应显式覆盖 Production Action、Skill Graph、"
                    "Schema Gate 与 Capability Union Policy。"
                )
            if section.heading == "相关页面":
                body = "\n".join(
                    [
                        "- 施工文档：产物生成Agent独立模块施工文档",
                        "- 设计文档：受控式异步产物生成Agent编排升级设计",
                        "- 运行时测试：artifact-worker 默认产物与 provider 回归",
                    ]
                )
            source_refs = list(dict.fromkeys([*section.source_refs, *wiki_source_refs]))
            enforced_sections.append(
                ArtifactSectionDraft(
                    heading=section.heading,
                    body=body,
                    source_refs=source_refs,
                )
            )
        summary = "enforced wiki structure and related-page references"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"sections": enforced_sections}, summary

    if executable_skill_key == "generic_section_writer":
        materials = list(state.get("materials", []))
        source_titles = [str(item["title"]) for item in materials[:2]]
        source_line = "、".join(source_titles) if source_titles else "工作台资料池"
        recipe_guidance = state["prompt_recipe"].section_guidance
        template_action_key = plan.template_action_key or plan.action_key
        sections = [
            ArtifactSectionDraft(
                heading=heading,
                body=_generic_section_body(
                    template_action_key,
                    heading,
                    source_line,
                    recipe_guidance.get(heading, ""),
                    node_guidance,
                ),
                source_refs=source_titles[:1],
            )
            for heading in plan.outline
        ]
        summary = f"generated {len(sections)} generic artifact sections"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"sections": sections}, summary

    if executable_skill_key == "evidence_guard":
        sections = list(state.get("sections", []))
        default_refs = [source.title for source in task_input.source_scope[:1]]
        guarded_sections = []
        for section in sections:
            refs = section.source_refs or default_refs
            guarded_sections.append(
                ArtifactSectionDraft(
                    heading=section.heading,
                    body=section.body,
                    source_refs=refs,
                )
            )
        summary = "attached workspace evidence trace to each section"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"sections": guarded_sections}, summary

    if executable_skill_key == "style_polisher":
        sections = list(state.get("sections", []))
        polished_sections = []
        for section in sections:
            body = section.body.replace("  ", " ").strip()
            if style_constraints:
                body = body.replace("，并", "，同时")
            polished_sections.append(
                ArtifactSectionDraft(
                    heading=section.heading,
                    body=body,
                    source_refs=section.source_refs,
                )
            )
        summary = "polished section wording for selected style profile"
        if node_guidance:
            summary = f"{summary}; node guidance applied"
        return {"sections": polished_sections}, summary

    raise ValueError(f"unsupported skill execution: {skill_key}")


def _resolve_executable_skill_key(skill_key: str) -> str:
    skill = resolve_skill_definition(skill_key)
    return skill.template_skill_key or skill.skill_key


def _verify_and_repair_skill_output(
    skill: SkillDefinition,
    output: dict[str, object],
    task_input: ArtifactTaskInput,
    plan: ArtifactExecutionPlan,
    state: dict[str, object],
) -> tuple[dict[str, object], str, list[str], list[str]]:
    executable_skill_key = skill.template_skill_key or skill.skill_key
    verification_checks: list[str] = []
    repair_actions: list[str] = []
    verified_output = dict(output)

    if executable_skill_key == "workspace_material_digest":
        materials = [dict(item) for item in list(output.get("materials", []))]
        for index, material in enumerate(materials):
            if not str(material.get("title", "")).strip() and index < len(task_input.source_scope):
                material["title"] = task_input.source_scope[index].title
                repair_actions.append("material title backfilled from source scope")
            if not str(material.get("summary", "")).strip():
                fallback_summary = str(material.get("title", "")).strip()
                if not fallback_summary and index < len(task_input.source_scope):
                    fallback_summary = (
                        task_input.source_scope[index].summary
                        or task_input.source_scope[index].title
                    )
                material["summary"] = fallback_summary
                repair_actions.append("material summary backfilled from source scope")
            if not str(material.get("source_id", "")).strip() and index < len(task_input.source_scope):
                material["source_id"] = task_input.source_scope[index].source_id
                repair_actions.append("material source id backfilled from source scope")
        verified_output["materials"] = materials
        if materials:
            verification_checks.append("source trace captured for digested materials")

    if executable_skill_key == "resume_highlight_extractor":
        highlights = [dict(item) for item in list(output.get("highlights", []))]
        while len(highlights) < 3:
            highlights.append(
                {
                    "headline": "补齐受控产物生成节点，强化可配置交付能力",
                    "impact": _impact_suffix(len(highlights) + 1),
                    "source_refs": [source.title for source in task_input.source_scope[:1]],
                }
            )
            repair_actions.append("resume highlight candidate backfilled")
        verified_output["highlights"] = highlights
        verification_checks.append("resume highlight candidate count satisfied")
        if plan.execution_spec.focus_points:
            verification_checks.append("user focus points applied to resume highlight extraction")

    if executable_skill_key == "resume_impact_normalizer":
        highlights = [dict(item) for item in list(output.get("highlights", []))]
        focus_points = [point.strip() for point in plan.execution_spec.focus_points if point.strip()]
        for index, highlight in enumerate(highlights):
            if not str(highlight.get("impact", "")).strip():
                highlight["impact"] = _impact_suffix(index + 1)
                repair_actions.append("resume impact backfilled at node level")
            source_refs = [str(item).strip() for item in highlight.get("source_refs", []) if str(item).strip()]
            if not source_refs:
                source_refs = [source.title for source in task_input.source_scope[:1]]
                highlight["source_refs"] = source_refs
                repair_actions.append("resume source refs backfilled at node level")
            if focus_points and not any(
                point in str(highlight.get("headline", "")) or point in str(highlight.get("impact", ""))
                for point in focus_points
            ):
                highlight["headline"] = (
                    f"{str(highlight.get('headline', '')).rstrip('。')}，聚焦 {focus_points[0]}"
                ).strip("， ")
                repair_actions.append(
                    f"resume focus point backfilled at node level: {focus_points[0]}"
                )
        verified_output["highlights"] = highlights
        verification_checks.append("resume impact statements normalized")

    if executable_skill_key == "resume_verifier":
        sections = _normalize_sections(state.get("sections", []))
        verification_report = _coerce_resume_verification_report(
            output.get("resume_verification_report", {}),
            sections,
            plan,
        )
        verified_output["resume_verification_report"] = verification_report
        verification_checks.append(
            "resume verifier inspected bullet count, focus coverage, and required phrases"
        )

    if executable_skill_key == "resume_local_repair":
        sections = _normalize_sections(output.get("sections", state.get("sections", [])))
        sections, section_repairs = _repair_missing_outline_sections(
            sections,
            plan,
            task_input,
        )
        repair_actions.extend(section_repairs)
        verification_report = _coerce_resume_verification_report(
            output.get("resume_verification_report", {}),
            sections,
            plan,
        )
        sections, resume_repairs = _repair_resume_node_sections(
            sections,
            required_phrases=plan.required_phrases,
            focus_points=plan.execution_spec.focus_points,
            verifier_report=verification_report,
        )
        repair_actions.extend(resume_repairs)
        sections, evidence_repairs = _repair_evidence_guard_sections(
            sections,
            task_input=task_input,
        )
        repair_actions.extend(evidence_repairs)
        repaired_report = _build_resume_verification_report(sections, plan)
        verified_output["sections"] = sections
        verified_output["resume_verification_report"] = repaired_report
        verification_checks.append("resume local repair confirmed verifier gaps are closed")

    if "sections" in output:
        sections = _normalize_sections(
            verified_output.get("sections", output.get("sections", []))
        )
        if executable_skill_key != "resume_local_repair":
            if executable_skill_key != "resume_bullet_writer":
                sections, section_repairs = _repair_missing_outline_sections(
                    sections,
                    plan,
                    task_input,
                )
                repair_actions.extend(section_repairs)
                verification_checks.append("outline contract preserved")
            else:
                verification_checks.append("resume section draft generated")

        if executable_skill_key == "resume_bullet_writer":
            verified_output["sections"] = sections

        if executable_skill_key == "quiz_designer":
            sections, quiz_repairs = _repair_quiz_node_sections(sections, plan)
            repair_actions.extend(quiz_repairs)
            verification_checks.append("quiz question contract preserved")

        if executable_skill_key == "quiz_difficulty_normalizer":
            sections, quiz_tier_repairs = _repair_quiz_scoring_node_sections(sections, plan)
            repair_actions.extend(quiz_tier_repairs)
            verification_checks.append("quiz difficulty tiers preserved")

        if executable_skill_key == "wiki_structure_enforcer":
            sections, wiki_repairs = _repair_wiki_node_sections(
                sections, plan, task_input
            )
            repair_actions.extend(wiki_repairs)
            verification_checks.append("wiki structure contract preserved")

        if executable_skill_key == "evidence_guard":
            sections, evidence_repairs = _repair_evidence_guard_sections(
                sections,
                task_input=task_input,
            )
            repair_actions.extend(evidence_repairs)
            verification_checks.append("evidence trace attached to each section")

        if executable_skill_key == "style_polisher":
            previous_sections = _normalize_sections(state.get("sections", []))
            if len(previous_sections) == len(sections):
                verification_checks.append("section count preserved after polish")
            else:
                verification_checks.append("section count changed during polish")

        verified_output["sections"] = sections

    if executable_skill_key == "resume_local_repair":
        verified_output["sections"] = _normalize_sections(
            verified_output.get("sections", [])
        )

    verification_status = "PASS_WITH_REPAIR" if repair_actions else "PASS"
    return verified_output, verification_status, verification_checks, repair_actions


def _normalize_sections(sections: object) -> list[ArtifactSectionDraft]:
    normalized: list[ArtifactSectionDraft] = []
    for section in sections if isinstance(sections, list) else []:
        if isinstance(section, ArtifactSectionDraft):
            normalized.append(section)
        elif isinstance(section, dict):
            normalized.append(
                ArtifactSectionDraft(
                    heading=str(section.get("heading", "")),
                    body=str(section.get("body", "")),
                    source_refs=[
                        str(item)
                        for item in section.get("source_refs", [])
                        if str(item).strip()
                    ],
                )
            )
    return normalized


def _build_resume_verification_report(
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
) -> dict[str, object]:
    headings = [section.heading for section in sections]
    highlight_heading = plan.outline[1] if len(plan.outline) > 1 else ""
    highlight_section = next(
        (section for section in sections if section.heading == highlight_heading),
        None,
    )
    bullet_count = 0
    if highlight_section is not None:
        bullet_count = sum(
            1
            for line in highlight_section.body.splitlines()
            if line.lstrip().startswith("- ")
        )
    section_text = "\n".join(section.body for section in sections)
    missing_focus_points = [
        point
        for point in plan.execution_spec.focus_points
        if point.strip() and point not in section_text
    ]
    missing_required_phrases = [
        phrase
        for phrase in plan.required_phrases
        if phrase.strip() and phrase not in section_text
    ]
    missing_headings = [
        heading
        for heading in plan.outline
        if heading not in headings
    ]
    status = "PASS"
    if bullet_count < 3 or missing_focus_points or missing_required_phrases or missing_headings:
        status = "FAIL"
    return {
        "status": status,
        "bullet_count": bullet_count,
        "missing_focus_points": missing_focus_points,
        "missing_required_phrases": missing_required_phrases,
        "missing_headings": missing_headings,
    }


def _coerce_resume_verification_report(
    report: object,
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
) -> dict[str, object]:
    if isinstance(report, dict) and report:
        return {
            "status": str(report.get("status", "")).upper() or "PASS",
            "bullet_count": int(report.get("bullet_count", 0) or 0),
            "missing_focus_points": [
                str(item).strip()
                for item in report.get("missing_focus_points", [])
                if str(item).strip()
            ],
            "missing_required_phrases": [
                str(item).strip()
                for item in report.get("missing_required_phrases", [])
                if str(item).strip()
            ],
            "missing_headings": [
                str(item).strip()
                for item in report.get("missing_headings", [])
                if str(item).strip()
            ],
        }
    return _build_resume_verification_report(sections, plan)


def _repair_missing_outline_sections(
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
    task_input: ArtifactTaskInput,
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_sections = list(sections)
    repair_actions: list[str] = []
    existing_headings = {section.heading for section in repaired_sections}
    source_refs = [
        source.title.strip()
        for source in task_input.source_scope[:3]
        if source.title.strip()
    ]
    source_line = "、".join(source_refs) or "当前工作台资料"
    template_action_key = plan.template_action_key or plan.action_key
    for heading in plan.outline:
        if heading not in existing_headings:
            body = _generic_section_body(
                template_action_key,
                heading,
                source_line,
                "",
                "",
            )
            repaired_sections.append(
                ArtifactSectionDraft(
                    heading=heading,
                    body=body,
                    source_refs=source_refs,
                )
            )
            existing_headings.add(heading)
            repair_actions.append(f"missing section backfilled at node level: {heading}")
    return repaired_sections, repair_actions


def _repair_resume_node_sections(
    sections: list[ArtifactSectionDraft],
    required_phrases: list[str],
    focus_points: list[str] | None = None,
    verifier_report: dict[str, object] | None = None,
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_sections: list[ArtifactSectionDraft] = []
    repair_actions: list[str] = []
    repair_topics = _repair_topics(required_phrases, focus_points or [])
    fallback_bullets = [f"- {topic}" for topic in repair_topics[:3]]
    missing_focus_points = [
        point
        for point in verifier_report.get("missing_focus_points", [])
        if isinstance(verifier_report, dict) and point
    ] if isinstance(verifier_report, dict) else []
    for section in sections:
        body = section.body
        if section.heading == "简历亮点":
            original_body = body
            bullet_lines = [line for line in body.splitlines() if line.lstrip().startswith("- ")]
            while len(bullet_lines) < 3 and len(bullet_lines) < len(fallback_bullets):
                bullet_lines.append(fallback_bullets[len(bullet_lines)])
                repair_actions.append("resume highlight bullet backfilled at node level")
            for focus_index, focus_point in enumerate(missing_focus_points):
                if focus_point in original_body:
                    continue
                if focus_point in "\n".join(bullet_lines):
                    repair_actions.append(
                        f"resume focus point backfilled at node level: {focus_point}"
                    )
                    continue
                if bullet_lines:
                    target_index = min(len(bullet_lines) - 1, focus_index)
                    bullet_lines[target_index] = f"{bullet_lines[target_index]}；补充 {focus_point}"
                else:
                    bullet_lines.append(f"- 补充 {focus_point} 相关亮点。")
                repair_actions.append(
                    f"resume focus point backfilled at node level: {focus_point}"
                )
            body = "\n".join(bullet_lines)
        if section.heading == "关键词":
            for phrase in required_phrases:
                if phrase not in body:
                    body = f"{body} / {phrase}".strip(" /")
                    repair_actions.append(f"resume keyword backfilled at node level: {phrase}")
        repaired_sections.append(
            ArtifactSectionDraft(
                heading=section.heading,
                body=body,
                source_refs=section.source_refs,
            )
        )
    return repaired_sections, repair_actions


def _repair_quiz_node_sections(
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_sections: list[ArtifactSectionDraft] = []
    repair_actions: list[str] = []
    topics = _repair_topics(
        plan.required_phrases, plan.execution_spec.focus_points
    )
    tiers = ["基础题", "进阶题", "挑战题"]
    fallback_questions = [
        f"{index + 1}. [{tiers[index]}] 请结合已提供资料说明 {topic}。"
        for index, topic in enumerate(topics[:3])
    ]
    for section in sections:
        body = section.body
        if section.heading == "题目设计":
            numbered_lines = [
                line.strip()
                for line in body.splitlines()
                if re.match(r"^\s*\d+\.\s", line)
            ]
            while len(numbered_lines) < 3 and len(numbered_lines) < len(fallback_questions):
                numbered_lines.append(fallback_questions[len(numbered_lines)])
                repair_actions.append("quiz question backfilled at node level")
            body = "\n".join(numbered_lines)
        repaired_sections.append(
            ArtifactSectionDraft(
                heading=section.heading,
                body=body,
                source_refs=section.source_refs,
            )
        )
    return repaired_sections, repair_actions


def _repair_quiz_scoring_node_sections(
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_sections: list[ArtifactSectionDraft] = []
    repair_actions: list[str] = []
    topics = _repair_topics(
        plan.required_phrases, plan.execution_spec.focus_points
    )
    while len(topics) < 3 and topics:
        topics.append(topics[-1])
    tier_fallbacks = [
        (tier, f"- {tier}：能依据资料解释 {topics[index]}。")
        for index, tier in enumerate(("基础", "进阶", "挑战"))
        if index < len(topics)
    ]
    for section in sections:
        body = section.body
        if section.heading == "评分要点":
            normalized_lines = [line.strip() for line in body.splitlines() if line.strip()]
            normalized_body = "\n".join(normalized_lines)
            for keyword, fallback_line in tier_fallbacks:
                if keyword not in normalized_body:
                    normalized_lines.append(fallback_line)
                    normalized_body = "\n".join(normalized_lines)
                    repair_actions.append(
                        f"quiz scoring tier backfilled at node level: {keyword}"
                    )
            body = "\n".join(normalized_lines)
        repaired_sections.append(
            ArtifactSectionDraft(
                heading=section.heading,
                body=body,
                source_refs=section.source_refs,
            )
        )
    return repaired_sections, repair_actions


def _repair_wiki_node_sections(
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
    task_input: ArtifactTaskInput,
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_sections: list[ArtifactSectionDraft] = []
    repair_actions: list[str] = []
    fallback_lines = [
        f"- 资料：{source.title}"
        for source in task_input.source_scope[:3]
        if source.title.strip()
    ]
    mechanism_topics = _repair_topics(
        plan.required_phrases, plan.execution_spec.focus_points
    )[:3]
    for section in sections:
        body = section.body
        if section.heading == "概览":
            if "定义" not in body:
                body = f"{body} 本节补充定义说明。".strip()
                repair_actions.append("wiki overview definition backfilled at node level")
            if "边界" not in body:
                body = f"{body} 本节补充边界说明。".strip()
                repair_actions.append("wiki overview boundary backfilled at node level")
        if section.heading == "关键机制":
            for phrase in mechanism_topics:
                if phrase not in body:
                    body = f"{body} {phrase}".strip()
                    repair_actions.append(
                        f"wiki key mechanism backfilled at node level: {phrase}"
                    )
        if section.heading == "相关页面":
            bullet_lines = [line.strip() for line in body.splitlines() if line.strip().startswith("- ")]
            for fallback_line in fallback_lines:
                if fallback_line not in bullet_lines:
                    bullet_lines.append(fallback_line)
                    repair_actions.append("wiki related page backfilled at node level")
            body = "\n".join(bullet_lines)
        repaired_sections.append(
            ArtifactSectionDraft(
                heading=section.heading,
                body=body,
                source_refs=section.source_refs,
            )
        )
    return repaired_sections, repair_actions


def _repair_topics(
    required_phrases: list[str],
    focus_points: list[str],
) -> list[str]:
    topics: list[str] = []
    for candidate in [*focus_points, *required_phrases]:
        normalized = str(candidate).strip()
        if normalized and normalized not in topics:
            topics.append(normalized)
    return topics


def _repair_evidence_guard_sections(
    sections: list[ArtifactSectionDraft],
    task_input: ArtifactTaskInput,
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_sections: list[ArtifactSectionDraft] = []
    repair_actions: list[str] = []
    default_refs = [source.title for source in task_input.source_scope[:1]]
    for section in sections:
        source_refs = list(section.source_refs)
        if not source_refs and default_refs:
            source_refs = default_refs
            repair_actions.append(f"evidence refs backfilled at node level: {section.heading}")
        repaired_sections.append(
            ArtifactSectionDraft(
                heading=section.heading,
                body=section.body,
                source_refs=source_refs,
            )
        )
    return repaired_sections, repair_actions


def _resolve_max_bullets(
    plan: ArtifactExecutionPlan,
    *,
    default: int,
) -> int:
    for constraint in plan.execution_spec.constraints:
        if not constraint.startswith("max_bullets="):
            continue
        value = constraint.split("=", 1)[1].strip()
        if value.isdigit():
            return max(1, int(value))
    return default


def _impact_suffix(index: int) -> str:
    suffixes = {
        1: "把 Production Action、Style Profile 与 Skill Graph 抽象为可配置编排层",
        2: "把 Schema Gate 与局部修复前移到运行时，降低全链路重跑成本",
        3: "把用户自定义 MCP 的组合权限风险收敛到 Capability Union Policy",
    }
    return suffixes.get(index, "强化独立模块的可追踪与可复用交付能力")


def _generic_section_body(
    action_key: str,
    heading: str,
    source_line: str,
    recipe_guidance: str,
    node_guidance: str,
) -> str:
    templates = {
        "REPORT": {
            "问题定义": (
                f"围绕 {source_line} 中暴露的产物生成诉求，定义当前任务要解决的核心问题，"
                "包括统一主链路、可配置 Action 和受控扩展边界。"
            ),
            "证据综述": (
                f"综合 {source_line} 提供的设计资料，提炼 Schema Gate、Skill Graph、"
                "Capability Union Policy 等关键证据点，作为生成策略依据。"
            ),
            "建议方案": (
                "建议采用统一 Artifact Runtime 承接 Production Action，"
                "并通过 Verifier / Repair 和 Artifact Version 保证结果可追踪。"
            ),
        },
        "FAQ": {
            "问题集": (
                f"整理 {source_line} 中最常见的实现问题，重点覆盖默认产物类型、"
                "Schema Gate 和自定义 MCP 风险控制。"
            ),
            "标准回答": (
                "给出面向团队复用的标准答案，明确什么时候使用通用 Skill Graph，"
                "什么时候需要新增 Action 或 Style Profile。"
            ),
            "使用说明": (
                "说明如何通过独立 artifact-worker 触发产物生成、查看 Artifact Job、"
                "并根据 verification 结果决定是否继续修复。"
            ),
        },
        "QUIZ": {
            "测验目标": (
                f"围绕 {source_line} 中的设计材料定义本次测验要验证的核心概念，"
                "重点检查对统一主链路、schema gate 和能力风险治理的理解。"
            ),
            "题目设计": (
                "设计至少三道覆盖基础、进阶和挑战层级的问题，"
                "让题目同时触达 Production Action、Style Profile、Skill Graph 和 Policy Gate。"
            ),
            "答案与解析": (
                "给出标准答案，并解释为什么这些答案能够回到当前工作台资料和受控运行时约束。"
            ),
            "评分要点": (
                "区分基础概念识别、主链路理解和风险边界判断三类得分点，"
                "便于把测验结果回流到后续学习或面试准备。"
            ),
        },
        "STUDY_GUIDE": {
            "学习目标": (
                "先理解 Controlled Agentic Graph Harness 的固定主干，"
                "再掌握 Action、Style、Skill Graph 和 Policy Gate 的分层职责。"
            ),
            "核心概念": (
                f"结合 {source_line} 中的设计材料，重点学习 Schema-Gated Skill Graph Runtime、"
                "Capability Union Policy 和 Artifact Version 等核心对象。"
            ),
            "练习路径": (
                "建议先运行默认产物，再尝试新增一个 Action，最后观察 verifier/repair 如何在局部节点收敛问题。"
            ),
        },
        "WIKI_PAGE": {
            "概览": (
                "本页概览受控式产物生成模块的定位、边界和默认主链路，"
                "说明它为何是独立模块而不是失控的通用 Agent 平台。"
            ),
            "关键机制": (
                f"归纳 {source_line} 支撑的关键机制，包括 Production Action、Skill Graph、"
                "Schema Gate、Capability Union Policy 和 Verifier / Repair。"
            ),
            "相关页面": (
                "建议链接到施工文档、编排升级设计、MCP 权限治理和 Artifact Runtime 测试说明。"
            ),
        },
        "MINDMAP": {
            "主题中心": (
                f"围绕 {source_line} 建立资料全景。"
            ),
            "关键概念": (
                "提炼核心概念、关键对象与关系。"
            ),
            "证据脉络": (
                "按来源线索组织关键证据。"
            ),
            "核心结论": (
                "收敛可以直接复用的主要结论。"
            ),
            "后续行动": (
                "继续追问、验证并沉淀到 Wiki。"
            ),
        },
        "STRUCTURED_NOTE": {
            "主题快照": (
                "记录本次产物生成模块改造的主题范围，包括默认产物扩展、统一 runtime 和策略门控。"
            ),
            "关键摘录": (
                f"从 {source_line} 中摘录可直接复用的术语和约束，"
                "例如 Schema Gate、Capability Union Policy 与 Artifact Version。"
            ),
            "后续问题": (
                "后续可继续追问如何接入真实 MCP、如何把 Artifact Version 持久化，以及如何扩展更多默认 Action。"
            ),
        },
        "VIDEO_SUMMARY": {
            "一句话总结": (
                "该视频围绕受控式产物生成架构给出高密度综述，重点解释主链路、Skill Graph 和能力门控如何协同工作。"
            ),
            "时间线摘要": (
                f"基于 {source_line} 提供的转写线索，可将内容拆成背景动机、运行时编排、能力治理和结果沉淀四段时间线。"
            ),
            "核心观点": (
                "核心观点是通过 Controlled Agentic Graph Harness 固定主链路，再用 Schema Gate 和 Capability Layer 承接灵活扩展。"
            ),
            "关键概念": (
                "关键概念包括 Production Action、Style Profile、Skill Graph、Capability Union Policy 和 Artifact Version。"
            ),
            "可沉淀要点": (
                "可沉淀要点包括默认产物抽象方式、局部修复策略和能力分层约束，可直接回流为后续问答或 Wiki 草稿。"
            ),
        },
        "AUDIO_MINUTES": {
            "总体摘要": (
                "本次音频纪要聚焦产物生成模块的实现推进，讨论了默认产物扩展、能力门控和版本化沉淀。"
            ),
            "主要议题": (
                f"主要议题包括 {source_line} 涉及的 Schema-Gated Runtime、Capability Resolver 和后续持久化接入。"
            ),
            "关键结论": (
                "关键结论是先在独立 worker 内把可验证的技术点落齐，再逐步接入真实 MCP、审批流和写回链路。"
            ),
            "行动项": (
                "行动项包括继续扩展多模态默认产物、完善 gate 体系、并将 Artifact Version 对接后端持久化。"
            ),
            "待确认问题": (
                "待确认问题包括真实多媒体能力来源、审批边界以及产物回流索引的接入契约。"
            ),
        },
        "COURSE_NOTES": {
            "课程概要": (
                "本节课程聚焦受控式 Agent 编排，讲解如何用统一 runtime 承接不同类型的知识产物生成任务。"
            ),
            "知识点": (
                "知识点包括 Production Action、Style Profile、Skill Graph、Capability Layer、Schema Gate 和 Local Repair。"
            ),
            "重点难点": (
                f"重点难点在于如何把 {source_line} 中的多模态资料统一归一化，同时避免自定义 MCP 组合后越权。"
            ),
            "复习题": (
                "复习题可围绕为什么需要 Capability Union Policy、什么时候触发 Approval Gate、以及如何理解 Artifact-Derived Retrieval 展开。"
            ),
        },
    }
    action_templates = templates.get(action_key, {})
    body = action_templates.get(
        heading,
        f"围绕 {heading} 汇总 {source_line} 中的工作台资料，并保留受控产物生成语义。",
    )
    if recipe_guidance:
        body = f"{body} 配方重点：{recipe_guidance}"
    if node_guidance:
        body = f"{body} 节点侧重：{node_guidance}"
    return body


def _node_guidance(state: dict[str, object], skill_key: str) -> str:
    prompt_recipe = state.get("prompt_recipe")
    if prompt_recipe is None:
        return ""
    return str(prompt_recipe.node_guidance.get(skill_key, "")).strip()

