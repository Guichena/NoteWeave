from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ResearchHarnessRegressionCase:
    case_key: str
    title: str
    objective: str
    accepted_final_decisions: tuple[str, ...]
    accepted_global_decisions: tuple[str, ...] = ()
    expected_recovery_mode: str = ""
    expected_latest_branch_decision: str = ""
    expect_counterfactual: bool = False
    expect_resumed_from_checkpoint: bool = False
    min_loop_round_count: int = 1


def build_harness_regression_suite() -> list[ResearchHarnessRegressionCase]:
    return [
        ResearchHarnessRegressionCase(
            case_key="ready_synthesis",
            title="Ready Synthesis / Guarded Write",
            objective="The harness should finish a normal research run with a stable report outcome.",
            accepted_final_decisions=("SYNTHESIZE_REPORT", "WRITE_WITH_GUARDRAILS"),
            accepted_global_decisions=("READY_TO_WRITE", "WRITE_WITH_GUARDRAILS"),
            expect_counterfactual=False,
            expect_resumed_from_checkpoint=False,
            min_loop_round_count=1,
        ),
        ResearchHarnessRegressionCase(
            case_key="scope_expansion",
            title="Scope Expansion",
            objective="The harness should stop early and request source expansion when no evidence scope exists.",
            accepted_final_decisions=("EXPAND_SOURCE_SCOPE",),
            accepted_global_decisions=("WRITE_WITH_GUARDRAILS",),
            expected_latest_branch_decision="EXPAND_SOURCE_SCOPE",
            expect_counterfactual=False,
            expect_resumed_from_checkpoint=False,
            min_loop_round_count=1,
        ),
        ResearchHarnessRegressionCase(
            case_key="conflict_counterfactual",
            title="Conflict Counterfactual Recheck",
            objective="The harness should open a bounded counterfactual branch when conflict evidence appears.",
            accepted_final_decisions=("SYNTHESIZE_REPORT", "WRITE_WITH_GUARDRAILS"),
            accepted_global_decisions=("WRITE_WITH_GUARDRAILS",),
            expected_latest_branch_decision="COUNTERFACTUAL_RECHECK",
            expect_counterfactual=True,
            expect_resumed_from_checkpoint=False,
            min_loop_round_count=2,
        ),
        ResearchHarnessRegressionCase(
            case_key="resume_extract_again",
            title="Resume Extract Again",
            objective="The harness should resume from checkpoint and rerun extraction before opening new sources.",
            accepted_final_decisions=("SYNTHESIZE_REPORT", "WRITE_WITH_GUARDRAILS"),
            accepted_global_decisions=("READY_TO_WRITE", "WRITE_WITH_GUARDRAILS"),
            expected_recovery_mode="EXTRACT_AGAIN",
            expect_counterfactual=False,
            expect_resumed_from_checkpoint=True,
            min_loop_round_count=2,
        ),
        ResearchHarnessRegressionCase(
            case_key="resume_counterfactual",
            title="Resume Counterfactual Recovery",
            objective="The harness should preserve resumed conflict evidence and continue counterfactual recovery.",
            accepted_final_decisions=("SYNTHESIZE_REPORT", "WRITE_WITH_GUARDRAILS"),
            accepted_global_decisions=("READY_TO_WRITE", "WRITE_WITH_GUARDRAILS"),
            expected_latest_branch_decision="COUNTERFACTUAL_RECHECK",
            expect_counterfactual=True,
            expect_resumed_from_checkpoint=True,
            min_loop_round_count=2,
        ),
    ]


def build_harness_regression_suite_summary() -> dict[str, object]:
    suite = build_harness_regression_suite()
    return {
        "suite_version": "v1",
        "scenario_count": len(suite),
        "supported_case_keys": [case.case_key for case in suite],
        "titles": [case.title for case in suite],
    }


def evaluate_harness_regression_case(
    case: ResearchHarnessRegressionCase,
    result_payload: dict[str, object],
) -> dict[str, object]:
    harness_control_state = dict(result_payload.get("harness_control_state", {}))
    control_loop = dict(harness_control_state.get("control_loop", {}))
    verifier_gate = dict(harness_control_state.get("verifier_gate", {}))
    verifier_gate_policy = dict(result_payload.get("verifier_gate_policy", {}))
    branch_recovery = dict(harness_control_state.get("branch_recovery", {}))
    resume_checkpoint = dict(harness_control_state.get("resume_checkpoint", {}))
    counterfactual_summary = dict(result_payload.get("counterfactual_summary", {}))
    stop_contract = dict(result_payload.get("stop_contract", {}))
    loop_rounds = list(result_payload.get("loop_rounds", []))
    branch_decisions = [
        str(item.get("decision", "")).strip()
        for item in result_payload.get("branch_decisions", [])
        if isinstance(item, dict) and str(item.get("decision", "")).strip()
    ]

    failures: list[str] = []
    final_decision = str(control_loop.get("final_decision", "")).strip()
    if final_decision not in case.accepted_final_decisions:
        failures.append(
            f"final_decision expected one of {list(case.accepted_final_decisions)} but got {final_decision or 'EMPTY'}"
        )

    global_decision = str(verifier_gate.get("global_decision", "")).strip()
    if case.accepted_global_decisions and global_decision not in case.accepted_global_decisions:
        failures.append(
            f"global_decision expected one of {list(case.accepted_global_decisions)} but got {global_decision or 'EMPTY'}"
        )

    if case.expected_recovery_mode:
        recovery_mode = str(
            stop_contract.get("recovery_mode")
            or control_loop.get("recovery_mode")
            or ""
        ).strip()
        historical_recovery_decisions = {
            str(item.get("loop_decision", {}).get("decision", "")).strip()
            for item in loop_rounds
            if isinstance(item, dict) and isinstance(item.get("loop_decision"), dict)
        }
        if (
            recovery_mode != case.expected_recovery_mode
            and case.expected_recovery_mode not in historical_recovery_decisions
        ):
            failures.append(
                f"recovery_mode expected {case.expected_recovery_mode} but got {recovery_mode or 'EMPTY'}"
            )

    if case.expected_latest_branch_decision:
        latest_branch_decision = str(branch_recovery.get("latest_branch_decision", "")).strip()
        if (
            latest_branch_decision != case.expected_latest_branch_decision
            and case.expected_latest_branch_decision not in branch_decisions
        ):
            failures.append(
                f"latest_branch_decision expected {case.expected_latest_branch_decision} but got {latest_branch_decision or branch_decisions or 'EMPTY'}"
            )

    has_counterfactual = bool(counterfactual_summary.get("has_counterfactual_recheck"))
    if has_counterfactual != case.expect_counterfactual:
        failures.append(
            f"has_counterfactual_recheck expected {case.expect_counterfactual} but got {has_counterfactual}"
        )

    resumed = bool(resume_checkpoint.get("resumed_from_checkpoint"))
    if resumed != case.expect_resumed_from_checkpoint:
        failures.append(
            f"resumed_from_checkpoint expected {case.expect_resumed_from_checkpoint} but got {resumed}"
        )

    if len(loop_rounds) < case.min_loop_round_count:
        failures.append(
            f"loop_round_count expected >= {case.min_loop_round_count} but got {len(loop_rounds)}"
        )
    if str(verifier_gate_policy.get("final_loop_decision", "")).strip() != final_decision:
        failures.append(
            "verifier_gate_policy.final_loop_decision is not aligned with control_loop.final_decision"
        )
    if not str(verifier_gate_policy.get("report_gate_action", "")).strip():
        failures.append("verifier_gate_policy.report_gate_action is missing")

    return {
        "case_key": case.case_key,
        "title": case.title,
        "passed": not failures,
        "failures": failures,
        "actual": {
            "final_decision": final_decision,
            "global_decision": global_decision,
            "recovery_mode": str(
                stop_contract.get("recovery_mode")
                or control_loop.get("recovery_mode")
                or ""
            ).strip(),
            "latest_branch_decision": str(branch_recovery.get("latest_branch_decision", "")).strip(),
            "has_counterfactual_recheck": has_counterfactual,
            "resumed_from_checkpoint": resumed,
            "loop_round_count": len(loop_rounds),
            "loop_gate_action": str(verifier_gate_policy.get("loop_gate_action", "")).strip(),
            "final_loop_decision": str(verifier_gate_policy.get("final_loop_decision", "")).strip(),
            "report_gate_action": str(verifier_gate_policy.get("report_gate_action", "")).strip(),
        },
    }
