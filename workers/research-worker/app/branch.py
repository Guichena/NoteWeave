from __future__ import annotations

from app.models import (
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchEvidenceCard,
    ResearchReadWindow,
    ResearchSearchHit,
)


def plan_branch_recovery(
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
    local_result: LocalVerifierResult,
    *,
    recovery_mode: str = "",
    round_no: int = 1,
    prior_decisions: list[ResearchBranchDecision] | None = None,
    branch_budget: int = 1,
) -> list[ResearchBranchDecision]:
    """Create a small verifier-scoped recovery decision for the current run."""
    if not search_hits:
        return [
            ResearchBranchDecision(
                branch_id="branch-recovery-1",
                decision="EXPAND_SOURCE_SCOPE",
                branch_reason="NO_SEARCH_HITS",
                verifier_scope="SEARCH",
                hypothesis_summary="Current scope produced no source hits; widen source discovery.",
                branch_status="ACTIVE_BRANCH",
                recovery_actions=[
                    "attach at least one workspace source before final export",
                    "rerun query bundle after source expansion",
                ],
            )
        ]

    if not read_windows:
        return [
            ResearchBranchDecision(
                branch_id="branch-recovery-1",
                decision="REOPEN_READ_WINDOWS",
                branch_reason="NO_READ_WINDOWS",
                verifier_scope="READ",
                hypothesis_summary="Search hits exist but no bounded reading window survived retention.",
                branch_status="ACTIVE_BRANCH",
                recovery_actions=["increase tool_response_retention_budget and reopen top hits"],
            )
        ]

    if not evidence_cards:
        return [
            ResearchBranchDecision(
                branch_id="branch-recovery-1",
                decision="REEXTRACT_EVIDENCE",
                branch_reason="NO_EVIDENCE_CARDS",
                verifier_scope="EXTRACT",
                hypothesis_summary="Opened windows did not yield grounded evidence cards and require re-extraction.",
                branch_status="ACTIVE_BRANCH",
                recovery_actions=["rerun evidence extraction before synthesis"],
            )
        ]

    resolved_evidence_ids = {
        evidence_id
        for decision in (prior_decisions or [])
        if decision.branch_status in {"RESOLVED", "RESOLVED_BRANCH"}
        for evidence_id in [*decision.target_evidence_ids, *decision.result_evidence_ids]
    }
    conflicting_cards = [
        card
        for card in evidence_cards
        if card.relation_type == "CONFLICTS"
        and card.evidence_id not in resolved_evidence_ids
    ]
    if conflicting_cards:
        conflict_groups: dict[tuple[str, str], list[ResearchEvidenceCard]] = {}
        for card in conflicting_cards:
            conflict_groups.setdefault(
                (card.entity_id or card.source_id, card.column_key or card.claim_text),
                [],
            ).append(card)
        conflict_targets = list(conflict_groups.values())
        if recovery_mode == "COUNTERFACTUAL_RECHECK":
            active_prior = [
                decision
                for decision in (prior_decisions or [])
                if decision.decision == "COUNTERFACTUAL_RECHECK"
                and decision.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
            ]
            if active_prior:
                evidence_by_id = {card.evidence_id: card for card in evidence_cards}
                decisions: list[ResearchBranchDecision] = []
                for prior in active_prior:
                    target_cards = [
                        evidence_by_id[evidence_id]
                        for evidence_id in prior.target_evidence_ids
                        if evidence_id in evidence_by_id
                    ]
                    target_keys = {
                        (card.entity_id or card.source_id, card.column_key or card.claim_text)
                        for card in target_cards
                    }
                    target_sources = {card.source_id for card in target_cards if card.source_id}
                    independent_results = [
                        card
                        for card in evidence_cards
                        if card.evidence_id not in prior.target_evidence_ids
                        and (card.entity_id or card.source_id, card.column_key or card.claim_text) in target_keys
                        and card.source_id not in target_sources
                        and card.discovery_round > prior.created_round
                        and card.branch_id == prior.branch_id
                        and card.grounding_verified
                    ]
                    if not independent_results:
                        decisions.append(
                            prior.model_copy(
                                update={
                                    "branch_reason": "COUNTERFACTUAL_EVIDENCE_MISSING",
                                    "resolved_round": 0,
                                    "result_evidence_ids": [],
                                    "recovery_actions": [
                                        "search a source independent from the conflicting source",
                                        "do not mark the branch resolved without new branch-scoped evidence",
                                    ],
                                }
                            )
                        )
                        continue
                    resolution = _resolve_counterfactual_outcome(independent_results)
                    decisions.append(
                        prior.model_copy(
                            update={
                                "decision": "COUNTERFACTUAL_RESOLVED",
                                "branch_reason": "INDEPENDENT_COUNTERFACTUAL_EVIDENCE_REVIEWED",
                                "hypothesis_summary": "Independent counterfactual evidence was reviewed before mainline merge.",
                                "branch_status": "RESOLVED_BRANCH",
                                "resolution": resolution,
                                "resolved_round": round_no,
                                "result_evidence_ids": [card.evidence_id for card in independent_results],
                                "recovery_actions": ["preserve both target and result evidence in the branch audit"],
                            }
                        )
                    )
                return decisions
        used_branch_ids = {
            decision.branch_id
            for decision in (prior_decisions or [])
            if decision.decision in {"COUNTERFACTUAL_RECHECK", "COUNTERFACTUAL_RESOLVED"}
            and decision.branch_id != "branch-main"
        }
        remaining_branch_budget = max(0, branch_budget - len(used_branch_ids))
        if remaining_branch_budget == 0:
            return [
                ResearchBranchDecision(
                    branch_id="branch-main",
                    decision="NO_BRANCH",
                    branch_reason="BRANCH_BUDGET_EXHAUSTED",
                    verifier_scope="VERIFY",
                    hypothesis_summary="Conflicting evidence remains but the bounded branch budget is exhausted.",
                    branch_status="MAINLINE",
                    recovery_actions=["keep the unresolved conflict visible and require guarded synthesis"],
                )
            ]
        conflict_targets = conflict_targets[:remaining_branch_budget]
        sibling_branch_ids: list[str] = []
        candidate_index = 1
        while len(sibling_branch_ids) < len(conflict_targets):
            candidate = _counterfactual_branch_id(candidate_index)
            candidate_index += 1
            if candidate not in used_branch_ids:
                sibling_branch_ids.append(candidate)
        return [
            ResearchBranchDecision(
                branch_id=sibling_branch_ids[index - 1],
                session_id=sibling_branch_ids[index - 1].replace("branch-", "session-", 1),
                decision="COUNTERFACTUAL_RECHECK",
                branch_reason="CONFLICTING_EVIDENCE",
                verifier_scope="VERIFY",
                hypothesis_summary=(
                    "Mainline evidence is contested; validate whether the conclusion still holds "
                    f"under alternative sources for {card.source_title}."
                ),
                target_evidence_ids=[item.evidence_id for item in cards],
                sibling_branch_ids=[branch_id for branch_id in sibling_branch_ids if branch_id != sibling_branch_ids[index - 1]],
                execution_mode="SEQUENTIAL_BRANCH",
                branch_status="ACTIVE_BRANCH",
                created_round=round_no,
                recovery_actions=[
                    "open an alternative source window for the conflicting claim",
                    "keep uncertainty visible in verifier-gated synthesis",
                ],
            )
            for index, cards in enumerate(conflict_targets, start=1)
            for card in cards[:1]
        ]

    if local_result.warnings:
        return [
            ResearchBranchDecision(
                branch_id="branch-main",
                decision="NO_BRANCH",
                branch_reason="LOCAL_VERIFIER_WARNINGS",
                verifier_scope="VERIFY",
                hypothesis_summary="Verifier warnings remain unresolved; keep the report guarded.",
                branch_status="MAINLINE",
                recovery_actions=list(local_result.recovery_actions),
            )
        ]

    return [
        ResearchBranchDecision(
            branch_id="branch-main",
            decision="NO_BRANCH",
            branch_reason="VERIFIED_PATH",
            verifier_scope="VERIFY",
            hypothesis_summary="Mainline evidence is sufficient for synthesis.",
            branch_status="MAINLINE",
            recovery_actions=[],
        )
    ]


def _counterfactual_branch_id(index: int) -> str:
    return f"branch-counterfactual-{index}"


def _resolve_counterfactual_outcome(cards: list[ResearchEvidenceCard]) -> str:
    support_count = sum(card.relation_type == "SUPPORTS" for card in cards)
    conflict_count = sum(card.relation_type == "CONFLICTS" for card in cards)
    if conflict_count > support_count:
        return "REVISE_MAINLINE"
    if support_count > 0 and conflict_count == 0:
        return "KEEP_MAINLINE_WITH_AUDIT"
    return "KEEP_GUARDED"
