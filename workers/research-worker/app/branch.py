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
) -> list[ResearchBranchDecision]:
    """Create a small verifier-scoped recovery decision for the current run."""
    if not search_hits:
        return [
            ResearchBranchDecision(
                branch_id="branch-recovery-1",
                decision="EXPAND_SOURCE_SCOPE",
                branch_reason="NO_SEARCH_HITS",
                verifier_scope="SEARCH",
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
                recovery_actions=["rerun evidence extraction before synthesis"],
            )
        ]

    if any(card.relation_type == "CONFLICTS" for card in evidence_cards):
        return [
            ResearchBranchDecision(
                branch_id="branch-recovery-1",
                decision="COUNTERFACTUAL_RECHECK",
                branch_reason="CONFLICTING_EVIDENCE",
                verifier_scope="VERIFY",
                recovery_actions=[
                    "open an alternative source window for the conflicting claim",
                    "keep uncertainty visible in verifier-gated synthesis",
                ],
            )
        ]

    if local_result.warnings:
        return [
            ResearchBranchDecision(
                branch_id="branch-recovery-1",
                decision="WRITE_WITH_GUARDRAILS",
                branch_reason="LOCAL_VERIFIER_WARNINGS",
                verifier_scope="VERIFY",
                recovery_actions=list(local_result.recovery_actions),
            )
        ]

    return [
        ResearchBranchDecision(
            branch_id="branch-main",
            decision="NO_BRANCH",
            branch_reason="VERIFIED_PATH",
            verifier_scope="VERIFY",
            recovery_actions=[],
        )
    ]
