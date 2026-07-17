from __future__ import annotations

import argparse
import json
from dataclasses import dataclass

from app.harness_regression import (
    ResearchHarnessRegressionCase,
    build_harness_regression_suite,
    evaluate_harness_regression_case,
)
from app.models import ResearchTaskInput
from app.runner import run_research_task


@dataclass(frozen=True)
class Gate1SmokeCase:
    case_key: str
    title: str
    objective: str
    regression_case_key: str = ""
    expect_counterfactual: bool = False
    expect_external_fallback: bool = False


def _build_wide_deep_report_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-gate1-wide-deep",
            "workspace_id": "ws-1",
            "target_id": "run-gate1-wide-deep",
            "source_scope": [
                {
                    "source_id": "src-gate1-wide",
                    "title": "Gate 1 Workspace Source",
                    "summary": (
                        "The research harness should keep wide search breadth, "
                        "deep reading discipline, and evidence-backed synthesis aligned."
                    ),
                    "sample_text": (
                        "Wide search should broaden coverage, deep reading should verify claims, "
                        "and the final report should stay anchored to explicit evidence windows."
                    ),
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Every finding must stay anchored to explicit evidence windows."],
            },
            "input_payload": {
                "question": "How should Deep Research combine wide search and deep reading?",
                "profile_key": "DEFAULT",
                "research_intent": {
                    "research_goal": "Produce a source-backed report for the wide-to-deep research path.",
                    "deliverable_format": "Formal research report",
                    "constraints": [
                        "Lead with verified findings.",
                        "Keep unsupported claims out of the main conclusion.",
                    ],
                    "time_range": "Current system design",
                    "depth": "DEEP",
                },
            },
        }
    )


def _build_conflict_counterfactual_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-gate1-conflict",
            "workspace_id": "ws-1",
            "target_id": "run-gate1-conflict",
            "source_scope": [
                {
                    "source_id": "src-gate1-conflict",
                    "title": "Gate 1 Conflict Source",
                    "summary": (
                        "This source reports conflict risk and an opposing interpretation "
                        "that should trigger a bounded counterfactual review."
                    ),
                    "sample_text": (
                        "However, this source reports conflict risk and an opposing interpretation "
                        "that should trigger a bounded counterfactual review."
                    ),
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Conflicting evidence must trigger a bounded counterfactual recheck."],
            },
            "input_payload": {
                "question": "How should Deep Research correct path drift when evidence conflicts?",
                "profile_key": "DEFAULT",
                "research_intent": {
                    "research_goal": "Show how verifier-gated counterfactual recovery corrects path drift.",
                    "deliverable_format": "Conflict-aware research report",
                    "constraints": [
                        "Do not merge conflicting evidence into a single unqualified conclusion.",
                    ],
                    "time_range": "Current system design",
                    "depth": "DEEP",
                },
            },
        }
    )


def _build_external_fetch_fallback_task_input() -> ResearchTaskInput:
    payload = _build_wide_deep_report_task_input().model_dump(mode="json")
    payload["task_id"] = "task-gate1-external-fallback"
    payload["target_id"] = "run-gate1-external-fallback"
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-gate1-external-seed",
        "checkpoint_no": 1,
        "snapshot_type": "RESEARCH_LOOP_CHECKPOINT",
        "active_branch_id": "branch-main",
        "final_loop_decision": "READ_MORE",
        "payload": {
            "search_hits": [
                {
                    "hit_id": "hit-gate1-external-1",
                    "source_id": "web-gate1-external-1",
                    "source_title": "External Recovery Source",
                    "query": "How should Deep Research combine wide search and deep reading? :: recovery round 1",
                    "rank": 1,
                    "snippet": "External fallback evidence remains available through the preserved snippet.",
                    "confidence_score": 0.82,
                    "retrieval_reason": "resume external hit",
                    "search_angle": "coverage_gap",
                    "matched_fields": ["snippet"],
                    "coverage_score": 0.74,
                    "url": "https://example.com/gate1-external",
                    "provider": "fake-web",
                    "adapter": "external",
                    "provider_attempts": ["fake-web"],
                    "provider_resolution": "fake-web",
                    "source_domain": "example.com",
                    "source_quality": "GENERAL_WEB",
                    "source_quality_score": 0.61,
                    "search_lane": "BALANCED",
                }
            ],
            "read_windows": [
                {
                    "window_id": "window-gate1-external-1",
                    "hit_id": "hit-gate1-external-1",
                    "source_id": "web-gate1-external-1",
                    "source_title": "External Recovery Source",
                    "query": "How should Deep Research combine wide search and deep reading? :: recovery round 1",
                    "query_family": "coverage_gap",
                    "read_focus": "Preserve external fallback evidence for the resumed report path",
                    "window_text": "External fallback evidence remains available through the preserved snippet.",
                    "retention_reason": "RECOVERY_ANCHOR",
                    "token_estimate": 44,
                    "url": "https://example.com/gate1-external",
                    "provider": "fake-web",
                    "adapter": "external_url",
                    "snapshot_status": "FALLBACK",
                    "snapshot_key": "snap-gate1-external-1",
                    "fetch_status": "FALLBACK_USED",
                    "fetch_method": "NO_TRANSPORT",
                    "content_origin": "SEARCH_SNIPPET_FALLBACK",
                    "content_type_label": "SEARCH_SNIPPET_FALLBACK",
                    "fetch_error_reason": "no url snapshot transport configured",
                    "fetch_failure_code": "NO_TRANSPORT",
                    "fetch_attempts": ["NO_TRANSPORT"],
                    "transport_chain": ["NO_TRANSPORT"],
                    "transport_resolution": "NO_TRANSPORT",
                    "transport_fallback_reason": "no url snapshot transport configured",
                    "transport_fallback_code": "NO_TRANSPORT",
                    "transport_attempt_count": 1,
                    "snapshot_archive_ready": False,
                    "source_domain": "example.com",
                    "source_quality": "GENERAL_WEB",
                    "source_quality_score": 0.61,
                    "read_strategy": "BALANCED_READ",
                }
            ],
            "evidence_cards": [
                {
                    "evidence_id": "ev-gate1-external-1",
                    "window_id": "window-gate1-external-1",
                    "source_id": "web-gate1-external-1",
                    "source_title": "External Recovery Source",
                    "claim_text": "External fallback evidence should remain visible after resume.",
                    "quote_text": "External fallback evidence remains available through the preserved snippet.",
                    "relation_type": "SUPPORTS",
                    "support_score": 0.73,
                    "conflict_score": 0.08,
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
                        "row_id": "row-gate1-external-1",
                        "source_id": "web-gate1-external-1",
                        "source_title": "External Recovery Source",
                        "search_query": "How should Deep Research combine wide search and deep reading? :: recovery round 1",
                        "read_focus": "Preserve external fallback evidence for the resumed report path",
                        "evidence_id": "ev-gate1-external-1",
                        "claim_text": "External fallback evidence should remain visible after resume.",
                        "quote_text": "External fallback evidence remains available through the preserved snippet.",
                        "evidence_excerpt": "External fallback evidence remains available through the preserved snippet.",
                        "relation_type": "SUPPORTS",
                        "support_score": 0.73,
                        "conflict_score": 0.08,
                        "row_status": "VERIFIED",
                        "branch_id": "branch-main",
                        "source_quality": "GENERAL_WEB",
                        "source_domain": "example.com",
                        "read_strategy": "BALANCED_READ",
                        "verification_status": "LOCAL_PASS",
                        "support_level": "SUPPORTED",
                        "verifier_note": "Fallback evidence remains acceptable for bounded continuity.",
                        "repair_hint": "",
                    }
                ],
                "cells": [
                    {
                        "cell_id": "row-gate1-external-1:claim_text",
                        "row_id": "row-gate1-external-1",
                        "column_key": "claim_text",
                        "candidate_value": "External fallback evidence should remain visible after resume.",
                        "status": "VERIFIED",
                        "confidence": 0.73,
                        "evidence_refs": ["ev-gate1-external-1"],
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
                "verifier_decisions": [],
                "unresolved_questions": [
                    "External fallback evidence should be re-opened only if stronger fetched content becomes available."
                ],
                "row_status_summary": {"VERIFIED": 1},
                "search_hit_count": 1,
                "read_window_count": 1,
                "evidence_card_count": 1,
                "verified_row_count": 1,
                "conflicted_row_count": 0,
                "coverage_score": 0.8,
                "active_branch_id": "branch-main",
            },
            "local_verifier": {
                "status": "WARN",
                "passed_checks": ["query bundle compiled"],
                "warnings": ["checkpoint requested one more read round before synthesis"],
                "recovery_actions": ["open one more read window before synthesis"],
                "decision_records": [],
            },
            "global_verifier": {
                "status": "WARN",
                "decision": "WRITE_WITH_GUARDRAILS",
                "summary": "Checkpoint is mid-loop and should continue once more.",
                "counterfactual_checks": [],
                "recovery_actions": ["open one more read window before synthesis"],
                "completion_score": 0.52,
                "decision_records": [],
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
                    "trace_id": "tool-fetch_external-round-1",
                    "phase": "TOOL_FETCH_URL",
                    "status": "success",
                    "message": "resume checkpoint external fetch trace",
                    "inputs": {"query_count": 1},
                    "outputs": {"fallback_document_count": 1},
                    "warnings": [],
                }
            ],
        },
    }
    return ResearchTaskInput.model_validate(payload)


def build_gate1_smoke_suite() -> list[Gate1SmokeCase]:
    return [
        Gate1SmokeCase(
            case_key="wide_deep_report",
            title="Wide Search + Deep Read",
            objective="Validate the default report path for wide-to-deep research.",
            regression_case_key="ready_synthesis",
        ),
        Gate1SmokeCase(
            case_key="conflict_counterfactual",
            title="Conflict Counterfactual",
            objective="Validate verifier-gated counterfactual correction when evidence conflicts.",
            regression_case_key="conflict_counterfactual",
            expect_counterfactual=True,
        ),
        Gate1SmokeCase(
            case_key="external_fetch_fallback",
            title="External Fetch Fallback",
            objective="Validate that an external fallback fetch/read path still produces a usable report.",
            expect_external_fallback=True,
        ),
    ]


def _gate1_task_builders() -> dict[str, object]:
    return {
        "wide_deep_report": _build_wide_deep_report_task_input,
        "conflict_counterfactual": _build_conflict_counterfactual_task_input,
        "external_fetch_fallback": _build_external_fetch_fallback_task_input,
    }


def _regression_case_map() -> dict[str, ResearchHarnessRegressionCase]:
    return {
        case.case_key: case
        for case in build_harness_regression_suite()
    }


def _count_external_sources(result_payload: dict[str, object]) -> int:
    source_keys: set[str] = set()
    for collection_key in ("search_hits", "fetched_documents", "read_windows"):
        for item in result_payload.get(collection_key, []):
            if not isinstance(item, dict):
                continue
            url = str(item.get("url", "")).strip()
            adapter = str(item.get("adapter", "")).strip()
            provider = str(item.get("provider", "")).strip()
            source_id = str(item.get("source_id", "")).strip()
            source_title = str(item.get("source_title", "")).strip()
            if url or adapter in {"external", "external_url"} or provider not in {"", "workspace"}:
                source_keys.add(f"{source_id}::{source_title}")
    return len(source_keys)


def evaluate_gate1_smoke_case(
    case: Gate1SmokeCase,
    result_payload: dict[str, object],
) -> dict[str, object]:
    regression_case = None
    if case.regression_case_key:
        regression_case = _regression_case_map()[case.regression_case_key]

    report_markdown = str(result_payload.get("report_markdown", "")).strip()
    citations = result_payload.get("citations", [])
    if not isinstance(citations, list):
        citations = []
    report_structure = dict(result_payload.get("report_structure", {}))
    toolbox_summary = dict(result_payload.get("toolbox_summary", {}))
    audit_summaries = dict(result_payload.get("audit_summaries", {}))
    verifier_gate_policy = dict(result_payload.get("verifier_gate_policy", {}))
    counterfactual_summary = dict(result_payload.get("counterfactual_summary", {}))
    fetched_documents = [
        item for item in result_payload.get("fetched_documents", [])
        if isinstance(item, dict)
    ]
    read_windows = [
        item for item in result_payload.get("read_windows", [])
        if isinstance(item, dict)
    ]
    verified_findings = report_structure.get("verified_findings", [])
    if not isinstance(verified_findings, list):
        verified_findings = []

    fallback_document_count = sum(
        1 for item in fetched_documents
        if str(item.get("fetch_status", "")).strip() == "FALLBACK_USED"
    )
    fallback_read_window_count = sum(
        1 for item in read_windows
        if str(item.get("fetch_status", "")).strip() == "FALLBACK_USED"
    )
    process_summary_ready = all(
        key in toolbox_summary
        for key in ("search_summary", "fetch_summary", "read_summary")
    )
    audit_summary_ready = all(
        key in audit_summaries
        for key in (
            "checkpoint_summary",
            "counterfactual_summary",
            "resume_summary",
            "evidence_coverage_summary",
        )
    ) and bool(str(verifier_gate_policy.get("report_gate_action", "")).strip())

    source_count = len(
        {
            str(item.get("source_title", "")).strip()
            for item in verified_findings
            if isinstance(item, dict) and str(item.get("source_title", "")).strip()
        }
    )
    if source_count == 0:
        source_count = len(
            {
                str(item.get("title", "")).strip()
                for item in citations
                if isinstance(item, dict) and str(item.get("title", "")).strip()
            }
        )
    if source_count == 0:
        source_count = len(
            {
                str(item.get("source_title", "")).strip()
                for item in report_structure.get("evidence_ledger", [])
                if isinstance(item, dict) and str(item.get("source_title", "")).strip()
            }
        )

    actual = {
        "report_generated": bool(report_markdown),
        "report_length": len(report_markdown),
        "source_count": source_count,
        "process_summary_ready": process_summary_ready,
        "audit_summary_ready": audit_summary_ready,
        "has_counterfactual_recheck": bool(counterfactual_summary.get("has_counterfactual_recheck")),
        "counterfactual_branch_count": int(counterfactual_summary.get("counterfactual_branch_count") or 0),
        "report_gate_action": str(verifier_gate_policy.get("report_gate_action", "")).strip(),
        "fallback_document_count": fallback_document_count,
        "fallback_read_window_count": fallback_read_window_count,
        "external_source_count": _count_external_sources(result_payload),
    }

    failures: list[str] = []
    if not actual["report_generated"]:
        failures.append("report_markdown is empty")
    if actual["source_count"] < 1:
        failures.append("report does not expose at least one source-backed evidence context")
    if not actual["process_summary_ready"]:
        failures.append("toolbox_summary is missing search/fetch/read summaries")
    if not actual["audit_summary_ready"]:
        failures.append("audit_summaries or verifier gate policy are incomplete")
    if case.expect_counterfactual and not actual["has_counterfactual_recheck"]:
        failures.append("counterfactual recheck was expected but not present")
    if not case.expect_counterfactual and case.case_key != "external_fetch_fallback" and actual["has_counterfactual_recheck"]:
        failures.append("counterfactual recheck was not expected for this case")
    if case.expect_external_fallback and actual["fallback_document_count"] < 1:
        failures.append("expected at least one fallback fetched document")
    if case.expect_external_fallback and actual["fallback_read_window_count"] < 1:
        failures.append("expected at least one fallback read window")
    if case.expect_external_fallback and actual["external_source_count"] < 1:
        failures.append("expected at least one external source in the result payload")

    regression_evaluation = None
    if regression_case is not None:
        regression_evaluation = evaluate_harness_regression_case(regression_case, result_payload)
        if not regression_evaluation["passed"]:
            failures.extend(
                f"regression::{failure}"
                for failure in regression_evaluation["failures"]
            )

    return {
        "case_key": case.case_key,
        "title": case.title,
        "objective": case.objective,
        "passed": not failures,
        "failures": failures,
        "actual": actual,
        "regression_evaluation": regression_evaluation,
    }


def run_gate1_smoke_case(case_key: str) -> dict[str, object]:
    suite = {
        case.case_key: case
        for case in build_gate1_smoke_suite()
    }
    builders = _gate1_task_builders()
    if case_key not in suite:
        raise KeyError(f"unknown Gate 1 smoke case: {case_key}")
    task_input = builders[case_key]()
    _events, result = run_research_task(task_input)
    evaluation = evaluate_gate1_smoke_case(
        suite[case_key],
        {
            **result.result_payload,
            "citations": result.citations,
        },
    )
    evaluation["result_title"] = result.result_title
    return evaluation


def run_gate1_smoke_suite(case_keys: list[str] | None = None) -> dict[str, object]:
    requested_case_keys = case_keys or [case.case_key for case in build_gate1_smoke_suite()]
    evaluations = [
        run_gate1_smoke_case(case_key)
        for case_key in requested_case_keys
    ]
    return {
        "suite_key": "gate1",
        "case_count": len(evaluations),
        "passed_count": sum(1 for evaluation in evaluations if evaluation["passed"]),
        "failed_case_keys": [
            evaluation["case_key"]
            for evaluation in evaluations
            if not evaluation["passed"]
        ],
        "evaluations": evaluations,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run Gate 1 Deep Research smoke cases.")
    parser.add_argument(
        "--case",
        dest="case_keys",
        action="append",
        help="Optional case key. Repeat to run multiple specific Gate 1 cases.",
    )
    args = parser.parse_args()
    summary = run_gate1_smoke_suite(args.case_keys)
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0 if not summary["failed_case_keys"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
