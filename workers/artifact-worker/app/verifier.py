from __future__ import annotations

from app.models import (
    ArtifactContractCheckTrace,
    ArtifactExecutionPlan,
    ArtifactOutputContractTrace,
    ArtifactRepairSummaryTrace,
    ArtifactTaskResult,
    ArtifactVerificationResult,
)


def verify_artifact_output(
    result: ArtifactTaskResult,
    plan: ArtifactExecutionPlan,
    repaired_checks: list[str],
) -> ArtifactVerificationResult:
    contract_trace = build_output_contract_trace(result, plan, repaired_checks)
    return ArtifactVerificationResult(
        status=contract_trace.status,
        passed_checks=list(contract_trace.passed_checks),
        repaired_checks=list(contract_trace.repaired_checks),
        failed_checks=list(contract_trace.failed_checks),
        warnings=list(contract_trace.warnings),
    )


def build_output_contract_trace(
    result: ArtifactTaskResult,
    plan: ArtifactExecutionPlan,
    repaired_checks: list[str],
) -> ArtifactOutputContractTrace:
    markdown = result.result_payload.get("markdown", "")
    if not isinstance(markdown, str) or not markdown.strip():
        raise ValueError("artifact markdown must not be empty")
    if not result.result_title.strip():
        raise ValueError("artifact title must not be empty")

    outline_checks: list[ArtifactContractCheckTrace] = []
    phrase_checks: list[ArtifactContractCheckTrace] = []
    action_checks: list[ArtifactContractCheckTrace] = []
    evidence_checks: list[ArtifactContractCheckTrace] = []
    passed_checks: list[str] = []
    failed_checks: list[str] = []
    warnings: list[str] = []

    for heading in plan.outline:
        if f"### {heading}" in markdown:
            _append_trace(
                trace_bucket=outline_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label=heading,
                status="PASS",
                detail=f"section present: {heading}",
                metadata={"heading": heading},
            )
        else:
            _append_trace(
                trace_bucket=outline_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label=heading,
                status="FAIL",
                detail=f"section missing after repair: {heading}",
                metadata={"heading": heading},
            )

    for phrase in plan.required_phrases:
        if phrase in markdown:
            _append_trace(
                trace_bucket=phrase_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label=phrase,
                status="PASS",
                detail=f"required phrase present: {phrase}",
                metadata={"phrase": phrase},
            )
        else:
            _append_trace(
                trace_bucket=phrase_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label=phrase,
                status="FAIL",
                detail=f"required phrase missing after repair: {phrase}",
                metadata={"phrase": phrase},
            )

    sections = result.result_payload.get("sections", [])
    _verify_evidence_coverage(
        result,
        plan,
        passed_checks,
        failed_checks,
        evidence_checks,
    )
    _verify_quiz_structure(
        plan,
        sections,
        passed_checks,
        failed_checks,
        action_checks,
    )
    _verify_wiki_page_structure(
        plan,
        sections,
        passed_checks,
        failed_checks,
        action_checks,
    )
    _verify_resume_highlight_structure(
        plan,
        sections,
        passed_checks,
        failed_checks,
        action_checks,
    )

    status = "PASS" if not failed_checks and not warnings else "WARN"
    notes: list[str] = []
    if repaired_checks:
        notes.append("local repair completed before final contract verification")
    repair_summary = _build_repair_summary(result, repaired_checks)

    return ArtifactOutputContractTrace(
        status=status,
        outline_checks=outline_checks,
        phrase_checks=phrase_checks,
        action_checks=action_checks,
        evidence_checks=evidence_checks,
        repair_summary=repair_summary,
        passed_checks=passed_checks,
        repaired_checks=repaired_checks,
        failed_checks=failed_checks,
        warnings=warnings,
        notes=notes,
    )


def _verify_evidence_coverage(
    result: ArtifactTaskResult,
    plan: ArtifactExecutionPlan,
    passed_checks: list[str],
    failed_checks: list[str],
    evidence_checks: list[ArtifactContractCheckTrace],
) -> None:
    evidence_coverage = result.result_payload.get("evidence_coverage", {})
    if not isinstance(evidence_coverage, dict) or not evidence_coverage:
        return

    required_density = str(
        evidence_coverage.get(
            "required_citation_density",
            plan.evidence_gate.required_citation_density,
        )
    ).upper()
    coverage_status = str(evidence_coverage.get("status", "")).upper()
    if coverage_status == "PASS":
        _append_trace(
            trace_bucket=evidence_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="evidence coverage",
            status="PASS",
            detail=f"evidence coverage satisfied: {required_density}",
            metadata={
                "required_citation_density": required_density,
                "covered_section_count": evidence_coverage.get("covered_section_count", 0),
                "section_count": evidence_coverage.get("section_count", 0),
            },
        )
        return

    missing_sections = [
        str(item)
        for item in evidence_coverage.get("sections_missing_evidence", [])
        if str(item).strip()
    ]
    _append_trace(
        trace_bucket=evidence_checks,
        passed_checks=passed_checks,
        failed_checks=failed_checks,
        label="evidence coverage",
        status="FAIL",
        detail=f"evidence coverage unsatisfied: {required_density}",
        metadata={
            "required_citation_density": required_density,
            "sections_missing_evidence": missing_sections,
        },
    )
    if missing_sections:
        failed_checks.append(
            "sections missing evidence: " + ", ".join(missing_sections)
        )


def _build_repair_summary(
    result: ArtifactTaskResult,
    repaired_checks: list[str],
) -> ArtifactRepairSummaryTrace:
    local_repair_checks = [check.strip() for check in repaired_checks if check.strip()]
    node_repair_actions: list[str] = []
    affected_nodes: list[str] = []
    node_traces = result.result_payload.get("node_traces", [])
    for trace in node_traces if isinstance(node_traces, list) else []:
        if not isinstance(trace, dict):
            continue
        repair_actions = [
            str(action).strip()
            for action in trace.get("repair_actions", [])
            if str(action).strip()
        ]
        if not repair_actions:
            continue
        node_skill_key = str(trace.get("skill_key", "")).strip()
        if node_skill_key and node_skill_key not in affected_nodes:
            affected_nodes.append(node_skill_key)
        node_repair_actions.extend(repair_actions)

    category_counts: dict[str, int] = {}
    affected_sections: list[str] = []

    for check in local_repair_checks:
        category, affected_section = _categorize_local_repair(check)
        category_counts[category] = category_counts.get(category, 0) + 1
        if affected_section and affected_section not in affected_sections:
            affected_sections.append(affected_section)

    for action in node_repair_actions:
        category = _categorize_node_repair(action)
        category_counts[category] = category_counts.get(category, 0) + 1

    notes: list[str] = []
    if local_repair_checks:
        notes.append("local repair summary aggregated from repaired_checks")
    if node_repair_actions:
        notes.append("node repair summary aggregated from node_traces")

    return ArtifactRepairSummaryTrace(
        total_repair_count=len(local_repair_checks) + len(node_repair_actions),
        local_repair_count=len(local_repair_checks),
        node_repair_count=len(node_repair_actions),
        affected_sections=affected_sections,
        affected_nodes=affected_nodes,
        local_repair_checks=local_repair_checks,
        node_repair_actions=node_repair_actions,
        category_counts=category_counts,
        notes=notes,
    )


def _categorize_local_repair(repaired_check: str) -> tuple[str, str]:
    if repaired_check.startswith("missing section repaired:"):
        return "missing_section", repaired_check.split(":", 1)[1].strip()
    if repaired_check.startswith("forbidden pattern removed:"):
        return "forbidden_pattern", ""
    if repaired_check.startswith("keyword repaired:"):
        return "keyword", "关键词"
    if repaired_check.startswith("resume highlight bullet repaired"):
        return "resume_highlight_bullet", "简历亮点"
    if repaired_check.startswith("quiz question repaired"):
        return "quiz_question", "题目设计"
    if repaired_check.startswith("quiz scoring tier repaired:"):
        return "quiz_scoring_tier", "评分要点"
    if repaired_check.startswith("wiki overview repaired:"):
        return "wiki_overview", "概览"
    if repaired_check.startswith("wiki mechanism repaired:"):
        return "wiki_mechanism", "关键机制"
    if repaired_check.startswith("wiki related page repaired"):
        return "wiki_related_pages", "相关页面"
    return "local_other", ""


def _categorize_node_repair(repair_action: str) -> str:
    if repair_action.startswith("resume highlight candidate backfilled"):
        return "node_resume_highlight_candidate"
    if repair_action.startswith("resume focus point backfilled at node level:"):
        return "node_resume_focus_point"
    if repair_action.startswith("resume keyword backfilled at node level:"):
        return "node_resume_keyword"
    if repair_action.startswith("resume highlight bullet backfilled at node level"):
        return "node_resume_highlight_bullet"
    if repair_action.startswith("missing section backfilled at node level:"):
        return "node_missing_section"
    if repair_action.startswith("quiz question backfilled at node level"):
        return "node_quiz_question"
    if repair_action.startswith("quiz scoring tier backfilled at node level:"):
        return "node_quiz_scoring_tier"
    if repair_action.startswith("wiki"):
        return "node_wiki"
    if repair_action.startswith("material "):
        return "node_material_backfill"
    return "node_other"


def _verify_resume_highlight_structure(
    plan: ArtifactExecutionPlan,
    sections: object,
    passed_checks: list[str],
    failed_checks: list[str],
    action_checks: list[ArtifactContractCheckTrace],
) -> None:
    if plan.action_key != "RESUME_HIGHLIGHT":
        return

    section_list = sections if isinstance(sections, list) else []
    highlight_heading = plan.outline[1] if len(plan.outline) > 1 else ""
    highlight_section = next(
        (
            section
            for section in section_list
            if isinstance(section, dict) and section.get("heading") == highlight_heading
        ),
        None,
    )
    if highlight_section is None:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="resume highlight section",
            status="FAIL",
            detail="resume highlight section missing",
            metadata={"action_key": plan.action_key, "heading": highlight_heading},
        )
        return

    bullet_count = sum(
        1
        for line in str(highlight_section.get("body", "")).splitlines()
        if line.lstrip().startswith("- ")
    )
    if bullet_count >= 3:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="resume highlight bullet count",
            status="PASS",
            detail="resume highlight bullet count satisfied",
            metadata={"action_key": plan.action_key, "bullet_count": bullet_count},
        )
    else:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="resume highlight bullet count",
            status="FAIL",
            detail="resume highlight bullet count below minimum",
            metadata={"action_key": plan.action_key, "bullet_count": bullet_count},
        )

    focus_points = [
        point.strip()
        for point in plan.execution_spec.focus_points
        if point.strip()
    ]
    if not focus_points:
        return

    section_text_parts: list[str] = []
    for section in section_list:
        if isinstance(section, dict):
            section_text_parts.append(str(section.get("body", "")))
        else:
            body = getattr(section, "body", "")
            if isinstance(body, str):
                section_text_parts.append(body)
    section_text = "\n".join(section_text_parts)
    matched_focus_points = [
        point
        for point in focus_points
        if point in section_text
    ]
    missing_focus_points = [
        point
        for point in focus_points
        if point not in matched_focus_points
    ]
    focus_coverage_status = "PASS" if not missing_focus_points else "FAIL"
    _append_trace(
        trace_bucket=action_checks,
        passed_checks=passed_checks,
        failed_checks=failed_checks,
        label="resume focus point coverage",
        status=focus_coverage_status,
        detail=(
            "resume focus point coverage satisfied"
            if focus_coverage_status == "PASS"
            else "resume focus point coverage missing required focus points"
        ),
        metadata={
            "action_key": plan.action_key,
            "matched_focus_point_count": len(matched_focus_points),
            "focus_point_count": len(focus_points),
            "matched_focus_points": matched_focus_points,
            "missing_focus_points": missing_focus_points,
        },
    )


def _verify_quiz_structure(
    plan: ArtifactExecutionPlan,
    sections: object,
    passed_checks: list[str],
    failed_checks: list[str],
    action_checks: list[ArtifactContractCheckTrace],
) -> None:
    if plan.action_key != "QUIZ":
        return

    section_list = sections if isinstance(sections, list) else []
    question_heading = plan.outline[1] if len(plan.outline) > 1 else ""
    scoring_heading = plan.outline[3] if len(plan.outline) > 3 else ""
    question_section = next(
        (
            section
            for section in section_list
            if isinstance(section, dict) and section.get("heading") == question_heading
        ),
        None,
    )
    scoring_section = next(
        (
            section
            for section in section_list
            if isinstance(section, dict) and section.get("heading") == scoring_heading
        ),
        None,
    )
    if question_section is None:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="quiz question section",
            status="FAIL",
            detail="quiz question section missing",
            metadata={"action_key": plan.action_key, "heading": question_heading},
        )
        return
    question_count = sum(
        1
        for line in str(question_section.get("body", "")).splitlines()
        if line.lstrip().startswith(("1.", "2.", "3.", "4.", "5."))
    )
    if question_count >= 3:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="quiz question count",
            status="PASS",
            detail="quiz question count satisfied",
            metadata={"action_key": plan.action_key, "question_count": question_count},
        )
    else:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="quiz question count",
            status="FAIL",
            detail="quiz question count below minimum",
            metadata={"action_key": plan.action_key, "question_count": question_count},
        )

    if scoring_section is None:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="quiz scoring section",
            status="FAIL",
            detail="quiz scoring section missing",
            metadata={"action_key": plan.action_key, "heading": scoring_heading},
        )
        return
    scoring_body = str(scoring_section.get("body", ""))
    if all(keyword in scoring_body for keyword in ("基础", "进阶", "挑战")):
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="quiz scoring tiers",
            status="PASS",
            detail="quiz scoring tiers satisfied",
            metadata={"action_key": plan.action_key, "heading": scoring_heading},
        )
    else:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="quiz scoring tiers",
            status="FAIL",
            detail="quiz scoring tiers missing",
            metadata={"action_key": plan.action_key, "heading": scoring_heading},
        )


def _verify_wiki_page_structure(
    plan: ArtifactExecutionPlan,
    sections: object,
    passed_checks: list[str],
    failed_checks: list[str],
    action_checks: list[ArtifactContractCheckTrace],
) -> None:
    if plan.action_key != "WIKI_PAGE":
        return

    section_list = sections if isinstance(sections, list) else []
    overview_heading = plan.outline[0] if len(plan.outline) > 0 else ""
    mechanism_heading = plan.outline[1] if len(plan.outline) > 1 else ""
    related_pages_heading = plan.outline[2] if len(plan.outline) > 2 else ""

    overview_section = next(
        (
            section
            for section in section_list
            if isinstance(section, dict) and section.get("heading") == overview_heading
        ),
        None,
    )
    mechanism_section = next(
        (
            section
            for section in section_list
            if isinstance(section, dict) and section.get("heading") == mechanism_heading
        ),
        None,
    )
    related_pages_section = next(
        (
            section
            for section in section_list
            if isinstance(section, dict) and section.get("heading") == related_pages_heading
        ),
        None,
    )

    if overview_section is None:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="wiki overview definition and boundary",
            status="FAIL",
            detail="wiki overview section missing",
            metadata={"action_key": plan.action_key, "heading": overview_heading},
        )
    else:
        overview_body = str(overview_section.get("body", ""))
        if all(keyword in overview_body for keyword in ("定义", "边界")):
            _append_trace(
                trace_bucket=action_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label="wiki overview definition and boundary",
                status="PASS",
                detail="wiki overview definition and boundary satisfied",
                metadata={"action_key": plan.action_key, "heading": overview_heading},
            )
        else:
            _append_trace(
                trace_bucket=action_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label="wiki overview definition and boundary",
                status="FAIL",
                detail="wiki overview definition or boundary missing",
                metadata={"action_key": plan.action_key, "heading": overview_heading},
            )

    if mechanism_section is None:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="wiki key mechanisms",
            status="FAIL",
            detail="wiki key mechanism section missing",
            metadata={"action_key": plan.action_key, "heading": mechanism_heading},
        )
    else:
        mechanism_body = str(mechanism_section.get("body", ""))
        if all(
            phrase in mechanism_body
            for phrase in ("Production Action", "Skill Graph", "Capability Union Policy")
        ):
            _append_trace(
                trace_bucket=action_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label="wiki key mechanisms",
                status="PASS",
                detail="wiki key mechanisms satisfied",
                metadata={"action_key": plan.action_key, "heading": mechanism_heading},
            )
        else:
            _append_trace(
                trace_bucket=action_checks,
                passed_checks=passed_checks,
                failed_checks=failed_checks,
                label="wiki key mechanisms",
                status="FAIL",
                detail="wiki key mechanisms missing",
                metadata={"action_key": plan.action_key, "heading": mechanism_heading},
            )

    if related_pages_section is None:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="wiki related page count",
            status="FAIL",
            detail="wiki related pages section missing",
            metadata={"action_key": plan.action_key, "heading": related_pages_heading},
        )
        return

    related_page_count = sum(
        1
        for line in str(related_pages_section.get("body", "")).splitlines()
        if line.lstrip().startswith("- ")
    )
    if related_page_count >= 3:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="wiki related page count",
            status="PASS",
            detail="wiki related page count satisfied",
            metadata={"action_key": plan.action_key, "related_page_count": related_page_count},
        )
    else:
        _append_trace(
            trace_bucket=action_checks,
            passed_checks=passed_checks,
            failed_checks=failed_checks,
            label="wiki related page count",
            status="FAIL",
            detail="wiki related page count below minimum",
            metadata={"action_key": plan.action_key, "related_page_count": related_page_count},
        )


def _append_trace(
    *,
    trace_bucket: list[ArtifactContractCheckTrace],
    passed_checks: list[str],
    failed_checks: list[str],
    label: str,
    status: str,
    detail: str,
    metadata: dict[str, object] | None = None,
) -> None:
    trace_bucket.append(
        ArtifactContractCheckTrace(
            label=label,
            status=status,
            detail=detail,
            metadata=metadata or {},
        )
    )
    if status == "PASS":
        passed_checks.append(detail)
    else:
        failed_checks.append(detail)
