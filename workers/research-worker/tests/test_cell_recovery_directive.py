"""DR-108：恢复规则收敛的集成 / 单元断言。

在此之前，「下一轮做什么」（``loop_runtime.evaluate_loop_decision``）、「是否必须
停止」（``loop_stop_guard.evaluate_loop_stop_guard``）、「单个 Cell 怎么恢复」
（``cell_recovery_runtime``）是三套互不知晓的判断，产生三类可观察的不一致：

1. Cell 级策略请求的 ``recovery_mode`` 被 loop 静默改写（请求 / 生效两套取值）；
2. 停止保护判定之后，本轮仍留下 ``external_call=True`` 的轨迹；
3. 停止保护冻结的 Cell 在轨迹里仍是 ``frozen=False``。

DR-108 把三者的结论收敛进 :class:`app.cell_recovery_runtime.CellRecoveryDirective`，
本文件断言这三类不一致在**真实主路径**上不再出现。
"""

from __future__ import annotations

import json

import pytest

from app.cell_recovery_policy import CellRecoveryThresholds
from app.cell_recovery_runtime import (
    RECOVERY_MODE_SOURCE_CELL_POLICY,
    RECOVERY_MODE_SOURCE_LOOP_DECISION,
    RECOVERY_MODE_SOURCE_NONE,
    CellRecoveryBudgetLedger,
    CellRecoveryRuntime,
    apply_cell_recovery_directive,
    apply_cell_recovery_policy,
    build_cell_recovery_directive,
    reconcile_recovery_mode,
    resolve_loop_recovery_decision,
)
from app.loop_runtime import evaluate_loop_decision, run_research_loop
from app.models import (
    GlobalVerifierResult,
    ResearchStateCell,
    ResearchStateLedger,
    ResearchTaskInput,
)
from app.planner import build_research_plan


# --------------------------------------------------------------------------- #
# 测试替身
# --------------------------------------------------------------------------- #
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


def _task_input(
    sample_text: str = "Stable evidence supports the research loop stop contract.",
) -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-dr108",
            "workspace_id": "ws-dr108",
            "target_id": "run-dr108",
            "source_scope": [
                {
                    "source_id": "src-dr108",
                    "title": "DR108 Evidence Source",
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


def _cell(cell_id: str, *, status: str = "EMPTY", repair_count: int = 0) -> ResearchStateCell:
    entity_id, column_key = cell_id.split(":", 1)
    return ResearchStateCell(
        cell_id=cell_id,
        row_id=entity_id,
        entity_id=entity_id,
        column_key=column_key,
        candidate_value="candidate",
        status=status,
        repair_count=repair_count,
    )


def _budget() -> CellRecoveryBudgetLedger:
    return CellRecoveryBudgetLedger(
        remaining_search=4,
        remaining_fetch=4,
        remaining_read=4,
        remaining_llm=4,
        remaining_extract=4,
    )


def _outcome_with_external_action():
    """一个「无卡但有已读窗口」的 Cell -> TARGETED_SEARCH（外部动作）。"""
    ledger = ResearchStateLedger(cells=[_cell("entity-1:method")])
    return apply_cell_recovery_policy(
        ledger,
        round_no=1,
        budget_ledger=_budget(),
        read_window_count=1,
    )


# --------------------------------------------------------------------------- #
# 1. recovery_mode 的收敛规则（谁最终决定，可断言）
# --------------------------------------------------------------------------- #
def test_reconcile_accepts_in_family_refinement_from_cell_policy() -> None:
    assert reconcile_recovery_mode(
        decision_family="READ_MORE", requested_recovery_mode="TARGETED_SEARCH"
    ) == ("TARGETED_SEARCH", RECOVERY_MODE_SOURCE_CELL_POLICY)
    assert reconcile_recovery_mode(
        decision_family="READ_MORE", requested_recovery_mode="REREAD_WINDOW"
    ) == ("REREAD_WINDOW", RECOVERY_MODE_SOURCE_CELL_POLICY)


def test_reconcile_keeps_loop_family_and_reports_the_source() -> None:
    # 同族但无请求：决策族自身生效
    assert reconcile_recovery_mode(
        decision_family="READ_MORE", requested_recovery_mode=""
    ) == ("READ_MORE", RECOVERY_MODE_SOURCE_LOOP_DECISION)
    # 跨族请求不再被静默接受：生效值仍由决策族决定，请求值另行保留
    assert reconcile_recovery_mode(
        decision_family="EXTRACT_AGAIN", requested_recovery_mode="TARGETED_SEARCH"
    ) == ("EXTRACT_AGAIN", RECOVERY_MODE_SOURCE_LOOP_DECISION)
    assert reconcile_recovery_mode(
        decision_family="COUNTERFACTUAL_RECHECK", requested_recovery_mode="REREAD_WINDOW"
    ) == ("COUNTERFACTUAL_RECHECK", RECOVERY_MODE_SOURCE_LOOP_DECISION)


def test_reconcile_returns_no_mode_for_non_recovery_families() -> None:
    assert reconcile_recovery_mode(
        decision_family="SYNTHESIZE_REPORT", requested_recovery_mode="TARGETED_SEARCH"
    ) == ("", RECOVERY_MODE_SOURCE_NONE)


# --------------------------------------------------------------------------- #
# 2. 停止保护给出结论后，本轮不再保留外部动作
# --------------------------------------------------------------------------- #
def test_directive_suppresses_external_actions_once_the_guard_decides() -> None:
    outcome = _outcome_with_external_action()
    assert outcome.external_actions, "前置条件：本轮确实计划了外部动作"

    directive = build_cell_recovery_directive(
        round_no=1,
        decision_family="EXTRACT_AGAIN",
        decision_reason="READ_WINDOWS_WITHOUT_EVIDENCE",
        recovery_outcome=outcome,
        stop_reason_code="PROVIDER_NOT_CONFIGURED",
        stop_reason_category="INFRASTRUCTURE",
        external_call_allowed=False,
    )

    # 「请求 vs 生效」不再是两套无法解释的取值
    assert directive.requested_recovery_mode == "TARGETED_SEARCH"
    assert directive.recovery_mode == ""
    assert directive.recovery_mode_source == RECOVERY_MODE_SOURCE_NONE
    assert directive.external_call_allowed is False
    assert directive.suppressed_external_actions == tuple(outcome.external_actions)
    assert directive.planned_external_actions == ()

    amended = apply_cell_recovery_directive(outcome, directive)
    assert [entry.external_call for entry in amended.trace] == [False]
    assert [entry.external_call_suppressed for entry in amended.trace] == [True]
    assert amended.external_actions == []
    # 预算原额退还：动作没有发生，就不该占用额度
    assert amended.budget_ledger.snapshot() == _budget().snapshot()
    # 轨迹保留策略当时的判断，不改写历史
    assert amended.trace[0].action == outcome.trace[0].action
    assert amended.trace[0].reason_code == outcome.trace[0].reason_code


def test_directive_keeps_external_actions_while_the_loop_still_runs() -> None:
    outcome = _outcome_with_external_action()
    directive = build_cell_recovery_directive(
        round_no=1,
        decision_family="READ_MORE",
        decision_reason="REQUIRED_FINDINGS_MISSING",
        recovery_outcome=outcome,
        external_call_allowed=True,
    )
    assert directive.recovery_mode == "TARGETED_SEARCH"
    assert directive.suppressed_external_actions == ()
    assert apply_cell_recovery_directive(outcome, directive) is outcome


# --------------------------------------------------------------------------- #
# 3. 被冻结的 Cell 在轨迹里如实标记
# --------------------------------------------------------------------------- #
def test_apply_directive_marks_guard_frozen_cells_in_trace() -> None:
    outcome = _outcome_with_external_action()
    directive = build_cell_recovery_directive(
        round_no=2,
        decision_family="READ_MORE",
        decision_reason="REQUIRED_FINDINGS_MISSING",
        recovery_outcome=outcome,
        freeze_cell_ids=("entity-1:method",),
        external_call_allowed=True,
    )
    amended = apply_cell_recovery_directive(outcome, directive)

    assert amended.trace[0].frozen is True
    assert amended.trace[0].status_after == "FROZEN"
    assert amended.frozen_cell_ids == ["entity-1:method"]


# --------------------------------------------------------------------------- #
# 4. 真实主路径：三类不一致不再出现
# --------------------------------------------------------------------------- #
def test_infrastructure_stop_leaves_no_planned_external_call() -> None:
    task = _task_input()
    result = run_research_loop(task, build_research_plan(task), llm_client=None)

    assert result.stop_reason_code == "PROVIDER_NOT_CONFIGURED"
    assert result.stop_reason_category == "INFRASTRUCTURE"
    # 循环已停止：本轮不得再留下任何「计划中的外部调用」
    assert result.external_recovery_call_count == 0
    assert all(not entry["external_call"] for entry in result.cell_recovery_trace)
    assert result.cell_recovery_directives
    assert all(
        directive["external_call_allowed"] is False
        for directive in result.cell_recovery_directives
    )
    assert all(
        directive["recovery_mode"] == "" for directive in result.cell_recovery_directives
    )


def test_repeated_cell_failure_freeze_is_visible_in_the_trace() -> None:
    task = _task_input()
    result = run_research_loop(task, build_research_plan(task), llm_client=_RejectingQuoteLlm())

    assert result.stop_reason_code == "REPEATED_CELL_FAILURE"
    last_round = result.rounds[-1].round_no
    tail = [
        entry
        for entry in result.cell_recovery_trace
        if int(entry["round_no"]) == last_round
    ]
    assert tail
    assert all(entry["frozen"] for entry in tail if not entry["settled"])
    assert all(
        entry["status_after"] == "FROZEN"
        for entry in tail
        if not entry["settled"]
    )
    frozen_in_ledger = {
        cell.cell_id for cell in result.artifacts.ledger.cells if cell.status == "FROZEN"
    }
    assert frozen_in_ledger == {
        entry["cell_id"] for entry in tail if not entry["settled"]
    }


def test_directives_align_with_rounds_and_final_stop_reason() -> None:
    task = _task_input()
    result = run_research_loop(task, build_research_plan(task), llm_client=_RejectingQuoteLlm())

    # 每轮一条指令，按轮对齐
    assert [directive["round_no"] for directive in result.cell_recovery_directives] == [
        item.round_no for item in result.rounds
    ]
    # 最后一条指令的停止原因就是对外上报的停止原因（不再有第二处取值）
    assert result.cell_recovery_directives[-1]["stop_reason_code"] == result.stop_reason_code
    assert (
        result.cell_recovery_directives[-1]["stop_reason_category"]
        == result.stop_reason_category
    )


def test_directive_convergence_is_deterministic() -> None:
    def _signature() -> tuple:
        task = _task_input()
        result = run_research_loop(
            task, build_research_plan(task), llm_client=_RejectingQuoteLlm()
        )
        return (
            result.stop_reason_code,
            result.external_recovery_call_count,
            tuple(
                (
                    directive["round_no"],
                    directive["recovery_mode"],
                    directive["requested_recovery_mode"],
                    directive["recovery_mode_source"],
                    directive["external_call_allowed"],
                )
                for directive in result.cell_recovery_directives
            ),
        )

    assert _signature() == _signature()


# --------------------------------------------------------------------------- #
# 5. 收敛等价性：策略动作 -> loop 决策族 的唯一映射（5 类代表性场景）
# --------------------------------------------------------------------------- #
def _equivalence_outcome(
    ledger: ResearchStateLedger,
    *,
    rejection: dict[str, int] | None = None,
    read_window_count: int = 0,
    budget: CellRecoveryBudgetLedger | None = None,
    max_attempts_per_cell: int | None = None,
):
    """经 ``CellRecoveryRuntime``（真实编排层）取得策略产物，不直接构造替身。"""
    thresholds = (
        CellRecoveryThresholds(max_attempts_per_cell=max_attempts_per_cell)
        if max_attempts_per_cell is not None
        else None
    )
    runtime = CellRecoveryRuntime(
        budget_ledger=budget or _budget(),
        thresholds=thresholds,
    )
    return runtime.apply(
        ledger,
        round_no=1,
        rejection_summary=rejection,
        read_window_count=read_window_count,
    )


@pytest.mark.parametrize(
    ("scenario", "ledger", "kwargs", "expected_actions", "expected_family"),
    [
        (
            "缺证据重试：schema / grounding 拒绝 -> 重抽",
            ResearchStateLedger(cells=[_cell("entity-1:method")]),
            {"rejection": {"NON_EXACT_QUOTE": 1}, "read_window_count": 1},
            [("entity-1:method", "RETRY_EXTRACTION", "NON_EXACT_QUOTE")],
            ("EXTRACT_AGAIN", "READ_WINDOWS_WITHOUT_EVIDENCE"),
        ),
        (
            "持续失败：尝试次数触顶 -> 冻结，不再有外部动作",
            ResearchStateLedger(cells=[_cell("entity-1:method", repair_count=1)]),
            {"rejection": {"EMPTY_CLAIM": 1}, "max_attempts_per_cell": 1},
            [("entity-1:method", "FREEZE_UNRESOLVED", "ATTEMPT_LIMIT_REACHED")],
            None,
        ),
        (
            "预算耗尽：所需额度不足 -> 停止，绝不返回必然失败的动作",
            ResearchStateLedger(cells=[_cell("entity-1:method")]),
            {
                "rejection": {"WRONG_COLUMN": 1},
                "budget": CellRecoveryBudgetLedger(
                    remaining_search=0,
                    remaining_fetch=4,
                    remaining_read=4,
                    remaining_llm=4,
                    remaining_extract=4,
                ),
            },
            [("entity-1:method", "STOP_BUDGET_EXHAUSTED", "BUDGET_EXHAUSTED")],
            None,
        ),
        (
            "冲突反证：反向证据 -> 受限反证搜索",
            ResearchStateLedger(
                cells=[_cell("entity-1:method", status="CONFLICTED")]
            ),
            {},
            [("entity-1:method", "COUNTEREVIDENCE_SEARCH", "CONFLICTING_EVIDENCE")],
            ("COUNTERFACTUAL_RECHECK", "CONFLICTING_EVIDENCE"),
        ),
        (
            "无搜索命中 / 无已读窗口：先重开窗口，不越级搜索",
            ResearchStateLedger(cells=[_cell("entity-1:method")]),
            {"read_window_count": 0},
            [("entity-1:method", "REREAD_WINDOW", "NO_READ_WINDOWS")],
            ("READ_MORE", "SEARCH_HITS_WITHOUT_READ_WINDOWS"),
        ),
    ],
)
def test_recovery_action_sequence_is_fixed_for_representative_scenarios(
    scenario: str,
    ledger: ResearchStateLedger,
    kwargs: dict,
    expected_actions: list[tuple[str, str, str]],
    expected_family: tuple[str, str] | None,
) -> None:
    """5 类代表性场景的「有效动作序列 + reason_code + loop 决策族」固定。

    这些期望是收敛**前后不变**的外部可观察行为：动作与 reason_code 由策略权威给出，
    loop 决策族只是策略动作的渲染（``resolve_loop_recovery_decision``）。
    """
    outcome = _equivalence_outcome(ledger, **kwargs)

    actual_actions = [
        (entry.cell_id, entry.action, entry.reason_code) for entry in outcome.trace
    ]
    assert actual_actions == expected_actions, scenario
    assert resolve_loop_recovery_decision(outcome) == expected_family, scenario


@pytest.mark.parametrize(
    ("rejection_reason", "policy_action", "expected_family", "expected_mode"),
    [
        ("EMPTY_QUOTE", "REREAD_WINDOW", "READ_MORE", "REREAD_WINDOW"),
        ("UNKNOWN_WINDOW", "REREAD_WINDOW", "READ_MORE", "REREAD_WINDOW"),
        ("WRONG_COLUMN", "TARGETED_SEARCH", "READ_MORE", "TARGETED_SEARCH"),
    ],
)
def test_missing_evidence_family_follows_policy_request_not_legacy_extract_again(
    rejection_reason: str,
    policy_action: str,
    expected_family: str,
    expected_mode: str,
) -> None:
    """锁定 DR-108 的**目标行为**：``read>0 & cards==0`` 时决策族由策略动作决定。

    收敛前，这两个「重读窗口 / 定向搜索」拒绝原因会被 loop 的本地 `read_window_count>0`
    规则覆盖为 ``EXTRACT_AGAIN``，且 ``reconcile_recovery_mode`` 会**跨族否决**策略请求，
    使生效 ``recovery_mode`` 与策略判断相反（重复注定失败的抽取）。这正是 §7.2 禁止的
    「两套权威」。收敛后：决策族 = ``READ_MORE``，生效 ``recovery_mode`` 取策略请求值
    （来源 ``CELL_POLICY``）。本用例把该行为锁死，使其是有意目标而非偶然产物。
    """
    ledger = ResearchStateLedger(cells=[_cell("entity-1:method")])
    outcome = _equivalence_outcome(
        ledger, rejection={rejection_reason: 1}, read_window_count=1
    )

    # 前置条件：策略确实给出了「重读 / 搜」动作，而不是重抽。
    assert [item["action"] for item in outcome.external_actions] == [policy_action]
    assert resolve_loop_recovery_decision(outcome) == (
        expected_family,
        "SEARCH_HITS_WITHOUT_READ_WINDOWS",
    )

    decision = evaluate_loop_decision(
        plan=build_research_plan(_task_input()),
        round_no=1,
        search_hit_count=1,
        read_window_count=1,
        evidence_card_count=0,
        has_conflict=False,
        ledger=ledger,
        global_result=GlobalVerifierResult(
            status="WARN",
            decision="WRITE_WITH_GUARDRAILS",
            summary="missing evidence",
        ),
        recovery_outcome=outcome,
    )

    assert decision.decision == expected_family
    assert decision.should_continue is True
    # 生效 recovery_mode 取策略请求值（同族细化），策略是唯一权威。
    assert reconcile_recovery_mode(
        decision_family=decision.decision,
        requested_recovery_mode=outcome.requested_recovery_mode,
    ) == (expected_mode, RECOVERY_MODE_SOURCE_CELL_POLICY)


# --------------------------------------------------------------------------- #
# 6. 证伪：loop 不再按本地计数规则选择恢复动作族
# --------------------------------------------------------------------------- #
def test_evaluate_loop_decision_defers_to_cell_policy_action_not_local_counts_rule() -> None:
    """证伪收敛前的行为：决策族必须来自策略动作，而不是 ``read_window_count`` 计数规则。

    收敛前 ``evaluate_loop_decision`` 在 ``read_window_count > 0`` 且零卡时**无条件**
    返回 ``EXTRACT_AGAIN``（与策略动作无关）。本用例构造策略给出 ``TARGETED_SEARCH``
    的产物（此时 ``read_window_count`` 仍 > 0），断言决策族为 ``READ_MORE``。
    旧实现下本断言必然失败。
    """
    ledger = ResearchStateLedger(cells=[_cell("entity-1:method")])
    outcome = _equivalence_outcome(
        ledger, rejection={"WRONG_COLUMN": 1}, read_window_count=1
    )
    assert [item["action"] for item in outcome.external_actions] == ["TARGETED_SEARCH"]

    decision = evaluate_loop_decision(
        plan=build_research_plan(_task_input()),
        round_no=1,
        search_hit_count=1,
        read_window_count=1,
        evidence_card_count=0,
        has_conflict=False,
        ledger=ledger,
        global_result=GlobalVerifierResult(
            status="WARN",
            decision="WRITE_WITH_GUARDRAILS",
            summary="missing evidence",
        ),
        recovery_outcome=outcome,
    )

    assert decision.decision == "READ_MORE"
    assert decision.reason == "SEARCH_HITS_WITHOUT_READ_WINDOWS"
    assert decision.should_continue is True


def test_evaluate_loop_decision_without_recovery_outcome_does_not_self_decide() -> None:
    """无策略产物时，loop 不得自行发明「重抽 / 搜 / 重读」恢复动作族。

    收敛前该场景会返回 ``EXTRACT_AGAIN``；收敛后缺证据分支被策略接管，没有策略产物
    时分支不生效，loop 退回通用循环控制（此处由提前承诺保护给出 ``READ_MORE``）。
    """
    decision = evaluate_loop_decision(
        plan=build_research_plan(_task_input()),
        round_no=1,
        search_hit_count=1,
        read_window_count=1,
        evidence_card_count=0,
        has_conflict=False,
        ledger=ResearchStateLedger(),
        global_result=GlobalVerifierResult(
            status="WARN",
            decision="WRITE_WITH_GUARDRAILS",
            summary="missing evidence",
        ),
    )

    assert decision.reason != "READ_WINDOWS_WITHOUT_EVIDENCE"
    assert decision.reason != "SEARCH_HITS_WITHOUT_READ_WINDOWS"
    assert decision.decision == "READ_MORE"
    assert decision.reason == "PREMATURE_COMMITMENT_BLOCKED"


# --------------------------------------------------------------------------- #
# 7. 无搜索命中：loop 级停止，绝不计划外部恢复调用
# --------------------------------------------------------------------------- #
def _task_without_sources() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-dr108-empty",
            "workspace_id": "ws-dr108",
            "target_id": "run-dr108-empty",
            "source_scope": [],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "What if there is no evidence at all?",
                "profile_key": "DEFAULT",
            },
        }
    )


def test_no_search_hits_stop_never_plans_a_recovery_external_call() -> None:
    task = _task_without_sources()
    result = run_research_loop(task, build_research_plan(task))

    assert result.final_decision.decision == "EXPAND_SOURCE_SCOPE"
    assert result.stop_reason_code == "NO_SEARCH_HITS"
    assert result.external_recovery_call_count == 0
    assert all(not entry["external_call"] for entry in result.cell_recovery_trace)
