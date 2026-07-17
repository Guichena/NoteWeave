from __future__ import annotations

from app.models import ResearchEvidenceCard, ResearchStateLedger


def plan_evidence_horizon(
    ledger: ResearchStateLedger,
    evidence_cards: list[ResearchEvidenceCard],
    *,
    round_no: int,
) -> list[dict[str, object]]:
    cards_by_id = {card.evidence_id: card for card in evidence_cards}
    decisions: list[dict[str, object]] = []
    for cell in ledger.cells:
        bound = [cards_by_id[item] for item in cell.evidence_refs if item in cards_by_id]
        source_diversity = len({card.source_id for card in bound if card.source_id})
        has_conflict = any(card.relation_type == "CONFLICTS" for card in bound)
        uncertainty = round(1.0 - max(cell.verdict_confidence, cell.confidence), 4)
        retry_pressure = min(2, max(0, cell.repair_count))
        if cell.status in {"VERIFIED", "FROZEN"} and not has_conflict:
            action, target, reason = "SHRINK", 1, "stable cell has sufficient evidence"
        elif has_conflict or cell.status == "CONFLICTED":
            action, target, reason = "EXPAND_COUNTERFACTUAL", min(5, max(2, source_diversity + 1 + retry_pressure)), "conflict needs an independent source"
        elif uncertainty >= 0.5 or source_diversity < 2:
            action, target, reason = "EXPAND", min(5, max(2, source_diversity + 1 + retry_pressure)), "uncertainty, retry pressure, or single-source support remains"
        else:
            action, target, reason = "HOLD", max(1, source_diversity), "marginal evidence need is low"
        decisions.append({
            "horizon_key": f"{cell.entity_id}:{cell.column_key}",
            "entity_id": cell.entity_id,
            "column_key": cell.column_key,
            "round_no": round_no,
            "uncertainty": uncertainty,
            "source_diversity": source_diversity,
            "retry_count": cell.repair_count,
            "action": action,
            "target_window_count": target,
            "reason": reason,
        })
    return decisions
