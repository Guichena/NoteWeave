from __future__ import annotations

import pytest

pytestmark = pytest.mark.usefixtures("fake_default_llm")

from app.models import ResearchTaskInput
from app.planner import build_research_plan
from app.runner import run_research_task
from app.search import run_workspace_search
from app.reader import open_read_windows
from app.extractor import extract_evidence_cards


def _build_task_input(
    sample_text: str = "Alpha source suggests the workspace should prioritize verified evidence windows.",
) -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-r-1",
            "workspace_id": "ws-1",
            "target_id": "run-1",
            "source_scope": [
                {
                    "source_id": "src-1",
                    "title": "Alpha Source",
                    "summary": sample_text,
                }
            ],
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
                "research_intent": {
                    "research_goal": "Identify the verifier-approved summary direction for AlphaResearch.",
                    "deliverable_format": "Evidence-backed executive brief",
                    "constraints": [
                        "Keep the answer anchored to explicit evidence windows.",
                        "Call out unresolved conflicts separately.",
                    ],
                    "time_range": "Focus on the current project cycle.",
                    "depth": "DEEP",
                },
            },
        }
    )


def test_build_research_plan_should_compile_query_set_and_stop_contract() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    assert plan.normalized_question == "AlphaResearch should focus on what?"
    assert plan.query_set[0] == "AlphaResearch should focus on what?"
    assert any("research goal" in query for query in plan.query_set)
    assert any("deliverable" in query for query in plan.query_set)
    assert any("constraint" in query for query in plan.query_set)
    assert any("time range" in query for query in plan.query_set)
    assert any("Alpha Source" in query for query in plan.query_set)
    assert plan.stop_contract["must_respect_evidence_policy"] is True
    assert plan.stop_contract["depth"] == "DEEP"
    assert plan.stop_contract["research_goal"] == "Identify the verifier-approved summary direction for AlphaResearch."
    assert plan.stop_contract["deliverable_format"] == "Evidence-backed executive brief"
    assert plan.stop_contract["global_search_limit"] == 12
    assert plan.stop_contract["tool_response_retention_budget"] == 7
    assert plan.stop_contract["execution_profile"]["planning_mode"] == "WIDE_AND_DEEP"
    assert plan.stop_contract["execution_profile"]["search_query_budget"] >= 6
    assert plan.stop_contract["execution_profile"]["per_query_result_limit"] >= 2
    assert plan.stop_contract["execution_profile"]["query_family_budgets"]["source_scoped"] >= 1
    assert plan.stop_contract["execution_profile"]["query_family_budgets"]["deep_focus"] == 1
    assert "coverage_gap" in plan.stop_contract["search_angles"]
    assert any("coverage gap check" in query for query in plan.query_set)
    assert any("counterfactual evidence check" in query for query in plan.query_set)
    assert any("direct answer with evidence" in query for query in plan.query_set)
    assert any("verified evidence search" in query for query in plan.query_set)
    assert "support_level" in plan.state_columns or any(
        col.key == "problem" for col in plan.research_schema.columns
    ), f"P0-1: state_columns 来自 schema,应该至少包含问题维度; got={plan.state_columns}"
    assert "Research Intent" in plan.report_sections
    assert any("depth tier: DEEP" in note for note in plan.notes)
    assert plan.stop_contract["required_finding_contract"]
    assert plan.stop_contract["required_finding_contract"][0]["completion_mode"] == "ROW_EVIDENCE"
    assert "LOOP_BUDGET_EXHAUSTED_WITHOUT_VERIFIED_COVERAGE" in plan.stop_contract["abandon_conditions"]
    assert "COUNTERFACTUAL_CONFLICT_UNRESOLVED_AFTER_BUDGET" in plan.stop_contract["human_handoff_conditions"]
    assert plan.stop_contract["source_scope_count"] == 1


def test_build_research_plan_should_surface_execution_profile_by_depth() -> None:
    quick_payload = _build_task_input().model_dump(mode="json")
    quick_payload["input_payload"]["research_intent"]["depth"] = "QUICK"
    quick_task_input = ResearchTaskInput.model_validate(quick_payload)
    quick_plan = build_research_plan(quick_task_input)

    standard_payload = _build_task_input().model_dump(mode="json")
    standard_payload["input_payload"]["research_intent"]["depth"] = "STANDARD"
    standard_task_input = ResearchTaskInput.model_validate(standard_payload)
    standard_plan = build_research_plan(standard_task_input)

    deep_task_input = _build_task_input()
    deep_plan = build_research_plan(deep_task_input)

    assert quick_plan.stop_contract["execution_profile"]["planning_mode"] == "FAST_GUARDED"
    assert standard_plan.stop_contract["execution_profile"]["planning_mode"] == "BALANCED_COVERAGE"
    assert deep_plan.stop_contract["execution_profile"]["planning_mode"] == "WIDE_AND_DEEP"
    assert quick_plan.stop_contract["execution_profile"]["search_query_budget"] < standard_plan.stop_contract["execution_profile"]["search_query_budget"] < deep_plan.stop_contract["execution_profile"]["search_query_budget"]
    assert quick_plan.stop_contract["execution_profile"]["per_query_result_limit"] <= standard_plan.stop_contract["execution_profile"]["per_query_result_limit"] <= deep_plan.stop_contract["execution_profile"]["per_query_result_limit"]
    assert quick_plan.stop_contract["execution_profile"]["query_family_budgets"]["coverage_gap"] == 0
    assert standard_plan.stop_contract["execution_profile"]["query_family_budgets"]["coverage_gap"] == 1
    assert deep_plan.stop_contract["execution_profile"]["query_family_budgets"]["source_scoped"] >= 2

    assert not any("coverage gap check" in query for query in quick_plan.query_set)
    assert not any("counterfactual evidence check" in query for query in quick_plan.query_set)

    assert any("coverage gap check" in query for query in standard_plan.query_set)
    assert any("counterfactual evidence check" in query for query in standard_plan.query_set)
    assert any("direct answer with evidence" in query for query in standard_plan.query_set)
    assert any("verified evidence search" in query for query in standard_plan.query_set)

    assert any("deep focus" in query for query in deep_plan.query_set)
    assert any("triangulation" in query for query in deep_plan.query_set)
    assert deep_plan.query_set[-2].endswith("coverage gap check")
    assert deep_plan.query_set[-1].endswith("counterfactual evidence check")


def test_build_research_plan_should_use_research_type_report_sections() -> None:
    cases = [
        ("Summarize recent arxiv papers on verifier loops", "Paper Map", "Methods And Benchmarks"),
        ("Analyze the github repo architecture for verifier loops", "Repository Overview", "Architecture And Reusable Design"),
        ("Compare technical solutions for verifier loop orchestration", "Solution Landscape", "Architecture Tradeoffs"),
        ("What is verifier-centric orchestration as a concept", "Concept Definition", "Key Ideas And Examples"),
    ]
    for question, first_section, second_section in cases:
        payload = _build_task_input().model_dump(mode="json")
        payload["input_payload"]["question"] = question
        task_input = ResearchTaskInput.model_validate(payload)

        plan = build_research_plan(task_input)

        assert first_section in plan.report_sections
        assert second_section in plan.report_sections
        assert "Evidence Ledger" in plan.report_sections
        assert "Deliverable Requirements" in plan.report_sections


def test_build_research_plan_should_accept_explicit_research_type() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["research_intent"]["research_type"] = "GITHUB_REPO_ANALYSIS"
    task_input = ResearchTaskInput.model_validate(payload)

    plan = build_research_plan(task_input)

    assert plan.research_type == "GITHUB_REPO_ANALYSIS"
    assert "Repository Overview" in plan.report_sections
    assert "Architecture And Reusable Design" in plan.report_sections


def test_research_loop_should_materialize_search_read_and_extract_objects() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    read_windows = open_read_windows(task_input, plan, search_hits)
    # P0-4: 必须显式传 llm_client,否则返回空列表
    from app.llm_client import FakeLlmClient
    column_key = plan.research_schema.columns[0].key
    llm_client = FakeLlmClient(
        {
            "research.extract": (
                '{"evidence_cards":[{"window_id":"window-1","claim_text":"'
                'Alpha source suggests grounded claim","quote_text":"Alpha source '
                'suggests the workspace should prioritize verified evidence windows.",'
                f'"column_key":"{column_key}",'
                '"relation_type":"SUPPORTS","support_score":0.85,"conflict_score":0.02}]}'
            )
        }
    )
    evidence_cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    assert search_hits[0].hit_id == "hit-1"
    assert search_hits[0].source_title == "Alpha Source"
    assert search_hits[0].query in plan.query_set
    assert search_hits[0].search_angle in {"direct", "source_scoped"}
    assert "summary" in search_hits[0].matched_fields
    assert search_hits[0].coverage_score > 0
    assert read_windows[0].window_id == "window-1"
    assert read_windows[0].source_id == "src-1"
    assert "Alpha source suggests" in read_windows[0].window_text
    # P0-5: evidence_id 现在由 (window_id, position) 生成,不再是简单序号
    assert evidence_cards[0].evidence_id.startswith("ev-window-1-")
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


def test_build_research_plan_should_record_resume_checkpoint_note() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-seed-1",
        "checkpoint_no": 2,
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "active_branch_id": "branch-recovery-1",
        "final_loop_decision": "READ_MORE",
        "payload": {
            "state_ledger": {"active_branch_id": "branch-recovery-1"},
            "loop_decision": {"decision": "READ_MORE"},
        },
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)

    assert any("run-seed-1#2" in note for note in plan.notes)


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
    assert result.result_payload["fetched_documents"][0]["source_id"] == "src-1"
    assert result.result_payload["read_windows"][0]["source_id"] == "src-1"
    assert result.result_payload["evidence_cards"][0]["evidence_id"].startswith("ev-window-1-")
    assert result.result_payload["branch_decisions"][0]["decision"] == "NO_BRANCH"
    assert result.result_payload["state_ledger"]["rows"][0]["source_title"] == "Alpha Source"
    row = result.result_payload["state_ledger"]["rows"][0]
    assert row["evidence_id"].startswith("ev-window-1-")
    assert row["row_id"] == row["entity_id"]
    assert result.result_payload["state_ledger"]["rows"][0]["requirement_completion_status"] == "READY"
    assert result.result_payload["state_ledger"]["cells"][0]["requirement_completion_status"] in {"READY", "NOT_REQUIRED", "MISSING"}
    assert result.result_payload["state_ledger"]["required_finding_progress"][0]["status"] == "READY"
    assert result.result_payload["state_ledger"]["requirement_ready_row_count"] >= 1
    assert result.result_payload["local_verifier"]["status"] in {"PASS", "WARN"}
    assert result.result_payload["global_verifier"]["decision"] in {
        "READY_TO_WRITE",
        "WRITE_WITH_GUARDRAILS",
    }
    report_structure = result.result_payload["report_structure"]
    assert report_structure["verified_findings"][0]["source_title"] == "Alpha Source"
    verified_provenance = report_structure["verified_findings"][0]["provenance"]
    assert verified_provenance["source_ref"]["source_id"] == "src-1"
    assert verified_provenance["source_ref"]["source_title"] == "Alpha Source"
    assert verified_provenance["window_ref"]["window_id"] == "window-1"
    assert verified_provenance["evidence_ref"]["quote_start"] >= 0
    assert verified_provenance["evidence_ref"]["quote_end"] > verified_provenance["evidence_ref"]["quote_start"]
    assert verified_provenance["evidence_ref"]["content_sha256"]
    assert verified_provenance["row_ref"]["row_id"] == row["entity_id"]
    assert verified_provenance["row_ref"]["cell_ids"]
    assert verified_provenance["requirement_ref"]["matched_requirement_ids"]
    assert report_structure["research_intent"]["research_goal"] == "Identify the verifier-approved summary direction for AlphaResearch."
    assert report_structure["planned_report_sections"] == result.result_payload["report_sections"]
    assert "Evidence Ledger" in report_structure["planned_report_sections"]
    assert report_structure["research_intent"]["research_type"] == result.result_payload["stop_contract"]["research_type"]
    assert report_structure["research_intent"]["deliverable_format"] == "Evidence-backed executive brief"
    assert report_structure["research_intent"]["depth"] == "DEEP"
    assert report_structure["intent_completion_contract"]["status"] == "PASS"
    assert report_structure["intent_completion_contract"]["satisfied_requirement_count"] == report_structure["intent_completion_contract"]["total_requirement_count"]
    assert report_structure["intent_completion_contract"]["pending_requirement_count"] == 0
    assert all(
        requirement["status"] == "PASS"
        for requirement in report_structure["intent_completion_contract"]["requirements"]
    )
    assert report_structure["research_intent_alignment"]["status"] == "PASS"
    assert report_structure["research_intent_alignment"]["reason_code"] == "INTENT_ALIGNED"
    assert report_structure["research_intent_alignment"]["satisfied_constraint_count"] == report_structure["research_intent_alignment"]["total_constraint_count"]
    assert report_structure["counterfactual_summary"]["has_counterfactual_recheck"] is False
    assert report_structure["counterfactual_summary"]["counterfactual_branch_count"] == 0
    assert report_structure["recovery_status"]["local_verifier_status"] in {"PASS", "WARN"}
    assert report_structure["report_refinement_guard"]["status"] in {"PASS", "WARN"}
    guard_checks = {
        check["check"]: check["status"]
        for check in report_structure["report_refinement_guard"]["checks"]
    }
    assert guard_checks["conflicts_are_separated"] in {"PASS", "WARN"}
    assert guard_checks["unverified_rows_are_guarded"] in {"PASS", "WARN"}
    assert guard_checks["single_source_dominance"] in {"PASS", "WARN"}
    assert report_structure["closed_loop_state"]["recovery_targets"]["requirement_count"] == 0
    assert report_structure["closed_loop_state"]["abandon_conditions"]
    assert report_structure["closed_loop_state"]["human_handoff_conditions"]
    assert report_structure["recovery_status"]["recovery_targets"]["query_count"] == 0
    assert report_structure["next_actions"]
    assert result.result_payload["counterfactual_summary"]["has_counterfactual_recheck"] is False
    assert result.result_payload["counterfactual_summary"]["counterfactual_branch_count"] == 0
    assert result.result_payload["research_intent"]["depth"] == "DEEP"
    assert result.result_payload["state_ledger"]["intent_completion_contract"]["status"] == "PASS"
    assert result.result_payload["intent_completion_contract"]["total_requirement_count"] >= 1
    assert result.result_payload["research_intent_alignment"]["status"] == "PASS"
    assert result.result_payload["global_verifier"]["research_intent_alignment"]["reason_code"] == "INTENT_ALIGNED"
    assert result.result_payload["global_verifier"]["intent_completion_contract"]["pending_requirement_count"] == 0
    assert "## Verified Findings" in result.result_payload["report_markdown"]
    assert "## Research Intent" in result.result_payload["report_markdown"]
    assert "- Research type:" in result.result_payload["report_markdown"]
    assert "## Planned Report Sections" in result.result_payload["report_markdown"]
    assert "- Evidence Ledger" in result.result_payload["report_markdown"]
    assert "## Research Intent Completion Contract" in result.result_payload["report_markdown"]
    assert "## Recovery Status" in result.result_payload["report_markdown"]
    assert "- Abandon conditions:" in result.result_payload["report_markdown"]
    assert "- Human handoff conditions:" in result.result_payload["report_markdown"]
    assert "## Report Refinement Guard" in result.result_payload["report_markdown"]
    assert result.citations[0]["title"] == "Alpha Source"
    assert result.citations[0]["evidence_id"] == row["evidence_id"]


def test_run_research_task_should_emit_phase_specific_progress_metrics() -> None:
    task_input = _build_task_input()
    events, _result = run_research_task(task_input)

    by_phase = {event.phase: event.metrics for event in events}

    assert set(by_phase["PLANNING"]) == {"query_count", "source_count"}
    assert "search_hits" not in by_phase["PLANNING"]

    assert "search_hits" in by_phase["SEARCHING"]
    assert "read_windows" not in by_phase["SEARCHING"]

    assert "read_windows" in by_phase["READING"]
    assert "evidence_cards" not in by_phase["READING"]

    assert "evidence_cards" in by_phase["EXTRACTING"]
    assert "ledger_rows" in by_phase["EXTRACTING"]
    assert "local_status" not in by_phase["EXTRACTING"]

    assert "local_status" in by_phase["VERIFYING"]
    assert "global_status" in by_phase["VERIFYING"]
    assert "loop_rounds" not in by_phase["VERIFYING"]

    assert "loop_rounds" in by_phase["WRITING"]
    assert "loop_decision" in by_phase["WRITING"]


def test_run_research_task_should_emit_phase_specific_runtime_payload() -> None:
    task_input = _build_task_input()
    events, _result = run_research_task(task_input)

    by_phase = {event.phase: event.payload for event in events}

    assert by_phase["PLANNING"]["query_samples"]
    assert by_phase["PLANNING"]["source_samples"][0]["source_title"] == "Alpha Source"
    assert by_phase["PLANNING"]["execution_profile"]["planning_mode"] == "WIDE_AND_DEEP"
    assert "recovery_targets" not in by_phase["PLANNING"]

    assert by_phase["SEARCHING"]["tool_trace"]["phase"] == "TOOL_SEARCH_WEB"

    assert by_phase["READING"]["tool_trace"]["phase"] == "TOOL_OPEN_READ_WINDOWS"

    assert by_phase["EXTRACTING"]["tool_trace"]["phase"] == "TOOL_EXTRACT_EVIDENCE"

    assert by_phase["VERIFYING"]["tool_trace"]["phase"] == "TOOL_VERIFY_GLOBAL"

    assert by_phase["WRITING"]["loop_decision"]["decision"] in {
        "SYNTHESIZE_REPORT",
        "CONTINUE_RECOVERY",
        "WRITE_WITH_GUARDRAILS",
    }
    assert by_phase["WRITING"]["loop_round_count"] >= 1


def test_run_research_task_should_emit_execution_profile_in_stop_contract() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)

    execution_profile = result.result_payload["stop_contract"]["execution_profile"]
    assert execution_profile["planning_mode"] == "WIDE_AND_DEEP"
    assert execution_profile["coverage_gap_query_enabled"] is True
    assert execution_profile["counterfactual_query_enabled"] is True
    assert execution_profile["deep_focus_query_enabled"] is True


def test_research_task_should_emit_checkpoint_and_report_source_candidates() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)

    checkpoint = result.result_payload["research_checkpoint_candidate"]
    artifact = result.result_payload["research_artifact_candidate"]
    report_source = result.result_payload["report_source_candidate"]
    assert checkpoint["checkpoint_no"] == len(result.result_payload["loop_rounds"])
    assert checkpoint["research_intent"]["deliverable_format"] == "Evidence-backed executive brief"
    assert checkpoint["state_ledger"]["evidence_card_count"] >= 1
    assert checkpoint["state_ledger"]["required_finding_progress"][0]["status"] == "READY"
    assert checkpoint["state_ledger"]["requirement_ready_row_count"] >= 1
    assert checkpoint["state_ledger"]["intent_completion_contract"]["pending_requirement_count"] == 0
    assert checkpoint["intent_completion_contract"]["status"] == "PASS"
    assert checkpoint["counterfactual_summary"]["has_counterfactual_recheck"] is False
    assert checkpoint["counterfactual_summary"]["counterfactual_branch_count"] == 0
    assert checkpoint["loop_decision"]["decision"] in {
        "SYNTHESIZE_REPORT",
        "WRITE_WITH_GUARDRAILS",
    }
    assert artifact["artifact_type"] == "DEEP_RESEARCH_REPORT"
    assert artifact["artifact_version"] == "v1"
    assert artifact["title"] == "AlphaResearch should focus on what?"
    assert artifact["question"] == "AlphaResearch should focus on what?"
    assert artifact["generated_by"] == "research_agent"
    assert artifact["generated_ref_type"] == "RESEARCH_RUN"
    assert artifact["generated_ref_id"] == "run-1"
    assert artifact["answer_status"] == result.result_payload["report_structure"]["final_answer"]["answer_status"]
    assert artifact["source_basis"] == result.result_payload["report_structure"]["final_answer"]["source_basis"]
    assert artifact["confidence_label"] == result.result_payload["report_structure"]["final_answer"]["confidence_label"]
    assert artifact["content_markdown"] == result.result_payload["report_markdown"]
    assert artifact["report_structure"]["research_question"]["original_question"] == "AlphaResearch should focus on what?"
    assert artifact["source_foundation"]["primary_quality"]
    assert artifact["provenance_bindings"][0]["source_ref"]["source_id"] == "src-1"
    assert artifact["provenance_bindings"][0]["window_ref"]["window_id"] == "window-1"
    entity_id = checkpoint["state_ledger"]["rows"][0]["entity_id"]
    evidence_id = checkpoint["state_ledger"]["rows"][0]["evidence_id"]
    assert artifact["provenance_bindings"][0]["row_ref"]["row_id"] == entity_id
    assert artifact["provenance_bindings"][0]["requirement_ref"]["matched_requirement_ids"]
    assert artifact["citation_count"] == len(result.citations)
    assert artifact["citations"][0]["evidence_id"] == evidence_id
    # The checkpoint is the report's input snapshot; it deliberately avoids
    # recursively embedding the generated artifact back into itself.
    assert "research_artifact_candidate" not in checkpoint
    assert report_source["source_type"] == "GENERATED_RESEARCH_REPORT"
    assert report_source["generated_by"] == "research_agent"
    assert report_source["title"] == "AlphaResearch should focus on what?"
    assert "AlphaResearch should focus on what?" in report_source["content_markdown"]
    assert report_source["title"] == artifact["title"]
    assert report_source["content_markdown"] == artifact["content_markdown"]


def test_research_task_should_continue_from_resume_checkpoint_and_preserve_prior_assets() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-seed-1",
        "checkpoint_no": 1,
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "active_branch_id": "branch-main",
        "final_loop_decision": "READ_MORE",
        "payload": {
            "search_hits": [
                {
                    "hit_id": "hit-resume-1",
                    "source_id": "src-resume-1",
                    "source_title": "Resume Source",
                    "query": "AlphaResearch should focus on what? :: recovery round 1",
                    "rank": 1,
                    "snippet": "Checkpoint evidence remains valid for continuation.",
                    "confidence_score": 0.88,
                    "retrieval_reason": "resume seed hit",
                    "search_angle": "counterfactual",
                    "matched_fields": ["summary"],
                    "coverage_score": 0.8,
                    "url": "https://example.com/resume-hit",
                    "provider": "workspace",
                    "adapter": "workspace",
                }
            ],
            "read_windows": [
                {
                    "window_id": "window-resume-1",
                    "hit_id": "hit-resume-1",
                    "source_id": "src-resume-1",
                    "source_title": "Resume Source",
                    "query": "AlphaResearch should focus on what? :: recovery round 1",
                    "read_focus": "Preserve checkpoint continuity",
                    "window_text": "Checkpoint evidence remains valid for continuation.",
                    "retention_reason": "RECOVERY_ANCHOR",
                    "token_estimate": 48,
                    "url": "https://example.com/resume-hit",
                    "provider": "workspace",
                    "adapter": "workspace",
                    "snapshot_status": "FALLBACK",
                    "snapshot_key": "snapshot-resume-1",
                }
            ],
            "evidence_cards": [
                {
                    "evidence_id": "ev-resume-1",
                    "window_id": "window-resume-1",
                    "source_id": "src-resume-1",
                    "source_title": "Resume Source",
                    "claim_text": "Checkpoint evidence should stay visible after resume.",
                    "quote_text": "Checkpoint evidence remains valid for continuation.",
                    "relation_type": "SUPPORTS",
                    "support_score": 0.86,
                    "conflict_score": 0.04,
                }
            ],
            "state_ledger": {
                "columns": [
                    "source_title",
                    "search_query",
                    "read_focus",
                    "evidence_id",
                    "claim_text",
                    "evidence_excerpt",
                    "relation_type",
                    "support_score",
                    "support_level",
                    "verifier_note",
                ],
                "rows": [
                    {
                        "row_id": "row-resume-1",
                        "source_id": "src-resume-1",
                        "source_title": "Resume Source",
                        "search_query": "AlphaResearch should focus on what? :: recovery round 1",
                        "read_focus": "Preserve checkpoint continuity",
                        "evidence_id": "ev-resume-1",
                        "claim_text": "Checkpoint evidence should stay visible after resume.",
                        "quote_text": "Checkpoint evidence remains valid for continuation.",
                        "evidence_excerpt": "Checkpoint evidence remains valid for continuation.",
                        "relation_type": "SUPPORTS",
                        "support_score": 0.86,
                        "conflict_score": 0.04,
                        "row_status": "VERIFIED",
                        "branch_id": "branch-main",
                        "source_quality": "WORKSPACE_SOURCE",
                        "verification_status": "LOCAL_PASS",
                        "support_level": "SUPPORTED",
                        "verifier_note": "Resume checkpoint already holds verified evidence.",
                        "repair_hint": "",
                    }
                ],
                "cells": [
                    {
                        "cell_id": "row-resume-1:claim_text",
                        "row_id": "row-resume-1",
                        "column_key": "claim_text",
                        "candidate_value": "Checkpoint evidence should stay visible after resume.",
                        "status": "VERIFIED",
                        "confidence": 0.86,
                        "evidence_refs": ["ev-resume-1"],
                        "branch_id": "branch-main",
                        "last_verifier_decision": "PASS",
                        "repair_count": 0,
                    }
                ],
                "branches": [
                    {
                        "branch_id": "branch-main",
                        "parent_branch_id": "",
                        "branch_reason": "MAINLINE",
                        "hypothesis_summary": "Primary resume branch.",
                        "status": "MAINLINE",
                        "created_round": 1,
                    }
                ],
                "verifier_decisions": [
                    {
                        "decision_scope": "LOCAL",
                        "decision_type": "RECOVER",
                        "reason_code": "NO_READ_WINDOWS",
                        "target_id": "",
                        "evidence_ids": [],
                        "action": "increase read-window retention budget",
                        "status": "WARN",
                        "notes": [],
                    }
                ],
                "unresolved_questions": [
                    "Search hits exist, but one more read window should be opened before synthesis."
                ],
                "row_status_summary": {"VERIFIED": 1},
                "search_hit_count": 1,
                "read_window_count": 1,
                "evidence_card_count": 1,
                "verified_row_count": 1,
                "conflicted_row_count": 0,
                "coverage_score": 1.0,
                "active_branch_id": "branch-main",
            },
            "local_verifier": {
                "status": "WARN",
                "passed_checks": ["question normalized", "query bundle compiled"],
                "warnings": ["checkpoint requested one more read round before synthesis"],
                "recovery_actions": ["open one more read window before synthesis"],
                "decision_records": [
                    {
                        "decision_scope": "LOCAL",
                        "decision_type": "RECOVER",
                        "reason_code": "NO_READ_WINDOWS",
                        "target_id": "",
                        "evidence_ids": [],
                        "action": "open one more read window before synthesis",
                        "status": "WARN",
                        "notes": [],
                    }
                ],
            },
            "global_verifier": {
                "status": "WARN",
                "decision": "WRITE_WITH_GUARDRAILS",
                "summary": "Checkpoint is mid-loop and should continue once more.",
                "counterfactual_checks": [],
                "recovery_actions": ["open one more read window before synthesis"],
                "completion_score": 0.52,
                "decision_records": [
                    {
                        "decision_scope": "GLOBAL",
                        "decision_type": "WRITE_WITH_GUARDRAILS",
                        "reason_code": "UNRESOLVED_QUESTIONS",
                        "target_id": "",
                        "evidence_ids": [],
                        "action": "open one more read window before synthesis",
                        "status": "WARN",
                        "notes": ["completion_score=0.52"],
                    }
                ],
            },
            "branch_decisions": [
                {
                    "branch_id": "branch-recovery-1",
                    "decision": "READ_MORE",
                    "branch_reason": "NO_READ_WINDOWS",
                    "verifier_scope": "READ",
                    "parent_branch_id": "branch-main",
                    "hypothesis_summary": "Open one more recovery window.",
                    "target_evidence_ids": [],
                    "branch_status": "ACTIVE_BRANCH",
                    "recovery_actions": ["open one more read window before synthesis"],
                }
            ],
            "loop_rounds": [
                {
                    "round_no": 1,
                    "search_hit_count": 1,
                    "read_window_count": 1,
                    "evidence_card_count": 1,
                    "branch_decision": "READ_MORE",
                    "global_decision": "WRITE_WITH_GUARDRAILS",
                    "loop_decision": {
                        "decision": "READ_MORE",
                        "reason": "SEARCH_HITS_WITHOUT_READ_WINDOWS",
                        "round_no": 1,
                        "should_continue": True,
                        "recovery_actions": ["open one more read window before synthesis"],
                    },
                }
            ],
            "loop_decision": {
                "decision": "READ_MORE",
                "reason": "SEARCH_HITS_WITHOUT_READ_WINDOWS",
                "round_no": 1,
                "should_continue": True,
                "recovery_actions": ["open one more read window before synthesis"],
            },
            "tool_traces": [
                {
                    "trace_id": "tool-search_web-round-1",
                    "phase": "TOOL_SEARCH_WEB",
                    "status": "success",
                    "message": "resume checkpoint search trace",
                    "inputs": {"query_count": 1},
                    "outputs": {"hit_count": 1},
                    "warnings": [],
                }
            ],
        },
    }
    task_input = ResearchTaskInput.model_validate(payload)

    _events, result = run_research_task(task_input)

    evidence_ids = {card["evidence_id"] for card in result.result_payload["evidence_cards"]}
    search_hit_ids = {hit["hit_id"] for hit in result.result_payload["search_hits"]}
    window_ids = {window["window_id"] for window in result.result_payload["read_windows"]}
    resume_hit = next(hit for hit in result.result_payload["search_hits"] if hit["hit_id"] == "hit-resume-1")
    resume_window = next(window for window in result.result_payload["read_windows"] if window["window_id"] == "window-resume-1")
    trace_ids = {trace["trace_id"] for trace in result.result_payload["tool_traces"]}

    assert "ev-resume-1" in evidence_ids
    assert "hit-resume-1" in search_hit_ids
    assert "hit-1" in search_hit_ids
    assert "window-resume-1" in window_ids
    assert "window-1" in window_ids
    assert resume_hit["provider_attempts"] == ["workspace"]
    assert resume_hit["provider_resolution"] == "workspace"
    assert resume_window["fetch_status"] == "FALLBACK_USED"
    assert resume_window["fetch_method"] == "NO_TRANSPORT"
    assert resume_window["transport_chain"] == ["NO_TRANSPORT"]
    assert resume_window["transport_resolution"] == "NO_TRANSPORT"
    assert "tool-search_web-round-1" in trace_ids
    assert result.result_payload["loop_rounds"][0]["round_no"] == 1
    assert result.result_payload["loop_rounds"][-1]["round_no"] >= 2
    assert (
        result.result_payload["research_checkpoint_candidate"]["checkpoint_no"]
        == result.result_payload["loop_rounds"][-1]["round_no"]
    )
    assert result.result_payload["research_checkpoint_candidate"]["state_ledger"]["evidence_card_count"] >= 2
    assert result.result_payload["resume_context_summary"]["source_research_run_id"] == "run-seed-1"
    assert result.result_payload["resume_context_summary"]["restored_search_hit_count"] == 1
    assert result.result_payload["resume_context_summary"]["restored_fetch_document_count"] == 1
    assert result.result_payload["resume_context_summary"]["restored_tool_trace_count"] == 1
    assert result.result_payload["research_checkpoint_candidate"]["resume_context_summary"]["source_research_run_id"] == "run-seed-1"
    assert result.result_payload["research_checkpoint_candidate"]["resume_context_summary"]["restored_loop_round_count"] == 1
    assert result.result_payload["research_artifact_candidate"]["resume_context_summary"]["source_research_run_id"] == "run-seed-1"
    assert result.result_payload["research_checkpoint_candidate"]["fetched_documents"][0]["source_id"] == "src-resume-1"
    assert result.result_payload["harness_control_state"]["resume_checkpoint"]["resumed_from_checkpoint"] is True
    assert result.result_payload["harness_control_state"]["resume_checkpoint"]["source_research_run_id"] == "run-seed-1"
    assert result.result_payload["research_checkpoint_candidate"]["harness_control_state"]["resume_checkpoint"]["restored_fetch_document_count"] == 1
    assert result.result_payload["harness_summary"]["resume_checkpoint"]["resumed_from_checkpoint"] is True
    assert result.result_payload["harness_summary"]["resume_checkpoint"]["source_research_run_id"] == "run-seed-1"
    assert result.result_payload["harness_summary"]["resume_checkpoint"]["restored_fetch_document_count"] == 1


def test_research_task_should_reuse_read_windows_for_extract_again_resume() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-seed-extract",
        "checkpoint_no": 1,
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "active_branch_id": "branch-main",
        "final_loop_decision": "EXTRACT_AGAIN",
        "payload": {
            "search_hits": [
                {
                    "hit_id": "hit-extract-1",
                    "source_id": "src-extract-1",
                    "source_title": "Extract Retry Source",
                    "query": "AlphaResearch should focus on what? :: evidence extraction retry round 1",
                    "rank": 1,
                    "snippet": "Stable evidence is already visible in the retained window.",
                    "confidence_score": 0.8,
                    "retrieval_reason": "resume extract retry",
                    "search_angle": "source_scoped",
                    "matched_fields": ["snippet"],
                    "coverage_score": 0.7,
                    "url": "",
                    "provider": "workspace",
                    "adapter": "workspace",
                }
            ],
            "read_windows": [
                {
                    "window_id": "window-extract-1",
                    "hit_id": "hit-extract-1",
                    "source_id": "src-extract-1",
                    "source_title": "Extract Retry Source",
                    "query": "AlphaResearch should focus on what? :: evidence extraction retry round 1",
                    "read_focus": "Retry evidence extraction from the retained window",
                    "window_text": "Stable evidence is already visible in the retained window.",
                    "retention_reason": "RECOVERY_ANCHOR",
                    "token_estimate": 40,
                    "url": "",
                    "provider": "workspace",
                    "adapter": "workspace",
                    "snapshot_status": "WORKSPACE",
                    "snapshot_key": "",
                }
            ],
            "evidence_cards": [],
            "state_ledger": {
                "active_branch_id": "branch-main",
                "rows": [],
                "cells": [],
                "branches": [
                    {
                        "branch_id": "branch-main",
                        "parent_branch_id": "",
                        "branch_reason": "MAINLINE",
                        "hypothesis_summary": "Extract retry mainline.",
                        "status": "MAINLINE",
                        "created_round": 1,
                    }
                ],
                "verifier_decisions": [],
                "unresolved_questions": [
                    "Read windows exist, but no evidence card was extracted for synthesis."
                ],
                "row_status_summary": {},
                "search_hit_count": 1,
                "read_window_count": 1,
                "evidence_card_count": 0,
                "verified_row_count": 0,
                "conflicted_row_count": 0,
                "coverage_score": 0.0,
            },
            "local_verifier": {
                "status": "WARN",
                "passed_checks": ["question normalized", "query bundle compiled"],
                "warnings": ["retry extraction from the retained window"],
                "recovery_actions": ["rerun evidence extraction before synthesis"],
                "decision_records": [],
            },
            "global_verifier": {
                "status": "WARN",
                "decision": "WRITE_WITH_GUARDRAILS",
                "summary": "Retry extraction before synthesis.",
                "counterfactual_checks": [],
                "recovery_actions": ["rerun evidence extraction before synthesis"],
                "completion_score": 0.0,
                "decision_records": [],
            },
            "branch_decisions": [
                {
                    "branch_id": "branch-recovery-1",
                    "decision": "REEXTRACT_EVIDENCE",
                    "branch_reason": "NO_EVIDENCE_CARDS",
                    "verifier_scope": "EXTRACT",
                    "parent_branch_id": "branch-main",
                    "hypothesis_summary": "Reuse retained windows before opening new sources.",
                    "target_evidence_ids": [],
                    "branch_status": "ACTIVE_BRANCH",
                    "recovery_actions": ["rerun evidence extraction before synthesis"],
                }
            ],
            "loop_rounds": [
                {
                    "round_no": 1,
                    "search_hit_count": 1,
                    "read_window_count": 1,
                    "evidence_card_count": 0,
                    "branch_decision": "REEXTRACT_EVIDENCE",
                    "global_decision": "WRITE_WITH_GUARDRAILS",
                    "loop_decision": {
                        "decision": "EXTRACT_AGAIN",
                        "reason": "READ_WINDOWS_WITHOUT_EVIDENCE",
                        "round_no": 1,
                        "should_continue": True,
                        "recovery_actions": ["rerun evidence extraction with stricter schema"],
                    },
                }
            ],
            "loop_decision": {
                "decision": "EXTRACT_AGAIN",
                "reason": "READ_WINDOWS_WITHOUT_EVIDENCE",
                "round_no": 1,
                "should_continue": True,
                "recovery_actions": ["rerun evidence extraction with stricter schema"],
            },
        },
    }
    task_input = ResearchTaskInput.model_validate(payload)

    _events, result = run_research_task(task_input)

    assert result.result_payload["stop_contract"]["recovery_mode"] == "READ_MORE"
    assert any(
        item["decision"] == "EXTRACT_AGAIN"
        for item in result.result_payload["replan_history"]
    )
    assert any(hit["hit_id"] == "hit-extract-1" for hit in result.result_payload["search_hits"])
    assert any(window["window_id"] == "window-extract-1" for window in result.result_payload["read_windows"])
    assert result.result_payload["evidence_cards"]
    assert result.result_payload["loop_rounds"][-1]["round_no"] >= 2
    assert (
        result.result_payload["research_checkpoint_candidate"]["checkpoint_no"]
        == result.result_payload["loop_rounds"][-1]["round_no"]
    )


def test_research_task_should_add_targeted_counterfactual_query_for_resume_checkpoint() -> None:
    payload = _build_task_input(
        sample_text="This source contains conflict risk and an opposing interpretation."
    ).model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-seed-conflict",
        "checkpoint_no": 1,
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "active_branch_id": "branch-recovery-1",
        "final_loop_decision": "COUNTERFACTUAL_RECHECK",
        "payload": {
            "search_hits": [],
            "read_windows": [],
            "evidence_cards": [
                {
                    "evidence_id": "ev-conflict-1",
                    "window_id": "window-conflict-1",
                    "source_id": "src-conflict-1",
                    "source_title": "Conflict Source",
                    "claim_text": "The current conclusion is contested.",
                    "quote_text": "This source contains conflict risk and an opposing interpretation.",
                    "relation_type": "CONFLICTS",
                    "support_score": 0.55,
                    "conflict_score": 0.62,
                }
            ],
            "state_ledger": {
                "columns": [
                    "source_title",
                    "search_query",
                    "read_focus",
                    "evidence_id",
                    "claim_text",
                    "evidence_excerpt",
                    "relation_type",
                    "support_score",
                    "support_level",
                    "verifier_note",
                ],
                "rows": [
                    {
                        "row_id": "row-conflict-1",
                        "source_id": "src-conflict-1",
                        "source_title": "Conflict Source",
                        "search_query": "How should the conflict be rechecked?",
                        "read_focus": "Counterfactual recheck for the conflict source",
                        "evidence_id": "ev-conflict-1",
                        "claim_text": "The current conclusion is contested.",
                        "quote_text": "This source contains conflict risk and an opposing interpretation.",
                        "evidence_excerpt": "This source contains conflict risk and an opposing interpretation.",
                        "relation_type": "CONFLICTS",
                        "support_score": 0.55,
                        "conflict_score": 0.62,
                        "row_status": "CONFLICTED",
                        "branch_id": "branch-recovery-1",
                        "source_quality": "WORKSPACE_SOURCE",
                        "verification_status": "COUNTERFACTUAL_REQUIRED",
                        "support_level": "CONFLICTING",
                        "verifier_note": "Conflict source requires counterfactual recheck.",
                        "repair_hint": "Run counterfactual branch before synthesis.",
                    }
                ],
                "cells": [],
                "branches": [
                    {
                        "branch_id": "branch-main",
                        "parent_branch_id": "",
                        "branch_reason": "MAINLINE",
                        "hypothesis_summary": "Mainline branch.",
                        "status": "MAINLINE",
                        "created_round": 1,
                    },
                    {
                        "branch_id": "branch-recovery-1",
                        "parent_branch_id": "branch-main",
                        "branch_reason": "CONFLICTING_EVIDENCE",
                        "hypothesis_summary": "Conflict branch.",
                        "status": "ACTIVE_BRANCH",
                        "created_round": 1,
                    },
                ],
                "verifier_decisions": [],
                "unresolved_questions": ["Conflicting evidence needs targeted counterfactual recheck."],
                "row_status_summary": {"CONFLICTED": 1},
                "search_hit_count": 0,
                "read_window_count": 0,
                "evidence_card_count": 1,
                "verified_row_count": 0,
                "conflicted_row_count": 1,
                "coverage_score": 0.0,
                "active_branch_id": "branch-recovery-1",
            },
            "local_verifier": {
                "status": "WARN",
                "passed_checks": [],
                "warnings": ["conflicting evidence card requires a branch recheck"],
                "recovery_actions": ["open an alternative source window for conflicting evidence"],
                "decision_records": [],
            },
            "global_verifier": {
                "status": "WARN",
                "decision": "WRITE_WITH_GUARDRAILS",
                "summary": "Conflict branch should continue.",
                "counterfactual_checks": [],
                "recovery_actions": ["open an alternative source window for conflicting evidence"],
                "completion_score": 0.0,
                "decision_records": [],
            },
            "branch_decisions": [
                {
                    "branch_id": "branch-recovery-1",
                    "decision": "COUNTERFACTUAL_RECHECK",
                    "branch_reason": "CONFLICTING_EVIDENCE",
                    "verifier_scope": "VERIFY",
                    "parent_branch_id": "branch-main",
                    "hypothesis_summary": "Validate against the conflict source.",
                    "target_evidence_ids": ["ev-conflict-1"],
                    "branch_status": "ACTIVE_BRANCH",
                    "recovery_actions": ["open an alternative source window for the conflicting claim"],
                }
            ],
            "loop_rounds": [
                {
                    "round_no": 1,
                    "search_hit_count": 0,
                    "read_window_count": 0,
                    "evidence_card_count": 1,
                    "branch_decision": "COUNTERFACTUAL_RECHECK",
                    "global_decision": "WRITE_WITH_GUARDRAILS",
                    "loop_decision": {
                        "decision": "COUNTERFACTUAL_RECHECK",
                        "reason": "CONFLICTING_EVIDENCE",
                        "round_no": 1,
                        "should_continue": True,
                        "recovery_actions": ["run one bounded counterfactual recheck before synthesis"],
                    },
                }
            ],
            "loop_decision": {
                "decision": "COUNTERFACTUAL_RECHECK",
                "reason": "CONFLICTING_EVIDENCE",
                "round_no": 1,
                "should_continue": True,
                "recovery_actions": ["run one bounded counterfactual recheck before synthesis"],
            },
        },
    }
    task_input = ResearchTaskInput.model_validate(payload)

    _events, result = run_research_task(task_input)

    assert any(
        "counterfactual recheck against Conflict Source" in query
        for query in result.result_payload["query_set"]
    )
    assert result.result_payload["counterfactual_summary"]["has_counterfactual_recheck"] is True
    assert result.result_payload["counterfactual_summary"]["counterfactual_branch_count"] == 1
    assert result.result_payload["counterfactual_summary"]["counterfactual_branch_ids"] == ["branch-recovery-1"]
    assert "ev-conflict-1" in result.result_payload["counterfactual_summary"]["target_evidence_ids"]
    assert result.result_payload["harness_control_state"]["branch_recovery"]["active_branch_id"] == "branch-recovery-1"
    assert result.result_payload["harness_control_state"]["branch_recovery"]["latest_branch_decision"] == "COUNTERFACTUAL_RECHECK"
    assert result.result_payload["harness_control_state"]["branch_recovery"]["latest_branch_reason"] == "COUNTERFACTUAL_EVIDENCE_MISSING"
    assert result.result_payload["research_checkpoint_candidate"]["harness_control_state"]["branch_recovery"]["latest_branch_decision"] == "COUNTERFACTUAL_RECHECK"
    assert result.result_payload["harness_summary"]["counterfactual_recovery"]["has_counterfactual_recheck"] is True
    assert result.result_payload["harness_summary"]["counterfactual_recovery"]["counterfactual_branch_count"] == 1
    assert result.result_payload["harness_summary"]["control_loop"]["recovery_mode"] == "COUNTERFACTUAL_RECHECK"


def test_completed_run_should_close_full_horizon_and_emit_observed_tool_timing() -> None:
    _events, result = run_research_task(_build_task_input())

    phases = {item["phase"]: item["status"] for item in result.result_payload["plan_horizon"]}
    assert phases == {
        "PLANNING": "COMPLETE",
        "WIDE_DISCOVERY": "COMPLETE",
        "ENTITY_FREEZE": "COMPLETE",
        "DEEP_CELL_COMPLETION": "COMPLETE",
        "COUNTERFACTUAL_RECOVERY": "SKIPPED",
        "GLOBAL_VERIFY": "COMPLETE",
        "REPORT_AUDIT": "COMPLETE",
        "DELIVERY": "COMPLETE",
    }
    observed = [trace for trace in result.result_payload["tool_traces"] if trace["round_no"] > 0]
    assert observed
    assert all(trace["started_at"] <= trace["ended_at"] for trace in observed)
    assert all("duration_ms" in trace["outputs"] for trace in observed)
    assert all(trace["attempt"] >= 1 for trace in observed)


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
