"""DR-202：把 ``CellRecoveryPolicy`` 接入真实研究循环的运行时编排层。

本模块只负责「编排」，不重新实现决策规则（阈值、优先级、动作映射仍然全部来自
``app.cell_recovery_policy``，见 ``docs/DeepResearch-简历能力落地执行计划.md`` §7.2）。

职责边界：

- 对账本中每个 Cell 调用 :func:`app.cell_recovery_policy.decide`；
- 按返回的 ``action`` 执行对应分支，并且**预算检查先于外部调用**；
- 已结算 Cell（``VERIFIED`` / ``FROZEN``）只记录轨迹，绝不回退、绝不消耗预算；
- 为每次恢复决策留下可断言的结构化轨迹（动作、``reason_code``、影响 Cell、
  预算扣减、是否发生外部调用）。

DR-108：本模块额外对外提供**模块级指令接口** :class:`CellRecoveryDirective`。

在此之前，「下一轮做什么」（``loop_runtime.evaluate_loop_decision``）、「是否必须
停止」（``loop_stop_guard.evaluate_loop_stop_guard``）、「单个 Cell 该怎么恢复」
（本模块）是三套并存且互不知晓的判断，由此产生三类可观察的不一致：

1. Cell 级策略请求的 ``recovery_mode`` 被 loop 静默改写（请求 ``TARGETED_SEARCH``、
   生效 ``EXTRACT_AGAIN``），调用方无法分辨是谁最终决定的；
2. 停止保护判定基础设施 / 资源 / 恢复终止后，本轮仍留下 ``external_call=True``
   的轨迹（动作永远不会被执行，却计入外部调用预算）；
3. 停止保护冻结的 Cell 在轨迹里仍是 ``frozen=False``（冻结发生在 loop 侧，
   轨迹不反映最终状态）。

收敛方式不是再增加一套判断，而是把三者的**结论**收敛成一条指令
:func:`build_cell_recovery_directive`，并由 :func:`apply_cell_recovery_directive`
把这条指令**回写到轨迹与预算**：

- ``recovery_mode`` 由决策族 + Cell 级请求**确定性地**收敛，并把来源记进
  ``recovery_mode_source``（不再静默改写）；
- 停止保护一旦给出结论，本轮不再保留任何外部动作（轨迹改为
  ``external_call=False`` 且预算原额退还）；
- 被冻结的 Cell 在轨迹里如实标记为 ``frozen=True``。

本模块仍**不**决定「是否停止」：``recovery_mode`` / 外部动作 / 轨迹只是**服从**
调用方传入的停止结论（``stop_reason_code`` / ``external_call_allowed``）。

运行层级差异（必须在回传中上报给主对话的已知偏差）：

- ``decide`` 的预算是 Cell 级动作预算（search / fetch / read / llm / extract），
  而现有 loop / plan 只有 Run 级额度（``global_search_limit`` /
  ``tool_response_retention_budget`` / ``max_loop_rounds``）。
  本模块用 :meth:`CellRecoveryBudgetLedger.from_plan` 做一次显式、可测试的换算；
  换算规则集中在该方法内，不散落到调用方。
- 抽取拒绝摘要是 Run 级（一次抽取覆盖多个 window），无法精确归因到单个 Cell。
  调用方按「本轮未填满的 Cell 共享同一份拒绝摘要」传入，本模块据此派生
  :mod:`app.cell_recovery_policy` 需要的信号，并在轨迹里保留原始 reason_code。
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field, replace

from app.cell_recovery_policy import (
    CellRecoveryAction,
    CellRecoveryBudget,
    CellRecoveryDecision,
    CellRecoveryHistory,
    CellRecoveryThresholds,
    decide,
)
from app.extraction_result import ExtractionTerminationReason
from app.models import ResearchPlan, ResearchStateCell, ResearchStateLedger

__all__ = [
    "CellRecoveryBudgetLedger",
    "CellRecoveryDirective",
    "CellRecoveryOutcome",
    "CellRecoveryRuntime",
    "CellRecoveryTraceEntry",
    "RECOVERY_MODE_SOURCE_CELL_POLICY",
    "RECOVERY_MODE_SOURCE_LOOP_DECISION",
    "RECOVERY_MODE_SOURCE_NONE",
    "RecoveryToolchain",
    "apply_cell_recovery_directive",
    "apply_cell_recovery_policy",
    "build_cell_recovery_directive",
    "derive_recovery_mode",
    "map_cell_state",
    "plan_digest",
    "reconcile_recovery_mode",
    "resolve_loop_recovery_decision",
]

#: 已结算 Cell：不回退、不消耗预算。
_SETTLED_STATES = frozenset({"VERIFIED", "FROZEN"})

#: 账本 status -> ``decide`` 的 ``cell_state`` 稳定词表。
_CONFLICT_STATES = frozenset({"CONFLICTED", "CONFLICT"})
_STALE_STATES = frozenset({"STALE", "STALE_EVIDENCE"})
_GAP_STATES = frozenset({"EMPTY", "GAP"})
_REPAIR_STATES = frozenset(
    {"NEED_MORE_EVIDENCE", "NEEDS_REPAIR", "CANDIDATE_READY", "FILLED", "BLOCKED"}
)

#: 策略动作 -> 现有 loop ``recovery_mode`` 词表（复用既有取值，不新增跨模块枚举）。
_ACTION_TO_RECOVERY_MODE: dict[CellRecoveryAction, str] = {
    CellRecoveryAction.RETRY_EXTRACTION: "EXTRACT_AGAIN",
    CellRecoveryAction.REREAD_WINDOW: "REREAD_WINDOW",
    CellRecoveryAction.TARGETED_SEARCH: "TARGETED_SEARCH",
    CellRecoveryAction.COUNTEREVIDENCE_SEARCH: "COUNTERFACTUAL_RECHECK",
}

#: 多个 Cell 给出不同外部动作时，选出「本轮恢复模式」的确定性优先级。
_ACTION_PRIORITY: tuple[CellRecoveryAction, ...] = (
    CellRecoveryAction.COUNTEREVIDENCE_SEARCH,
    CellRecoveryAction.TARGETED_SEARCH,
    CellRecoveryAction.REREAD_WINDOW,
    CellRecoveryAction.RETRY_EXTRACTION,
)

#: DR-108：策略动作 -> loop 决策族（``family``, ``reason_code``）的**唯一映射点**。
#:
#: §7.2 要求恢复规则不得散落在搜索、抽取与 Runner 中。此前 ``loop_runtime`` 会按
#: ``evidence_card_count`` / ``read_window_count`` 自行决定 ``EXTRACT_AGAIN`` /
#: ``READ_MORE``，与策略的 ``RETRY_EXTRACTION`` / ``TARGETED_SEARCH`` 语义重叠。
#: 收敛后 loop 决策族只是策略动作的**渲染**：由策略动作唯一决定，loop 不再自持规则。
#:
#: 族边界（与 :data:`_RECOVERY_FAMILY_REFINEMENTS` 对齐）：
#:
#: - ``RETRY_EXTRACTION`` -> ``EXTRACT_AGAIN``（复用既有窗口重抽）；
#: - ``REREAD_WINDOW`` / ``TARGETED_SEARCH`` -> ``READ_MORE``（需要更多 / 更好的证据窗口）；
#: - ``COUNTEREVIDENCE_SEARCH`` -> ``COUNTERFACTUAL_RECHECK``（受限反证）。
#:
#: ``reason_code`` 复用既有 loop reason 词表（该词表仅 Worker 内部使用，Java 侧
#: 无枚举校验），不新增跨语言取值。
_ACTION_TO_LOOP_DECISION: dict[CellRecoveryAction, tuple[str, str]] = {
    CellRecoveryAction.RETRY_EXTRACTION: (
        "EXTRACT_AGAIN",
        "READ_WINDOWS_WITHOUT_EVIDENCE",
    ),
    CellRecoveryAction.REREAD_WINDOW: (
        "READ_MORE",
        "SEARCH_HITS_WITHOUT_READ_WINDOWS",
    ),
    CellRecoveryAction.TARGETED_SEARCH: (
        "READ_MORE",
        "SEARCH_HITS_WITHOUT_READ_WINDOWS",
    ),
    CellRecoveryAction.COUNTEREVIDENCE_SEARCH: (
        "COUNTERFACTUAL_RECHECK",
        "CONFLICTING_EVIDENCE",
    ),
}

#: DR-108：``recovery_mode`` 的最终决定者（写入 ``recovery_mode_source``，可断言）。
RECOVERY_MODE_SOURCE_LOOP_DECISION = "LOOP_DECISION"
RECOVERY_MODE_SOURCE_CELL_POLICY = "CELL_POLICY"
RECOVERY_MODE_SOURCE_NONE = "NONE"

#: DR-108：loop 决策族 -> 该族**允许** Cell 级策略细分的 ``recovery_mode``。
#:
#: loop 决策族定义了恢复的**族**（读更多 / 重抽 / 反证），Cell 级策略只能在族内
#: 细化：``READ_MORE`` 可被细分为 ``TARGETED_SEARCH`` / ``REREAD_WINDOW``；
#: ``EXTRACT_AGAIN`` / ``COUNTERFACTUAL_RECHECK`` 已经是精化后的取值，不再细分。
#: 「请求了跨族的模式」不再被静默接受：来源记为 ``LOOP_DECISION`` 且
#: :class:`CellRecoveryDirective` 同时保留 ``requested_recovery_mode``。
_RECOVERY_FAMILY_REFINEMENTS: dict[str, frozenset[str]] = {
    "READ_MORE": frozenset({"TARGETED_SEARCH", "REREAD_WINDOW"}),
    "EXTRACT_AGAIN": frozenset(),
    "COUNTERFACTUAL_RECHECK": frozenset(),
}

#: ``budget_cost`` 键 -> 本模块预算账本字段名；与 policy 内部映射保持同源语义。
_BUDGET_FIELDS: dict[str, str] = {
    "search_calls": "remaining_search",
    "fetch_calls": "remaining_fetch",
    "read_calls": "remaining_read",
    "llm_calls": "remaining_llm",
    "extract_calls": "remaining_extract",
}

#: 轨迹 detail 上限，与 policy / extraction_result 保持一致。
_MAX_DETAIL_CHARS = 240

#: ``CellRecoveryHistory.plan_digests`` 保留的最近条数（足够触发重复保护即可）。
_MAX_TRACKED_PLAN_DIGESTS = 8

#: ``plan_digest`` 忽略的 ``stop_contract`` 键：这些字段是**运行期记账**，与计划内容无关，
#: 且每轮都会被重写；若计入摘要，「内容摘要」会永远变化，本保护会再次退化成死代码。
_PLAN_DIGEST_IGNORED_STOP_CONTRACT_KEYS = frozenset(
    {
        # ``loop_runtime._record_wall_clock_consumption`` 每轮原地写入。
        "wall_clock_seconds_consumed",
    }
)


@dataclass
class CellRecoveryBudgetLedger:
    """可变的 Cell 级动作预算账本；``decide`` 只读它的快照。"""

    remaining_search: int = 0
    remaining_fetch: int = 0
    remaining_read: int = 0
    remaining_llm: int = 0
    remaining_extract: int = 0

    @classmethod
    def zeroed(cls) -> "CellRecoveryBudgetLedger":
        return cls()

    @classmethod
    def from_plan(cls, plan: ResearchPlan) -> "CellRecoveryBudgetLedger":
        """Run 级停止契约 -> Cell 级动作预算的显式换算。

        换算依据（全部来自 ``plan.stop_contract``，缺省时给保守下界）：

        - ``search_calls``  <- ``global_search_limit``
        - ``fetch_calls``   <- ``tool_response_retention_budget``
        - ``read_calls``    <- ``tool_response_retention_budget``
        - ``llm_calls``     <- ``max_loop_rounds``（每轮至多一次抽取 LLM 调用）
        - ``extract_calls`` <- ``max_loop_rounds``

        这是一次**有损**换算：Run 级额度无法表达 Cell 级并行额度。换算只在此处定义，
        调用方不得各自复制一份。
        """
        stop_contract = plan.stop_contract or {}

        def _int(key: str, default: int) -> int:
            try:
                return max(0, int(stop_contract.get(key, default) or 0))
            except (TypeError, ValueError):
                return max(0, default)

        max_rounds = _int("max_loop_rounds", 2)
        return cls(
            remaining_search=_int("global_search_limit", 8),
            remaining_fetch=_int("tool_response_retention_budget", 5),
            remaining_read=_int("tool_response_retention_budget", 5),
            remaining_llm=max(1, max_rounds),
            remaining_extract=max(1, max_rounds),
        )

    def snapshot(self) -> CellRecoveryBudget:
        return CellRecoveryBudget(
            remaining_search=self.remaining_search,
            remaining_fetch=self.remaining_fetch,
            remaining_read=self.remaining_read,
            remaining_llm=self.remaining_llm,
            remaining_extract=self.remaining_extract,
        )

    def is_affordable(self, cost: Mapping[str, int]) -> bool:
        return all(
            getattr(self, _BUDGET_FIELDS[key]) >= int(amount)
            for key, amount in cost.items()
        )

    def consume(self, cost: Mapping[str, int]) -> bool:
        """全有或全无地扣减预算；额度不足时返回 ``False`` 且不改变任何字段。"""
        if not cost:
            return True
        if not self.is_affordable(cost):
            return False
        for key, amount in cost.items():
            setattr(self, _BUDGET_FIELDS[key], getattr(self, _BUDGET_FIELDS[key]) - int(amount))
        return True

    def refund(self, cost: Mapping[str, int]) -> None:
        """原额退还已扣减的预算（DR-108：外部动作被停止保护撤销时调用）。"""
        for key, amount in (cost or {}).items():
            field_name = _BUDGET_FIELDS.get(key)
            if not field_name:
                continue
            setattr(self, field_name, getattr(self, field_name) + int(amount))


@dataclass(frozen=True)
class CellRecoveryTraceEntry:
    """一次 Cell 恢复决策的结构化轨迹；字段全部可序列化、可断言。"""

    round_no: int
    cell_id: str
    status_before: str
    status_after: str
    action: str
    reason_code: str
    reason_detail: str
    budget_cost: dict[str, int] = field(default_factory=dict)
    external_call: bool = False
    frozen: bool = False
    settled: bool = False
    #: DR-108: 该外部动作已被停止保护撤销（动作不会执行，预算已原额退还）。
    external_call_suppressed: bool = False

    def as_dict(self) -> dict[str, object]:
        return {
            "round_no": self.round_no,
            "cell_id": self.cell_id,
            "status_before": self.status_before,
            "status_after": self.status_after,
            "action": self.action,
            "reason_code": self.reason_code,
            "reason_detail": self.reason_detail,
            "budget_cost": dict(self.budget_cost),
            "external_call": self.external_call,
            "frozen": self.frozen,
            "settled": self.settled,
            "external_call_suppressed": self.external_call_suppressed,
        }


#: 外部动作执行器：仅当预算已扣减成功后调用；参数为 (cell_id, action, decision)。
RecoveryToolchain = Callable[[str, CellRecoveryAction, CellRecoveryDecision], None]


@dataclass
class CellRecoveryOutcome:
    """一次编排的产物：新账本、轨迹、预算账本与动作分桶。"""

    ledger: ResearchStateLedger
    trace: list[CellRecoveryTraceEntry] = field(default_factory=list)
    budget_ledger: CellRecoveryBudgetLedger = field(default_factory=CellRecoveryBudgetLedger)
    external_actions: list[dict[str, object]] = field(default_factory=list)
    frozen_cell_ids: list[str] = field(default_factory=list)
    stopped_cell_ids: list[str] = field(default_factory=list)
    settled_cell_ids: list[str] = field(default_factory=list)

    @property
    def has_external_action(self) -> bool:
        return bool(self.external_actions)

    @property
    def halted_cell_ids(self) -> list[str]:
        return list(dict.fromkeys([*self.frozen_cell_ids, *self.stopped_cell_ids]))

    @property
    def requested_recovery_mode(self) -> str:
        """Cell 级策略请求的 ``recovery_mode``（**未经**决策族收敛）。

        DR-108：本轮**生效**的 ``recovery_mode`` 只能从 :class:`CellRecoveryDirective`
        读取——它需要同时知道 loop 决策族与停止保护结论，二者都不在本模块内。
        这里暴露的只是策略侧的请求值，供指令记录与复盘使用。
        """
        actions = [str(item.get("action") or "") for item in self.external_actions]
        return derive_recovery_mode(actions)


@dataclass(frozen=True)
class CellRecoveryDirective:
    """DR-108：一轮恢复的**唯一指令**（模块级接口）。

    它是三套判断（loop 决策族 / 停止保护 / Cell 级恢复策略）的**收敛产物**：
    调用方先拿到停止保护的结论与 Cell 级策略的产物，再用
    :func:`build_cell_recovery_directive` 生成这条指令，最后用
    :func:`apply_cell_recovery_directive` 把它回写到轨迹与预算。

    约定（均可断言）：

    - ``recovery_mode`` 是本轮**生效**的取值，``requested_recovery_mode`` 是 Cell 级
      策略请求的取值，``recovery_mode_source`` 说明最终由谁决定——三者一起消除了
      「请求 vs 生效」的静默不一致；
    - ``external_call_allowed == False`` 时，本轮不得保留任何外部动作：
      ``suppressed_external_actions`` 记录被撤销的动作，``recovery_mode`` 为空；
    - ``freeze_cell_ids`` 是停止保护要求冻结的 Cell，轨迹必须如实标记 ``frozen``。
    """

    round_no: int
    recovery_mode: str = ""
    requested_recovery_mode: str = ""
    recovery_mode_source: str = RECOVERY_MODE_SOURCE_NONE
    external_call_allowed: bool = True
    external_call_upper_bound: int = 0
    stop_reason_code: str = ""
    stop_reason_category: str = ""
    freeze_cell_ids: tuple[str, ...] = ()
    planned_external_actions: tuple[dict[str, object], ...] = ()
    suppressed_external_actions: tuple[dict[str, object], ...] = ()

    @property
    def has_stop_reason(self) -> bool:
        return bool(self.stop_reason_code)

    @property
    def has_suppressed_external_actions(self) -> bool:
        return bool(self.suppressed_external_actions)

    def as_dict(self) -> dict[str, object]:
        return {
            "round_no": self.round_no,
            "recovery_mode": self.recovery_mode,
            "requested_recovery_mode": self.requested_recovery_mode,
            "recovery_mode_source": self.recovery_mode_source,
            "external_call_allowed": self.external_call_allowed,
            "external_call_upper_bound": self.external_call_upper_bound,
            "stop_reason_code": self.stop_reason_code,
            "stop_reason_category": self.stop_reason_category,
            "freeze_cell_ids": list(self.freeze_cell_ids),
            "planned_external_actions": [dict(item) for item in self.planned_external_actions],
            "suppressed_external_actions": [
                dict(item) for item in self.suppressed_external_actions
            ],
        }


def reconcile_recovery_mode(
    *,
    decision_family: str,
    requested_recovery_mode: str,
) -> tuple[str, str]:
    """收敛「loop 决策族」与「Cell 级请求」为唯一的 ``(recovery_mode, source)``。

    规则（确定性、可断言）：loop 决策族定义恢复的**族**；Cell 级策略只能在族内细化
    （见 :data:`_RECOVERY_FAMILY_REFINEMENTS`）。跨族请求不再被静默接受：返回决策族
    自身的取值，并返回 ``LOOP_DECISION`` 作为来源，调用方仍可从
    ``requested_recovery_mode`` 读出 Cell 级策略的真实请求。
    """
    family = str(decision_family or "").strip().upper()
    requested = str(requested_recovery_mode or "").strip().upper()
    allowed = _RECOVERY_FAMILY_REFINEMENTS.get(family)
    if allowed is None:
        return "", RECOVERY_MODE_SOURCE_NONE
    if requested and requested in allowed:
        return requested, RECOVERY_MODE_SOURCE_CELL_POLICY
    return family, RECOVERY_MODE_SOURCE_LOOP_DECISION


def build_cell_recovery_directive(
    *,
    round_no: int,
    decision_family: str,
    decision_reason: str = "",
    recovery_outcome: CellRecoveryOutcome | None = None,
    stop_reason_code: str = "",
    stop_reason_category: str = "",
    freeze_cell_ids: Sequence[str] = (),
    external_call_upper_bound: int = 0,
    external_call_allowed: bool = True,
) -> CellRecoveryDirective:
    """把停止保护结论 + Cell 级恢复产物收敛成一条指令（DR-108 的唯一收敛点）。

    ``external_call_allowed=False``（停止保护已给出结论）时，本轮不保留任何外部动作：
    全部计划动作进入 ``suppressed_external_actions``，``recovery_mode`` 置空——
    「循环已停止还计划外部调用」不再可能出现在轨迹里。
    """
    outcome = recovery_outcome
    planned = tuple(dict(item) for item in (outcome.external_actions if outcome else ()))
    requested = outcome.requested_recovery_mode if outcome is not None else ""
    recovery_mode, source = reconcile_recovery_mode(
        decision_family=decision_family,
        requested_recovery_mode=requested,
    )
    suppressed: tuple[dict[str, object], ...] = ()
    if not external_call_allowed:
        suppressed = planned
        recovery_mode, source = "", RECOVERY_MODE_SOURCE_NONE
    return CellRecoveryDirective(
        round_no=max(1, int(round_no)),
        recovery_mode=recovery_mode,
        requested_recovery_mode=requested,
        recovery_mode_source=source,
        external_call_allowed=bool(external_call_allowed),
        external_call_upper_bound=max(0, int(external_call_upper_bound)),
        stop_reason_code=str(stop_reason_code or decision_reason or ""),
        stop_reason_category=str(stop_reason_category or ""),
        freeze_cell_ids=tuple(str(cell_id) for cell_id in freeze_cell_ids if str(cell_id)),
        planned_external_actions=() if suppressed else planned,
        suppressed_external_actions=suppressed,
    )


def apply_cell_recovery_directive(
    outcome: CellRecoveryOutcome,
    directive: CellRecoveryDirective,
) -> CellRecoveryOutcome:
    """把指令回写到轨迹与预算（DR-108 的**提交**步骤）。

    两件确定性改写：

    - 被撤销的外部动作：``external_call=False``、``budget_cost={}``（预算原额退还）、
      ``external_call_suppressed=True``，并从 ``external_actions`` 中移除；
    - 被停止保护冻结的 Cell：轨迹如实标记 ``frozen=True`` / ``status_after=FROZEN``
      （循环侧冻结发生在策略之后，旧实现下轨迹与账本长期不一致）。

    轨迹的 ``action`` / ``reason_code`` **保留原值**：它们记录策略当时给出了什么判断，
    撤销是后续收敛的结果，不改写历史判断本身。
    """
    if not directive.suppressed_external_actions and not directive.freeze_cell_ids:
        return outcome
    suppressed_cell_ids = {
        str(item.get("cell_id") or "")
        for item in directive.suppressed_external_actions
        if str(item.get("cell_id") or "")
    }
    frozen_cell_ids = set(directive.freeze_cell_ids)
    new_trace: list[CellRecoveryTraceEntry] = []
    for entry in outcome.trace:
        updated = entry
        if entry.external_call and entry.cell_id in suppressed_cell_ids:
            outcome.budget_ledger.refund(entry.budget_cost)
            updated = replace(
                updated,
                external_call=False,
                budget_cost={},
                external_call_suppressed=True,
            )
        if entry.cell_id in frozen_cell_ids and not entry.settled:
            updated = replace(updated, frozen=True, status_after="FROZEN")
        new_trace.append(updated)
    remaining_actions = [
        dict(item)
        for item in outcome.external_actions
        if str(item.get("cell_id") or "") not in suppressed_cell_ids
    ]
    return CellRecoveryOutcome(
        ledger=outcome.ledger,
        trace=new_trace,
        budget_ledger=outcome.budget_ledger,
        external_actions=remaining_actions,
        frozen_cell_ids=list(
            dict.fromkeys([*outcome.frozen_cell_ids, *directive.freeze_cell_ids])
        ),
        stopped_cell_ids=list(outcome.stopped_cell_ids),
        settled_cell_ids=list(outcome.settled_cell_ids),
    )


def map_cell_state(cell: ResearchStateCell) -> str:
    """账本 Cell status -> ``decide`` 的 ``cell_state`` 稳定取值。"""
    status = str(cell.status or "").strip().upper()
    if status in _SETTLED_STATES:
        return status
    if status in _CONFLICT_STATES:
        return "CONFLICT"
    if status in _STALE_STATES:
        return "STALE"
    if status in _GAP_STATES:
        return "GAP"
    if status in _REPAIR_STATES:
        return "NEEDS_REPAIR" if cell.evidence_refs else "GAP"
    return "NEEDS_REPAIR" if cell.evidence_refs else "GAP"


def derive_recovery_mode(actions: list[str]) -> str:
    """多个外部动作并存时，按固定优先级选出唯一 ``recovery_mode``。"""
    normalized = {str(action or "").strip().upper() for action in actions}
    if not normalized:
        return ""
    for action in _ACTION_PRIORITY:
        if action.value in normalized:
            return _ACTION_TO_RECOVERY_MODE[action]
    return ""


def resolve_loop_recovery_decision(
    outcome: CellRecoveryOutcome | None,
) -> tuple[str, str] | None:
    """DR-108：Cell 级恢复产物 -> loop 决策族（唯一权威映射）。

    返回 ``(decision_family, reason_code)``；当本轮没有任何可执行的外部恢复动作
    （全部 ``FREEZE_UNRESOLVED`` / ``STOP_BUDGET_EXHAUSTED`` / 已结算）时返回
    ``None``——此时 loop **不得自行发明**恢复动作，由停止保护按 ``halted_cell_ids``
    裁决（``CELL_RECOVERY_HALTED``）。

    多动作并存时按 :data:`_ACTION_PRIORITY` 取唯一动作，与
    :func:`derive_recovery_mode` 使用同一优先级，保证「生效模式」与「决策族」不再分叉。
    """
    if outcome is None or not outcome.external_actions:
        return None
    actions = {
        str(item.get("action") or "").strip().upper()
        for item in outcome.external_actions
    }
    for action in _ACTION_PRIORITY:
        if action.value in actions:
            return _ACTION_TO_LOOP_DECISION[action]
    return None


def _rejection_summary_for_cell(
    cell: ResearchStateCell,
    round_rejection_counts: Mapping[str, int],
    *,
    read_window_count: int,
) -> dict[str, int]:
    """Run 级拒绝摘要 + Cell 级状态信号 -> ``decide`` 的 ``rejection_summary``。

    归因是**有损**的：一次抽取覆盖多个 window，无法精确归属到 Cell。规则：

    - 先继承本轮抽取的拒绝计数（真实原因码，原样保留）；
    - ``CONFLICTED`` Cell 追加 ``CONFLICTING_EVIDENCE``；
    - 未填满 Cell 若本轮没有任何拒绝信号，则按状态给出诚实信号：
      没有可读窗口 -> ``NO_READ_WINDOWS``，否则 -> ``MISSING_EVIDENCE_CARDS``。
      这样「缺证据 Cell」不会被误判成 ``NO_RECOVERABLE_SIGNAL`` 而被过早冻结。
    """
    summary: dict[str, int] = {}
    for raw_key, raw_value in (round_rejection_counts or {}).items():
        key = str(raw_key or "").strip().upper()
        try:
            count = int(raw_value)
        except (TypeError, ValueError):
            count = 0
        if key and count > 0:
            summary[key] = summary.get(key, 0) + count

    state = map_cell_state(cell)
    if state == "CONFLICT":
        summary["CONFLICTING_EVIDENCE"] = summary.get("CONFLICTING_EVIDENCE", 0) + 1

    if state not in _SETTLED_STATES and not summary:
        signal = "NO_READ_WINDOWS" if read_window_count <= 0 else "MISSING_EVIDENCE_CARDS"
        summary[signal] = 1
    return summary


def apply_cell_recovery_policy(
    ledger: ResearchStateLedger,
    *,
    round_no: int,
    budget_ledger: CellRecoveryBudgetLedger,
    rejection_summary: Mapping[str, int] | None = None,
    histories: Mapping[str, CellRecoveryHistory] | None = None,
    default_history: CellRecoveryHistory | None = None,
    thresholds: CellRecoveryThresholds | None = None,
    toolchain: RecoveryToolchain | None = None,
    read_window_count: int = 0,
) -> CellRecoveryOutcome:
    """对账本中每个 Cell 决策并执行对应分支（纯编排 + 显式外部调用闸门）。"""
    limits = thresholds or CellRecoveryThresholds()
    round_rejection_counts = dict(rejection_summary or {})
    history_by_cell = dict(histories or {})
    fallback_history = default_history or CellRecoveryHistory(
        attempts=0,
        consecutive_failures=0,
        actions_taken=(),
        plan_digests=(),
    )

    new_cells: list[ResearchStateCell] = []
    trace: list[CellRecoveryTraceEntry] = []
    external_actions: list[dict[str, object]] = []
    frozen_cell_ids: list[str] = []
    stopped_cell_ids: list[str] = []
    settled_cell_ids: list[str] = []

    for cell in ledger.cells:
        status_before = str(cell.status or "")
        state = map_cell_state(cell)
        summary = _rejection_summary_for_cell(
            cell,
            round_rejection_counts,
            read_window_count=read_window_count,
        )
        history = history_by_cell.get(cell.cell_id) or _history_for_cell(
            cell, fallback_history
        )
        decision = decide(state, summary, budget_ledger.snapshot(), history, limits)
        settled = state in _SETTLED_STATES

        if settled:
            # 已完成 Cell：只记录 CELL_ALREADY_SETTLED，绝不回退、绝不消耗预算。
            settled_cell_ids.append(cell.cell_id)
            new_cells.append(cell)
            trace.append(
                _trace_entry(
                    round_no=round_no,
                    cell=cell,
                    status_after=status_before,
                    decision=decision,
                    external_call=False,
                    frozen=False,
                    settled=True,
                )
            )
            continue

        if decision.action is CellRecoveryAction.STOP_BUDGET_EXHAUSTED:
            stopped_cell_ids.append(cell.cell_id)
            new_cells.append(cell)
            trace.append(
                _trace_entry(
                    round_no=round_no,
                    cell=cell,
                    status_after=status_before,
                    decision=decision,
                    external_call=False,
                    frozen=False,
                    settled=False,
                )
            )
            continue

        if decision.action is CellRecoveryAction.FREEZE_UNRESOLVED:
            frozen_cell_ids.append(cell.cell_id)
            frozen_cell = _freeze_cell(cell, decision)
            new_cells.append(frozen_cell)
            trace.append(
                _trace_entry(
                    round_no=round_no,
                    cell=cell,
                    status_after=str(frozen_cell.status or ""),
                    decision=decision,
                    external_call=False,
                    frozen=True,
                    settled=False,
                )
            )
            continue

        # 外部动作：预算检查必须发生在调用之前（全有或全无扣减）。
        if not budget_ledger.consume(decision.budget_cost):
            stopped_cell_ids.append(cell.cell_id)
            new_cells.append(cell)
            trace.append(
                _trace_entry(
                    round_no=round_no,
                    cell=cell,
                    status_after=status_before,
                    decision=decision,
                    external_call=False,
                    frozen=False,
                    settled=False,
                )
            )
            continue

        external_actions.append(
            {
                "cell_id": cell.cell_id,
                "action": decision.action.value,
                "reason_code": decision.reason_code,
                "budget_cost": dict(decision.budget_cost),
            }
        )
        new_cells.append(cell)
        trace.append(
            _trace_entry(
                round_no=round_no,
                cell=cell,
                status_after=status_before,
                decision=decision,
                external_call=True,
                frozen=False,
                settled=False,
            )
        )
        if toolchain is not None:
            toolchain(cell.cell_id, decision.action, decision)

    return CellRecoveryOutcome(
        ledger=ledger.model_copy(update={"cells": new_cells}),
        trace=trace,
        budget_ledger=budget_ledger,
        external_actions=external_actions,
        frozen_cell_ids=frozen_cell_ids,
        stopped_cell_ids=stopped_cell_ids,
        settled_cell_ids=settled_cell_ids,
    )


class CellRecoveryRuntime:
    """跨轮持有 Cell 恢复历史与预算账本的编排器。

    每轮调用 :meth:`apply`；历史（尝试次数、连续失败、动作序列、Plan digest）与预算
    扣减都在此累积，因此「同一输入两次运行得到相同动作序列」是可验证的性质。
    """

    def __init__(
        self,
        *,
        budget_ledger: CellRecoveryBudgetLedger | None = None,
        thresholds: CellRecoveryThresholds | None = None,
        toolchain: RecoveryToolchain | None = None,
    ) -> None:
        self.budget_ledger = budget_ledger or CellRecoveryBudgetLedger.zeroed()
        self.thresholds = thresholds or CellRecoveryThresholds()
        self.toolchain = toolchain
        self._attempts: dict[str, int] = {}
        self._consecutive_failures: dict[str, int] = {}
        self._actions_taken: dict[str, list[str]] = {}
        self._plan_digests: list[str] = []
        self.trace: list[CellRecoveryTraceEntry] = []

    def apply(
        self,
        ledger: ResearchStateLedger,
        *,
        round_no: int,
        rejection_summary: Mapping[str, int] | None = None,
        read_window_count: int = 0,
        plan_digest: str = "",
    ) -> CellRecoveryOutcome:
        if plan_digest:
            self._plan_digests.append(str(plan_digest))
            del self._plan_digests[:-_MAX_TRACKED_PLAN_DIGESTS]

        histories = {
            cell.cell_id: self._history_snapshot(cell)
            for cell in ledger.cells
        }
        outcome = apply_cell_recovery_policy(
            ledger,
            round_no=round_no,
            budget_ledger=self.budget_ledger,
            rejection_summary=rejection_summary,
            histories=histories,
            thresholds=self.thresholds,
            toolchain=self.toolchain,
            read_window_count=read_window_count,
        )
        self._record(outcome)
        self.trace.extend(outcome.trace)
        return outcome

    @property
    def attempts_by_cell(self) -> dict[str, int]:
        """已观察到外部恢复动作的 Cell 尝试次数（只读快照，供停止保护判定）。"""
        return dict(self._attempts)

    def _history_snapshot(self, cell: ResearchStateCell) -> CellRecoveryHistory:
        return CellRecoveryHistory(
            attempts=max(int(cell.repair_count or 0), self._attempts.get(cell.cell_id, 0)),
            consecutive_failures=self._consecutive_failures.get(cell.cell_id, 0),
            actions_taken=tuple(self._actions_taken.get(cell.cell_id, ())),
            plan_digests=tuple(self._plan_digests),
        )

    def _record(self, outcome: CellRecoveryOutcome) -> None:
        for entry in outcome.trace:
            if entry.settled:
                self._consecutive_failures[entry.cell_id] = 0
                continue
            if entry.action == CellRecoveryAction.FREEZE_UNRESOLVED.value:
                continue
            if entry.external_call:
                self._attempts[entry.cell_id] = self._attempts.get(entry.cell_id, 0) + 1
                self._actions_taken.setdefault(entry.cell_id, []).append(entry.action)
                self._consecutive_failures[entry.cell_id] = (
                    self._consecutive_failures.get(entry.cell_id, 0) + 1
                )


def plan_digest(plan: ResearchPlan) -> str:
    """Plan 的**内容**摘要，用于「换计划但不产生新信息」的收敛保护。

    「内容相同」的定义（本函数是全仓唯一定义点，必须可判定且稳定）：

    **计入摘要的字段**（任一字段内容变化都会改变摘要）：

    - ``query_set``：检索意图。新增 / 删除 / 改写查询都会改变摘要。
    - ``research_schema``：整表 dump，即列集合（``key`` / ``dtype`` / ``required`` /
      ``acceptance`` / ``description``）与 schema 级 ``research_type`` / ``entity_type``；
      它决定后续抽取的目标结构。
    - ``report_sections``：交付物结构。
    - ``research_type`` / ``target_entity_type``：研究类型与实体类型。
    - ``stop_contract`` 的**全部键值**，但排除
      :data:`_PLAN_DIGEST_IGNORED_STOP_CONTRACT_KEYS`。其中 ``recovery_mode``、
      ``recovery_target_*``、``evidence_horizon_*``、``max_loop_rounds`` 等直接决定
      恢复动作与预算口径；其余键（深度、限额、放弃条件等）也是计划内容的一部分。

    **显式排除的字段**（都是「重计划过程记录」或「运行期记账」，不是计划内容）：

    - ``plan_revision``：每轮 ``+1``，是把本保护变成死代码的根因（DR-109），必须排除。
    - ``replan_history``：每轮追加一条，记录「为什么重计划」，不描述计划本身。
    - ``notes``：每轮追加 ``loop recovery: ...`` 说明文本。
    - ``plan_horizon``：阶段状态标记，被 ``research_tools.mark_plan_phase`` 就地推进
      （同一次运行内会多次变化，且与检索 / 抽取策略无关）。
    - ``stop_contract["wall_clock_seconds_consumed"]``：墙钟记账，每轮重写。

    摘要对字段顺序不敏感（``sort_keys``），但对内容敏感：只有上列字段的组合完全一致
    才会得到相同摘要。因此「内容相同」是可判定且稳定的——被排除的项都是**每轮必然
    变化且与决策无关**的记账字段，把它们排除不会漏判真实的内容变化；反过来，任何被
    计入字段的变化都会被如实捕获，不会产生假阳性冻结。
    """
    stop_contract = {
        str(key): value
        for key, value in (plan.stop_contract or {}).items()
        if str(key) not in _PLAN_DIGEST_IGNORED_STOP_CONTRACT_KEYS
    }
    content = {
        "query_set": [str(item) for item in plan.query_set],
        "report_sections": [str(item) for item in plan.report_sections],
        "research_type": str(plan.research_type or ""),
        "target_entity_type": str(plan.target_entity_type or ""),
        "research_schema": plan.research_schema.model_dump(mode="json"),
        "stop_contract": stop_contract,
    }
    canonical = json.dumps(
        content,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
        default=str,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]


def _history_for_cell(
    cell: ResearchStateCell,
    fallback: CellRecoveryHistory,
) -> CellRecoveryHistory:
    """把 Cell 的 ``repair_count`` 并入调用方提供的历史（取较大者，单调不回退）。"""
    return CellRecoveryHistory(
        attempts=max(int(cell.repair_count or 0), fallback.attempts),
        consecutive_failures=fallback.consecutive_failures,
        actions_taken=fallback.actions_taken,
        plan_digests=fallback.plan_digests,
    )


def _freeze_cell(cell: ResearchStateCell, decision: CellRecoveryDecision) -> ResearchStateCell:
    marker = f"FROZEN_UNRESOLVED:{decision.reason_code}"
    existing = str(cell.verdict_reason or "")
    reason = existing if marker in existing else f"{existing} | {marker}".strip(" |")
    return cell.model_copy(update={"status": "FROZEN", "verdict_reason": reason})


def _trace_entry(
    *,
    round_no: int,
    cell: ResearchStateCell,
    status_after: str,
    decision: CellRecoveryDecision,
    external_call: bool,
    frozen: bool,
    settled: bool,
) -> CellRecoveryTraceEntry:
    return CellRecoveryTraceEntry(
        round_no=round_no,
        cell_id=cell.cell_id,
        status_before=str(cell.status or ""),
        status_after=status_after,
        action=decision.action.value,
        reason_code=decision.reason_code,
        reason_detail=_truncate(decision.detail),
        budget_cost=dict(decision.budget_cost),
        external_call=external_call,
        frozen=frozen,
        settled=settled,
    )


def _truncate(text: str, limit: int = _MAX_DETAIL_CHARS) -> str:
    collapsed = " ".join(str(text).split())
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[: limit - 1].rstrip() + "…"


# ``ExtractionTerminationReason`` 是抽取终止原因的稳定词表；这里显式引用一次，
# 保证「终止原因 -> 信号」的映射来源可追溯（调用方可用它构造 rejection_summary）。
TERMINATION_REASONS = tuple(reason.value for reason in ExtractionTerminationReason)
