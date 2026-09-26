"""DR-202：``CellRecoveryPolicy`` 接入真实研究循环的集成测试。

对应 ``docs/DeepResearch-简历能力落地执行计划.md`` DR-202 退出条件
「不重做已完成 Cell，动作受预算限制」，证据产物为「集成测试轨迹」。

被测对象：

- ``app.cell_recovery_runtime``：Cell 级决策编排 + 预算闸门 + 结构化轨迹；
- ``app.loop_runtime.run_research_loop``：真实主路径接入点
  （``ResearchLoopResult.cell_recovery_trace`` 与 checkpoint 的
  ``cell_recovery_trace``）。

覆盖 5 条验收性质：

a. 缺证据 Cell 在循环中获得显式动作（动作 + ``reason_code`` + 轨迹出现）；
b. ``STOP_BUDGET_EXHAUSTED`` 场景下外部调用计数为 0（fake toolchain 计数）；
c. 已完成 Cell 在另一个 Cell 重试时不回退（status / version 不变）；
d. ``FREEZE_UNRESOLVED`` 后该 Cell 不再被调度；
e. 同一输入两次运行得到相同的动作序列（确定性）。

DR-109 追加一节：``plan_digest`` 的「内容摘要」语义（排除 ``plan_revision``）、
「无信息重计划保护」在真实 ``run_research_loop`` 中触发 / 收敛 / 不误触 / 确定性。
"""

from __future__ import annotations

import json

import pytest

from app.cell_recovery_policy import CellRecoveryThresholds
from app.cell_recovery_runtime import (
    CellRecoveryBudgetLedger,
    CellRecoveryOutcome,
    CellRecoveryRuntime,
    apply_cell_recovery_policy,
    derive_recovery_mode,
    map_cell_state,
    plan_digest,
)
from app.loop_runtime import _apply_cell_recovery_stop_guard, run_research_loop
from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchColumn,
    ResearchColumnDtype,
    ResearchEvidenceCard,
    ResearchLoopDecision,
    ResearchPlan,
    ResearchReadWindow,
    ResearchRequiredFindingProgress,
    ResearchSearchHit,
    ResearchStateCell,
    ResearchStateLedger,
    ResearchTaskInput,
)
from app.planner import build_research_plan
from app.research_tools import ResearchRoundArtifacts


# --------------------------------------------------------------------------- #
# 测试替身
# --------------------------------------------------------------------------- #
class _CountingToolchain:
    """只计数、不发生任何外部调用的 toolchain；用于证明「预算闸门先于调用」。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def __call__(self, cell_id: str, action, decision) -> None:  # noqa: ANN001
        self.calls.append((cell_id, action.value))


class _RejectingQuoteLlm:
    """返回一张「引文不在窗口中」的候选卡，触发 NON_EXACT_QUOTE 拒绝。"""

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


def _task_input(sample_text: str = "Stable evidence supports the research loop stop contract.") -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-dr202",
            "workspace_id": "ws-dr202",
            "target_id": "run-dr202",
            "source_scope": [
                {
                    "source_id": "src-dr202",
                    "title": "DR202 Evidence Source",
                    "summary": sample_text,
                    "sample_text": sample_text,
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Do not synthesize without evidence."],
            },
            "input_payload": {
                "question": "How should the research loop recover missing evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _cell(
    cell_id: str,
    *,
    status: str = "NEED_MORE_EVIDENCE",
    evidence_refs: list[str] | None = None,
    repair_count: int = 0,
    version: int = 0,
) -> ResearchStateCell:
    entity_id, column_key = cell_id.split(":", 1)
    return ResearchStateCell(
        cell_id=cell_id,
        row_id=entity_id,
        entity_id=entity_id,
        column_key=column_key,
        candidate_value="candidate",
        status=status,
        evidence_refs=list(evidence_refs or []),
        repair_count=repair_count,
        version=version,
    )


def _ledger(*cells: ResearchStateCell) -> ResearchStateLedger:
    return ResearchStateLedger(cells=list(cells))


def _budget(**overrides: int) -> CellRecoveryBudgetLedger:
    values = {
        "remaining_search": 4,
        "remaining_fetch": 4,
        "remaining_read": 4,
        "remaining_llm": 4,
        "remaining_extract": 4,
    }
    values.update(overrides)
    return CellRecoveryBudgetLedger(**values)


# --------------------------------------------------------------------------- #
# a. 缺证据 Cell 在循环中获得显式动作
# --------------------------------------------------------------------------- #
def test_loop_should_record_explicit_recovery_action_for_evidence_starved_cell() -> None:
    task_input = _task_input()
    plan = build_research_plan(task_input)
    llm = _RejectingQuoteLlm()

    result = run_research_loop(task_input, plan, llm_client=llm)

    assert result.cell_recovery_trace, "loop 必须为缺证据 Cell 留下恢复决策轨迹"
    retry_entries = [
        entry
        for entry in result.cell_recovery_trace
        if entry["action"] == "RETRY_EXTRACTION"
        and entry["reason_code"] == "NON_EXACT_QUOTE"
    ]
    assert retry_entries, result.cell_recovery_trace
    entry = retry_entries[0]
    assert entry["external_call"] is True
    assert entry["frozen"] is False
    assert entry["budget_cost"] == {"extract_calls": 1, "llm_calls": 1}
    assert entry["cell_id"]


def test_loop_checkpoint_should_carry_the_cell_recovery_trace() -> None:
    task_input = _task_input()
    plan = build_research_plan(task_input)
    checkpoints: list[dict[str, object]] = []

    run_research_loop(
        task_input,
        plan,
        llm_client=_RejectingQuoteLlm(),
        checkpoint_callback=checkpoints.append,
    )

    assert checkpoints
    assert all("cell_recovery_trace" in checkpoint for checkpoint in checkpoints)
    assert any(checkpoint["cell_recovery_trace"] for checkpoint in checkpoints)


def test_missing_evidence_cell_maps_to_explicit_targeted_search_action() -> None:
    """无卡但有已读窗口：诚实信号 MISSING_EVIDENCE_CARDS -> TARGETED_SEARCH。"""
    ledger = _ledger(_cell("entity-1:method", status="EMPTY"))
    toolchain = _CountingToolchain()

    outcome = apply_cell_recovery_policy(
        ledger,
        round_no=1,
        budget_ledger=_budget(),
        rejection_summary={},
        toolchain=toolchain,
        read_window_count=1,
    )

    assert [entry.action for entry in outcome.trace] == ["TARGETED_SEARCH"]
    assert outcome.trace[0].reason_code == "MISSING_EVIDENCE_CARDS"
    assert outcome.trace[0].external_call is True
    assert toolchain.calls == [("entity-1:method", "TARGETED_SEARCH")]


# --------------------------------------------------------------------------- #
# b. STOP_BUDGET_EXHAUSTED -> 外部调用计数为 0
# --------------------------------------------------------------------------- #
def test_budget_exhausted_stops_before_any_external_call() -> None:
    ledger = _ledger(_cell("entity-1:method", evidence_refs=["ev-1"]))
    toolchain = _CountingToolchain()
    budget = _budget(remaining_search=0)

    outcome = apply_cell_recovery_policy(
        ledger,
        round_no=1,
        budget_ledger=budget,
        rejection_summary={"WRONG_COLUMN": 1},
        toolchain=toolchain,
    )

    assert outcome.trace[0].action == "STOP_BUDGET_EXHAUSTED"
    assert outcome.trace[0].reason_code == "BUDGET_EXHAUSTED"
    assert outcome.trace[0].external_call is False
    assert outcome.has_external_action is False
    # 关键断言：fake toolchain 从未被调用（预算检查先于外部调用）。
    assert toolchain.calls == []
    # 预算未被扣减。
    assert budget.snapshot() == _budget(remaining_search=0).snapshot()
    assert outcome.ledger.cells[0].status == "NEED_MORE_EVIDENCE"


def test_zero_budget_ledger_never_invokes_toolchain_for_any_action() -> None:
    ledger = _ledger(
        _cell("entity-1:method", evidence_refs=["ev-1"]),
        _cell("entity-2:method", status="CONFLICTED"),
    )
    toolchain = _CountingToolchain()

    outcome = apply_cell_recovery_policy(
        ledger,
        round_no=1,
        budget_ledger=CellRecoveryBudgetLedger.zeroed(),
        rejection_summary={},
        toolchain=toolchain,
    )

    assert toolchain.calls == []
    assert outcome.external_actions == []
    assert {entry.action for entry in outcome.trace} == {"STOP_BUDGET_EXHAUSTED"}


# --------------------------------------------------------------------------- #
# c. 已完成 Cell 不回退
# --------------------------------------------------------------------------- #
def test_settled_cell_is_not_regressed_when_another_cell_retries() -> None:
    verified = _cell(
        "entity-done:method",
        status="VERIFIED",
        evidence_refs=["ev-done"],
        version=7,
    )
    repair_target = _cell("entity-gap:method", evidence_refs=["ev-gap"], repair_count=1)
    ledger = _ledger(verified, repair_target)
    toolchain = _CountingToolchain()

    outcome = apply_cell_recovery_policy(
        ledger,
        round_no=2,
        budget_ledger=_budget(),
        rejection_summary={"UNKNOWN_WINDOW": 1},
        toolchain=toolchain,
    )

    settled_cells = {cell.cell_id: cell for cell in outcome.ledger.cells}
    assert settled_cells["entity-done:method"].status == "VERIFIED"
    assert settled_cells["entity-done:method"].version == 7
    assert settled_cells["entity-done:method"].evidence_refs == ["ev-done"]

    settled_trace = next(
        entry for entry in outcome.trace if entry.cell_id == "entity-done:method"
    )
    assert settled_trace.settled is True
    assert settled_trace.action == "FREEZE_UNRESOLVED"
    assert settled_trace.reason_code == "CELL_ALREADY_SETTLED"
    assert settled_trace.external_call is False
    assert settled_trace.budget_cost == {}

    # 另一个 Cell 正常重试，且被结算的 Cell 从未进入外部动作。
    assert toolchain.calls == [("entity-gap:method", "REREAD_WINDOW")]
    assert all(item["cell_id"] != "entity-done:method" for item in outcome.external_actions)


# --------------------------------------------------------------------------- #
# d. FREEZE_UNRESOLVED 之后不再被调度
# --------------------------------------------------------------------------- #
def test_frozen_cell_is_not_scheduled_in_later_rounds() -> None:
    runtime = CellRecoveryRuntime(
        budget_ledger=_budget(),
        thresholds=CellRecoveryThresholds(max_attempts_per_cell=1),
    )
    ledger = _ledger(_cell("entity-1:method", repair_count=1, evidence_refs=["ev-1"]))

    first = runtime.apply(
        ledger,
        round_no=1,
        rejection_summary={"EMPTY_CLAIM": 1},
        plan_digest="d-1",
    )
    assert first.trace[0].action == "FREEZE_UNRESOLVED"
    assert first.trace[0].reason_code == "ATTEMPT_LIMIT_REACHED"
    assert first.frozen_cell_ids == ["entity-1:method"]
    assert first.ledger.cells[0].status == "FROZEN"
    assert first.ledger.cells[0].verdict_reason == "FROZEN_UNRESOLVED:ATTEMPT_LIMIT_REACHED"

    second = runtime.apply(
        first.ledger,
        round_no=2,
        rejection_summary={"EMPTY_CLAIM": 1},
        plan_digest="d-2",
    )
    assert second.external_actions == []
    assert second.trace[0].action == "FREEZE_UNRESOLVED"
    assert second.trace[0].reason_code == "CELL_ALREADY_SETTLED"
    assert second.trace[0].external_call is False


def test_conflicting_evidence_freezes_once_attempt_limit_is_reached() -> None:
    runtime = CellRecoveryRuntime(
        budget_ledger=_budget(),
        thresholds=CellRecoveryThresholds(max_attempts_per_cell=1),
    )
    ledger = _ledger(_cell("entity-1:method", status="CONFLICTED", repair_count=1))

    outcome = runtime.apply(ledger, round_no=1, plan_digest="d-1")

    assert outcome.trace[0].action == "FREEZE_UNRESOLVED"
    assert outcome.trace[0].action != "COUNTEREVIDENCE_SEARCH"
    assert outcome.external_actions == []


# --------------------------------------------------------------------------- #
# e. 确定性：同一输入两次运行得到相同动作序列
# --------------------------------------------------------------------------- #
def _action_sequence(entries) -> list[tuple[str, str, str]]:
    return [(entry.cell_id, entry.action, entry.reason_code) for entry in entries]


def _run_deterministic_scenario() -> list[tuple[str, str, str]]:
    ledger = _ledger(
        _cell("entity-gap:method", evidence_refs=["ev-1"]),
        _cell("entity-conflict:answer", status="CONFLICTED"),
        _cell("entity-done:method", status="VERIFIED", version=3),
    )
    runtime = CellRecoveryRuntime(budget_ledger=_budget())
    outcome = runtime.apply(
        ledger,
        round_no=1,
        rejection_summary={"NON_EXACT_QUOTE": 2, "EMPTY_QUOTE": 1},
        read_window_count=2,
        plan_digest="digest-a",
    )
    return _action_sequence(outcome.trace)


def test_recovery_decisions_are_deterministic_across_runs() -> None:
    first = _run_deterministic_scenario()
    second = _run_deterministic_scenario()

    assert first == second
    assert first  # 非空，避免空序列的假通过


def test_loop_recovery_trace_is_deterministic_across_runs() -> None:
    def _run() -> list[tuple[str, str, str]]:
        task_input = _task_input()
        plan = build_research_plan(task_input)
        result = run_research_loop(task_input, plan, llm_client=_RejectingQuoteLlm())
        return [
            (str(entry["cell_id"]), str(entry["action"]), str(entry["reason_code"]))
            for entry in result.cell_recovery_trace
        ]

    assert _run() == _run()


# --------------------------------------------------------------------------- #
# 接口形状 / 收敛断言
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    ("status", "expected"),
    [
        ("VERIFIED", "VERIFIED"),
        ("FROZEN", "FROZEN"),
        ("CONFLICTED", "CONFLICT"),
        ("STALE", "STALE"),
        ("EMPTY", "GAP"),
        ("NEED_MORE_EVIDENCE", "NEEDS_REPAIR"),
    ],
)
def test_map_cell_state_uses_policy_vocabulary(status: str, expected: str) -> None:
    assert map_cell_state(_cell("e:c", status=status, evidence_refs=["ev"])) == expected


def test_derive_recovery_mode_prefers_counterevidence_search() -> None:
    assert (
        derive_recovery_mode(["RETRY_EXTRACTION", "COUNTEREVIDENCE_SEARCH", "TARGETED_SEARCH"])
        == "COUNTERFACTUAL_RECHECK"
    )
    assert derive_recovery_mode(["RETRY_EXTRACTION"]) == "EXTRACT_AGAIN"
    assert derive_recovery_mode([]) == ""


def test_budget_ledger_from_plan_never_negative() -> None:
    plan = build_research_plan(_task_input())
    ledger = CellRecoveryBudgetLedger.from_plan(plan)

    assert ledger.remaining_search >= 0
    assert ledger.remaining_llm >= 1
    assert ledger.remaining_extract >= 1


@pytest.mark.parametrize(
    ("limit", "retention", "rounds", "expected"),
    [
        # (global_search_limit, tool_response_retention_budget, max_loop_rounds)
        (13, 7, 5, (13, 7, 7, 5, 5)),
        (0, 0, 1, (0, 0, 0, 1, 1)),
        (2, 4, 9, (2, 4, 4, 9, 9)),
    ],
)
def test_budget_ledger_from_plan_mapping_is_fixed(
    limit: int,
    retention: int,
    rounds: int,
    expected: tuple[int, int, int, int, int],
) -> None:
    """Run 级停止契约 -> Cell 级动作预算的换算表逐项固定。

    DR-405 收口项：M4 用真 ``research_run_budget_ledger`` 替换该换算时，
    本用例是可验证的等价基线。
    """
    plan = build_research_plan(_task_input())
    plan.stop_contract["global_search_limit"] = limit
    plan.stop_contract["tool_response_retention_budget"] = retention
    plan.stop_contract["max_loop_rounds"] = rounds

    ledger = CellRecoveryBudgetLedger.from_plan(plan)

    assert (
        ledger.remaining_search,
        ledger.remaining_fetch,
        ledger.remaining_read,
        ledger.remaining_llm,
        ledger.remaining_extract,
    ) == expected


def test_budget_ledger_from_plan_clamps_invalid_and_missing_values() -> None:
    plan = build_research_plan(_task_input())
    plan.stop_contract.pop("global_search_limit", None)
    plan.stop_contract["tool_response_retention_budget"] = -3
    plan.stop_contract["max_loop_rounds"] = 0

    ledger = CellRecoveryBudgetLedger.from_plan(plan)

    assert ledger.remaining_search == 8  # 文档化默认值
    assert ledger.remaining_fetch == 0
    assert ledger.remaining_read == 0
    assert ledger.remaining_llm == 1  # max(1, max_loop_rounds)
    assert ledger.remaining_extract == 1


# --------------------------------------------------------------------------- #
# stop guard 的 handoff_required 必须与 terminal_disposition 语义一致
# --------------------------------------------------------------------------- #
def _artifacts_with_verified(verified_row_count: int) -> ResearchRoundArtifacts:
    return ResearchRoundArtifacts(
        search_hits=[],
        fetched_documents=[],
        read_windows=[],
        evidence_cards=[],
        ledger=ResearchStateLedger(verified_row_count=verified_row_count),
        local_result=LocalVerifierResult(status="WARN"),
        branch_decisions=[],
        global_result=GlobalVerifierResult(
            status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="halted"
        ),
    )


def test_stop_guard_handoff_required_matches_terminal_disposition() -> None:
    outcome = CellRecoveryOutcome(
        ledger=ResearchStateLedger(),
        frozen_cell_ids=["entity-1:method"],
    )
    decision = ResearchLoopDecision(
        decision="READ_MORE",
        reason="GLOBAL_VERIFIER_NOT_READY",
        round_no=1,
        should_continue=True,
    )

    without_coverage = _apply_cell_recovery_stop_guard(decision, outcome, _artifacts_with_verified(0))
    assert without_coverage.should_continue is False
    assert without_coverage.reason == "CELL_RECOVERY_HALTED"
    assert without_coverage.terminal_disposition == "HUMAN_HANDOFF"
    assert without_coverage.handoff_required is True

    with_coverage = _apply_cell_recovery_stop_guard(decision, outcome, _artifacts_with_verified(1))
    assert with_coverage.terminal_disposition == "GUARDED_COMPLETE"
    assert with_coverage.handoff_required is False


# --------------------------------------------------------------------------- #
# 动作分支落地：REREAD_WINDOW 只重读窗口，不重复搜索
# --------------------------------------------------------------------------- #
def test_reread_window_recovery_mode_does_not_repeat_search(monkeypatch) -> None:
    import app.research_tools as research_tools_module

    search_calls: list[int] = []
    monkeypatch.setattr(
        research_tools_module,
        "run_research_search",
        lambda *args, **kwargs: search_calls.append(1) or [],
    )

    from app.research_tools import ResearchToolbox

    task_input = _task_input()
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "REREAD_WINDOW"
    prior = ResearchRoundArtifacts(
        search_hits=[],
        fetched_documents=[],
        read_windows=[],
        evidence_cards=[],
        ledger=ResearchStateLedger(),
        local_result=LocalVerifierResult(status="WARN"),
        branch_decisions=[],
        global_result=GlobalVerifierResult(
            status="WARN", decision="WRITE_WITH_GUARDRAILS", summary="recovery"
        ),
    )
    toolbox = ResearchToolbox(llm_client=None)

    _hits, trace = toolbox.search_web(
        task_input,
        plan,
        round_no=2,
        prior_artifacts=prior,
        recovery_mode="REREAD_WINDOW",
    )

    assert trace.status == "reused"
    assert search_calls == []


# --------------------------------------------------------------------------- #
# DR-109：plan_digest 必须是「内容摘要」，无信息重计划保护必须真实生效
# --------------------------------------------------------------------------- #
def _digest_plan() -> ResearchPlan:
    return build_research_plan(_task_input())


def test_plan_digest_ignores_plan_revision_when_content_is_identical() -> None:
    """核心断言：内容相同、``plan_revision`` 不同 -> 摘要相同。

    证伪修复前行为：旧 ``plan_digest`` 把 ``plan_revision`` 计入摘要，而
    ``_augment_plan_for_next_round`` 每轮 ``+1``，因此旧实现下本断言必然失败。
    """
    baseline = _digest_plan()
    revised = baseline.model_copy(update={"plan_revision": baseline.plan_revision + 7})

    assert baseline.plan_revision != revised.plan_revision
    assert plan_digest(baseline) == plan_digest(revised)


def test_plan_digest_is_deterministic_for_identical_content() -> None:
    assert plan_digest(_digest_plan()) == plan_digest(_digest_plan())


def test_plan_digest_changes_when_query_set_changes() -> None:
    baseline = _digest_plan()
    changed = baseline.model_copy(
        update={"query_set": [*baseline.query_set, "additional evidence query"]}
    )

    assert plan_digest(baseline) != plan_digest(changed)


def test_plan_digest_changes_when_schema_columns_change() -> None:
    baseline = _digest_plan()
    changed = baseline.model_copy(deep=True)
    changed.research_schema.columns.append(
        ResearchColumn(
            key="extra_column",
            label="Extra Column",
            dtype=ResearchColumnDtype.TEXT,
            required=False,
        )
    )

    assert plan_digest(baseline) != plan_digest(changed)


def test_plan_digest_changes_when_recovery_mode_changes() -> None:
    baseline = _digest_plan()
    changed = baseline.model_copy(deep=True)
    changed.stop_contract["recovery_mode"] = "TARGETED_SEARCH"

    assert plan_digest(baseline) != plan_digest(changed)


def test_plan_digest_ignores_per_round_wall_clock_bookkeeping() -> None:
    """``wall_clock_seconds_consumed`` 每轮被重写；若计入摘要，内容摘要会永远变化。"""
    baseline = _digest_plan()
    advanced = baseline.model_copy(deep=True)
    advanced.stop_contract["wall_clock_seconds_consumed"] = 123.456

    assert plan_digest(baseline) == plan_digest(advanced)


def _stubbed_loop_plan() -> ResearchPlan:
    """放宽环次 / 重复失败 / Cell 尝试上限，让「无信息重计划」成为停止的唯一原因。"""
    plan = build_research_plan(_task_input())
    plan.stop_contract["max_loop_rounds"] = 6
    plan.stop_contract["global_search_limit"] = 40
    plan.stop_contract["max_repeated_failures"] = 20
    plan.stop_contract["max_retry_per_cell"] = 10
    return plan


def _loop_search_hit() -> ResearchSearchHit:
    return ResearchSearchHit(
        hit_id="hit-1",
        source_id="src-dr202",
        source_title="DR202 Evidence Source",
        query="q",
        rank=1,
        snippet="snippet",
        confidence_score=0.9,
        retrieval_reason="direct",
    )


def _loop_read_window() -> ResearchReadWindow:
    return ResearchReadWindow(
        window_id="window-1",
        hit_id="hit-1",
        source_id="src-dr202",
        source_title="DR202 Evidence Source",
        query="q",
        read_focus="direct",
        window_text="Stable evidence supports the research loop stop contract.",
        retention_reason="retention",
        token_estimate=8,
    )


def _loop_evidence_card() -> ResearchEvidenceCard:
    return ResearchEvidenceCard(
        evidence_id="ev-1",
        window_id="window-1",
        source_id="src-dr202",
        source_title="DR202 Evidence Source",
        claim_text="Stable evidence supports the research loop.",
        quote_text="Stable evidence supports the research loop stop contract.",
        relation_type="SUPPORTS",
        support_score=1.0,
        conflict_score=0.0,
    )


def _loop_cell(cell_id: str) -> ResearchStateCell:
    entity_id, column_key = cell_id.split(":", 1)
    return ResearchStateCell(
        cell_id=cell_id,
        row_id=entity_id,
        entity_id=entity_id,
        column_key=column_key,
        candidate_value="candidate",
        status="NEED_MORE_EVIDENCE",
        evidence_refs=["ev-1"],
        repair_count=0,
    )


class _LoopStubToolbox:
    """只替换 I/O 边界的 toolbox 替身。

    计划演进（``_augment_plan_for_next_round``）、``plan_digest``、Cell 恢复策略与
    停止保护全部是生产实现；本替身只按轮次给出可复现的 ``ResearchRoundArtifacts``
    并记录「真实发生了多少次外部轮次」，因此不涉及把相同摘要人为塞进 history。
    """

    def __init__(self, *, requirement_label, extra_cell_from_round: int | None = None) -> None:
        self.requirement_label = requirement_label
        self.extra_cell_from_round = extra_cell_from_round
        self.rounds: list[int] = []

    def __call__(self, *args, **kwargs) -> "_LoopStubToolbox":  # 兼容 ResearchToolbox(...)
        return self

    def execute_round(self, task_input, plan, round_no, prior_artifacts=None):
        self.rounds.append(round_no)
        cells = [_loop_cell("entity-1:method")]
        if self.extra_cell_from_round is not None and round_no >= self.extra_cell_from_round:
            cells.append(_loop_cell("entity-2:method"))
        ledger = ResearchStateLedger(
            cells=cells,
            required_finding_progress=[
                ResearchRequiredFindingProgress(
                    requirement_id="requirement-1",
                    requirement_type="GOAL_FINDING",
                    label=self.requirement_label(round_no),
                    required_columns=["method"],
                    status="MISSING",
                )
            ],
        )
        return ResearchRoundArtifacts(
            search_hits=[_loop_search_hit()],
            fetched_documents=[],
            read_windows=[_loop_read_window()],
            evidence_cards=[_loop_evidence_card()],
            ledger=ledger,
            local_result=LocalVerifierResult(status="WARN"),
            branch_decisions=[],
            global_result=GlobalVerifierResult(
                status="WARN",
                decision="WRITE_WITH_GUARDRAILS",
                summary="global verifier not ready",
            ),
        )


def _run_loop_with_stub(
    monkeypatch,
    toolbox: _LoopStubToolbox,
) -> tuple[object, list[tuple[int, str]]]:
    """在真实 ``run_research_loop`` 上跑 stub toolbox，并记录每轮 Plan 的 (revision, digest)。"""
    import app.loop_runtime as loop_runtime_module

    monkeypatch.setattr(loop_runtime_module, "ResearchToolbox", toolbox)

    observed: list[tuple[int, str]] = []
    # 直接取未被打桩的真实实现，避免同一测试内重复运行时 spy 互相包裹。
    import app.cell_recovery_runtime as recovery_runtime_module

    real_digest = recovery_runtime_module.plan_digest

    def _spy(plan: ResearchPlan) -> str:
        value = real_digest(plan)
        observed.append((int(plan.plan_revision), value))
        return value

    monkeypatch.setattr(loop_runtime_module, "plan_digest", _spy)
    result = loop_runtime_module.run_research_loop(
        _task_input(), _stubbed_loop_plan(), llm_client=None
    )
    return result, observed


def test_repeated_plan_digest_protection_converges_real_loop(monkeypatch) -> None:
    """真实 loop：计划连续多轮内容不变时，保护触发并使 Cell 收敛。"""
    toolbox = _LoopStubToolbox(
        requirement_label=lambda _round: "Answer with evidence",
        extra_cell_from_round=3,
    )

    result, observed = _run_loop_with_stub(monkeypatch, toolbox)

    # 1) 真实 loop 内 plan_revision 逐轮 +1，但内容相同的轮次摘要相同。
    #    修复前的 plan_digest 计入 plan_revision，会得到 4 个互不相同的摘要，
    #    因此 REPEATED_PLAN_DIGEST 在真实链路中永不触发（DR-109 缺陷）。
    revisions = [item[0] for item in observed]
    digests = [item[1] for item in observed]
    assert revisions == [0, 1, 2, 3], observed
    assert len(set(digests)) == 2, observed
    assert digests[1] == digests[2] == digests[3], observed

    # 2) 保护被触发，Cell 收敛为 FROZEN_UNRESOLVED。
    digest_entries = [
        entry
        for entry in result.cell_recovery_trace
        if entry["reason_code"] == "REPEATED_PLAN_DIGEST"
    ]
    assert digest_entries, result.cell_recovery_trace
    assert all(entry["action"] == "FREEZE_UNRESOLVED" for entry in digest_entries)
    assert all(entry["external_call"] is False for entry in digest_entries)
    assert all(entry["frozen"] is True for entry in digest_entries)

    frozen_cells = {
        cell.cell_id: cell
        for cell in result.artifacts.ledger.cells
        if cell.status == "FROZEN"
    }
    protected_cell_id = str(digest_entries[0]["cell_id"])
    assert protected_cell_id in frozen_cells
    assert "REPEATED_PLAN_DIGEST" in frozen_cells[protected_cell_id].verdict_reason

    # 3) 收敛后不再产生外部调用：冻结轮就是最后一轮（计数断言，不是「看起来停了」）。
    freeze_round = int(digest_entries[0]["round_no"])
    assert freeze_round == len(result.rounds) == 4
    assert toolbox.rounds == [1, 2, 3, 4]
    assert result.final_decision.should_continue is False
    assert not [
        entry
        for entry in result.cell_recovery_trace
        if int(entry["round_no"]) > freeze_round and entry["external_call"]
    ]

    # 4) ATTEMPT_LIMIT_REACHED 仍是兜底（同一轮另一条 Cell 走的是尝试上限）。
    assert any(
        entry["reason_code"] == "ATTEMPT_LIMIT_REACHED"
        for entry in result.cell_recovery_trace
    )


def test_repeated_plan_digest_scenario_is_deterministic(monkeypatch) -> None:
    def _run():
        toolbox = _LoopStubToolbox(
            requirement_label=lambda _round: "Answer with evidence",
            extra_cell_from_round=3,
        )
        result, observed = _run_loop_with_stub(monkeypatch, toolbox)
        actions = [
            (
                entry["round_no"],
                entry["cell_id"],
                entry["action"],
                entry["reason_code"],
            )
            for entry in result.cell_recovery_trace
        ]
        return observed, actions, toolbox.rounds

    first = _run()
    assert first == _run()
    assert first[0] and first[1]  # 非空，避免空序列的假通过


def test_repeated_plan_digest_does_not_fire_while_plan_content_evolves(monkeypatch) -> None:
    """计划内容确实在变化时（每轮出现新的待满足要求）保护不得触发。"""
    toolbox = _LoopStubToolbox(
        requirement_label=lambda round_no: f"Answer with evidence (round {round_no})",
        extra_cell_from_round=None,
    )

    result, observed = _run_loop_with_stub(monkeypatch, toolbox)

    digests = [item[1] for item in observed]
    assert len(digests) == 4
    assert len(set(digests)) == 4, observed  # 每轮都有真实内容变化
    assert result.cell_recovery_trace
    assert not [
        entry
        for entry in result.cell_recovery_trace
        if entry["reason_code"] == "REPEATED_PLAN_DIGEST"
    ]
