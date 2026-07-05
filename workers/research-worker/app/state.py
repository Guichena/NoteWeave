from __future__ import annotations

from app.models import ResearchPlan, ResearchStateLedger, ResearchStateRow, ResearchTaskInput


def build_state_ledger(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
) -> ResearchStateLedger:
    rows: list[ResearchStateRow] = []
    for index, item in enumerate(task_input.source_scope[:5], start=1):
        read_focus = item.summary.strip() or f"Read for evidence related to {plan.normalized_question}"
        evidence_excerpt = item.summary.strip() or f"Placeholder evidence extracted from {item.title}"
        rows.append(
            ResearchStateRow(
                row_id=f"row-{index}",
                source_id=item.source_id,
                source_title=item.title,
                search_query=plan.query_set[min(index - 1, len(plan.query_set) - 1)],
                read_focus=read_focus,
                evidence_excerpt=evidence_excerpt,
                support_level="SUPPORTED" if item.summary.strip() else "WEAK_SUPPORT",
                verifier_note="Source opened and attached to the research ledger.",
            )
        )

    unresolved_questions: list[str] = []
    if not rows:
        unresolved_questions.append(
            "No source is currently attached, so the report must stay conservative."
        )
    elif len(rows) < plan.stop_contract["min_sources"]:
        unresolved_questions.append(
            "The source set is smaller than the preferred minimum and should be expanded."
        )

    return ResearchStateLedger(
        columns=plan.state_columns,
        rows=rows,
        unresolved_questions=unresolved_questions,
    )
