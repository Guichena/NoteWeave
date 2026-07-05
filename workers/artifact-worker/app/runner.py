from __future__ import annotations

from app.compiler import build_execution_plan
from app.composer import compose_sections, render_markdown
from app.models import ArtifactProgressEvent, ArtifactTaskInput, ArtifactTaskResult
from app.repair import repair_sections
from app.verifier import verify_artifact_output


PHASE_SEQUENCE = [
    ("RESOLVING", 10, "artifact action resolved"),
    ("ACQUIRING", 30, "source bundle compiled"),
    ("COMPOSING", 60, "section drafts generated"),
    ("VERIFYING", 85, "schema gate and local repair completed"),
    ("EXPORTING", 100, "artifact draft exported"),
]


def run_artifact_task(task_input: ArtifactTaskInput) -> tuple[list[ArtifactProgressEvent], ArtifactTaskResult]:
    plan = build_execution_plan(task_input)
    drafted_sections = compose_sections(task_input, plan)
    repaired_sections, repaired_checks = repair_sections(
        drafted_sections,
        plan,
        task_input.control_pack.forbidden_patterns,
    )

    events = [
        ArtifactProgressEvent(
            phase=phase,
            progress_percent=progress_percent,
            message=message,
            metrics={
                "outline_sections": len(plan.outline),
                "source_count": len(task_input.source_scope),
                "repaired_checks": len(repaired_checks),
            },
        )
        for phase, progress_percent, message in PHASE_SEQUENCE
    ]

    result = ArtifactTaskResult(
        result_title=f"{plan.action_key.title()} Draft",
        result_payload={
            "markdown": render_markdown(task_input, plan, repaired_sections),
            "execution_plan": plan.model_dump(mode="json"),
            "sections": [section.model_dump(mode="json") for section in repaired_sections],
        },
        trace_summary=(
            "artifact runtime executed: resolve action -> compile sources -> compose sections "
            "-> schema gate -> local repair -> export"
        ),
        citations=[
            {"title": item.title, "source_id": item.source_id}
            for item in task_input.source_scope[:3]
        ],
    )
    verification = verify_artifact_output(result, plan, repaired_checks)
    result.result_payload["verification"] = verification.model_dump(mode="json")
    return events, result
