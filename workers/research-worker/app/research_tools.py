from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import math
from typing import Callable

from app.branch import plan_branch_recovery
from app.extraction_result import ExtractionResult
from app.extractor import extract_evidence_cards_detailed
from app.fetch_adapters import run_research_fetch
from app.llm_client import LlmClient
from app.models import (
    ResearchFetchedDocument,
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchEvidenceCard,
    ResearchPlan,
    ResearchProgressEvent,
    ResearchReadWindow,
    ResearchSearchHit,
    ResearchStateLedger,
    ResearchStepTrace,
    ResearchTaskInput,
)
from app.read_adapters import run_research_read
from app.search_adapters import build_search_query_plan, run_research_search
from app.state import build_state_ledger, finalize_state_ledger
from app.verifier import run_global_verifier, run_local_verifier
from app.trace_security import sanitize_trace_payload
from app.evidence_horizon import plan_evidence_horizon
from app.cell_verifier import CellVerifier
from app.cell_task_planner import plan_cell_task_bundles
from app.config import load_settings
from app.merge_gate import MergeAuthority, MergeDecisionType, merge_candidate
from app.local_parallel_scheduler import execute_local_parallel_bundles
from app.sequential_candidate_executor import execute_sequential_bundle


@dataclass
class ResearchRoundArtifacts:
    search_hits: list[ResearchSearchHit]
    fetched_documents: list[ResearchFetchedDocument]
    read_windows: list[ResearchReadWindow]
    evidence_cards: list[ResearchEvidenceCard]
    ledger: ResearchStateLedger
    local_result: LocalVerifierResult
    branch_decisions: list[ResearchBranchDecision]
    global_result: GlobalVerifierResult
    tool_traces: list[ResearchStepTrace] = field(default_factory=list)
    evidence_horizon_decisions: list[dict[str, object]] = field(default_factory=list)
    # DR-202: 本轮结构化抽取出口（含拒绝原因计数），供 Cell 恢复策略决策使用。
    extraction_result: ExtractionResult | None = None


class ResearchToolbox:
    """Explicit internal research tools used by the closed-loop runtime."""

    def __init__(
        self,
        llm_client: LlmClient | None = None,
        progress_callback: Callable[[ResearchProgressEvent], None] | None = None,
        cancellation_checker: Callable[[], None] | None = None,
    ) -> None:
        self.llm_client = llm_client
        self.progress_callback = progress_callback
        self.cancellation_checker = cancellation_checker
        # P0-5: 持有 CellVerifier 实例,execute_round 中每轮调用一次
        self.cell_verifier = CellVerifier(llm_client=llm_client)

    def execute_round(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        round_no: int,
        prior_artifacts: ResearchRoundArtifacts | None = None,
    ) -> ResearchRoundArtifacts:
        self._check_cancelled()
        recovery_mode = _recovery_mode(plan)
        round_traces: list[ResearchStepTrace] = []

        search_started = _utc_now()
        search_hits, search_trace = self.search_web(
            task_input,
            plan,
            round_no=round_no,
            prior_artifacts=prior_artifacts,
            recovery_mode=recovery_mode,
        )
        search_trace = _observe_trace(
            search_trace,
            search_started,
            max((hit.provider_attempt_count for hit in search_hits), default=1),
        )
        mark_plan_phase(plan, "WIDE_DISCOVERY", "COMPLETE")
        round_traces.append(search_trace)
        self._emit_progress(
            phase="SEARCHING",
            progress_percent=28,
            message="research search adapter hits resolved",
            round_no=round_no,
            recovery_mode=recovery_mode,
            metrics={"search_hits": len(search_hits)},
            payload={"tool_trace": search_trace.model_dump(mode="json")},
        )

        fetch_started = _utc_now()
        fetched_documents, fetch_trace = self.fetch_sources(
            task_input,
            plan,
            search_hits,
            round_no=round_no,
            prior_artifacts=prior_artifacts,
            recovery_mode=recovery_mode,
        )
        fetch_trace = _observe_trace(
            fetch_trace,
            fetch_started,
            max((document.transport_attempt_count for document in fetched_documents), default=1),
        )
        self._check_cancelled()
        round_traces.append(fetch_trace)

        read_started = _utc_now()
        read_windows, read_trace = self.open_read_windows(
            task_input,
            plan,
            fetched_documents,
            round_no=round_no,
            prior_artifacts=prior_artifacts,
            recovery_mode=recovery_mode,
        )
        read_trace = _observe_trace(read_trace, read_started, 1)
        self._check_cancelled()
        round_traces.append(read_trace)
        self._emit_progress(
            phase="READING",
            progress_percent=48,
            message="bounded read windows opened",
            round_no=round_no,
            recovery_mode=recovery_mode,
            metrics={
                "search_hits": len(search_hits),
                "fetched_documents": len(fetched_documents),
                "read_windows": len(read_windows),
            },
            payload={"tool_trace": read_trace.model_dump(mode="json")},
        )

        extract_started = _utc_now()
        evidence_cards, extract_trace, extraction_result = self.extract_evidence(
            task_input,
            plan,
            read_windows,
            round_no=round_no,
            recovery_mode=recovery_mode,
        )
        extract_trace = _observe_trace(
            extract_trace,
            extract_started,
            _latest_llm_attempt_count(self.llm_client, "research.extract"),
        )
        self._check_cancelled()
        if prior_artifacts is not None and recovery_mode != "EXTRACT_AGAIN":
            evidence_cards = _merge_models(
                prior_artifacts.evidence_cards,
                evidence_cards,
                key=_evidence_card_merge_key,
            )
        round_traces.append(extract_trace)

        state_started = _utc_now()
        ledger, state_trace = self.update_state_ledger(
            task_input,
            plan,
            search_hits,
            read_windows,
            evidence_cards,
            round_no=round_no,
        )
        state_trace = _observe_trace(state_trace, state_started, 1)
        ledger = _freeze_entity_candidate_set(
            ledger,
            prior_artifacts.ledger if prior_artifacts is not None else None,
        )
        mark_plan_phase(plan, "ENTITY_FREEZE", "COMPLETE")
        if prior_artifacts is not None:
            ledger = _seed_cell_history(prior_artifacts.ledger, ledger)
        round_traces.append(state_trace)
        self._emit_progress(
            phase="EXTRACTING",
            progress_percent=68,
            message="evidence cards extracted",
            round_no=round_no,
            recovery_mode=recovery_mode,
            metrics={
                "read_windows": len(read_windows),
                "evidence_cards": len(evidence_cards),
                "ledger_rows": len(ledger.rows),
                "ledger_cells": len(ledger.cells),
            },
            payload={"tool_trace": extract_trace.model_dump(mode="json")},
        )

        # P0-5: Cell-level 4-way verdict — 每个 cell 经过独立验证后才算 SUPPORTS
        cell_started = _utc_now()
        ledger, cell_verdict_trace = self.verify_cells(
            ledger,
            evidence_cards,
            plan=plan,
            research_run_id=task_input.target_id,
            round_no=round_no,
        )
        cell_verdict_trace = _observe_trace(
            cell_verdict_trace,
            cell_started,
            _latest_llm_attempt_count(self.llm_client, "research.verify.cell"),
        )
        self._check_cancelled()
        round_traces.append(cell_verdict_trace)

        local_started = _utc_now()
        local_result, local_trace = self.verify_local(
            task_input,
            plan,
            ledger,
            search_hits,
            read_windows,
            evidence_cards,
            round_no=round_no,
        )
        local_trace = _observe_trace(
            local_trace,
            local_started,
            _latest_llm_attempt_count(self.llm_client, "research.verify.local"),
        )
        self._check_cancelled()
        round_traces.append(local_trace)

        branch_started = _utc_now()
        current_branch_decisions, branch_trace = self.open_counterfactual_branch(
            plan,
            search_hits,
            read_windows,
            evidence_cards,
            local_result,
            round_no=round_no,
            prior_decisions=(
                prior_artifacts.branch_decisions
                if prior_artifacts is not None
                else []
            ),
        )
        branch_trace = _observe_trace(branch_trace, branch_started, 1)
        round_traces.append(branch_trace)

        branch_decisions = _merge_branch_decisions(
            prior_artifacts.branch_decisions if prior_artifacts is not None else [],
            current_branch_decisions,
        )

        global_started = _utc_now()
        global_result, global_trace = self.verify_global(
            local_result,
            ledger,
            branch_decisions,
            evidence_cards,
            round_no=round_no,
        )
        global_trace = _observe_trace(global_trace, global_started, 1)
        self._check_cancelled()
        round_traces.append(global_trace)

        ledger = finalize_state_ledger(
            ledger,
            local_result,
            global_result,
            branch_decisions,
            round_no=round_no,
        )
        required_cells = [cell for cell in ledger.cells if cell.is_required]
        mark_plan_phase(
            plan,
            "DEEP_CELL_COMPLETION",
            "COMPLETE" if all(cell.status == "VERIFIED" for cell in required_cells) else "ACTIVE",
        )
        counterfactual_items = [
            item
            for item in branch_decisions
            if item.decision in {"COUNTERFACTUAL_RECHECK", "COUNTERFACTUAL_RESOLVED"}
        ]
        mark_plan_phase(
            plan,
            "COUNTERFACTUAL_RECOVERY",
            "ACTIVE"
            if any(item.branch_status in {"ACTIVE", "ACTIVE_BRANCH"} for item in counterfactual_items)
            else "COMPLETE"
            if counterfactual_items
            else "SKIPPED",
        )
        mark_plan_phase(plan, "GLOBAL_VERIFY", "COMPLETE")
        if prior_artifacts is not None:
            ledger = _merge_ledger_history(prior_artifacts.ledger, ledger)
            local_result = _merge_local_result(prior_artifacts.local_result, local_result)
            global_result = _merge_global_result(prior_artifacts.global_result, global_result)

        self._emit_progress(
            phase="VERIFYING",
            progress_percent=86,
            message="dual verifier completed",
            round_no=round_no,
            recovery_mode=recovery_mode,
            metrics={
                "ledger_rows": len(ledger.rows),
                "ledger_cells": len(ledger.cells),
                "evidence_cards": len(evidence_cards),
                "branch_count": len(branch_decisions),
                "local_status": local_result.status,
                "global_status": global_result.status,
            },
            payload={"tool_trace": global_trace.model_dump(mode="json")},
        )

        all_tool_traces = (
            list(prior_artifacts.tool_traces)
            if prior_artifacts is not None
            else []
        )
        all_tool_traces.extend(round_traces)
        all_tool_traces = [
            trace.model_copy(
                update={
                    "run_id": trace.run_id or task_input.target_id,
                    "branch_id": ledger.active_branch_id if trace.round_no == round_no else trace.branch_id,
                    "event_id": (
                        trace.event_id
                        if trace.event_id.startswith(f"{task_input.target_id}:")
                        else f"{task_input.target_id}:{trace.event_id or trace.trace_id}"
                    ),
                }
            )
            for trace in all_tool_traces
        ]
        horizon_decisions = plan_evidence_horizon(
            ledger,
            evidence_cards,
            round_no=round_no,
        )

        return ResearchRoundArtifacts(
            search_hits=search_hits,
            fetched_documents=fetched_documents,
            read_windows=read_windows,
            evidence_cards=evidence_cards,
            ledger=ledger,
            local_result=local_result,
            branch_decisions=branch_decisions,
            global_result=global_result,
            tool_traces=all_tool_traces,
            evidence_horizon_decisions=horizon_decisions,
            extraction_result=extraction_result,
        )

    def _check_cancelled(self) -> None:
        if self.cancellation_checker is not None:
            self.cancellation_checker()

    def _emit_progress(
        self,
        *,
        phase: str,
        progress_percent: int,
        message: str,
        round_no: int,
        recovery_mode: str,
        metrics: dict[str, int | str],
        payload: dict[str, object],
    ) -> None:
        if self.progress_callback is None:
            return
        event_payload = dict(payload)
        event_payload["round_no"] = round_no
        event_payload["recovery_mode"] = recovery_mode or "DEFAULT"
        self.progress_callback(
            ResearchProgressEvent(
                phase=phase,
                progress_percent=progress_percent,
                message=message,
                metrics={"round_no": round_no, **metrics},
                payload=event_payload,
            )
        )

    def search_web(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        *,
        round_no: int,
        prior_artifacts: ResearchRoundArtifacts | None,
        recovery_mode: str,
    ) -> tuple[list[ResearchSearchHit], ResearchStepTrace]:
        query_plan = build_search_query_plan(plan)
        # DR-202: RETRY_EXTRACTION(EXTRACT_AGAIN) 与 REREAD_WINDOW 都不得重复搜索；
        # REREAD_WINDOW 只重开窗口，因此复用已抓取的 hits。
        if recovery_mode in {"EXTRACT_AGAIN", "REREAD_WINDOW"} and prior_artifacts is not None:
            hits = list(prior_artifacts.search_hits)
            return hits, _build_tool_trace(
                round_no=round_no,
                tool_name="search_web",
                status="reused",
                message="search tool reused prior hits for extraction retry",
                inputs={
                    "recovery_mode": recovery_mode,
                    "query_count": len(plan.query_set),
                    "selected_query_count": len(query_plan),
                    "selected_query_samples": [item.query for item in query_plan[:4]],
                },
                outputs={
                    "hit_count": len(hits),
                    "reused_hit_count": len(hits),
                    "selected_query_count": len(query_plan),
                    "selected_query_family_summary": _count_values(item.family for item in query_plan),
                },
            )

        global_limit = max(0, int(plan.stop_contract.get("global_search_limit", 8)))
        prior_hit_count = len(prior_artifacts.search_hits) if prior_artifacts is not None else 0
        remaining_global_budget = max(0, global_limit - prior_hit_count)
        rounds_remaining = max(
            1,
            int(plan.stop_contract.get("max_loop_rounds", round_no)) - round_no + 1,
        )
        round_budget = min(
            remaining_global_budget,
            max(1, math.ceil(remaining_global_budget / rounds_remaining))
            if remaining_global_budget
            else 0,
        )
        current_hits = run_research_search(
            task_input,
            plan,
            remaining_budget=round_budget,
        )
        if recovery_mode in {"READ_MORE", "COUNTERFACTUAL_RECHECK", "TARGETED_SEARCH"} and prior_artifacts is not None:
            hits = _merge_models(
                prior_artifacts.search_hits,
                current_hits,
                key=_search_hit_merge_key,
            )
            trace_status = "merged"
            outputs = {
                "new_hit_count": len(current_hits),
                "merged_hit_count": len(hits),
            }
        else:
            hits = current_hits
            trace_status = "success"
            outputs = {"hit_count": len(hits)}
        hits = hits[:global_limit]
        outputs.update(
            {
                "provider_summary": _count_values(hit.provider for hit in hits),
                "adapter_summary": _count_values(hit.adapter for hit in hits),
                "provider_resolution_summary": _count_values(hit.provider_resolution for hit in hits),
                "provider_attempt_chain_summary": _count_values(
                    _chain_label(hit.provider_attempts)
                    for hit in hits
                ),
                "source_quality_summary": _count_values(hit.source_quality for hit in hits),
                "search_lane_summary": _count_values(hit.search_lane for hit in hits),
                "selected_query_count": len(query_plan),
                "selected_query_family_summary": _count_values(item.family for item in query_plan),
                "selected_query_lane_summary": _count_values(item.lane for item in query_plan),
                "global_search_limit": global_limit,
                "remaining_global_budget_before_round": remaining_global_budget,
                "round_search_budget": round_budget,
            }
        )
        return hits, _build_tool_trace(
            round_no=round_no,
            tool_name="search_web",
            status=trace_status,
            message="internal search tool executed",
            inputs={
                "recovery_mode": recovery_mode or "DEFAULT",
                "query_count": len(plan.query_set),
                "query_samples": plan.query_set[:3],
                "selected_query_samples": [item.query for item in query_plan[:4]],
            },
            outputs=outputs,
            warnings=["search returned no hits"] if not hits else [],
        )

    def fetch_sources(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        search_hits: list[ResearchSearchHit],
        *,
        round_no: int,
        prior_artifacts: ResearchRoundArtifacts | None,
        recovery_mode: str,
    ) -> tuple[list[ResearchFetchedDocument], ResearchStepTrace]:
        if recovery_mode in {"EXTRACT_AGAIN", "REREAD_WINDOW"} and prior_artifacts is not None and prior_artifacts.fetched_documents:
            documents = list(prior_artifacts.fetched_documents)
            return documents, _build_tool_trace(
                round_no=round_no,
                tool_name="fetch_sources",
                status="reused",
                message="fetch tool reused prior documents for extraction retry",
                inputs={"recovery_mode": recovery_mode, "search_hit_count": len(search_hits)},
                outputs={"fetch_document_count": len(documents), "reused_fetch_document_count": len(documents)},
            )

        current_documents = run_research_fetch(task_input, plan, search_hits)
        if recovery_mode in {"READ_MORE", "COUNTERFACTUAL_RECHECK", "TARGETED_SEARCH"} and prior_artifacts is not None:
            documents = _merge_models(
                prior_artifacts.fetched_documents,
                current_documents,
                key=_fetched_document_merge_key,
            )
            trace_status = "merged"
            outputs = {
                "new_fetch_document_count": len(current_documents),
                "merged_fetch_document_count": len(documents),
            }
        else:
            documents = current_documents
            trace_status = "success"
            outputs = {"fetch_document_count": len(documents)}
        outputs.update(
            {
                "fetch_status_summary": _count_values(document.fetch_status for document in documents),
                "fetch_method_summary": _count_values(document.fetch_method for document in documents),
                "transport_resolution_summary": _count_values(
                    document.transport_resolution for document in documents
                ),
                "transport_attempt_chain_summary": _count_values(
                    _chain_label(document.transport_chain)
                    for document in documents
                ),
                "content_origin_summary": _count_values(document.content_origin for document in documents),
                "source_quality_summary": _count_values(document.source_quality for document in documents),
            }
        )
        return documents, _build_tool_trace(
            round_no=round_no,
            tool_name="fetch_sources",
            status=trace_status,
            message="internal fetch tool executed",
            inputs={
                "recovery_mode": recovery_mode or "DEFAULT",
                "search_hit_count": len(search_hits),
                "retention_budget": int(plan.stop_contract.get("tool_response_retention_budget", 0)),
            },
            outputs=outputs,
            warnings=["fetch tool retained no documents"] if not documents else [],
        )

    def open_read_windows(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        fetched_documents: list[ResearchFetchedDocument],
        *,
        round_no: int,
        prior_artifacts: ResearchRoundArtifacts | None,
        recovery_mode: str,
    ) -> tuple[list[ResearchReadWindow], ResearchStepTrace]:
        if recovery_mode == "EXTRACT_AGAIN" and prior_artifacts is not None:
            windows = list(prior_artifacts.read_windows)
            return windows, _build_tool_trace(
                round_no=round_no,
                tool_name="open_read_windows",
                status="reused",
                message="read tool reused prior windows for extraction retry",
                inputs={"recovery_mode": recovery_mode, "fetch_document_count": len(fetched_documents)},
                outputs={"read_window_count": len(windows), "reused_window_count": len(windows)},
            )

        current_windows = run_research_read(
            task_input,
            plan,
            fetched_documents=fetched_documents,
        )
        if recovery_mode in {
            "READ_MORE",
            "COUNTERFACTUAL_RECHECK",
            "TARGETED_SEARCH",
            "REREAD_WINDOW",
        } and prior_artifacts is not None:
            windows = _merge_models(
                prior_artifacts.read_windows,
                current_windows,
                key=_read_window_merge_key,
            )
            trace_status = "merged"
            outputs = {
                "new_read_window_count": len(current_windows),
                "merged_read_window_count": len(windows),
            }
        else:
            windows = current_windows
            trace_status = "success"
            outputs = {"read_window_count": len(windows)}
        outputs.update(
            {
                "adapter_summary": _count_values(window.adapter for window in windows),
                "snapshot_summary": _count_values(window.snapshot_status for window in windows),
                "fetch_status_summary": _count_values(window.fetch_status for window in windows),
                "fetch_method_summary": _count_values(window.fetch_method for window in windows),
                "transport_resolution_summary": _count_values(
                    window.transport_resolution for window in windows
                ),
                "transport_attempt_chain_summary": _count_values(
                    _chain_label(window.transport_chain)
                    for window in windows
                ),
                "content_origin_summary": _count_values(window.content_origin for window in windows),
                "source_quality_summary": _count_values(window.source_quality for window in windows),
                "read_strategy_summary": _count_values(window.read_strategy for window in windows),
            }
        )
        return windows, _build_tool_trace(
            round_no=round_no,
            tool_name="open_read_windows",
            status=trace_status,
            message="internal read tool executed",
            inputs={
                "recovery_mode": recovery_mode or "DEFAULT",
                "fetch_document_count": len(fetched_documents),
            },
            outputs=outputs,
            warnings=["read tool retained no windows"] if not windows else [],
        )

    def extract_evidence(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        read_windows: list[ResearchReadWindow],
        *,
        round_no: int,
        recovery_mode: str,
    ) -> tuple[list[ResearchEvidenceCard], ResearchStepTrace, ExtractionResult]:
        # DR-202: 走结构化出口，随卡片一起返回拒绝原因，供 Cell 恢复策略决策。
        extraction_result = extract_evidence_cards_detailed(
            task_input,
            plan,
            read_windows,
            llm_client=self.llm_client,
        )
        evidence_cards = [
            card.model_copy(update={"discovery_round": round_no})
            for card in extraction_result.accepted_cards
        ]
        conflict_count = sum(1 for card in evidence_cards if card.relation_type == "CONFLICTS")
        support_count = sum(1 for card in evidence_cards if card.relation_type == "SUPPORTS")
        weak_support_count = sum(1 for card in evidence_cards if card.relation_type == "WEAK_SUPPORT")
        return evidence_cards, _build_tool_trace(
            round_no=round_no,
            tool_name="extract_evidence",
            status="success",
            message="internal evidence extraction tool executed",
            inputs={
                "recovery_mode": recovery_mode or "DEFAULT",
                "read_window_count": len(read_windows),
                "llm_enabled": self.llm_client is not None,
            },
            outputs={
                "evidence_card_count": len(evidence_cards),
                "conflict_card_count": conflict_count,
                "support_card_count": support_count,
                "weak_support_card_count": weak_support_count,
                "extraction_termination_reason": extraction_result.termination_reason.value,
                "extraction_rejected_count": extraction_result.rejected_count,
                "extraction_rejection_counts": extraction_result.rejection_counts(),
            },
            warnings=["evidence extraction produced no cards"] if not evidence_cards else [],
        ), extraction_result

    def update_state_ledger(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        search_hits: list[ResearchSearchHit],
        read_windows: list[ResearchReadWindow],
        evidence_cards: list[ResearchEvidenceCard],
        *,
        round_no: int,
    ) -> tuple[ResearchStateLedger, ResearchStepTrace]:
        ledger = build_state_ledger(task_input, plan, search_hits, read_windows, evidence_cards)
        pending_requirements = [
            item.label
            for item in ledger.required_finding_progress
            if item.status != "READY"
        ]
        return ledger, _build_tool_trace(
            round_no=round_no,
            tool_name="update_state_ledger",
            status="success",
            message="table-as-state ledger updated from tool outputs",
            inputs={
                "search_hit_count": len(search_hits),
                "read_window_count": len(read_windows),
                "evidence_card_count": len(evidence_cards),
            },
            outputs={
                "row_count": len(ledger.rows),
                "cell_count": len(ledger.cells),
                "entity_count": len(ledger.entities),
                "verified_row_count": ledger.verified_row_count,
                "conflicted_row_count": ledger.conflicted_row_count,
                "source_quality_summary": _count_values(row.source_quality for row in ledger.rows),
                "pending_requirement_count": len(pending_requirements),
                "pending_requirement_labels": pending_requirements[:4],
            },
        )

    def verify_cells(
        self,
        ledger: ResearchStateLedger,
        evidence_cards: list[ResearchEvidenceCard],
        *,
        plan=None,
        research_run_id: str = "",
        round_no: int,
    ) -> tuple[ResearchStateLedger, ResearchStepTrace]:
        """P0-5: 对每个 cell 调用 CellVerifier,产出 4-way verdict。

        cells 已与 evidence_cards 通过 entity_id 关联 (在 state.py build_state_ledger 中完成)。
        """
        from app.models import CellSupportStatus

        execution_mode = load_settings().research_agent_execution_mode
        if (
            plan is not None
            and research_run_id
            and execution_mode in {"SEQUENTIAL_V2", "LOCAL_PARALLEL"}
        ):
            return self._verify_cells_v2(
                ledger,
                evidence_cards,
                plan=plan,
                research_run_id=research_run_id,
                round_no=round_no,
            )

        # 按 cell_id 分组 evidence_cards
        cards_by_cell: dict[str, list[ResearchEvidenceCard]] = {}
        for card in evidence_cards:
            if not card.entity_id or not card.column_key:
                continue
            cards_by_cell.setdefault(
                f"{card.entity_id}:{card.column_key}", []
            ).append(card)

        entity_display_by_id = {entity.entity_id: entity.display_name for entity in ledger.entities}
        original_cells = {cell.cell_id: cell for cell in ledger.cells}
        updated_cells, verdicts = self.cell_verifier.verify_cells(
            ledger.cells, cards_by_cell, entity_display_by_id=entity_display_by_id
        )

        # 更新 verdict_round 与 status 联动 (P0-6: SUPPORTS -> VERIFIED / 其他保持 CANDIDATE_READY)
        new_cells = []
        for cell, verdict in zip(updated_cells, verdicts):
            original = original_cells[cell.cell_id]
            bound_cards = cards_by_cell.get(cell.cell_id, [])
            if original.status == "FROZEN":
                new_cells.append(original)
                continue
            if (
                original.status == "VERIFIED"
                and not any(card.relation_type == "CONFLICTS" for card in bound_cards)
            ):
                new_cells.append(original)
                continue
            new_status = cell.status
            if verdict.status == CellSupportStatus.SUPPORTS:
                new_status = "VERIFIED"
            elif verdict.status == CellSupportStatus.CONTRADICTS:
                new_status = "CONFLICTED"
            elif verdict.status == CellSupportStatus.PARTIALLY_SUPPORTS:
                new_status = "CANDIDATE_READY"
            elif verdict.status == CellSupportStatus.NOT_ENOUGH_INFO:
                # 如果 cell 已经有 evidence 但 verdict 还是 NEI,保持 FILLED,让 loop 触发 READ_MORE
                new_status = cell.status if cell.status in {"FILLED", "CANDIDATE_READY", "NEED_MORE_EVIDENCE"} else "EMPTY"
            new_cells.append(
                cell.model_copy(
                    update={
                        "verdict_round": round_no,
                        "status": new_status,
                        "last_verifier_decision": f"CELL_VERIFIER:{verdict.status.value}",
                    }
                )
            )

        # 更新 row 的 row_status (取该 entity 最严 verdict)
        cells_by_entity: dict[str, list] = {}
        for cell in new_cells:
            cells_by_entity.setdefault(cell.entity_id or cell.row_id, []).append(cell)
        new_rows = []
        for row in ledger.rows:
            entity_cells = cells_by_entity.get(row.entity_id or row.row_id, [])
            if not entity_cells:
                new_rows.append(row)
                continue
            statuses = {cell.status for cell in entity_cells}
            required_cells = [cell for cell in entity_cells if cell.is_required]
            if "CONFLICTED" in statuses:
                row_status = "CONFLICTED"
            elif required_cells and all(cell.status == "VERIFIED" for cell in required_cells):
                row_status = "VERIFIED"
            elif "NEED_MORE_EVIDENCE" in statuses:
                row_status = "NEED_MORE_EVIDENCE"
            else:
                row_status = "CANDIDATE_READY"
            new_rows.append(
                row.model_copy(
                    update={
                        "row_status": row_status,
                        "verification_status": "LOCAL_PASS" if row_status == "VERIFIED" else "LOCAL_WARN",
                    }
                )
            )

        # 汇总 cell_verdict_counts
        cell_verdict_counts: dict[str, int] = {}
        for v in verdicts:
            key = v.status.value
            cell_verdict_counts[key] = cell_verdict_counts.get(key, 0) + 1

        new_ledger = ledger.model_copy(
            update={
                "cells": new_cells,
                "rows": new_rows,
                "cell_verdicts": verdicts,
                "cell_verdict_counts": cell_verdict_counts,
                "verified_row_count": sum(1 for r in new_rows if r.row_status == "VERIFIED"),
                "conflicted_row_count": sum(1 for r in new_rows if r.row_status == "CONFLICTED"),
                "row_status_summary": _row_status_summary(new_rows),
            }
        )
        new_ledger = _recompute_requirement_progress_after_cell_verification(new_ledger)
        return new_ledger, _build_tool_trace(
            round_no=round_no,
            tool_name="verify_cells",
            status="success",
            message="4-way cell-level verifier executed",
            inputs={
                "cell_count": len(ledger.cells),
                "evidence_card_count": len(evidence_cards),
                "llm_enabled": self.llm_client is not None,
            },
            outputs={
                "verdict_counts": cell_verdict_counts,
                "verified_cell_count": cell_verdict_counts.get("SUPPORTS", 0),
                "conflicted_cell_count": cell_verdict_counts.get("CONTRADICTS", 0),
                "partially_supports_count": cell_verdict_counts.get("PARTIALLY_SUPPORTS", 0),
                "not_enough_info_count": cell_verdict_counts.get("NOT_ENOUGH_INFO", 0),
            },
        )

    def _verify_cells_v2(
        self,
        ledger: ResearchStateLedger,
        evidence_cards: list[ResearchEvidenceCard],
        *,
        plan,
        research_run_id: str,
        round_no: int,
    ) -> tuple[ResearchStateLedger, ResearchStepTrace]:
        """MA1 bridge: sequentially propose, verify, and merge existing cells.

        Search/read/extract remain the existing round-level pipeline.  This
        method only changes state ownership after extraction: candidates are
        immutable proposal objects and verified state is written via MA0's
        Merge Gate.
        """
        from app.models import CellSupportStatus

        bound_cells = [
            cell.model_copy(
                update={
                    "plan_revision": plan.plan_revision,
                    "entity_set_version": ledger.entity_set_version,
                }
            )
            for cell in ledger.cells
        ]
        bound_ledger = ledger.model_copy(update={"cells": bound_cells})
        cards_by_cell = _cards_by_cell(evidence_cards)
        entity_display_by_id = {entity.entity_id: entity.display_name for entity in bound_ledger.entities}
        verifier_cells, verdicts = self.cell_verifier.verify_cells(
            bound_ledger.cells,
            cards_by_cell,
            entity_display_by_id=entity_display_by_id,
        )
        settings = load_settings()
        execution_mode = settings.research_agent_execution_mode
        max_cells = settings.research_agent_bundle_max_cells
        bundles = plan_cell_task_bundles(
            research_run_id,
            bound_ledger,
            plan_revision=plan.plan_revision,
            max_cells_per_bundle=max_cells,
            round_no=round_no,
        )
        cells_by_id = {cell.cell_id: cell for cell in bound_ledger.cells}
        execution_summary = execute_local_parallel_bundles(
            bundles,
            cells_by_id,
            max_concurrency=(
                settings.research_agent_max_concurrency
                if execution_mode == "LOCAL_PARALLEL"
                else 1
            ),
            execute_bundle=execute_sequential_bundle,
        )
        executions = execution_summary.results
        candidate_by_cell = {
            candidate.cell_id: (bundle, execution, candidate)
            for bundle, execution in zip(bundles, executions)
            for candidate in execution.candidates
        }
        evidence_by_id = {card.evidence_id: card for card in evidence_cards}
        authority = MergeAuthority(
            plan_revision=plan.plan_revision,
            entity_set_version=bound_ledger.entity_set_version,
            lease_epoch=1,
            fencing_token=1,
        )
        original_cells = {cell.cell_id: cell for cell in bound_ledger.cells}
        merged_cells: list[ResearchStateCell] = []
        merge_accepted_count = 0
        merge_rejected_count = 0
        for verifier_cell, verdict in zip(verifier_cells, verdicts):
            original = original_cells[verifier_cell.cell_id]
            bound_cards = cards_by_cell.get(verifier_cell.cell_id, [])
            if original.status == "FROZEN":
                merged_cells.append(original)
                continue
            if original.status == "VERIFIED" and not any(card.relation_type == "CONFLICTS" for card in bound_cards):
                merged_cells.append(original)
                continue
            proposal = candidate_by_cell.get(verifier_cell.cell_id)
            if verdict.status == CellSupportStatus.SUPPORTS and proposal is not None:
                _bundle, execution, candidate = proposal
                outcome = merge_candidate(
                    cell=original,
                    task=_bundle,
                    candidate=candidate,
                    verdict=verdict,
                    evidence_by_id=evidence_by_id,
                    authority=authority,
                    merge_id=f"{execution.execution_id}:{candidate.candidate_id}",
                )
                if outcome.decision in {MergeDecisionType.ACCEPTED, MergeDecisionType.IDEMPOTENT_REPLAY}:
                    merge_accepted_count += 1
                    merged_cells.append(outcome.cell.model_copy(update={"verdict_round": round_no}))
                    continue
                merge_rejected_count += 1
                merged_cells.append(
                    original.model_copy(
                        update={
                            "verdict": verdict.status,
                            "verdict_reason": verdict.reason,
                            "verdict_confidence": verdict.confidence,
                            "verdict_used_llm": verdict.used_llm,
                            "verdict_round": round_no,
                            "last_verifier_decision": f"MERGE_GATE:{outcome.reason_code.value}",
                        }
                    )
                )
                continue
            merged_cells.append(_apply_legacy_verdict_status(original, verifier_cell, verdict, round_no))

        new_rows = _rows_from_cell_statuses(bound_ledger.rows, merged_cells)
        cell_verdict_counts = _count_values(verdict.status.value for verdict in verdicts)
        new_ledger = bound_ledger.model_copy(
            update={
                "cells": merged_cells,
                "rows": new_rows,
                "cell_verdicts": verdicts,
                "cell_verdict_counts": cell_verdict_counts,
                "verified_row_count": sum(1 for row in new_rows if row.row_status == "VERIFIED"),
                "conflicted_row_count": sum(1 for row in new_rows if row.row_status == "CONFLICTED"),
                "row_status_summary": _row_status_summary(new_rows),
            }
        )
        new_ledger = _recompute_requirement_progress_after_cell_verification(new_ledger)
        return new_ledger, _build_tool_trace(
            round_no=round_no,
            tool_name="verify_cells",
            status="success",
            message="MA1 sequential task bundle candidate verification and merge executed",
            inputs={
                "cell_count": len(bound_ledger.cells),
                "evidence_card_count": len(evidence_cards),
                "execution_mode": execution_mode,
            },
            outputs={
                "execution_mode": execution_mode,
                "task_bundle_count": len(bundles),
                "candidate_execution_count": len(executions),
                "candidate_count": sum(len(item.candidates) for item in executions),
                "configured_max_concurrency": execution_summary.configured_max_concurrency,
                "effective_max_concurrency": execution_summary.effective_max_concurrency,
                "observed_max_in_flight": execution_summary.observed_max_in_flight,
                "execution_failure_count": execution_summary.execution_failure_count,
                "execution_cancelled_count": execution_summary.execution_cancelled_count,
                "merge_accepted_count": merge_accepted_count,
                "merge_rejected_count": merge_rejected_count,
                "verdict_counts": cell_verdict_counts,
            },
        )

    def verify_local(
        self,
        task_input: ResearchTaskInput,
        plan: ResearchPlan,
        ledger: ResearchStateLedger,
        search_hits: list[ResearchSearchHit],
        read_windows: list[ResearchReadWindow],
        evidence_cards: list[ResearchEvidenceCard],
        *,
        round_no: int,
    ) -> tuple[LocalVerifierResult, ResearchStepTrace]:
        local_result = run_local_verifier(
            task_input,
            plan,
            ledger,
            search_hits,
            read_windows,
            evidence_cards,
            llm_client=self.llm_client,
        )
        return local_result, _build_tool_trace(
            round_no=round_no,
            tool_name="verify_local",
            status="success",
            message="local verifier executed against current table-as-state",
            inputs={
                "row_count": len(ledger.rows),
                "verified_row_count": ledger.verified_row_count,
                "conflicted_row_count": ledger.conflicted_row_count,
            },
            outputs={
                "status": local_result.status,
                "warning_count": len(local_result.warnings),
                "recovery_action_count": len(local_result.recovery_actions),
                "intent_contract_status": local_result.intent_completion_contract.status,
                "intent_alignment_status": local_result.research_intent_alignment.status,
            },
            warnings=list(local_result.warnings[:4]),
        )

    def open_counterfactual_branch(
        self,
        plan: ResearchPlan,
        search_hits: list[ResearchSearchHit],
        read_windows: list[ResearchReadWindow],
        evidence_cards: list[ResearchEvidenceCard],
        local_result: LocalVerifierResult,
        *,
        round_no: int,
        prior_decisions: list[ResearchBranchDecision],
    ) -> tuple[list[ResearchBranchDecision], ResearchStepTrace]:
        branch_decisions = plan_branch_recovery(
            search_hits,
            read_windows,
            evidence_cards,
            local_result,
            recovery_mode=_recovery_mode(plan),
            round_no=round_no,
            prior_decisions=prior_decisions,
            branch_budget=max(0, int(plan.stop_contract.get("branch_budget", 1))),
        )
        return branch_decisions, _build_tool_trace(
            round_no=round_no,
            tool_name="open_counterfactual_branch",
            status="success",
            message="branch recovery tool evaluated the current verifier state",
            inputs={
                "search_hit_count": len(search_hits),
                "read_window_count": len(read_windows),
                "evidence_card_count": len(evidence_cards),
                "local_warning_count": len(local_result.warnings),
            },
            outputs={
                "branch_decision_count": len(branch_decisions),
                "branch_session_count": len({item.session_id for item in branch_decisions}),
                "branch_decisions": [item.decision for item in branch_decisions],
                "branch_reasons": [item.branch_reason for item in branch_decisions],
                "branch_session_ids": [item.session_id for item in branch_decisions],
            },
        )

    def verify_global(
        self,
        local_result: LocalVerifierResult,
        ledger: ResearchStateLedger,
        branch_decisions: list[ResearchBranchDecision],
        evidence_cards: list[ResearchEvidenceCard] | None = None,
        *,
        round_no: int,
    ) -> tuple[GlobalVerifierResult, ResearchStepTrace]:
        global_result = run_global_verifier(local_result, ledger, branch_decisions, evidence_cards)
        return global_result, _build_tool_trace(
            round_no=round_no,
            tool_name="verify_global",
            status="success",
            message="global verifier decided whether the round is ready to write",
            inputs={
                "local_status": local_result.status,
                "branch_decision_count": len(branch_decisions),
                "conflicted_row_count": ledger.conflicted_row_count,
            },
            outputs={
                "status": global_result.status,
                "decision": global_result.decision,
                "completion_score": global_result.completion_score,
                "counterfactual_check_count": len(global_result.counterfactual_checks),
            },
            warnings=list(global_result.recovery_actions[:4]),
        )


def _build_tool_trace(
    *,
    round_no: int,
    tool_name: str,
    status: str,
    message: str,
    inputs: dict[str, object] | None = None,
    outputs: dict[str, object] | None = None,
    warnings: list[str] | None = None,
) -> ResearchStepTrace:
    phase = f"TOOL_{tool_name.upper()}"
    timestamp = datetime.now(timezone.utc).isoformat()
    return ResearchStepTrace(
        trace_id=f"tool-{tool_name}-round-{round_no}",
        event_id=f"tool-{tool_name}-round-{round_no}",
        round_no=round_no,
        tool=tool_name,
        started_at=timestamp,
        ended_at=timestamp,
        phase=phase,
        status=status,
        message=message,
        inputs=_sanitize_payload(inputs or {}),
        outputs=_sanitize_payload(outputs or {}),
        warnings=warnings or [],
    )


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _observe_trace(
    trace: ResearchStepTrace,
    started_at: str,
    attempt: int,
) -> ResearchStepTrace:
    ended_at = _utc_now()
    try:
        duration_ms = round(
            (datetime.fromisoformat(ended_at) - datetime.fromisoformat(started_at)).total_seconds() * 1000,
            3,
        )
    except ValueError:
        duration_ms = 0.0
    return trace.model_copy(update={
        "started_at": started_at,
        "ended_at": ended_at,
        "attempt": max(1, attempt),
        "outputs": {**trace.outputs, "duration_ms": duration_ms},
    })


def _latest_llm_attempt_count(llm_client: LlmClient | None, purpose: str) -> int:
    records = getattr(llm_client, "call_records", []) if llm_client is not None else []
    for record in reversed(records):
        if str(record.get("purpose") or "") == purpose:
            return max(1, int(record.get("attempt_count") or 1))
    return 1


def _sanitize_payload(payload: dict[str, object]) -> dict[str, object]:
    return sanitize_trace_payload(payload)


def _recovery_mode(plan: ResearchPlan) -> str:
    return str(plan.stop_contract.get("recovery_mode", "")).strip().upper()


def _count_values(values) -> dict[str, int]:
    summary: dict[str, int] = {}
    for raw_value in values:
        value = str(raw_value or "").strip() or "UNKNOWN"
        summary[value] = summary.get(value, 0) + 1
    return summary


def _cards_by_cell(
    evidence_cards: list[ResearchEvidenceCard],
) -> dict[str, list[ResearchEvidenceCard]]:
    """Index only evidence explicitly bound to one canonical cell key."""
    mapping: dict[str, list[ResearchEvidenceCard]] = {}
    for card in evidence_cards:
        if not card.entity_id or not card.column_key:
            continue
        mapping.setdefault(f"{card.entity_id}:{card.column_key}", []).append(card)
    return mapping


def _apply_legacy_verdict_status(original, verifier_cell, verdict, round_no: int):
    """Project a non-merged verdict with the existing V1 cell-status rules.

    MA1 must retain V1's conservative handling whenever there is no submitted
    candidate to merge (for example NOT_ENOUGH_INFO or a stale proposal).  The
    verifier output is preserved, but it does not get authority to increment a
    cell version or manufacture a merge audit record.
    """
    from app.models import CellSupportStatus

    if original.status == "FROZEN":
        return original
    if verdict.status == CellSupportStatus.SUPPORTS:
        status = "VERIFIED"
    elif verdict.status == CellSupportStatus.CONTRADICTS:
        status = "CONFLICTED"
    elif verdict.status == CellSupportStatus.PARTIALLY_SUPPORTS:
        status = "CANDIDATE_READY"
    else:
        status = (
            verifier_cell.status
            if verifier_cell.status in {"FILLED", "CANDIDATE_READY", "NEED_MORE_EVIDENCE"}
            else "EMPTY"
        )
    return verifier_cell.model_copy(
        update={
            "status": status,
            "verdict_round": round_no,
            "last_verifier_decision": f"CELL_VERIFIER:{verdict.status.value}",
            "version": original.version,
            "last_merge_id": original.last_merge_id,
        }
    )


def _rows_from_cell_statuses(rows, cells):
    """Derive row state from final cell state without mutating ledger input."""
    cells_by_entity: dict[str, list] = {}
    for cell in cells:
        cells_by_entity.setdefault(cell.entity_id or cell.row_id, []).append(cell)

    updated_rows = []
    for row in rows:
        entity_cells = cells_by_entity.get(row.entity_id or row.row_id, [])
        if not entity_cells:
            updated_rows.append(row)
            continue
        statuses = {cell.status for cell in entity_cells}
        required_cells = [cell for cell in entity_cells if cell.is_required]
        if "CONFLICTED" in statuses:
            row_status = "CONFLICTED"
        elif required_cells and all(cell.status == "VERIFIED" for cell in required_cells):
            row_status = "VERIFIED"
        elif "NEED_MORE_EVIDENCE" in statuses:
            row_status = "NEED_MORE_EVIDENCE"
        else:
            row_status = "CANDIDATE_READY"
        updated_rows.append(
            row.model_copy(
                update={
                    "row_status": row_status,
                    "verification_status": "LOCAL_PASS" if row_status == "VERIFIED" else "LOCAL_WARN",
                }
            )
        )
    return updated_rows


def _chain_label(values: list[str]) -> str:
    cleaned = [str(item).strip() for item in values if str(item).strip()]
    return " -> ".join(cleaned) if cleaned else "UNKNOWN"


def _merge_ledger_history(
    prior_ledger: ResearchStateLedger,
    current_ledger: ResearchStateLedger,
) -> ResearchStateLedger:
    merged_branch_sessions = _merge_models(
        prior_ledger.branch_sessions,
        current_ledger.branch_sessions,
        key=lambda item: item.session_id,
    )
    merged_branches = _merge_models(
        prior_ledger.branches,
        current_ledger.branches,
        key=lambda item: item.branch_id,
    )
    merged_verifier_decisions = _merge_models(
        prior_ledger.verifier_decisions,
        current_ledger.verifier_decisions,
        key=lambda item: (
            item.decision_scope,
            item.decision_type,
            item.reason_code,
            item.target_id,
        ),
    )
    prior_cells = {cell.cell_id: cell for cell in prior_ledger.cells}
    merged_cells = []
    for cell in current_ledger.cells:
        prior = prior_cells.get(cell.cell_id)
        if prior is None:
            merged_cells.append(cell)
            continue
        update = {
            "repair_count": max(prior.repair_count, cell.repair_count),
            "evidence_refs": list(dict.fromkeys([*prior.evidence_refs, *cell.evidence_refs])),
            "version": max(prior.version, cell.version),
            "plan_revision": max(prior.plan_revision, cell.plan_revision),
            "entity_set_version": max(prior.entity_set_version, cell.entity_set_version),
            "last_merge_id": cell.last_merge_id or prior.last_merge_id,
        }
        if prior.status == "FROZEN":
            update.update(
                status="FROZEN",
                verdict=prior.verdict,
                verdict_reason=prior.verdict_reason,
                verdict_confidence=prior.verdict_confidence,
                verdict_round=prior.verdict_round,
                verdict_used_llm=prior.verdict_used_llm,
            )
        elif prior.status == "VERIFIED" and cell.status not in {"CONFLICTED"}:
            update.update(
                status="VERIFIED",
                verdict=prior.verdict,
                verdict_reason=prior.verdict_reason,
                verdict_confidence=max(prior.verdict_confidence, cell.verdict_confidence),
                verdict_round=max(prior.verdict_round, cell.verdict_round),
                verdict_used_llm=prior.verdict_used_llm or cell.verdict_used_llm,
            )
        merged_cells.append(cell.model_copy(update=update))
    return current_ledger.model_copy(
        update={
            "branch_sessions": merged_branch_sessions,
            "branches": merged_branches,
            "verifier_decisions": merged_verifier_decisions,
            "cells": merged_cells,
            "entity_set_status": "FROZEN",
            "entity_set_version": max(1, prior_ledger.entity_set_version, current_ledger.entity_set_version),
            "frozen_entity_ids": list(prior_ledger.frozen_entity_ids or current_ledger.frozen_entity_ids),
            "rejected_candidate_entity_ids": list(dict.fromkeys([
                *prior_ledger.rejected_candidate_entity_ids,
                *current_ledger.rejected_candidate_entity_ids,
            ])),
            "unresolved_questions": list(current_ledger.unresolved_questions),
        }
    )


def _recompute_requirement_progress_after_cell_verification(
    ledger: ResearchStateLedger,
) -> ResearchStateLedger:
    """Make requirement readiness a consequence of final CellVerifier verdicts."""
    if not ledger.required_finding_progress:
        return ledger
    rows_by_id = {row.row_id: row for row in ledger.rows}
    cells_by_row: dict[str, list] = {}
    for cell in ledger.cells:
        cells_by_row.setdefault(cell.row_id, []).append(cell)

    progress_items = []
    ready_rows: set[str] = set()
    partial_rows: set[str] = set()
    for progress in ledger.required_finding_progress:
        matched = [row_id for row_id in progress.matched_row_ids if row_id in rows_by_id]
        ready: list[str] = []
        partial: list[str] = []
        for row_id in matched:
            row = rows_by_id[row_id]
            required_columns = set(progress.required_columns)
            verified_columns = {
                cell.column_key
                for cell in cells_by_row.get(row_id, [])
                if cell.status == "VERIFIED" and cell.candidate_value.strip()
            }
            is_ready = (
                row.row_status in set(progress.accepted_row_statuses or ["VERIFIED"])
                and required_columns.issubset(verified_columns)
            )
            if is_ready:
                ready.append(row_id)
                ready_rows.add(row_id)
            else:
                partial.append(row_id)
                partial_rows.add(row_id)
        status = "READY" if len(ready) >= progress.target_row_count else "PARTIAL" if matched else "MISSING"
        progress_items.append(progress.model_copy(update={
            "matched_row_ids": matched,
            "ready_row_ids": ready,
            "partial_row_ids": partial,
            "status": status,
        }))

    ready_by_row: dict[str, list[str]] = {}
    for progress in progress_items:
        for row_id in progress.ready_row_ids:
            ready_by_row.setdefault(row_id, []).append(progress.requirement_id)
    rows = [
        row.model_copy(update={
            "ready_requirement_ids": ready_by_row.get(row.row_id, []),
            "requirement_completion_status": (
                "READY"
                if row.matched_requirement_ids
                and set(row.matched_requirement_ids).issubset(set(ready_by_row.get(row.row_id, [])))
                else "PARTIAL" if row.matched_requirement_ids else "NOT_REQUIRED"
            ),
        })
        for row in ledger.rows
    ]
    ready_requirements = {
        (row_id, progress.requirement_id)
        for progress in progress_items
        for row_id in progress.ready_row_ids
    }
    cells = [
        cell.model_copy(update={
            "satisfied_requirement_ids": [
                requirement_id
                for requirement_id in cell.required_by_requirement_ids
                if (cell.row_id, requirement_id) in ready_requirements
            ],
            "requirement_completion_status": (
                "READY"
                if cell.required_by_requirement_ids
                and all((cell.row_id, requirement_id) in ready_requirements for requirement_id in cell.required_by_requirement_ids)
                else "MISSING" if cell.required_by_requirement_ids else "NOT_REQUIRED"
            ),
        })
        for cell in ledger.cells
    ]
    return ledger.model_copy(update={
        "rows": rows,
        "cells": cells,
        "required_finding_progress": progress_items,
        "requirement_ready_row_count": len(ready_rows),
        "requirement_partial_row_count": len(partial_rows - ready_rows),
    })


def _freeze_entity_candidate_set(
    current_ledger: ResearchStateLedger,
    prior_ledger: ResearchStateLedger | None,
) -> ResearchStateLedger:
    """Freeze row identity after wide discovery while still allowing new evidence for known rows."""
    if prior_ledger is None or not prior_ledger.entities:
        frozen_ids = [entity.entity_id for entity in current_ledger.entities]
        return current_ledger.model_copy(
            update={
                "entity_set_status": "FROZEN",
                "entity_set_version": 1,
                "frozen_entity_ids": frozen_ids,
            }
        )

    allowed_ids = set(
        prior_ledger.frozen_entity_ids
        or [entity.entity_id for entity in prior_ledger.entities]
    )
    rejected_ids = [
        entity.entity_id
        for entity in current_ledger.entities
        if entity.entity_id not in allowed_ids
    ]
    kept_rows = [row for row in current_ledger.rows if (row.entity_id or row.row_id) in allowed_ids]
    kept_cells = [cell for cell in current_ledger.cells if (cell.entity_id or cell.row_id) in allowed_ids]
    kept_row_ids = {row.row_id for row in kept_rows}
    progress = [
        item.model_copy(
            update={
                "matched_row_ids": [row_id for row_id in item.matched_row_ids if row_id in kept_row_ids],
                "ready_row_ids": [row_id for row_id in item.ready_row_ids if row_id in kept_row_ids],
                "partial_row_ids": [row_id for row_id in item.partial_row_ids if row_id in kept_row_ids],
                "status": (
                    "READY"
                    if len([row_id for row_id in item.ready_row_ids if row_id in kept_row_ids]) >= item.target_row_count
                    else "PARTIAL"
                    if any(row_id in kept_row_ids for row_id in item.matched_row_ids)
                    else "MISSING"
                ),
            }
        )
        for item in current_ledger.required_finding_progress
    ]
    row_status_summary = _count_values(row.row_status for row in kept_rows)
    return current_ledger.model_copy(
        update={
            "entities": [entity for entity in current_ledger.entities if entity.entity_id in allowed_ids],
            "rows": kept_rows,
            "cells": kept_cells,
            "cell_verdicts": [
                verdict
                for verdict in current_ledger.cell_verdicts
                if verdict.cell_id.rsplit(":", 1)[0] in allowed_ids
            ],
            "entity_set_status": "FROZEN",
            "entity_set_version": max(1, prior_ledger.entity_set_version),
            "frozen_entity_ids": list(allowed_ids),
            "required_finding_progress": progress,
            "requirement_ready_row_count": sum(bool(item.ready_row_ids) for item in progress),
            "requirement_partial_row_count": sum(bool(item.partial_row_ids) for item in progress),
            "row_status_summary": row_status_summary,
            "verified_row_count": row_status_summary.get("VERIFIED", 0),
            "conflicted_row_count": row_status_summary.get("CONFLICTED", 0),
            "rejected_candidate_entity_ids": list(dict.fromkeys([
                *prior_ledger.rejected_candidate_entity_ids,
                *rejected_ids,
            ])),
        }
    )


def mark_plan_phase(plan: ResearchPlan, phase: str, status: str) -> None:
    updated: list[dict[str, object]] = []
    for item in plan.plan_horizon:
        if str(item.get("phase", "")) == phase:
            updated.append({**item, "status": status})
        else:
            updated.append(item)
    plan.plan_horizon = updated


def _seed_cell_history(
    prior_ledger: ResearchStateLedger,
    current_ledger: ResearchStateLedger,
) -> ResearchStateLedger:
    prior_cells = {cell.cell_id: cell for cell in prior_ledger.cells}
    seeded = []
    for cell in current_ledger.cells:
        prior = prior_cells.get(cell.cell_id)
        if prior is None:
            seeded.append(cell)
            continue
        update = {
            "repair_count": prior.repair_count,
            "verdict_round": prior.verdict_round,
            "version": prior.version,
            "plan_revision": prior.plan_revision,
            "entity_set_version": prior.entity_set_version,
            "last_merge_id": prior.last_merge_id,
        }
        if prior.status in {"VERIFIED", "FROZEN"} and cell.status != "CONFLICTED":
            update.update(
                status=prior.status,
                verdict=prior.verdict,
                verdict_reason=prior.verdict_reason,
                verdict_confidence=prior.verdict_confidence,
                verdict_used_llm=prior.verdict_used_llm,
            )
        seeded.append(cell.model_copy(update=update))
    return current_ledger.model_copy(update={"cells": seeded})


def _merge_local_result(
    prior_result: LocalVerifierResult,
    current_result: LocalVerifierResult,
) -> LocalVerifierResult:
    return current_result.model_copy(
        update={
            "status": current_result.status,
            "passed_checks": list(
                dict.fromkeys(
                    list(prior_result.passed_checks)
                    + list(current_result.passed_checks)
                )
            ),
            "warnings": list(current_result.warnings),
            "recovery_actions": list(current_result.recovery_actions),
            "decision_records": _merge_models(
                prior_result.decision_records,
                current_result.decision_records,
                key=lambda item: (
                    item.decision_scope,
                    item.decision_type,
                    item.reason_code,
                    item.target_id,
                ),
            ),
        }
    )


def _merge_global_result(
    prior_result: GlobalVerifierResult,
    current_result: GlobalVerifierResult,
) -> GlobalVerifierResult:
    return current_result.model_copy(
        update={
            "status": current_result.status,
            "counterfactual_checks": list(
                dict.fromkeys(
                    list(prior_result.counterfactual_checks)
                    + list(current_result.counterfactual_checks)
                )
            ),
            "recovery_actions": list(current_result.recovery_actions),
            "completion_score": current_result.completion_score,
            "decision_records": _merge_models(
                prior_result.decision_records,
                current_result.decision_records,
                key=lambda item: (
                    item.decision_scope,
                    item.decision_type,
                    item.reason_code,
                    item.target_id,
                ),
            ),
        }
    )


def _merge_models(existing_items: list, new_items: list, key):
    merged = {key(item): item for item in existing_items}
    for item in new_items:
        merged[key(item)] = item
    return list(merged.values())


def _resolve_entity_id_for_card(card: ResearchEvidenceCard) -> str:
    return card.entity_id


def _row_status_summary(rows) -> dict[str, int]:
    summary: dict[str, int] = {}
    for row in rows:
        summary[row.row_status] = summary.get(row.row_status, 0) + 1
    return summary


def _merge_branch_decisions(
    existing_items: list[ResearchBranchDecision],
    new_items: list[ResearchBranchDecision],
) -> list[ResearchBranchDecision]:
    merged: dict[str, ResearchBranchDecision] = {
        item.branch_id: item
        for item in existing_items
    }
    for item in new_items:
        merged[item.branch_id] = item
    return list(merged.values())


def _search_hit_merge_key(hit: ResearchSearchHit) -> tuple[str, str, str]:
    return (
        hit.source_id.strip().lower(),
        hit.url.strip().lower(),
        hit.query.strip().lower(),
    )


def _read_window_merge_key(window: ResearchReadWindow) -> tuple[str, str, str, str]:
    return (
        window.hit_id.strip().lower(),
        window.source_id.strip().lower(),
        window.query.strip().lower(),
        window.read_focus.strip().lower(),
    )


def _fetched_document_merge_key(document: ResearchFetchedDocument) -> tuple[str, str, str]:
    return (
        document.hit_id.strip().lower(),
        document.source_id.strip().lower(),
        document.query.strip().lower(),
    )


def _evidence_card_merge_key(card: ResearchEvidenceCard) -> tuple[str, str, str, str, str, float]:
    # P0-3: 加入 relation_type + support_score bucket,确保 SUPPORTS 与 CONFLICTS 卡片不被合并丢弃
    return (
        card.window_id.strip().lower(),
        card.source_id.strip().lower(),
        card.claim_text.strip().lower(),
        card.quote_text.strip().lower(),
        card.relation_type.strip().upper(),
        round(float(card.support_score or 0.0), 1),
    )
