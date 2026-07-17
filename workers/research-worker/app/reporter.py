from __future__ import annotations

from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchEvidenceCard,
    ResearchPlan,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchStateLedger,
    ResearchTaskInput,
)
from app.recovery_targets import build_recovery_targets


def build_research_report_structure(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
    branch_decisions: list[ResearchBranchDecision],
    local_result: LocalVerifierResult,
    global_result: GlobalVerifierResult,
) -> dict[str, object]:
    source_titles = [item.title for item in task_input.source_scope[:3]]
    research_intent = task_input.input_payload.research_intent
    terminology = (
        "; ".join(task_input.control_pack.terminology_policy)
        or "Use workspace-level terminology consistently."
    )
    evidence_policy = (
        "; ".join(task_input.control_pack.evidence_policy)
        or "Every key finding must remain anchored to workspace evidence."
    )
    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    recovery_targets = build_recovery_targets(plan)
    resume_checkpoint = task_input.input_payload.resume_checkpoint
    valid_evidence_ids = {card.evidence_id for card in evidence_cards}
    evidence_by_id = {
        card.evidence_id: card
        for card in evidence_cards
    }
    windows_by_id = {
        window.window_id: window
        for window in read_windows
    }
    search_hits_by_id = {
        hit.hit_id: hit
        for hit in search_hits
    }
    cells_by_row_id: dict[str, list[object]] = {}
    for cell in ledger.cells:
        cells_by_row_id.setdefault(cell.row_id, []).append(cell)
    verified_rows = [
        row
        for row in ledger.rows
        if row.evidence_id in valid_evidence_ids and row.row_status == "VERIFIED"
    ]
    conflicted_rows = [
        row
        for row in ledger.rows
        if row.evidence_id in valid_evidence_ids and row.row_status == "CONFLICTED"
    ]
    guarded_rows = [
        row
        for row in ledger.rows
        if row.evidence_id in valid_evidence_ids and row.row_status not in {"VERIFIED", "CONFLICTED"}
    ]

    next_actions = [
        (
            "Continue recovery before final synthesis because verifier guardrails remain active."
            if global_result.decision == "WRITE_WITH_GUARDRAILS"
            else "Continue only if additional source expansion or clarification is needed."
        ),
        f"Terminology policy: {terminology}",
    ]
    next_actions.extend(
        f"Recovery action: {action}"
        for action in global_result.recovery_actions
    )
    counterfactual_summary = build_counterfactual_summary(
        ledger,
        branch_decisions,
        local_result,
        global_result,
        recovery_mode,
        valid_evidence_ids=valid_evidence_ids,
    )
    verified_row_summaries = [
        _build_row_summary(
            row,
            cells_by_row_id=cells_by_row_id,
            evidence_by_id=evidence_by_id,
            windows_by_id=windows_by_id,
            search_hits_by_id=search_hits_by_id,
        )
        for row in verified_rows
    ]
    conflicted_row_summaries = [
        _build_row_summary(
            row,
            cells_by_row_id=cells_by_row_id,
            evidence_by_id=evidence_by_id,
            windows_by_id=windows_by_id,
            search_hits_by_id=search_hits_by_id,
        )
        for row in conflicted_rows
    ]
    guarded_row_summaries = [
        _build_row_summary(
            row,
            cells_by_row_id=cells_by_row_id,
            evidence_by_id=evidence_by_id,
            windows_by_id=windows_by_id,
            search_hits_by_id=search_hits_by_id,
        )
        for row in guarded_rows
    ]
    evidence_ledger = verified_row_summaries + conflicted_row_summaries + guarded_row_summaries
    provenance_bindings = _collect_provenance_bindings(evidence_ledger)
    key_findings = [
        (
            "The harness completed planning, workspace search, bounded reading, "
            "evidence extraction, local verification, and global verification."
        ),
        (
            f"Research goal: {research_intent.research_goal}"
            if research_intent.research_goal
            else "Research goal: derive verifier-ready findings from the explicit user question."
        ),
        (
            f"Current workspace source scope: {', '.join(source_titles) if source_titles else 'No explicit source attached'}"
        ),
        (
            f"Runtime objects: {len(search_hits)} search hits, "
            f"{len(read_windows)} read windows, {len(evidence_cards)} evidence cards."
        ),
        (
            f"Deliverable format: {research_intent.deliverable_format}"
            if research_intent.deliverable_format
            else f"Depth tier: {research_intent.depth or 'STANDARD'}"
        ),
    ]
    source_foundation = _build_source_foundation(
        verified_row_summaries,
        evidence_ledger,
        search_hits,
        read_windows,
        provenance_bindings=provenance_bindings,
    )
    final_answer = _build_final_answer(
        plan,
        verified_row_summaries,
        conflicted_row_summaries,
        guarded_row_summaries,
        global_result,
        ledger,
        source_foundation,
    )
    executive_summary = _build_executive_summary(
        plan,
        verified_row_summaries,
        conflicted_row_summaries,
        guarded_row_summaries,
        global_result,
        source_titles,
        counterfactual_summary,
    )
    key_takeaways = _build_key_takeaways(
        verified_row_summaries,
        key_findings,
        global_result,
    )
    evidence_highlights = _build_evidence_highlights(
        verified_row_summaries,
        evidence_ledger,
    )
    uncertainty_and_risks = _build_uncertainty_and_risks(
        conflicted_row_summaries,
        guarded_row_summaries,
        global_result,
        local_result,
    )
    report_refinement_guard = _build_report_refinement_guard(
        verified_row_summaries,
        conflicted_row_summaries,
        guarded_row_summaries,
        evidence_ledger,
    )
    agent_capabilities = _build_agent_capabilities(
        plan,
        ledger,
        search_hits,
        read_windows,
        evidence_cards,
        local_result,
        global_result,
        counterfactual_summary,
    )

    return {
        "research_question": {
            "original_question": plan.normalized_question,
            "research_profile": task_input.input_payload.profile_key.strip() or "DEFAULT",
        },
        "planned_report_sections": list(plan.report_sections),
        "research_intent": {
            "research_goal": research_intent.research_goal,
            "deliverable_format": research_intent.deliverable_format,
            "constraints": list(research_intent.constraints),
            "time_range": research_intent.time_range,
            "depth": research_intent.depth or "STANDARD",
            "research_type": plan.research_type,
        },
        "intent_completion_contract": global_result.intent_completion_contract.model_dump(mode="json"),
        "research_intent_alignment": global_result.research_intent_alignment.model_dump(mode="json"),
        "final_answer": final_answer,
        "executive_summary": executive_summary,
        "key_takeaways": key_takeaways,
        "evidence_highlights": evidence_highlights,
        "uncertainty_and_risks": uncertainty_and_risks,
        "report_refinement_guard": report_refinement_guard,
        "source_foundation": source_foundation,
        "agent_capabilities": agent_capabilities,
        "key_findings": key_findings,
        "verified_findings": verified_row_summaries,
        "evidence_ledger": evidence_ledger,
        "provenance_bindings": provenance_bindings,
        "closed_loop_state": {
            "active_branch": ledger.active_branch_id,
            "branch_count": len(ledger.branches),
            "verifier_decisions_count": len(ledger.verifier_decisions),
            "verified_rows_count": ledger.verified_row_count,
            "conflicted_rows_count": ledger.conflicted_row_count,
            "requirement_ready_rows_count": ledger.requirement_ready_row_count,
            "requirement_partial_rows_count": ledger.requirement_partial_row_count,
            "research_intent_alignment_status": global_result.research_intent_alignment.status,
            "research_intent_alignment_reason": global_result.research_intent_alignment.reason_code,
            "intent_constraint_count": global_result.research_intent_alignment.total_constraint_count,
            "intent_satisfied_constraint_count": global_result.research_intent_alignment.satisfied_constraint_count,
            "intent_completion_contract": global_result.intent_completion_contract.model_dump(mode="json"),
            "intent_requirement_count": global_result.intent_completion_contract.total_requirement_count,
            "intent_satisfied_requirement_count": global_result.intent_completion_contract.satisfied_requirement_count,
            "intent_pending_requirement_count": global_result.intent_completion_contract.pending_requirement_count,
            "missing_intent_requirements": list(global_result.intent_completion_contract.missing_requirement_labels),
            "recovery_targets": recovery_targets,
            "abandon_conditions": list(plan.stop_contract.get("abandon_conditions", [])),
            "human_handoff_conditions": list(plan.stop_contract.get("human_handoff_conditions", [])),
        },
        "counterfactual_summary": counterfactual_summary,
        "conflict_and_counterfactual_review": {
            "local_verifier_status": local_result.status,
            "global_verifier_decision": global_result.decision,
            "evidence_policy": evidence_policy,
            "recovery_mode": recovery_mode or None,
            "verifier_decisions": [
                {
                    "decision_scope": verifier_decision.decision_scope,
                    "decision_type": verifier_decision.decision_type,
                    "reason_code": verifier_decision.reason_code,
                }
                for verifier_decision in ledger.verifier_decisions[:6]
            ],
            "conflicted_rows": [
                row
                for row in conflicted_row_summaries
            ],
            "branch_decisions": [
                branch_decision.model_dump(mode="json")
                for branch_decision in branch_decisions
            ],
        },
        "recovery_status": {
            "local_verifier_status": local_result.status,
            "global_verifier_decision": global_result.decision,
            "active_recovery_strategy": recovery_mode or None,
            "recovery_targets": recovery_targets,
            "guardrailed_rows": [
                row
                for row in guarded_row_summaries
            ],
            "unresolved_questions": list(ledger.unresolved_questions),
            "warnings": list(local_result.warnings),
        },
        "next_actions": next_actions,
        "resume_checkpoint": (
            resume_checkpoint.model_dump(mode="json")
            if resume_checkpoint is not None
            else None
        ),
        "recovery_mode": recovery_mode or None,
        "control_notes": list(plan.notes),
    }


def build_counterfactual_summary(
    ledger: ResearchStateLedger,
    branch_decisions: list[ResearchBranchDecision],
    local_result: LocalVerifierResult,
    global_result: GlobalVerifierResult,
    recovery_mode: str,
    *,
    valid_evidence_ids: set[str] | None = None,
) -> dict[str, object]:
    valid_ids = valid_evidence_ids
    if valid_ids is None:
        valid_ids = {
            row.evidence_id
            for row in ledger.rows
            if row.evidence_id
        }
    branch_index = {
        branch.branch_id: branch
        for branch in ledger.branches
    }
    counterfactual_branches: list[dict[str, object]] = []
    counterfactual_branch_ids: list[str] = []
    counterfactual_session_ids: list[str] = []
    active_counterfactual_branch_ids: list[str] = []
    active_counterfactual_session_ids: list[str] = []
    branch_reasons: list[str] = []
    target_evidence_ids: list[str] = []
    seen_branch_ids: set[str] = set()
    seen_session_ids: set[str] = set()
    seen_branch_reasons: set[str] = set()
    seen_target_evidence_ids: set[str] = set()

    for branch_decision in branch_decisions:
        if branch_decision.decision not in {
            "COUNTERFACTUAL_RECHECK",
            "COUNTERFACTUAL_RESOLVED",
        }:
            continue
        branch = branch_index.get(branch_decision.branch_id)
        target_ids = list(dict.fromkeys(
            evidence_id
            for evidence_id in (
                branch_decision.target_evidence_ids
                or (branch.target_evidence_ids if branch is not None else [])
            )
            if evidence_id
        ))
        branch_payload = {
            "branch_id": branch_decision.branch_id,
            "session_id": branch_decision.session_id or (branch.session_id if branch is not None else ""),
            "parent_branch_id": branch_decision.parent_branch_id or (branch.parent_branch_id if branch is not None else ""),
            "parent_session_id": branch_decision.parent_session_id or (branch.parent_session_id if branch is not None else ""),
            "branch_reason": branch_decision.branch_reason or (branch.branch_reason if branch is not None else "COUNTERFACTUAL_RECHECK"),
            "branch_status": branch_decision.branch_status or (branch.status if branch is not None else "ACTIVE_BRANCH"),
            "execution_mode": branch_decision.execution_mode or (branch.execution_mode if branch is not None else "SEQUENTIAL"),
            "sibling_branch_ids": list(branch_decision.sibling_branch_ids or (branch.sibling_branch_ids if branch is not None else [])),
            "decision": branch_decision.decision,
            "verifier_scope": branch_decision.verifier_scope,
            "hypothesis_summary": branch_decision.hypothesis_summary or (branch.hypothesis_summary if branch is not None else ""),
            "target_evidence_ids": target_ids,
            "resolution": branch_decision.resolution,
            "resolved_round": branch_decision.resolved_round,
            "result_evidence_ids": list(branch_decision.result_evidence_ids),
        }
        counterfactual_branches.append(branch_payload)
        if branch_decision.branch_id and branch_decision.branch_id not in seen_branch_ids:
            counterfactual_branch_ids.append(branch_decision.branch_id)
            seen_branch_ids.add(branch_decision.branch_id)
        session_id = str(branch_payload["session_id"] or "")
        if session_id and session_id not in seen_session_ids:
            counterfactual_session_ids.append(session_id)
            seen_session_ids.add(session_id)
        if branch_payload["branch_reason"] and branch_payload["branch_reason"] not in seen_branch_reasons:
            branch_reasons.append(str(branch_payload["branch_reason"]))
            seen_branch_reasons.add(str(branch_payload["branch_reason"]))
        for evidence_id in target_ids:
            if evidence_id not in seen_target_evidence_ids:
                target_evidence_ids.append(evidence_id)
                seen_target_evidence_ids.add(evidence_id)
        if (
            branch_decision.branch_id == ledger.active_branch_id
            or str(branch_payload["branch_status"]).upper() in {"ACTIVE_BRANCH", "ACTIVE"}
        ) and branch_decision.branch_id not in active_counterfactual_branch_ids:
            active_counterfactual_branch_ids.append(branch_decision.branch_id)
            if session_id and session_id not in active_counterfactual_session_ids:
                active_counterfactual_session_ids.append(session_id)

    conflicted_rows = [
        row
        for row in ledger.rows
        if row.evidence_id in valid_ids
        and (
            row.row_status == "CONFLICTED"
            or row.verification_status in {"COUNTERFACTUAL_REQUIRED", "COUNTERFACTUAL_RECHECK"}
        )
    ]

    return {
        "has_counterfactual_recheck": bool(counterfactual_branches) or recovery_mode == "COUNTERFACTUAL_RECHECK",
        "counterfactual_branch_count": len(counterfactual_branches),
        "conflicted_row_count": len(conflicted_rows),
        "local_verifier_status": local_result.status,
        "global_verifier_decision": global_result.decision,
        "recovery_mode": recovery_mode or None,
        "counterfactual_branch_ids": counterfactual_branch_ids,
        "counterfactual_session_ids": counterfactual_session_ids,
        "active_counterfactual_branch_ids": active_counterfactual_branch_ids,
        "active_counterfactual_session_ids": active_counterfactual_session_ids,
        "branch_reasons": branch_reasons,
        "target_evidence_ids": target_evidence_ids,
        "branches": counterfactual_branches,
    }


def write_research_report(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
    branch_decisions: list[ResearchBranchDecision],
    local_result: LocalVerifierResult,
    global_result: GlobalVerifierResult,
    report_structure: dict[str, object] | None = None,
) -> str:
    structure = report_structure or build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        evidence_cards,
        branch_decisions,
        local_result,
        global_result,
    )
    research_question = dict(structure.get("research_question", {}))
    planned_report_sections = list(structure.get("planned_report_sections", []))
    research_intent = dict(structure.get("research_intent", {}))
    key_findings = list(structure.get("key_findings", []))
    final_answer = dict(structure.get("final_answer", {}))
    executive_summary = list(structure.get("executive_summary", []))
    key_takeaways = list(structure.get("key_takeaways", []))
    evidence_highlights = list(structure.get("evidence_highlights", []))
    uncertainty_and_risks = list(structure.get("uncertainty_and_risks", []))
    report_refinement_guard = dict(structure.get("report_refinement_guard", {}))
    citation_verification = dict(structure.get("citation_verification", {}))
    report_revision_loop = dict(structure.get("report_revision_loop", {}))
    source_foundation = dict(structure.get("source_foundation", {}))
    agent_capabilities = list(structure.get("agent_capabilities", []))
    intent_completion_contract = dict(structure.get("intent_completion_contract", {}))
    intent_alignment = dict(structure.get("research_intent_alignment", {}))
    verified_findings = list(structure.get("verified_findings", []))
    evidence_ledger = list(structure.get("evidence_ledger", []))
    closed_loop_state = dict(structure.get("closed_loop_state", {}))
    conflict_review = dict(structure.get("conflict_and_counterfactual_review", {}))
    conflicted_rows = list(conflict_review.get("conflicted_rows", []))
    verifier_decisions = list(conflict_review.get("verifier_decisions", []))
    recovery_status = dict(structure.get("recovery_status", {}))
    recovery_targets = dict(closed_loop_state.get("recovery_targets", {}))
    guarded_rows = list(recovery_status.get("guardrailed_rows", []))
    next_actions = list(structure.get("next_actions", []))
    control_notes = list(structure.get("control_notes", []))
    resume_checkpoint = structure.get("resume_checkpoint")
    recovery_mode = structure.get("recovery_mode")

    lines = [
        f"# {plan.normalized_question}",
        "",
        "## Research Deliverable Summary",
        f"- Status: {final_answer.get('answer_status', _answer_status(global_result.decision))}",
        f"- Confidence: {final_answer.get('confidence_label', _confidence_label(global_result.decision, len(verified_findings), len(conflicted_rows), source_foundation))}",
        f"- Coverage: {final_answer.get('coverage_label', _coverage_label(global_result, verified_findings))}",
        f"- Source basis: {final_answer.get('source_basis', _source_basis_label(verified_findings, source_foundation))}",
        "",
        str(final_answer.get('answer_text') or "A stable research deliverable is not yet ready; continue with guarded recovery and source expansion."),
        "",
        "## Deliverable Highlights",
    ]
    lines.extend(f"- {item}" for item in executive_summary)
    lines.extend(["", "## Source Basis"])
    if source_foundation:
        lines.append(f"- Source foundation: {source_foundation.get('primary_quality', 'UNKNOWN')}")
        lines.append(f"- Quality mix: {source_foundation.get('quality_mix_label', 'No quality mix available')}")
        lines.append(
            f"- Read strategy mix: {source_foundation.get('read_strategy_mix_label', 'No read strategy mix available')}"
        )
        lines.append(f"- Deep read windows: {source_foundation.get('deep_read_count', 0)}")
        if int(source_foundation.get("external_search_hit_count", 0)) > 0 or int(source_foundation.get("external_window_count", 0)) > 0:
            lines.append(
                f"- Orchestration foundation: {source_foundation.get('orchestration_foundation_label', 'No orchestration foundation available')}"
            )
            lines.append(
                f"- Provider mix: {source_foundation.get('provider_mix_label', 'No provider mix available')}"
            )
            lines.append(
                f"- Transport mix: {source_foundation.get('transport_mix_label', 'No transport mix available')}"
            )
        if int(source_foundation.get("external_window_count", 0)) > 0:
            lines.append(
                f"- Fetch foundation: {source_foundation.get('fetch_foundation_label', 'No fetch foundation available')}"
            )
            lines.append(
                f"- Fetch mix: {source_foundation.get('fetch_mix_label', 'No fetch mix available')}"
            )
            lines.append(
                f"- Archive readiness: {source_foundation.get('archive_readiness_label', 'No archive readiness data available')}"
            )
            lines.append(
                f"- Transport complexity: {source_foundation.get('transport_complexity_label', 'No transport complexity data available')}"
            )
            lines.append(f"- Fetched external windows: {source_foundation.get('fetched_window_count', 0)}")
            lines.append(f"- Fallback external windows: {source_foundation.get('fallback_window_count', 0)}")
            lines.append(f"- Archive-ready external windows: {source_foundation.get('archive_ready_window_count', 0)}")
            lines.append(f"- Multi-transport external windows: {source_foundation.get('multi_transport_window_count', 0)}")
    if verified_findings:
        source_basis_titles = list(dict.fromkeys(
            str(row.get("source_title", "")).strip()
            for row in verified_findings
            if str(row.get("source_title", "")).strip()
        ))
        lines.extend(f"- {title}" for title in source_basis_titles[:6])
    else:
        lines.append("- No verifier-approved source basis is ready yet.")
    lines.extend(["", "## Key Takeaways"])
    lines.extend(f"- {item}" for item in key_takeaways)
    lines.extend(["", "## Evidence Highlights"])
    for row in evidence_highlights:
        lines.extend(_render_row_markdown(row))
    if not evidence_highlights:
        lines.append("- No evidence highlight is ready yet.")
    lines.extend(["", "## Agent Capabilities"])
    for capability in agent_capabilities:
        lines.append(
            f"- {capability.get('title', capability.get('capability_key', 'Capability'))}: "
            f"{capability.get('status', '')}"
        )
        if capability.get("summary"):
            lines.append(f"  summary: {capability.get('summary')}")
        if capability.get("detail"):
            lines.append(f"  detail: {capability.get('detail')}")
    lines.extend([
        "",
        "## Research Question",
        f"- Original question: {research_question.get('original_question', plan.normalized_question)}",
        f"- Research profile: {research_question.get('research_profile', task_input.input_payload.profile_key.strip() or 'DEFAULT')}",
        (
            f"- Resume checkpoint: {resume_checkpoint.get('source_research_run_id')}#"
            f"{resume_checkpoint.get('checkpoint_no')}"
            if isinstance(resume_checkpoint, dict) and resume_checkpoint
            else "- Resume checkpoint: none"
        ),
        "",
        "## Planned Report Sections",
    ])
    if planned_report_sections:
        lines.extend(f"- {section}" for section in planned_report_sections)
    else:
        lines.append("- No planned report section template was recorded.")
    lines.extend([
        "",
        "## Research Intent",
        f"- Research goal: {research_intent.get('research_goal') or 'No explicit research goal provided.'}",
        f"- Deliverable format: {research_intent.get('deliverable_format') or 'Default research report'}",
        f"- Research type: {research_intent.get('research_type') or plan.research_type or 'AUTO'}",
        f"- Time range: {research_intent.get('time_range') or 'No explicit time range'}",
        f"- Depth: {research_intent.get('depth') or task_input.input_payload.research_intent.depth or 'STANDARD'}",
        (
            "- Constraints: " + "; ".join(
                str(item)
                for item in list(research_intent.get("constraints", []))
                if str(item).strip()
            )
            if list(research_intent.get("constraints", []))
            else "- Constraints: none"
        ),
        "",
        "## Research Intent Alignment",
        f"- Status: {intent_alignment.get('status') or global_result.research_intent_alignment.status}",
        f"- Reason: {intent_alignment.get('reason_code') or global_result.research_intent_alignment.reason_code}",
        (
            f"- Constraint coverage: "
            f"{intent_alignment.get('satisfied_constraint_count', global_result.research_intent_alignment.satisfied_constraint_count)}/"
            f"{intent_alignment.get('total_constraint_count', global_result.research_intent_alignment.total_constraint_count)}"
        ),
        "",
    ])
    for item in list(intent_alignment.get("covered_requirements", [])):
        lines.append(f"- Intent covered: {item}")
    for item in list(intent_alignment.get("missing_requirements", [])):
        lines.append(f"- Intent gap: {item}")
    lines.extend(
        [
            "",
            "## Research Intent Completion Contract",
            (
                f"- Requirement completion: "
                f"{intent_completion_contract.get('satisfied_requirement_count', global_result.intent_completion_contract.satisfied_requirement_count)}/"
                f"{intent_completion_contract.get('total_requirement_count', global_result.intent_completion_contract.total_requirement_count)}"
            ),
            f"- Pending requirements: {intent_completion_contract.get('pending_requirement_count', global_result.intent_completion_contract.pending_requirement_count)}",
        ]
    )
    for requirement in list(intent_completion_contract.get("requirements", [])):
        requirement_label = str(requirement.get("label", "")).strip() or str(requirement.get("requirement_id", "requirement"))
        requirement_status = str(requirement.get("status", "")).strip() or "WARN"
        coverage_note = str(requirement.get("coverage_note", "")).strip()
        missing_reason = str(requirement.get("missing_reason", "")).strip()
        detail = coverage_note or missing_reason or "No additional detail recorded."
        lines.append(f"- [{requirement_status}] {requirement_label}: {detail}")
    lines.append("")
    lines.append("## Key Findings")
    lines.extend(f"- {finding}" for finding in key_findings)
    lines.extend(["", "## Verified Findings"])

    for row in verified_findings:
        lines.extend(_render_row_markdown(row))

    if not verified_findings:
        lines.append("- No verifier-approved finding is ready for direct synthesis.")

    lines.extend(["", "## Evidence Ledger"])
    for row in evidence_ledger:
        lines.extend(_render_row_markdown(row))

    if not evidence_ledger:
        lines.append("- No evidence row was built for this run.")

    lines.extend(
        [
            "",
            "## Closed-Loop State",
            f"- Active branch: {closed_loop_state.get('active_branch', ledger.active_branch_id)}",
            f"- Branch count: {closed_loop_state.get('branch_count', len(ledger.branches))}",
            f"- Verifier decisions: {closed_loop_state.get('verifier_decisions_count', len(ledger.verifier_decisions))}",
            f"- Verified rows: {closed_loop_state.get('verified_rows_count', ledger.verified_row_count)}",
            f"- Conflicted rows: {closed_loop_state.get('conflicted_rows_count', ledger.conflicted_row_count)}",
            f"- Requirement-ready rows: {closed_loop_state.get('requirement_ready_rows_count', ledger.requirement_ready_row_count)}",
            f"- Requirement-partial rows: {closed_loop_state.get('requirement_partial_rows_count', ledger.requirement_partial_row_count)}",
        ]
    )
    if recovery_targets:
        labels = ", ".join(str(item) for item in list(recovery_targets.get("requirement_labels", []))[:3])
        columns = ", ".join(str(item) for item in list(recovery_targets.get("target_columns", []))[:4])
        queries = ", ".join(str(item) for item in list(recovery_targets.get("target_queries", []))[:2])
        sources = ", ".join(str(item) for item in list(recovery_targets.get("target_sources", []))[:3])
        if labels:
            lines.append(f"- Recovery target requirements: {labels}")
        if columns:
            lines.append(f"- Recovery target columns: {columns}")
        if queries:
            lines.append(f"- Recovery target queries: {queries}")
        if sources:
            lines.append(f"- Recovery target sources: {sources}")
    abandon_conditions = list(closed_loop_state.get("abandon_conditions", []))
    handoff_conditions = list(closed_loop_state.get("human_handoff_conditions", []))
    if abandon_conditions:
        lines.append("- Abandon conditions: " + "; ".join(str(item) for item in abandon_conditions))
    if handoff_conditions:
        lines.append("- Human handoff conditions: " + "; ".join(str(item) for item in handoff_conditions))
    lines.extend(
        [
            "",
            "## Conflicts And Uncertainty",
            f"- Local verifier status: {conflict_review.get('local_verifier_status', local_result.status)}",
            f"- Global verifier decision: {conflict_review.get('global_verifier_decision', global_result.decision)}",
            f"- Evidence policy: {conflict_review.get('evidence_policy', '')}",
        ]
    )

    if recovery_mode:
        lines.append(f"- Recovery mode: {recovery_mode}")

    for verifier_decision in verifier_decisions:
        lines.append(
            "- Verifier decision: "
            f"{verifier_decision.get('decision_scope', '')}/"
            f"{verifier_decision.get('decision_type', '')}/"
            f"{verifier_decision.get('reason_code', '')}"
        )

    lines.extend(["", "## Conflict And Counterfactual Review"])
    if conflicted_rows:
        for row in conflicted_rows:
            lines.extend(
                [
                    f"- Conflict source: {row.get('source_title', '')}",
                    f"  evidence: {row.get('evidence_id', '')}",
                    f"  claim: {row.get('claim_text', '')}",
                    f"  conflict_score: {_format_score(row.get('conflict_score'))}",
                    f"  verifier_note: {row.get('verifier_note', '')}",
                ]
            )
    else:
        lines.append("- No conflict row is currently blocking synthesis.")

    for branch_decision in list(conflict_review.get("branch_decisions", [])):
        lines.append(
            f"- Branch decision: {branch_decision.get('decision', '')} / {branch_decision.get('branch_reason', '')}"
        )

    lines.extend(["", "## Recovery Status"])
    lines.append(f"- Local verifier status: {recovery_status.get('local_verifier_status', local_result.status)}")
    lines.append(f"- Global verifier decision: {recovery_status.get('global_verifier_decision', global_result.decision)}")
    if recovery_status.get("active_recovery_strategy"):
        lines.append(f"- Active recovery strategy: {recovery_status.get('active_recovery_strategy')}")
    if guarded_rows:
        for row in guarded_rows:
            lines.extend(
                [
                    f"- Guardrailed row: {row.get('source_title', '')}",
                    f"  row_status: {row.get('row_status', '')}",
                    f"  repair_hint: {row.get('repair_hint') or 'No explicit repair hint recorded.'}",
                ]
            )

    for question in list(recovery_status.get("unresolved_questions", [])):
        lines.append(f"- Unresolved: {question}")

    for warning in list(recovery_status.get("warnings", [])):
        lines.append(f"- Warning: {warning}")

    lines.extend(["", "## Open Verification Notes"])
    lines.extend(f"- {item}" for item in uncertainty_and_risks)

    lines.extend(["", "## Report Refinement Guard"])
    lines.append(f"- Status: {report_refinement_guard.get('status', 'PASS')}")
    for check in list(report_refinement_guard.get("checks", [])):
        if isinstance(check, dict):
            lines.append(
                f"- {check.get('check', '')}: {check.get('status', '')}"
                + (f" ({check.get('detail')})" if check.get("detail") else "")
            )

    lines.extend(["", "## Citation Verification"])
    lines.append(f"- Status: {citation_verification.get('status', 'NOT_RUN')}")
    lines.append(
        f"- Findings passed: {citation_verification.get('passed_finding_count', 0)}/"
        f"{citation_verification.get('finding_count', 0)}"
    )
    lines.append(f"- Association accuracy: {citation_verification.get('association_accuracy', 0.0)}")
    lines.append(f"- Support accuracy: {citation_verification.get('support_accuracy', 0.0)}")
    lines.append(f"- Section revisions: {report_revision_loop.get('revision_count', 0)}")

    lines.extend(["", "## Next Actions"])
    lines.extend(f"- {action}" for action in next_actions)

    if control_notes:
        lines.extend(["", "## Control Notes"])
        for note in control_notes:
            lines.append(f"- {note}")

    return "\n".join(lines).strip() + "\n"


def build_research_artifact_candidate(
    *,
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    report_structure: dict[str, object],
    report_markdown: str,
    citations: list[dict[str, object]],
    generated_ref_id: str,
    resume_context_summary: dict[str, object] | None = None,
) -> dict[str, object]:
    final_answer = dict(report_structure.get("final_answer", {}))
    source_foundation = dict(report_structure.get("source_foundation", {}))
    research_question = dict(report_structure.get("research_question", {}))
    closed_loop_state = dict(report_structure.get("closed_loop_state", {}))
    provenance_bindings = list(report_structure.get("provenance_bindings", []))
    return {
        "artifact_type": "DEEP_RESEARCH_REPORT",
        "artifact_version": "v1",
        "title": task_input.input_payload.question.strip()[:120] or "Research Report",
        "question": str(
            research_question.get("original_question")
            or plan.normalized_question
            or task_input.input_payload.question
        ).strip(),
        "generated_by": "research_agent",
        "generated_ref_type": "RESEARCH_RUN",
        "generated_ref_id": generated_ref_id,
        "answer_status": final_answer.get("answer_status", ""),
        "confidence_label": final_answer.get("confidence_label", ""),
        "coverage_label": final_answer.get("coverage_label", ""),
        "source_basis": final_answer.get("source_basis", ""),
        "answer_text": final_answer.get("answer_text", ""),
        "content_markdown": report_markdown,
        "report_structure": report_structure,
        "source_foundation": source_foundation,
        "provenance_bindings": provenance_bindings,
        "research_intent": task_input.input_payload.research_intent.model_dump(mode="json"),
        "closed_loop_state": closed_loop_state,
        "resume_context_summary": resume_context_summary,
        "citation_count": len(citations),
        "citations": citations,
    }


def _build_row_summary(
    row: object,
    *,
    cells_by_row_id: dict[str, list[object]],
    evidence_by_id: dict[str, ResearchEvidenceCard],
    windows_by_id: dict[str, ResearchReadWindow],
    search_hits_by_id: dict[str, ResearchSearchHit],
) -> dict[str, object]:
    evidence_card = evidence_by_id.get(getattr(row, "evidence_id", ""))
    window = windows_by_id.get(evidence_card.window_id) if evidence_card is not None else None
    search_hit = search_hits_by_id.get(window.hit_id) if window is not None else None
    cell_refs = _build_cell_refs(
        cells_by_row_id.get(getattr(row, "row_id", ""), []),
        row=row,
    )
    source_ref = _build_source_ref(row, window, search_hit)
    provenance = {
        "source_ref": source_ref,
        "window_ref": _build_window_ref(window),
        "search_ref": _build_search_ref(search_hit),
        "evidence_ref": {
            "evidence_id": getattr(row, "evidence_id", ""),
            "relation_type": getattr(row, "relation_type", ""),
            "support_score": getattr(row, "support_score", 0.0),
            "conflict_score": getattr(row, "conflict_score", 0.0),
            "window_id": evidence_card.window_id if evidence_card is not None else "",
            "quote_start": evidence_card.quote_start if evidence_card is not None else -1,
            "quote_end": evidence_card.quote_end if evidence_card is not None else -1,
            "snapshot_key": evidence_card.snapshot_key if evidence_card is not None else "",
            "content_sha256": evidence_card.content_sha256 if evidence_card is not None else "",
        },
        "row_ref": {
            "row_id": getattr(row, "row_id", ""),
            "row_status": getattr(row, "row_status", ""),
            "verification_status": getattr(row, "verification_status", ""),
            "branch_id": getattr(row, "branch_id", ""),
            "cell_ids": [item["cell_id"] for item in cell_refs],
        },
        "cell_refs": cell_refs,
        "requirement_ref": {
            "target_requirement_ids": list(getattr(window, "target_requirement_ids", [])) if window is not None else [],
            "target_requirement_labels": list(getattr(window, "target_requirement_labels", [])) if window is not None else [],
            "target_columns": list(getattr(window, "target_columns", [])) if window is not None else [],
            "matched_requirement_ids": list(getattr(row, "matched_requirement_ids", [])),
            "ready_requirement_ids": list(getattr(row, "ready_requirement_ids", [])),
        },
    }
    return {
        "row_id": row.row_id,
        "source_id": row.source_id,
        "source_title": row.source_title,
        "source_domain": getattr(row, "source_domain", ""),
        "source_quality": getattr(row, "source_quality", "GENERAL_WEB"),
        "read_strategy": getattr(row, "read_strategy", "BALANCED_READ"),
        "search_query": row.search_query,
        "read_focus": row.read_focus,
        "evidence_id": row.evidence_id,
        "claim_text": row.claim_text,
        "quote_text": row.quote_text,
        "evidence_excerpt": row.evidence_excerpt,
        "relation_type": row.relation_type,
        "support_score": row.support_score,
        "conflict_score": row.conflict_score,
        "support_level": row.support_level,
        "row_status": row.row_status,
        "verifier_note": row.verifier_note,
        "repair_hint": row.repair_hint,
        "window_id": window.window_id if window is not None else "",
        "cell_ids": provenance["row_ref"]["cell_ids"],
        "query_family": window.query_family if window is not None else "direct",
        "target_requirement_ids": provenance["requirement_ref"]["target_requirement_ids"],
        "target_requirement_labels": provenance["requirement_ref"]["target_requirement_labels"],
        "target_columns": provenance["requirement_ref"]["target_columns"],
        "matched_requirement_ids": provenance["requirement_ref"]["matched_requirement_ids"],
        "ready_requirement_ids": provenance["requirement_ref"]["ready_requirement_ids"],
        "source_ref": source_ref,
        "provenance": provenance,
    }


def _build_final_answer(
    plan: ResearchPlan,
    verified_findings: list[dict[str, object]],
    conflicted_rows: list[dict[str, object]],
    guarded_rows: list[dict[str, object]],
    global_result: GlobalVerifierResult,
    ledger: ResearchStateLedger,
    source_foundation: dict[str, object],
) -> dict[str, object]:
    answer_text = ""
    top_claims = [
        str(row.get("claim_text", "")).strip()
        for row in verified_findings[:3]
        if str(row.get("claim_text", "")).strip()
    ]
    if top_claims:
        answer_text = "Based on verifier-approved evidence, the research indicates: " + "; ".join(top_claims)
    elif conflicted_rows:
        answer_text = (
            "A stable final conclusion is not yet ready because the current evidence contains "
            f"{len(conflicted_rows)} conflicted finding(s) that still require counterfactual review."
        )
    elif guarded_rows:
        answer_text = (
            "The research has enough material to produce a guarded answer, but some findings still "
            "need repair before they should be treated as fully verified."
        )
    else:
        answer_text = (
            f"The research run for '{plan.normalized_question}' completed its current loop, but it did not "
            "yet produce verifier-approved findings strong enough for a direct final answer."
        )
    return {
        "answer_text": answer_text,
        "answer_status": _answer_status(global_result.decision),
        "confidence_label": _confidence_label(
            global_result.decision,
            len(verified_findings),
            len(conflicted_rows),
            source_foundation,
        ),
        "coverage_label": _coverage_label(global_result, verified_findings),
        "source_basis": _source_basis_label(verified_findings, source_foundation),
        "ledger_row_count": len(ledger.rows),
    }


def _build_executive_summary(
    plan: ResearchPlan,
    verified_findings: list[dict[str, object]],
    conflicted_rows: list[dict[str, object]],
    guarded_rows: list[dict[str, object]],
    global_result: GlobalVerifierResult,
    source_titles: list[str],
    counterfactual_summary: dict[str, object],
) -> list[str]:
    summary = [
        (
            f"The run is currently { _answer_status(global_result.decision) } with "
            f"{len(verified_findings)} verifier-approved finding(s)."
        ),
        (
            f"Primary research scope: {', '.join(source_titles) if source_titles else 'question-driven discovery with runtime search'}."
        ),
        (
            f"Question focus: {plan.normalized_question}"
        ),
    ]
    if conflicted_rows:
        summary.append(
            f"{len(conflicted_rows)} finding(s) remain conflicted and continue to constrain answer confidence."
        )
    elif guarded_rows:
        summary.append(
            f"{len(guarded_rows)} guarded finding(s) remain visible for follow-up repair, but they no longer block a first answer."
        )
    if counterfactual_summary.get("has_counterfactual_recheck"):
        summary.append(
            f"Counterfactual recheck was activated on {counterfactual_summary.get('counterfactual_branch_count', 0)} branch(es) to correct path drift."
        )
    return summary


def _build_key_takeaways(
    verified_findings: list[dict[str, object]],
    key_findings: list[str],
    global_result: GlobalVerifierResult,
) -> list[str]:
    takeaways = [
        str(row.get("claim_text", "")).strip()
        for row in verified_findings[:4]
        if str(row.get("claim_text", "")).strip()
    ]
    if takeaways:
        return takeaways
    fallback = [item for item in key_findings if item.strip()]
    if fallback:
        return fallback[:4]
    return [global_result.summary or "No concise takeaway is ready yet."]


def _build_evidence_highlights(
    verified_findings: list[dict[str, object]],
    evidence_ledger: list[dict[str, object]],
) -> list[dict[str, object]]:
    if verified_findings:
        return verified_findings[:3]
    return evidence_ledger[:3]


def _build_uncertainty_and_risks(
    conflicted_rows: list[dict[str, object]],
    guarded_rows: list[dict[str, object]],
    global_result: GlobalVerifierResult,
    local_result: LocalVerifierResult,
) -> list[str]:
    items: list[str] = []
    if conflicted_rows:
        items.append(
            f"{len(conflicted_rows)} conflicted finding(s) are still unresolved and can change the final answer."
        )
    if guarded_rows:
        items.append(
            f"{len(guarded_rows)} guardrailed finding(s) still need repair hints or additional evidence before full promotion."
        )
    for warning in list(local_result.warnings)[:3]:
        warning_text = str(warning).strip()
        if warning_text:
            items.append(warning_text)
    for action in list(global_result.recovery_actions)[:3]:
        action_text = str(action).strip()
        if action_text:
            items.append(f"Recommended follow-up: {action_text}")
    if not items:
        items.append("No major blocking risk is currently surfaced by the verifier pipeline.")
    return items


def _build_report_refinement_guard(
    verified_findings: list[dict[str, object]],
    conflicted_rows: list[dict[str, object]],
    guarded_rows: list[dict[str, object]],
    evidence_ledger: list[dict[str, object]],
) -> dict[str, object]:
    checks: list[dict[str, object]] = []
    checks.append(
        {
            "check": "conflicts_are_separated",
            "status": "WARN" if conflicted_rows else "PASS",
            "detail": f"{len(conflicted_rows)} conflicted row(s) require explicit uncertainty" if conflicted_rows else "no conflicted rows",
        }
    )
    checks.append(
        {
            "check": "unverified_rows_are_guarded",
            "status": "WARN" if guarded_rows else "PASS",
            "detail": f"{len(guarded_rows)} guarded row(s) must not be written as final facts" if guarded_rows else "no guarded rows",
        }
    )
    dominant_source = _dominant_report_source(verified_findings)
    checks.append(
        {
            "check": "single_source_dominance",
            "status": "WARN" if dominant_source is not None else "PASS",
            "detail": dominant_source or "verified findings are not dominated by one source",
        }
    )
    checks.append(
        {
            "check": "evidence_ledger_present",
            "status": "PASS" if evidence_ledger else "WARN",
            "detail": f"{len(evidence_ledger)} ledger row(s) available",
        }
    )
    return {
        "status": "WARN" if any(check["status"] == "WARN" for check in checks) else "PASS",
        "checks": checks,
    }


def _dominant_report_source(verified_findings: list[dict[str, object]]) -> str | None:
    if len(verified_findings) < 3:
        return None
    counts: dict[str, int] = {}
    for row in verified_findings:
        source_id = str(row.get("source_id") or row.get("source_title") or "unknown-source").strip()
        counts[source_id] = counts.get(source_id, 0) + 1
    source_id, count = max(counts.items(), key=lambda item: item[1])
    if count / len(verified_findings) >= 0.8:
        return f"{source_id} supplies {count}/{len(verified_findings)} verified finding(s)"
    return None


def _build_agent_capabilities(
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
    local_result: LocalVerifierResult,
    global_result: GlobalVerifierResult,
    counterfactual_summary: dict[str, object],
) -> list[dict[str, object]]:
    return [
        {
            "capability_key": "research_harness",
            "title": "Closed-Loop Research Harness",
            "status": "COMPLETE" if global_result.decision in {"READY_TO_WRITE", "WRITE_WITH_GUARDRAILS"} else "ACTIVE",
            "summary": "The run completed a controlled loop of planning, search, bounded reading, extraction, verification, and report synthesis.",
            "detail": (
                f"{len(search_hits)} search hits -> {len(read_windows)} read windows -> "
                f"{len(evidence_cards)} evidence cards."
            ),
        },
        {
            "capability_key": "table_as_state",
            "title": "Table-as-State",
            "status": "TRACKING",
            "summary": "Research state is stored as structured ledger rows instead of free-form notes, so coverage and drift stay inspectable.",
            "detail": (
                f"{len(ledger.rows)} rows, {ledger.verified_row_count} verified, "
                f"{ledger.conflicted_row_count} conflicted, {ledger.requirement_partial_row_count} partial."
            ),
        },
        {
            "capability_key": "dual_verifier",
            "title": "Dual Verifier",
            "status": _answer_status(global_result.decision),
            "summary": "Local verification checks evidence quality, while global verification decides whether synthesis may proceed.",
            "detail": f"local={local_result.status} · global={global_result.decision}",
        },
        {
            "capability_key": "counterfactual_branch",
            "title": "反证分支 / Counterfactual Branch",
            "status": "USED" if counterfactual_summary.get("has_counterfactual_recheck") else "READY",
            "summary": (
                "The agent can open a counterfactual branch to challenge current evidence and correct path drift."
            ),
            "detail": (
                f"{counterfactual_summary.get('counterfactual_branch_count', 0)} branch(es) activated; "
                f"active mode={counterfactual_summary.get('recovery_mode') or 'NONE'}."
            ),
        },
        {
            "capability_key": "intent_contract",
            "title": "Intent-Aware Completion Contract",
            "status": global_result.research_intent_alignment.status,
            "summary": "The run keeps the answer aligned with explicit user intent instead of optimizing only for retrieval volume.",
            "detail": (
                f"requirements={global_result.intent_completion_contract.satisfied_requirement_count}/"
                f"{global_result.intent_completion_contract.total_requirement_count} · "
                f"depth={plan.stop_contract.get('depth', 'STANDARD')}"
            ),
        },
    ]


def _build_source_foundation(
    verified_findings: list[dict[str, object]],
    evidence_ledger: list[dict[str, object]],
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    *,
    provenance_bindings: list[dict[str, object]],
) -> dict[str, object]:
    rows = verified_findings or evidence_ledger
    quality_counts: dict[str, int] = {}
    strategy_counts: dict[str, int] = {}
    for row in rows:
        quality = str(row.get("source_quality", "")).strip() or "GENERAL_WEB"
        strategy = str(row.get("read_strategy", "")).strip() or "BALANCED_READ"
        quality_counts[quality] = quality_counts.get(quality, 0) + 1
        strategy_counts[strategy] = strategy_counts.get(strategy, 0) + 1

    provider_resolution_counts: dict[str, int] = {}
    provider_attempt_chain_counts: dict[str, int] = {}
    fallback_provider_hit_count = 0
    external_search_hit_count = 0
    for hit in search_hits:
        if hit.adapter != "external" and not hit.url.strip():
            continue
        external_search_hit_count += 1
        provider_resolution = str(hit.provider_resolution or hit.provider or "").strip() or "UNKNOWN"
        provider_resolution_counts[provider_resolution] = provider_resolution_counts.get(provider_resolution, 0) + 1
        provider_chain = _chain_label(hit.provider_attempts)
        provider_attempt_chain_counts[provider_chain] = provider_attempt_chain_counts.get(provider_chain, 0) + 1
        if len([item for item in hit.provider_attempts if str(item).strip()]) > 1:
            fallback_provider_hit_count += 1

    fetch_status_counts: dict[str, int] = {}
    fetch_method_counts: dict[str, int] = {}
    transport_resolution_counts: dict[str, int] = {}
    transport_chain_counts: dict[str, int] = {}
    external_window_count = 0
    fetched_window_count = 0
    fallback_window_count = 0
    fallback_transport_window_count = 0
    archive_ready_window_count = 0
    archive_unready_window_count = 0
    multi_transport_window_count = 0
    for window in read_windows:
        if window.adapter != "external_url" and not window.url.strip():
            continue
        external_window_count += 1
        fetch_status = str(window.fetch_status or "").strip() or "UNKNOWN"
        fetch_method = str(window.fetch_method or "").strip() or "UNKNOWN"
        transport_resolution = str(window.transport_resolution or "").strip() or fetch_method
        transport_chain = _chain_label(window.transport_chain)
        fetch_status_counts[fetch_status] = fetch_status_counts.get(fetch_status, 0) + 1
        fetch_method_counts[fetch_method] = fetch_method_counts.get(fetch_method, 0) + 1
        transport_resolution_counts[transport_resolution] = transport_resolution_counts.get(transport_resolution, 0) + 1
        transport_chain_counts[transport_chain] = transport_chain_counts.get(transport_chain, 0) + 1
        if fetch_status == "FETCHED" or window.content_origin == "FETCHED_SNAPSHOT":
            fetched_window_count += 1
        if fetch_status == "FALLBACK_USED" or window.content_origin == "SEARCH_SNIPPET_FALLBACK":
            fallback_window_count += 1
        if window.snapshot_archive_ready:
            archive_ready_window_count += 1
        else:
            archive_unready_window_count += 1
        if window.transport_attempt_count > 1 or len([item for item in window.transport_chain if str(item).strip()]) > 1:
            multi_transport_window_count += 1
            fallback_transport_window_count += 1

    primary_quality = max(quality_counts, key=quality_counts.get) if quality_counts else "UNKNOWN"
    deep_read_count = sum(
        count
        for strategy, count in strategy_counts.items()
        if strategy in {"DEEP_EVIDENCE_READ", "COUNTERFACTUAL_DEEP_READ"}
    )
    return {
        "primary_quality": primary_quality,
        "source_refs": _build_source_refs(provenance_bindings),
        "quality_counts": quality_counts,
        "read_strategy_counts": strategy_counts,
        "deep_read_count": deep_read_count,
        "fetch_status_counts": fetch_status_counts,
        "fetch_method_counts": fetch_method_counts,
        "provider_resolution_counts": provider_resolution_counts,
        "provider_attempt_chain_counts": provider_attempt_chain_counts,
        "transport_resolution_counts": transport_resolution_counts,
        "transport_chain_counts": transport_chain_counts,
        "external_search_hit_count": external_search_hit_count,
        "external_window_count": external_window_count,
        "fetched_window_count": fetched_window_count,
        "fallback_window_count": fallback_window_count,
        "fallback_provider_hit_count": fallback_provider_hit_count,
        "fallback_transport_window_count": fallback_transport_window_count,
        "archive_ready_window_count": archive_ready_window_count,
        "archive_unready_window_count": archive_unready_window_count,
        "multi_transport_window_count": multi_transport_window_count,
        "quality_mix_label": _mix_label(quality_counts),
        "read_strategy_mix_label": _mix_label(strategy_counts),
        "fetch_mix_label": _mix_label(fetch_status_counts),
        "provider_mix_label": _mix_label(provider_resolution_counts),
        "transport_mix_label": _mix_label(transport_resolution_counts),
        "fetch_foundation_label": _fetch_foundation_label(
            external_window_count=external_window_count,
            fetched_window_count=fetched_window_count,
            fallback_window_count=fallback_window_count,
        ),
        "orchestration_foundation_label": _orchestration_foundation_label(
            external_search_hit_count=external_search_hit_count,
            fallback_provider_hit_count=fallback_provider_hit_count,
            external_window_count=external_window_count,
            fallback_transport_window_count=fallback_transport_window_count,
        ),
        "archive_readiness_label": _archive_readiness_label(
            external_window_count=external_window_count,
            archive_ready_window_count=archive_ready_window_count,
            archive_unready_window_count=archive_unready_window_count,
        ),
        "transport_complexity_label": _transport_complexity_label(
            external_window_count=external_window_count,
            multi_transport_window_count=multi_transport_window_count,
        ),
    }


def _build_source_ref(
    row: object,
    window: ResearchReadWindow | None,
    search_hit: ResearchSearchHit | None,
) -> dict[str, object]:
    return {
        "source_id": getattr(row, "source_id", ""),
        "source_title": getattr(row, "source_title", ""),
        "source_domain": getattr(row, "source_domain", ""),
        "source_quality": getattr(row, "source_quality", "GENERAL_WEB"),
        "read_strategy": getattr(row, "read_strategy", "BALANCED_READ"),
        "url": window.url if window is not None else "",
        "provider": window.provider if window is not None else "",
        "adapter": window.adapter if window is not None else "",
        "fetch_status": window.fetch_status if window is not None else "",
        "fetch_method": window.fetch_method if window is not None else "",
        "content_origin": window.content_origin if window is not None else "",
        "transport_resolution": window.transport_resolution if window is not None else "",
        "source_type": window.source_type if window is not None else "UNKNOWN",
        "author": window.author if window is not None else "",
        "institution": window.institution if window is not None else "",
        "published_at": window.published_at if window is not None else "",
        "updated_at": window.updated_at if window is not None else "",
        "freshness_status": window.freshness_status if window is not None else "UNKNOWN",
        "hit_id": search_hit.hit_id if search_hit is not None else "",
    }


def _build_window_ref(window: ResearchReadWindow | None) -> dict[str, object]:
    if window is None:
        return {
            "window_id": "",
            "hit_id": "",
            "query": "",
            "query_family": "direct",
            "read_focus": "",
            "retention_reason": "",
            "snapshot_status": "",
            "snapshot_key": "",
            "url": "",
            "provider": "",
            "adapter": "",
            "fetch_status": "",
            "fetch_method": "",
            "content_origin": "",
            "transport_resolution": "",
        }
    return {
        "window_id": window.window_id,
        "hit_id": window.hit_id,
        "query": window.query,
        "query_family": window.query_family,
        "read_focus": window.read_focus,
        "retention_reason": window.retention_reason,
        "snapshot_status": window.snapshot_status,
        "snapshot_key": window.snapshot_key,
        "url": window.url,
        "provider": window.provider,
        "adapter": window.adapter,
        "fetch_status": window.fetch_status,
        "fetch_method": window.fetch_method,
        "content_origin": window.content_origin,
        "transport_resolution": window.transport_resolution,
    }


def _build_search_ref(search_hit: ResearchSearchHit | None) -> dict[str, object]:
    if search_hit is None:
        return {
            "hit_id": "",
            "query": "",
            "search_angle": "",
            "rank": 0,
            "provider_resolution": "",
            "provider_attempts": [],
        }
    return {
        "hit_id": search_hit.hit_id,
        "query": search_hit.query,
        "search_angle": search_hit.search_angle,
        "rank": search_hit.rank,
        "provider_resolution": search_hit.provider_resolution,
        "provider_attempts": list(search_hit.provider_attempts),
    }


def _build_cell_refs(
    cells: list[object],
    *,
    row: object,
) -> list[dict[str, object]]:
    if cells:
        return [
            {
                "cell_id": cell.cell_id,
                "column_key": cell.column_key,
                "status": cell.status,
                "confidence": cell.confidence,
                "last_verifier_decision": cell.last_verifier_decision,
                "is_required": cell.is_required,
                "required_by_requirement_ids": list(cell.required_by_requirement_ids),
                "satisfied_requirement_ids": list(cell.satisfied_requirement_ids),
                "requirement_completion_status": cell.requirement_completion_status,
                "evidence_refs": list(cell.evidence_refs),
            }
            for cell in cells
        ]
    fallback_columns = [
        ("source_title", getattr(row, "source_title", "")),
        ("read_focus", getattr(row, "read_focus", "")),
        ("evidence_id", getattr(row, "evidence_id", "")),
        ("claim_text", getattr(row, "claim_text", "")),
        ("evidence_excerpt", getattr(row, "evidence_excerpt", "")),
    ]
    fallback_refs: list[dict[str, object]] = []
    for column_key, value in fallback_columns:
        if not str(value).strip():
            continue
        fallback_refs.append(
            {
                "cell_id": f"{getattr(row, 'row_id', '')}:{column_key}",
                "column_key": column_key,
                "status": getattr(row, "row_status", ""),
                "confidence": max(
                    float(getattr(row, "support_score", 0.0) or 0.0),
                    float(getattr(row, "conflict_score", 0.0) or 0.0),
                ),
                "last_verifier_decision": getattr(row, "verification_status", ""),
                "is_required": False,
                "required_by_requirement_ids": [],
                "satisfied_requirement_ids": [],
                "requirement_completion_status": "NOT_REQUIRED",
                "evidence_refs": [getattr(row, "evidence_id", "")] if getattr(row, "evidence_id", "") else [],
            }
        )
    return fallback_refs


def _collect_provenance_bindings(
    evidence_ledger: list[dict[str, object]],
) -> list[dict[str, object]]:
    bindings: list[dict[str, object]] = []
    seen: set[tuple[str, str]] = set()
    for item in evidence_ledger:
        evidence_id = str(item.get("evidence_id", "")).strip()
        row_id = str(item.get("row_id", "")).strip()
        provenance = item.get("provenance")
        if not isinstance(provenance, dict):
            continue
        key = (evidence_id, row_id)
        if key in seen:
            continue
        seen.add(key)
        bindings.append(provenance)
    return bindings


def _build_source_refs(
    provenance_bindings: list[dict[str, object]],
) -> list[dict[str, object]]:
    refs_by_source_id: dict[str, dict[str, object]] = {}
    for binding in provenance_bindings:
        source_ref = dict(binding.get("source_ref", {}))
        requirement_ref = dict(binding.get("requirement_ref", {}))
        row_ref = dict(binding.get("row_ref", {}))
        window_ref = dict(binding.get("window_ref", {}))
        evidence_ref = dict(binding.get("evidence_ref", {}))
        source_id = str(source_ref.get("source_id", "")).strip()
        if not source_id:
            continue
        entry = refs_by_source_id.setdefault(
            source_id,
            {
                "source_id": source_id,
                "source_title": source_ref.get("source_title", ""),
                "source_domain": source_ref.get("source_domain", ""),
                "source_quality": source_ref.get("source_quality", "GENERAL_WEB"),
                "provider": source_ref.get("provider", ""),
                "adapter": source_ref.get("adapter", ""),
                "window_ids": [],
                "row_ids": [],
                "cell_ids": [],
                "evidence_ids": [],
                "query_families": [],
                "target_requirement_ids": [],
            },
        )
        for key, values in (
            ("window_ids", [window_ref.get("window_id", "")]),
            ("row_ids", [row_ref.get("row_id", "")]),
            ("cell_ids", list(row_ref.get("cell_ids", []))),
            ("evidence_ids", [evidence_ref.get("evidence_id", "")]),
            ("query_families", [window_ref.get("query_family", "")]),
            ("target_requirement_ids", list(requirement_ref.get("target_requirement_ids", []))),
        ):
            for value in values:
                value_text = str(value).strip()
                if value_text and value_text not in entry[key]:
                    entry[key].append(value_text)
    return list(refs_by_source_id.values())


def _render_row_markdown(row: dict[str, object]) -> list[str]:
    source_ref = dict(row.get("source_ref", {}))
    window_id = str(row.get("window_id", "")).strip()
    query_family = str(row.get("query_family", "")).strip()
    requirement_labels = [
        str(item).strip()
        for item in row.get("target_requirement_labels", [])
        if str(item).strip()
    ]
    return [
        f"- {row.get('source_title', '')}",
        f"  source_ref: {source_ref.get('source_id', '') or row.get('source_id', '')}",
        f"  focus: {row.get('read_focus', '')}",
        f"  window: {window_id or 'N/A'} ({query_family or 'direct'})",
        f"  evidence: {row.get('evidence_id', '')}",
        f"  claim: {row.get('claim_text', '')}",
        f"  excerpt: {row.get('evidence_excerpt', '')}",
        f"  support: {row.get('support_level', '')} ({_format_score(row.get('support_score'))})",
        f"  row_status: {row.get('row_status', '')}",
        (
            f"  requirements: {'; '.join(requirement_labels[:2])}"
            if requirement_labels
            else "  requirements: none targeted"
        ),
    ]


def _format_score(value: object) -> str:
    try:
        return f"{float(value):.2f}"
    except (TypeError, ValueError):
        return "0.00"


def _answer_status(decision: str) -> str:
    normalized = (decision or "").strip().upper()
    if normalized == "READY_TO_WRITE":
        return "VERIFIED"
    if normalized == "WRITE_WITH_GUARDRAILS":
        return "GUARDED"
    return "RECOVERY_NEEDED"


def _confidence_label(
    decision: str,
    verified_count: int,
    conflicted_count: int,
    source_foundation: dict[str, object],
) -> str:
    status = _answer_status(decision)
    fetch_foundation = str(source_foundation.get("fetch_foundation_label", "")).strip().lower()
    orchestration_foundation = str(source_foundation.get("orchestration_foundation_label", "")).strip().lower()
    if status == "VERIFIED":
        if "all external windows are snippet-level fallback windows" in fetch_foundation:
            return f"Verifier-approved but still snippet-level fallback grounded across {verified_count} verified finding(s)"
        if "fallback chain" in orchestration_foundation:
            return f"Verifier-approved but recovered through external fallback chains across {verified_count} verified finding(s)"
        return f"Verifier-approved with {verified_count} verified finding(s)"
    if status == "GUARDED":
        if "fallback" in fetch_foundation:
            return f"Guarded synthesis because fallback snippet windows still carry the answer path ({conflicted_count} unresolved conflict(s))"
        if "fallback chain" in orchestration_foundation:
            return f"Guarded synthesis because external fallback chains still carry the answer path ({conflicted_count} unresolved conflict(s))"
        return f"Guarded synthesis with {conflicted_count} unresolved conflict(s)"
    return "Recovery still required before a stable answer"


def _coverage_label(
    global_result: GlobalVerifierResult,
    verified_findings: list[dict[str, object]],
) -> str:
    contract = global_result.intent_completion_contract
    if contract.total_requirement_count > 0:
        return (
            f"{contract.satisfied_requirement_count}/"
            f"{contract.total_requirement_count} intent requirement(s) satisfied"
        )
    return f"{len(verified_findings)} verified finding(s) ready for synthesis"


def _source_basis_label(
    verified_findings: list[dict[str, object]],
    source_foundation: dict[str, object],
) -> str:
    fetch_foundation = str(source_foundation.get("fetch_foundation_label", "")).strip().lower()
    orchestration_foundation = str(source_foundation.get("orchestration_foundation_label", "")).strip().lower()
    if "all external windows are snippet-level fallback windows" in fetch_foundation:
        return "Fallback-snippet grounded"
    if "all external windows have fetched page bodies" in fetch_foundation:
        if "provider and transport fallback chains" in orchestration_foundation:
            return "Provider/transport-fallback grounded"
        if "provider fallback chain" in orchestration_foundation:
            return "Provider-fallback grounded"
        if "transport fallback chain" in orchestration_foundation:
            return "Transport-fallback grounded"
        return "Fetched-external grounded"
    qualities = {
        str(row.get("source_quality", "")).strip()
        for row in verified_findings
        if str(row.get("source_quality", "")).strip()
    }
    if qualities and qualities.issubset({"OFFICIAL_DOC"}):
        return "Official-document grounded"
    if qualities and qualities.issubset({"GOVERNMENT_SOURCE"}):
        return "Government-source grounded"
    if qualities and qualities.issubset({"ACADEMIC_SOURCE"}):
        return "Academic-source grounded"
    if qualities and qualities.issubset({"GENERAL_WEB", "SECONDARY_SOURCE", "REFERENCE_SOURCE"}):
        return "General-web grounded"
    source_titles = list(dict.fromkeys(
        str(row.get("source_title", "")).strip()
        for row in verified_findings
        if str(row.get("source_title", "")).strip()
    ))
    if not source_titles:
        return "No verifier-approved source basis yet"
    return f"{len(source_titles)} source(s): {', '.join(source_titles[:3])}"


def _mix_label(counts: dict[str, int]) -> str:
    if not counts:
        return "No mix data available"
    ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    return ", ".join(f"{key} x{value}" for key, value in ordered[:3])


def _chain_label(values: list[str]) -> str:
    cleaned = [str(item).strip() for item in values if str(item).strip()]
    return " -> ".join(cleaned) if cleaned else "UNKNOWN"


def _fetch_foundation_label(
    *,
    external_window_count: int,
    fetched_window_count: int,
    fallback_window_count: int,
) -> str:
    if external_window_count <= 0:
        return "No external fetch foundation"
    if fetched_window_count == external_window_count:
        return "All external windows have fetched page bodies"
    if fallback_window_count == external_window_count:
        return "All external windows are snippet-level fallback windows"
    return (
        f"Mixed external fetch foundation: {fetched_window_count} fetched / "
        f"{fallback_window_count} fallback"
    )


def _orchestration_foundation_label(
    *,
    external_search_hit_count: int,
    fallback_provider_hit_count: int,
    external_window_count: int,
    fallback_transport_window_count: int,
) -> str:
    if external_search_hit_count <= 0 and external_window_count <= 0:
        return "No external orchestration foundation"
    if fallback_provider_hit_count > 0 and fallback_transport_window_count > 0:
        return "Recovered through provider and transport fallback chains"
    if fallback_provider_hit_count > 0:
        return "Recovered through provider fallback chain"
    if fallback_transport_window_count > 0:
        return "Recovered through transport fallback chain"
    return "Primary provider and transport path held"


def _archive_readiness_label(
    *,
    external_window_count: int,
    archive_ready_window_count: int,
    archive_unready_window_count: int,
) -> str:
    if external_window_count <= 0:
        return "No external archive-readiness foundation"
    if archive_ready_window_count == external_window_count:
        return "All external windows are archive-ready"
    if archive_unready_window_count == external_window_count:
        return "No external window is archive-ready yet"
    return (
        f"Mixed archive-ready foundation: {archive_ready_window_count} archive-ready / "
        f"{archive_unready_window_count} not ready"
    )


def _transport_complexity_label(
    *,
    external_window_count: int,
    multi_transport_window_count: int,
) -> str:
    if external_window_count <= 0:
        return "No external transport complexity foundation"
    if multi_transport_window_count <= 0:
        return "All external windows resolved through single-transport paths"
    if multi_transport_window_count == external_window_count:
        return "All external windows required multi-transport recovery"
    return (
        f"Mixed transport complexity: {multi_transport_window_count} multi-transport / "
        f"{external_window_count - multi_transport_window_count} single-transport"
    )
