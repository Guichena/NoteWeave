from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from typing import Callable
import time

from app.branch import plan_branch_recovery
from app.cell_recovery_policy import (
    CellRecoveryAction,
    CellRecoveryBudget,
    CellRecoveryHistory,
    CellRecoveryThresholds,
    decide,
)
from app.cell_recovery_runtime import (
    CellRecoveryBudgetLedger,
    CellRecoveryDirective,
    CellRecoveryOutcome,
    CellRecoveryRuntime,
    apply_cell_recovery_directive,
    build_cell_recovery_directive,
    plan_digest,
    reconcile_recovery_mode,
    resolve_loop_recovery_decision,
)
from app.extraction_result import ExtractionTerminationReason
from app.fetch_adapters import run_research_fetch
from app.llm_client import LlmClient
from app.loop_stop_guard import (
    CELL_BOUND_REASON_CODES,
    DEFAULT_MAX_REPEATED_FAILURES,
    LoopStopDecision,
    LoopStopGuardInputs,
    LoopStopGuardResult,
    attempt_limit_cell_ids,
    dominant_rejection_reason,
    evaluate_loop_stop_guard,
    freeze_unresolved_cells,
    provider_failure_signal,
    reason_category,
    unresolved_cell_ids,
)
from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchEvidenceCard,
    ResearchFetchedDocument,
    ResearchLoopDecision,
    ResearchLoopRoundSummary,
    ResearchPlan,
    ResearchProgressEvent,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchStateLedger,
    ResearchStepTrace,
    ResearchTaskInput,
)
from app.research_tools import ResearchRoundArtifacts, ResearchToolbox
from app.state import build_state_ledger
from app.verifier import run_global_verifier, run_local_verifier

TERMINAL_DECISIONS = {
    "SYNTHESIZE_REPORT",
    "WRITE_WITH_GUARDRAILS",
    "EXPAND_SOURCE_SCOPE",
}

#: DR-108: 恢复决策族的**呈现文本**。决策族本身不再由本模块决定（唯一权威是
#: ``CellRecoveryPolicy``，经 ``resolve_loop_recovery_decision`` 收敛），这里只把
#: 已确定的族渲染成对调用方可见的恢复动作文案。
_RECOVERY_FAMILY_ACTIONS = {
    "EXTRACT_AGAIN": "rerun evidence extraction with stricter schema",
    "READ_MORE": "open one more read window before synthesis",
    "COUNTERFACTUAL_RECHECK": "run one bounded counterfactual recheck before synthesis",
}


@dataclass
class ResearchLoopResult:
    plan: ResearchPlan
    artifacts: ResearchRoundArtifacts
    rounds: list[ResearchLoopRoundSummary]
    final_decision: ResearchLoopDecision
    resume_context_summary: dict[str, object] | None = None
    # DR-202: 每次 Cell 恢复决策的结构化轨迹（动作 / reason_code / 影响 Cell /
    # 预算扣减 / 是否发生外部调用）。集成测试直接断言此字段。
    cell_recovery_trace: list[dict[str, object]] = field(default_factory=list)
    # DR-108: 每轮**唯一指令**（生效 recovery_mode 及其来源 / 外部动作是否被撤销 /
    # 停止原因码），与 ``cell_recovery_trace`` 按轮对齐（每轮一条）。
    cell_recovery_directives: list[dict[str, object]] = field(default_factory=list)
    # DR-204: 停止保护的权威原因码与类别（见 app/loop_stop_guard.py 的全量词表）。
    # 基础设施类原因（PROVIDER_NOT_CONFIGURED / PROVIDER_UNAVAILABLE）用于回答
    # 「这次循环为什么停」，避免把 Provider 故障伪装成证据不足。
    stop_reason_code: str = ""
    stop_reason_category: str = ""
    # DR-204: 有界性证据——实际发生的外部恢复动作次数，以及可证明的明确上界
    # （min(max_loop_rounds, max_attempts_per_cell) * cell_count）。
    external_recovery_call_count: int = 0
    external_recovery_call_upper_bound: int = 0


@dataclass
class ResumeCheckpointContext:
    plan: ResearchPlan
    artifacts: ResearchRoundArtifacts
    rounds: list[ResearchLoopRoundSummary]
    final_decision: ResearchLoopDecision
    summary: dict[str, object]


def run_research_loop(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    llm_client: LlmClient | None = None,
    progress_callback: Callable[[ResearchProgressEvent], None] | None = None,
    checkpoint_callback: Callable[[dict[str, object]], None] | None = None,
    cancellation_checker: Callable[[], None] | None = None,
) -> ResearchLoopResult:
    max_rounds = _max_loop_rounds(plan)
    rounds: list[ResearchLoopRoundSummary] = []
    active_plan = plan
    latest_artifacts: ResearchRoundArtifacts | None = None
    latest_decision: ResearchLoopDecision | None = None
    start_round = 1
    run_started_at = time.monotonic()
    initial_wall_clock_consumed = _wall_clock_seconds_consumed(plan)
    cancellation_setter = getattr(llm_client, "set_cancellation_checker", None)
    if callable(cancellation_setter):
        cancellation_setter(cancellation_checker)
    toolbox = ResearchToolbox(
        llm_client=llm_client,
        progress_callback=progress_callback,
        cancellation_checker=cancellation_checker,
    )

    resume_context = _restore_resume_context(task_input, plan)
    if resume_context is not None:
        active_plan = resume_context.plan
        max_rounds = _max_loop_rounds(active_plan)
        initial_wall_clock_consumed = _wall_clock_seconds_consumed(active_plan)
        rounds = list(resume_context.rounds)
        latest_artifacts = resume_context.artifacts
        latest_decision = resume_context.final_decision
        if (
            latest_decision.decision in TERMINAL_DECISIONS
            or len(rounds) >= max_rounds
            or not latest_decision.should_continue
        ):
            return ResearchLoopResult(
                plan=active_plan,
                artifacts=latest_artifacts,
                rounds=rounds,
                final_decision=latest_decision,
                resume_context_summary=resume_context.summary,
                stop_reason_code=str(latest_decision.reason or ""),
                stop_reason_category=reason_category(latest_decision.reason),
            )
        if _wall_clock_budget_exhausted(
            active_plan,
            initial_consumed=initial_wall_clock_consumed,
            run_started_at=run_started_at,
        ):
            deadline_decision = _wall_clock_exhausted_decision(
                round_no=max((item.round_no for item in rounds), default=0),
                artifacts=latest_artifacts,
            )
            if checkpoint_callback is not None:
                checkpoint_callback(
                    _build_runtime_checkpoint(
                        task_input=task_input,
                        plan=active_plan,
                        artifacts=latest_artifacts,
                        rounds=rounds,
                        decision=deadline_decision,
                    )
                )
            return ResearchLoopResult(
                plan=active_plan,
                artifacts=latest_artifacts,
                rounds=rounds,
                final_decision=deadline_decision,
                resume_context_summary=resume_context.summary,
                stop_reason_code=str(deadline_decision.reason or ""),
                stop_reason_category=reason_category(deadline_decision.reason),
            )
        active_plan = _augment_plan_for_next_round(
            active_plan,
            latest_decision,
            latest_artifacts,
        )
        start_round = max(1, max((item.round_no for item in rounds), default=0) + 1)

    # DR-202: 每轮结束后把「缺证据 / 未收敛 Cell」的恢复决策集中委托给
    # CellRecoveryPolicy。历史与预算账本跨轮累积，因此动作序列是确定性的。
    max_attempts_per_cell = max(
        1, int(active_plan.stop_contract.get("max_retry_per_cell", 3) or 3)
    )
    # DR-204: 「同一失败原因连续重复」阈值；允许 plan 覆盖，默认值集中在本模块常量。
    max_repeated_failures = max(
        1,
        int(
            active_plan.stop_contract.get(
                "max_repeated_failures", DEFAULT_MAX_REPEATED_FAILURES
            )
            or DEFAULT_MAX_REPEATED_FAILURES
        ),
    )
    recovery_runtime = CellRecoveryRuntime(
        budget_ledger=CellRecoveryBudgetLedger.from_plan(active_plan),
        thresholds=CellRecoveryThresholds(
            max_attempts_per_cell=max_attempts_per_cell
        ),
    )
    cell_recovery_trace: list[dict[str, object]] = []
    cell_recovery_directives: list[dict[str, object]] = []
    # DR-204: 「同一失败原因连续重复」的跨轮计数；判定阈值集中在 loop_stop_guard。
    repeated_failure_reason = ""
    repeated_failure_streak = 0
    last_guard_result: LoopStopGuardResult | None = None
    # DR-108: 每轮的**唯一指令**（recovery_mode / 外部动作 / 停止原因的收敛产物）。
    last_directive: CellRecoveryDirective | None = None

    for round_no in range(start_round, max_rounds + 1):
        if cancellation_checker is not None:
            cancellation_checker()
        if _wall_clock_budget_exhausted(
            active_plan,
            initial_consumed=initial_wall_clock_consumed,
            run_started_at=run_started_at,
        ) and latest_artifacts is not None:
            latest_decision = _wall_clock_exhausted_decision(
                round_no=max(0, round_no - 1),
                artifacts=latest_artifacts,
            )
            if checkpoint_callback is not None:
                checkpoint_callback(
                    _build_runtime_checkpoint(
                        task_input=task_input,
                        plan=active_plan,
                        artifacts=latest_artifacts,
                        rounds=rounds,
                        decision=latest_decision,
                    )
                )
            # DR-108: 本轮没有产生指令（循环在开始新一轮前就被墙钟短路），
            # 停止原因回退到墙钟决策本身，避免沿用上一轮的指令。
            last_directive = None
            break
        artifacts = toolbox.execute_round(
            task_input=task_input,
            plan=active_plan,
            round_no=round_no,
            prior_artifacts=latest_artifacts,
        )
        # DR-202: 旧规则（按 repair_count 冻结超 retry 预算的 cell）已收敛进
        # CellRecoveryPolicy；这里只做编排：预算检查先于外部动作，已结算 Cell 不回退。
        recovery_outcome = recovery_runtime.apply(
            artifacts.ledger,
            round_no=round_no,
            rejection_summary=_extraction_rejection_summary(artifacts),
            read_window_count=len(artifacts.read_windows),
            plan_digest=plan_digest(active_plan),
        )
        artifacts = replace(artifacts, ledger=recovery_outcome.ledger)
        has_conflict_cards = any(
            card.relation_type == "CONFLICTS"
            for card in artifacts.evidence_cards
        )
        has_conflict = has_conflict_cards and any(
            decision.decision == "COUNTERFACTUAL_RECHECK"
            and decision.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
            for decision in artifacts.branch_decisions
        )
        decision = evaluate_loop_decision(
            plan=active_plan,
            round_no=round_no,
            search_hit_count=len(artifacts.search_hits),
            read_window_count=len(artifacts.read_windows),
            evidence_card_count=len(artifacts.evidence_cards),
            has_conflict=has_conflict,
            ledger=artifacts.ledger,
            global_result=artifacts.global_result,
            recovery_outcome=recovery_outcome,
        )
        _record_wall_clock_consumption(
            active_plan,
            initial_consumed=initial_wall_clock_consumed,
            run_started_at=run_started_at,
        )
        wall_clock_exhausted = _wall_clock_budget_exhausted(
            active_plan,
            initial_consumed=initial_wall_clock_consumed,
            run_started_at=run_started_at,
        )
        if wall_clock_exhausted:
            decision = _wall_clock_exhausted_decision(
                round_no=round_no,
                artifacts=artifacts,
            )
        # DR-204: 停止保护集中判定。轮次 / 墙钟 / 重复失败 / Cell 尝试上限 /
        # Provider 基础设施失败全部由 loop_stop_guard 一处裁决，loop 侧不再散落 if。
        repeated_failure_reason, repeated_failure_streak = _update_failure_streak(
            previous_reason=repeated_failure_reason,
            previous_streak=repeated_failure_streak,
            artifacts=artifacts,
        )
        last_guard_result = _evaluate_loop_guard(
            round_no=round_no,
            max_rounds=max_rounds,
            artifacts=artifacts,
            decision=decision,
            recovery_outcome=recovery_outcome,
            llm_client=llm_client,
            max_attempts_per_cell=max_attempts_per_cell,
            max_repeated_failures=max_repeated_failures,
            wall_clock_exhausted=wall_clock_exhausted,
            rejection_reason=repeated_failure_reason,
            repeated_failure_streak=repeated_failure_streak,
            observed_cell_attempts=recovery_runtime.attempts_by_cell,
        )
        if last_guard_result.freeze_cell_ids:
            artifacts = replace(
                artifacts,
                ledger=freeze_unresolved_cells(
                    artifacts.ledger,
                    last_guard_result.freeze_cell_ids,
                    last_guard_result.stop_reason_code or "REPEATED_CELL_FAILURE",
                ),
            )
        run_stop = last_guard_result.run_stop
        if run_stop is not None and run_stop.is_infrastructure:
            # DR-107: Provider 未配置 / 不可用属基础设施失败，立即停止且不得伪装成证据不足。
            decision = _infrastructure_stop_decision(run_stop, artifacts)
        elif run_stop is not None and run_stop.reason_code in CELL_BOUND_REASON_CODES:
            decision = _cell_bound_stop_decision(decision, artifacts, run_stop)
        else:
            # DR-202: 若恢复策略判定没有任何可执行的外部动作（全部 FREEZE / STOP），
            # 就不允许 loop 再发起一轮外部调用。
            decision = _apply_cell_recovery_stop_guard(decision, recovery_outcome, artifacts)
        # DR-108: 三套判断（决策族 / 停止保护 / Cell 级恢复）在此收敛成唯一指令，
        # 之后「本轮生效的 recovery_mode」「是否还有外部动作」「停止原因」全部
        # 只从这一条指令读取，不再各自重写。
        guarded_reason = _guarded_stop_reason_code(last_guard_result)
        resolved_reason = guarded_reason or str(decision.reason or "")
        last_directive = build_cell_recovery_directive(
            round_no=round_no,
            decision_family=decision.decision,
            decision_reason=str(decision.reason or ""),
            recovery_outcome=recovery_outcome,
            stop_reason_code=resolved_reason,
            stop_reason_category=reason_category(resolved_reason),
            freeze_cell_ids=last_guard_result.freeze_cell_ids,
            external_call_upper_bound=last_guard_result.external_call_upper_bound,
            external_call_allowed=last_guard_result.run_stop is None,
        )
        recovery_outcome = apply_cell_recovery_directive(recovery_outcome, last_directive)
        cell_recovery_trace.extend(entry.as_dict() for entry in recovery_outcome.trace)
        cell_recovery_directives.append(last_directive.as_dict())
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
        if checkpoint_callback is not None:
            checkpoint_callback(
                _build_runtime_checkpoint(
                    task_input=task_input,
                    plan=active_plan,
                    artifacts=artifacts,
                    rounds=rounds,
                    decision=decision,
                    cell_recovery_trace=cell_recovery_trace,
                    cell_recovery_directives=cell_recovery_directives,
                )
            )
        if not decision.should_continue:
            break
        active_plan = _augment_plan_for_next_round(
            active_plan,
            decision,
            artifacts,
            # DR-108: 用指令里已收敛的生效模式，而不是 Cell 级策略的原始请求，
            # 这样「请求 vs 生效」不会再出现两套取值。
            recovery_mode=(
                last_directive.recovery_mode if last_directive is not None else ""
            ),
        )

    if latest_artifacts is None or latest_decision is None:
        latest_artifacts = toolbox.execute_round(
            task_input=task_input,
            plan=active_plan,
            round_no=1,
        )
        fallback_outcome = recovery_runtime.apply(
            latest_artifacts.ledger,
            round_no=1,
            rejection_summary=_extraction_rejection_summary(latest_artifacts),
            read_window_count=len(latest_artifacts.read_windows),
            plan_digest=plan_digest(active_plan),
        )
        latest_artifacts = replace(latest_artifacts, ledger=fallback_outcome.ledger)
        cell_recovery_trace.extend(
            entry.as_dict() for entry in fallback_outcome.trace
        )
        latest_decision = evaluate_loop_decision(
            plan=active_plan,
            round_no=1,
            search_hit_count=len(latest_artifacts.search_hits),
            read_window_count=len(latest_artifacts.read_windows),
            evidence_card_count=len(latest_artifacts.evidence_cards),
            has_conflict=any(
                card.relation_type == "CONFLICTS"
                for card in latest_artifacts.evidence_cards
            ),
            ledger=latest_artifacts.ledger,
            global_result=latest_artifacts.global_result,
            recovery_outcome=fallback_outcome,
        )

    return ResearchLoopResult(
        plan=active_plan,
        artifacts=latest_artifacts,
        rounds=rounds,
        final_decision=latest_decision,
        resume_context_summary=resume_context.summary if resume_context is not None else None,
        cell_recovery_trace=cell_recovery_trace,
        cell_recovery_directives=cell_recovery_directives,
        stop_reason_code=_resolved_stop_reason_code(
            latest_decision, last_guard_result, last_directive
        ),
        stop_reason_category=reason_category(
            _resolved_stop_reason_code(latest_decision, last_guard_result, last_directive)
        ),
        external_recovery_call_count=_external_recovery_call_count(cell_recovery_trace),
        external_recovery_call_upper_bound=(
            last_guard_result.external_call_upper_bound
            if last_guard_result is not None
            else 0
        ),
    )


def _guarded_stop_reason_code(guard_result: LoopStopGuardResult | None) -> str:
    """停止保护中**优先于循环决策**的原因码：基础设施类与 Cell 级保护。"""
    if guard_result is None or guard_result.run_stop is None:
        return ""
    guarded = guard_result.run_stop
    if guarded.is_infrastructure or guarded.is_cell_bound:
        return guarded.reason_code
    return ""


def _resolved_stop_reason_code(
    decision: ResearchLoopDecision,
    guard_result: LoopStopGuardResult | None,
    directive: CellRecoveryDirective | None = None,
) -> str:
    """停止原因的权威取值：DR-108 指令优先，其次基础设施 / Cell 级保护，最后循环决策。"""
    if directive is not None:
        return directive.stop_reason_code
    return _guarded_stop_reason_code(guard_result) or str(decision.reason or "")


def _external_recovery_call_count(trace: list[dict[str, object]]) -> int:
    return sum(1 for entry in trace if bool(entry.get("external_call")))


def _update_failure_streak(
    *,
    previous_reason: str,
    previous_streak: int,
    artifacts: ResearchRoundArtifacts,
) -> tuple[str, int]:
    """跨轮统计「同一主导失败原因」的连续出现次数。

    只有在完全没有已接受证据时才累计；一旦有证据或已收敛为 VERIFIED，计数归零。
    """
    has_evidence = bool(artifacts.evidence_cards) or bool(
        getattr(artifacts.ledger, "verified_row_count", 0)
    )
    if has_evidence:
        return "", 0
    reason = dominant_rejection_reason(_extraction_rejection_summary(artifacts))
    if not reason:
        return "", 0
    if reason == previous_reason:
        return reason, previous_streak + 1
    return reason, 1


def _evaluate_loop_guard(
    *,
    round_no: int,
    max_rounds: int,
    artifacts: ResearchRoundArtifacts,
    decision: ResearchLoopDecision,
    recovery_outcome: CellRecoveryOutcome,
    llm_client: LlmClient | None,
    max_attempts_per_cell: int,
    max_repeated_failures: int,
    wall_clock_exhausted: bool,
    rejection_reason: str,
    repeated_failure_streak: int,
    observed_cell_attempts: Mapping[str, int] | None = None,
) -> LoopStopGuardResult:
    """收集全部信号并调用唯一的停止保护判定点。"""
    llm_provider_failure = provider_failure_signal(llm_client)
    return evaluate_loop_stop_guard(
        LoopStopGuardInputs(
            round_no=round_no,
            max_rounds=max_rounds,
            cell_count=len(artifacts.ledger.cells),
            max_attempts_per_cell=max_attempts_per_cell,
            max_repeated_failures=max_repeated_failures,
            search_hit_count=len(artifacts.search_hits),
            read_window_count=len(artifacts.read_windows),
            evidence_card_count=len(artifacts.evidence_cards),
            verified_row_count=int(getattr(artifacts.ledger, "verified_row_count", 0) or 0),
            wall_clock_exhausted=wall_clock_exhausted,
            llm_provider_configured=llm_client is not None,
            llm_provider_failure=llm_provider_failure,
            rejection_reason=rejection_reason,
            repeated_failure_streak=repeated_failure_streak,
            has_external_recovery_action=recovery_outcome.has_external_action,
            decision_should_continue=decision.should_continue,
            decision_reason=str(decision.reason or ""),
            unresolved_cell_ids=unresolved_cell_ids(artifacts.ledger),
            attempt_limit_cell_ids=attempt_limit_cell_ids(
                artifacts.ledger,
                max_attempts_per_cell=max_attempts_per_cell,
                observed_attempts=observed_cell_attempts,
            ),
            halted_cell_ids=tuple(recovery_outcome.halted_cell_ids),
        )
    )


def _infrastructure_stop_decision(
    stop: LoopStopDecision,
    artifacts: ResearchRoundArtifacts,
) -> ResearchLoopDecision:
    """基础设施类终止：明确的 machine-readable 标记，且不得落成证据不足 / 完成态。"""
    has_coverage = bool(getattr(artifacts.ledger, "verified_row_count", 0))
    actions = [
        "verify the configured research providers and credentials before rerun",
        f"infrastructure_failure:{stop.reason_code}",
    ]
    if not has_coverage:
        actions.append(
            f"abandon_condition:{stop.reason_code}_WITHOUT_VERIFIED_COVERAGE"
        )
    # terminal_disposition 仅作建议；DR-305 的 RunCompletionGate 是业务终态唯一权威。
    # 基础设施失败需要人（运维）修复配置，因此建议 HUMAN_HANDOFF，而不是业务放弃。
    return ResearchLoopDecision(
        decision="WRITE_WITH_GUARDRAILS",
        reason=stop.reason_code,
        round_no=max(1, int(stop.round_no)),
        should_continue=False,
        recovery_actions=actions,
        terminal_disposition="HUMAN_HANDOFF",
        handoff_required=True,
        abandon_reason="",
    )


def _cell_bound_stop_decision(
    decision: ResearchLoopDecision,
    artifacts: ResearchRoundArtifacts,
    stop: LoopStopDecision,
) -> ResearchLoopDecision:
    """Cell 级停止保护：终止恢复并显式暴露未收敛 Cell（不再发起外部动作）。"""
    has_coverage = bool(getattr(artifacts.ledger, "verified_row_count", 0))
    frozen_ids = [
        cell.cell_id for cell in artifacts.ledger.cells if str(cell.status or "") == "FROZEN"
    ]
    actions = list(decision.recovery_actions)
    if frozen_ids:
        actions.append("surface unresolved cells: " + ", ".join(frozen_ids[:4]))
    if not has_coverage:
        actions.append(f"abandon_condition:{stop.reason_code}_WITHOUT_VERIFIED_COVERAGE")
    return ResearchLoopDecision(
        decision="WRITE_WITH_GUARDRAILS",
        reason=stop.reason_code,
        round_no=max(1, int(stop.round_no)),
        should_continue=False,
        recovery_actions=actions,
        # terminal_disposition 仅作建议；DR-305 的 RunCompletionGate 是业务终态唯一权威。
        terminal_disposition="GUARDED_COMPLETE" if has_coverage else "ABANDON",
        handoff_required=False,
        abandon_reason=(
            "" if has_coverage else f"{stop.reason_code}_WITHOUT_VERIFIED_COVERAGE"
        ),
    )


def _extraction_rejection_summary(artifacts: ResearchRoundArtifacts) -> dict[str, int]:
    """本轮抽取的**卡片级**拒绝原因计数，直接进入 Cell 恢复决策。

    重要边界：这里刻意**不**把响应级的 ``LLM_UNAVAILABLE`` 注入为 Cell 信号。
    原因：``run_research_loop`` 在 RULE 模式（``llm_client is None``）下不构建本地
    默认 LLM，因此每一次 ``LLM_UNAVAILABLE`` 都是「运行环境未配置 Provider」这一
    **Run 级基础设施状态**，而不是单个 Cell 的抽取拒绝。把它当作 Cell 信号会让所有
    未填满 Cell 立即 FREEZE，从而掩盖真正的根因。

    DR-204 / DR-107 已把该 Run 级状态上提为停止保护：当 Provider 未配置且本轮确实
    到达抽取阶段时，``app.loop_stop_guard`` 会以 ``PROVIDER_NOT_CONFIGURED``
    （基础设施类）终止循环，而不是把它记成证据不足。``CellRecoveryPolicy`` 本身
    仍完整支持 ``LLM_UNAVAILABLE`` 分支，运行时 API 也会如实执行（见
    ``cell_recovery_runtime.apply_cell_recovery_policy``）。
    """
    result = getattr(artifacts, "extraction_result", None)
    if result is None:
        return {}
    if result.termination_reason == ExtractionTerminationReason.LLM_UNAVAILABLE:
        # 运行级配置缺失：交给既有 loop 决策，不降格为 Cell 级信号。
        return {}
    return dict(result.rejection_counts())


#: 这些 loop decision 由 Cell 级恢复驱动；当策略判定无外部动作可做时不得继续。
_CELL_DRIVEN_RECOVERY_DECISIONS = frozenset(
    {"READ_MORE", "EXTRACT_AGAIN", "COUNTERFACTUAL_RECHECK"}
)


def _apply_cell_recovery_stop_guard(
    decision: ResearchLoopDecision,
    outcome: CellRecoveryOutcome,
    artifacts: ResearchRoundArtifacts,
) -> ResearchLoopDecision:
    """恢复策略判定「无外部动作可做」时，阻止 loop 再发起一轮外部调用。

    仅当以下条件同时成立才改写 decision：

    - decision 本来要继续，且属于 Cell 驱动的恢复决策族；
    - 本轮没有任何外部恢复动作（全部 FREEZE / STOP / 已结算）；
    - 至少有一个 Cell 被冻结或停止（确实存在未解决 Cell）。
    """
    if not decision.should_continue:
        return decision
    if decision.decision not in _CELL_DRIVEN_RECOVERY_DECISIONS:
        return decision
    if outcome.has_external_action or not outcome.halted_cell_ids:
        return decision
    has_verified_coverage = bool(getattr(artifacts.ledger, "verified_row_count", 0))
    terminal_disposition = (
        "GUARDED_COMPLETE" if has_verified_coverage else "HUMAN_HANDOFF"
    )
    return decision.model_copy(
        update={
            "decision": "WRITE_WITH_GUARDRAILS",
            "reason": "CELL_RECOVERY_HALTED",
            "should_continue": False,
            "recovery_actions": [
                *decision.recovery_actions,
                "surface unresolved cells: "
                + ", ".join(outcome.halted_cell_ids[:4]),
            ],
            "terminal_disposition": terminal_disposition,
            # 与 loop 其他调用点语义一致：仅 HUMAN_HANDOFF 需要人工接手。
            # 注意：DR-305 的 RunCompletionGate 是业务终态的唯一权威，这里的
            # terminal_disposition 只作建议值，不抢先定义业务终态。
            "handoff_required": terminal_disposition == "HUMAN_HANDOFF",
        }
    )


def _build_runtime_checkpoint(
    *,
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    artifacts: ResearchRoundArtifacts,
    rounds: list[ResearchLoopRoundSummary],
    decision: ResearchLoopDecision,
    cell_recovery_trace: list[dict[str, object]] | None = None,
    cell_recovery_directives: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    checkpoint_no = max(1, len(rounds))
    return {
        "checkpoint_no": checkpoint_no,
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "cursor": {"round_no": checkpoint_no, "next_round_no": checkpoint_no + 1},
        "task_id": task_input.task_id,
        "research_run_id": task_input.target_id,
        "plan": plan.model_dump(mode="json"),
        "search_hits": [item.model_dump(mode="json") for item in artifacts.search_hits],
        "fetched_documents": [item.model_dump(mode="json") for item in artifacts.fetched_documents],
        "read_windows": [item.model_dump(mode="json") for item in artifacts.read_windows],
        "evidence_cards": [item.model_dump(mode="json") for item in artifacts.evidence_cards],
        "state_ledger": artifacts.ledger.model_dump(mode="json"),
        "local_verifier": artifacts.local_result.model_dump(mode="json"),
        "global_verifier": artifacts.global_result.model_dump(mode="json"),
        "branch_decisions": [item.model_dump(mode="json") for item in artifacts.branch_decisions],
        "tool_traces": [item.model_dump(mode="json") for item in artifacts.tool_traces],
        "evidence_horizon_decisions": list(artifacts.evidence_horizon_decisions),
        "loop_rounds": [item.model_dump(mode="json") for item in rounds],
        "loop_decision": decision.model_dump(mode="json"),
        # DR-202: Cell 恢复决策轨迹随 checkpoint 一起持久化，供集成测试与复盘断言。
        "cell_recovery_trace": list(cell_recovery_trace or []),
        # DR-108: 每轮的收敛指令（生效模式 / 撤销的外部动作 / 停止原因）。
        "cell_recovery_directives": list(cell_recovery_directives or []),
        "budgets": {
            "max_loop_rounds": int(plan.stop_contract.get("max_loop_rounds", 1)),
            "max_retry_per_cell": int(plan.stop_contract.get("max_retry_per_cell", 1)),
            "max_wall_clock_seconds": _wall_clock_budget_seconds(plan),
            "wall_clock_seconds_consumed": _wall_clock_seconds_consumed(plan),
            "rounds_consumed": checkpoint_no,
        },
    }


def _wall_clock_budget_seconds(plan: ResearchPlan) -> float:
    """Return the task-wide runtime budget; zero deliberately disables it."""
    return max(0.0, float(plan.stop_contract.get("max_wall_clock_seconds", 0) or 0))


def _wall_clock_seconds_consumed(plan: ResearchPlan) -> float:
    return max(0.0, float(plan.stop_contract.get("wall_clock_seconds_consumed", 0.0) or 0.0))


def _wall_clock_budget_exhausted(
    plan: ResearchPlan,
    *,
    initial_consumed: float,
    run_started_at: float,
) -> bool:
    budget = _wall_clock_budget_seconds(plan)
    return bool(budget and initial_consumed + (time.monotonic() - run_started_at) >= budget)


def _record_wall_clock_consumption(
    plan: ResearchPlan,
    *,
    initial_consumed: float,
    run_started_at: float,
) -> None:
    # Persist the accumulated amount in the plan so a resumed task cannot
    # reset a task-wide deadline merely by creating a new worker process.
    plan.stop_contract["wall_clock_seconds_consumed"] = round(
        max(initial_consumed, initial_consumed + (time.monotonic() - run_started_at)),
        3,
    )


def _wall_clock_exhausted_decision(
    *,
    round_no: int,
    artifacts: ResearchRoundArtifacts,
) -> ResearchLoopDecision:
    has_conflict = any(card.relation_type == "CONFLICTS" for card in artifacts.evidence_cards)
    has_active_branch = any(
        item.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
        for item in artifacts.branch_decisions
    )
    has_verified_coverage = bool(getattr(artifacts.ledger, "verified_row_count", 0))
    actions = ["finish the current round and surface evidence gaps in the final report"]
    if not has_verified_coverage:
        actions.append("abandon_condition:WALL_CLOCK_BUDGET_EXHAUSTED_WITHOUT_VERIFIED_COVERAGE")
    if has_conflict or has_active_branch:
        actions.append("handoff_condition:COUNTERFACTUAL_CONFLICT_UNRESOLVED_AFTER_BUDGET")
    return ResearchLoopDecision(
        decision="WRITE_WITH_GUARDRAILS",
        reason="WALL_CLOCK_BUDGET_EXHAUSTED",
        round_no=max(1, round_no),
        should_continue=False,
        recovery_actions=actions,
        terminal_disposition=(
            "HUMAN_HANDOFF" if has_conflict or has_active_branch
            else "ABANDON" if not has_verified_coverage
            else "GUARDED_COMPLETE"
        ),
        handoff_required=has_conflict or has_active_branch,
        abandon_reason=(
            "WALL_CLOCK_BUDGET_EXHAUSTED_WITHOUT_VERIFIED_COVERAGE"
            if not has_verified_coverage else ""
        ),
    )


def evaluate_loop_decision(
    plan: ResearchPlan,
    round_no: int,
    search_hit_count: int,
    read_window_count: int,
    evidence_card_count: int,
    has_conflict: bool,
    ledger: ResearchStateLedger | None,
    global_result: GlobalVerifierResult,
    recovery_outcome: CellRecoveryOutcome | None = None,
) -> ResearchLoopDecision:
    max_rounds = _max_loop_rounds(plan)
    pending_requirements = _pending_requirement_progress(ledger)
    has_pending_conflict_requirement = any(
        item.requirement_type == "CONFLICT_FINDING"
        for item in pending_requirements
    )
    has_missing_requirements = any(
        item.status == "MISSING"
        for item in pending_requirements
    )
    has_partial_requirements = any(
        item.status == "PARTIAL"
        for item in pending_requirements
    )
    commitment_guard = evaluate_premature_commitment_guard(plan, ledger, evidence_card_count)
    if search_hit_count == 0:
        retrieval_mode = str(plan.stop_contract.get("retrieval_mode", "")).upper()
        if retrieval_mode == "WEB_ONLY":
            actions = ["verify the web search provider and broaden the discovery queries before rerun"]
        elif retrieval_mode == "WEB_PLUS_SEEDS":
            actions = ["broaden web discovery queries and review the selected seed sources before rerun"]
        elif retrieval_mode == "SOURCES_ONLY":
            actions = ["review or replace the selected seed sources before rerun"]
        else:
            actions = ["review the configured retrieval channels and broaden discovery before rerun"]
        no_evidence = evidence_card_count == 0
        if no_evidence:
            actions.append("abandon_condition:NO_SEARCH_HITS_WITHOUT_EVIDENCE")
        return ResearchLoopDecision(
            decision="EXPAND_SOURCE_SCOPE",
            reason="NO_SEARCH_HITS",
            round_no=round_no,
            should_continue=False,
            recovery_actions=actions,
            terminal_disposition="ABANDON" if no_evidence else "GUARDED_COMPLETE",
            abandon_reason="NO_SEARCH_HITS_WITHOUT_EVIDENCE" if no_evidence else "",
        )

    if round_no >= max_rounds and global_result.decision != "READY_TO_WRITE":
        actions = ["surface missing evidence and uncertainty in the final report"]
        if ledger is None or not getattr(ledger, "verified_row_count", 0):
            actions.append("abandon_condition:LOOP_BUDGET_EXHAUSTED_WITHOUT_VERIFIED_COVERAGE")
        if has_conflict:
            actions.append("handoff_condition:COUNTERFACTUAL_CONFLICT_UNRESOLVED_AFTER_BUDGET")
        no_verified_coverage = ledger is None or not getattr(ledger, "verified_row_count", 0)
        return ResearchLoopDecision(
            decision="WRITE_WITH_GUARDRAILS",
            reason="LOOP_BUDGET_EXHAUSTED",
            round_no=round_no,
            should_continue=False,
            recovery_actions=actions,
            terminal_disposition=(
                "HUMAN_HANDOFF" if has_conflict else "ABANDON" if no_verified_coverage else "GUARDED_COMPLETE"
            ),
            handoff_required=has_conflict,
            abandon_reason="LOOP_BUDGET_EXHAUSTED_WITHOUT_VERIFIED_COVERAGE" if no_verified_coverage else "",
        )

    if has_conflict and round_no < max_rounds:
        return ResearchLoopDecision(
            decision="COUNTERFACTUAL_RECHECK",
            reason="CONFLICTING_EVIDENCE",
            round_no=round_no,
            should_continue=True,
            recovery_actions=[
                "run one bounded counterfactual recheck before synthesis"
            ],
        )

    if evidence_card_count <= 0 and round_no < max_rounds:
        # DR-108: 「缺证据该做什么」不再由本函数按 ``read_window_count`` 自行判断。
        # 恢复动作的唯一权威是 CellRecoveryPolicy（经 CellRecoveryRuntime 编排）；
        # 本函数只消费策略给出的决策族 + reason_code。
        resolved = resolve_loop_recovery_decision(recovery_outcome)
        if resolved is not None:
            recovery_family, recovery_reason = resolved
            return ResearchLoopDecision(
                decision=recovery_family,
                reason=recovery_reason,
                round_no=round_no,
                should_continue=True,
                recovery_actions=[
                    _RECOVERY_FAMILY_ACTIONS.get(
                        recovery_family,
                        "continue bounded recovery for unresolved cells",
                    )
                ],
            )
        # 策略判定无外部动作可做（全部 FREEZE / STOP / 已结算）时不在此处发明动作；
        # 交由停止保护按 ``halted_cell_ids`` 裁决（CELL_RECOVERY_HALTED），并继续走
        # 通用循环控制（待满足要求 / 提前承诺保护 / 全局验证器未就绪）。

    if pending_requirements and round_no < max_rounds:
        if has_pending_conflict_requirement:
            return ResearchLoopDecision(
                decision="COUNTERFACTUAL_RECHECK",
                reason="REQUIRED_CONFLICT_FINDING_MISSING",
                round_no=round_no,
                should_continue=True,
                recovery_actions=[
                    "run one bounded counterfactual recheck for the missing conflict finding"
                ],
            )
        return ResearchLoopDecision(
            decision="READ_MORE",
            reason=(
                "REQUIRED_FINDINGS_MISSING"
                if has_missing_requirements
                else "REQUIRED_FINDINGS_PARTIAL"
                if has_partial_requirements
                else "REQUIRED_FINDINGS_PENDING"
            ),
            round_no=round_no,
            should_continue=True,
            recovery_actions=[
                "open targeted evidence windows for the missing required findings"
            ],
        )

    if commitment_guard["blocked"] and round_no < max_rounds:
        return ResearchLoopDecision(
            decision="READ_MORE",
            reason="PREMATURE_COMMITMENT_BLOCKED",
            round_no=round_no,
            should_continue=True,
            recovery_actions=[
                *[f"resolve:{reason}" for reason in commitment_guard["reason_codes"]],
                "continue only the unresolved cells from the full-horizon plan",
            ],
        )

    if global_result.decision != "READY_TO_WRITE" and round_no < max_rounds:
        global_blockers = {
            str(item).strip()
            for item in dict(global_result.verification_facts or {}).get("blocker_reason_codes", [])
            if str(item).strip()
        }
        # A local WARN can legitimately disagree with a canonical global
        # barrier that has no factual blockers (for example, an unresolved
        # intent note). Preserve the disagreement in the report, but do not
        # burn the bounded recovery loop reopening already verified cells.
        if global_blockers <= {"VERIFIER_DISAGREEMENT"} and global_result.verifier_disagreement:
            return ResearchLoopDecision(
                decision="WRITE_WITH_GUARDRAILS",
                reason="VERIFIER_DISAGREEMENT_WITHOUT_CANONICAL_BLOCKER",
                round_no=round_no,
                should_continue=False,
                recovery_actions=list(global_result.recovery_actions),
                terminal_disposition="GUARDED_COMPLETE",
            )
        return ResearchLoopDecision(
            decision="READ_MORE",
            reason="GLOBAL_VERIFIER_NOT_READY",
            round_no=round_no,
            should_continue=True,
            recovery_actions=list(global_result.recovery_actions)
            or ["collect evidence required by the global verifier"],
        )

    if round_no >= max_rounds and has_conflict:
        return ResearchLoopDecision(
            decision="WRITE_WITH_GUARDRAILS",
            reason="CONFLICT_RECHECK_BUDGET_EXHAUSTED",
            round_no=round_no,
            should_continue=False,
            recovery_actions=["write with explicit conflict and uncertainty section"],
            terminal_disposition="HUMAN_HANDOFF",
            handoff_required=True,
        )

    return ResearchLoopDecision(
        decision="SYNTHESIZE_REPORT",
        reason="STOP_CONTRACT_SATISFIED",
        round_no=round_no,
        should_continue=False,
        recovery_actions=[],
        terminal_disposition="VERIFIED_COMPLETE",
    )


def evaluate_premature_commitment_guard(
    plan: ResearchPlan,
    ledger: ResearchStateLedger | None,
    evidence_card_count: int,
) -> dict[str, object]:
    reasons: list[str] = []
    if ledger is None:
        reasons.append("STATE_LEDGER_MISSING")
    else:
        required_cells = [cell for cell in ledger.cells if cell.is_required]
        if required_cells and any(cell.status != "VERIFIED" for cell in required_cells):
            reasons.append("REQUIRED_CELLS_NOT_VERIFIED")
        if any(cell.status == "FROZEN" and cell.is_required for cell in ledger.cells):
            reasons.append("REQUIRED_CELL_FROZEN")
        if any(branch.status in {"ACTIVE", "ACTIVE_BRANCH"} for branch in ledger.branches):
            reasons.append("ACTIVE_RECOVERY_BRANCH")
        distinct_sources = {
            source_id
            for entity in ledger.entities
            for source_id in entity.source_ids
            if source_id
        } or {row.source_id for row in ledger.rows if row.source_id}
        if len(distinct_sources) < int(plan.stop_contract.get("min_sources", 1)):
            reasons.append("MIN_SOURCE_COVERAGE_NOT_MET")
    if evidence_card_count < int(plan.stop_contract.get("min_evidence_cards", 1)):
        reasons.append("MIN_EVIDENCE_NOT_MET")
    return {
        "status": "BLOCK" if reasons else "PASS",
        "blocked": bool(reasons),
        "reason_codes": list(dict.fromkeys(reasons)),
    }


def _augment_plan_for_next_round(
    plan: ResearchPlan,
    decision: ResearchLoopDecision,
    artifacts: ResearchRoundArtifacts,
    recovery_mode: str = "",
) -> ResearchPlan:
    requested_recovery_mode = str(recovery_mode or "").strip().upper()
    recovery_mode = ""
    notes: list[str] = []
    conflict_sources: list[str] = []
    requirement_hints = _build_requirement_recovery_hints(plan, artifacts.ledger)
    if decision.decision == "COUNTERFACTUAL_RECHECK":
        conflict_sources = [
            row.source_title
            for row in artifacts.ledger.rows
            if row.row_status == "CONFLICTED"
        ]
        if requirement_hints["counterfactual_queries"]:
            extra_query = requirement_hints["counterfactual_queries"][0]
        elif conflict_sources:
            extra_query = (
                f"{plan.normalized_question} :: counterfactual recheck against "
                f"{', '.join(conflict_sources[:2])}"
            )
        else:
            extra_query = (
                f"{plan.normalized_question} :: counterfactual recheck round "
                f"{decision.round_no + 1}"
            )
    elif decision.decision == "EXTRACT_AGAIN":
        extra_query = (
            f"{plan.normalized_question} :: evidence extraction retry round "
            f"{decision.round_no + 1}"
        )
        notes = [
            "recovery mode: reuse existing read windows before opening new sources"
        ]
    else:
        extra_query = (
            requirement_hints["target_queries"][0]
            if requirement_hints["target_queries"]
            else f"{plan.normalized_question} :: recovery round {decision.round_no + 1}"
        )
        notes = []

    query_set = list(plan.query_set)
    for query in [extra_query, *requirement_hints["target_queries"]]:
        if query and query not in query_set:
            query_set.append(query)
    plan_notes = list(plan.notes)
    plan_notes.append(
        f"loop recovery: {decision.decision} because {decision.reason}"
    )
    plan_notes.extend(notes)
    if requirement_hints["requirement_labels"]:
        plan_notes.append(
            "recovery targets: "
            + "; ".join(requirement_hints["requirement_labels"][:3])
        )
    if requirement_hints["target_columns"]:
        plan_notes.append(
            "recovery columns: "
            + ", ".join(requirement_hints["target_columns"][:4])
        )
    if artifacts.ledger.branches and artifacts.ledger.active_branch_id != "branch-main":
        plan_notes.append(
            f"active branch: {artifacts.ledger.active_branch_id} "
            f"with {len(artifacts.ledger.verifier_decisions)} verifier decisions"
        )
    stop_contract = dict(plan.stop_contract)
    expanding_horizons = [
        item
        for item in artifacts.evidence_horizon_decisions
        if item.get("action") in {"EXPAND", "EXPAND_COUNTERFACTUAL"}
    ]
    if expanding_horizons:
        horizon_columns = list(dict.fromkeys(
            str(item.get("column_key") or "")
            for item in expanding_horizons
            if str(item.get("column_key") or "")
        ))
        stop_contract["evidence_horizon_target_columns"] = horizon_columns
        stop_contract["evidence_horizon_window_budget"] = max(
            int(item.get("target_window_count") or 1)
            for item in expanding_horizons
        )
        stop_contract["evidence_horizon_decisions"] = expanding_horizons
    stop_contract["recovery_target_requirement_ids"] = requirement_hints[
        "requirement_ids"
    ]
    stop_contract["recovery_target_requirement_types"] = requirement_hints[
        "requirement_types"
    ]
    stop_contract["recovery_target_requirement_labels"] = requirement_hints[
        "requirement_labels"
    ]
    stop_contract["recovery_target_columns"] = list(dict.fromkeys(
        [
            *requirement_hints["target_columns"],
            *stop_contract.get("evidence_horizon_target_columns", []),
        ]
    ))
    stop_contract["recovery_target_queries"] = requirement_hints["target_queries"]
    if decision.decision == "COUNTERFACTUAL_RECHECK":
        stop_contract["recovery_target_sources"] = list(
            dict.fromkeys(
                list(stop_contract.get("recovery_target_sources", []))
                + requirement_hints["target_sources"]
                + conflict_sources
            )
        )[:2]
        stop_contract["recovery_target_evidence_ids"] = list(dict.fromkeys(
            evidence_id
            for row in artifacts.ledger.rows
            if row.row_status == "CONFLICTED"
            for evidence_id in row.conflicting_evidence_ids
            if evidence_id
        ))[:3]
        target_rows = [
            row
            for row in artifacts.ledger.rows
            if row.row_status == "CONFLICTED" and (row.entity_id or row.row_id)
        ]
        stop_contract["recovery_target_entity_ids"] = list(dict.fromkeys(
            row.entity_id or row.row_id for row in target_rows
        ))
        branch_by_evidence = {
            evidence_id: branch.branch_id
            for branch in artifacts.branch_decisions
            if branch.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
            for evidence_id in branch.target_evidence_ids
        }
        stop_contract["recovery_target_branch_by_entity"] = {
            row.entity_id or row.row_id: next(
                (
                    branch_by_evidence[evidence_id]
                    for evidence_id in row.conflicting_evidence_ids
                    if evidence_id in branch_by_evidence
                ),
                artifacts.ledger.active_branch_id,
            )
            for row in target_rows
        }
        plan_notes.append(
            "recovery mode: counterfactual recheck against conflicted sources"
        )
    elif decision.decision == "READ_MORE":
        stop_contract["tool_response_retention_budget"] = max(
            int(stop_contract.get("tool_response_retention_budget", 5)),
            len(artifacts.read_windows) + 1,
        )
        plan_notes.append(
            "recovery mode: preserve prior hits and expand read windows"
        )
        stop_contract["recovery_target_sources"] = requirement_hints[
            "target_sources"
        ]
        stop_contract.pop("recovery_target_evidence_ids", None)
        stop_contract.pop("recovery_target_entity_ids", None)
        stop_contract.pop("recovery_target_branch_by_entity", None)
    else:
        stop_contract.pop("recovery_target_sources", None)
        stop_contract.pop("recovery_target_evidence_ids", None)
        stop_contract.pop("recovery_target_entity_ids", None)
        stop_contract.pop("recovery_target_branch_by_entity", None)
        stop_contract.pop("recovery_target_requirement_ids", None)
        stop_contract.pop("recovery_target_requirement_types", None)
        stop_contract.pop("recovery_target_requirement_labels", None)
        stop_contract.pop("recovery_target_columns", None)
        stop_contract.pop("recovery_target_queries", None)
    # DR-108: 生效模式由唯一规则收敛（与 CellRecoveryDirective 同一实现）。
    # loop 决策族定义恢复族，Cell 级策略只能在族内细化；跨族请求不再静默改写
    # 生效值，而是保留在指令的 requested_recovery_mode 里供复盘。
    recovery_mode, _recovery_mode_source = reconcile_recovery_mode(
        decision_family=decision.decision,
        requested_recovery_mode=requested_recovery_mode,
    )
    if recovery_mode:
        stop_contract["recovery_mode"] = recovery_mode
    else:
        stop_contract.pop("recovery_mode", None)
    return plan.model_copy(
        update={
            "query_set": query_set,
            "notes": plan_notes,
            "stop_contract": stop_contract,
            "plan_revision": plan.plan_revision + 1,
            "replan_history": [
                *plan.replan_history,
                {
                    "revision": plan.plan_revision + 1,
                    "trigger": decision.reason,
                    "decision": decision.decision,
                    "round_no": decision.round_no,
                    "affected_cells": [
                        str(item.get("horizon_key") or "")
                        for item in expanding_horizons
                    ],
                    "preserved_horizon_phases": [
                        str(item.get("phase") or "")
                        for item in plan.plan_horizon
                        if str(item.get("phase") or "") not in {"DEEP_CELL_COMPLETION", "COUNTERFACTUAL_RECOVERY"}
                    ],
                },
            ],
        }
    )


def _max_loop_rounds(plan: ResearchPlan) -> int:
    return max(1, int(plan.stop_contract.get("max_loop_rounds", 2)))


def _freeze_overspent_cells(
    ledger: ResearchStateLedger, max_retry_per_cell: int
) -> ResearchStateLedger:
    """P0-6: 把超过 max_retry_per_cell 的 cell 标记为 FROZEN,后续循环不再消耗 token。

    设计参考文档 §6.2: VERIFIED / CONFLICTED / FROZEN 是 cell 的三个稳定终态;
    FROZEN 表示"当前预算下不再继续消耗资源",这与设计文档 §7.9 Stop Contract
    的"放弃条件"对齐。

    DR-202: 阈值判定不再本地重写，而是委托 :func:`cell_recovery_policy.decide`。
    本函数只保留「ATTEMPT_LIMIT_REACHED -> FROZEN」这一族的落地写法，避免恢复规则
    在 loop 与策略模块里各有一份。
    """
    if max_retry_per_cell <= 0:
        return ledger
    limits = CellRecoveryThresholds(max_attempts_per_cell=max_retry_per_cell)
    empty_budget = CellRecoveryBudget(
        remaining_search=0,
        remaining_fetch=0,
        remaining_read=0,
        remaining_llm=0,
        remaining_extract=0,
    )
    new_cells = []
    frozen_count = 0
    for cell in ledger.cells:
        if cell.status in {"VERIFIED", "CONFLICTED", "FROZEN"}:
            new_cells.append(cell)
            continue
        decision = decide(
            cell.status,
            {},
            empty_budget,
            CellRecoveryHistory(
                attempts=int(cell.repair_count or 0),
                consecutive_failures=0,
                actions_taken=(),
                plan_digests=(),
            ),
            limits,
        )
        if (
            decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
            and decision.reason_code == "ATTEMPT_LIMIT_REACHED"
        ):
            new_cells.append(
                cell.model_copy(
                    update={
                        "status": "FROZEN",
                        "verdict_reason": (cell.verdict_reason or "")
                        + f" | FROZEN after {cell.repair_count} retries (max_retry_per_cell={max_retry_per_cell})",
                    }
                )
            )
            frozen_count += 1
        else:
            new_cells.append(cell)
    if frozen_count == 0:
        return ledger
    return ledger.model_copy(update={"cells": new_cells})


def _pending_requirement_progress(ledger: ResearchStateLedger | None):
    if ledger is None:
        return []
    return [
        item
        for item in ledger.required_finding_progress
        if item.status != "READY"
    ]


def _build_requirement_recovery_hints(
    plan: ResearchPlan,
    ledger: ResearchStateLedger,
) -> dict[str, list[str]]:
    requirement_ids: list[str] = []
    requirement_types: list[str] = []
    requirement_labels: list[str] = []
    target_columns: list[str] = []
    target_queries: list[str] = []
    counterfactual_queries: list[str] = []
    target_sources: list[str] = []
    for item in _pending_requirement_progress(ledger):
        requirement_ids.append(item.requirement_id)
        requirement_types.append(item.requirement_type)
        requirement_labels.append(item.label)
        for column in item.required_columns:
            if column not in target_columns:
                target_columns.append(column)
        label = item.label.strip() or item.requirement_id
        if item.requirement_type == "CONFLICT_FINDING":
            query = (
                f"{plan.normalized_question} :: counterfactual evidence check :: "
                f"{label}"
            )
            counterfactual_queries.append(query)
            target_queries.append(query)
            target_sources.extend(
                row.source_title
                for row in ledger.rows
                if row.row_status == "CONFLICTED"
            )
        elif item.requirement_type == "CONSTRAINT_FINDING":
            target_queries.append(
                f"{plan.normalized_question} :: verified evidence search :: {label}"
            )
            target_sources.extend(
                row.source_title
                for row in ledger.rows
                if row.row_status in {"NEED_MORE_EVIDENCE", "BLOCKED"}
            )
        else:
            target_queries.append(
                f"{plan.normalized_question} :: direct answer with evidence :: {label}"
            )
            target_sources.extend(
                row.source_title
                for row in ledger.rows
                if row.row_status != "VERIFIED"
            )
    return {
        "requirement_ids": list(dict.fromkeys(requirement_ids)),
        "requirement_types": list(dict.fromkeys(requirement_types)),
        "requirement_labels": list(dict.fromkeys(requirement_labels)),
        "target_columns": list(dict.fromkeys(target_columns)),
        "target_queries": list(dict.fromkeys(target_queries)),
        "counterfactual_queries": list(dict.fromkeys(counterfactual_queries)),
        "target_sources": list(
            dict.fromkeys(
                source
                for source in target_sources
                if source.strip()
            )
        ),
    }


def _restore_resume_context(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
) -> ResumeCheckpointContext | None:
    resume_checkpoint = task_input.input_payload.resume_checkpoint
    if resume_checkpoint is None:
        return None

    payload = resume_checkpoint.payload
    saved_plan_payload = payload.get("plan")
    restored_plan = (
        ResearchPlan.model_validate(saved_plan_payload)
        if isinstance(saved_plan_payload, dict)
        else plan
    )
    search_hits = _parse_models(payload.get("search_hits"), ResearchSearchHit)
    search_hits = [_normalize_restored_search_hit(hit) for hit in search_hits]
    fetched_documents = _parse_models(payload.get("fetched_documents"), ResearchFetchedDocument)
    read_windows = _parse_models(payload.get("read_windows"), ResearchReadWindow)
    read_windows = [_normalize_restored_read_window(window) for window in read_windows]
    if not fetched_documents and read_windows:
        fetched_documents = [
            _restore_fetched_document_from_read_window(window, index=index + 1)
            for index, window in enumerate(read_windows)
        ]
    if not fetched_documents and search_hits:
        fetched_documents = run_research_fetch(task_input, restored_plan, search_hits)
    evidence_cards = _parse_models(payload.get("evidence_cards"), ResearchEvidenceCard)
    ledger_payload = payload.get("state_ledger")
    if isinstance(ledger_payload, dict):
        ledger = ResearchStateLedger.model_validate(ledger_payload)
    else:
        ledger = build_state_ledger(
            task_input,
            restored_plan,
            search_hits,
            read_windows,
            evidence_cards,
        )

    local_payload = payload.get("local_verifier")
    if isinstance(local_payload, dict):
        local_result = LocalVerifierResult.model_validate(local_payload)
    else:
        local_result = run_local_verifier(
            task_input,
            restored_plan,
            ledger,
            search_hits,
            read_windows,
            evidence_cards,
            llm_client=None,
        )

    branch_decisions = _parse_models(
        payload.get("branch_decisions"),
        ResearchBranchDecision,
    )
    if not branch_decisions:
        branch_decisions = plan_branch_recovery(
            search_hits,
            read_windows,
            evidence_cards,
            local_result,
        )

    global_payload = payload.get("global_verifier")
    if isinstance(global_payload, dict):
        global_result = GlobalVerifierResult.model_validate(global_payload)
    else:
        global_result = run_global_verifier(local_result, ledger, branch_decisions, evidence_cards)

    loop_rounds = _parse_models(payload.get("loop_rounds"), ResearchLoopRoundSummary)
    loop_decision_payload = payload.get("loop_decision")
    if isinstance(loop_decision_payload, dict):
        final_decision = ResearchLoopDecision.model_validate(loop_decision_payload)
    else:
        final_decision = evaluate_loop_decision(
            plan=restored_plan,
            round_no=max(1, len(loop_rounds)),
            search_hit_count=len(search_hits),
            read_window_count=len(read_windows),
            evidence_card_count=len(evidence_cards),
            has_conflict=any(
                card.relation_type == "CONFLICTS"
                for card in evidence_cards
            ),
            ledger=ledger,
            global_result=global_result,
        )

    return ResumeCheckpointContext(
        plan=restored_plan,
        artifacts=ResearchRoundArtifacts(
            search_hits=search_hits,
            fetched_documents=fetched_documents,
            read_windows=read_windows,
            evidence_cards=evidence_cards,
            ledger=ledger,
            local_result=local_result,
            branch_decisions=branch_decisions,
            global_result=global_result,
            tool_traces=_parse_models(payload.get("tool_traces"), ResearchStepTrace),
            evidence_horizon_decisions=[
                dict(item)
                for item in payload.get("evidence_horizon_decisions", [])
                if isinstance(item, dict)
            ],
        ),
        rounds=loop_rounds,
        final_decision=final_decision,
        summary={
            "source_research_run_id": resume_checkpoint.source_research_run_id,
            "checkpoint_no": resume_checkpoint.checkpoint_no,
            "snapshot_type": resume_checkpoint.snapshot_type,
            "active_branch_id": resume_checkpoint.active_branch_id or ledger.active_branch_id,
            "final_loop_decision": resume_checkpoint.final_loop_decision or final_decision.decision,
            "restored_search_hit_count": len(search_hits),
            "restored_fetch_document_count": len(fetched_documents),
            "restored_read_window_count": len(read_windows),
            "restored_evidence_card_count": len(evidence_cards),
            "restored_loop_round_count": len(loop_rounds),
            "restored_tool_trace_count": len(_parse_models(payload.get("tool_traces"), ResearchStepTrace)),
        },
    )


def _parse_models(value: object, model_class: type):
    if not isinstance(value, list):
        return []
    parsed = []
    for item in value:
        if isinstance(item, dict):
            parsed.append(model_class.model_validate(item))
    return parsed


def _normalize_restored_search_hit(hit: ResearchSearchHit) -> ResearchSearchHit:
    provider = str(hit.provider or "").strip() or ("workspace" if hit.adapter == "workspace" else "external")
    provider_attempts = [
        str(item).strip()
        for item in hit.provider_attempts
        if str(item).strip()
    ] or [provider]
    provider_resolution = str(hit.provider_resolution or "").strip() or provider
    provider_fallback_reason = str(hit.provider_fallback_reason or "").strip()
    if not provider_fallback_reason and len(provider_attempts) > 1:
        provider_fallback_reason = "restored from legacy checkpoint with provider fallback chain"
    return hit.model_copy(
        update={
            "provider": provider,
            "provider_attempts": provider_attempts,
            "provider_resolution": provider_resolution,
            "provider_fallback_reason": provider_fallback_reason,
        }
    )


def _restore_fetched_document_from_read_window(
    window: ResearchReadWindow,
    *,
    index: int,
) -> ResearchFetchedDocument:
    return ResearchFetchedDocument(
        fetch_id=f"fetch-restored-{index}",
        hit_id=window.hit_id,
        source_id=window.source_id,
        source_title=window.source_title,
        query=window.query,
        url=window.url,
        provider=window.provider,
        adapter=window.adapter,
        search_angle="restored",
        snapshot_text=window.window_text,
        snapshot_status=window.snapshot_status,
        snapshot_key=window.snapshot_key,
        fetch_status=window.fetch_status,
        fetch_method=window.fetch_method,
        content_origin=window.content_origin,
        fetch_error_reason=window.fetch_error_reason,
        fetch_attempts=list(window.fetch_attempts),
        transport_chain=list(window.transport_chain),
        transport_resolution=window.transport_resolution,
        transport_fallback_reason=window.transport_fallback_reason,
        source_domain=window.source_domain,
        source_quality=window.source_quality,
        source_quality_score=window.source_quality_score,
    )


def _normalize_restored_read_window(window: ResearchReadWindow) -> ResearchReadWindow:
    snapshot_status = str(window.snapshot_status or "").strip().upper() or "WORKSPACE"
    existing_fetch_status = str(window.fetch_status or "").strip().upper()
    existing_content_origin = str(window.content_origin or "").strip().upper()
    has_external_hint = (
        window.adapter == "external_url"
        or bool(window.url.strip())
        or snapshot_status in {"FALLBACK", "FETCH_FAILED", "FETCHED", "HTTP_FETCHED", "JINA_FETCHED"}
    )
    if has_external_hint:
        if (
            snapshot_status in {"FETCHED", "HTTP_FETCHED", "JINA_FETCHED"}
            or existing_fetch_status == "FETCHED"
            or existing_content_origin == "FETCHED_SNAPSHOT"
        ):
            fetch_status = "FETCHED"
            content_origin = "FETCHED_SNAPSHOT"
            fetch_method = (
                str(window.fetch_method or "").strip()
                if str(window.fetch_method or "").strip() and str(window.fetch_method).strip() != "WORKSPACE"
                else "HTTP" if snapshot_status == "HTTP_FETCHED" else "JINA" if snapshot_status == "JINA_FETCHED" else "TRANSPORT"
            )
            fetch_error_reason = str(window.fetch_error_reason or "").strip()
        elif snapshot_status in {"FALLBACK", "FETCH_FAILED"} or existing_fetch_status == "FALLBACK_USED":
            fetch_status = "FALLBACK_USED"
            content_origin = "SEARCH_SNIPPET_FALLBACK"
            fetch_method = (
                str(window.fetch_method or "").strip()
                if str(window.fetch_method or "").strip() and str(window.fetch_method).strip() != "WORKSPACE"
                else "NO_TRANSPORT"
            )
            fetch_error_reason = str(window.fetch_error_reason or "").strip() or "restored from legacy checkpoint without explicit fetch metadata"
        else:
            fetch_status = existing_fetch_status or "FETCH_METADATA_UNKNOWN"
            content_origin = existing_content_origin or "RESTORED_EXTERNAL_TEXT"
            fetch_method = (
                str(window.fetch_method or "").strip()
                if str(window.fetch_method or "").strip() and str(window.fetch_method).strip() != "WORKSPACE"
                else "TRANSPORT"
            )
            fetch_error_reason = str(window.fetch_error_reason or "").strip()
        fetch_attempts = [
            str(item).strip()
            for item in window.fetch_attempts
            if str(item).strip()
        ] or [fetch_method]
        transport_chain = [
            str(item).strip()
            for item in window.transport_chain
            if str(item).strip()
        ] or list(fetch_attempts)
        existing_transport_resolution = str(window.transport_resolution or "").strip()
        transport_resolution = (
            existing_transport_resolution
            if existing_transport_resolution and existing_transport_resolution != "WORKSPACE"
            else fetch_method
        )
        transport_fallback_reason = str(window.transport_fallback_reason or "").strip()
        if not transport_fallback_reason and len(transport_chain) > 1:
            transport_fallback_reason = fetch_error_reason or "restored from legacy checkpoint with transport fallback chain"
        return window.model_copy(
            update={
                "fetch_status": fetch_status,
                "fetch_method": fetch_method,
                "content_origin": content_origin,
                "fetch_error_reason": fetch_error_reason,
                "fetch_attempts": fetch_attempts,
                "transport_chain": transport_chain,
                "transport_resolution": transport_resolution,
                "transport_fallback_reason": transport_fallback_reason,
            }
        )

    fetch_attempts = [
        str(item).strip()
        for item in window.fetch_attempts
        if str(item).strip()
    ] or ["WORKSPACE"]
    transport_chain = [
        str(item).strip()
        for item in window.transport_chain
        if str(item).strip()
    ] or ["WORKSPACE"]
    return window.model_copy(
        update={
            "fetch_status": "WORKSPACE_READY",
            "fetch_method": "WORKSPACE",
            "content_origin": "WORKSPACE_TEXT",
            "fetch_error_reason": "",
            "fetch_attempts": fetch_attempts,
            "transport_chain": transport_chain,
            "transport_resolution": "WORKSPACE",
            "transport_fallback_reason": "",
        }
    )
