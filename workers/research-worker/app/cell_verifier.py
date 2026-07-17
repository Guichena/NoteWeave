"""P0-5: Cell-level evidence verifier (4-way verdict).

借鉴:
- DeepWideSearch metric_utils.py:262 (per-cell llm_judge 模板)
- Marco-Agent-DeepResearch context_manager._llm_verify (LLM tie-break)
- Table-as-Search Internal Knowledge Usage Policy (强制 grounding)

每个 cell 必须经过 CellVerifier 校验才能获得 SUPPORTS verdict。
判定的 4-way 状态:
- SUPPORTS: 证据直接支持 cell value
- PARTIALLY_SUPPORTS: 证据部分支持 (置信度 0.4-0.7)
- CONTRADICTS: 证据与 cell value 冲突或不同来源给出相反结论
- NOT_ENOUGH_INFO: 证据不足,无法判断

P0-4 配合: 当 LLM 不可用时,降级为 NOT_ENOUGH_INFO (诚实拒绝伪造 verdict)。
"""

from __future__ import annotations

from collections import Counter

from app.json_repair import parse_json_payload
from app.llm_client import LlmClient
from app.models import (
    CellSupportStatus,
    CellVerdict,
    ResearchEvidenceCard,
    ResearchStateCell,
)
from app.source_profile import HIGH_TRUST_QUALITY_SCORES


CELL_VERIFIER_PROMPT_TEMPLATE = """You are a strict evidence-grounded cell verifier.

# Task
Given a (entity, column) cell with a candidate value, evaluate whether the evidence cards
support, partially support, contradict, or fail to support that value.

# Cell
- Entity: {entity}
- Column: {column}
- Candidate value: {candidate_value}

# Evidence cards
{evidence_block}

# Rules
1. SUPPORTS only if AT LEAST ONE evidence card directly grounds the candidate value.
2. PARTIALLY_SUPPORTS if the evidence is related but incomplete or hedged.
3. CONTRADICTS if any card explicitly disagrees with the candidate value, OR if cards give
   mutually contradictory answers.
4. NOT_ENOUGH_INFO if no card is even topically related to the column.
5. NEVER invent evidence that is not in the cards.
6. Confidence must reflect how directly the evidence quotes the candidate value:
   - SUPPORTS with verbatim quote: 0.85-0.95
   - SUPPORTS with paraphrase only: 0.6-0.8
   - PARTIALLY_SUPPORTS: 0.4-0.65
   - CONTRADICTS: 0.7-0.95
   - NOT_ENOUGH_INFO: 0.0-0.3

# Output JSON schema (strict)
{{
  "status": "SUPPORTS | PARTIALLY_SUPPORTS | CONTRADICTS | NOT_ENOUGH_INFO",
  "confidence": 0.0,
  "reason": "short explanation",
  "suggested_revision": "improved value or empty"
}}
"""


class CellVerifier:
    """P0-5: 4-way cell-level evidence verifier."""

    def __init__(self, llm_client: LlmClient | None = None) -> None:
        self.llm_client = llm_client

    def verify_cells(
        self,
        cells: list[ResearchStateCell],
        evidence_cards_by_cell: dict[str, list[ResearchEvidenceCard]],
        entity_display_by_id: dict[str, str] | None = None,
    ) -> tuple[list[ResearchStateCell], list[CellVerdict]]:
        """批量验证 cell,返回更新后的 cells 与对应的 CellVerdict 列表。

        evidence_cards_by_cell: {cell_id: [evidence_cards]} — 注意 cell_id 即 entity_id:column_key
        entity_display_by_id: {entity_id: display_name} — 用于 prompt 中的 entity 提示
        """
        updated_cells: list[ResearchStateCell] = []
        verdicts: list[CellVerdict] = []
        entity_display_by_id = entity_display_by_id or {}
        for cell in cells:
            cards = evidence_cards_by_cell.get(cell.cell_id, [])
            verdict = self._verify_one(cell, cards, entity_display_by_id.get(cell.entity_id, ""))
            updated_cells.append(
                cell.model_copy(
                    update={
                        "verdict": verdict.status,
                        "verdict_reason": verdict.reason,
                        "verdict_confidence": verdict.confidence,
                        "verdict_used_llm": verdict.used_llm,
                        # verdict_round 由 caller 维护
                    }
                )
            )
            verdicts.append(verdict)
        return updated_cells, verdicts

    def _verify_one(
        self,
        cell: ResearchStateCell,
        cards: list[ResearchEvidenceCard],
        entity_display: str,
    ) -> CellVerdict:
        """单 cell 验证逻辑。

        流程:
        1. 预筛:无 cards -> NOT_ENOUGH_INFO
        2. 冲突预筛:cards 同时含 SUPPORTS 与 CONFLICTS -> LLM 判定
        3. LLM 判定:若 LLM 可用 -> LLM JSON 解析
        4. 降级:LLM 不可用 / 解析失败 -> 规则 fallback
        """
        if not cards:
            return CellVerdict(
                cell_id=cell.cell_id,
                status=CellSupportStatus.NOT_ENOUGH_INFO,
                confidence=0.0,
                reason="no evidence cards bound to this cell",
                suggested_revision="",
                evidence_ids=[],
                used_llm=False,
            )

        # 冲突预筛:cards 同时含 SUPPORTS 与 CONFLICTS -> LLM 必须解释
        relation_counts = Counter(card.relation_type for card in cards)
        has_both_sides = relation_counts.get("SUPPORTS", 0) > 0 and relation_counts.get("CONFLICTS", 0) > 0
        conflicts = [c for c in cards if c.relation_type == "CONFLICTS"]
        supports = [c for c in cards if c.relation_type == "SUPPORTS"]
        if conflicts and not supports:
            return CellVerdict(
                cell_id=cell.cell_id,
                status=CellSupportStatus.CONTRADICTS,
                confidence=max(0.5, sum(c.conflict_score for c in conflicts) / len(conflicts)),
                reason="only conflict evidence is bound to this cell",
                suggested_revision="run a bounded counterfactual branch",
                evidence_ids=[c.evidence_id for c in conflicts],
                used_llm=False,
            )

        if self.llm_client is not None:
            llm_verdict = self._verify_with_llm(cell, cards, entity_display)
            if llm_verdict is not None:
                return llm_verdict

        # 降级规则(无 LLM 或 LLM 失败):
        # - 仅有 SUPPORTS 且 >= 2 张高信任 -> SUPPORTS (低置信 0.6)
        # - 同时含 SUPPORTS + CONFLICTS -> CONTRADICTS
        # - 仅有 CONFLICTS -> CONTRADICTS
        # - 仅有 WEAK_SUPPORT -> NOT_ENOUGH_INFO (诚实)
        if has_both_sides:
            return CellVerdict(
                cell_id=cell.cell_id,
                status=CellSupportStatus.CONTRADICTS,
                confidence=0.55,
                reason="evidence cards contain both SUPPORTS and CONFLICTS (LLM unavailable, rule fallback)",
                suggested_revision="run with LLM enabled to get a nuanced verdict",
                evidence_ids=[card.evidence_id for card in cards],
                used_llm=False,
            )
        if supports:
            avg = sum(c.support_score for c in supports) / max(1, len(supports))
            avg_source_quality = self._avg_source_quality(supports)
            # 综合 score = support_score * source_quality_factor
            composite = avg * avg_source_quality
            if composite >= 0.7:
                return CellVerdict(
                    cell_id=cell.cell_id,
                    status=CellSupportStatus.SUPPORTS,
                    confidence=round(composite, 3),
                    reason=f"rule fallback: {len(supports)} SUPPORTS cards, avg score={avg:.2f}, source_quality={avg_source_quality:.2f}",
                    suggested_revision="",
                    evidence_ids=[c.evidence_id for c in supports],
                    used_llm=False,
                )
            return CellVerdict(
                cell_id=cell.cell_id,
                status=CellSupportStatus.PARTIALLY_SUPPORTS,
                confidence=round(composite, 3),
                reason=f"rule fallback: {len(supports)} SUPPORTS cards but composite score low ({composite:.2f})",
                suggested_revision="",
                evidence_ids=[c.evidence_id for c in supports],
                used_llm=False,
            )
        if conflicts:
            return CellVerdict(
                cell_id=cell.cell_id,
                status=CellSupportStatus.CONTRADICTS,
                confidence=0.5,
                reason="rule fallback: only CONFLICTS cards bound to this cell",
                suggested_revision="",
                evidence_ids=[c.evidence_id for c in conflicts],
                used_llm=False,
            )
        # WEAK_SUPPORT 单独 / 没匹配
        return CellVerdict(
            cell_id=cell.cell_id,
            status=CellSupportStatus.NOT_ENOUGH_INFO,
            confidence=0.2,
            reason="rule fallback: evidence cards are topically related but neither SUPPORTS nor CONFLICTS",
            suggested_revision="",
            evidence_ids=[card.evidence_id for card in cards],
            used_llm=False,
        )

    def _verify_with_llm(
        self,
        cell: ResearchStateCell,
        cards: list[ResearchEvidenceCard],
        entity_display: str,
    ) -> CellVerdict | None:
        """调用 LLM 4-way verdict。失败时返回 None,触发降级。"""
        if self.llm_client is None:
            return None
        evidence_block_lines: list[str] = []
        for index, card in enumerate(cards[:5], start=1):  # P0-9: 限制 5 张
            evidence_block_lines.append(
                f"[{index}] source={card.source_title} relation={card.relation_type}\n"
                f"    claim: {card.claim_text}\n"
                f"    quote: {card.quote_text}"
            )
        evidence_block = "\n\n".join(evidence_block_lines) or "(no evidence cards)"
        prompt = CELL_VERIFIER_PROMPT_TEMPLATE.format(
            entity=entity_display or cell.entity_id or cell.row_id,
            column=cell.column_key,
            candidate_value=cell.candidate_value or "(empty)",
            evidence_block=evidence_block,
        )
        try:
            response = self.llm_client.complete_json(
                "research.verify.cell",
                {"prompt": prompt, "cell_id": cell.cell_id},
            )
        except Exception:
            return None
        payload = parse_json_payload(response)
        if not isinstance(payload, dict):
            return None
        status_raw = str(payload.get("status") or "").strip().upper()
        try:
            status = CellSupportStatus(status_raw)
        except ValueError:
            return None
        try:
            confidence = float(payload.get("confidence") or 0.0)
        except (TypeError, ValueError):
            confidence = 0.0
        confidence = min(1.0, max(0.0, round(confidence, 3)))
        return CellVerdict(
            cell_id=cell.cell_id,
            status=status,
            confidence=confidence,
            reason=str(payload.get("reason") or "").strip()[:500],
            suggested_revision=str(payload.get("suggested_revision") or "").strip(),
            evidence_ids=[card.evidence_id for card in cards],
            used_llm=True,
        )

    @staticmethod
    def _avg_source_quality(cards: list[ResearchEvidenceCard]) -> float:
        """P0-5: 估算 evidence cards 的平均 source quality factor。

        由于 ResearchEvidenceCard 不带显式 source_quality_score,
        这里按 relation_type 给出合理兜底值:
        - SUPPORTS: 0.85 (LLM 抽取的卡片本身就是高质量证据)
        - WEAK_SUPPORT: 0.65
        - CONFLICTS: 1.0 (冲突信号本身比支持信号更有价值,不应该被折扣)
        """
        if not cards:
            return 0.0
        factor_by_relation = {"SUPPORTS": 0.85, "WEAK_SUPPORT": 0.65, "CONFLICTS": 1.0}
        scores = [factor_by_relation.get(card.relation_type, 0.55) for card in cards]
        return sum(scores) / max(1, len(scores))
