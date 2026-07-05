from app.harness import build_harness
from app.models import ResearchTaskInput
from app.runner import run_research_task


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-harness-1",
            "workspace_id": "ws-1",
            "target_id": "run-harness-1",
            "source_scope": [
                {
                    "source_id": "src-harness",
                    "title": "Harness Source",
                    "summary": "Harness tracing should keep research state observable without storing large raw text.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Every report claim must be backed by traceable evidence."],
            },
            "input_payload": {
                "question": "What should the research harness trace?",
                "profile_key": "DEFAULT",
            },
        }
    )


def test_harness_should_emit_planning_trace() -> None:
    task_input = _build_task_input()
    harness = build_harness(task_input)

    plan, traces = harness.plan(task_input)

    assert plan.normalized_question == "What should the research harness trace?"
    assert harness.mode == "RULE"
    assert traces[0].phase == "PLANNING"
    assert traces[0].status == "success"
    assert traces[0].outputs["query_count"] == len(plan.query_set)
    assert "raw_text" not in traces[0].outputs


def test_harness_should_fallback_to_rule_mode_without_llm_config() -> None:
    task_input = _build_task_input()
    harness = build_harness(task_input)

    assert harness.mode == "RULE"
    assert harness.llm_enabled is False


def test_runner_should_include_harness_trace_in_result_payload() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)
    traces = result.result_payload["harness_trace"]

    assert [trace["phase"] for trace in traces] == [
        "PLANNING",
        "SEARCHING",
        "READING",
        "EXTRACTING",
        "VERIFYING",
        "WRITING",
    ]
    assert traces[1]["outputs"]["search_hits"] == 1
    assert traces[-1]["outputs"]["report_generated"] is True
    assert all("window_text" not in trace["outputs"] for trace in traces)
