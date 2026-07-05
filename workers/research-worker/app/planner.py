from __future__ import annotations

from app.models import ResearchPlan, ResearchTaskInput


def build_research_plan(task_input: ResearchTaskInput) -> ResearchPlan:
    question = task_input.input_payload.question.strip()
    profile_key = task_input.input_payload.profile_key.strip().upper() or "DEFAULT"

    query_set = [question]
    for item in task_input.source_scope[:3]:
        query_set.append(f"{question} :: {item.title}".strip())

    report_sections = [
        "Research Question",
        "Key Findings",
        "Evidence Ledger",
        "Conflicts And Uncertainty",
        "Next Actions",
    ]
    state_columns = [
        "source_title",
        "search_query",
        "read_focus",
        "evidence_id",
        "claim_text",
        "evidence_excerpt",
        "relation_type",
        "support_score",
        "support_level",
        "verifier_note",
    ]

    notes: list[str] = []
    if task_input.control_pack.style_constraints:
        notes.append(
            "style constraints: " + "; ".join(task_input.control_pack.style_constraints)
        )
    if task_input.control_pack.structure_constraints:
        notes.append(
            "structure constraints: "
            + "; ".join(task_input.control_pack.structure_constraints)
        )

    stop_contract = {
        "profile_key": profile_key,
        "required_sections": report_sections,
        "min_sources": min(3, max(1, len(task_input.source_scope) or 1)),
        "must_respect_evidence_policy": True,
        "requires_local_verifier": True,
        "requires_global_verifier": True,
        "global_search_limit": 8,
        "tool_response_retention_budget": 5,
        "branch_budget": 1,
    }

    return ResearchPlan(
        normalized_question=question,
        query_set=query_set,
        report_sections=report_sections,
        state_columns=state_columns,
        stop_contract=stop_contract,
        notes=notes,
    )
