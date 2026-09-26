"""DR-204：循环与预算停止保护的唯一判定点。

本模块把「研究循环什么时候必须停止」的全部保护条件收敛到一处，
避免同一个「停止」判断散落在 ``loop_runtime`` / ``cell_recovery_runtime`` /
``runner`` 的多个 ``if`` 分支里。调用方只调用一次
:func:`evaluate_loop_stop_guard`，按返回的 ``reason_code`` 决定是否改写循环决策。

设计边界：

- 本模块只做**判定**，不定义业务终态。DR-305 的 ``RunCompletionGate`` 是业务终态
  的唯一权威；``loop_runtime`` 写进 ``ResearchLoopDecision`` 的
  ``terminal_disposition`` 仅是建议值。
- 阈值不在此处重新发明：Cell 尝试上限来自 ``CellRecoveryThresholds``
  （由调用方从 ``stop_contract.max_retry_per_cell`` 派生并传入），本模块只新增
  「同一失败原因连续重复」这一循环级保护（``max_repeated_failures``）。
- 判定是纯函数：同一输入必定返回同一结果，不读环境变量 / 时间 / 随机数。
- 与 ``loop_runtime.evaluate_loop_decision`` 的分工：后者负责「下一轮该做什么」
  （CONTINUE 决策族），本模块负责「是否必须停止」以及停止原因码。两者长期并存，
  但**停止判定与原因码**只在本模块产出；两者的结论不再各说各话——DR-108 已把
  「决策族 + 停止判定 + Cell 级恢复」收敛进
  :class:`app.cell_recovery_runtime.CellRecoveryDirective`，由
  ``loop_runtime`` 在每轮末尾构建一次并回写轨迹 / 预算 / ``recovery_mode``。
- 循环开头的墙钟短路检查（避免超预算后仍开启新一轮）复用同一预算阈值函数
  ``_wall_clock_budget_exhausted``，不引入第二套墙钟语义。

``reason_code`` 全量取值（稳定、machine-readable）：

基础设施类 ``INFRASTRUCTURE_REASON_CODES`` —— Provider 未配置或不可用，**不得**
伪装成证据不足（DR-107）：

- ``PROVIDER_NOT_CONFIGURED``：Run 级 Provider 未配置（如 ``llm_client`` 为空），
  且本轮确实到达抽取阶段（存在可读窗口）。零卡的根因是配置缺失，而不是没有证据。
- ``PROVIDER_UNAVAILABLE``：Provider 已配置，但最近一次抽取调用的收据表明是基础设施
  失败（``RETRY_EXHAUSTED`` / ``NON_RETRYABLE_HTTP_*`` / ``EMPTY_CHOICES`` /
  ``EMPTY_MESSAGE``），且本轮没有可用证据。

资源类 ``RESOURCE_REASON_CODES`` —— 硬预算，先于 Provider / 证据判断：

- ``WALL_CLOCK_BUDGET_EXHAUSTED``：任务级墙钟预算耗尽。
- ``LOOP_BUDGET_EXHAUSTED``：轮次上限耗尽。
- ``CELL_ATTEMPT_LIMIT_REACHED``：某个 Cell 的恢复尝试次数触顶。
- ``REPEATED_CELL_FAILURE``：同一 Cell 以相同失败原因连续多轮未收敛。

证据类 ``EVIDENCE_REASON_CODES`` —— 业务结论候选，仍由 loop 建议、RunCompletionGate 裁决：

- ``NO_SEARCH_HITS``：所有检索通道有效结果为零。
- ``CELL_RECOVERY_HALTED``：恢复策略判定无任何可执行外部动作。

判定优先级（自上而下，先命中先返回，确定且互斥）：

1. ``WALL_CLOCK_BUDGET_EXHAUSTED``；
2. ``NO_SEARCH_HITS``（检索为零时 Provider 从未被使用，不能归因为基础设施）；
3. ``PROVIDER_NOT_CONFIGURED`` / ``PROVIDER_UNAVAILABLE``；
4. ``REPEATED_CELL_FAILURE``（同时冻结全部未收敛 Cell）；
5. ``CELL_ATTEMPT_LIMIT_REACHED``；
6. ``LOOP_BUDGET_EXHAUSTED``；
7. ``CELL_RECOVERY_HALTED``。

外部调用上界：每个未收敛 Cell 每轮至多一次外部恢复动作，且该 Cell 全程的外部
恢复动作不超过 ``max_attempts_per_cell``；因此整轮外部恢复动作数的明确上界是
``min(max_loop_rounds, max_attempts_per_cell) * cell_count``。Provider 调用次数由
每轮的搜索 / 抓取 / 抽取预算另行封顶，本模块暴露
:func:`external_recovery_call_upper_bound` 供调用方与测试直接断言。
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum

from app.models import ResearchStateLedger

__all__ = [
    "ALL_REASON_CODES",
    "BUSINESS_INSUFFICIENT_EVIDENCE_CODES",
    "CELL_BOUND_REASON_CODES",
    "DEFAULT_MAX_REPEATED_FAILURES",
    "EVIDENCE_REASON_CODES",
    "INFRASTRUCTURE_REASON_CODES",
    "LoopStopDecision",
    "LoopStopGuardInputs",
    "LoopStopGuardResult",
    "LoopStopReason",
    "RESOURCE_REASON_CODES",
    "dominant_rejection_reason",
    "evaluate_loop_stop_guard",
    "external_recovery_call_upper_bound",
    "freeze_unresolved_cells",
    "provider_failure_signal",
    "reason_category",
]

#: 业务终态「证据不足」词表：基础设施类原因**禁止**落入这些取值。
BUSINESS_INSUFFICIENT_EVIDENCE_CODES = frozenset({"INSUFFICIENT_EVIDENCE"})

#: 「同一失败原因连续重复」的默认阈值：连续两轮相同主导失败原因即视为未收敛。
DEFAULT_MAX_REPEATED_FAILURES = 2

#: 已结算 / 冲突 Cell 不参与「重复失败冻结」；前者已结束，后者由冲突分支处理。
_SETTLED_CELL_STATES = frozenset({"VERIFIED", "FROZEN"})
_UNRESOLVED_EXCLUDED_STATES = frozenset({"VERIFIED", "FROZEN", "CONFLICTED"})

#: LLM 收据中表示「Provider 侧基础设施失败」的终止原因码。
_INFRASTRUCTURE_CALL_TERMINATIONS = frozenset(
    {
        "RETRY_EXHAUSTED",
        "EMPTY_CHOICES",
        "EMPTY_MESSAGE",
    }
)
_NON_RETRYABLE_HTTP_PREFIX = "NON_RETRYABLE_HTTP_"

#: 策略冻结 Cell 时写入 ``verdict_reason`` 的稳定标记（与 cell_recovery_runtime 同源）。
_ATTEMPT_LIMIT_MARKER = "FROZEN_UNRESOLVED:ATTEMPT_LIMIT_REACHED"


class LoopStopReason(StrEnum):
    """停止保护原因码；取值即 ``ResearchLoopDecision.reason`` 的稳定词表。"""

    # 基础设施类
    PROVIDER_NOT_CONFIGURED = "PROVIDER_NOT_CONFIGURED"
    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
    # 资源类
    WALL_CLOCK_BUDGET_EXHAUSTED = "WALL_CLOCK_BUDGET_EXHAUSTED"
    LOOP_BUDGET_EXHAUSTED = "LOOP_BUDGET_EXHAUSTED"
    CELL_ATTEMPT_LIMIT_REACHED = "CELL_ATTEMPT_LIMIT_REACHED"
    REPEATED_CELL_FAILURE = "REPEATED_CELL_FAILURE"
    # 证据类
    NO_SEARCH_HITS = "NO_SEARCH_HITS"
    CELL_RECOVERY_HALTED = "CELL_RECOVERY_HALTED"


INFRASTRUCTURE_REASON_CODES = frozenset(
    {
        LoopStopReason.PROVIDER_NOT_CONFIGURED.value,
        LoopStopReason.PROVIDER_UNAVAILABLE.value,
    }
)
RESOURCE_REASON_CODES = frozenset(
    {
        LoopStopReason.WALL_CLOCK_BUDGET_EXHAUSTED.value,
        LoopStopReason.LOOP_BUDGET_EXHAUSTED.value,
        LoopStopReason.CELL_ATTEMPT_LIMIT_REACHED.value,
        LoopStopReason.REPEATED_CELL_FAILURE.value,
    }
)
EVIDENCE_REASON_CODES = frozenset(
    {
        LoopStopReason.NO_SEARCH_HITS.value,
        LoopStopReason.CELL_RECOVERY_HALTED.value,
    }
)
ALL_REASON_CODES = frozenset(
    {*INFRASTRUCTURE_REASON_CODES, *RESOURCE_REASON_CODES, *EVIDENCE_REASON_CODES}
)

#: 触发「Cell 级停止」的原因码：命中后 loop 不得再发起外部恢复动作。
CELL_BOUND_REASON_CODES = frozenset(
    {
        LoopStopReason.REPEATED_CELL_FAILURE.value,
        LoopStopReason.CELL_ATTEMPT_LIMIT_REACHED.value,
    }
)


@dataclass(frozen=True)
class LoopStopDecision:
    """一次停止保护判定：原因码 + 类别 + 有界说明。"""

    reason_code: str
    category: str
    detail: str
    round_no: int

    @property
    def is_infrastructure(self) -> bool:
        return self.category == "INFRASTRUCTURE"

    @property
    def is_cell_bound(self) -> bool:
        return self.reason_code in CELL_BOUND_REASON_CODES

    def as_dict(self) -> dict[str, object]:
        return {
            "reason_code": self.reason_code,
            "category": self.category,
            "detail": self.detail,
            "round_no": self.round_no,
        }


@dataclass(frozen=True)
class LoopStopGuardInputs:
    """一次判定所需的全部信号；全部由调用方显式提供（无隐式 I/O）。"""

    round_no: int
    max_rounds: int
    cell_count: int
    max_attempts_per_cell: int
    max_repeated_failures: int = DEFAULT_MAX_REPEATED_FAILURES
    search_hit_count: int = 0
    read_window_count: int = 0
    evidence_card_count: int = 0
    verified_row_count: int = 0
    wall_clock_exhausted: bool = False
    llm_provider_configured: bool = True
    llm_provider_failure: str = ""
    rejection_reason: str = ""
    repeated_failure_streak: int = 0
    has_external_recovery_action: bool = False
    decision_should_continue: bool = False
    decision_reason: str = ""
    unresolved_cell_ids: tuple[str, ...] = ()
    attempt_limit_cell_ids: tuple[str, ...] = ()
    halted_cell_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class LoopStopGuardResult:
    """判定产物：权威停止原因、需要冻结的 Cell、外部调用上界。"""

    run_stop: LoopStopDecision | None = None
    freeze_cell_ids: tuple[str, ...] = ()
    external_call_upper_bound: int = 0

    @property
    def stop_reason_code(self) -> str:
        return self.run_stop.reason_code if self.run_stop is not None else ""


def reason_category(reason_code: str) -> str:
    """把任意 ``reason_code`` 映射到有限的类别词表。"""
    normalized = str(reason_code or "").strip().upper()
    if normalized in INFRASTRUCTURE_REASON_CODES:
        return "INFRASTRUCTURE"
    if normalized in RESOURCE_REASON_CODES:
        return "RESOURCE"
    if normalized in EVIDENCE_REASON_CODES:
        return "EVIDENCE"
    return "UNCLASSIFIED"


def external_recovery_call_upper_bound(
    *,
    max_rounds: int,
    max_attempts_per_cell: int,
    cell_count: int,
) -> int:
    """外部恢复动作次数的明确上界：同一 Cell 既受轮次封顶，也受尝试上限封顶。"""
    rounds = max(0, int(max_rounds))
    attempts = max(0, int(max_attempts_per_cell))
    cells = max(0, int(cell_count))
    if not rounds or not attempts or not cells:
        return 0
    return min(rounds, attempts) * cells


def provider_failure_signal(
    llm_client: object,
    *,
    purpose: str = "research.extract",
) -> str:
    """读取 LLM 收据，返回最后一次抽取调用的基础设施失败原因码（无则为空串）。

    只读 ``call_records``；不发起任何调用。无收据的测试替身返回空串，因此那种
    情况只能按业务原因处理，不会被误判成基础设施故障。
    """
    records = getattr(llm_client, "call_records", None)
    if not isinstance(records, Sequence) or isinstance(records, (str, bytes)):
        return ""
    for record in reversed(records):
        if not isinstance(record, Mapping):
            continue
        if str(record.get("purpose") or "") != purpose:
            continue
        termination = str(record.get("termination_reason") or "").strip().upper()
        if not termination or termination == "SUCCESS":
            return ""
        if termination in _INFRASTRUCTURE_CALL_TERMINATIONS:
            return termination
        if termination.startswith(_NON_RETRYABLE_HTTP_PREFIX):
            return termination
        return ""
    return ""


def dominant_rejection_reason(rejection_counts: Mapping[str, int] | None) -> str:
    """从抽取拒绝计数中取主导原因：计数最大者，并列取原因名最小者（确定性）。"""
    if not rejection_counts:
        return ""
    candidates = [
        (str(name or "").strip().upper(), int(count))
        for name, count in rejection_counts.items()
        if int(count or 0) > 0 and str(name or "").strip()
    ]
    if not candidates:
        return ""
    candidates.sort(key=lambda item: (-item[1], item[0]))
    return candidates[0][0]


def evaluate_loop_stop_guard(inputs: LoopStopGuardInputs) -> LoopStopGuardResult:
    """按固定优先级返回唯一的停止保护判定。"""
    upper_bound = external_recovery_call_upper_bound(
        max_rounds=inputs.max_rounds,
        max_attempts_per_cell=inputs.max_attempts_per_cell,
        cell_count=inputs.cell_count,
    )
    evidence_free = inputs.evidence_card_count <= 0 and inputs.verified_row_count <= 0

    def _result(decision: LoopStopDecision | None, freeze: tuple[str, ...] = ()) -> LoopStopGuardResult:
        return LoopStopGuardResult(
            run_stop=decision,
            freeze_cell_ids=freeze,
            external_call_upper_bound=upper_bound,
        )

    def _stop(reason: LoopStopReason, detail: str) -> LoopStopDecision:
        return LoopStopDecision(
            reason_code=reason.value,
            category=reason_category(reason.value),
            detail=detail,
            round_no=max(1, int(inputs.round_no)),
        )

    # 1. 墙钟硬预算。
    if inputs.wall_clock_exhausted:
        return _result(
            _stop(
                LoopStopReason.WALL_CLOCK_BUDGET_EXHAUSTED,
                "task-wide wall clock budget exhausted; finish the round and stop",
            )
        )

    # 2. 检索为零：Provider 从未被使用，属证据 / 范围问题，不归因为基础设施。
    if inputs.search_hit_count <= 0:
        return _result(
            _stop(
                LoopStopReason.NO_SEARCH_HITS,
                "no search hit was produced by any configured retrieval channel",
            )
        )

    # 3. Provider 基础设施失败（仅当抽取被真实触达且没有任何已接受证据）。
    if evidence_free and inputs.read_window_count > 0:
        if not inputs.llm_provider_configured:
            return _result(
                _stop(
                    LoopStopReason.PROVIDER_NOT_CONFIGURED,
                    "llm provider is not configured; read windows exist but extraction cannot run",
                )
            )
        if inputs.llm_provider_failure:
            return _result(
                _stop(
                    LoopStopReason.PROVIDER_UNAVAILABLE,
                    f"llm provider call failed at infrastructure level: {inputs.llm_provider_failure}",
                )
            )

    # 4. 同一失败原因连续重复：冻结全部未收敛 Cell，并终止整轮。
    if (
        inputs.unresolved_cell_ids
        and inputs.rejection_reason
        and inputs.repeated_failure_streak >= max(1, int(inputs.max_repeated_failures))
    ):
        return _result(
            _stop(
                LoopStopReason.REPEATED_CELL_FAILURE,
                (
                    f"same dominant failure reason {inputs.rejection_reason} repeated for "
                    f"{inputs.repeated_failure_streak} consecutive rounds"
                ),
            ),
            freeze=tuple(inputs.unresolved_cell_ids),
        )

    # 5. Cell 尝试上限触顶。
    if inputs.attempt_limit_cell_ids:
        return _result(
            _stop(
                LoopStopReason.CELL_ATTEMPT_LIMIT_REACHED,
                (
                    "cell attempt limit reached for: "
                    + ", ".join(inputs.attempt_limit_cell_ids[:4])
                ),
            )
        )

    # 6. 轮次上限：仅在循环还需要继续（即不是正常完成）时才是保护。
    if (
        int(inputs.round_no) >= int(inputs.max_rounds)
        and not inputs.decision_should_continue
        and inputs.decision_reason != "STOP_CONTRACT_SATISFIED"
    ):
        return _result(
            _stop(
                LoopStopReason.LOOP_BUDGET_EXHAUSTED,
                f"loop round budget {inputs.max_rounds} exhausted at round {inputs.round_no}",
            )
        )

    # 7. 恢复策略判定无外部动作可做。
    if inputs.halted_cell_ids and not inputs.has_external_recovery_action:
        return _result(
            _stop(
                LoopStopReason.CELL_RECOVERY_HALTED,
                "no external recovery action is available for unresolved cells",
            )
        )

    return _result(None)


def freeze_unresolved_cells(
    ledger: ResearchStateLedger,
    cell_ids: Sequence[str],
    reason_code: str,
) -> ResearchStateLedger:
    """把指定未收敛 Cell 置为 ``FROZEN``，并写入稳定标记 ``FROZEN_UNRESOLVED:<reason>``。

    已结算 Cell（``VERIFIED`` / ``FROZEN``）不回退。
    """
    targets = {str(cell_id) for cell_id in cell_ids if str(cell_id)}
    if not targets:
        return ledger
    marker = f"FROZEN_UNRESOLVED:{reason_code}"
    changed = False
    new_cells = []
    for cell in ledger.cells:
        if cell.cell_id in targets and str(cell.status or "") not in _SETTLED_CELL_STATES:
            existing = str(cell.verdict_reason or "")
            reason = existing if marker in existing else f"{existing} | {marker}".strip(" |")
            new_cells.append(
                cell.model_copy(update={"status": "FROZEN", "verdict_reason": reason})
            )
            changed = True
        else:
            new_cells.append(cell)
    if not changed:
        return ledger
    return ledger.model_copy(update={"cells": new_cells})


def unresolved_cell_ids(ledger: ResearchStateLedger) -> tuple[str, ...]:
    """需要参与「重复失败冻结」的 Cell：既非已结算，也非冲突（冲突走反证分支）。"""
    return tuple(
        cell.cell_id
        for cell in ledger.cells
        if str(cell.status or "") not in _UNRESOLVED_EXCLUDED_STATES
    )


def attempt_limit_cell_ids(
    ledger: ResearchStateLedger,
    *,
    max_attempts_per_cell: int,
    observed_attempts: Mapping[str, int] | None = None,
) -> tuple[str, ...]:
    """已触顶 ``max_attempts_per_cell`` 的 Cell（含被策略冻结为 ATTEMPT_LIMIT_REACHED 的）。

    ``observed_attempts`` 来自 :class:`app.cell_recovery_runtime.CellRecoveryRuntime`
    的真实外部动作计数；缺省时退化为读取 ``cell.repair_count``。
    """
    limit = max(0, int(max_attempts_per_cell))
    if limit <= 0:
        return ()
    observed = dict(observed_attempts or {})
    reached: list[str] = []
    for cell in ledger.cells:
        status = str(cell.status or "")
        verdict = str(cell.verdict_reason or "")
        if status == "FROZEN" and _ATTEMPT_LIMIT_MARKER in verdict:
            reached.append(cell.cell_id)
            continue
        if status in _SETTLED_CELL_STATES:
            continue
        attempts = max(
            int(cell.repair_count or 0),
            int(observed.get(cell.cell_id, 0) or 0),
        )
        if attempts >= limit:
            reached.append(cell.cell_id)
    return tuple(reached)
