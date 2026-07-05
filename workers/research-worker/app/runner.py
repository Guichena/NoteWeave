from __future__ import annotations

from app.models import ResearchProgressEvent, ResearchTaskInput, ResearchTaskResult
from app.planner import build_research_plan
from app.reporter import write_research_report
from app.state import build_state_ledger
from app.verifier import run_global_verifier, run_local_verifier


PHASE_SEQUENCE = [
    ("PLANNING", 10, "research plan compiled"),
    ("SEARCHING", 30, "query bundle resolved"),
    ("READING", 55, "table-as-state ledger assembled"),
    ("VERIFYING", 80, "dual verifier completed"),
    ("WRITING", 100, "research report generated"),
]


def run_research_task(task_input: ResearchTaskInput) -> tuple[list[ResearchProgressEvent], ResearchTaskResult]:
    plan = build_research_plan(task_input)
    ledger = build_state_ledger(task_input, plan)
    local_result = run_local_verifier(task_input, plan, ledger)
    global_result = run_global_verifier(local_result, ledger)

    events = [
        ResearchProgressEvent(
            phase=phase,
            progress_percent=progress_percent,
            message=message,
            metrics={
                "query_count": len(plan.query_set),
                "source_count": len(task_input.source_scope),
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
        local_result,
        global_result,
    )
    result = ResearchTaskResult(
        result_title=task_input.input_payload.question.strip()[:120] or "Research Report",
        result_payload={
            "report_markdown": report_markdown,
            "query_set": plan.query_set,
            "report_sections": plan.report_sections,
            "state_ledger": ledger.model_dump(mode="json"),
            "local_verifier": local_result.model_dump(mode="json"),
            "global_verifier": global_result.model_dump(mode="json"),
            "stop_contract": plan.stop_contract,
        },
        trace_summary=(
            "research harness executed: plan -> query bundle -> table-as-state ledger "
            "-> dual verifier -> report writer"
        ),
        citations=[
            {"title": row.source_title, "source_id": row.source_id}
            for row in ledger.rows[:3]
        ],
    )
    return events, result
