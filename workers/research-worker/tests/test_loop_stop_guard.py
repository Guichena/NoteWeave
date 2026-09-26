"""DR-204：循环与预算停止保护的故障注入证据（含 DR-107 基础设施分类）。

对应 ``docs/DeepResearch-简历能力落地执行计划.md``：

- M2 表 DR-204 退出条件「重复失败不会无限执行」，证据产物「故障注入结果」；
- 第 10 节故障注入矩阵中：
  「证据缺失 / 搜索无有效结果 -> 有界 Replan 后进入 unresolved 或证据不足终态」、
  「Provider 失败 -> 根据收据状态安全重试，基础设施失败分类准确」。

每条注入都断言**计数**（轮次 / 外部调用次数），并复跑两次证明确定性。
停止判定集中在 :mod:`app.loop_stop_guard`，本文件同时直接覆盖该模块的优先级
与词表，避免保护条件再次散落。
"""

from __future__ import annotations

import json
from collections import Counter

import pytest

from app.cell_recovery_runtime import (
    CellRecoveryBudgetLedger,
    CellRecoveryRuntime,
)
from app.cell_recovery_policy import CellRecoveryThresholds
from app.llm_client import FakeLlmClient
from app.loop_runtime import run_research_loop
from app.loop_stop_guard import (
    ALL_REASON_CODES,
    BUSINESS_INSUFFICIENT_EVIDENCE_CODES,
    CELL_BOUND_REASON_CODES,
    EVIDENCE_REASON_CODES,
    INFRASTRUCTURE_REASON_CODES,
    RESOURCE_REASON_CODES,
    LoopStopGuardInputs,
    LoopStopReason,
    dominant_rejection_reason,
    evaluate_loop_stop_guard,
    external_recovery_call_upper_bound,
    provider_failure_signal,
    reason_category,
)
from app.models import ResearchStateCell, ResearchStateLedger, ResearchTaskInput
from app.planner import build_research_plan


# --------------------------------------------------------------------------- #
# 测试替身与构造器
# --------------------------------------------------------------------------- #
class _RejectingQuoteLlm:
    """配置可用、但持续返回「引文不在窗口中」的候选卡（NON_EXACT_QUOTE）。"""

    def __init__(self, quote: str = "THIS QUOTE IS ABSENT FROM EVERY WINDOW") -> None:
        self.quote = quote
        self.calls: list[str] = []

    def complete_json(self, purpose: str, payload: dict) -> str:
        self.calls.append(purpose)
        if purpose != "research.extract":
            return ""
        schema_columns = [
            str(item).strip()
            for item in payload.get("schema_columns", [])
            if str(item).strip()
        ] or ["claim"]
        windows = payload.get("windows") or []
        window_id = windows[0].get("window_id", "window-1") if windows else "window-1"
        return json.dumps(
            {
                "evidence_cards": [
                    {
                        "window_id": window_id,
                        "column_key": schema_columns[0],
                        "claim_text": "A claim whose quote cannot be grounded.",
                        "quote_text": self.quote,
                        "relation_type": "SUPPORTS",
                    }
                ]
            }
        )

    def extract_call_count(self) -> int:
        return sum(1 for purpose in self.calls if purpose == "research.extract")


class _UnavailableProviderLlm:
    """已配置但基础设施侧失败（重试耗尽 / 5xx）的 Provider：收据可读、响应为空。"""

    def __init__(self, termination_reason: str = "RETRY_EXHAUSTED") -> None:
        self.termination_reason = termination_reason
        self.call_records: list[dict[str, object]] = []
        self.calls: list[str] = []

    def complete_json(self, purpose: str, payload: dict) -> str:
        self.calls.append(purpose)
        self.call_records.append(
            {
                "purpose": purpose,
                "attempt_count": 3,
                "termination_reason": self.termination_reason,
                "http_statuses": [503, 503, 503],
            }
        )
        return ""

    def extract_call_count(self) -> int:
        return sum(1 for purpose in self.calls if purpose == "research.extract")


class _CountingToolchain:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def __call__(self, cell_id: str, action, decision) -> None:  # noqa: ANN001
        self.calls.append((cell_id, action.value))


def _task_input(
    *,
    source_scope: list[dict[str, object]] | None = None,
    question: str = "How should the research loop recover missing evidence?",
) -> ResearchTaskInput:
    payload: dict[str, object] = {
        "task_id": "task-dr204",
        "workspace_id": "ws-dr204",
        "target_id": "run-dr204",
        "source_scope": (
            source_scope
            if source_scope is not None
            else [
                {
                    "source_id": "src-dr204",
                    "title": "DR204 Evidence Source",
                    "summary": "Stable evidence supports the research loop stop contract.",
                    "sample_text": "Stable evidence supports the research loop stop contract.",
                }
            ]
        ),
        "control_pack": {
            "pack_type": "research",
            "target_key": "DEFAULT",
            "task_neighborhood": "RESEARCH_DEFAULT",
            "evidence_policy": ["Do not synthesize without evidence."],
        },
        "input_payload": {"question": question, "profile_key": "DEFAULT"},
    }
    return ResearchTaskInput.model_validate(payload)


def _budget_inputs(**overrides: object) -> LoopStopGuardInputs:
    values: dict[str, object] = {
        "round_no": 1,
        "max_rounds": 4,
        "cell_count": 8,
        "max_attempts_per_cell": 3,
        "max_repeated_failures": 2,
    }
    values.update(overrides)
    return LoopStopGuardInputs(**values)  # type: ignore[arg-type]


def _per_round_external_calls(result) -> Counter:  # noqa: ANN001
    return Counter(
        entry["round_no"]
        for entry in result.cell_recovery_trace
        if bool(entry.get("external_call"))
    )


def _per_cell_external_calls(result) -> Counter:  # noqa: ANN001
    return Counter(
        entry["cell_id"]
        for entry in result.cell_recovery_trace
        if bool(entry.get("external_call"))
    )


# --------------------------------------------------------------------------- #
# 注入 1：持续返回不可用引文（非精确引文）
# --------------------------------------------------------------------------- #
def _run_non_exact_quote_injection():
    task_input = _task_input()
    plan = build_research_plan(task_input)
    llm = _RejectingQuoteLlm()
    result = run_research_loop(task_input, plan, llm_client=llm)
    return result, llm, plan


def test_injection_non_exact_quote_is_bounded_and_freezes_cells() -> None:
    result, llm, plan = _run_non_exact_quote_injection()
    max_rounds = int(plan.stop_contract["max_loop_rounds"])
    max_retry = int(plan.stop_contract["max_retry_per_cell"])
    budget = CellRecoveryBudgetLedger.from_plan(plan)
    cell_count = len(result.artifacts.ledger.cells)

    # 有界轮次内终止
    assert 0 < len(result.rounds) <= max_rounds
    assert result.stop_reason_code == LoopStopReason.REPEATED_CELL_FAILURE.value
    assert result.stop_reason_category == "RESOURCE"

    # 外部调用计数有明确上界，且实测不超过
    assert result.external_recovery_call_upper_bound == min(max_rounds, max_retry) * cell_count
    assert result.external_recovery_call_count == budget.remaining_llm  # 实测计数
    assert result.external_recovery_call_count <= result.external_recovery_call_upper_bound

    # 每个 Cell 的外部恢复动作数不超过 max_retry_per_cell
    per_cell = _per_cell_external_calls(result)
    assert per_cell
    assert max(per_cell.values()) <= max_retry

    # 每个 Cell 最终 FROZEN（等价终态），带稳定标记
    frozen = [cell for cell in result.artifacts.ledger.cells if cell.status == "FROZEN"]
    assert len(frozen) == cell_count
    assert all("FROZEN_UNRESOLVED:" in (cell.verdict_reason or "") for cell in frozen)

    # Provider 抽取调用被轮次封顶：每轮恰好一次
    assert llm.extract_call_count() == len(result.rounds)


def test_non_exact_quote_injection_is_deterministic() -> None:
    first_result, _first_llm, _plan = _run_non_exact_quote_injection()
    second_result, _second_llm, _plan2 = _run_non_exact_quote_injection()

    def _signature(result) -> tuple:  # noqa: ANN001
        return (
            result.stop_reason_code,
            result.external_recovery_call_count,
            len(result.rounds),
            sorted(_per_cell_external_calls(result).items()),
        )

    assert _signature(first_result) == _signature(second_result)
    assert _signature(first_result)[0] == LoopStopReason.REPEATED_CELL_FAILURE.value


# --------------------------------------------------------------------------- #
# 注入 2：持续搜索无命中
# --------------------------------------------------------------------------- #
def test_injection_no_search_hits_terminates_with_stable_reason() -> None:
    llm = FakeLlmClient({})  # Provider 已配置；失败原因不是基础设施

    def _run():
        task_input = _task_input(source_scope=[])
        plan = build_research_plan(task_input)
        return run_research_loop(task_input, plan, llm_client=llm)

    result = _run()
    assert len(result.rounds) == 1
    assert result.stop_reason_code == LoopStopReason.NO_SEARCH_HITS.value
    assert result.stop_reason_category == "EVIDENCE"
    assert result.final_decision.decision == "EXPAND_SOURCE_SCOPE"
    # 不得无限重搜：零外部恢复动作、零抽取调用
    assert result.external_recovery_call_count == 0
    assert sum(1 for purpose, _payload in llm.calls if purpose == "research.extract") == 0

    # 确定性：同一注入两次运行得到相同 reason_code 与相同调用计数
    again = _run()
    assert again.stop_reason_code == result.stop_reason_code
    assert again.external_recovery_call_count == result.external_recovery_call_count
    assert len(again.rounds) == len(result.rounds)


# --------------------------------------------------------------------------- #
# 注入 3：预算在第二轮耗尽
# --------------------------------------------------------------------------- #
def test_injection_budget_exhausted_stops_growing_external_calls() -> None:
    task_input = _task_input()
    plan = build_research_plan(task_input)
    # 关掉重复失败/尝试上限保护，单独隔离「预算耗尽」这一条保护
    plan.stop_contract["max_loop_rounds"] = 9
    plan.stop_contract["max_retry_per_cell"] = 10
    plan.stop_contract["max_repeated_failures"] = 10
    budget = CellRecoveryBudgetLedger.from_plan(plan)
    llm = _RejectingQuoteLlm()

    result = run_research_loop(task_input, plan, llm_client=llm)

    per_round = _per_round_external_calls(result)
    last_round = result.rounds[-1].round_no
    rounds_with_calls = sorted(round_no for round_no, count in per_round.items() if count)

    # 预算恰好被耗尽，之后外部调用计数不再增长
    assert result.external_recovery_call_count == budget.remaining_llm
    assert rounds_with_calls == [1, 2]
    assert per_round[1] == len(result.artifacts.ledger.cells)
    assert per_round[2] == 1
    assert per_round.get(last_round, 0) == 0
    assert last_round > rounds_with_calls[-1]
    # 有界且不超过明确上界
    assert len(result.rounds) <= int(plan.stop_contract["max_loop_rounds"])
    assert result.external_recovery_call_count <= result.external_recovery_call_upper_bound


# --------------------------------------------------------------------------- #
# 注入 4：Cell 尝试次数达到 max_attempts_per_cell
# --------------------------------------------------------------------------- #
def test_injection_cell_attempt_limit_produces_no_more_external_actions() -> None:
    task_input = _task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["max_loop_rounds"] = 16
    plan.stop_contract["max_retry_per_cell"] = 3
    plan.stop_contract["max_repeated_failures"] = 10
    llm = _RejectingQuoteLlm()

    result = run_research_loop(task_input, plan, llm_client=llm)
    cell_count = len(result.artifacts.ledger.cells)
    per_round = _per_round_external_calls(result)

    assert result.stop_reason_code == LoopStopReason.CELL_ATTEMPT_LIMIT_REACHED.value
    # 每个 Cell 恰好用满 2 次外部重试（repair_count 每轮 +1，故上限 3 只允许 2 次重试）
    assert _per_cell_external_calls(result) == Counter(
        {cell.cell_id: 2 for cell in result.artifacts.ledger.cells}
    )
    assert result.external_recovery_call_count == 2 * cell_count
    assert result.external_recovery_call_upper_bound == min(16, 3) * cell_count
    # 触顶后不再产生任何外部动作
    assert sorted(per_round) == [1, 2]
    assert per_round.get(result.rounds[-1].round_no, 0) == 0

    frozen = [cell for cell in result.artifacts.ledger.cells if cell.status == "FROZEN"]
    assert len(frozen) == cell_count
    assert all("ATTEMPT_LIMIT_REACHED" in (cell.verdict_reason or "") for cell in frozen)


# --------------------------------------------------------------------------- #
# 注入 5：Provider 未配置（DR-107）
# --------------------------------------------------------------------------- #
def test_injection_provider_not_configured_is_infrastructure_not_evidence() -> None:
    def _run():
        task_input = _task_input()
        plan = build_research_plan(task_input)
        return run_research_loop(task_input, plan, llm_client=None)

    result = _run()

    # 终止原因必须是基础设施类，且不得是证据不足 / 完成态
    assert result.stop_reason_code == LoopStopReason.PROVIDER_NOT_CONFIGURED.value
    assert result.stop_reason_code in INFRASTRUCTURE_REASON_CODES
    assert result.stop_reason_code not in EVIDENCE_REASON_CODES
    assert result.stop_reason_code not in BUSINESS_INSUFFICIENT_EVIDENCE_CODES
    assert not result.stop_reason_code.startswith("COMPLETED_")
    assert result.stop_reason_category == "INFRASTRUCTURE"

    # 不得反复烧轮次：Provider 未配置时立即停止（1 轮）
    assert len(result.rounds) == 1

    # machine-readable 基础设施标记进入恢复动作，供 DR-305 的 RunCompletionGate 分类
    assert any(
        action == "infrastructure_failure:PROVIDER_NOT_CONFIGURED"
        for action in result.final_decision.recovery_actions
    )

    # 确定性：同一注入两次运行得到相同 reason_code 与相同调用计数
    again = _run()
    assert (again.stop_reason_code, again.external_recovery_call_count, len(again.rounds)) == (
        result.stop_reason_code,
        result.external_recovery_call_count,
        len(result.rounds),
    )


def test_injection_provider_unavailable_is_infrastructure() -> None:
    task_input = _task_input()
    plan = build_research_plan(task_input)
    llm = _UnavailableProviderLlm()

    result = run_research_loop(task_input, plan, llm_client=llm)

    assert result.stop_reason_code == LoopStopReason.PROVIDER_UNAVAILABLE.value
    assert result.stop_reason_code in INFRASTRUCTURE_REASON_CODES
    assert result.stop_reason_code not in EVIDENCE_REASON_CODES
    assert result.stop_reason_category == "INFRASTRUCTURE"
    assert len(result.rounds) == 1
    assert any(
        action == "infrastructure_failure:PROVIDER_UNAVAILABLE"
        for action in result.final_decision.recovery_actions
    )


# --------------------------------------------------------------------------- #
# 停止保护判定本身：优先级、词表、上界、冻结
# --------------------------------------------------------------------------- #
def test_reason_code_vocabulary_is_stable_and_categorized() -> None:
    assert ALL_REASON_CODES == {
        "PROVIDER_NOT_CONFIGURED",
        "PROVIDER_UNAVAILABLE",
        "WALL_CLOCK_BUDGET_EXHAUSTED",
        "LOOP_BUDGET_EXHAUSTED",
        "CELL_ATTEMPT_LIMIT_REACHED",
        "REPEATED_CELL_FAILURE",
        "NO_SEARCH_HITS",
        "CELL_RECOVERY_HALTED",
    }
    assert INFRASTRUCTURE_REASON_CODES.isdisjoint(EVIDENCE_REASON_CODES)
    assert INFRASTRUCTURE_REASON_CODES.isdisjoint(RESOURCE_REASON_CODES)
    assert EVIDENCE_REASON_CODES.isdisjoint(RESOURCE_REASON_CODES)
    assert CELL_BOUND_REASON_CODES <= RESOURCE_REASON_CODES
    for code in ALL_REASON_CODES:
        assert reason_category(code) in {"INFRASTRUCTURE", "RESOURCE", "EVIDENCE"}
    assert reason_category("INSUFFICIENT_EVIDENCE") == "UNCLASSIFIED"
    assert "INSUFFICIENT_EVIDENCE" in BUSINESS_INSUFFICIENT_EVIDENCE_CODES


def test_stop_guard_precedence_is_fixed() -> None:
    # 墙钟最优先
    wall = evaluate_loop_stop_guard(
        _budget_inputs(
            wall_clock_exhausted=True,
            search_hit_count=0,
            llm_provider_configured=False,
        )
    )
    assert wall.run_stop is not None
    assert wall.run_stop.reason_code == LoopStopReason.WALL_CLOCK_BUDGET_EXHAUSTED.value

    # 检索为零优先于 Provider 不可用（Provider 从未被使用，不能归因基础设施）
    no_hits = evaluate_loop_stop_guard(
        _budget_inputs(search_hit_count=0, llm_provider_configured=False)
    )
    assert no_hits.run_stop is not None
    assert no_hits.run_stop.reason_code == LoopStopReason.NO_SEARCH_HITS.value
    assert no_hits.freeze_cell_ids == ()

    # Provider 未配置优先于轮次上限
    provider = evaluate_loop_stop_guard(
        _budget_inputs(
            round_no=4,
            max_rounds=4,
            search_hit_count=3,
            read_window_count=1,
            llm_provider_configured=False,
        )
    )
    assert provider.run_stop is not None
    assert provider.run_stop.reason_code == LoopStopReason.PROVIDER_NOT_CONFIGURED.value
    assert provider.run_stop.is_infrastructure is True

    # 重复失败：冻结未收敛 Cell
    repeated = evaluate_loop_stop_guard(
        _budget_inputs(
            search_hit_count=3,
            read_window_count=1,
            evidence_card_count=0,
            rejection_reason="NON_EXACT_QUOTE",
            repeated_failure_streak=2,
            unresolved_cell_ids=("a:method", "b:method"),
            llm_provider_configured=True,
        )
    )
    assert repeated.run_stop is not None
    assert repeated.run_stop.reason_code == LoopStopReason.REPEATED_CELL_FAILURE.value
    assert repeated.freeze_cell_ids == ("a:method", "b:method")

    # 正常完成（无任何保护命中）：run_stop 为空
    clean = evaluate_loop_stop_guard(
        _budget_inputs(
            search_hit_count=3,
            read_window_count=1,
            evidence_card_count=2,
            verified_row_count=1,
            round_no=1,
        )
    )
    assert clean.run_stop is None
    assert clean.stop_reason_code == ""


def test_external_call_upper_bound_is_explicit() -> None:
    assert external_recovery_call_upper_bound(
        max_rounds=4, max_attempts_per_cell=3, cell_count=8
    ) == 24
    assert external_recovery_call_upper_bound(
        max_rounds=16, max_attempts_per_cell=3, cell_count=8
    ) == 24
    assert external_recovery_call_upper_bound(
        max_rounds=0, max_attempts_per_cell=3, cell_count=8
    ) == 0


def test_dominant_rejection_reason_is_deterministic() -> None:
    assert dominant_rejection_reason({"EMPTY_QUOTE": 1, "NON_EXACT_QUOTE": 2}) == "NON_EXACT_QUOTE"
    # 并列取原因名最小者
    assert dominant_rejection_reason({"B_REASON": 1, "A_REASON": 1}) == "A_REASON"
    assert dominant_rejection_reason({}) == ""
    assert dominant_rejection_reason({"IGNORED": 0}) == ""


def test_provider_failure_signal_only_flags_infrastructure_terminations() -> None:
    healthy = _UnavailableProviderLlm(termination_reason="SUCCESS")
    healthy.complete_json("research.extract", {})
    assert provider_failure_signal(healthy) == ""

    retried = _UnavailableProviderLlm(termination_reason="RETRY_EXHAUSTED")
    retried.complete_json("research.extract", {})
    assert provider_failure_signal(retried) == "RETRY_EXHAUSTED"

    auth = _UnavailableProviderLlm(termination_reason="NON_RETRYABLE_HTTP_401")
    auth.complete_json("research.extract", {})
    assert provider_failure_signal(auth) == "NON_RETRYABLE_HTTP_401"

    # 无收据的测试替身不能被误判为基础设施
    assert provider_failure_signal(object()) == ""


def test_cell_recovery_runtime_attempts_are_observable() -> None:
    runtime = CellRecoveryRuntime(
        budget_ledger=CellRecoveryBudgetLedger(
            remaining_search=4,
            remaining_fetch=4,
            remaining_read=4,
            remaining_llm=4,
            remaining_extract=4,
        ),
        thresholds=CellRecoveryThresholds(max_attempts_per_cell=2),
        toolchain=_CountingToolchain(),
    )
    ledger = ResearchStateLedger(
        cells=[
            ResearchStateCell(
                cell_id="entity-1:method",
                row_id="entity-1",
                entity_id="entity-1",
                column_key="method",
                candidate_value="candidate",
                status="NEED_MORE_EVIDENCE",
                evidence_refs=["ev-1"],
            )
        ]
    )

    outcome = runtime.apply(
        ledger,
        round_no=1,
        rejection_summary={"NON_EXACT_QUOTE": 1},
        read_window_count=1,
        plan_digest="digest-1",
    )

    assert runtime.attempts_by_cell == {"entity-1:method": 1}
    assert outcome.external_actions and outcome.external_actions[0]["action"] == "RETRY_EXTRACTION"


@pytest.mark.parametrize(
    ("termination", "expected"),
    [
        ("RETRY_EXHAUSTED", "PROVIDER_UNAVAILABLE"),
        ("EMPTY_CHOICES", "PROVIDER_UNAVAILABLE"),
    ],
)
def test_configured_but_failing_provider_maps_to_infrastructure(
    termination: str, expected: str
) -> None:
    task_input = _task_input()
    plan = build_research_plan(task_input)
    llm = _UnavailableProviderLlm(termination_reason=termination)

    result = run_research_loop(task_input, plan, llm_client=llm)

    assert result.stop_reason_code == expected
