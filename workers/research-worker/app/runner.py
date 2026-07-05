from __future__ import annotations

from app.harness import build_harness, build_step_trace
from app.llm_client import build_default_llm_client
from app.loop_runtime import run_research_loop
from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult
from app.reporter import write_research_report


PHASE_SEQUENCE = [
    ("PLANNING", 10, "research plan compiled"),
    ("SEARCHING", 28, "research search adapter hits resolved"),
    ("READING", 48, "bounded read windows opened"),
    ("EXTRACTING", 68, "evidence cards extracted"),
    ("VERIFYING", 86, "dual verifier completed"),
    ("WRITING", 100, "research report generated"),
]


def run_research_task(task_input: ResearchTaskInput) -> tuple[list[ResearchProgressEvent], ResearchTaskResult]:
    harness = build_harness(task_input)
    llm_client = build_default_llm_client()
    plan, harness_trace = harness.plan(task_input)
    loop_result = run_research_loop(task_input, plan, llm_client=llm_client)
    plan = loop_result.plan
    search_hits = loop_result.artifacts.search_hits
    read_windows = loop_result.artifacts.read_windows
    evidence_cards = loop_result.artifacts.evidence_cards
    ledger = loop_result.artifacts.ledger
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
                "loop_decision": loop_result.final_decision.decision,
                "loop_rounds": len(loop_result.rounds),
            },
            warnings=list(local_result.warnings) + list(ledger.unresolved_questions),
        )
    )

    events = [
        ResearchProgressEvent(
            phase=phase,
            progress_percent=progress_percent,
            message=message,
            metrics={
                "query_count": len(plan.query_set),
                "source_count": len(task_input.source_scope),
                "search_hits": len(search_hits),
                "read_windows": len(read_windows),
                "evidence_cards": len(evidence_cards),
                "ledger_rows": len(ledger.rows),
                "local_status": local_result.status,
                "global_status": global_result.status,
                "loop_rounds": len(loop_result.rounds),
                "loop_decision": loop_result.final_decision.decision,
            },
        )
        for phase, progress_percent, message in PHASE_SEQUENCE
    ]

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
    )
    harness_trace.append(
        build_step_trace(
            phase="WRITING",
            message="research report generated",
            inputs={
                "ledger_rows": len(ledger.rows),
                "global_decision": global_result.decision,
            },
            outputs={
                "report_generated": bool(report_markdown.strip()),
                "report_characters": len(report_markdown),
                "citation_count": min(3, len(ledger.rows)),
            },
        )
    )
    result = ResearchTaskResult(
        result_title=task_input.input_payload.question.strip()[:120] or "Research Report",
        result_payload={
            "report_markdown": report_markdown,
            "query_set": plan.query_set,
            "report_sections": plan.report_sections,
            "search_hits": [
                hit.model_dump(mode="json")
                for hit in search_hits
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
            "local_verifier": local_result.model_dump(mode="json"),
            "global_verifier": global_result.model_dump(mode="json"),
            "stop_contract": plan.stop_contract,
            "loop_rounds": [
                round_summary.model_dump(mode="json")
                for round_summary in loop_result.rounds
            ],
            "loop_decision": loop_result.final_decision.model_dump(mode="json"),
            "harness_trace": [
                trace.model_dump(mode="json")
                for trace in harness_trace
            ],
            "harness_summary": harness.summarize_trace(harness_trace),
        },
        trace_summary=(
            "research harness executed: plan -> bounded loop runtime -> search adapters "
            "-> read adapters -> evidence cards -> table-as-state ledger -> branch recovery "
            "-> dual verifier -> report writer"
        ),
        citations=[
            {
                "title": row.source_title,
                "source_id": row.source_id,
                "evidence_id": row.evidence_id,
                "quote": row.quote_text,
            }
            for row in ledger.rows[:3]
        ],
    )
    return events, result
