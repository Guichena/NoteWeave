from __future__ import annotations

from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchPlan,
    ResearchStateLedger,
    ResearchTaskInput,
)


def run_local_verifier(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
) -> LocalVerifierResult:
    passed_checks: list[str] = []
    warnings: list[str] = []
    recovery_actions: list[str] = []

    if plan.normalized_question:
        passed_checks.append("question normalized")
    else:
        warnings.append("question is empty after normalization")

    if ledger.rows:
        passed_checks.append("state ledger populated")
    else:
        warnings.append("state ledger has no evidence rows")
        recovery_actions.append("attach at least one workspace source before final export")

    if task_input.control_pack.evidence_policy:
        passed_checks.append("evidence policy acknowledged")
    else:
        warnings.append("no evidence policy supplied by control pack")

    if len(ledger.rows) < int(plan.stop_contract["min_sources"]):
        warnings.append("source coverage is below the preferred threshold")
        recovery_actions.append("expand the source scope or lower the source threshold")

    status = "PASS" if not warnings else "WARN"
    return LocalVerifierResult(
        status=status,
        passed_checks=passed_checks,
        warnings=warnings,
        recovery_actions=recovery_actions,
    )


def run_global_verifier(
    local_result: LocalVerifierResult,
    ledger: ResearchStateLedger,
) -> GlobalVerifierResult:
    counterfactual_checks = [
        "Would the conclusion change if the strongest source were removed?",
        "Is every key finding backed by an opened source window rather than style memory?",
    ]
    recovery_actions = list(local_result.recovery_actions)

    if local_result.status == "PASS" and not ledger.unresolved_questions:
        return GlobalVerifierResult(
            status="PASS",
            decision="READY_TO_WRITE",
            summary="The research loop has enough structure to write a bounded report.",
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
