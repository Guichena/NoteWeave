from __future__ import annotations

from app.output_contract import evaluate_output_contract
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
        heading_present = f"## {heading}" in markdown or f"### {heading}" in markdown
        if heading_present:
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

    # 运行关键词写在运行信息里，不要求出现在读者看到的正文中
    runtime_notes = str(result.result_payload.get("runtime_notes", ""))
    for phrase in plan.required_phrases:
        if phrase in markdown or phrase in runtime_notes:
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
    action_checks.extend(evaluate_output_contract(result, plan))
    for check in action_checks:
        if check.status == "PASS":
            passed_checks.append(check.detail)
        elif check.status == "WARN":
            warnings.append(check.detail)
        else:
            failed_checks.append(check.detail)

    status = "FAIL" if failed_checks else ("WARN" if warnings else "PASS")
    notes: list[str] = []
    if repaired_checks:
        notes.append("typed repair completed before final contract verification")
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
