from app.models import ResearchTaskInput
from app.planner import build_research_plan
from app.runner import run_research_task
from app.search import run_workspace_search
from app.reader import open_read_windows
from app.extractor import extract_evidence_cards


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
    assert plan.stop_contract["global_search_limit"] == 8
    assert plan.stop_contract["tool_response_retention_budget"] == 5
    assert "coverage_gap" in plan.stop_contract["search_angles"]
    assert any("coverage gap check" in query for query in plan.query_set)
    assert any("counterfactual evidence check" in query for query in plan.query_set)
    assert "support_level" in plan.state_columns


def test_research_loop_should_materialize_search_read_and_extract_objects() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    read_windows = open_read_windows(task_input, plan, search_hits)
    evidence_cards = extract_evidence_cards(task_input, plan, read_windows)

    assert search_hits[0].hit_id == "hit-1"
    assert search_hits[0].source_title == "Alpha Source"
    assert search_hits[0].query in plan.query_set
    assert search_hits[0].search_angle in {"direct", "source_scoped"}
    assert "summary" in search_hits[0].matched_fields
    assert search_hits[0].coverage_score > 0
    assert read_windows[0].window_id == "window-1"
    assert read_windows[0].source_id == "src-1"
    assert "Alpha source suggests" in read_windows[0].window_text
    assert evidence_cards[0].evidence_id == "ev-1"
    assert evidence_cards[0].source_id == "src-1"
    assert evidence_cards[0].support_score >= 0.7


def test_workspace_search_should_use_sample_text_when_summary_is_blank() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["source_scope"] = [
        {
            "source_id": "src-window",
            "title": "Window Only Source",
            "summary": "",
            "sample_text": "Window text contains the only concrete research evidence.",
        }
    ]
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)

    assert search_hits[0].source_title == "Window Only Source"
    assert "only concrete research evidence" in search_hits[0].snippet
    assert search_hits[0].confidence_score >= 0.6


def test_workspace_search_should_rank_relevant_source_by_query_coverage() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["source_scope"] = [
        {
            "source_id": "src-low",
            "title": "General Workspace Notes",
            "summary": "This source is broad and does not mention verifier evidence windows.",
        },
        {
            "source_id": "src-high",
            "title": "Verified Evidence Windows",
            "summary": (
                "AlphaResearch should focus on verified evidence windows, "
                "support levels, and local verifier checks."
            ),
        },
    ]
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)

    assert search_hits[0].source_id == "src-high"
    assert search_hits[0].matched_fields
    assert search_hits[0].coverage_score >= search_hits[1].coverage_score


def test_run_research_task_should_emit_full_phase_sequence_and_report() -> None:
    task_input = _build_task_input()
    events, result = run_research_task(task_input)
    assert [event.phase for event in events] == [
        "PLANNING",
        "SEARCHING",
        "READING",
        "EXTRACTING",
        "VERIFYING",
        "WRITING",
    ]
    assert events[-1].progress_percent == 100
    assert result.result_type == "RESEARCH_REPORT"
    assert "report_markdown" in result.result_payload
    assert "AlphaResearch should focus on what?" in result.result_payload["report_markdown"]
    assert result.result_payload["search_hits"][0]["source_title"] == "Alpha Source"
    assert result.result_payload["read_windows"][0]["source_id"] == "src-1"
    assert result.result_payload["evidence_cards"][0]["evidence_id"] == "ev-1"
    assert result.result_payload["branch_decisions"][0]["decision"] == "NO_BRANCH"
    assert result.result_payload["state_ledger"]["rows"][0]["source_title"] == "Alpha Source"
    assert result.result_payload["state_ledger"]["rows"][0]["evidence_id"] == "ev-1"
    assert result.result_payload["local_verifier"]["status"] in {"PASS", "WARN"}
    assert result.result_payload["global_verifier"]["decision"] in {
        "READY_TO_WRITE",
        "WRITE_WITH_GUARDRAILS",
    }
    assert result.citations[0]["title"] == "Alpha Source"
    assert result.citations[0]["evidence_id"] == "ev-1"


def test_research_task_should_create_recovery_branch_when_evidence_is_missing() -> None:
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-r-empty",
            "workspace_id": "ws-1",
            "target_id": "run-empty",
            "source_scope": [],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Do not synthesize facts without opened evidence windows."],
            },
            "input_payload": {
                "question": "What should be researched without sources?",
                "profile_key": "DEFAULT",
            },
        }
    )
    _events, result = run_research_task(task_input)
    branch_decision = result.result_payload["branch_decisions"][0]
    assert branch_decision["decision"] == "EXPAND_SOURCE_SCOPE"
    assert branch_decision["branch_reason"] == "NO_SEARCH_HITS"
    assert "attach at least one workspace source" in branch_decision["recovery_actions"][0]
    assert result.result_payload["global_verifier"]["decision"] == "WRITE_WITH_GUARDRAILS"
