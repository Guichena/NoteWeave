from __future__ import annotations

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient
from app.models import ResearchEvidenceCard, ResearchPlan, ResearchReadWindow, ResearchTaskInput


def extract_evidence_cards(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    read_windows: list[ResearchReadWindow],
    llm_client: LlmClient | None = None,
) -> list[ResearchEvidenceCard]:
    """Convert opened read windows into explicit evidence cards."""
    del task_input
    if llm_client is not None:
        llm_cards = _extract_evidence_cards_with_llm(plan, read_windows, llm_client)
        if llm_cards:
            return llm_cards

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


def _extract_evidence_cards_with_llm(
    plan: ResearchPlan,
    read_windows: list[ResearchReadWindow],
    llm_client: LlmClient,
) -> list[ResearchEvidenceCard]:
    response = llm_client.complete_json(
        "research.extract",
        {
            "question": plan.normalized_question,
            "windows": [
                {
                    "window_id": window.window_id,
                    "source_id": window.source_id,
                    "source_title": window.source_title,
                    "query": window.query,
                    "text": window.window_text[:1200],
                }
                for window in read_windows
            ],
            "schema": {
                "evidence_cards": [
                    {
                        "window_id": "window id from input",
                        "claim_text": "claim grounded in the window",
                        "quote_text": "short quote copied from the window",
                        "relation_type": "SUPPORTS | WEAK_SUPPORT | CONFLICTS",
                        "support_score": "0.0-1.0",
                        "conflict_score": "0.0-1.0",
                    }
                ]
            },
        },
    )
    payload = parse_json_payload(response)
    if not isinstance(payload, dict):
        return []
    raw_cards = payload.get("evidence_cards")
    if not isinstance(raw_cards, list):
        return []

    windows_by_id = {window.window_id: window for window in read_windows}
    cards: list[ResearchEvidenceCard] = []
    for raw_card in raw_cards:
        if not isinstance(raw_card, dict):
            continue
        window_id = str(raw_card.get("window_id") or "").strip()
        window = windows_by_id.get(window_id)
        if window is None:
            continue
        quote = str(raw_card.get("quote_text") or "").strip() or _first_evidence_span(window.window_text)
        claim = str(raw_card.get("claim_text") or "").strip()
        if not claim:
            claim = f"{window.source_title} provides evidence related to {plan.normalized_question}."
        relation_type = _normalize_relation_type(str(raw_card.get("relation_type") or "SUPPORTS"))
        support_score = _clamp_score(raw_card.get("support_score"), default=0.72)
        conflict_score = _clamp_score(raw_card.get("conflict_score"), default=0.05)
        if relation_type == "CONFLICTS":
            conflict_score = max(conflict_score, 0.5)
        cards.append(
            ResearchEvidenceCard(
                evidence_id=f"ev-{len(cards) + 1}",
                window_id=window.window_id,
                source_id=window.source_id,
                source_title=window.source_title,
                claim_text=claim,
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


def _normalize_relation_type(value: str) -> str:
    normalized = value.strip().upper()
    if normalized in {"SUPPORTS", "WEAK_SUPPORT", "CONFLICTS"}:
        return normalized
    return "SUPPORTS"


def _clamp_score(value: object, default: float) -> float:
    try:
        score = float(value)
    except (TypeError, ValueError):
        score = default
    return min(1.0, max(0.0, round(score, 4)))
