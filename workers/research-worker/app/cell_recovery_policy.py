"""DR-201：Cell 恢复动作决策 Module（纯函数、无 I/O）。

设计目标（``docs/DeepResearch-简历能力落地执行计划.md`` §7.2）：

- 根据 Cell 当前状态、抽取拒绝摘要、剩余预算与历史，选择一个**有界动作**；
- 不直接调用 Provider，不读环境变量 / 时间 / 随机数；同一输入必须得到同一决策；
- 把「重试阈值、动作优先级、预算扣减」收敛在 Module 内部，只向 Runner 暴露一个
  稳定决策 Interface（提高 Depth 与 Locality，避免恢复规则散落在搜索、抽取与
  Runner 中）。

动作取值固定（``CellRecoveryAction``）：

``RETRY_EXTRACTION`` / ``REREAD_WINDOW`` / ``TARGETED_SEARCH`` /
``COUNTEREVIDENCE_SEARCH`` / ``FREEZE_UNRESOLVED`` / ``STOP_BUDGET_EXHAUSTED``

``reason_code`` 全量取值：

- 复用 ``app.extraction_result`` 的稳定词表（不新增、不改名）：
  ``INVALID_JSON`` / ``NON_OBJECT_CARD`` / ``DUPLICATE_EVIDENCE`` /
  ``NON_EXACT_QUOTE`` / ``EMPTY_CLAIM`` / ``UNSUPPORTED_RELATION`` /
  ``EMPTY_QUOTE`` / ``MISSING_WINDOW_ID`` / ``UNKNOWN_WINDOW`` /
  ``WRONG_COLUMN`` / ``MISSING_EVIDENCE_CARDS`` / ``NO_READ_WINDOWS`` /
  ``LLM_UNAVAILABLE``；
- 本 Module 的策略级取值：
  ``BUDGET_EXHAUSTED`` / ``ATTEMPT_LIMIT_REACHED`` / ``REPEATED_PLAN_DIGEST`` /
  ``CELL_ALREADY_SETTLED`` / ``NO_RECOVERABLE_SIGNAL`` / ``CONFLICTING_EVIDENCE`` /
  ``STALE_EVIDENCE``。

决策优先级（自上而下，先命中先返回）：

1. 已结算 Cell（``VERIFIED`` / ``FROZEN``）→ ``FREEZE_UNRESOLVED``
   （``CELL_ALREADY_SETTLED``，零成本、不回退）；
2. ``LLM_UNAVAILABLE``（配置缺失，重试无用）→ ``FREEZE_UNRESOLVED``
   （保留 ``LLM_UNAVAILABLE``，禁止伪装成预算问题）；
3. 有界性：``attempts`` 或 ``consecutive_failures`` 触顶 → ``FREEZE_UNRESOLVED``
   （``ATTEMPT_LIMIT_REACHED``），不得再发起外部动作；
4. 无信息重计划保护：最近 ``max_repeated_plan_digests`` 个 digest 相同 →
   ``FREEZE_UNRESOLVED``（``REPEATED_PLAN_DIGEST``）；
5. 冲突优先：``cell_state == "CONFLICT"`` 或摘要含反向证据信号 →
   ``COUNTEREVIDENCE_SEARCH``（预算不足则 ``STOP_BUDGET_EXHAUSTED``）；
6. 拒绝原因 → 动作映射（多原因取主导原因：计数最大、并列取原因名最小）；
7. ``STALE`` Cell 且无其它信号 → ``REREAD_WINDOW``（``STALE_EVIDENCE``）；
8. 无任何可恢复信号 → ``FREEZE_UNRESOLVED``（``NO_RECOVERABLE_SIGNAL``）。

预算优先：所选动作所需额度为 0 时返回 ``STOP_BUDGET_EXHAUSTED``，绝不返回一个
必然失败的外部动作。零成本动作的 ``budget_cost`` 是空映射（所有键之和为 0）。

``NO_READ_WINDOWS`` 是映射表里唯一的显式降级：无 fetch 额度时降级
``TARGETED_SEARCH``；若连 read 额度也没有则按预算优先返回
``STOP_BUDGET_EXHAUSTED``。
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import StrEnum

# ``detail`` 上限：与 ``app/extraction_result.py`` 的拒绝记录保持一致，足以定位问题，
# 又不至于把不可信内容搬进证据库。本 Module 的 detail 只包含原因码 / 计数，不含模型原文。
_MAX_DETAIL_CHARS = 240

#: ``cell_state`` 的稳定取值（`CellSupportStatus` 的等价字符串）。
_STATE_GAP = "GAP"
_STATE_CONFLICT = "CONFLICT"
_STATE_NEEDS_REPAIR = "NEEDS_REPAIR"
_STATE_VERIFIED = "VERIFIED"
_STATE_FROZEN = "FROZEN"
_STATE_STALE = "STALE"

#: 已结算 Cell：不再回退、不再消耗预算。
_SETTLED_STATES = frozenset({_STATE_VERIFIED, _STATE_FROZEN})

#: 配置缺失类终止信号：重试无用，必须与预算耗尽区分开。
_IRRECOVERABLE_REASON = "LLM_UNAVAILABLE"

#: 反向证据信号（``rejection_summary`` 中可能出现的键）。
_CONFLICT_SIGNAL_KEYS = frozenset(
    {
        "CONFLICT",
        "CONFLICTS",
        "CONFLICTING_EVIDENCE",
        "CONTRADICTS",
        "COUNTEREVIDENCE",
        "REVERSE_EVIDENCE",
    }
)


class CellRecoveryAction(StrEnum):
    """Cell 恢复动作词表；取值与 §7.2 一致，不得改名。"""

    RETRY_EXTRACTION = "RETRY_EXTRACTION"
    REREAD_WINDOW = "REREAD_WINDOW"
    TARGETED_SEARCH = "TARGETED_SEARCH"
    COUNTEREVIDENCE_SEARCH = "COUNTEREVIDENCE_SEARCH"
    FREEZE_UNRESOLVED = "FREEZE_UNRESOLVED"
    STOP_BUDGET_EXHAUSTED = "STOP_BUDGET_EXHAUSTED"


#: 外部动作：会消耗额度，因此必须通过预算闸门与有界性检查。
_EXTERNAL_ACTIONS = frozenset(
    {
        CellRecoveryAction.RETRY_EXTRACTION,
        CellRecoveryAction.REREAD_WINDOW,
        CellRecoveryAction.TARGETED_SEARCH,
        CellRecoveryAction.COUNTEREVIDENCE_SEARCH,
    }
)

#: 每个动作预计扣减的预算键与数量。预算键名与 completion contract 保持一致。
_ACTION_BUDGET_COST: dict[CellRecoveryAction, dict[str, int]] = {
    # 抽取需要一次 LLM 调用。
    CellRecoveryAction.RETRY_EXTRACTION: {"extract_calls": 1, "llm_calls": 1},
    # 重读复用已抓取的文档，只需 read 额度。
    CellRecoveryAction.REREAD_WINDOW: {"read_calls": 1},
    # 定向搜索只需要 search 额度；其后的 fetch / read 由 DR-202 单独记账。
    CellRecoveryAction.TARGETED_SEARCH: {"search_calls": 1},
    CellRecoveryAction.COUNTEREVIDENCE_SEARCH: {"search_calls": 1},
    CellRecoveryAction.FREEZE_UNRESOLVED: {},
    CellRecoveryAction.STOP_BUDGET_EXHAUSTED: {},
}

#: ``budget_cost`` 键 -> ``CellRecoveryBudget`` 字段名。
_BUDGET_FIELDS: dict[str, str] = {
    "search_calls": "remaining_search",
    "fetch_calls": "remaining_fetch",
    "read_calls": "remaining_read",
    "llm_calls": "remaining_llm",
    "extract_calls": "remaining_extract",
}

#: 拒绝 / 终止原因 -> 动作映射（§7.2 建议表）。
_REASON_ACTIONS: dict[str, CellRecoveryAction] = {
    "INVALID_JSON": CellRecoveryAction.RETRY_EXTRACTION,
    "NON_OBJECT_CARD": CellRecoveryAction.RETRY_EXTRACTION,
    "DUPLICATE_EVIDENCE": CellRecoveryAction.RETRY_EXTRACTION,
    "NON_EXACT_QUOTE": CellRecoveryAction.RETRY_EXTRACTION,
    "EMPTY_CLAIM": CellRecoveryAction.RETRY_EXTRACTION,
    "UNSUPPORTED_RELATION": CellRecoveryAction.RETRY_EXTRACTION,
    "EMPTY_QUOTE": CellRecoveryAction.REREAD_WINDOW,
    "MISSING_WINDOW_ID": CellRecoveryAction.REREAD_WINDOW,
    "UNKNOWN_WINDOW": CellRecoveryAction.REREAD_WINDOW,
    "WRONG_COLUMN": CellRecoveryAction.TARGETED_SEARCH,
    "MISSING_EVIDENCE_CARDS": CellRecoveryAction.TARGETED_SEARCH,
    "NO_READ_WINDOWS": CellRecoveryAction.REREAD_WINDOW,
}


@dataclass(frozen=True)
class CellRecoveryBudget:
    """只读输入：各动作可用的剩余额度（不允许为负）。"""

    remaining_search: int
    remaining_fetch: int
    remaining_read: int
    remaining_llm: int
    remaining_extract: int


@dataclass(frozen=True)
class CellRecoveryHistory:
    """该 Cell 的恢复历史；全部字段可序列化、可断言。"""

    attempts: int
    consecutive_failures: int
    actions_taken: tuple[str, ...]
    plan_digests: tuple[str, ...]

    def __post_init__(self) -> None:
        # 容忍调用方传入 list：统一成 tuple，保证决策输入等价、可比对。
        for name in ("actions_taken", "plan_digests"):
            value = getattr(self, name)
            if not isinstance(value, tuple):
                object.__setattr__(self, name, tuple(value))


@dataclass(frozen=True)
class CellRecoveryThresholds:
    """阈值集中在此，禁止散落到调用方。

    默认值与既有实现对齐：``loop_runtime._freeze_overspent_cells`` 的
    ``max_retry_per_cell`` 默认 3，``config.llm_max_attempts`` 默认 3。
    ``max_repeated_plan_digests`` 默认 3：连续 3 次相同 Plan digest 视为
    「换计划但不产生新信息」。
    """

    max_attempts_per_cell: int = 3
    max_consecutive_failures: int = 3
    max_repeated_plan_digests: int = 3


@dataclass(frozen=True)
class CellRecoveryDecision:
    """一次恢复决策：动作 + 机器可读原因 + 有界说明 + 预计预算扣减。"""

    action: CellRecoveryAction
    reason_code: str
    detail: str
    budget_cost: dict[str, int] = field(default_factory=dict)

    @property
    def is_external(self) -> bool:
        """该动作是否会发起新的外部消耗（用于有界性断言）。"""
        return self.action in _EXTERNAL_ACTIONS


def decide(
    cell_state: str,
    rejection_summary: Mapping[str, int],
    budget: CellRecoveryBudget,
    history: CellRecoveryHistory,
    thresholds: CellRecoveryThresholds | None = None,
) -> CellRecoveryDecision:
    """纯函数决策入口。同一输入必定返回完全相同的 ``CellRecoveryDecision``。"""
    limits = thresholds or CellRecoveryThresholds()
    state = _normalize_state(cell_state)
    signals = _normalize_summary(rejection_summary)

    # 1. 已完成的 Cell 不回退：零成本、稳定原因码。
    if state in _SETTLED_STATES:
        return _decision(
            CellRecoveryAction.FREEZE_UNRESOLVED,
            "CELL_ALREADY_SETTLED",
            f"cell state {state} is already settled; recovery must not reopen it",
        )

    # 2. 不可恢复原因（配置缺失）：重试无用，且不得伪装成预算问题。
    if _has_signal(signals, _IRRECOVERABLE_REASON):
        return _decision(
            CellRecoveryAction.FREEZE_UNRESOLVED,
            _IRRECOVERABLE_REASON,
            "LLM provider is not configured; retrying extraction cannot succeed",
        )

    # 3. 有界性：触顶后不得再返回任何外部动作。
    if history.attempts >= limits.max_attempts_per_cell:
        return _decision(
            CellRecoveryAction.FREEZE_UNRESOLVED,
            "ATTEMPT_LIMIT_REACHED",
            (
                f"cell attempted {history.attempts} times, reaching "
                f"max_attempts_per_cell={limits.max_attempts_per_cell}"
            ),
        )
    if history.consecutive_failures >= limits.max_consecutive_failures:
        return _decision(
            CellRecoveryAction.FREEZE_UNRESOLVED,
            "ATTEMPT_LIMIT_REACHED",
            (
                f"{history.consecutive_failures} consecutive failures reached "
                f"max_consecutive_failures={limits.max_consecutive_failures}"
            ),
        )

    # 4. 无信息重计划保护：换计划却不产生新信息时必须收敛。
    if _has_repeated_plan_digest(history.plan_digests, limits.max_repeated_plan_digests):
        return _decision(
            CellRecoveryAction.FREEZE_UNRESOLVED,
            "REPEATED_PLAN_DIGEST",
            (
                f"the last {limits.max_repeated_plan_digests} plan digests are identical; "
                "replanning yields no new information"
            ),
        )

    # 5. 冲突优先：存在反向证据时只做受限反证搜索。
    if state == _STATE_CONFLICT or _has_any_signal(signals, _CONFLICT_SIGNAL_KEYS):
        return _external_or_stop(
            CellRecoveryAction.COUNTEREVIDENCE_SEARCH,
            "CONFLICTING_EVIDENCE",
            "conflicting evidence requires one bounded counterevidence search",
            budget,
        )

    # 6. 拒绝原因 -> 动作映射（多原因取主导原因）。
    reason = _dominant_reason(signals)
    if reason is not None:
        return _decide_from_reason(reason, budget)

    # 7. Cell 状态兜底：STALE 只做一次廉价的窗口重读。
    if state == _STATE_STALE:
        return _external_or_stop(
            CellRecoveryAction.REREAD_WINDOW,
            "STALE_EVIDENCE",
            "stale cell evidence should be re-read from the existing window first",
            budget,
        )

    # 8. 无任何可恢复信号。
    return _decision(
        CellRecoveryAction.FREEZE_UNRESOLVED,
        "NO_RECOVERABLE_SIGNAL",
        f"cell state {state or 'UNKNOWN'} exposes no recoverable rejection reason",
    )


def _decide_from_reason(reason: str, budget: CellRecoveryBudget) -> CellRecoveryDecision:
    """把主导拒绝原因翻译成动作；``NO_READ_WINDOWS`` 带显式降级链。"""
    if reason == "NO_READ_WINDOWS":
        return _decide_no_read_windows(budget)
    return _external_or_stop(
        _REASON_ACTIONS[reason],
        reason,
        f"dominant rejection reason {reason} maps to {_REASON_ACTIONS[reason].value}",
        budget,
    )


def _decide_no_read_windows(budget: CellRecoveryBudget) -> CellRecoveryDecision:
    """NO_READ_WINDOWS：无 fetch 额度时降级为定向搜索，无 read 额度时停止。"""
    if budget.remaining_read <= 0:
        return _budget_exhausted(
            "NO_READ_WINDOWS",
            "no read budget left to reopen a window",
        )
    if budget.remaining_fetch <= 0:
        if budget.remaining_search > 0:
            return _decision(
                CellRecoveryAction.TARGETED_SEARCH,
                "NO_READ_WINDOWS",
                "no fetch budget left to reopen windows; degrade to a targeted search",
                _ACTION_BUDGET_COST[CellRecoveryAction.TARGETED_SEARCH],
            )
        return _budget_exhausted(
            "NO_READ_WINDOWS",
            "no fetch or search budget left to recover the missing read windows",
        )
    return _decision(
        CellRecoveryAction.REREAD_WINDOW,
        "NO_READ_WINDOWS",
        "reopen a read window before attempting a broader search",
        _ACTION_BUDGET_COST[CellRecoveryAction.REREAD_WINDOW],
    )


def _external_or_stop(
    action: CellRecoveryAction,
    reason_code: str,
    detail: str,
    budget: CellRecoveryBudget,
) -> CellRecoveryDecision:
    """预算闸门：所需额度不足时返回 ``STOP_BUDGET_EXHAUSTED``，而不是必然失败的动作。"""
    cost = _ACTION_BUDGET_COST[action]
    if not _is_affordable(cost, budget):
        return _budget_exhausted(
            reason_code,
            f"{action.value} needs {_format_cost(cost)} but the remaining budget is insufficient",
        )
    return _decision(action, reason_code, detail, cost)


def _budget_exhausted(reason_code: str, detail: str) -> CellRecoveryDecision:
    return _decision(
        CellRecoveryAction.STOP_BUDGET_EXHAUSTED,
        "BUDGET_EXHAUSTED",
        f"{reason_code}: {detail}",
    )


def _is_affordable(cost: Mapping[str, int], budget: CellRecoveryBudget) -> bool:
    return all(
        getattr(budget, _BUDGET_FIELDS[key]) >= amount
        for key, amount in cost.items()
    )


def _format_cost(cost: Mapping[str, int]) -> str:
    return ", ".join(f"{key}={amount}" for key, amount in sorted(cost.items()))


def _has_repeated_plan_digest(digests: tuple[str, ...], limit: int) -> bool:
    if limit <= 0 or len(digests) < limit:
        return False
    recent = [str(item).strip() for item in digests[-limit:]]
    if not recent or not recent[0]:
        return False
    return all(item == recent[0] for item in recent)


def _dominant_reason(signals: Mapping[str, int]) -> str | None:
    """返回主导拒绝原因：计数最大者，并列时取原因名最小者（确定性）。"""
    candidates = [
        (name, count)
        for name, count in signals.items()
        if count > 0 and name in _REASON_ACTIONS
    ]
    if not candidates:
        return None
    candidates.sort(key=lambda item: (-item[1], item[0]))
    return candidates[0][0]


def _has_signal(signals: Mapping[str, int], key: str) -> bool:
    return signals.get(key, 0) > 0


def _has_any_signal(signals: Mapping[str, int], keys: frozenset[str]) -> bool:
    return any(signals.get(key, 0) > 0 for key in keys)


def _normalize_state(cell_state: str) -> str:
    return str(cell_state or "").strip().upper()


def _normalize_summary(rejection_summary: Mapping[str, int]) -> dict[str, int]:
    """把原因摘要归一为确定、非负的计数映射，避免输入顺序影响结果。"""
    normalized: dict[str, int] = {}
    for raw_key, raw_value in rejection_summary.items():
        key = str(raw_key).strip().upper()
        if not key:
            continue
        try:
            count = int(raw_value)
        except (TypeError, ValueError):
            count = 0
        normalized[key] = normalized.get(key, 0) + max(0, count)
    return normalized


def _decision(
    action: CellRecoveryAction,
    reason_code: str,
    detail: str,
    budget_cost: Mapping[str, int] | None = None,
) -> CellRecoveryDecision:
    return CellRecoveryDecision(
        action=action,
        reason_code=reason_code,
        detail=_truncate(detail),
        budget_cost=dict(budget_cost or {}),
    )


def _truncate(text: str, limit: int = _MAX_DETAIL_CHARS) -> str:
    collapsed = " ".join(str(text).split())
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[: limit - 1].rstrip() + "…"


__all__ = [
    "CellRecoveryAction",
    "CellRecoveryBudget",
    "CellRecoveryDecision",
    "CellRecoveryHistory",
    "CellRecoveryThresholds",
    "decide",
]
