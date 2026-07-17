from __future__ import annotations

from datetime import datetime, timezone

from app.config import load_settings
from app.harness_regression import build_harness_regression_suite_summary
from app.models import (
    GlobalVerifierResult,
    LocalVerifierResult,
    ResearchBranchDecision,
    ResearchHarnessAuditSummaryState,
    ResearchHarnessBranchRecoveryState,
    ResearchHarnessCheckpointSummaryState,
    ResearchHarnessControlState,
    ResearchHarnessEvidenceCoverageState,
    ResearchHarnessEvidenceCoverageSummaryState,
    ResearchHarnessLoopState,
    ResearchHarnessResumeState,
    ResearchHarnessResumeSummaryState,
    ResearchHarnessCounterfactualSummaryState,
    ResearchHarnessVerifierGateState,
    ResearchHarnessVerifierLatestDecision,
    ResearchFetchedDocument,
    ResearchIntentAlignment,
    ResearchIntentCompletionContract,
    ResearchLoopDecision,
    ResearchPlan,
    ResumeCheckpointPayload,
    ResearchReadWindow,
    ResearchStateLedger,
    ResearchStepTrace,
    ResearchTaskInput,
    ResearchSearchHit,
    ResearchToolboxFetchSummary,
    ResearchToolboxFetchItem,
    ResearchToolboxReadItem,
    ResearchToolboxReadSummary,
    ResearchToolboxSearchItem,
    ResearchToolboxSearchSummary,
    ResearchToolboxSearchTopHit,
    ResearchToolboxSummary,
)
from app.trace_security import sanitize_trace_payload
from app.search_adapters import build_search_query_plan
from app.planner import build_research_plan
from app.verifier_gate_policy import build_verifier_gate_policy_state


class ResearchHarness:
    def __init__(self, mode: str, llm_enabled: bool) -> None:
        self.mode = mode
        self.llm_enabled = llm_enabled

    def plan(
        self,
        task_input: ResearchTaskInput,
    ) -> tuple[ResearchPlan, list[ResearchStepTrace]]:
        plan = build_research_plan(task_input)
        return plan, [
            build_step_trace(
                phase="PLANNING",
                message="research plan compiled by rule-mode harness",
                inputs={
                    "source_count": len(task_input.source_scope),
                    "profile_key": task_input.input_payload.profile_key,
                    "harness_mode": self.mode,
                    "resume_checkpoint": (
                        ""
                        if task_input.input_payload.resume_checkpoint is None
                        else (
                            f"{task_input.input_payload.resume_checkpoint.source_research_run_id}"
                            f"#{task_input.input_payload.resume_checkpoint.checkpoint_no}"
                        )
                    ),
                },
                outputs={
                    "query_count": len(plan.query_set),
                    "report_sections": len(plan.report_sections),
                    "stop_contract_keys": sorted(plan.stop_contract.keys()),
                },
            )
        ]

    def summarize_trace(self, traces: list[ResearchStepTrace]) -> dict[str, object]:
        return {
            "mode": self.mode,
            "llm_enabled": self.llm_enabled,
            "phase_count": len(traces),
            "phases": [trace.phase for trace in traces],
            "warning_count": sum(len(trace.warnings) for trace in traces),
        }

    def summarize_execution(
        self,
        *,
        traces: list[ResearchStepTrace],
        plan: ResearchPlan,
        fetched_documents: list[ResearchFetchedDocument],
        search_hits: list[ResearchSearchHit],
        read_windows: list[ResearchReadWindow],
        ledger: ResearchStateLedger,
        branch_decisions: list[ResearchBranchDecision],
        local_result: LocalVerifierResult,
        global_result: GlobalVerifierResult,
        final_decision: ResearchLoopDecision,
        loop_round_count: int,
        counterfactual_summary: dict[str, object],
        resume_context_summary: dict[str, object] | None,
        resume_checkpoint_payload: ResumeCheckpointPayload | None,
    ) -> dict[str, object]:
        summary = self.summarize_trace(traces)
        control_state = self.build_control_state(
            plan=plan,
            fetched_documents=fetched_documents,
            ledger=ledger,
            branch_decisions=branch_decisions,
            local_result=local_result,
            global_result=global_result,
            final_decision=final_decision,
            loop_round_count=loop_round_count,
            counterfactual_summary=counterfactual_summary,
            resume_context_summary=resume_context_summary,
        )
        control_state_payload = control_state.model_dump(mode="json")
        audit_summaries = self.build_audit_summaries(
            control_state=control_state,
            counterfactual_summary=counterfactual_summary,
            global_result=global_result,
            checkpoint_no=max(1, loop_round_count),
            snapshot_type="RESEARCH_LOOP_CHECKPOINT",
            resume_checkpoint_payload=resume_checkpoint_payload,
        ).model_dump(mode="json")
        contract = global_result.intent_completion_contract or ResearchIntentCompletionContract()
        alignment = global_result.research_intent_alignment or ResearchIntentAlignment()
        summary.update(control_state_payload)
        summary["audit_summaries"] = audit_summaries
        summary["toolbox_summary"] = self.build_toolbox_summary(
            plan=plan,
            search_hits=search_hits,
            fetched_documents=fetched_documents,
            read_windows=read_windows,
        ).model_dump(mode="json")
        summary["intent_contract"] = {
            "status": contract.status,
            "reason_code": contract.reason_code,
            "satisfied_requirement_count": contract.satisfied_requirement_count,
            "total_requirement_count": contract.total_requirement_count,
            "pending_requirement_count": contract.pending_requirement_count,
            "missing_requirement_labels": list(contract.missing_requirement_labels[:4]),
            "alignment_status": alignment.status,
            "alignment_reason_code": alignment.reason_code,
            "satisfied_constraint_count": alignment.satisfied_constraint_count,
            "total_constraint_count": alignment.total_constraint_count,
        }
        summary["counterfactual_recovery"] = {
            "has_counterfactual_recheck": bool(counterfactual_summary.get("has_counterfactual_recheck")),
            "counterfactual_branch_count": int(counterfactual_summary.get("counterfactual_branch_count") or 0),
            "counterfactual_branch_ids": list(counterfactual_summary.get("counterfactual_branch_ids", [])),
            "counterfactual_session_ids": list(counterfactual_summary.get("counterfactual_session_ids", [])),
            "target_evidence_ids": list(counterfactual_summary.get("target_evidence_ids", [])),
            "recovery_mode": str(
                counterfactual_summary.get("recovery_mode")
                or control_state.control_loop.recovery_mode
            ),
        }
        summary["regression_suite"] = build_harness_regression_suite_summary()
        return summary

    def build_control_state(
        self,
        *,
        plan: ResearchPlan,
        fetched_documents: list[ResearchFetchedDocument],
        ledger: ResearchStateLedger,
        branch_decisions: list[ResearchBranchDecision],
        local_result: LocalVerifierResult,
        global_result: GlobalVerifierResult,
        final_decision: ResearchLoopDecision,
        loop_round_count: int,
        counterfactual_summary: dict[str, object],
        resume_context_summary: dict[str, object] | None,
    ) -> ResearchHarnessControlState:
        latest_verifier = ledger.verifier_decisions[-1] if ledger.verifier_decisions else None
        latest_branch_decision = next(
            (decision for decision in reversed(branch_decisions) if decision.decision != "NO_BRANCH"),
            branch_decisions[-1] if branch_decisions else None,
        )
        recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper() or "DEFAULT"
        latest_reason_code = (
            latest_verifier.reason_code
            if latest_verifier is not None and latest_verifier.reason_code
            else latest_branch_decision.branch_reason
            if latest_branch_decision is not None and latest_branch_decision.branch_reason
            else global_result.research_intent_alignment.reason_code
        )
        return ResearchHarnessControlState(
            control_loop=ResearchHarnessLoopState(
                loop_round_count=loop_round_count,
                final_decision=final_decision.decision,
                final_reason=final_decision.reason,
                should_continue=final_decision.should_continue,
                recovery_mode=recovery_mode,
                branch_count=len(ledger.branches),
                active_branch_id=ledger.active_branch_id,
                recovery_action_count=len(final_decision.recovery_actions),
            ),
            verifier_gate=ResearchHarnessVerifierGateState(
                local_status=local_result.status,
                global_status=global_result.status,
                global_decision=global_result.decision,
                completion_score=global_result.completion_score,
                warning_count=len(local_result.warnings),
                unresolved_question_count=len(ledger.unresolved_questions),
                recovery_action_count=len(global_result.recovery_actions),
                latest_reason_code=latest_reason_code,
                latest_decision=ResearchHarnessVerifierLatestDecision(
                    decision_scope=latest_verifier.decision_scope if latest_verifier is not None else (
                        latest_branch_decision.verifier_scope if latest_branch_decision is not None else "VERIFY"
                    ),
                    decision_type=latest_verifier.decision_type if latest_verifier is not None else (
                        latest_branch_decision.decision if latest_branch_decision is not None else global_result.decision
                    ),
                    status=latest_verifier.status if latest_verifier is not None else local_result.status,
                    target_id=latest_verifier.target_id if latest_verifier is not None else (
                        latest_branch_decision.branch_id if latest_branch_decision is not None else ""
                    ),
                ),
            ),
            verifier_gate_policy=build_verifier_gate_policy_state(
                recovery_mode=recovery_mode,
                branch_decisions=branch_decisions,
                ledger=ledger,
                local_result=local_result,
                global_result=global_result,
                final_decision=final_decision,
            ),
            branch_recovery=ResearchHarnessBranchRecoveryState(
                active_branch_id=ledger.active_branch_id,
                branch_count=len(ledger.branches),
                has_recovery_branch=any(decision.decision != "NO_BRANCH" for decision in branch_decisions),
                latest_branch_id=latest_branch_decision.branch_id if latest_branch_decision is not None else "",
                latest_branch_decision=latest_branch_decision.decision if latest_branch_decision is not None else "",
                latest_branch_reason=latest_branch_decision.branch_reason if latest_branch_decision is not None else "",
                latest_verifier_scope=latest_branch_decision.verifier_scope if latest_branch_decision is not None else "",
                target_evidence_ids=list(
                    counterfactual_summary.get("target_evidence_ids", [])
                    or (latest_branch_decision.target_evidence_ids if latest_branch_decision is not None else [])
                ),
                recovery_action_count=len(global_result.recovery_actions),
            ),
            resume_checkpoint=ResearchHarnessResumeState(
                resumed_from_checkpoint=resume_context_summary is not None,
                source_research_run_id=(
                    str(resume_context_summary.get("source_research_run_id") or "")
                    if resume_context_summary is not None
                    else ""
                ),
                checkpoint_no=(
                    int(resume_context_summary.get("checkpoint_no") or 0)
                    if resume_context_summary is not None
                    else 0
                ),
                restored_search_hit_count=(
                    int(resume_context_summary.get("restored_search_hit_count") or 0)
                    if resume_context_summary is not None
                    else 0
                ),
                restored_fetch_document_count=(
                    int(resume_context_summary.get("restored_fetch_document_count") or 0)
                    if resume_context_summary is not None
                    else 0
                ),
                restored_read_window_count=(
                    int(resume_context_summary.get("restored_read_window_count") or 0)
                    if resume_context_summary is not None
                    else 0
                ),
                restored_evidence_card_count=(
                    int(resume_context_summary.get("restored_evidence_card_count") or 0)
                    if resume_context_summary is not None
                    else 0
                ),
                restored_loop_round_count=(
                    int(resume_context_summary.get("restored_loop_round_count") or 0)
                    if resume_context_summary is not None
                    else 0
                ),
            ),
            evidence_coverage=ResearchHarnessEvidenceCoverageState(
                search_hit_count=ledger.search_hit_count,
                fetch_document_count=len(fetched_documents),
                read_window_count=ledger.read_window_count,
                evidence_card_count=ledger.evidence_card_count,
                verified_row_count=ledger.verified_row_count,
                conflicted_row_count=ledger.conflicted_row_count,
                requirement_ready_row_count=ledger.requirement_ready_row_count,
                coverage_score=ledger.coverage_score,
            ),
        )

    def build_toolbox_summary(
        self,
        *,
        plan: ResearchPlan,
        search_hits: list[ResearchSearchHit],
        fetched_documents: list[ResearchFetchedDocument],
        read_windows: list[ResearchReadWindow],
    ) -> ResearchToolboxSummary:
        query_plan = build_search_query_plan(plan)
        hits_by_query: dict[str, list[ResearchSearchHit]] = {}
        for hit in search_hits:
            hits_by_query.setdefault(hit.query, []).append(hit)
        query_family_budget_counts = {
            str(key).strip(): max(0, int(value))
            for key, value in dict(
                dict(plan.stop_contract.get("execution_profile", {})).get("query_family_budgets", {})
            ).items()
            if str(key).strip()
        }
        selected_queries = [
            {
                "query": item.query,
                "family": item.family,
                "lane": item.lane,
                "priority": item.priority,
                "family_budget": query_family_budget_counts.get(item.family, 1),
                "selection_reason": _selection_reason(item.family, plan),
            }
            for item in query_plan
        ]
        query_effectiveness: list[dict[str, object]] = []
        search_items: list[ResearchToolboxSearchItem] = []
        effective_query_count = 0
        zero_hit_query_count = 0
        for item in query_plan:
            query_hits = hits_by_query.get(item.query, [])
            hit_count = len(query_hits)
            if hit_count > 0:
                effective_query_count += 1
            else:
                zero_hit_query_count += 1
            coverage_scores = [float(hit.coverage_score) for hit in query_hits]
            query_effectiveness.append(
                {
                    "query": item.query,
                    "family": item.family,
                    "lane": item.lane,
                    "hit_count": hit_count,
                    "max_coverage_score": max(coverage_scores) if coverage_scores else 0.0,
                    "avg_coverage_score": (
                        round(sum(coverage_scores) / len(coverage_scores), 4)
                        if coverage_scores
                        else 0.0
                    ),
                    "provider_resolution_counts": _count_values(
                        hit.provider_resolution for hit in query_hits
                    ),
                    "top_source_titles": [hit.source_title for hit in query_hits[:3]],
                }
            )
            search_items.append(
                ResearchToolboxSearchItem(
                    query=item.query,
                    family=item.family,
                    lane=item.lane,
                    priority=item.priority,
                    family_budget=query_family_budget_counts.get(item.family, 1),
                    selection_reason=_selection_reason(item.family, plan),
                    hit_count=hit_count,
                    top_hits=[
                        ResearchToolboxSearchTopHit(
                            hit_id=hit.hit_id,
                            source_id=hit.source_id,
                            source_title=hit.source_title,
                            rank=hit.rank,
                            provider_resolution=hit.provider_resolution,
                            search_angle=hit.search_angle,
                            coverage_score=hit.coverage_score,
                            url=hit.url,
                        )
                        for hit in query_hits[:3]
                    ],
                )
            )
        query_effectiveness.sort(
            key=lambda item: (-int(item["hit_count"]), float(item["max_coverage_score"]) * -1, str(item["query"]))
        )
        search_items.sort(key=lambda item: (-item.hit_count, item.priority, item.query))
        return ResearchToolboxSummary(
            search_summary=ResearchToolboxSearchSummary(
                query_count=len(plan.query_set),
                hit_count=len(search_hits),
                selected_query_count=len(query_plan),
                query_family_counts=_count_values(item.family for item in query_plan),
                query_family_budget_counts=query_family_budget_counts,
                query_lane_counts=_count_values(item.lane for item in query_plan),
                search_angle_counts=_count_values(hit.search_angle for hit in search_hits),
                provider_resolution_counts=_count_values(hit.provider_resolution for hit in search_hits),
                provider_fallback_reason_counts=_count_values(
                    str(hit.provider_fallback_reason or "").strip() or None
                    for hit in search_hits
                ),
                source_quality_counts=_count_values(hit.source_quality for hit in search_hits),
                effective_query_count=effective_query_count,
                zero_hit_query_count=zero_hit_query_count,
                selected_queries=selected_queries,
                query_effectiveness=query_effectiveness,
            ),
            fetch_summary=ResearchToolboxFetchSummary(
                fetch_document_count=len(fetched_documents),
                fetched_document_count=sum(1 for document in fetched_documents if document.fetch_status == "FETCHED"),
                fallback_document_count=sum(1 for document in fetched_documents if document.fetch_status == "FALLBACK_USED"),
                snapshot_archive_ready_count=sum(
                    1 for document in fetched_documents if document.snapshot_archive_ready
                ),
                multi_transport_document_count=sum(
                    1 for document in fetched_documents if document.transport_attempt_count > 1
                ),
                fetch_status_counts=_count_values(document.fetch_status for document in fetched_documents),
                fetch_method_counts=_count_values(document.fetch_method for document in fetched_documents),
                content_origin_counts=_count_values(document.content_origin for document in fetched_documents),
                content_type_counts=_count_values(document.content_type_label for document in fetched_documents),
                transport_resolution_counts=_count_values(document.transport_resolution for document in fetched_documents),
                fetch_failure_code_counts=_count_values(
                    str(document.fetch_failure_code or "").strip() or None
                    for document in fetched_documents
                ),
                transport_fallback_code_counts=_count_values(
                    str(document.transport_fallback_code or "").strip() or None
                    for document in fetched_documents
                ),
            ),
            read_summary=ResearchToolboxReadSummary(
                read_window_count=len(read_windows),
                fallback_window_count=sum(1 for window in read_windows if window.fetch_status == "FALLBACK_USED"),
                snapshot_archive_ready_count=sum(
                    1 for window in read_windows if window.snapshot_archive_ready
                ),
                multi_transport_window_count=sum(
                    1 for window in read_windows if window.transport_attempt_count > 1
                ),
                query_family_counts=_count_values(window.query_family for window in read_windows),
                read_strategy_counts=_count_values(window.read_strategy for window in read_windows),
                fetch_status_counts=_count_values(window.fetch_status for window in read_windows),
                content_origin_counts=_count_values(window.content_origin for window in read_windows),
                content_type_counts=_count_values(window.content_type_label for window in read_windows),
                fetch_failure_code_counts=_count_values(
                    str(window.fetch_failure_code or "").strip() or None
                    for window in read_windows
                ),
                target_requirement_window_count=sum(
                    1 for window in read_windows if window.target_requirement_ids or window.target_requirement_labels
                ),
            ),
            search_items=search_items,
            fetch_items=[
                ResearchToolboxFetchItem(
                    fetch_id=document.fetch_id,
                    hit_id=document.hit_id,
                    source_id=document.source_id,
                    source_title=document.source_title,
                    query=document.query,
                    fetch_status=document.fetch_status,
                    fetch_method=document.fetch_method,
                    content_origin=document.content_origin,
                    content_type_label=document.content_type_label,
                    fetch_failure_code=document.fetch_failure_code,
                    transport_resolution=document.transport_resolution,
                    transport_fallback_code=document.transport_fallback_code,
                    transport_chain=list(document.transport_chain),
                    transport_attempt_count=document.transport_attempt_count,
                    snapshot_status=document.snapshot_status,
                    snapshot_key=document.snapshot_key,
                    snapshot_archive_ready=document.snapshot_archive_ready,
                    url=document.url,
                )
                for document in fetched_documents
            ],
            read_items=[
                ResearchToolboxReadItem(
                    window_id=window.window_id,
                    hit_id=window.hit_id,
                    source_id=window.source_id,
                    source_title=window.source_title,
                    query=window.query,
                    query_family=window.query_family,
                    read_focus=window.read_focus,
                    read_strategy=window.read_strategy,
                    fetch_status=window.fetch_status,
                    content_origin=window.content_origin,
                    content_type_label=window.content_type_label,
                    fetch_failure_code=window.fetch_failure_code,
                    transport_resolution=window.transport_resolution,
                    transport_attempt_count=window.transport_attempt_count,
                    target_requirement_ids=list(window.target_requirement_ids),
                    target_requirement_labels=list(window.target_requirement_labels),
                    target_columns=list(window.target_columns),
                    snapshot_status=window.snapshot_status,
                    snapshot_key=window.snapshot_key,
                    snapshot_archive_ready=window.snapshot_archive_ready,
                    url=window.url,
                )
                for window in read_windows
            ],
        )

    def build_audit_summaries(
        self,
        *,
        control_state: ResearchHarnessControlState,
        counterfactual_summary: dict[str, object],
        global_result: GlobalVerifierResult,
        checkpoint_no: int,
        snapshot_type: str,
        resume_checkpoint_payload: ResumeCheckpointPayload | None,
    ) -> ResearchHarnessAuditSummaryState:
        contract = global_result.intent_completion_contract or ResearchIntentCompletionContract()
        return ResearchHarnessAuditSummaryState(
            checkpoint_summary=ResearchHarnessCheckpointSummaryState(
                checkpoint_no=checkpoint_no,
                snapshot_type=snapshot_type,
                loop_round_count=control_state.control_loop.loop_round_count,
                final_loop_decision=(
                    control_state.verifier_gate_policy.final_loop_decision
                    or control_state.control_loop.final_decision
                ),
                report_gate_action=control_state.verifier_gate_policy.report_gate_action,
                active_branch_id=control_state.control_loop.active_branch_id,
                recovery_mode=control_state.control_loop.recovery_mode,
                resumed_from_checkpoint=control_state.resume_checkpoint.resumed_from_checkpoint,
                allows_final_write=control_state.verifier_gate_policy.allows_final_write,
                allows_guarded_write=control_state.verifier_gate_policy.allows_guarded_write,
            ),
            counterfactual_summary=ResearchHarnessCounterfactualSummaryState(
                has_counterfactual_recheck=bool(counterfactual_summary.get("has_counterfactual_recheck")),
                counterfactual_branch_count=int(counterfactual_summary.get("counterfactual_branch_count") or 0),
                counterfactual_branch_ids=list(counterfactual_summary.get("counterfactual_branch_ids", [])),
                counterfactual_session_ids=list(counterfactual_summary.get("counterfactual_session_ids", [])),
                active_counterfactual_branch_ids=list(counterfactual_summary.get("active_counterfactual_branch_ids", [])),
                active_counterfactual_session_ids=list(counterfactual_summary.get("active_counterfactual_session_ids", [])),
                target_evidence_ids=list(counterfactual_summary.get("target_evidence_ids", [])),
                conflicted_row_count=control_state.evidence_coverage.conflicted_row_count,
                recovery_mode=str(
                    counterfactual_summary.get("recovery_mode")
                    or control_state.control_loop.recovery_mode
                ),
                latest_branch_decision=control_state.branch_recovery.latest_branch_decision,
                latest_branch_reason=control_state.branch_recovery.latest_branch_reason,
            ),
            resume_summary=ResearchHarnessResumeSummaryState(
                resumed_from_checkpoint=control_state.resume_checkpoint.resumed_from_checkpoint,
                source_research_run_id=control_state.resume_checkpoint.source_research_run_id,
                checkpoint_no=control_state.resume_checkpoint.checkpoint_no,
                source_final_loop_decision=(
                    resume_checkpoint_payload.final_loop_decision
                    if resume_checkpoint_payload is not None
                    else ""
                ),
                source_active_branch_id=(
                    resume_checkpoint_payload.active_branch_id
                    if resume_checkpoint_payload is not None
                    else ""
                ),
                restored_search_hit_count=control_state.resume_checkpoint.restored_search_hit_count,
                restored_fetch_document_count=control_state.resume_checkpoint.restored_fetch_document_count,
                restored_read_window_count=control_state.resume_checkpoint.restored_read_window_count,
                restored_evidence_card_count=control_state.resume_checkpoint.restored_evidence_card_count,
                restored_loop_round_count=control_state.resume_checkpoint.restored_loop_round_count,
            ),
            evidence_coverage_summary=ResearchHarnessEvidenceCoverageSummaryState(
                search_hit_count=control_state.evidence_coverage.search_hit_count,
                fetch_document_count=control_state.evidence_coverage.fetch_document_count,
                read_window_count=control_state.evidence_coverage.read_window_count,
                evidence_card_count=control_state.evidence_coverage.evidence_card_count,
                verified_row_count=control_state.evidence_coverage.verified_row_count,
                conflicted_row_count=control_state.evidence_coverage.conflicted_row_count,
                requirement_ready_row_count=control_state.evidence_coverage.requirement_ready_row_count,
                coverage_score=control_state.evidence_coverage.coverage_score,
                pending_requirement_count=contract.pending_requirement_count,
                unresolved_question_count=control_state.verifier_gate.unresolved_question_count,
            ),
        )


def build_harness(_task_input: ResearchTaskInput) -> ResearchHarness:
    settings = load_settings()
    llm_enabled = bool(
        getattr(settings, "llm_api_key", "").strip()
        and getattr(settings, "llm_model", "").strip()
    )
    return ResearchHarness(mode="LLM" if llm_enabled else "RULE", llm_enabled=llm_enabled)


def build_step_trace(
    phase: str,
    message: str,
    inputs: dict[str, object] | None = None,
    outputs: dict[str, object] | None = None,
    warnings: list[str] | None = None,
    status: str = "success",
) -> ResearchStepTrace:
    timestamp = datetime.now(timezone.utc).isoformat()
    return ResearchStepTrace(
        trace_id=f"trace-{phase.lower()}",
        event_id=f"trace-{phase.lower()}",
        started_at=timestamp,
        ended_at=timestamp,
        phase=phase,
        status=status,
        message=message,
        inputs=_sanitize_payload(inputs or {}),
        outputs=_sanitize_payload(outputs or {}),
        warnings=warnings or [],
    )


def _sanitize_payload(payload: dict[str, object]) -> dict[str, object]:
    return sanitize_trace_payload(payload)


def _count_values(values) -> dict[str, int]:
    summary: dict[str, int] = {}
    for value in values:
        if value is None:
            continue
        normalized = str(value).strip()
        if not normalized:
            continue
        summary[normalized] = summary.get(normalized, 0) + 1
    return summary


def _selection_reason(family: str, plan: ResearchPlan) -> str:
    recovery_mode = str(plan.stop_contract.get("recovery_mode", "")).strip().upper()
    if recovery_mode == "COUNTERFACTUAL_RECHECK":
        return "selected because the run is in counterfactual recovery mode and needs challenge evidence"
    if recovery_mode == "READ_MORE":
        return "selected because the run needs another verifier-grounded evidence window"
    if recovery_mode == "EXTRACT_AGAIN":
        return "selected because the run is retrying extraction from retained evidence"
    if family == "direct":
        return "selected as the primary direct query for the research question"
    if family == "source_scoped":
        return "selected to bind search toward source-scoped evidence targets"
    if family == "coverage_gap":
        return "selected to close coverage gaps left by earlier direct retrieval"
    if family == "deep_focus":
        return "selected to deepen evidence collection on the most relevant line of inquiry"
    if family == "triangulation":
        return "selected to cross-check claims across multiple sources"
    if family == "verified_evidence":
        return "selected to open verifier-ready evidence windows for grounded synthesis"
    if family == "direct_evidence":
        return "selected to retrieve answer-shaped evidence with direct grounding"
    if family == "counterfactual":
        return "selected to test the current conclusion against conflicting or opposing evidence"
    if family == "constraints":
        return "selected to satisfy explicit user constraints in the research intent"
    if family == "intent":
        return "selected to keep retrieval aligned with the explicit research goal"
    if family == "deliverable":
        return "selected to support the requested report or deliverable shape"
    if family == "time_range":
        return "selected to recover evidence scoped to the requested time range"
    return "selected by the execution profile as part of the current bounded search bundle"
