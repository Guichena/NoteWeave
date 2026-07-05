from app.compiler import build_execution_plan
from app.models import ArtifactTaskInput
from app.runner import run_artifact_task


def _build_task_input() -> ArtifactTaskInput:
    return ArtifactTaskInput.model_validate(
        {
            "task_id": "task-a-1",
            "workspace_id": "ws-1",
            "target_id": "artifact-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Artifact Source",
                    "summary": "Artifact generation context",
                }
            ],
            "context_snapshot": {"context_snapshot_id": "ctx-1"},
            "control_pack": {
                "pack_type": "artifact",
                "target_key": "REPORT",
                "task_neighborhood": "ARTIFACT_REPORT",
                "style_constraints": ["Lead with conclusions."],
                "structure_constraints": ["Use problem-method-impact structure."],
                "terminology_policy": ["Use research workspace consistently."],
                "forbidden_patterns": ["deprecated wording"],
                "evidence_policy": ["Memory must not replace artifact source material."],
                "interaction_policy": [],
                "review_checklist": [],
                "memory_object_ids": ["mem-1"],
            },
            "input_payload": {
                "action_key": "report",
                "style_profile_key": "interview",
                "context_snapshot_id": "ctx-1",
            },
        }
    )


def test_build_execution_plan_should_compile_action_outline_and_schema_gate() -> None:
    task_input = _build_task_input()
    plan = build_execution_plan(task_input)
    assert plan.action_key == "REPORT"
    assert plan.style_profile_key == "INTERVIEW"
    assert plan.schema_gate_status == "PASSED"
    assert "Problem" in plan.outline
    assert "Every output must preserve the requested outline." in plan.schema_gate_rules


def test_run_artifact_task_should_emit_full_phase_sequence_and_markdown() -> None:
    task_input = _build_task_input()
    events, result = run_artifact_task(task_input)
    assert [event.phase for event in events] == [
        "RESOLVING",
        "ACQUIRING",
        "COMPOSING",
        "VERIFYING",
        "EXPORTING",
    ]
    assert events[-1].progress_percent == 100
    assert result.result_type == "MARKDOWN"
    assert result.result_payload["execution_plan"]["action_key"] == "REPORT"
    assert result.result_payload["sections"][0]["heading"] == "Problem"
    assert result.result_payload["verification"]["status"] in {"PASS", "WARN"}
    assert "Artifact Source" in result.result_payload["markdown"]
    assert result.citations[0]["title"] == "Artifact Source"
