from __future__ import annotations

from app.models import ResearchEvidenceCard, ResearchPlan, ResearchReadWindow, ResearchTaskInput


def extract_evidence_cards(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    read_windows: list[ResearchReadWindow],
) -> list[ResearchEvidenceCard]:
    """Convert opened read windows into explicit evidence cards."""
    del task_input
    cards: list[ResearchEvidenceCard] = []
    for window in read_windows:
        quote = _first_evidence_span(window.window_text)
        conflict_score = _score_conflict(quote)
        support_score = _score_support(quote, conflict_score)
        relation_type = "CONFLICTS" if conflict_score >= 0.5 else "SUPPORTS"
        if support_score < 0.55 and relation_type != "CONFLICTS":
            relation_type = "WEAK_SUPPORT"

        cards.append(
            ResearchEvidenceCard(
                evidence_id=f"ev-{len(cards) + 1}",
                window_id=window.window_id,
                source_id=window.source_id,
                source_title=window.source_title,
                claim_text=(
                    f"{window.source_title} provides evidence related to "
                    f"{plan.normalized_question}."
                ),
                quote_text=quote,
                relation_type=relation_type,
                support_score=support_score,
                conflict_score=conflict_score,
            )
        )
    return cards


def _first_evidence_span(text: str) -> str:
    normalized = " ".join(text.strip().split())
    if not normalized:
        return "No readable evidence span was extracted from this window."
    for separator in [". ", "; ", "\n"]:
        if separator in normalized:
            return normalized.split(separator, maxsplit=1)[0].strip() + "."
    return normalized[:280]


def _score_conflict(quote: str) -> float:
    lowered = quote.lower()
    conflict_markers = ["conflict", "contradict", "however", "but ", "risk", "uncertain"]
    return 0.55 if any(marker in lowered for marker in conflict_markers) else 0.05


def _score_support(quote: str, conflict_score: float) -> float:
    if not quote.strip() or quote.startswith("No readable"):
        return 0.2
    if "has no summary" in quote:
        return 0.42
    if conflict_score >= 0.5:
        return 0.58
    return 0.82
