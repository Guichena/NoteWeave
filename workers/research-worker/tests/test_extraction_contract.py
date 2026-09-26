"""DR-103：EvidenceExtraction 抽取拒绝原因契约测试。

对应 ``docs/DeepResearch-简历能力落地执行计划.md`` DR-103 退出条件
「每类错误有确定 reason code」，证据产物为测试报告。

被测契约（DR-101 已实现，本文件只做只读验证，不改任何 app/ 实现）：

- ``app/extractor.py``：``extract_evidence_cards_detailed`` 结构化出口；
- ``app/extraction_result.py``：原因词表、拒绝记录、``rejection_counts()`` /
  ``diagnostics()`` 的有界输出契约。

测试意图是「把每个拒绝/终止出口钉死在确定 reason code 上」，任何原因词表回退、
终止原因塌缩为笼统空值、或 diagnostics 泄漏模型原文都会让本文件失败。
"""

from __future__ import annotations

import json

import pytest

from app.extraction_result import (
    REQUIRED_REJECTION_REASONS,
    ExtractionFailureReason,
    ExtractionTerminationReason,
)
from app.extractor import extract_evidence_cards, extract_evidence_cards_detailed
from app.llm_client import FakeLlmClient
from app.models import ResearchPlan, ResearchReadWindow, ResearchTaskInput
from app.planner import build_research_plan
from app.read_adapters import run_research_read
from app.search import run_workspace_search

#: 契约上限常量（与 app/extraction_result.py 保持一致）。
_MAX_REJECTION_SAMPLES = 8
_MAX_DETAIL_CHARS = 240
#: 模型原文不得出现在 diagnostics 中；用一个不可能被巧合命中的字段名做哨兵。
_RAW_RESPONSE_SENTINEL = "provider_response_raw"


# --------------------------------------------------------------------------- #
# fixture：复用 tests/test_llm_extract_verify.py 的 plan / read_window 构造方式，
# 不另造一套 ResearchPlan / ResearchReadWindow 夹具。
# --------------------------------------------------------------------------- #
def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-contract-1",
            "workspace_id": "ws-1",
            "target_id": "run-contract-1",
            "source_scope": [
                {
                    "source_id": "src-llm",
                    "title": "LLM Evidence Source",
                    "summary": "The source says verifier gated synthesis must cite opened windows.",
                    "sample_text": (
                        "Verifier gated synthesis must cite opened windows and reject "
                        "unsupported claims."
                    ),
                }
            ],
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


def _build_case() -> tuple[ResearchTaskInput, ResearchPlan, list[ResearchReadWindow]]:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    assert read_windows, "fixture precondition: planner + read must yield at least one window"
    return task_input, plan, read_windows


def _model_column_key(plan: ResearchPlan) -> str:
    """返回 schema 中真实存在的列 key，保证「只有目标字段被拒绝」。"""
    assert plan.research_schema.columns, "fixture precondition: schema must expose columns"
    return plan.research_schema.columns[0].key


def _exact_quote(window: ResearchReadWindow) -> str:
    """返回 window 原文中的连续精确子串（非精确引文测试的对照基线）。"""
    quote = "Verifier gated synthesis must cite opened windows"
    assert quote in window.window_text, "fixture precondition: sample text must be in window"
    return quote


def _card(plan: ResearchPlan, window: ResearchReadWindow, **overrides: object) -> dict[str, object]:
    """构造一张「其余字段全部合法」的候选卡，便于单点注入某类缺陷。"""
    card: dict[str, object] = {
        "window_id": window.window_id,
        "entity_id": "",
        "entity_name": "",
        "column_key": _model_column_key(plan),
        "claim_text": "Verifier gated synthesis must cite opened windows.",
        "quote_text": _exact_quote(window),
        "relation_type": "SUPPORTS",
        "support_score": 0.9,
        "conflict_score": 0.02,
    }
    card.update(overrides)
    return card


def _run(task_input: ResearchTaskInput, plan: ResearchPlan, read_windows, response: str):
    return extract_evidence_cards_detailed(
        task_input,
        plan,
        read_windows,
        llm_client=FakeLlmClient({"research.extract": response}),
    )


def _run_cards(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    read_windows,
    cards: list[object],
):
    return _run(task_input, plan, read_windows, json.dumps({"evidence_cards": cards}))


# --------------------------------------------------------------------------- #
# 1. 卡片级拒绝原因：每条用独立用例，断言 rejection_counts() 精确匹配。
# --------------------------------------------------------------------------- #
def test_non_object_card_is_rejected_with_reason_code() -> None:
    task_input, plan, read_windows = _build_case()

    result = _run_cards(task_input, plan, read_windows, ["not-an-object"])

    assert result.rejection_counts() == {"NON_OBJECT_CARD": 1}
    assert result.accepted_count == 0
    assert result.termination_reason is ExtractionTerminationReason.ALL_CARDS_REJECTED


@pytest.mark.parametrize("blank_window_id", ["", "   "])
def test_missing_window_id_is_rejected_with_reason_code(blank_window_id: str) -> None:
    task_input, plan, read_windows = _build_case()

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], window_id=blank_window_id)],
    )

    assert result.rejection_counts() == {"MISSING_WINDOW_ID": 1}
    assert result.accepted_count == 0


def test_unknown_window_is_rejected_with_reason_code() -> None:
    task_input, plan, read_windows = _build_case()
    hallucinated = "window-does-not-exist"

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], window_id=hallucinated)],
    )

    assert result.rejection_counts() == {"UNKNOWN_WINDOW": 1}
    assert result.rejected_cards[0].window_id == hallucinated


def test_wrong_column_is_rejected_with_reason_code() -> None:
    task_input, plan, read_windows = _build_case()
    bad_column = "__no_such_schema_column__"

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], column_key=bad_column)],
    )

    assert result.rejection_counts() == {"WRONG_COLUMN": 1}
    assert result.rejected_cards[0].column_key == bad_column


@pytest.mark.parametrize("blank_quote", ["", "   "])
def test_empty_quote_is_rejected_with_reason_code(blank_quote: str) -> None:
    task_input, plan, read_windows = _build_case()

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], quote_text=blank_quote)],
    )

    assert result.rejection_counts() == {"EMPTY_QUOTE": 1}
    assert result.accepted_count == 0


def test_non_exact_quote_is_rejected_with_reason_code() -> None:
    task_input, plan, read_windows = _build_case()
    rewritten = "This sentence is a paraphrase rewritten by the model, not the window text."

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], quote_text=rewritten)],
    )

    assert result.rejection_counts() == {"NON_EXACT_QUOTE": 1}
    assert result.accepted_count == 0


def test_empty_claim_is_rejected_with_reason_code() -> None:
    task_input, plan, read_windows = _build_case()

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], claim_text="")],
    )

    assert result.rejection_counts() == {"EMPTY_CLAIM": 1}
    assert result.accepted_count == 0


def test_unsupported_relation_is_rejected_with_reason_code() -> None:
    task_input, plan, read_windows = _build_case()

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], relation_type="MAYBE")],
    )

    assert result.rejection_counts() == {"UNSUPPORTED_RELATION": 1}
    assert result.rejected_cards[0].reason is ExtractionFailureReason.UNSUPPORTED_RELATION
    assert result.accepted_count == 0


def test_duplicate_evidence_is_rejected_with_reason_code() -> None:
    task_input, plan, read_windows = _build_case()
    first = _card(plan, read_windows[0])

    result = _run_cards(task_input, plan, read_windows, [first, dict(first)])

    assert result.rejection_counts() == {"DUPLICATE_EVIDENCE": 1}
    assert result.accepted_count == 1
    assert result.termination_reason is ExtractionTerminationReason.ACCEPTED_CARDS


# --------------------------------------------------------------------------- #
# 2. 终止原因：零卡 Run 必须落到确定原因，而不是笼统空。
# --------------------------------------------------------------------------- #
def test_no_read_windows_termination() -> None:
    task_input, plan, _read_windows = _build_case()

    result = extract_evidence_cards_detailed(
        task_input,
        plan,
        [],
        llm_client=FakeLlmClient({"research.extract": json.dumps({"evidence_cards": []})}),
    )

    assert result.termination_reason is ExtractionTerminationReason.NO_READ_WINDOWS
    assert result.accepted_count == 0
    assert result.rejected_count == 0
    assert result.provider_receipt.call_count == 0


def test_llm_unavailable_termination() -> None:
    task_input, plan, read_windows = _build_case()

    result = extract_evidence_cards_detailed(task_input, plan, read_windows, llm_client=None)

    assert result.termination_reason is ExtractionTerminationReason.LLM_UNAVAILABLE
    assert result.accepted_count == 0
    assert result.provider_receipt.call_count == 0


@pytest.mark.parametrize("bad_response", ["", "{not json"])
def test_invalid_json_termination(bad_response: str) -> None:
    task_input, plan, read_windows = _build_case()

    result = _run(task_input, plan, read_windows, bad_response)

    assert result.termination_reason is ExtractionTerminationReason.INVALID_JSON
    assert result.accepted_count == 0
    assert result.provider_receipt.call_count == 1
    assert result.provider_receipt.response_chars == len(bad_response)


@pytest.mark.parametrize(
    "response",
    [
        json.dumps({"other_key": []}),  # 合法 JSON，但缺 evidence_cards
        json.dumps({"evidence_cards": []}),  # 空候选列表
        json.dumps({"evidence_cards": "not-a-list"}),  # evidence_cards 类型非法
    ],
)
def test_missing_evidence_cards_termination(response: str) -> None:
    task_input, plan, read_windows = _build_case()

    result = _run(task_input, plan, read_windows, response)

    assert result.termination_reason is ExtractionTerminationReason.MISSING_EVIDENCE_CARDS
    assert result.accepted_count == 0
    assert result.rejected_count == 0


def test_all_cards_rejected_termination() -> None:
    task_input, plan, read_windows = _build_case()

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], quote_text="Paraphrased, not verbatim from the window.")],
    )

    assert result.accepted_count == 0
    assert result.rejected_count == 1
    assert result.termination_reason is ExtractionTerminationReason.ALL_CARDS_REJECTED


def test_accepted_cards_termination() -> None:
    task_input, plan, read_windows = _build_case()

    result = _run_cards(task_input, plan, read_windows, [_card(plan, read_windows[0])])

    assert result.accepted_count == 1
    assert result.termination_reason is ExtractionTerminationReason.ACCEPTED_CARDS


# --------------------------------------------------------------------------- #
# 3. 向后兼容：旧入口 == 结构化入口的 accepted_cards。
# --------------------------------------------------------------------------- #
def test_legacy_entrypoint_matches_detailed_accepted_cards() -> None:
    task_input, plan, read_windows = _build_case()
    accepted = _card(plan, read_windows[0])
    rejected = _card(
        plan,
        read_windows[0],
        quote_text="Rewritten by the model instead of copied character-for-character.",
    )
    response = json.dumps({"evidence_cards": [accepted, rejected]})

    legacy = extract_evidence_cards(
        task_input,
        plan,
        read_windows,
        llm_client=FakeLlmClient({"research.extract": response}),
    )
    detailed = extract_evidence_cards_detailed(
        task_input,
        plan,
        read_windows,
        llm_client=FakeLlmClient({"research.extract": response}),
    )

    assert legacy == list(detailed.accepted_cards)
    assert len(legacy) == 1
    assert detailed.rejection_counts() == {"NON_EXACT_QUOTE": 1}
    assert legacy[0].evidence_id == detailed.accepted_cards[0].evidence_id
    assert legacy[0].quote_start == detailed.accepted_cards[0].quote_start
    assert legacy[0].quote_end == detailed.accepted_cards[0].quote_end
    assert legacy[0].quote_start >= 0


# --------------------------------------------------------------------------- #
# 4. diagnostics 契约：可序列化、有界、不泄漏模型原文。
# --------------------------------------------------------------------------- #
def test_diagnostics_is_json_serializable_and_bounded() -> None:
    task_input, plan, read_windows = _build_case()
    cards: list[object] = [_card(plan, read_windows[0])]  # 1 张被接受
    cards += [
        _card(plan, read_windows[0], window_id=f"unknown-window-{index}")
        for index in range(10)
    ]  # 10 张 UNKNOWN_WINDOW -> 触发 rejection_samples 上限
    response = json.dumps({"evidence_cards": cards})

    result = _run(task_input, plan, read_windows, response)

    diagnostics = result.diagnostics()
    serialized = json.dumps(diagnostics, ensure_ascii=False)
    assert isinstance(serialized, str) and serialized

    counts = diagnostics["rejection_counts"]
    assert counts == {"UNKNOWN_WINDOW": 10}
    assert sum(counts.values()) == result.rejected_count == diagnostics["rejected_count"]

    samples = diagnostics["rejection_samples"]
    assert len(samples) <= _MAX_REJECTION_SAMPLES
    assert len(samples) == _MAX_REJECTION_SAMPLES  # 上限确实生效
    assert all(len(sample["detail"]) <= _MAX_DETAIL_CHARS for sample in samples)

    # 模型原文绝不落盘：完整响应不是 diagnostics JSON 的子串。
    assert response not in serialized
    assert _RAW_RESPONSE_SENTINEL not in serialized


def test_rejection_detail_is_truncated_to_contract_limit() -> None:
    task_input, plan, read_windows = _build_case()
    huge_window_id = "w" * 500

    result = _run_cards(
        task_input,
        plan,
        read_windows,
        [_card(plan, read_windows[0], window_id=huge_window_id)],
    )

    assert result.rejection_counts() == {"UNKNOWN_WINDOW": 1}
    assert len(result.rejected_cards[0].detail) <= _MAX_DETAIL_CHARS
    samples = result.diagnostics()["rejection_samples"]
    assert all(len(sample["detail"]) <= _MAX_DETAIL_CHARS for sample in samples)


# --------------------------------------------------------------------------- #
# 5. 词表不回退：第 7.1 节点名的原因必须存在且字面稳定。
# --------------------------------------------------------------------------- #
def test_required_rejection_reason_vocabulary_does_not_regress() -> None:
    assert set(REQUIRED_REJECTION_REASONS) <= set(ExtractionFailureReason)

    expected_literals = {
        ExtractionFailureReason.INVALID_JSON: "INVALID_JSON",
        ExtractionFailureReason.UNKNOWN_WINDOW: "UNKNOWN_WINDOW",
        ExtractionFailureReason.WRONG_COLUMN: "WRONG_COLUMN",
        ExtractionFailureReason.NON_EXACT_QUOTE: "NON_EXACT_QUOTE",
        ExtractionFailureReason.EMPTY_CLAIM: "EMPTY_CLAIM",
        ExtractionFailureReason.UNSUPPORTED_RELATION: "UNSUPPORTED_RELATION",
    }
    for member, literal in expected_literals.items():
        assert member.value == literal
        assert member in REQUIRED_REJECTION_REASONS
