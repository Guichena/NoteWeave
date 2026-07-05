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
) -> str:
    source_titles = [item.title for item in task_input.source_scope[:3]]
    terminology = (
        "; ".join(task_input.control_pack.terminology_policy)
        or "Use workspace-level terminology consistently."
    )
    evidence_policy = (
        "; ".join(task_input.control_pack.evidence_policy)
        or "Every key finding must remain anchored to workspace evidence."
    )

    lines = [
        f"# {plan.normalized_question}",
        "",
        "## Research Question",
        f"- Original question: {plan.normalized_question}",
        f"- Research profile: {task_input.input_payload.profile_key.strip() or 'DEFAULT'}",
        "",
        "## Key Findings",
        (
            "- The harness completed planning, workspace search, bounded reading, "
            "evidence extraction, local verification, and global verification."
        ),
        (
            f"- Current workspace source scope: {', '.join(source_titles) if source_titles else 'No explicit source attached'}"
        ),
        (
            f"- Runtime objects: {len(search_hits)} search hits, "
            f"{len(read_windows)} read windows, {len(evidence_cards)} evidence cards."
        ),
        "",
        "## Evidence Ledger",
    ]

    for row in ledger.rows:
        lines.extend(
            [
                f"- {row.source_title}",
                f"  focus: {row.read_focus}",
                f"  evidence: {row.evidence_id}",
                f"  claim: {row.claim_text}",
                f"  excerpt: {row.evidence_excerpt}",
                f"  support: {row.support_level} ({row.support_score:.2f})",
            ]
        )

    if not ledger.rows:
        lines.append("- No evidence row was built for this run.")

    lines.extend(
        [
            "",
            "## Conflicts And Uncertainty",
            f"- Local verifier status: {local_result.status}",
            f"- Global verifier decision: {global_result.decision}",
            f"- Evidence policy: {evidence_policy}",
        ]
    )

    for branch_decision in branch_decisions:
        lines.append(
            f"- Branch decision: {branch_decision.decision} / {branch_decision.branch_reason}"
        )

    for question in ledger.unresolved_questions:
        lines.append(f"- Unresolved: {question}")

    for warning in local_result.warnings:
        lines.append(f"- Warning: {warning}")

    lines.extend(
        [
            "",
            "## Next Actions",
            "- Continue only if additional source expansion or clarification is needed.",
            f"- Terminology policy: {terminology}",
        ]
    )

    if global_result.recovery_actions:
        for action in global_result.recovery_actions:
            lines.append(f"- Recovery action: {action}")

    if plan.notes:
        lines.extend(["", "## Control Notes"])
        for note in plan.notes:
            lines.append(f"- {note}")

    return "\n".join(lines).strip() + "\n"
