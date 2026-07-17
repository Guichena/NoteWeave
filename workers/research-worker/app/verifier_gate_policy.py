from __future__ import annotations

from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchHarnessVerifierGatePolicyState,
    ResearchLoopDecision,
    ResearchStateLedger,
)


def report_gate_action_from_global_decision(global_decision: str) -> str:
    normalized = (global_decision or "").strip().upper()
    if normalized == "READY_TO_WRITE":
        return "ALLOW_FINAL_WRITE"
    if normalized == "WRITE_WITH_GUARDRAILS":
        return "ALLOW_GUARDED_WRITE"
    return "BLOCK_REPORT_WRITE"


def build_verifier_gate_policy_state(
    *,
    recovery_mode: str = "",
    branch_decisions: list[ResearchBranchDecision] | None = None,
    ledger: ResearchStateLedger,
    local_result: LocalVerifierResult,
    global_result: GlobalVerifierResult,
    final_decision: ResearchLoopDecision,
) -> ResearchHarnessVerifierGatePolicyState:
    final_loop_decision = str(final_decision.decision or "").strip().upper()
    loop_gate_action = infer_loop_gate_action(
        final_decision=final_decision,
        recovery_mode=recovery_mode,
        branch_decisions=branch_decisions or [],
    )
    report_gate_action = report_gate_action_from_global_decision(global_result.decision)
    blocking_reason_codes = _collect_blocking_reason_codes(
        ledger=ledger,
        local_result=local_result,
        global_result=global_result,
        final_decision=final_decision,
    )
    pending_requirement_count = sum(
        1
        for item in ledger.required_finding_progress
        if item.status != "READY"
    )
    return ResearchHarnessVerifierGatePolicyState(
        policy_version="v1",
        loop_gate_action=loop_gate_action,
        final_loop_decision=final_loop_decision,
        report_gate_action=report_gate_action,
        should_continue_loop=final_decision.should_continue,
        allows_final_write=report_gate_action == "ALLOW_FINAL_WRITE",
        allows_guarded_write=report_gate_action == "ALLOW_GUARDED_WRITE",
        requires_source_expansion=loop_gate_action == "EXPAND_SOURCE_SCOPE",
        requires_read_more=loop_gate_action == "READ_MORE",
        requires_extract_again=loop_gate_action == "EXTRACT_AGAIN",
        requires_counterfactual_recheck=loop_gate_action == "COUNTERFACTUAL_RECHECK",
        blocking_reason_codes=blocking_reason_codes,
        pending_requirement_count=pending_requirement_count,
        unresolved_question_count=len(ledger.unresolved_questions),
        warning_count=len(local_result.warnings),
    )


def infer_loop_gate_action(
    *,
    final_decision: ResearchLoopDecision,
    recovery_mode: str,
    branch_decisions: list[ResearchBranchDecision],
) -> str:
    final_loop_decision = str(final_decision.decision or "").strip().upper()
    if final_loop_decision in {
        "EXPAND_SOURCE_SCOPE",
        "READ_MORE",
        "EXTRACT_AGAIN",
        "COUNTERFACTUAL_RECHECK",
    }:
        return final_loop_decision
    normalized_recovery_mode = str(recovery_mode or "").strip().upper()
    if normalized_recovery_mode in {
        "READ_MORE",
        "EXTRACT_AGAIN",
        "COUNTERFACTUAL_RECHECK",
    }:
        return normalized_recovery_mode
    for branch_decision in reversed(branch_decisions):
        decision = str(branch_decision.decision or "").strip().upper()
        if decision in {
            "EXPAND_SOURCE_SCOPE",
            "READ_MORE",
            "EXTRACT_AGAIN",
            "COUNTERFACTUAL_RECHECK",
        }:
            return decision
    return final_loop_decision


def _collect_blocking_reason_codes(
    *,
    ledger: ResearchStateLedger,
    local_result: LocalVerifierResult,
    global_result: GlobalVerifierResult,
    final_decision: ResearchLoopDecision,
) -> list[str]:
    reason_codes: list[str] = []
    for code in [
        str(final_decision.reason or "").strip(),
        *[
            str(record.reason_code or "").strip()
            for record in local_result.decision_records
        ],
        *[
            str(record.reason_code or "").strip()
            for record in global_result.decision_records
        ],
    ]:
        if code and code not in reason_codes:
            reason_codes.append(code)
    if not reason_codes and ledger.unresolved_questions:
        reason_codes.append("UNRESOLVED_QUESTIONS")
    return reason_codes
