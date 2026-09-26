"""DR-201：``CellRecoveryPolicy`` 决策接口契约测试。

对应 ``docs/DeepResearch-简历能力落地执行计划.md`` DR-201 退出条件
「每个缺证据 Cell 获得显式动作」，证据产物为单元测试。

被测接口（``app/cell_recovery_policy.py``，纯函数、无 I/O）：

``decide(cell_state, rejection_summary, budget, history, thresholds=None)``
-> ``CellRecoveryDecision``

测试意图是把决策性质钉死，任何「阈值散落回调用方」「预算耗尽被伪装成重试」
「换计划但不产生新信息仍无限重试」或「已结算 Cell 被重新打开」都会让本文件失败。
"""

from __future__ import annotations

import pytest

from app.cell_recovery_policy import (
    CellRecoveryAction,
    CellRecoveryBudget,
    CellRecoveryDecision,
    CellRecoveryHistory,
    CellRecoveryThresholds,
    decide,
)

#: ``budget_cost`` 只允许出现这些契约键。
_CONTRACT_BUDGET_KEYS = {
    "search_calls",
    "fetch_calls",
    "read_calls",
    "llm_calls",
    "extract_calls",
}
_MAX_DETAIL_CHARS = 240

#: 会被判定为「已结算」的 Cell 状态。
_SETTLED_STATES = ("VERIFIED", "FROZEN")


def _budget(**overrides: int) -> CellRecoveryBudget:
    values = {
        "remaining_search": 4,
        "remaining_fetch": 4,
        "remaining_read": 4,
        "remaining_llm": 4,
        "remaining_extract": 4,
    }
    values.update(overrides)
    return CellRecoveryBudget(**values)


def _history(**overrides: object) -> CellRecoveryHistory:
    values: dict[str, object] = {
        "attempts": 0,
        "consecutive_failures": 0,
        "actions_taken": (),
        "plan_digests": ("plan-1", "plan-2"),
    }
    values.update(overrides)
    return CellRecoveryHistory(**values)  # type: ignore[arg-type]


def _decide(
    cell_state: str = "GAP",
    rejection_summary: dict[str, int] | None = None,
    *,
    budget: CellRecoveryBudget | None = None,
    history: CellRecoveryHistory | None = None,
    thresholds: CellRecoveryThresholds | None = None,
) -> CellRecoveryDecision:
    return decide(
        cell_state,
        rejection_summary if rejection_summary is not None else {},
        budget if budget is not None else _budget(),
        history if history is not None else _history(),
        thresholds,
    )


# --------------------------------------------------------------------------- #
# 性质 1：确定性（同一输入 -> 完全相同的决策）。
# --------------------------------------------------------------------------- #
def test_decide_is_deterministic_for_identical_inputs() -> None:
    budget = _budget(remaining_search=2)
    history = _history(attempts=1, actions_taken=("RETRY_EXTRACTION",))
    summary = {"NON_EXACT_QUOTE": 2, "EMPTY_CLAIM": 1}

    first = decide("NEEDS_REPAIR", summary, budget, history)
    second = decide("NEEDS_REPAIR", summary, budget, history)

    assert first == second
    assert first.action is second.action
    assert first.reason_code == second.reason_code
    assert first.detail == second.detail
    assert first.budget_cost == second.budget_cost


def test_decide_does_not_observe_input_order_or_mutate_inputs() -> None:
    budget = _budget()
    history = _history()
    summary = {"EMPTY_CLAIM": 1, "NON_EXACT_QUOTE": 1}
    summary_snapshot = dict(summary)

    reordered = decide("GAP", {"NON_EXACT_QUOTE": 1, "EMPTY_CLAIM": 1}, budget, history)
    original = decide("GAP", summary, budget, history)

    assert reordered == original
    assert summary == summary_snapshot
    assert history.plan_digests == ("plan-1", "plan-2")


# --------------------------------------------------------------------------- #
# 性质 2：有界性（触顶后不得再发起外部动作）。
# --------------------------------------------------------------------------- #
def test_attempt_limit_freezes_cell_without_external_action() -> None:
    decision = _decide("NEEDS_REPAIR", {"EMPTY_CLAIM": 1}, history=_history(attempts=3))

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "ATTEMPT_LIMIT_REACHED"
    assert not decision.is_external
    assert decision.budget_cost == {}


def test_consecutive_failure_limit_freezes_cell() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"UNKNOWN_WINDOW": 1},
        history=_history(consecutive_failures=3),
    )

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "ATTEMPT_LIMIT_REACHED"
    assert not decision.is_external


def test_explicit_thresholds_tighten_the_attempt_bound() -> None:
    decision = _decide(
        "GAP",
        {"WRONG_COLUMN": 1},
        history=_history(attempts=1),
        thresholds=CellRecoveryThresholds(max_attempts_per_cell=1),
    )

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "ATTEMPT_LIMIT_REACHED"


def test_conflict_cannot_bypass_the_bounded_guard() -> None:
    decision = _decide(
        "CONFLICT",
        {"CONFLICTING_EVIDENCE": 1},
        history=_history(attempts=5),
    )

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.action is not CellRecoveryAction.COUNTEREVIDENCE_SEARCH


# --------------------------------------------------------------------------- #
# 性质 3：预算优先（所需额度为 0 -> STOP_BUDGET_EXHAUSTED）。
# --------------------------------------------------------------------------- #
def test_targeted_search_without_search_budget_stops() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"WRONG_COLUMN": 1},
        budget=_budget(remaining_search=0),
    )

    assert decision.action is CellRecoveryAction.STOP_BUDGET_EXHAUSTED
    assert decision.reason_code == "BUDGET_EXHAUSTED"
    assert decision.budget_cost == {}


def test_reread_without_read_budget_stops() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"EMPTY_QUOTE": 1},
        budget=_budget(remaining_read=0),
    )

    assert decision.action is CellRecoveryAction.STOP_BUDGET_EXHAUSTED
    assert decision.reason_code == "BUDGET_EXHAUSTED"


@pytest.mark.parametrize("field", ["remaining_llm", "remaining_extract"])
def test_retry_extraction_without_its_budget_stops(field: str) -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"NON_EXACT_QUOTE": 1},
        budget=_budget(**{field: 0}),
    )

    assert decision.action is CellRecoveryAction.STOP_BUDGET_EXHAUSTED
    assert decision.reason_code == "BUDGET_EXHAUSTED"


def test_counterevidence_search_without_search_budget_stops() -> None:
    decision = _decide("CONFLICT", {}, budget=_budget(remaining_search=0))

    assert decision.action is CellRecoveryAction.STOP_BUDGET_EXHAUSTED
    assert decision.reason_code == "BUDGET_EXHAUSTED"


def test_stop_budget_exhausted_never_declares_a_budget_cost() -> None:
    decision = _decide("CONFLICT", {}, budget=_budget(remaining_search=0))

    assert sum(decision.budget_cost.values()) == 0
    assert not decision.is_external


# --------------------------------------------------------------------------- #
# 性质 4：无信息重计划保护。
# --------------------------------------------------------------------------- #
def test_repeated_plan_digests_freeze_the_cell() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"EMPTY_QUOTE": 1},
        history=_history(plan_digests=("digest-a", "digest-a", "digest-a")),
    )

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "REPEATED_PLAN_DIGEST"
    assert not decision.is_external
    assert decision.budget_cost == {}


def test_repeated_plan_digest_freeze_wins_over_conflict() -> None:
    decision = _decide(
        "CONFLICT",
        {"CONFLICTING_EVIDENCE": 1},
        history=_history(plan_digests=("d", "d", "d")),
    )

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "REPEATED_PLAN_DIGEST"


def test_distinct_plan_digests_still_allow_recovery() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"EMPTY_QUOTE": 1},
        history=_history(plan_digests=("d-1", "d-2", "d-3")),
    )

    assert decision.action is CellRecoveryAction.REREAD_WINDOW


def test_explicit_repeated_digest_threshold_is_honoured() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"EMPTY_QUOTE": 1},
        history=_history(plan_digests=("d", "d")),
        thresholds=CellRecoveryThresholds(max_repeated_plan_digests=2),
    )

    assert decision.reason_code == "REPEATED_PLAN_DIGEST"


def test_blank_plan_digests_are_not_treated_as_repetition() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"EMPTY_QUOTE": 1},
        history=_history(plan_digests=("", "", "")),
    )

    assert decision.action is CellRecoveryAction.REREAD_WINDOW


# --------------------------------------------------------------------------- #
# 性质 5：冲突优先。
# --------------------------------------------------------------------------- #
def test_conflict_cell_state_returns_counterevidence_search() -> None:
    decision = _decide("CONFLICT", {})

    assert decision.action is CellRecoveryAction.COUNTEREVIDENCE_SEARCH
    assert decision.reason_code == "CONFLICTING_EVIDENCE"
    assert decision.budget_cost == {"search_calls": 1}


def test_conflict_signal_in_summary_returns_counterevidence_search() -> None:
    decision = _decide("NEEDS_REPAIR", {"CONFLICTING_EVIDENCE": 1})

    assert decision.action is CellRecoveryAction.COUNTEREVIDENCE_SEARCH


def test_non_conflict_reasons_do_not_trigger_counterevidence_search() -> None:
    decision = _decide("NEEDS_REPAIR", {"WRONG_COLUMN": 3})

    assert decision.action is CellRecoveryAction.TARGETED_SEARCH


# --------------------------------------------------------------------------- #
# 性质 6：不可恢复原因（LLM_UNAVAILABLE）不得伪装成预算问题。
# --------------------------------------------------------------------------- #
def test_llm_unavailable_freezes_with_its_own_reason_code() -> None:
    decision = _decide("NEEDS_REPAIR", {"LLM_UNAVAILABLE": 1})

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "LLM_UNAVAILABLE"
    assert decision.budget_cost == {}


def test_llm_unavailable_is_not_reported_as_budget_exhaustion() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"LLM_UNAVAILABLE": 1},
        budget=_budget(
            remaining_search=0,
            remaining_fetch=0,
            remaining_read=0,
            remaining_llm=0,
            remaining_extract=0,
        ),
    )

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.action is not CellRecoveryAction.STOP_BUDGET_EXHAUSTED
    assert decision.reason_code == "LLM_UNAVAILABLE"


def test_llm_unavailable_outranks_recoverable_reasons() -> None:
    decision = _decide("NEEDS_REPAIR", {"LLM_UNAVAILABLE": 1, "NON_EXACT_QUOTE": 5})

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "LLM_UNAVAILABLE"


# --------------------------------------------------------------------------- #
# 性质 7：已完成 Cell 不回退。
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("state", _SETTLED_STATES)
def test_settled_cells_return_unchanged_freeze(state: str) -> None:
    decision = _decide(state, {"EMPTY_QUOTE": 1})

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "CELL_ALREADY_SETTLED"
    assert all(value == 0 for value in decision.budget_cost.values())


@pytest.mark.parametrize("state", _SETTLED_STATES)
def test_settled_cells_do_not_regress_on_conflict(state: str) -> None:
    decision = _decide(state, {"CONFLICTING_EVIDENCE": 2})

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "CELL_ALREADY_SETTLED"
    assert not decision.is_external


# --------------------------------------------------------------------------- #
# 拒绝原因 -> 动作映射（§7.2 建议表逐行）。
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    "reason",
    [
        "INVALID_JSON",
        "NON_OBJECT_CARD",
        "DUPLICATE_EVIDENCE",
        "NON_EXACT_QUOTE",
        "EMPTY_CLAIM",
        "UNSUPPORTED_RELATION",
    ],
)
def test_retry_extraction_reasons(reason: str) -> None:
    decision = _decide("NEEDS_REPAIR", {reason: 1})

    assert decision.action is CellRecoveryAction.RETRY_EXTRACTION
    assert decision.reason_code == reason
    assert decision.budget_cost == {"extract_calls": 1, "llm_calls": 1}


@pytest.mark.parametrize(
    "reason",
    ["EMPTY_QUOTE", "MISSING_WINDOW_ID", "UNKNOWN_WINDOW"],
)
def test_reread_window_reasons(reason: str) -> None:
    decision = _decide("NEEDS_REPAIR", {reason: 1})

    assert decision.action is CellRecoveryAction.REREAD_WINDOW
    assert decision.reason_code == reason
    assert decision.budget_cost == {"read_calls": 1}


@pytest.mark.parametrize("reason", ["WRONG_COLUMN", "MISSING_EVIDENCE_CARDS"])
def test_targeted_search_reasons(reason: str) -> None:
    decision = _decide("NEEDS_REPAIR", {reason: 1})

    assert decision.action is CellRecoveryAction.TARGETED_SEARCH
    assert decision.reason_code == reason
    assert decision.budget_cost == {"search_calls": 1}


def test_no_read_windows_rereads_a_window() -> None:
    decision = _decide("GAP", {"NO_READ_WINDOWS": 1})

    assert decision.action is CellRecoveryAction.REREAD_WINDOW
    assert decision.reason_code == "NO_READ_WINDOWS"


def test_no_read_windows_degrades_to_targeted_search_without_fetch_budget() -> None:
    decision = _decide("GAP", {"NO_READ_WINDOWS": 1}, budget=_budget(remaining_fetch=0))

    assert decision.action is CellRecoveryAction.TARGETED_SEARCH
    assert decision.reason_code == "NO_READ_WINDOWS"


def test_no_read_windows_stops_when_read_budget_is_gone() -> None:
    decision = _decide("GAP", {"NO_READ_WINDOWS": 1}, budget=_budget(remaining_read=0))

    assert decision.action is CellRecoveryAction.STOP_BUDGET_EXHAUSTED
    assert decision.reason_code == "BUDGET_EXHAUSTED"


def test_all_cards_rejected_recurses_into_the_dominant_reason() -> None:
    decision = _decide(
        "NEEDS_REPAIR",
        {"ALL_CARDS_REJECTED": 1, "EMPTY_QUOTE": 1, "WRONG_COLUMN": 3},
    )

    assert decision.action is CellRecoveryAction.TARGETED_SEARCH
    assert decision.reason_code == "WRONG_COLUMN"


def test_dominant_reason_tie_breaks_by_reason_name() -> None:
    decision = _decide("NEEDS_REPAIR", {"UNKNOWN_WINDOW": 1, "EMPTY_QUOTE": 1})

    # 并列时取原因名最小者：EMPTY_QUOTE < UNKNOWN_WINDOW。
    assert decision.reason_code == "EMPTY_QUOTE"
    assert decision.action is CellRecoveryAction.REREAD_WINDOW


# --------------------------------------------------------------------------- #
# 无信号 / STALE / 未知状态。
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("state", ["GAP", "NEEDS_REPAIR"])
def test_cell_without_any_signal_freezes_as_unrecoverable(state: str) -> None:
    decision = _decide(state, {})

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "NO_RECOVERABLE_SIGNAL"
    assert decision.budget_cost == {}


def test_all_cards_rejected_without_card_level_reason_has_no_signal() -> None:
    decision = _decide("NEEDS_REPAIR", {"ALL_CARDS_REJECTED": 1})

    assert decision.action is CellRecoveryAction.FREEZE_UNRESOLVED
    assert decision.reason_code == "NO_RECOVERABLE_SIGNAL"


def test_stale_cell_rereads_the_window() -> None:
    decision = _decide("STALE", {})

    assert decision.action is CellRecoveryAction.REREAD_WINDOW
    assert decision.reason_code == "STALE_EVIDENCE"


def test_stale_cell_stops_when_read_budget_is_gone() -> None:
    decision = _decide("STALE", {}, budget=_budget(remaining_read=0))

    assert decision.action is CellRecoveryAction.STOP_BUDGET_EXHAUSTED


def test_unknown_cell_state_still_recovers_from_a_known_reason() -> None:
    decision = _decide("SOME_NEW_STATE", {"WRONG_COLUMN": 1})

    assert decision.action is CellRecoveryAction.TARGETED_SEARCH


# --------------------------------------------------------------------------- #
# 接口形状：detail 有界、预算键受控、六个动作均可达。
# --------------------------------------------------------------------------- #
def _all_scenarios() -> list[CellRecoveryDecision]:
    return [
        _decide("NEEDS_REPAIR", {"EMPTY_CLAIM": 1}),
        _decide("NEEDS_REPAIR", {"EMPTY_QUOTE": 1}),
        _decide("NEEDS_REPAIR", {"WRONG_COLUMN": 1}),
        _decide("CONFLICT", {}),
        _decide("GAP", {}),
        _decide("NEEDS_REPAIR", {"WRONG_COLUMN": 1}, budget=_budget(remaining_search=0)),
    ]


def test_every_recovery_action_is_reachable() -> None:
    actions = {decision.action for decision in _all_scenarios()}

    assert actions == set(CellRecoveryAction)


@pytest.mark.parametrize("decision", _all_scenarios())
def test_detail_is_bounded_and_free_of_raw_provider_text(decision: CellRecoveryDecision) -> None:
    assert decision.detail
    assert len(decision.detail) <= _MAX_DETAIL_CHARS
    # detail 只允许出现原因码 / 动作名，不允许出现窗口或模型原文标记。
    assert "\n" not in decision.detail


@pytest.mark.parametrize("decision", _all_scenarios())
def test_budget_cost_uses_only_contract_keys(decision: CellRecoveryDecision) -> None:
    assert set(decision.budget_cost) <= _CONTRACT_BUDGET_KEYS
    assert all(value > 0 for value in decision.budget_cost.values())
    assert all(isinstance(key, str) for key in decision.budget_cost)


def test_thresholds_defaults_are_stable() -> None:
    thresholds = CellRecoveryThresholds()

    assert thresholds.max_attempts_per_cell == 3
    assert thresholds.max_consecutive_failures == 3
    assert thresholds.max_repeated_plan_digests == 3
