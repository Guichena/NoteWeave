from __future__ import annotations

from app.config import load_settings
from app.models import ResearchPlan, ResearchStepTrace, ResearchTaskInput
from app.planner import build_research_plan


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


def build_harness(task_input: ResearchTaskInput) -> ResearchHarness:
    del task_input
    settings = load_settings()
    llm_enabled = bool(getattr(settings, "llm_api_key", "").strip())
    return ResearchHarness(mode="LLM" if llm_enabled else "RULE", llm_enabled=llm_enabled)


def build_step_trace(
    phase: str,
    message: str,
    inputs: dict[str, object] | None = None,
    outputs: dict[str, object] | None = None,
    warnings: list[str] | None = None,
    status: str = "success",
) -> ResearchStepTrace:
    return ResearchStepTrace(
        trace_id=f"trace-{phase.lower()}",
        phase=phase,
        status=status,
        message=message,
        inputs=_sanitize_payload(inputs or {}),
        outputs=_sanitize_payload(outputs or {}),
        warnings=warnings or [],
    )


def _sanitize_payload(payload: dict[str, object]) -> dict[str, object]:
    blocked_keys = {"raw_text", "window_text", "full_text", "content"}
    return {
        key: value
        for key, value in payload.items()
        if key not in blocked_keys
    }
