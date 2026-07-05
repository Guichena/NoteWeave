from __future__ import annotations

from app.loop_runtime import evaluate_loop_decision, run_research_loop
from app.models import GlobalVerifierResult, ResearchTaskInput
from app.planner import build_research_plan
from app.runner import run_research_task


def _build_task_input(sample_text: str = "Stable evidence supports verifier gated synthesis.") -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-loop-1",
            "workspace_id": "ws-1",
            "target_id": "run-loop-1",
            "source_scope": [
                {
                    "source_id": "src-loop",
                    "title": "Loop Evidence Source",
                    "summary": sample_text,
                    "sample_text": sample_text,
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Do not synthesize without evidence."],
            },
            "input_payload": {
                "question": "How should the research loop stop?",
                "profile_key": "DEFAULT",
            },
        }
    )


def test_loop_runtime_should_default_to_at_most_two_rounds() -> None:
    task_input = _build_task_input(
        "However, this source reports a conflict risk that needs counterfactual verification."
    )
    plan = build_research_plan(task_input)

    result = run_research_loop(task_input, plan)

    assert plan.stop_contract["max_loop_rounds"] == 2
    assert len(result.rounds) == 2
    assert result.rounds[0].loop_decision.decision == "COUNTERFACTUAL_RECHECK"
    assert result.final_decision.decision == "WRITE_WITH_GUARDRAILS"


def test_first_round_without_search_hits_should_request_scope_expansion() -> None:
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-loop-empty",
            "workspace_id": "ws-1",
            "target_id": "run-loop-empty",
            "source_scope": [],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
            },
            "input_payload": {
                "question": "What if there is no evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )
    plan = build_research_plan(task_input)

    result = run_research_loop(task_input, plan)

    assert len(result.rounds) == 1
    assert result.final_decision.decision == "EXPAND_SOURCE_SCOPE"
    assert result.final_decision.should_continue is False


def test_conflicting_evidence_should_trigger_counterfactual_recheck_before_budget_exhaustion() -> None:
    task_input = _build_task_input("The source supports the claim but contains conflict risk and uncertainty.")
    plan = build_research_plan(task_input)

    result = run_research_loop(task_input, plan)

    assert result.rounds[0].branch_decision == "COUNTERFACTUAL_RECHECK"
    assert result.rounds[0].loop_decision.decision == "COUNTERFACTUAL_RECHECK"
    assert result.rounds[0].loop_decision.should_continue is True
    assert len(result.rounds) == 2


def test_budget_exhaustion_should_force_guardrailed_write_without_infinite_loop() -> None:
    task_input = _build_task_input("However, this source has unresolved conflict risk.")
    plan = build_research_plan(task_input)
    plan.stop_contract["max_loop_rounds"] = 1

    result = run_research_loop(task_input, plan)

    assert len(result.rounds) == 1
    assert result.final_decision.decision == "WRITE_WITH_GUARDRAILS"
    assert result.final_decision.should_continue is False


def test_evaluate_loop_decision_should_search_more_when_reading_has_no_evidence() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    global_result = GlobalVerifierResult(
        status="WARN",
        decision="WRITE_WITH_GUARDRAILS",
        summary="missing evidence",
        recovery_actions=[],
    )

    decision = evaluate_loop_decision(
        plan=plan,
        round_no=1,
        search_hit_count=1,
        read_window_count=1,
        evidence_card_count=0,
        has_conflict=False,
        global_result=global_result,
    )

    assert decision.decision == "EXTRACT_AGAIN"
    assert decision.should_continue is True


def test_runner_should_include_loop_runtime_summary() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)

    assert result.result_payload["loop_rounds"][0]["round_no"] == 1
    assert result.result_payload["loop_decision"]["decision"] in {
        "SYNTHESIZE_REPORT",
        "WRITE_WITH_GUARDRAILS",
    }
