from __future__ import annotations

from app.branch import plan_branch_recovery
from app.extractor import extract_evidence_cards
from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult
from app.planner import build_research_plan
from app.reader import open_read_windows
from app.reporter import write_research_report
from app.search import run_workspace_search
from app.state import build_state_ledger
from app.verifier import run_global_verifier, run_local_verifier


PHASE_SEQUENCE = [
    ("PLANNING", 10, "research plan compiled"),
    ("SEARCHING", 28, "workspace search hits resolved"),
    ("READING", 48, "bounded read windows opened"),
    ("EXTRACTING", 68, "evidence cards extracted"),
    ("VERIFYING", 86, "dual verifier completed"),
    ("WRITING", 100, "research report generated"),
]


def run_research_task(task_input: ResearchTaskInput) -> tuple[list[ResearchProgressEvent], ResearchTaskResult]:
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    read_windows = open_read_windows(task_input, plan, search_hits)
    evidence_cards = extract_evidence_cards(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, evidence_cards)
    local_result = run_local_verifier(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        evidence_cards,
    )
    branch_decisions = plan_branch_recovery(
        search_hits,
        read_windows,
        evidence_cards,
        local_result,
    )
    global_result = run_global_verifier(local_result, ledger, branch_decisions)

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
        },
        trace_summary=(
            "research harness executed: plan -> workspace search -> bounded read windows "
            "-> evidence cards -> table-as-state ledger -> branch recovery "
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
