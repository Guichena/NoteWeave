from __future__ import annotations

import re

from app.models import ArtifactExecutionPlan, ArtifactSectionDraft


def repair_sections(
    sections: list[ArtifactSectionDraft],
    plan: ArtifactExecutionPlan,
    forbidden_patterns: list[str],
) -> tuple[list[ArtifactSectionDraft], list[str]]:
    repaired_checks: list[str] = []
    repaired_sections: list[ArtifactSectionDraft] = []

    by_heading = {section.heading: section for section in sections}
    for heading in plan.outline:
        section = by_heading.get(heading)
        if section is None:
            repaired_sections.append(
                ArtifactSectionDraft(
                    heading=heading,
                    body=_default_section_body(heading, plan),
                    source_refs=[],
                )
            )
            repaired_checks.append(f"missing section repaired: {heading}")
            continue

        body = section.body
        for pattern in forbidden_patterns:
            if pattern and pattern in body:
                body = body.replace(pattern, "[removed]")
                repaired_checks.append(f"forbidden pattern removed: {pattern}")

        if plan.action_key == "QUIZ":
            question_heading = plan.outline[1] if len(plan.outline) > 1 else ""
            scoring_heading = plan.outline[3] if len(plan.outline) > 3 else ""
            if heading == question_heading:
                body, quiz_question_repairs = _repair_quiz_questions(body)
                repaired_checks.extend(quiz_question_repairs)
            if heading == scoring_heading:
                body, quiz_scoring_repairs = _repair_quiz_scoring_tiers(body)
                repaired_checks.extend(quiz_scoring_repairs)

        if plan.action_key == "WIKI_PAGE":
            overview_heading = plan.outline[0] if len(plan.outline) > 0 else ""
            mechanism_heading = plan.outline[1] if len(plan.outline) > 1 else ""
            related_pages_heading = plan.outline[2] if len(plan.outline) > 2 else ""
            if heading == overview_heading:
                body, wiki_overview_repairs = _repair_wiki_overview(body)
                repaired_checks.extend(wiki_overview_repairs)
            if heading == mechanism_heading:
                body, wiki_mechanism_repairs = _repair_wiki_key_mechanisms(body)
                repaired_checks.extend(wiki_mechanism_repairs)
            if heading == related_pages_heading:
                body, wiki_related_repairs = _repair_wiki_related_pages(body)
                repaired_checks.extend(wiki_related_repairs)

        if heading == "简历亮点":
            body, highlight_repairs = _repair_resume_highlights(body)
            repaired_checks.extend(highlight_repairs)

        if heading == "关键词":
            body, keyword_repairs = _repair_keywords(body, plan.required_phrases)
            repaired_checks.extend(keyword_repairs)

        repaired_sections.append(
            ArtifactSectionDraft(
                heading=section.heading,
                body=body,
                source_refs=section.source_refs,
            )
        )

    return repaired_sections, repaired_checks


def _default_section_body(
    heading: str,
    plan: ArtifactExecutionPlan,
) -> str:
    if plan.action_key == "QUIZ":
        if heading == (plan.outline[0] if len(plan.outline) > 0 else ""):
            return (
                "围绕受控式产物生成主链路验证对 Production Action、Skill Graph、"
                "Schema-Gated Skill Graph Runtime 与 Capability Union Policy 的理解。"
            )
        if heading == (plan.outline[1] if len(plan.outline) > 1 else ""):
            return "\n".join(
                [
                    "1. [基础题] Production Action、Style Profile、Skill Graph 和 Artifact Runtime 分别负责什么？",
                    "2. [进阶题] Schema-Gated Skill Graph Runtime 如何约束执行计划？",
                    "3. [挑战题] Capability Union Policy 在工作台资料与高风险 external network capability 组合时主要防什么风险？",
                ]
            )
        if heading == (plan.outline[2] if len(plan.outline) > 2 else ""):
            return "\n".join(
                [
                    "- 基础题：它们分别定义生成目标、风格约束、执行子图与统一运行时主链路。",
                    "- 进阶题：它通过 schema contract、action 兼容性和 skill graph 校验限制计划扩张。",
                    "- 挑战题：它阻止 WORKSPACE_READ 与高风险 external network capability 组合后形成数据外泄路径。",
                ]
            )
        if heading == (plan.outline[3] if len(plan.outline) > 3 else ""):
            return "\n".join(
                [
                    "- 基础：能解释统一主链路与核心对象职责。",
                    "- 进阶：能说明 schema gate、verifier 和 repair 的协作关系。",
                    "- 挑战：能识别 Capability Union Policy 的组合权限风险。",
                ]
            )
    if plan.action_key == "WIKI_PAGE":
        if heading == (plan.outline[0] if len(plan.outline) > 0 else ""):
            return (
                "本页概览受控式异步产物生成模块的定义、边界与默认主链路，"
                "说明它为何作为独立模块存在而不是失控的通用 Agent 平台。"
            )
        if heading == (plan.outline[1] if len(plan.outline) > 1 else ""):
            return (
                "关键机制包括 Production Action、Skill Graph、Schema Gate、"
                "Capability Union Policy 与 Verifier / Repair。"
            )
        if heading == (plan.outline[2] if len(plan.outline) > 2 else ""):
            return "\n".join(
                [
                    "- 施工文档：产物生成Agent独立模块施工文档",
                    "- 设计文档：受控式异步产物生成Agent编排升级设计",
                    "- 运行时测试：artifact-worker 默认产物与 provider 回归",
                ]
            )
    if heading == "一句话定位":
        return (
            "设计并实现基于 Controlled Agentic Graph Harness 的独立产物生成模块，"
            "通过 Schema-Gated Skill Graph Runtime 与 Capability Union Policy 保持可控扩展。"
        )
    if heading == "简历亮点":
        return "\n".join(
            [
                "- 将 Production Action、Style Profile 与 Skill Graph 抽象为统一配置层。",
                "- 通过 Schema-Gated Skill Graph Runtime 约束执行计划并支持局部修复。",
                "- 使用 Capability Union Policy 管理自定义 MCP 组合权限风险。",
            ]
        )
    if heading == "关键词":
        return " / ".join(plan.required_phrases)
    return f"Local repair inserted section for {heading}."


def _repair_resume_highlights(body: str) -> tuple[str, list[str]]:
    repaired_checks: list[str] = []
    lines = [line for line in body.splitlines() if line.strip()]
    bullet_lines = [line for line in lines if line.lstrip().startswith("- ")]

    filler_bullets = [
        "- 将 Production Action、Style Profile 与 Skill Graph 抽象为统一主链路。",
        "- 通过 Schema-Gated Skill Graph Runtime 收敛计划校验与节点执行解释。",
        "- 使用 Capability Union Policy 与 Verifier / Repair 收敛扩展能力风险。",
    ]
    filler_index = 0
    while len(bullet_lines) < 3 and filler_index < len(filler_bullets):
        bullet_lines.append(filler_bullets[filler_index])
        repaired_checks.append("resume highlight bullet repaired")
        filler_index += 1

    return "\n".join(bullet_lines), repaired_checks


def _repair_keywords(
    body: str,
    required_phrases: list[str],
) -> tuple[str, list[str]]:
    repaired_checks: list[str] = []
    repaired_body = body
    for phrase in required_phrases:
        if phrase not in repaired_body:
            repaired_body = f"{repaired_body} / {phrase}".strip(" /")
            repaired_checks.append(f"keyword repaired: {phrase}")
    return repaired_body, repaired_checks


def _repair_quiz_questions(body: str) -> tuple[str, list[str]]:
    repaired_checks: list[str] = []
    numbered_lines = [
        line.strip()
        for line in body.splitlines()
        if re.match(r"^\s*\d+\.\s", line)
    ]
    fallback_questions = [
        "1. [基础题] Production Action、Style Profile、Skill Graph 和 Artifact Runtime 分别负责什么？",
        "2. [进阶题] Schema-Gated Skill Graph Runtime 如何把计划限制在受控子图内？",
        "3. [挑战题] Capability Union Policy 在工作台资料和高风险 external network capability 组合时主要防什么风险？",
    ]
    fallback_index = 0
    while len(numbered_lines) < 3 and fallback_index < len(fallback_questions):
        candidate = fallback_questions[fallback_index]
        if candidate not in numbered_lines:
            numbered_lines.append(candidate)
            repaired_checks.append("quiz question repaired")
        fallback_index += 1
    return "\n".join(numbered_lines), repaired_checks


def _repair_quiz_scoring_tiers(body: str) -> tuple[str, list[str]]:
    repaired_checks: list[str] = []
    normalized_lines = [line.strip() for line in body.splitlines() if line.strip()]
    normalized_body = "\n".join(normalized_lines)
    tier_fallbacks = [
        ("基础", "- 基础：能解释统一主链路与核心对象职责。"),
        ("进阶", "- 进阶：能说明 schema gate、verifier 和 repair 的协作关系。"),
        ("挑战", "- 挑战：能识别 Capability Union Policy 的组合权限风险。"),
    ]
    for tier_keyword, fallback_line in tier_fallbacks:
        if tier_keyword not in normalized_body:
            normalized_lines.append(fallback_line)
            normalized_body = "\n".join(normalized_lines)
            repaired_checks.append(f"quiz scoring tier repaired: {tier_keyword}")
    return "\n".join(normalized_lines), repaired_checks


def _repair_wiki_overview(body: str) -> tuple[str, list[str]]:
    repaired_checks: list[str] = []
    repaired_body = body.strip()
    if "定义" not in repaired_body:
        repaired_body = f"{repaired_body} 本节补充定义说明。".strip()
        repaired_checks.append("wiki overview repaired: definition")
    if "边界" not in repaired_body:
        repaired_body = f"{repaired_body} 本节补充边界说明。".strip()
        repaired_checks.append("wiki overview repaired: boundary")
    return repaired_body, repaired_checks


def _repair_wiki_key_mechanisms(body: str) -> tuple[str, list[str]]:
    repaired_checks: list[str] = []
    repaired_body = body.strip()
    for phrase in ("Production Action", "Skill Graph", "Capability Union Policy"):
        if phrase not in repaired_body:
            repaired_body = f"{repaired_body} {phrase}".strip()
            repaired_checks.append(f"wiki mechanism repaired: {phrase}")
    return repaired_body, repaired_checks


def _repair_wiki_related_pages(body: str) -> tuple[str, list[str]]:
    repaired_checks: list[str] = []
    bullet_lines = [
        line.strip()
        for line in body.splitlines()
        if line.strip().startswith("- ")
    ]
    fallback_lines = [
        "- 施工文档：产物生成Agent独立模块施工文档",
        "- 设计文档：受控式异步产物生成Agent编排升级设计",
        "- 运行时测试：artifact-worker 默认产物与 provider 回归",
    ]
    for fallback_line in fallback_lines:
        if fallback_line not in bullet_lines:
            bullet_lines.append(fallback_line)
            repaired_checks.append("wiki related page repaired")
    return "\n".join(bullet_lines), repaired_checks
