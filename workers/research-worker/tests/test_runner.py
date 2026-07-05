from app.models import ResearchTaskInput
from app.planner import build_research_plan
from app.runner import run_research_task


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-r-1",
            "workspace_id": "ws-1",
            "target_id": "run-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Alpha Source",
                    "summary": "Alpha source suggests the workspace should prioritize verified evidence windows.",
                }
            ],
            "context_snapshot": {"context_snapshot_id": "ctx-1"},
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "style_constraints": ["Lead with conclusions."],
                "structure_constraints": ["Use question-findings-evidence-next-actions."],
                "terminology_policy": ["Use the term research workspace consistently."],
                "forbidden_patterns": [],
                "evidence_policy": ["Memory must not replace evidence retrieval."],
                "interaction_policy": [],
                "review_checklist": [],
                "memory_object_ids": ["mem-1"],
            },
            "input_payload": {
                "question": "AlphaResearch should focus on what?",
                "profile_key": "DEFAULT",
                "context_snapshot_id": "ctx-1",
            },
        }
    )


def test_build_research_plan_should_compile_query_set_and_stop_contract() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    assert plan.normalized_question == "AlphaResearch should focus on what?"
    assert plan.query_set[0] == "AlphaResearch should focus on what?"
    assert "Alpha Source" in plan.query_set[1]
    assert plan.stop_contract["must_respect_evidence_policy"] is True
    assert "support_level" in plan.state_columns


def test_run_research_task_should_emit_full_phase_sequence_and_report() -> None:
    task_input = _build_task_input()
    events, result = run_research_task(task_input)
    assert [event.phase for event in events] == [
        "PLANNING",
        "SEARCHING",
        "READING",
        "VERIFYING",
        "WRITING",
    ]
    assert events[-1].progress_percent == 100
    assert result.result_type == "RESEARCH_REPORT"
    assert "report_markdown" in result.result_payload
    assert "AlphaResearch should focus on what?" in result.result_payload["report_markdown"]
    assert result.result_payload["state_ledger"]["rows"][0]["source_title"] == "Alpha Source"
    assert result.result_payload["local_verifier"]["status"] in {"PASS", "WARN"}
    assert result.result_payload["global_verifier"]["decision"] in {
        "READY_TO_WRITE",
        "WRITE_WITH_GUARDRAILS",
    }
    assert result.citations[0]["title"] == "Alpha Source"
