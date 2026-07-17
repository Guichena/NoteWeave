from __future__ import annotations

from typing import Callable

from app.harness import build_harness, build_step_trace
from app.llm_client import build_default_llm_client
from app.loop_runtime import evaluate_premature_commitment_guard, run_research_loop
from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult
from app.reporter import (
    build_counterfactual_summary,
    build_research_artifact_candidate,
    build_research_report_structure,
    write_research_report,
)
from app.recovery_targets import build_recovery_targets
from app.citation_verifier import verify_report_citations
from app.report_refiner import refine_report_structure
from app.research_tools import mark_plan_phase


def _build_phase_metrics(
    phase: str,
    *,
    query_count: int,
    source_count: int,
    search_hits: int,
    read_windows: int,
    evidence_cards: int,
    ledger_rows: int,
    ledger_cells: int,
    branch_count: int,
    local_status: str,
    global_status: str,
    loop_rounds: int,
    loop_decision: str,
) -> dict[str, int | str]:
    metrics: dict[str, int | str] = {
        "query_count": query_count,
        "source_count": source_count,
    }
    if phase in {"SEARCHING", "READING", "EXTRACTING", "VERIFYING", "WRITING"}:
        metrics["search_hits"] = search_hits
    if phase in {"READING", "EXTRACTING", "VERIFYING", "WRITING"}:
        metrics["read_windows"] = read_windows
    if phase in {"EXTRACTING", "VERIFYING", "WRITING"}:
        metrics["evidence_cards"] = evidence_cards
    if phase in {"EXTRACTING", "VERIFYING", "WRITING"}:
        metrics["ledger_rows"] = ledger_rows
        metrics["ledger_cells"] = ledger_cells
    if phase in {"VERIFYING", "WRITING"}:
        metrics["branch_count"] = branch_count
        metrics["local_status"] = local_status
        metrics["global_status"] = global_status
    if phase == "WRITING":
        metrics["loop_rounds"] = loop_rounds
        metrics["loop_decision"] = loop_decision
    return metrics


def _dedupe_source_samples(samples: list[dict[str, object]], limit: int = 2) -> list[dict[str, object]]:
    deduped: list[dict[str, object]] = []
    seen_keys: set[str] = set()
    for sample in samples:
        source_id = str(sample.get("source_id", "")).strip()
        source_title = str(sample.get("source_title", "") or sample.get("title", "")).strip()
        key = f"{source_id}::{source_title}"
        if not source_id and not source_title:
            continue
        if key in seen_keys:
            continue
        seen_keys.add(key)
        deduped.append(sample)
        if len(deduped) >= limit:
            break
    return deduped


def _build_scope_source_samples(task_input: ResearchTaskInput) -> list[dict[str, object]]:
    return _dedupe_source_samples([
        {
            "source_id": item.source_id,
            "source_title": item.title,
            "title": item.title,
            "summary": item.summary or item.sample_text,
        }
        for item in task_input.source_scope
    ])


def _build_search_source_samples(search_hits) -> list[dict[str, object]]:
    return _dedupe_source_samples([
        {
            "source_id": hit.source_id,
            "source_title": hit.source_title,
            "title": hit.source_title,
            "query": hit.query,
            "search_angle": hit.search_angle,
            "url": hit.url,
            "provider": hit.provider,
            "provider_resolution": hit.provider_resolution,
            "provider_attempts": list(hit.provider_attempts),
            "adapter": hit.adapter,
        }
        for hit in search_hits
    ])


def _build_read_source_samples(read_windows) -> list[dict[str, object]]:
    return _dedupe_source_samples([
        {
            "source_id": window.source_id,
            "source_title": window.source_title,
            "title": window.source_title,
            "query": window.query,
            "read_focus": window.read_focus,
            "url": window.url,
            "snapshot_status": window.snapshot_status,
            "fetch_status": window.fetch_status,
            "fetch_method": window.fetch_method,
            "content_origin": window.content_origin,
            "transport_resolution": window.transport_resolution,
            "transport_chain": list(window.transport_chain),
        }
        for window in read_windows
    ])


def _build_evidence_source_samples(evidence_cards) -> list[dict[str, object]]:
    return _dedupe_source_samples([
        {
            "source_id": card.source_id,
            "source_title": card.source_title,
            "title": card.source_title,
            "claim_text": card.claim_text,
            "quote_text": card.quote_text,
            "relation_type": card.relation_type,
        }
        for card in evidence_cards
    ])


def _resolve_evidence_source_samples(
    evidence_cards,
    target_evidence_ids: list[str],
) -> list[dict[str, object]]:
    if not target_evidence_ids:
        return []
    evidence_by_id = {
        card.evidence_id: card
        for card in evidence_cards
    }
    samples = []
    for evidence_id in target_evidence_ids:
        card = evidence_by_id.get(evidence_id)
        if card is None:
            continue
        samples.append(
            {
                "source_id": card.source_id,
                "source_title": card.source_title,
                "title": card.source_title,
                "claim_text": card.claim_text,
                "quote_text": card.quote_text,
                "relation_type": card.relation_type,
                "evidence_id": card.evidence_id,
            }
        )
    return _dedupe_source_samples(samples)


def _build_verifier_focus(
    plan,
    ledger,
    branch_decisions,
    evidence_cards,
    local_result,
    global_result,
) -> dict[str, object]:
    latest_verifier = ledger.verifier_decisions[-1] if ledger.verifier_decisions else None
    latest_branch_decision = branch_decisions[-1] if branch_decisions else None
    recovery_targets = build_recovery_targets(plan)
    target_requirement_id = (
        str(latest_verifier.target_id).strip()
        if latest_verifier and str(latest_verifier.target_id).strip()
        else (recovery_targets["requirement_ids"][0] if recovery_targets["requirement_ids"] else "")
    )
    target_evidence_ids = [
        str(item).strip()
        for item in (
            latest_verifier.evidence_ids
            if latest_verifier is not None
            else latest_branch_decision.target_evidence_ids if latest_branch_decision is not None else []
        )
        if str(item).strip()
    ]
    source_samples = _resolve_evidence_source_samples(evidence_cards, target_evidence_ids)
    if not source_samples:
        source_samples = _build_evidence_source_samples(evidence_cards)
    return {
        "verifier_scope": (
            latest_branch_decision.verifier_scope
            if latest_branch_decision is not None and latest_branch_decision.verifier_scope
            else latest_verifier.decision_scope if latest_verifier is not None else "VERIFY"
        ),
        "branch_id": (
            latest_branch_decision.branch_id
            if latest_branch_decision is not None and latest_branch_decision.branch_id
            else ledger.active_branch_id
        ),
        "target_requirement_id": target_requirement_id,
        "decision": (
            latest_verifier.decision_type
            if latest_verifier is not None and latest_verifier.decision_type
            else global_result.decision
        ),
        "reason_code": (
            latest_verifier.reason_code
            if latest_verifier is not None and latest_verifier.reason_code
            else global_result.research_intent_alignment.reason_code
        ),
        "status": (
            latest_verifier.status
            if latest_verifier is not None and latest_verifier.status
            else local_result.status
        ),
        "target_evidence_ids": target_evidence_ids,
        "source_samples": source_samples,
    }


def _build_phase_payload(
    phase: str,
    task_input: ResearchTaskInput,
    plan,
    search_hits,
    read_windows,
    evidence_cards,
    ledger,
    branch_decisions,
    local_result,
    global_result,
    loop_rounds,
    loop_decision,
) -> dict[str, object]:
    if phase == "PLANNING":
        return {
            "query_samples": plan.query_set[:3],
            "source_samples": _build_scope_source_samples(task_input),
            "research_goal": task_input.input_payload.research_intent.research_goal,
            "deliverable_format": task_input.input_payload.research_intent.deliverable_format,
            "execution_profile": dict(plan.stop_contract.get("execution_profile", {})),
        }
    if phase == "SEARCHING":
        return {
            "query_samples": list(dict.fromkeys(hit.query for hit in search_hits[:3] if hit.query)),
            "source_samples": _build_search_source_samples(search_hits),
            "search_angles": sorted({hit.search_angle for hit in search_hits if hit.search_angle})[:4],
            "provider_resolutions": list(
                dict.fromkeys(hit.provider_resolution for hit in search_hits if hit.provider_resolution)
            ),
        }
    if phase == "READING":
        return {
            "query_samples": list(dict.fromkeys(window.query for window in read_windows[:3] if window.query)),
            "source_samples": _build_read_source_samples(read_windows),
            "read_focuses": list(dict.fromkeys(window.read_focus for window in read_windows[:3] if window.read_focus)),
            "fetch_statuses": list(dict.fromkeys(window.fetch_status for window in read_windows if window.fetch_status)),
            "fetch_methods": list(dict.fromkeys(window.fetch_method for window in read_windows if window.fetch_method)),
            "transport_resolutions": list(
                dict.fromkeys(window.transport_resolution for window in read_windows if window.transport_resolution)
            ),
        }
    if phase == "EXTRACTING":
        return {
            "source_samples": _build_evidence_source_samples(evidence_cards),
            "claim_samples": [
                card.claim_text
                for card in evidence_cards[:3]
                if card.claim_text
            ],
        }
    if phase in {"VERIFYING", "WRITING"}:
        payload = {
            "source_samples": _build_evidence_source_samples(evidence_cards),
            "recovery_mode": str(plan.stop_contract.get("recovery_mode", "")).strip().upper(),
            "recovery_targets": build_recovery_targets(plan),
            "verifier_focus": _build_verifier_focus(
                plan,
                ledger,
                branch_decisions,
                evidence_cards,
                local_result,
                global_result,
            ),
        }
        if phase == "WRITING":
            payload["loop_decision"] = loop_decision
            payload["loop_round_count"] = len(loop_rounds)
        return payload
    return {}


def run_research_task(
    task_input: ResearchTaskInput,
    progress_callback: Callable[[ResearchProgressEvent], None] | None = None,
    checkpoint_callback: Callable[[dict[str, object]], None] | None = None,
    cancellation_checker: Callable[[], None] | None = None,
) -> tuple[list[ResearchProgressEvent], ResearchTaskResult]:
    events: list[ResearchProgressEvent] = []

    def emit_progress(event: ResearchProgressEvent) -> None:
        events.append(event)
        if progress_callback is not None:
            progress_callback(event)

    harness = build_harness(task_input)
    llm_client = build_default_llm_client()
    plan, harness_trace = harness.plan(task_input)
    planning_event = ResearchProgressEvent(
        phase="PLANNING",
        progress_percent=10,
        message="research plan compiled",
        metrics={"query_count": len(plan.query_set), "source_count": len(task_input.source_scope)},
        payload=_build_phase_payload(
            "PLANNING",
            task_input,
            plan,
            [],
            [],
            [],
            None,
            [],
            None,
            None,
            [],
            {},
        ),
    )
    emit_progress(planning_event)
    loop_result = run_research_loop(
        task_input,
        plan,
        llm_client=llm_client,
        progress_callback=emit_progress,
        checkpoint_callback=checkpoint_callback,
        cancellation_checker=cancellation_checker,
    )
    plan = loop_result.plan
    search_hits = loop_result.artifacts.search_hits
    read_windows = loop_result.artifacts.read_windows
    fetched_documents = loop_result.artifacts.fetched_documents
    evidence_cards = loop_result.artifacts.evidence_cards
    ledger = loop_result.artifacts.ledger
    premature_commitment_guard = evaluate_premature_commitment_guard(
        plan,
        ledger,
        len(evidence_cards),
    )
    local_result = loop_result.artifacts.local_result
    branch_decisions = loop_result.artifacts.branch_decisions
    global_result = loop_result.artifacts.global_result

    harness_trace.append(
        build_step_trace(
            phase="SEARCHING",
            message="research search adapter hits resolved",
            inputs={"query_count": len(plan.query_set), "source_count": len(task_input.source_scope)},
            outputs={
                "search_hits": len(search_hits),
                "top_hit_ids": [hit.hit_id for hit in search_hits[:3]],
                "search_angles": sorted({hit.search_angle for hit in search_hits}),
            },
        )
    )
    harness_trace.append(
        build_step_trace(
            phase="READING",
            message="bounded read windows opened",
            inputs={"search_hits": len(search_hits), "retention_budget": plan.stop_contract.get("tool_response_retention_budget", 0)},
            outputs={
                "read_windows": len(read_windows),
                "window_ids": [window.window_id for window in read_windows[:5]],
                "token_estimate": sum(window.token_estimate for window in read_windows),
            },
        )
    )
    harness_trace.append(
        build_step_trace(
            phase="EXTRACTING",
            message="evidence cards extracted",
            inputs={"read_windows": len(read_windows)},
            outputs={
                "evidence_cards": len(evidence_cards),
                "evidence_ids": [card.evidence_id for card in evidence_cards[:5]],
                "conflicting_cards": sum(1 for card in evidence_cards if card.relation_type == "CONFLICTS"),
            },
        )
    )
    harness_trace.append(
        build_step_trace(
            phase="VERIFYING",
            message="dual verifier completed",
            inputs={
                "ledger_rows": len(ledger.rows),
                "evidence_cards": len(evidence_cards),
                "branch_decisions": len(branch_decisions),
            },
            outputs={
                "local_status": local_result.status,
                "global_status": global_result.status,
                "global_decision": global_result.decision,
                "recovery_actions": len(global_result.recovery_actions),
                "branch_count": len(ledger.branches),
                "verifier_decisions": len(ledger.verifier_decisions),
                "loop_decision": loop_result.final_decision.decision,
                "loop_rounds": len(loop_result.rounds),
            },
            warnings=list(local_result.warnings) + list(ledger.unresolved_questions),
        )
    )

    writing_event = ResearchProgressEvent(
        phase="WRITING",
        progress_percent=100,
        message="research report generated",
        metrics=_build_phase_metrics(
            "WRITING",
            query_count=len(plan.query_set),
            source_count=len(task_input.source_scope),
            search_hits=len(search_hits),
            read_windows=len(read_windows),
            evidence_cards=len(evidence_cards),
            ledger_rows=len(ledger.rows),
            ledger_cells=len(ledger.cells),
            branch_count=len(ledger.branches),
            local_status=local_result.status,
            global_status=global_result.status,
            loop_rounds=len(loop_result.rounds),
            loop_decision=loop_result.final_decision.decision,
        ),
        payload=_build_phase_payload(
            "WRITING",
            task_input,
            plan,
            search_hits,
            read_windows,
            evidence_cards,
            ledger,
            branch_decisions,
            local_result,
            global_result,
            loop_result.rounds,
            loop_result.final_decision.model_dump(mode="json"),
        ),
    )

    report_structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        evidence_cards,
        branch_decisions,
        local_result,
        global_result,
    )
    citation_verification = verify_report_citations(
        report_structure,
        evidence_cards,
        read_windows,
        llm_client=llm_client,
    )
    report_structure, report_revision_loop = refine_report_structure(
        report_structure,
        citation_verification,
        max_revisions=2,
    )
    mark_plan_phase(plan, "REPORT_AUDIT", "COMPLETE")
    counterfactual_summary = dict(report_structure.get("counterfactual_summary", {})) or build_counterfactual_summary(
        ledger,
        branch_decisions,
        local_result,
        global_result,
        str(plan.stop_contract.get("recovery_mode", "")).strip().upper(),
        valid_evidence_ids={card.evidence_id for card in evidence_cards},
    )
    intent_completion_contract = dict(report_structure.get("intent_completion_contract", {})) or global_result.intent_completion_contract.model_dump(mode="json")
    research_intent_alignment = dict(report_structure.get("research_intent_alignment", {})) or global_result.research_intent_alignment.model_dump(mode="json")
    report_markdown = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        evidence_cards,
        branch_decisions,
        local_result,
        global_result,
        report_structure=report_structure,
    )
    emit_progress(writing_event)
    accepted_evidence_ids = set(citation_verification["accepted_evidence_ids"])
    citations_payload = [
        {
            "title": card.source_title,
            "source_id": card.source_id,
            "evidence_id": card.evidence_id,
            "quote": card.quote_text,
            "quote_start": card.quote_start,
            "quote_end": card.quote_end,
            "snapshot_key": card.snapshot_key,
            "content_sha256": card.content_sha256,
        }
        for card in evidence_cards
        if card.evidence_id in accepted_evidence_ids
    ]
    harness_trace.append(
        build_step_trace(
            phase="WRITING",
            message="research report generated",
            inputs={
                "ledger_rows": len(ledger.rows),
                "branch_count": len(ledger.branches),
                "verifier_decisions": len(ledger.verifier_decisions),
                "global_decision": global_result.decision,
            },
            outputs={
                "report_generated": bool(report_markdown.strip()),
                "report_characters": len(report_markdown),
                "citation_count": min(3, len(ledger.rows)),
            },
        )
    )
    harness_trace = [
        trace.model_copy(
            update={
                "run_id": task_input.target_id,
                "round_no": trace.round_no or loop_result.final_decision.round_no,
                "branch_id": ledger.active_branch_id,
                "event_id": f"{task_input.target_id}:{trace.event_id or trace.trace_id}",
            }
        )
        for trace in harness_trace
    ]
    loop_rounds_payload = [
        round_summary.model_dump(mode="json")
        for round_summary in loop_result.rounds
    ]
    loop_decision_payload = loop_result.final_decision.model_dump(mode="json")
    tool_traces_payload = [
        trace.model_dump(mode="json")
        for trace in loop_result.artifacts.tool_traces
    ]
    resume_context_summary = (
        dict(loop_result.resume_context_summary)
        if loop_result.resume_context_summary is not None
        else None
    )
    harness_control_state_state = harness.build_control_state(
        plan=plan,
        fetched_documents=fetched_documents,
        ledger=ledger,
        branch_decisions=branch_decisions,
        local_result=local_result,
        global_result=global_result,
        final_decision=loop_result.final_decision,
        loop_round_count=len(loop_result.rounds),
        counterfactual_summary=counterfactual_summary,
        resume_context_summary=resume_context_summary,
    )
    verifier_gate_policy = harness_control_state_state.verifier_gate_policy.model_dump(mode="json")
    if int(citation_verification.get("failed_finding_count") or 0) > 0:
        verifier_gate_policy.update(
            {
                "report_gate_action": "ALLOW_GUARDED_WRITE",
                "allows_final_write": False,
                "allows_guarded_write": True,
                "requires_read_more": True,
                "blocking_reason_codes": list(dict.fromkeys([
                    *verifier_gate_policy.get("blocking_reason_codes", []),
                    "CITATION_VERIFICATION_FAILED",
                ])),
            }
        )
        harness_control_state_state = harness_control_state_state.model_copy(
            update={
                "verifier_gate_policy": harness_control_state_state.verifier_gate_policy.model_copy(
                    update=verifier_gate_policy
                )
            }
        )
    harness_control_state = harness_control_state_state.model_dump(mode="json")
    audit_summaries = harness.build_audit_summaries(
        control_state=harness_control_state_state,
        counterfactual_summary=counterfactual_summary,
        global_result=global_result,
        checkpoint_no=max(1, len(loop_rounds_payload)),
        snapshot_type="RESEARCH_LOOP_CHECKPOINT",
        resume_checkpoint_payload=task_input.input_payload.resume_checkpoint,
    ).model_dump(mode="json")
    toolbox_summary = harness.build_toolbox_summary(
        plan=plan,
        search_hits=search_hits,
        fetched_documents=fetched_documents,
        read_windows=read_windows,
    ).model_dump(mode="json")
    usage_summary = getattr(llm_client, "usage_summary", None)
    llm_cost_ledger = usage_summary() if callable(usage_summary) else {
        "call_count": len(getattr(llm_client, "calls", [])) if llm_client is not None else 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "estimated_cost": 0.0,
        "retry_count": 0,
        "calls": [],
    }
    mark_plan_phase(plan, "DELIVERY", "COMPLETE")
    research_artifact_candidate = build_research_artifact_candidate(
        task_input=task_input,
        plan=plan,
        report_structure=report_structure,
        report_markdown=report_markdown,
        citations=citations_payload,
        generated_ref_id=task_input.target_id,
        resume_context_summary=resume_context_summary,
    )
    checkpoint_candidate = {
        "schema_version": "research-checkpoint.v2",
        "checkpoint_no": max(1, len(loop_rounds_payload)),
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "research_intent": task_input.input_payload.research_intent.model_dump(mode="json"),
        "search_hits": [
            hit.model_dump(mode="json")
            for hit in search_hits
        ],
        "fetched_documents": [
            document.model_dump(mode="json")
            for document in fetched_documents
        ],
        "read_windows": [
            window.model_dump(mode="json")
            for window in read_windows
        ],
        "evidence_cards": [
            card.model_dump(mode="json")
            for card in evidence_cards
        ],
        "branch_decisions": [
            branch_decision.model_dump(mode="json")
            for branch_decision in branch_decisions
        ],
        "loop_rounds": loop_rounds_payload,
        "loop_decision": loop_decision_payload,
        "tool_traces": tool_traces_payload,
        "state_ledger": ledger.model_dump(mode="json"),
        "intent_completion_contract": intent_completion_contract,
        "local_verifier": local_result.model_dump(mode="json"),
        "global_verifier": global_result.model_dump(mode="json"),
        "research_intent_alignment": research_intent_alignment,
        "counterfactual_summary": counterfactual_summary,
        "resume_context_summary": resume_context_summary,
        "harness_control_state": harness_control_state,
        "audit_summaries": audit_summaries,
        "toolbox_summary": toolbox_summary,
        "citation_verification": citation_verification,
        "report_revision_loop": report_revision_loop,
        "llm_cost_ledger": llm_cost_ledger,
        "evidence_horizon_decisions": list(loop_result.artifacts.evidence_horizon_decisions),
        "premature_commitment_guard": premature_commitment_guard,
        "plan_horizon": list(plan.plan_horizon),
        "plan_revision": plan.plan_revision,
        "replan_history": list(plan.replan_history),
    }
    result_title = str(research_artifact_candidate.get("title") or task_input.input_payload.question.strip()[:120] or "Research Report")
    report_source_candidate = {
        "title": str(research_artifact_candidate.get("title") or result_title),
        "source_type": "GENERATED_RESEARCH_REPORT",
        "generated_by": "research_agent",
        "generated_ref_type": "RESEARCH_RUN",
        "generated_ref_id": task_input.target_id,
        "content_markdown": str(research_artifact_candidate.get("content_markdown") or report_markdown),
        "citation_count": int(research_artifact_candidate.get("citation_count") or len(citations_payload)),
    }
    result = ResearchTaskResult(
        result_title=result_title,
        result_payload={
            "schema_version": "research-result.v2",
            "report_markdown": report_markdown,
            "report_structure": report_structure,
            "research_artifact_candidate": research_artifact_candidate,
            "research_intent": task_input.input_payload.research_intent.model_dump(mode="json"),
            "query_set": plan.query_set,
            "report_sections": plan.report_sections,
            "search_hits": [
                hit.model_dump(mode="json")
                for hit in search_hits
            ],
            "fetched_documents": [
                document.model_dump(mode="json")
                for document in fetched_documents
            ],
            "read_windows": [
                window.model_dump(mode="json")
                for window in read_windows
            ],
            "evidence_cards": [
                card.model_dump(mode="json")
                for card in evidence_cards
            ],
            "branch_decisions": [
                branch_decision.model_dump(mode="json")
                for branch_decision in branch_decisions
            ],
            "state_ledger": ledger.model_dump(mode="json"),
            "intent_completion_contract": intent_completion_contract,
            "local_verifier": local_result.model_dump(mode="json"),
            "global_verifier": global_result.model_dump(mode="json"),
            "research_intent_alignment": research_intent_alignment,
            "counterfactual_summary": counterfactual_summary,
            "stop_contract": plan.stop_contract,
            "loop_rounds": loop_rounds_payload,
            "loop_decision": loop_decision_payload,
            "verifier_gate_policy": verifier_gate_policy,
            "audit_summaries": audit_summaries,
            "toolbox_summary": toolbox_summary,
            "citation_verification": citation_verification,
            "report_revision_loop": report_revision_loop,
            "llm_cost_ledger": llm_cost_ledger,
            "evidence_horizon_decisions": list(loop_result.artifacts.evidence_horizon_decisions),
            "premature_commitment_guard": premature_commitment_guard,
            "plan_horizon": list(plan.plan_horizon),
            "plan_revision": plan.plan_revision,
            "replan_history": list(plan.replan_history),
            "tool_traces": tool_traces_payload,
            "research_checkpoint_candidate": checkpoint_candidate,
            "report_source_candidate": report_source_candidate,
            "resume_context_summary": resume_context_summary,
            "harness_control_state": harness_control_state,
            "harness_trace": [
                trace.model_dump(mode="json")
                for trace in harness_trace
            ],
            "harness_summary": harness.summarize_execution(
                traces=harness_trace,
                plan=plan,
                fetched_documents=fetched_documents,
                search_hits=search_hits,
                read_windows=read_windows,
                ledger=ledger,
                branch_decisions=branch_decisions,
                local_result=local_result,
                global_result=global_result,
                final_decision=loop_result.final_decision,
                loop_round_count=len(loop_result.rounds),
                counterfactual_summary=counterfactual_summary,
                resume_context_summary=resume_context_summary,
                resume_checkpoint_payload=task_input.input_payload.resume_checkpoint,
            ),
        },
        trace_summary=(
            "research harness executed: plan -> bounded loop runtime -> search adapters "
            "-> fetch adapters -> read adapters -> evidence cards -> table-as-state ledger -> branch recovery "
            "-> dual verifier -> report writer"
        ),
        citations=citations_payload,
    )
    return events, result
