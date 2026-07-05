from __future__ import annotations

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient
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


def run_local_verifier(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
    llm_client: LlmClient | None = None,
) -> LocalVerifierResult:
    passed_checks: list[str] = []
    warnings: list[str] = []
    recovery_actions: list[str] = []

    if plan.normalized_question:
        passed_checks.append("question normalized")
    else:
        warnings.append("question is empty after normalization")

    if plan.query_set:
        passed_checks.append("query bundle compiled")
    else:
        warnings.append("query bundle is empty")

    if search_hits:
        passed_checks.append("workspace search hits resolved")
    else:
        warnings.append("no search hit resolved from workspace scope")
        recovery_actions.append("attach at least one workspace source before final export")

    if read_windows:
        passed_checks.append("goal-conditioned read windows opened")
    elif search_hits:
        warnings.append("search hits exist but no read window was opened")
        recovery_actions.append("increase read-window retention budget")

    if evidence_cards:
        passed_checks.append("evidence cards extracted")
    elif read_windows:
        warnings.append("read windows exist but no evidence card was extracted")
        recovery_actions.append("rerun evidence extraction before synthesis")

    if ledger.rows:
        passed_checks.append("state ledger populated")
    else:
        warnings.append("state ledger has no evidence rows")

    if task_input.control_pack.evidence_policy:
        passed_checks.append("evidence policy acknowledged")
    else:
        warnings.append("no evidence policy supplied by control pack")

    if len(ledger.rows) < int(plan.stop_contract["min_sources"]):
        warnings.append("source coverage is below the preferred threshold")
        recovery_actions.append("expand the source scope or lower the source threshold")

    if any(card.relation_type == "CONFLICTS" for card in evidence_cards):
        warnings.append("conflicting evidence card requires a branch recheck")
        recovery_actions.append("open an alternative source window for conflicting evidence")

    if llm_client is not None and evidence_cards:
        llm_warning, llm_actions = _run_llm_local_judge(
            llm_client,
            plan,
            ledger,
            evidence_cards,
        )
        warnings.extend(llm_warning)
        recovery_actions.extend(llm_actions)

    status = "PASS" if not warnings else "WARN"
    return LocalVerifierResult(
        status=status,
        passed_checks=passed_checks,
        warnings=warnings,
        recovery_actions=recovery_actions,
    )


def _run_llm_local_judge(
    llm_client: LlmClient,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
    evidence_cards: list[ResearchEvidenceCard],
) -> tuple[list[str], list[str]]:
    response = llm_client.complete_json(
        "research.verify.local",
        {
            "question": plan.normalized_question,
            "ledger_rows": [
                {
                    "evidence_id": row.evidence_id,
                    "claim_text": row.claim_text,
                    "support_level": row.support_level,
                    "relation_type": row.relation_type,
                    "support_score": row.support_score,
                    "conflict_score": row.conflict_score,
                }
                for row in ledger.rows
            ],
            "evidence_ids": [card.evidence_id for card in evidence_cards],
            "schema": {
                "status": "PASS | WARN",
                "warnings": ["short warning"],
                "recovery_actions": ["short recovery action"],
            },
        },
    )
    payload = parse_json_payload(response)
    if not isinstance(payload, dict):
        return [], []

    warnings = _string_list(payload.get("warnings"))
    recovery_actions = _string_list(payload.get("recovery_actions"))
    status = str(payload.get("status") or "").strip().upper()
    if status not in {"", "PASS", "WARN"}:
        warnings.append(f"llm verifier returned unsupported status: {status}")
    return warnings, recovery_actions


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def run_global_verifier(
    local_result: LocalVerifierResult,
    ledger: ResearchStateLedger,
    branch_decisions: list[ResearchBranchDecision],
) -> GlobalVerifierResult:
    counterfactual_checks = [
        "Would the conclusion change if the strongest source were removed?",
        "Is every key finding backed by an opened source window rather than style memory?",
        "Did the branch controller avoid synthesis when search/read/extract objects are missing?",
    ]
    recovery_actions = list(local_result.recovery_actions)
    for branch_decision in branch_decisions:
        recovery_actions.extend(branch_decision.recovery_actions)

    has_recovery_branch = any(
        branch_decision.decision != "NO_BRANCH" for branch_decision in branch_decisions
    )
    if (
        local_result.status == "PASS"
        and not ledger.unresolved_questions
        and not has_recovery_branch
    ):
        return GlobalVerifierResult(
            status="PASS",
            decision="READY_TO_WRITE",
            summary=(
                "The research loop has search hits, read windows, evidence cards, "
                "and a populated Table-as-State ledger."
            ),
            counterfactual_checks=counterfactual_checks,
            recovery_actions=recovery_actions,
        )

    if ledger.unresolved_questions:
        recovery_actions.append("mark unresolved questions explicitly in the report")

    return GlobalVerifierResult(
        status="WARN",
        decision="WRITE_WITH_GUARDRAILS",
        summary=(
            "The report can be written, but uncertainty and missing evidence must stay visible."
        ),
        counterfactual_checks=counterfactual_checks,
        recovery_actions=recovery_actions,
    )
