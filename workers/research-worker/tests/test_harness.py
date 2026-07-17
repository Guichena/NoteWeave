import pytest

pytestmark = pytest.mark.usefixtures("fake_default_llm")

from app.harness import build_harness
from app.harness_regression import (
    build_harness_regression_suite,
    evaluate_harness_regression_case,
)
from app.models import ResearchTaskInput
from app.runner import run_research_task
from app.verifier_gate_policy import report_gate_action_from_global_decision


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


def _build_empty_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-harness-empty",
            "workspace_id": "ws-1",
            "target_id": "run-harness-empty",
            "source_scope": [],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Do not synthesize without opened evidence."],
            },
            "input_payload": {
                "question": "What if the harness has no sources?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _build_conflict_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-harness-conflict",
            "workspace_id": "ws-1",
            "target_id": "run-harness-conflict",
            "source_scope": [
                {
                    "source_id": "src-harness-conflict",
                    "title": "Conflict Harness Source",
                    "summary": "However, this source reports conflict risk and an opposing interpretation that needs counterfactual review.",
                    "sample_text": "However, this source reports conflict risk and an opposing interpretation that needs counterfactual review.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Conflicting evidence must trigger a bounded counterfactual recheck."],
            },
            "input_payload": {
                "question": "How should the harness correct path drift?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _build_resume_extract_task_input() -> ResearchTaskInput:
    query = "How should the harness recover extraction? :: evidence extraction retry round 1"
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-harness-resume-extract",
            "workspace_id": "ws-1",
            "target_id": "run-harness-resume-extract",
            "source_scope": [
                {
                    "source_id": "src-harness-resume",
                    "title": "Resume Harness Source",
                    "summary": "Resume should reuse retained windows before expanding sources.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Resume should preserve retained evidence state."],
            },
            "input_payload": {
                "question": "How should the harness recover extraction?",
                "profile_key": "DEFAULT",
                "resume_checkpoint": {
                    "source_research_run_id": "run-resume-extract-seed",
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
                                "query": query,
                                "rank": 1,
                                "snippet": "Stable evidence is already visible in the retained window.",
                                "confidence_score": 0.8,
                                "retrieval_reason": "resume extract retry",
                                "search_angle": "source_scoped",
                                "matched_fields": ["snippet"],
                                "coverage_score": 0.7,
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
                                "query": query,
                                "query_family": "direct_evidence",
                                "read_focus": "Retry evidence extraction from the retained window",
                                "window_text": "Stable evidence is already visible in the retained window.",
                                "retention_reason": "RECOVERY_ANCHOR",
                                "token_estimate": 40,
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
                            "passed_checks": ["query bundle compiled"],
                            "warnings": ["retry extraction from the retained window"],
                            "recovery_actions": ["rerun evidence extraction before synthesis"],
                            "decision_records": [],
                        },
                        "global_verifier": {
                            "status": "WARN",
                            "decision": "WRITE_WITH_GUARDRAILS",
                            "summary": "Retry extraction before synthesis.",
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
                },
            },
        }
    )


def _build_resume_counterfactual_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-harness-resume-counterfactual",
            "workspace_id": "ws-1",
            "target_id": "run-harness-resume-counterfactual",
            "source_scope": [
                {
                    "source_id": "src-harness-counterfactual",
                    "title": "Counterfactual Harness Source",
                    "summary": "A resumed conflicting source should force bounded counterfactual recovery.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Resume should preserve conflicting evidence for counterfactual verification."],
            },
            "input_payload": {
                "question": "How should resumed conflicting evidence be handled?",
                "profile_key": "DEFAULT",
                "resume_checkpoint": {
                    "source_research_run_id": "run-resume-conflict-seed",
                    "checkpoint_no": 1,
                    "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
                    "active_branch_id": "branch-recovery-1",
                    "final_loop_decision": "COUNTERFACTUAL_RECHECK",
                    "payload": {
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
                },
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


def test_runner_should_include_verification_driven_harness_summary() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)
    summary = result.result_payload["harness_summary"]

    assert summary["mode"] == "RULE"
    assert summary["phase_count"] == 6
    assert summary["control_loop"]["final_decision"] in {"SYNTHESIZE_REPORT", "WRITE_WITH_GUARDRAILS"}
    assert summary["control_loop"]["loop_round_count"] >= 1
    assert summary["verifier_gate"]["global_decision"] in {"READY_TO_WRITE", "WRITE_WITH_GUARDRAILS"}
    assert summary["verifier_gate"]["local_status"] in {"PASS", "WARN"}
    assert summary["intent_contract"]["status"] == "PASS"
    assert summary["intent_contract"]["alignment_status"] == "PASS"
    assert summary["counterfactual_recovery"]["has_counterfactual_recheck"] is False
    assert summary["resume_checkpoint"]["resumed_from_checkpoint"] is False
    assert summary["evidence_coverage"]["search_hit_count"] == 1
    assert summary["evidence_coverage"]["fetch_document_count"] == 1
    assert summary["evidence_coverage"]["read_window_count"] == 1


def test_runner_should_include_formal_harness_control_state() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)
    control_state = result.result_payload["harness_control_state"]

    assert control_state["control_loop"]["final_decision"] in {"SYNTHESIZE_REPORT", "WRITE_WITH_GUARDRAILS"}
    assert control_state["verifier_gate"]["global_decision"] in {"READY_TO_WRITE", "WRITE_WITH_GUARDRAILS"}
    assert control_state["branch_recovery"]["active_branch_id"] == "branch-main"
    assert control_state["branch_recovery"]["branch_count"] >= 1
    assert control_state["resume_checkpoint"]["resumed_from_checkpoint"] is False
    assert control_state["evidence_coverage"]["fetch_document_count"] == 1
    assert result.result_payload["harness_summary"]["control_loop"]["final_decision"] == control_state["control_loop"]["final_decision"]


def test_runner_should_include_formal_verifier_gate_policy() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)
    policy = result.result_payload["verifier_gate_policy"]

    assert policy["policy_version"] == "v1"
    assert policy["final_loop_decision"] == result.result_payload["loop_decision"]["decision"]
    assert policy["report_gate_action"] == report_gate_action_from_global_decision(
        result.result_payload["global_verifier"]["decision"]
    )
    assert policy["allows_final_write"] is (
        result.result_payload["global_verifier"]["decision"] == "READY_TO_WRITE"
    )
    assert policy["allows_guarded_write"] is (
        result.result_payload["global_verifier"]["decision"] == "WRITE_WITH_GUARDRAILS"
    )
    assert result.result_payload["harness_control_state"]["verifier_gate_policy"]["loop_gate_action"] == policy["loop_gate_action"]
    assert result.result_payload["harness_summary"]["verifier_gate_policy"]["report_gate_action"] == policy["report_gate_action"]


def test_runner_should_include_summary_first_audit_summaries() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)
    payload_audit = result.result_payload["audit_summaries"]
    summary_audit = result.result_payload["harness_summary"]["audit_summaries"]
    checkpoint_audit = result.result_payload["research_checkpoint_candidate"]["audit_summaries"]

    assert payload_audit["checkpoint_summary"]["checkpoint_no"] == result.result_payload["research_checkpoint_candidate"]["checkpoint_no"]
    assert payload_audit["checkpoint_summary"]["snapshot_type"] == "RESEARCH_LOOP_CHECKPOINT"
    assert payload_audit["checkpoint_summary"]["final_loop_decision"] == result.result_payload["loop_decision"]["decision"]
    assert payload_audit["checkpoint_summary"]["report_gate_action"] == result.result_payload["verifier_gate_policy"]["report_gate_action"]
    assert payload_audit["checkpoint_summary"]["active_branch_id"] == result.result_payload["harness_control_state"]["control_loop"]["active_branch_id"]
    assert payload_audit["checkpoint_summary"]["resumed_from_checkpoint"] is False
    assert payload_audit["checkpoint_summary"]["loop_round_count"] == len(result.result_payload["loop_rounds"])

    assert payload_audit["counterfactual_summary"]["has_counterfactual_recheck"] is False
    assert payload_audit["counterfactual_summary"]["counterfactual_branch_count"] == 0
    assert payload_audit["counterfactual_summary"]["target_evidence_ids"] == []

    assert payload_audit["resume_summary"]["resumed_from_checkpoint"] is False
    assert payload_audit["resume_summary"]["source_research_run_id"] == ""
    assert payload_audit["resume_summary"]["source_final_loop_decision"] == ""

    assert payload_audit["evidence_coverage_summary"]["search_hit_count"] == 1
    assert payload_audit["evidence_coverage_summary"]["fetch_document_count"] == 1
    assert payload_audit["evidence_coverage_summary"]["read_window_count"] == 1
    assert (
        payload_audit["evidence_coverage_summary"]["evidence_card_count"]
        == len(result.result_payload["evidence_cards"])
    )
    assert (
        payload_audit["evidence_coverage_summary"]["pending_requirement_count"]
        == result.result_payload["intent_completion_contract"]["pending_requirement_count"]
    )
    assert (
        payload_audit["evidence_coverage_summary"]["unresolved_question_count"]
        == len(result.result_payload["state_ledger"]["unresolved_questions"])
    )

    assert summary_audit == payload_audit
    assert checkpoint_audit == payload_audit


def test_runner_should_include_formal_toolbox_summary() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)
    payload_summary = result.result_payload["toolbox_summary"]
    summary_summary = result.result_payload["harness_summary"]["toolbox_summary"]
    checkpoint_summary = result.result_payload["research_checkpoint_candidate"]["toolbox_summary"]

    assert payload_summary["search_summary"]["query_count"] == len(result.result_payload["query_set"])
    assert payload_summary["search_summary"]["hit_count"] == len(result.result_payload["search_hits"])
    assert payload_summary["search_summary"]["provider_resolution_counts"]["workspace"] >= 1
    assert payload_summary["search_summary"]["query_family_counts"]["direct"] >= 1
    assert payload_summary["search_summary"]["selected_query_count"] >= 1
    assert payload_summary["search_summary"]["selected_queries"]
    assert payload_summary["search_summary"]["query_family_budget_counts"]["direct"] >= 1
    first_selected_query = payload_summary["search_summary"]["selected_queries"][0]
    assert first_selected_query["query"]
    assert first_selected_query["family"] == "direct"
    assert first_selected_query["lane"]
    assert first_selected_query["priority"] == 1
    assert first_selected_query["family_budget"] >= 1
    assert first_selected_query["selection_reason"]
    assert payload_summary["search_summary"]["query_effectiveness"]
    effective_query = next(
        item
        for item in payload_summary["search_summary"]["query_effectiveness"]
        if item["hit_count"] >= 1
    )
    assert effective_query["query"]
    assert effective_query["family"] in {"direct", "source_scoped", "verified_evidence", "direct_evidence"}
    assert effective_query["hit_count"] >= 1
    assert effective_query["max_coverage_score"] >= 0
    assert effective_query["provider_resolution_counts"]["workspace"] >= 1
    assert payload_summary["search_summary"]["effective_query_count"] >= 1
    assert payload_summary["search_summary"]["zero_hit_query_count"] >= 0

    assert payload_summary["fetch_summary"]["fetch_document_count"] == len(result.result_payload["fetched_documents"])
    assert payload_summary["fetch_summary"]["fetch_status_counts"]["WORKSPACE_READY"] >= 1
    assert payload_summary["fetch_summary"]["content_type_counts"]["WORKSPACE_TEXT"] >= 1
    assert payload_summary["fetch_summary"]["fallback_document_count"] == 0
    assert payload_summary["fetch_summary"]["snapshot_archive_ready_count"] == 0
    assert payload_summary["fetch_summary"]["multi_transport_document_count"] == 0

    assert payload_summary["read_summary"]["read_window_count"] == len(result.result_payload["read_windows"])
    assert payload_summary["read_summary"]["read_strategy_counts"]
    assert payload_summary["read_summary"]["content_type_counts"]["WORKSPACE_TEXT"] >= 1
    assert payload_summary["read_summary"]["snapshot_archive_ready_count"] == 0
    assert payload_summary["read_summary"]["multi_transport_window_count"] == 0
    assert (
        payload_summary["read_summary"]["target_requirement_window_count"]
        == sum(
            1
            for window in result.result_payload["read_windows"]
            if window["target_requirement_ids"] or window["target_requirement_labels"]
        )
    )

    assert payload_summary["search_items"]
    direct_search_item = next(item for item in payload_summary["search_items"] if item["family"] == "direct")
    effective_search_item = next(item for item in payload_summary["search_items"] if item["hit_count"] >= 1)
    assert direct_search_item["query"]
    assert direct_search_item["lane"]
    assert direct_search_item["selection_reason"]
    assert effective_search_item["top_hits"]
    assert effective_search_item["top_hits"][0]["source_title"]
    assert effective_search_item["top_hits"][0]["provider_resolution"] == "workspace"

    assert payload_summary["fetch_items"]
    first_fetch_item = payload_summary["fetch_items"][0]
    assert first_fetch_item["fetch_id"]
    assert first_fetch_item["source_title"]
    assert first_fetch_item["query"]
    assert first_fetch_item["fetch_status"] == "WORKSPACE_READY"
    assert first_fetch_item["fetch_method"] == "WORKSPACE"
    assert first_fetch_item["content_type_label"] == "WORKSPACE_TEXT"
    assert first_fetch_item["transport_resolution"] == "WORKSPACE"
    assert "WORKSPACE" in first_fetch_item["transport_chain"]
    assert first_fetch_item["transport_attempt_count"] == 1
    assert first_fetch_item["snapshot_archive_ready"] is False

    assert payload_summary["read_items"]
    direct_or_targeted_read_item = next(
        item
        for item in payload_summary["read_items"]
        if item["query_family"] in {"direct", "source_scoped"}
    )
    assert direct_or_targeted_read_item["window_id"]
    assert direct_or_targeted_read_item["source_title"]
    assert direct_or_targeted_read_item["query"]
    assert direct_or_targeted_read_item["read_strategy"]
    assert direct_or_targeted_read_item["fetch_status"] == "WORKSPACE_READY"
    assert direct_or_targeted_read_item["content_type_label"] == "WORKSPACE_TEXT"
    assert direct_or_targeted_read_item["transport_attempt_count"] == 1
    assert direct_or_targeted_read_item["snapshot_archive_ready"] is False
    assert isinstance(direct_or_targeted_read_item["target_requirement_labels"], list)

    assert summary_summary == payload_summary
    assert checkpoint_summary == payload_summary


def test_runner_should_include_resume_and_counterfactual_audit_summaries_for_recovery_case() -> None:
    task_input = _build_resume_counterfactual_task_input()

    _events, result = run_research_task(task_input)
    audit = result.result_payload["audit_summaries"]

    assert audit["checkpoint_summary"]["resumed_from_checkpoint"] is True
    assert audit["checkpoint_summary"]["final_loop_decision"] == result.result_payload["loop_decision"]["decision"]
    assert audit["checkpoint_summary"]["report_gate_action"] == result.result_payload["verifier_gate_policy"]["report_gate_action"]

    assert audit["counterfactual_summary"]["has_counterfactual_recheck"] is True
    assert audit["counterfactual_summary"]["counterfactual_branch_count"] == 1
    assert audit["counterfactual_summary"]["counterfactual_branch_ids"] == ["branch-recovery-1"]
    assert "ev-conflict-1" in audit["counterfactual_summary"]["target_evidence_ids"]
    assert audit["counterfactual_summary"]["conflicted_row_count"] >= 1

    assert audit["resume_summary"]["resumed_from_checkpoint"] is True
    assert audit["resume_summary"]["source_research_run_id"] == "run-resume-conflict-seed"
    assert audit["resume_summary"]["checkpoint_no"] == 1
    assert audit["resume_summary"]["source_final_loop_decision"] == "COUNTERFACTUAL_RECHECK"
    assert audit["resume_summary"]["source_active_branch_id"] == "branch-recovery-1"

    assert audit["evidence_coverage_summary"]["conflicted_row_count"] >= 1
    assert (
        audit["evidence_coverage_summary"]["unresolved_question_count"]
        == len(result.result_payload["state_ledger"]["unresolved_questions"])
    )


def test_runner_should_surface_toolbox_fallback_and_failure_counts_for_recovery_case() -> None:
    task_input = _build_resume_counterfactual_task_input()

    _events, result = run_research_task(task_input)
    toolbox = result.result_payload["toolbox_summary"]

    assert toolbox["fetch_summary"]["fetch_document_count"] == len(result.result_payload["fetched_documents"])
    assert toolbox["search_summary"]["selected_queries"]
    assert toolbox["search_summary"]["selected_queries"][0]["family"] == "counterfactual"
    assert toolbox["search_summary"]["selected_queries"][0]["selection_reason"]
    assert toolbox["search_summary"]["query_effectiveness"][0]["family"] == "counterfactual"
    assert toolbox["search_summary"]["query_effectiveness"][0]["hit_count"] >= 1
    assert toolbox["fetch_summary"]["content_type_counts"]["WORKSPACE_TEXT"] >= 1
    assert toolbox["fetch_summary"]["snapshot_archive_ready_count"] == 0
    assert toolbox["read_summary"]["query_family_counts"]["counterfactual"] >= 1
    assert toolbox["read_summary"]["read_strategy_counts"]["COUNTERFACTUAL_DEEP_READ"] >= 1
    assert toolbox["read_summary"]["snapshot_archive_ready_count"] == 0
    assert toolbox["search_items"][0]["family"] == "counterfactual"
    assert toolbox["search_items"][0]["top_hits"][0]["source_title"]
    assert toolbox["fetch_items"][0]["content_type_label"] == "WORKSPACE_TEXT"
    assert toolbox["fetch_items"][0]["transport_attempt_count"] == 1
    assert toolbox["fetch_items"][0]["snapshot_archive_ready"] is False
    assert toolbox["read_items"][0]["query_family"] == "counterfactual"
    assert toolbox["read_items"][0]["read_strategy"] == "COUNTERFACTUAL_DEEP_READ"
    assert toolbox["read_items"][0]["transport_attempt_count"] == 1
    assert toolbox["read_items"][0]["snapshot_archive_ready"] is False


def test_runner_should_surface_zero_hit_queries_in_search_effectiveness_summary() -> None:
    task_input = _build_empty_task_input()

    _events, result = run_research_task(task_input)
    search_summary = result.result_payload["toolbox_summary"]["search_summary"]

    assert search_summary["hit_count"] == 0
    assert search_summary["selected_query_count"] >= 1
    assert search_summary["effective_query_count"] == 0
    assert search_summary["zero_hit_query_count"] == search_summary["selected_query_count"]
    assert all(item["hit_count"] == 0 for item in search_summary["query_effectiveness"])


def test_harness_regression_suite_should_cover_phase2_named_cases() -> None:
    suite = build_harness_regression_suite()
    case_keys = [case.case_key for case in suite]

    assert case_keys == [
        "ready_synthesis",
        "scope_expansion",
        "conflict_counterfactual",
        "resume_extract_again",
        "resume_counterfactual",
    ]


@pytest.mark.parametrize(
    ("case_key", "task_builder"),
    [
        ("ready_synthesis", _build_task_input),
        ("scope_expansion", _build_empty_task_input),
        ("conflict_counterfactual", _build_conflict_task_input),
        ("resume_extract_again", _build_resume_extract_task_input),
        ("resume_counterfactual", _build_resume_counterfactual_task_input),
    ],
)
def test_harness_regression_suite_should_validate_named_cases(
    case_key: str,
    task_builder,
) -> None:
    suite = {
        case.case_key: case
        for case in build_harness_regression_suite()
    }
    task_input = task_builder()

    _events, result = run_research_task(task_input)
    evaluation = evaluate_harness_regression_case(suite[case_key], result.result_payload)

    assert evaluation["case_key"] == case_key
    assert evaluation["passed"] is True
    assert evaluation["failures"] == []
    assert evaluation["actual"]["loop_gate_action"] == result.result_payload["verifier_gate_policy"]["loop_gate_action"]
    assert evaluation["actual"]["report_gate_action"] == result.result_payload["verifier_gate_policy"]["report_gate_action"]


def test_runner_should_include_harness_regression_suite_metadata() -> None:
    task_input = _build_task_input()

    _events, result = run_research_task(task_input)
    regression_suite = result.result_payload["harness_summary"]["regression_suite"]

    assert regression_suite["suite_version"] == "v1"
    assert regression_suite["scenario_count"] >= 5
    assert "conflict_counterfactual" in regression_suite["supported_case_keys"]
    assert "resume_extract_again" in regression_suite["supported_case_keys"]


@pytest.mark.parametrize(
    ("task_builder", "expected_loop_gate_action", "expected_flag"),
    [
        (_build_empty_task_input, "EXPAND_SOURCE_SCOPE", "requires_source_expansion"),
        (_build_conflict_task_input, "COUNTERFACTUAL_RECHECK", "requires_counterfactual_recheck"),
        (_build_resume_extract_task_input, "READ_MORE", "requires_read_more"),
        (_build_resume_counterfactual_task_input, "COUNTERFACTUAL_RECHECK", "requires_counterfactual_recheck"),
    ],
)
def test_verifier_gate_policy_should_encode_recovery_actions_for_named_cases(
    task_builder,
    expected_loop_gate_action: str,
    expected_flag: str,
) -> None:
    task_input = task_builder()

    _events, result = run_research_task(task_input)
    policy = result.result_payload["verifier_gate_policy"]

    assert policy["loop_gate_action"] == expected_loop_gate_action
    assert policy[expected_flag] is True
