"""DR-101：结构化抽取结果契约。

修复前，`extract_evidence_cards` 只在返回空列表时表达「没有卡」，调用方无法区分：

- 抽取根本没跑（未配置 LLM）；
- Provider 返回空或非法 JSON；
- 模型返回了卡，但每一张都被拒绝，且拒绝原因散落在 `continue` 里。

基线证据（`experiments/deep-research/baseline/README.md`）显示两类零卡 Run 在
`budget_usage` 上完全相同（`llm_calls` 之外没有信息），因此证据不足与配置缺失
无法被解释。本模块把每个抽取出口变成结构化结果，让上层无需从日志字符串猜测原因。

约定：

- 该契约只描述 Worker 观察到的抽取结果，不决定 Run 是否完成；
- `provider_receipt` 只保存摘要与 digest，不落模型原文，避免不可信内容扩散与体积膨胀；
- `diagnostics()` 输出有界 JSON，可直接进入完成信封的 trace。
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field

from app.models import ResearchEvidenceCard

# 单条拒绝记录的 detail 上限：足以定位问题，又不至于把模型输出搬进证据库。
_MAX_DETAIL_CHARS = 240
# diagnostics 中保留的拒绝样本数；完整计数仍然逐原因统计。
_MAX_REJECTION_SAMPLES = 8


class ExtractionFailureReason(StrEnum):
    """响应级与卡片级拒绝原因的统一词表。

    第 7.1 节要求至少覆盖 INVALID_JSON、UNKNOWN_WINDOW、WRONG_COLUMN、
    NON_EXACT_QUOTE、EMPTY_CLAIM、UNSUPPORTED_RELATION；其余取值用于把当前
    `continue` 分支显式化，避免出现「没有原因码的零卡」。
    """

    # 响应级
    LLM_UNAVAILABLE = "LLM_UNAVAILABLE"
    INVALID_JSON = "INVALID_JSON"
    MISSING_EVIDENCE_CARDS = "MISSING_EVIDENCE_CARDS"

    # 卡片级
    NON_OBJECT_CARD = "NON_OBJECT_CARD"
    MISSING_WINDOW_ID = "MISSING_WINDOW_ID"
    UNKNOWN_WINDOW = "UNKNOWN_WINDOW"
    WRONG_COLUMN = "WRONG_COLUMN"
    EMPTY_QUOTE = "EMPTY_QUOTE"
    NON_EXACT_QUOTE = "NON_EXACT_QUOTE"
    EMPTY_CLAIM = "EMPTY_CLAIM"
    UNSUPPORTED_RELATION = "UNSUPPORTED_RELATION"
    DUPLICATE_EVIDENCE = "DUPLICATE_EVIDENCE"


class ExtractionTerminationReason(StrEnum):
    """抽取阶段的终止原因；零卡时必须是某个具体失败原因，而不是笼统的空。"""

    ACCEPTED_CARDS = "ACCEPTED_CARDS"
    NO_READ_WINDOWS = "NO_READ_WINDOWS"
    LLM_UNAVAILABLE = ExtractionFailureReason.LLM_UNAVAILABLE.value
    INVALID_JSON = ExtractionFailureReason.INVALID_JSON.value
    MISSING_EVIDENCE_CARDS = ExtractionFailureReason.MISSING_EVIDENCE_CARDS.value
    ALL_CARDS_REJECTED = "ALL_CARDS_REJECTED"


class ExtractionProviderReceipt(BaseModel):
    """Provider 调用的最小收据：模型身份、调用次数与响应摘要，不含原文。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    purpose: str = "research.extract"
    transport: str = "openai-compatible"
    model: str = ""
    call_count: int = Field(default=0, ge=0)
    response_digest: str = ""
    response_chars: int = Field(default=0, ge=0)


class RejectedEvidenceCard(BaseModel):
    """被拒绝的一张候选卡及其确定原因。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    reason: ExtractionFailureReason
    window_id: str = ""
    column_key: str = ""
    detail: str = Field(default="", max_length=_MAX_DETAIL_CHARS)


class ExtractionResult(BaseModel):
    """一次抽取的结构化出口。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    accepted_cards: tuple[ResearchEvidenceCard, ...] = ()
    rejected_cards: tuple[RejectedEvidenceCard, ...] = ()
    provider_receipt: ExtractionProviderReceipt = Field(default_factory=ExtractionProviderReceipt)
    termination_reason: ExtractionTerminationReason = ExtractionTerminationReason.ACCEPTED_CARDS

    def __init__(self, **data: object) -> None:
        for field in ("accepted_cards", "rejected_cards"):
            value = data.get(field)
            if isinstance(value, list):
                data[field] = tuple(value)
        super().__init__(**data)

    @property
    def accepted_count(self) -> int:
        return len(self.accepted_cards)

    @property
    def rejected_count(self) -> int:
        return len(self.rejected_cards)

    def rejection_counts(self) -> dict[str, int]:
        """按原因统计拒绝数量，键稳定排序，便于断言与展示。"""
        counts: dict[str, int] = {}
        for rejected in self.rejected_cards:
            key = rejected.reason.value
            counts[key] = counts.get(key, 0) + 1
        return dict(sorted(counts.items()))

    def diagnostics(self) -> dict[str, object]:
        """输出有界的机器可读诊断，用于 completion trace 与 DB 持久化。"""
        payload: dict[str, object] = {
            "schema_version": "research-extraction-diagnostics.v1",
            "termination_reason": self.termination_reason.value,
            "accepted_count": self.accepted_count,
            "rejected_count": self.rejected_count,
            "rejection_counts": self.rejection_counts(),
            "provider_receipt": self.provider_receipt.model_dump(mode="json"),
        }
        if self.rejected_cards:
            samples = sorted(
                self.rejected_cards,
                key=lambda item: (item.reason.value, item.window_id, item.column_key),
            )[:_MAX_REJECTION_SAMPLES]
            payload["rejection_samples"] = [item.model_dump(mode="json") for item in samples]
        return payload


#: 第 7.1 节明确点名的拒绝原因，供契约测试断言词表不回退。
REQUIRED_REJECTION_REASONS = (
    ExtractionFailureReason.INVALID_JSON,
    ExtractionFailureReason.UNKNOWN_WINDOW,
    ExtractionFailureReason.WRONG_COLUMN,
    ExtractionFailureReason.NON_EXACT_QUOTE,
    ExtractionFailureReason.EMPTY_CLAIM,
    ExtractionFailureReason.UNSUPPORTED_RELATION,
)
