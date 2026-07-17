from __future__ import annotations

import re

from app.entity_identity import canonical_entity_id, canonical_entity_name
from app.models import (
    CellSupportStatus,
    CellVerdict,
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchBranchSession,
    ResearchEntityRow,
    ResearchEvidenceCard,
    ResearchPlan,
    ResearchReadWindow,
    ResearchRequiredFindingProgress,
    ResearchSearchHit,
    ResearchStateBranch,
    ResearchStateCell,
    ResearchStateLedger,
    ResearchStateRow,
    ResearchTaskInput,
    ResearchVerifierDecision,
)


# ---------------------------------------------------------------------------
# P0-1: EntityResolver — 把 search_hits/read_windows 归一化为候选研究实体
# ---------------------------------------------------------------------------


class EntityResolver:
    """把搜索命中与证据卡片归一化到 ResearchEntityRow。

    P0-1 重构核心:row 不再是 evidence_card,而是"候选研究实体"(如具体的产品/论文/GitHub repo)。
    同一实体的多张证据卡片会在 cell 阶段合并成 (entity_id, column_key) 单元。
    """

    def __init__(self, plan: ResearchPlan, round_no: int = 1) -> None:
        self.plan = plan
        self.round_no = round_no
        self._entities: dict[str, ResearchEntityRow] = {}

    def resolve_from_hits(
        self,
        search_hits: list[ResearchSearchHit],
        read_windows: list[ResearchReadWindow] | None = None,
    ) -> list[ResearchEntityRow]:
        for hit in search_hits:
            entity = self._upsert_from_hit(hit)
            if read_windows:
                for window in read_windows:
                    if window.hit_id and window.hit_id == hit.hit_id:
                        self._enrich_from_window(entity, window)
        return list(self._entities.values())

    def resolve_from_cards(
        self, evidence_cards: list[ResearchEvidenceCard]
    ) -> list[ResearchEntityRow]:
        for card in evidence_cards:
            entity = self._upsert_from_card(card)
        return list(self._entities.values())

    def all(self) -> list[ResearchEntityRow]:
        return list(self._entities.values())

    def get(self, entity_id: str) -> ResearchEntityRow | None:
        return self._entities.get(entity_id)

    # -- internal -----------------------------------------------------------

    def _upsert_from_hit(self, hit: ResearchSearchHit) -> ResearchEntityRow:
        key = canonical_entity_id(
            source_title=hit.source_title,
            source_id=hit.source_id,
            url=hit.url,
        )
        if key in self._entities:
            entity = self._entities[key]
            entity.source_ids = list(dict.fromkeys([*entity.source_ids, hit.source_id]))
            entity.source_urls = list(dict.fromkeys([*entity.source_urls, hit.url]))
            return entity
        entity = ResearchEntityRow(
            entity_id=key,
            entity_type=self.plan.target_entity_type or "candidate",
            display_name=(hit.source_title or hit.url or key).strip()[:200] or key,
            source_id=hit.source_id or "",
            source_title=hit.source_title or "",
            source_url=hit.url or "",
            source_quality=hit.source_quality or "GENERAL_WEB",
            source_ids=[hit.source_id] if hit.source_id else [],
            source_urls=[hit.url] if hit.url else [],
            first_seen_round=self.round_no,
        )
        self._entities[key] = entity
        return entity

    def _upsert_from_card(self, card: ResearchEvidenceCard) -> ResearchEntityRow:
        key = card.entity_id or canonical_entity_id(
            entity_hint=card.entity_name,
            source_title=card.source_title,
            source_id=card.source_id,
            url=card.source_url,
        )
        if key in self._entities:
            entity = self._entities[key]
            entity.source_ids = list(dict.fromkeys([*entity.source_ids, card.source_id]))
            entity.source_urls = list(dict.fromkeys([*entity.source_urls, card.source_url]))
            if card.source_quality != "GENERAL_WEB":
                entity.source_quality = card.source_quality
            return entity
        entity = ResearchEntityRow(
            entity_id=key,
            entity_type=self.plan.target_entity_type or "candidate",
            display_name=canonical_entity_name(card.entity_name, card.source_title, card.source_id),
            source_id=card.source_id or "",
            source_title=card.source_title or "",
            source_url=card.source_url,
            source_quality=card.source_quality,
            source_ids=[card.source_id] if card.source_id else [],
            source_urls=[card.source_url] if card.source_url else [],
            first_seen_round=self.round_no,
        )
        self._entities[key] = entity
        return entity

    def _enrich_from_window(
        self, entity: ResearchEntityRow, window: ResearchReadWindow
    ) -> None:
        if window.source_quality and window.source_quality != "GENERAL_WEB":
            entity.source_quality = window.source_quality
        if window.url and not entity.source_url:
            entity.source_url = window.url


# ---------------------------------------------------------------------------
# P0-1 + P0-5: 重写 build_state_ledger — row=entity, cell=column
# ---------------------------------------------------------------------------


def build_state_ledger(
    task_input: ResearchTaskInput,
    plan: ResearchPlan,
    search_hits: list[ResearchSearchHit],
    read_windows: list[ResearchReadWindow],
    evidence_cards: list[ResearchEvidenceCard],
) -> ResearchStateLedger:
    """构建 Table-as-State 账本:

    - entities: 从 search_hits 与 evidence_cards 归一化出的研究实体
    - rows:    每个 entity 一行
    - cells:   每个 (entity_id, column_key) 一个 cell
    - verdicts: 初始 verdict 全部为 NOT_ENOUGH_INFO,等 CellVerifier 调用后再更新
    """
    resolver = EntityResolver(plan)
    resolver.resolve_from_cards(evidence_cards)
    # Search hits are source candidates, not automatically business entities.
    # They seed provisional rows only while extraction has not identified any
    # schema entities. Once cards exist, their system-owned entity bindings are
    # authoritative and sources remain provenance on those entities.
    if not resolver.all():
        resolver.resolve_from_hits(search_hits, read_windows)
    entities = resolver.all()

    rows: list[ResearchStateRow] = []
    cells: list[ResearchStateCell] = []
    cell_verdicts: list[CellVerdict] = []
    # P0-1: 注意 Pydantic v2 BaseModel 有 schema() 方法,字段名改用 research_schema
    schema = plan.research_schema
    column_keys = [col.key for col in schema.columns] or plan.state_columns

    cards_by_source: dict[str, list[ResearchEvidenceCard]] = {}
    for card in evidence_cards:
        entity_id = card.entity_id
        if not entity_id:
            continue
        cards_by_source.setdefault(entity_id, []).append(card)

    for entity in entities:
        cards = cards_by_source.get(entity.entity_id, [])
        # 行级状态聚合:存在 CONFLICTS 卡片 → CONFLICTED;否则按 support_score 决定
        row_status = _aggregate_row_status(cards)
        conflicting_ids = [
            card.evidence_id for card in cards if card.relation_type == "CONFLICTS"
        ]
        verifier_note = _row_verifier_note(cards, row_status)
        repair_hint = _row_repair_hint(row_status)
        rows.append(
            ResearchStateRow(
                row_id=entity.entity_id,
                entity_id=entity.entity_id,
                entity_type=entity.entity_type,
                display_name=entity.display_name,
                source_id=entity.source_id,
                source_title=entity.source_title,
                source_url=entity.source_url,
                source_quality=entity.source_quality,
                source_domain=_derive_domain(entity.source_url),
                search_query=(
                    next((window.query for window in read_windows if window.source_id == entity.source_id), "")
                ),
                read_focus=(
                    next((window.read_focus for window in read_windows if window.source_id == entity.source_id), "")
                ),
                read_strategy=(
                    next((window.read_strategy for window in read_windows if window.source_id == entity.source_id), "BALANCED_READ")
                ),
                row_status=row_status,
                verification_status=_row_verification_status(row_status),
                support_level=_row_support_level(cards),
                verifier_note=verifier_note,
                repair_hint=repair_hint,
                conflicting_evidence_ids=conflicting_ids,
                # 兼容字段
                evidence_id=cards[0].evidence_id if cards else "",
                claim_text=cards[0].claim_text if cards else "",
                quote_text=cards[0].quote_text if cards else "",
                evidence_excerpt=cards[0].quote_text if cards else "",
                relation_type=cards[0].relation_type if cards else "SUPPORTS",
                support_score=cards[0].support_score if cards else 0.0,
                conflict_score=cards[0].conflict_score if cards else 0.0,
            )
        )

        # 每个 (entity, column) 一个 cell
        for column_key in column_keys:
            column_cards = _filter_cards_for_column(cards, column_key)
            candidate_value = _aggregate_cell_value(column_cards, column_key, entity)
            cell = ResearchStateCell(
                cell_id=f"{entity.entity_id}:{column_key}",
                row_id=entity.entity_id,
                entity_id=entity.entity_id,
                column_key=column_key,
                candidate_value=candidate_value,
                status=_cell_status_from_cards(column_cards, row_status),
                confidence=_cell_confidence(column_cards),
                evidence_refs=[card.evidence_id for card in column_cards],
                verdict=CellSupportStatus.NOT_ENOUGH_INFO,  # 等 CellVerifier 调用
                verdict_reason="awaiting CellVerifier",
                verdict_confidence=0.0,
                verdict_round=0,
                verdict_used_llm=False,
                is_required=_is_required_column(column_key, schema),
            )
            cells.append(cell)
            cell_verdicts.append(
                CellVerdict(
                    cell_id=cell.cell_id,
                    status=cell.verdict,
                    confidence=0.0,
                    reason="pending CellVerifier run",
                    evidence_ids=cell.evidence_refs,
                )
            )

    (
        rows,
        cells,
        required_finding_progress,
        requirement_ready_row_count,
        requirement_partial_row_count,
    ) = _apply_requirement_bindings(
        rows=rows,
        cells=cells,
        evidence_cards=evidence_cards,
        read_windows=read_windows,
        contracts=list(plan.stop_contract.get("required_finding_contract", [])),
        schema=schema,
    )

    unresolved_questions: list[str] = []
    if not search_hits:
        unresolved_questions.append(
            "No source matched the research query bundle in the current workspace scope."
        )
    elif not read_windows:
        unresolved_questions.append(
            "Search hits exist, but no read window was retained for verification."
        )
    elif not evidence_cards:
        unresolved_questions.append(
            "Read windows exist, but no evidence card was extracted for synthesis."
        )

    row_status_summary = _row_status_summary(rows)
    verified_row_count = row_status_summary.get("VERIFIED", 0)
    conflicted_row_count = row_status_summary.get("CONFLICTED", 0)

    coverage_score = round(
        min(1.0, verified_row_count / max(1, int(plan.stop_contract.get("min_sources", 1)))),
        4,
    )

    return ResearchStateLedger(
        schema=schema,
        entities=entities,
        columns=column_keys,
        rows=rows,
        cells=cells,
        branch_sessions=[
            ResearchBranchSession(
                session_id="session-main",
                branch_id="branch-main",
                parent_session_id="",
                parent_branch_id="",
                session_role="MAINLINE",
                execution_mode="MAINLINE",
                status="MAINLINE",
                created_round=1,
            )
        ],
        branches=[
            ResearchStateBranch(
                branch_id="branch-main",
                session_id="session-main",
                parent_branch_id="",
                parent_session_id="",
                branch_reason="MAINLINE",
                hypothesis_summary="Primary verified research path.",
                execution_mode="MAINLINE",
                status="MAINLINE",
                created_round=1,
            )
        ],
        verifier_decisions=[],
        cell_verdicts=cell_verdicts,
        required_finding_contract=list(plan.stop_contract.get("required_finding_contract", [])),
        required_finding_progress=required_finding_progress,
        unresolved_questions=unresolved_questions,
        row_status_summary=row_status_summary,
        search_hit_count=len(search_hits),
        read_window_count=len(read_windows),
        evidence_card_count=len(evidence_cards),
        verified_row_count=verified_row_count,
        conflicted_row_count=conflicted_row_count,
        cell_verdict_counts={"NOT_ENOUGH_INFO": len(cell_verdicts)},
        coverage_score=coverage_score,
        active_branch_id="branch-main",
        requirement_ready_row_count=requirement_ready_row_count,
        requirement_partial_row_count=requirement_partial_row_count,
    )


def _apply_requirement_bindings(
    *,
    rows: list[ResearchStateRow],
    cells: list[ResearchStateCell],
    evidence_cards: list[ResearchEvidenceCard],
    read_windows: list[ResearchReadWindow],
    contracts: list[dict[str, object]],
    schema,
) -> tuple[
    list[ResearchStateRow],
    list[ResearchStateCell],
    list[ResearchRequiredFindingProgress],
    int,
    int,
]:
    windows_by_id = {window.window_id: window for window in read_windows}
    cards_by_entity: dict[str, list[ResearchEvidenceCard]] = {}
    for card in evidence_cards:
        if card.entity_id:
            cards_by_entity.setdefault(card.entity_id, []).append(card)
    cells_by_entity: dict[str, list[ResearchStateCell]] = {}
    for cell in cells:
        cells_by_entity.setdefault(cell.entity_id or cell.row_id, []).append(cell)
    schema_required = [column.key for column in schema.columns if column.required]

    row_matches: dict[str, list[str]] = {row.row_id: [] for row in rows}
    row_ready: dict[str, list[str]] = {row.row_id: [] for row in rows}
    updated_cells = list(cells)
    progress_items: list[ResearchRequiredFindingProgress] = []

    for contract in contracts:
        requirement_id = str(contract.get("requirement_id") or "").strip()
        if not requirement_id:
            continue
        requirement_type = str(contract.get("requirement_type") or "GOAL_FINDING").strip()
        accepted_statuses = [
            str(item).strip().upper()
            for item in contract.get("accepted_row_statuses", ["VERIFIED"])
            if str(item).strip()
        ]
        configured_columns = [
            str(item).strip()
            for item in contract.get("required_columns", [])
            if str(item).strip()
        ]
        required_columns = [item for item in configured_columns if item in {c.key for c in schema.columns}]
        if not required_columns:
            if requirement_type == "CONFLICT_FINDING":
                required_columns = list(
                    dict.fromkeys(
                        card.column_key
                        for card in evidence_cards
                        if card.relation_type == "CONFLICTS" and card.column_key
                    )
                )
            else:
                required_columns = list(schema_required)

        matched_row_ids: list[str] = []
        ready_row_ids: list[str] = []
        partial_row_ids: list[str] = []
        for row in rows:
            entity_cards = cards_by_entity.get(row.entity_id or row.row_id, [])
            targeted_ids = {
                target_id
                for card in entity_cards
                for target_id in (
                    windows_by_id.get(card.window_id).target_requirement_ids
                    if windows_by_id.get(card.window_id) is not None
                    else []
                )
            }
            explicitly_targeted = requirement_id in targeted_ids
            type_matches = not targeted_ids and ((
                requirement_type == "CONFLICT_FINDING" and row.row_status == "CONFLICTED"
            ) or (
                requirement_type != "CONFLICT_FINDING" and bool(entity_cards)
            ))
            if not explicitly_targeted and not type_matches:
                continue
            matched_row_ids.append(row.row_id)
            row_matches[row.row_id].append(requirement_id)
            entity_cells = cells_by_entity.get(row.entity_id or row.row_id, [])
            completed_columns = {
                cell.column_key
                for cell in entity_cells
                if cell.column_key in required_columns
                and cell.candidate_value.strip()
                and cell.status not in {"EMPTY", "NEED_MORE_EVIDENCE", "FROZEN"}
            }
            is_ready = (
                row.row_status in accepted_statuses
                and all(column in completed_columns for column in required_columns)
            )
            if is_ready:
                ready_row_ids.append(row.row_id)
                row_ready[row.row_id].append(requirement_id)
            else:
                partial_row_ids.append(row.row_id)

            next_cells: list[ResearchStateCell] = []
            for cell in updated_cells:
                if cell.row_id != row.row_id or cell.column_key not in required_columns:
                    next_cells.append(cell)
                    continue
                required_by = list(dict.fromkeys([*cell.required_by_requirement_ids, requirement_id]))
                satisfied = list(cell.satisfied_requirement_ids)
                if is_ready and requirement_id not in satisfied:
                    satisfied.append(requirement_id)
                next_cells.append(
                    cell.model_copy(
                        update={
                            "required_by_requirement_ids": required_by,
                            "satisfied_requirement_ids": satisfied,
                            "requirement_completion_status": "READY" if is_ready else "MISSING",
                        }
                    )
                )
            updated_cells = next_cells

        target_count = max(1, int(contract.get("target_row_count") or 1))
        status = "READY" if len(ready_row_ids) >= target_count else "PARTIAL" if matched_row_ids else "MISSING"
        progress_items.append(
            ResearchRequiredFindingProgress(
                requirement_id=requirement_id,
                requirement_type=requirement_type,
                label=str(contract.get("label") or requirement_id),
                completion_mode=str(contract.get("completion_mode") or "ROW_EVIDENCE"),
                target_row_count=target_count,
                accepted_row_statuses=accepted_statuses,
                required_columns=required_columns,
                matched_row_ids=matched_row_ids,
                ready_row_ids=ready_row_ids,
                partial_row_ids=partial_row_ids,
                status=status,
            )
        )

    final_rows: list[ResearchStateRow] = []
    for row in rows:
        entity_cells = [cell for cell in updated_cells if cell.row_id == row.row_id]
        required_columns = [column.key for column in schema.columns if column.required]
        completed_columns = {
            cell.column_key
            for cell in entity_cells
            if cell.candidate_value.strip() and cell.status not in {"EMPTY", "NEED_MORE_EVIDENCE", "FROZEN"}
        }
        missing_columns = [column for column in required_columns if column not in completed_columns]
        matches = list(dict.fromkeys(row_matches[row.row_id]))
        ready = list(dict.fromkeys(row_ready[row.row_id]))
        final_rows.append(
            row.model_copy(
                update={
                    "matched_requirement_ids": matches,
                    "ready_requirement_ids": ready,
                    "required_column_count": len(required_columns),
                    "completed_column_count": len(required_columns) - len(missing_columns),
                    "missing_columns": missing_columns,
                    "requirement_completion_status": (
                        "READY" if matches and len(ready) == len(matches) else "PARTIAL" if matches else "NOT_REQUIRED"
                    ),
                }
            )
        )
    ready_rows = {row_id for item in progress_items for row_id in item.ready_row_ids}
    partial_rows = {row_id for item in progress_items for row_id in item.partial_row_ids}
    return final_rows, updated_cells, progress_items, len(ready_rows), len(partial_rows)


def finalize_state_ledger(
    ledger: ResearchStateLedger,
    local_result: LocalVerifierResult,
    global_result: GlobalVerifierResult,
    branch_decisions: list[ResearchBranchDecision],
    round_no: int,
) -> ResearchStateLedger:
    """P0-2: 应用分支决策时,只修改相关 cell,不动 VERIFIED 的 cell。"""
    branch_sessions = list(ledger.branch_sessions)
    branches = list(ledger.branches)
    active_branch_ids: list[str] = []
    for branch_decision in branch_decisions:
        if branch_decision.decision == "NO_BRANCH" or (
            branch_decision.branch_id == "branch-main"
            and branch_decision.branch_status == "MAINLINE"
        ):
            continue
        is_active = branch_decision.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
        if is_active:
            active_branch_ids.append(branch_decision.branch_id)
        existing_session_index = next(
            (
                index
                for index, session in enumerate(branch_sessions)
                if session.session_id == branch_decision.session_id
            ),
            None,
        )
        if existing_session_index is None:
            branch_sessions.append(
                ResearchBranchSession(
                    session_id=branch_decision.session_id,
                    branch_id=branch_decision.branch_id,
                    parent_session_id=branch_decision.parent_session_id,
                    parent_branch_id=branch_decision.parent_branch_id,
                    session_role=branch_decision.decision,
                    execution_mode=branch_decision.execution_mode,
                    target_evidence_ids=list(branch_decision.target_evidence_ids),
                    sibling_branch_ids=list(branch_decision.sibling_branch_ids),
                    status=branch_decision.branch_status.replace("_BRANCH", "") or "ACTIVE",
                    created_round=round_no,
                )
            )
        else:
            branch_sessions[existing_session_index] = branch_sessions[
                existing_session_index
            ].model_copy(
                update={
                    "branch_id": branch_decision.branch_id,
                    "parent_branch_id": branch_decision.parent_branch_id,
                    "session_role": branch_decision.decision,
                    "execution_mode": branch_decision.execution_mode,
                    "status": "ACTIVE" if is_active else "RESOLVED",
                    "target_evidence_ids": list(branch_decision.target_evidence_ids),
                    "sibling_branch_ids": list(branch_decision.sibling_branch_ids),
                }
            )
        branch_state = ResearchStateBranch(
                branch_id=branch_decision.branch_id,
                session_id=branch_decision.session_id,
                parent_branch_id=branch_decision.parent_branch_id,
                parent_session_id=branch_decision.parent_session_id,
                branch_reason=branch_decision.branch_reason,
                hypothesis_summary=branch_decision.hypothesis_summary
                or _branch_hypothesis(branch_decision),
                target_evidence_ids=list(branch_decision.target_evidence_ids),
                sibling_branch_ids=list(branch_decision.sibling_branch_ids),
                execution_mode=branch_decision.execution_mode,
                status=branch_decision.branch_status,
                created_round=round_no,
                resolution=branch_decision.resolution,
                resolved_round=branch_decision.resolved_round,
                result_evidence_ids=list(branch_decision.result_evidence_ids),
            )
        existing_branch_index = next(
            (
                index
                for index, branch in enumerate(branches)
                if branch.branch_id == branch_decision.branch_id
            ),
            None,
        )
        if existing_branch_index is None:
            branches.append(branch_state)
        else:
            branches[existing_branch_index] = branch_state

    verifier_decisions = _dedupe_verifier_decisions(
        list(local_result.decision_records)
        + list(global_result.decision_records)
        + _branch_verifier_decisions(branch_decisions)
    )
    active_branch_ids = list(dict.fromkeys(active_branch_ids))
    active_branch_id = active_branch_ids[0] if active_branch_ids else "branch-main"
    cells = _apply_verifier_decisions_to_cells_targeted(
        ledger.cells, branch_decisions, verifier_decisions
    )
    rows = _apply_branch_to_rows_targeted(ledger.rows, branch_decisions)
    return ledger.model_copy(
        update={
            "rows": rows,
            "cells": cells,
            "branch_sessions": branch_sessions,
            "branches": branches,
            "verifier_decisions": verifier_decisions,
            "active_branch_id": active_branch_id,
            "active_branch_ids": active_branch_ids,
            "intent_completion_contract": global_result.intent_completion_contract,
        }
    )


# ---------------------------------------------------------------------------
# P0-2: Targeted cell/row 更新 (替换原来全场清零的实现)
# ---------------------------------------------------------------------------


def _apply_verifier_decisions_to_cells_targeted(
    cells: list[ResearchStateCell],
    branch_decisions: list[ResearchBranchDecision],
    verifier_decisions: list[ResearchVerifierDecision],
) -> list[ResearchStateCell]:
    """P0-2: 只针对分支决策 target 命中的 cell 做状态更新,其他 cell 保持不变。

    关键设计点(对比原实现的"全场清零"):
    - COUNTERFACTUAL_RECHECK + 非空 target_evidence_ids:
        只把 evidence_refs 包含这些 id 的 cell 标为 CONFLICTED
    - 其他 recovery 决策:
        只对当前 status == "FILLED" / "CANDIDATE_READY" 的 cell 标为 NEED_MORE_EVIDENCE
    - 已 VERIFIED / FROZEN 的 cell 一律保留
    """
    target_branch_by_evidence = {
        evidence_id: branch_decision.branch_id
        for branch_decision in branch_decisions
        if branch_decision.decision == "COUNTERFACTUAL_RECHECK"
        and branch_decision.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
        for evidence_id in branch_decision.target_evidence_ids
        if evidence_id
    }
    generic_recovery = any(
        branch_decision.decision in {
            "EXPAND_SOURCE_SCOPE",
            "REOPEN_READ_WINDOWS",
            "REEXTRACT_EVIDENCE",
        }
        for branch_decision in branch_decisions
    )

    last_verifier_decision = ""
    for record in verifier_decisions:
        if record.decision_type:
            last_verifier_decision = record.decision_type
    if not last_verifier_decision and verifier_decisions:
        last_verifier_decision = verifier_decisions[-1].decision_type

    updated_cells: list[ResearchStateCell] = []
    for cell in cells:
        matched_target = next(
            (evidence_id for evidence_id in cell.evidence_refs if evidence_id in target_branch_by_evidence),
            "",
        )
        next_branch_id = target_branch_by_evidence.get(matched_target, cell.branch_id)
        next_status = cell.status
        next_repair_count = cell.repair_count
        if matched_target:
            next_status = "CONFLICTED"
            next_repair_count = cell.repair_count + 1
        elif generic_recovery:
            # 只对尚未 VERIFIED / FROZEN 的 cell 标记 need_more_evidence
            if cell.status not in {"VERIFIED", "FROZEN", "CONFLICTED"}:
                next_status = "NEED_MORE_EVIDENCE"
                next_repair_count = cell.repair_count + 1
        updated_cells.append(
            cell.model_copy(
                update={
                    "branch_id": next_branch_id,
                    "status": next_status,
                    "last_verifier_decision": last_verifier_decision or cell.last_verifier_decision,
                    "repair_count": next_repair_count,
                }
            )
        )
    return updated_cells


def _apply_branch_to_rows_targeted(
    rows: list[ResearchStateRow],
    branch_decisions: list[ResearchBranchDecision],
) -> list[ResearchStateRow]:
    """P0-2: 只针对冲突 evidence 的行做状态更新,不影响其他行。"""
    target_branch_by_evidence = {
        evidence_id: branch_decision.branch_id
        for branch_decision in branch_decisions
        if branch_decision.decision == "COUNTERFACTUAL_RECHECK"
        and branch_decision.branch_status in {"ACTIVE", "ACTIVE_BRANCH"}
        for evidence_id in branch_decision.target_evidence_ids
        if evidence_id
    }
    generic_recovery = any(
        branch_decision.decision in {
            "EXPAND_SOURCE_SCOPE",
            "REOPEN_READ_WINDOWS",
            "REEXTRACT_EVIDENCE",
        }
        for branch_decision in branch_decisions
    )

    updated_rows: list[ResearchStateRow] = []
    for row in rows:
        row_status = row.row_status
        matched_target = next(
            (evidence_id for evidence_id in row.conflicting_evidence_ids if evidence_id in target_branch_by_evidence),
            "",
        )
        next_branch_id = target_branch_by_evidence.get(matched_target, row.branch_id)
        if matched_target:
            if row_status == "VERIFIED":
                row_status = "CONFLICTED"
            elif row_status != "CONFLICTED":
                if row.conflicting_evidence_ids:
                    row_status = "CONFLICTED"
        elif generic_recovery:
            if row_status not in {"VERIFIED", "FROZEN"}:
                row_status = "NEED_MORE_EVIDENCE"
        updated_rows.append(
            row.model_copy(
                update={
                    "branch_id": next_branch_id,
                    "row_status": row_status,
                }
            )
        )
    return updated_rows


# ---------------------------------------------------------------------------
# 行 / cell 状态聚合 (P0-1 + P0-5:不伪造分,只用 evidence_cards 真实关系)
# ---------------------------------------------------------------------------


def _aggregate_row_status(cards: list[ResearchEvidenceCard]) -> str:
    if not cards:
        return "CANDIDATE_READY"
    supports = [c for c in cards if c.relation_type == "SUPPORTS"]
    conflicts = [c for c in cards if c.relation_type == "CONFLICTS"]
    if conflicts and supports:
        return "CONFLICTED"
    if conflicts and not supports:
        return "CONFLICTED"
    if supports:
        avg_support = sum(c.support_score for c in supports) / max(1, len(supports))
        if avg_support >= 0.7:
            return "VERIFIED"
        if avg_support >= 0.45:
            return "NEED_MORE_EVIDENCE"
    return "BLOCKED"


def _row_verification_status(row_status: str) -> str:
    if row_status == "VERIFIED":
        return "LOCAL_PASS"
    if row_status == "CONFLICTED":
        return "COUNTERFACTUAL_REQUIRED"
    return "LOCAL_WARN"


def _row_support_level(cards: list[ResearchEvidenceCard]) -> str:
    if not cards:
        return "UNSUPPORTED"
    if any(c.relation_type == "CONFLICTS" for c in cards):
        return "CONFLICTING"
    supports = [c for c in cards if c.relation_type == "SUPPORTS"]
    if supports:
        avg = sum(c.support_score for c in supports) / max(1, len(supports))
        if avg >= 0.7:
            return "SUPPORTED"
        if avg >= 0.45:
            return "WEAK_SUPPORT"
    return "UNSUPPORTED"


def _row_verifier_note(cards: list[ResearchEvidenceCard], row_status: str) -> str:
    if not cards:
        return "No evidence cards bound to this entity yet."
    if row_status == "VERIFIED":
        return "Entity evidence is supported; awaiting CellVerifier 4-way verdict."
    if row_status == "CONFLICTED":
        return "Conflicting evidence cards present; COUNTERFACTUAL_RECHECK recommended."
    if row_status == "NEED_MORE_EVIDENCE":
        return "Evidence cards are weakly supportive; needs additional grounding."
    return "Evidence insufficient; expansion required."


def _row_repair_hint(row_status: str) -> str:
    if row_status == "CONFLICTED":
        return "Run counterfactual branch before synthesis."
    if row_status == "NEED_MORE_EVIDENCE":
        return "Expand source scope or bind one more evidence card."
    if row_status == "BLOCKED":
        return "Open new read windows before synthesis."
    return ""


def _filter_cards_for_column(
    cards: list[ResearchEvidenceCard], column_key: str
) -> list[ResearchEvidenceCard]:
    """Bind evidence to a cell only through its persisted schema column."""
    return [card for card in cards if card.column_key == column_key]


def _column_tokens(column_key: str) -> list[str]:
    return [token for token in re.findall(r"[a-z0-9]+", column_key.lower()) if len(token) >= 2]


def _aggregate_cell_value(
    cards: list[ResearchEvidenceCard], column_key: str, entity: ResearchEntityRow
) -> str:
    """合并多张卡片为单一 cell value。

    优先级:SUPPORTS > WEAK_SUPPORT > CONFLICTS,只合并支持类证据;冲突另存为 conflict note。
    """
    if not cards:
        return ""
    supports = [c for c in cards if c.relation_type == "SUPPORTS"]
    if supports:
        # 取分数最高 + 长度足够的 claim_text
        top = max(supports, key=lambda c: c.support_score)
        return top.claim_text.strip()
    weak = [c for c in cards if c.relation_type == "WEAK_SUPPORT"]
    if weak:
        return weak[0].claim_text.strip()
    # 仅有冲突证据:返回 entity 名作为占位,verdict 走 CONTRADICTS
    return f"[conflict-only] {entity.display_name}"


def _cell_status_from_cards(
    cards: list[ResearchEvidenceCard], row_status: str
) -> str:
    if row_status == "VERIFIED":
        return "CANDIDATE_READY"  # 等 CellVerifier 输出 SUPPORTS
    if row_status == "CONFLICTED":
        return "CANDIDATE_READY"
    if not cards:
        return "EMPTY"
    return "FILLED"


def _cell_confidence(cards: list[ResearchEvidenceCard]) -> float:
    if not cards:
        return 0.0
    supports = [c for c in cards if c.relation_type == "SUPPORTS"]
    if supports:
        return sum(c.support_score for c in supports) / len(supports)
    return max((c.support_score for c in cards), default=0.0)


def _is_required_column(column_key: str, schema) -> bool:
    for col in schema.columns:
        if col.key == column_key:
            return col.required
    return False


def _derive_domain(url: str) -> str:
    if not url:
        return ""
    from urllib.parse import urlparse

    try:
        domain = urlparse(url).netloc.strip().lower()
    except ValueError:
        return ""
    if domain.startswith("www."):
        domain = domain[4:]
    return domain


def _row_status_summary(rows: list[ResearchStateRow]) -> dict[str, int]:
    summary: dict[str, int] = {}
    for row in rows:
        summary[row.row_status] = summary.get(row.row_status, 0) + 1
    return summary


def _branch_hypothesis(branch_decision: ResearchBranchDecision) -> str:
    if branch_decision.decision == "COUNTERFACTUAL_RECHECK":
        return "Validate whether the mainline conclusion still stands under conflicting evidence."
    if branch_decision.decision == "EXPAND_SOURCE_SCOPE":
        return "Search outside the current scope to recover missing evidence."
    if branch_decision.decision == "REOPEN_READ_WINDOWS":
        return "Re-open bounded reading windows to fill missing verification context."
    if branch_decision.decision == "REEXTRACT_EVIDENCE":
        return "Re-run evidence extraction with stricter grounding."
    return "Keep the result guarded until verifier concerns are resolved."


def _branch_verifier_decisions(
    branch_decisions: list[ResearchBranchDecision],
) -> list[ResearchVerifierDecision]:
    decisions: list[ResearchVerifierDecision] = []
    for branch_decision in branch_decisions:
        if branch_decision.decision == "NO_BRANCH":
            continue
        decisions.append(
            ResearchVerifierDecision(
                decision_scope="BRANCH",
                decision_type=branch_decision.decision,
                reason_code=branch_decision.branch_reason,
                target_id=branch_decision.branch_id,
                evidence_ids=list(branch_decision.target_evidence_ids),
                action="; ".join(branch_decision.recovery_actions[:2]),
                status="WARN",
                notes=[branch_decision.hypothesis_summary or _branch_hypothesis(branch_decision)],
            )
        )
    return decisions


def _dedupe_verifier_decisions(
    decisions: list[ResearchVerifierDecision],
) -> list[ResearchVerifierDecision]:
    deduped: list[ResearchVerifierDecision] = []
    seen: set[tuple[str, str, str, str]] = set()
    for decision in decisions:
        key = (
            decision.decision_scope,
            decision.decision_type,
            decision.reason_code,
            decision.target_id,
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(decision)
    return deduped
