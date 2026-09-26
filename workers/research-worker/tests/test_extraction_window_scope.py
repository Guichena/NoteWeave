"""DR-105：抽取窗口作用域（Evidence Horizon scope）契约测试。

问题背景：``_extract_evidence_cards_detailed_with_llm`` 只把 ``_select_focused_windows``
选出的窗口放进 prompt，但校验用的 ``windows_by_id`` 却由**全部** ``read_windows`` 建索引。
当 ``len(read_windows) > evidence_horizon_window_budget`` 时，模型若引用了一个真实存在、
但本次并未展示给它的窗口，该卡会被接受——这违反了「只能引用本次抽取真正展示的窗口」的契约。

本文件把作用域钉死在 focused_windows 上，并复用既有的 ``UNKNOWN_WINDOW`` 原因码
（不新增枚举取值，避免与 Java 侧硬编码词表漂移）。

证伪机制（关键）：缺陷用例选取的 ``window_id`` 是**真实存在**的窗口（只是未被选中），
且 ``quote_text`` 是**该窗口自己的逐字原文**。因此在修复前的实现下：
``windows_by_id`` 命中该窗口 -> 引文逐字匹配 -> 卡片被接受（``accepted_count == 1``）。
修复后该窗口不在 focused 作用域内 -> 落到 ``UNKNOWN_WINDOW``。
若某天作用域再次被放宽，本文件第一条用例立刻失败。
"""

from __future__ import annotations

import json

import pytest

from app.extraction_result import ExtractionFailureReason, ExtractionTerminationReason
from app.extractor import _select_focused_windows, extract_evidence_cards_detailed
from app.llm_client import FakeLlmClient
from app.models import (
    ResearchColumn,
    ResearchPlan,
    ResearchReadWindow,
    ResearchSchema,
    ResearchTaskInput,
)

#: 与生产默认值一致：evidence_horizon_window_budget 缺省为 5。
_WINDOW_BUDGET = 5
#: 目标列；focused 排序的第一关键字是「窗口是否命中 evidence_horizon_target_columns」。
_COLUMN_KEY = "method"


# --------------------------------------------------------------------------- #
# fixture 构造
# --------------------------------------------------------------------------- #
def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-window-scope",
            "workspace_id": "ws-window-scope",
            "target_id": "run-window-scope",
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Reject unsupported conclusions."],
            },
            "input_payload": {
                "question": "How should synthesis use evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _build_plan() -> ResearchPlan:
    """budget=5 且目标列为 method 的 plan；focused 排序完全由 target_columns 决定。"""
    return ResearchPlan(
        normalized_question="How should synthesis use evidence?",
        research_schema=ResearchSchema(
            columns=[ResearchColumn(key=_COLUMN_KEY, label="Method")],
        ),
        stop_contract={
            "evidence_horizon_window_budget": _WINDOW_BUDGET,
            "evidence_horizon_target_columns": [_COLUMN_KEY],
        },
    )


def _build_window(index: int, *, focused: bool) -> ResearchReadWindow:
    """每个窗口有独立 source_id 与独立原文，避免卡片之间互相撞 evidence_id。"""
    return ResearchReadWindow(
        window_id=f"window-{index}",
        hit_id=f"hit-{index}",
        source_id=f"src-{index}",
        source_title=f"Window Scope Source {index}",
        query="How should synthesis use evidence?",
        read_focus="window scope",
        window_text=f"Source {index} states a distinct grounded fact number {index}.",
        retention_reason="window scope fixture",
        token_estimate=16,
        target_columns=[_COLUMN_KEY] if focused else [],
    )


def _build_windows(focused_count: int, unfocused_count: int) -> list[ResearchReadWindow]:
    """前 focused_count 个命中目标列（会被选中），其余不命中（不会被选中）。"""
    return [
        *(_build_window(index, focused=True) for index in range(focused_count)),
        *(
            _build_window(focused_count + index, focused=False)
            for index in range(unfocused_count)
        ),
    ]


def _card(window: ResearchReadWindow, **overrides: object) -> dict[str, object]:
    card: dict[str, object] = {
        "window_id": window.window_id,
        "entity_id": "",
        "entity_name": "",
        "column_key": _COLUMN_KEY,
        "claim_text": f"Source {window.window_id} supports the method column.",
        # 逐字引用 window 自己的原文：修复前这条可以通过 NON_EXACT_QUOTE 校验。
        "quote_text": window.window_text,
        "relation_type": "SUPPORTS",
        "support_score": 0.9,
        "conflict_score": 0.02,
    }
    card.update(overrides)
    return card


def _run(
    plan: ResearchPlan,
    read_windows: list[ResearchReadWindow],
    cards: list[object],
):
    return extract_evidence_cards_detailed(
        _build_task_input(),
        plan,
        read_windows,
        llm_client=FakeLlmClient(
            {"research.extract": json.dumps({"evidence_cards": cards})}
        ),
    )


# --------------------------------------------------------------------------- #
# 0. fixture 前提：确认哪些窗口真的没被展示给模型
# --------------------------------------------------------------------------- #
def test_focused_selection_matches_expectation() -> None:
    """直接引用生产排序函数（不复制实现），确认哪几个窗口在作用域内。"""
    plan = _build_plan()
    read_windows = _build_windows(focused_count=5, unfocused_count=2)

    assert len(read_windows) > _WINDOW_BUDGET  # 缺陷只在窗口数超预算时可达
    focused = _select_focused_windows(plan, read_windows, max_windows=_WINDOW_BUDGET)

    assert [window.window_id for window in focused] == [
        "window-0",
        "window-1",
        "window-2",
        "window-3",
        "window-4",
    ]
    assert set(window.window_id for window in focused).isdisjoint({"window-5", "window-6"})


# --------------------------------------------------------------------------- #
# 1. 缺陷用例：真实但未展示的窗口必须落到 UNKNOWN_WINDOW
# --------------------------------------------------------------------------- #
def test_card_citing_unshown_real_window_is_rejected_as_unknown_window() -> None:
    plan = _build_plan()
    read_windows = _build_windows(focused_count=5, unfocused_count=2)
    focused_ids = {
        window.window_id
        for window in _select_focused_windows(plan, read_windows, max_windows=_WINDOW_BUDGET)
    }

    unshown_window = read_windows[5]
    # 前提：该窗口真实存在（修复前的 windows_by_id=read_windows 一定能命中它）。
    assert unshown_window.window_id in {window.window_id for window in read_windows}
    assert unshown_window.window_id not in focused_ids
    # 前提：引文是该窗口自己的逐字原文（修复前不会被 NON_EXACT_QUOTE 拦下）。
    candidate = _card(unshown_window)
    assert str(candidate["quote_text"]) in unshown_window.window_text

    result = _run(plan, read_windows, [_card(unshown_window)])

    assert result.rejection_counts() == {"UNKNOWN_WINDOW": 1}
    assert result.accepted_count == 0
    assert result.termination_reason is ExtractionTerminationReason.ALL_CARDS_REJECTED
    assert result.rejected_cards[0].reason is ExtractionFailureReason.UNKNOWN_WINDOW
    assert result.rejected_cards[0].window_id == unshown_window.window_id


def test_unshown_window_rejection_is_deterministic() -> None:
    plan = _build_plan()
    read_windows = _build_windows(focused_count=5, unfocused_count=2)
    unshown_window = read_windows[6]

    first = _run(plan, read_windows, [_card(unshown_window)])
    second = _run(plan, read_windows, [_card(unshown_window)])

    assert first.rejection_counts() == second.rejection_counts() == {"UNKNOWN_WINDOW": 1}
    assert first.diagnostics() == second.diagnostics()


def test_mixed_batch_counts_only_unshown_windows_as_unknown() -> None:
    """同一批里既有展示过的也有未展示的窗口：只拒绝后者，不误伤前者。"""
    plan = _build_plan()
    read_windows = _build_windows(focused_count=5, unfocused_count=2)
    cards = [
        _card(read_windows[0]),
        _card(read_windows[5]),
        _card(read_windows[1]),
        _card(read_windows[6]),
    ]

    result = _run(plan, read_windows, cards)

    assert result.rejection_counts() == {"UNKNOWN_WINDOW": 2}
    assert result.accepted_count == 2
    assert [card.window_id for card in result.accepted_cards] == ["window-0", "window-1"]
    assert result.termination_reason is ExtractionTerminationReason.ACCEPTED_CARDS


# --------------------------------------------------------------------------- #
# 2. 对照组：被展示的窗口必须照常接受（防止作用域收得过严）
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("focused_index", [0, 1, 2, 3, 4])
def test_card_citing_focused_window_is_accepted(focused_index: int) -> None:
    plan = _build_plan()
    read_windows = _build_windows(focused_count=5, unfocused_count=2)
    focused_ids = {
        window.window_id
        for window in _select_focused_windows(plan, read_windows, max_windows=_WINDOW_BUDGET)
    }
    focused_window = read_windows[focused_index]
    assert focused_window.window_id in focused_ids

    result = _run(plan, read_windows, [_card(focused_window)])

    assert result.rejection_counts() == {}
    assert result.accepted_count == 1
    assert result.accepted_cards[0].window_id == focused_window.window_id
    assert result.accepted_cards[0].quote_start >= 0
    assert result.termination_reason is ExtractionTerminationReason.ACCEPTED_CARDS


def test_all_focused_windows_are_accepted_in_one_batch() -> None:
    plan = _build_plan()
    read_windows = _build_windows(focused_count=5, unfocused_count=2)
    focused = _select_focused_windows(plan, read_windows, max_windows=_WINDOW_BUDGET)

    result = _run(plan, read_windows, [_card(window) for window in focused])

    assert result.rejection_counts() == {}
    assert result.accepted_count == 5


# --------------------------------------------------------------------------- #
# 3. 回归护栏：窗口数不超过预算时，全部窗口都在作用域内（行为与修复前一致）
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("window_count", [1, 2, 5])
def test_every_window_is_in_scope_when_within_budget(window_count: int) -> None:
    plan = _build_plan()
    read_windows = _build_windows(focused_count=window_count, unfocused_count=0)

    assert len(read_windows) <= _WINDOW_BUDGET
    focused = _select_focused_windows(plan, read_windows, max_windows=_WINDOW_BUDGET)
    assert [window.window_id for window in focused] == [
        window.window_id for window in read_windows
    ]

    result = _run(plan, read_windows, [_card(window) for window in read_windows])

    assert result.rejection_counts() == {}
    assert result.accepted_count == window_count
    assert sorted(card.window_id for card in result.accepted_cards) == sorted(
        window.window_id for window in read_windows
    )


def test_last_window_within_budget_is_accepted() -> None:
    """预算恰好用满时（len == budget），最后一个窗口也不能被排除。"""
    plan = _build_plan()
    read_windows = _build_windows(focused_count=_WINDOW_BUDGET, unfocused_count=0)

    result = _run(plan, read_windows, [_card(read_windows[-1])])

    assert result.rejection_counts() == {}
    assert result.accepted_count == 1
    assert result.accepted_cards[0].window_id == read_windows[-1].window_id
