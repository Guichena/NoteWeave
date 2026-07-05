from __future__ import annotations

from dataclasses import dataclass

from app.branch import plan_branch_recovery
from app.extractor import extract_evidence_cards
from app.llm_client import LlmClient
from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchEvidenceCard,
    ResearchLoopDecision,
    ResearchLoopRoundSummary,
    ResearchPlan,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchStateLedger,
    ResearchTaskInput,
)
from app.read_adapters import run_research_read
from app.search_adapters import run_research_search
from app.state import build_state_ledger
from app.verifier import run_global_verifier, run_local_verifier


@dataclass
class ResearchRoundArtifacts:
    search_hits: list[ResearchSearchHit]
    read_windows: list[ResearchReadWindow]
    evidence_cards: list[ResearchEvidenceCard]
    ledger: ResearchStateLedger
    local_result: LocalVerifierResult
    branch_decisions: list[ResearchBranchDecision]
    global_result: GlobalVerifierResult


@dataclass
class ResearchLoopResult:
    plan: ResearchPlan
    artifacts: ResearchRoundArtifacts
    rounds: list[ResearchLoopRoundSummary]
    final_decision: ResearchLoopDecision


def run_research_loop(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    llm_client: LlmClient | None = None,
) -> ResearchLoopResult:
    max_rounds = _max_loop_rounds(plan)
    rounds: list[ResearchLoopRoundSummary] = []
    active_plan = plan
    latest_artifacts: ResearchRoundArtifacts | None = None
    latest_decision: ResearchLoopDecision | None = None

    for round_no in range(1, max_rounds + 1):
        artifacts = _run_single_round(
            task_input=task_input,
            plan=active_plan,
            llm_client=llm_client,
        )
        has_conflict = any(
            card.relation_type == "CONFLICTS"
            for card in artifacts.evidence_cards
        )
        decision = evaluate_loop_decision(
            plan=active_plan,
            round_no=round_no,
            search_hit_count=len(artifacts.search_hits),
            read_window_count=len(artifacts.read_windows),
            evidence_card_count=len(artifacts.evidence_cards),
            has_conflict=has_conflict,
            global_result=artifacts.global_result,
        )
        rounds.append(
            ResearchLoopRoundSummary(
                round_no=round_no,
                search_hit_count=len(artifacts.search_hits),
                read_window_count=len(artifacts.read_windows),
                evidence_card_count=len(artifacts.evidence_cards),
                branch_decision=(
                    artifacts.branch_decisions[0].decision
                    if artifacts.branch_decisions
                    else "NO_BRANCH"
                ),
                global_decision=artifacts.global_result.decision,
                loop_decision=decision,
            )
        )
        latest_artifacts = artifacts
        latest_decision = decision
        if not decision.should_continue:
            break
        active_plan = _augment_plan_for_next_round(active_plan, decision)

    if latest_artifacts is None or latest_decision is None:
        latest_artifacts = _run_single_round(
            task_input=task_input,
            plan=active_plan,
            llm_client=llm_client,
        )
        latest_decision = evaluate_loop_decision(
            plan=active_plan,
            round_no=1,
            search_hit_count=len(latest_artifacts.search_hits),
            read_window_count=len(latest_artifacts.read_windows),
            evidence_card_count=len(latest_artifacts.evidence_cards),
            has_conflict=any(card.relation_type == "CONFLICTS" for card in latest_artifacts.evidence_cards),
            global_result=latest_artifacts.global_result,
        )

    return ResearchLoopResult(
        plan=active_plan,
        artifacts=latest_artifacts,
        rounds=rounds,
        final_decision=latest_decision,
    )


def evaluate_loop_decision(
    plan: ResearchPlan,
    round_no: int,
    search_hit_count: int,
    read_window_count: int,
    evidence_card_count: int,
    has_conflict: bool,
    global_result: GlobalVerifierResult,
) -> ResearchLoopDecision:
    max_rounds = _max_loop_rounds(plan)
    if search_hit_count == 0:
        return ResearchLoopDecision(
            decision="EXPAND_SOURCE_SCOPE",
            reason="NO_SEARCH_HITS",
            round_no=round_no,
            should_continue=False,
            recovery_actions=["attach workspace sources or enable external search before rerun"],
        )

    if round_no >= max_rounds and global_result.decision != "READY_TO_WRITE":
        return ResearchLoopDecision(
            decision="WRITE_WITH_GUARDRAILS",
            reason="LOOP_BUDGET_EXHAUSTED",
            round_no=round_no,
            should_continue=False,
            recovery_actions=["surface missing evidence and uncertainty in the final report"],
        )

    if has_conflict and round_no < max_rounds:
        return ResearchLoopDecision(
            decision="COUNTERFACTUAL_RECHECK",
            reason="CONFLICTING_EVIDENCE",
            round_no=round_no,
            should_continue=True,
            recovery_actions=["run one bounded counterfactual recheck before synthesis"],
        )

    if evidence_card_count <= 0 and round_no < max_rounds:
        if read_window_count > 0:
            decision = "EXTRACT_AGAIN"
            reason = "READ_WINDOWS_WITHOUT_EVIDENCE"
            action = "rerun evidence extraction with stricter schema"
        else:
            decision = "READ_MORE"
            reason = "SEARCH_HITS_WITHOUT_READ_WINDOWS"
            action = "open one more read window before synthesis"
        return ResearchLoopDecision(
            decision=decision,
            reason=reason,
            round_no=round_no,
            should_continue=True,
            recovery_actions=[action],
        )

    if round_no >= max_rounds and has_conflict:
        return ResearchLoopDecision(
            decision="WRITE_WITH_GUARDRAILS",
            reason="CONFLICT_RECHECK_BUDGET_EXHAUSTED",
            round_no=round_no,
            should_continue=False,
            recovery_actions=["write with explicit conflict and uncertainty section"],
        )

    return ResearchLoopDecision(
        decision="SYNTHESIZE_REPORT",
        reason="STOP_CONTRACT_SATISFIED",
        round_no=round_no,
        should_continue=False,
        recovery_actions=[],
    )


def _run_single_round(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    llm_client: LlmClient | None,
) -> ResearchRoundArtifacts:
    search_hits = run_research_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    evidence_cards = extract_evidence_cards(
        task_input,
        plan,
        read_windows,
        llm_client=llm_client,
    )
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, evidence_cards)
    local_result = run_local_verifier(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        evidence_cards,
        llm_client=llm_client,
    )
    branch_decisions = plan_branch_recovery(
        search_hits,
        read_windows,
        evidence_cards,
        local_result,
    )
    global_result = run_global_verifier(local_result, ledger, branch_decisions)
    return ResearchRoundArtifacts(
        search_hits=search_hits,
        read_windows=read_windows,
        evidence_cards=evidence_cards,
        ledger=ledger,
        local_result=local_result,
        branch_decisions=branch_decisions,
        global_result=global_result,
    )


def _augment_plan_for_next_round(
    plan: ResearchPlan,
    decision: ResearchLoopDecision,
) -> ResearchPlan:
    if decision.decision == "COUNTERFACTUAL_RECHECK":
        extra_query = f"{plan.normalized_question} :: counterfactual recheck round {decision.round_no + 1}"
    elif decision.decision == "EXTRACT_AGAIN":
        extra_query = f"{plan.normalized_question} :: evidence extraction retry round {decision.round_no + 1}"
    else:
        extra_query = f"{plan.normalized_question} :: recovery round {decision.round_no + 1}"

    query_set = list(plan.query_set)
    if extra_query not in query_set:
        query_set.append(extra_query)
    notes = list(plan.notes)
    notes.append(f"loop recovery: {decision.decision} because {decision.reason}")
    return plan.model_copy(update={"query_set": query_set, "notes": notes})


def _max_loop_rounds(plan: ResearchPlan) -> int:
    return max(1, int(plan.stop_contract.get("max_loop_rounds", 2)))
