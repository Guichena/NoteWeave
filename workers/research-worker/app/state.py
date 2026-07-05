from __future__ import annotations

from app.models import (
    ResearchEvidenceCard,
    ResearchPlan,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchStateLedger,
    ResearchStateRow,
    ResearchTaskInput,
)


def build_state_ledger(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
) -> ResearchStateLedger:
    rows: list[ResearchStateRow] = []
    windows_by_id = {window.window_id: window for window in read_windows}
    for index, card in enumerate(evidence_cards, start=1):
        window = windows_by_id.get(card.window_id)
        read_focus = (
            window.read_focus
            if window
            else f"Read for evidence related to {plan.normalized_question}"
        )
        support_level = _support_level(card)
        rows.append(
            ResearchStateRow(
                row_id=f"row-{index}",
                source_id=card.source_id,
                source_title=card.source_title,
                search_query=window.query if window else _fallback_query(plan),
                read_focus=read_focus,
                evidence_id=card.evidence_id,
                claim_text=card.claim_text,
                quote_text=card.quote_text,
                evidence_excerpt=card.quote_text,
                relation_type=card.relation_type,
                support_score=card.support_score,
                conflict_score=card.conflict_score,
                support_level=support_level,
                verifier_note=_verifier_note(card, support_level),
            )
        )

    unresolved_questions: list[str] = []
    if not search_hits:
        unresolved_questions.append(
            "No source matched the research query bundle in the current workspace scope."
        )
    elif not read_windows:
        unresolved_questions.append(
            "Search hits exist, but no read window was retained for verification."
        )
    elif not rows:
        unresolved_questions.append(
            "Read windows exist, but no evidence card was extracted for synthesis."
        )
    elif len(rows) < plan.stop_contract["min_sources"]:
        unresolved_questions.append(
            "The source set is smaller than the preferred minimum and should be expanded."
        )

    return ResearchStateLedger(
        columns=plan.state_columns,
        rows=rows,
        unresolved_questions=unresolved_questions,
        search_hit_count=len(search_hits),
        read_window_count=len(read_windows),
        evidence_card_count=len(evidence_cards),
    )


def _fallback_query(plan: ResearchPlan) -> str:
    return plan.query_set[0] if plan.query_set else plan.normalized_question


def _support_level(card: ResearchEvidenceCard) -> str:
    if card.relation_type == "CONFLICTS":
        return "CONFLICTING"
    if card.support_score >= 0.7:
        return "SUPPORTED"
    if card.support_score >= 0.45:
        return "WEAK_SUPPORT"
    return "UNSUPPORTED"


def _verifier_note(card: ResearchEvidenceCard, support_level: str) -> str:
    if support_level == "SUPPORTED":
        return "Evidence card is strong enough for verifier-gated synthesis."
    if support_level == "CONFLICTING":
        return "Evidence card requires counterfactual recheck before synthesis."
    return "Evidence card is weak and should stay visible as uncertainty."
