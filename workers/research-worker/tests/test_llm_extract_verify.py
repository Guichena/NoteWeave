from __future__ import annotations

import json

import pytest

from app.extractor import extract_evidence_cards
from app.llm_client import FakeLlmClient
from app.models import ResearchStateLedger, ResearchStateRow, ResearchTaskInput
from app.planner import build_research_plan
from app.read_adapters import run_research_read
from app.reporter import build_research_report_structure, write_research_report
from app.search import run_workspace_search
from app.state import build_state_ledger
from app.verifier import run_global_verifier, run_local_verifier


def _build_task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-llm-1",
            "workspace_id": "ws-1",
            "target_id": "run-llm-1",
            "source_scope": [
                {
                    "source_id": "src-llm",
                    "title": "LLM Evidence Source",
                    "summary": "The source says verifier gated synthesis must cite opened windows.",
                    "sample_text": "Verifier gated synthesis must cite opened windows and reject unsupported claims.",
                }
            ],
            "control_pack": {
                "pack_type": "research",
                "target_key": "DEFAULT",
                "task_neighborhood": "RESEARCH_DEFAULT",
                "evidence_policy": ["Reject unsupported conclusions."],
            },
            "input_payload": {
                "question": "How should synthesis use evidence?",
                "profile_key": "DEFAULT",
            },
        }
    )


def _read_windows():
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    return task_input, plan, search_hits, run_research_read(task_input, plan, search_hits)


def _extract_with_fake(
    task_input: ResearchTaskInput,
    plan,
    read_windows,
    *,
    relation_type: str = "SUPPORTS",
):
    window = read_windows[0]
    conflict = relation_type == "CONFLICTS"
    column_keys = [
        column.key
        for column in plan.research_schema.columns
        if column.required
    ] or [plan.research_schema.columns[0].key]
    if conflict:
        column_keys = column_keys[:1]
    llm_client = FakeLlmClient(
        {
            "research.extract": json.dumps(
                {
                    "evidence_cards": [
                        {
                            "window_id": window.window_id,
                            "entity_id": "",
                            "column_key": column_key,
                            "claim_text": f"Evidence-grounded finding for {column_key}.",
                            "quote_text": window.window_text[:180],
                            "relation_type": relation_type,
                            "support_score": 0.4 if conflict else 0.9,
                            "conflict_score": 0.8 if conflict else 0.02,
                        }
                        for column_key in column_keys
                    ]
                }
            )
        }
    )
    return extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)


def test_llm_extractor_should_generate_evidence_cards_from_valid_json() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient(
        {
            "research.extract": (
                '{"evidence_cards":[{"window_id":"window-1","claim_text":"'
                'Synthesis must cite opened windows.","quote_text":"Verifier gated '
                    'synthesis must cite opened windows","column_key":"product","relation_type":"SUPPORTS",'
                '"support_score":0.93,"conflict_score":0.01}]}'
            )
        }
    )

    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    # P0-5: evidence_id 现在由 (window_id, position) 生成,不再是简单序号
    assert cards[0].evidence_id.startswith("ev-window-1-")
    assert cards[0].claim_text == "Synthesis must cite opened windows."
    assert cards[0].quote_text in read_windows[0].window_text
    assert cards[0].quote_start >= 0
    assert cards[0].quote_end > cards[0].quote_start
    assert cards[0].content_sha256
    assert cards[0].support_score == 0.93


def test_llm_extractor_should_repair_fenced_json_with_trailing_commas() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient(
        {
            "research.extract": """
```json
{"evidence_cards":[{"window_id":"window-1","column_key":"product","claim_text":"Repair works","quote_text":"opened windows","relation_type":"SUPPORTS","support_score":0.8,"conflict_score":0.0,},],}
```
"""
        }
    )

    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    assert cards[0].claim_text == "Repair works"
    assert cards[0].quote_text in read_windows[0].window_text
    assert cards[0].quote_start >= 0


def test_llm_extractor_should_return_no_cards_on_unusable_output() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient({"research.extract": "not-json-at-all"})

    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    assert cards == []


def test_llm_extractor_should_reject_ungrounded_quote_instead_of_substituting_excerpt() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient({
        "research.extract": (
            '{"evidence_cards":[{"window_id":"window-1","column_key":"product",'
            '"claim_text":"Invented conclusion","quote_text":"This quote is not in the window.",'
            '"relation_type":"SUPPORTS","support_score":0.99,"conflict_score":0.0}]}'
        )
    })

    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)

    assert cards == []


def test_llm_extractor_should_reject_case_insensitive_only_quote_match() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    assert "Verifier gated synthesis" in read_windows[0].window_text
    llm_client = FakeLlmClient({
        "research.extract": (
            '{"evidence_cards":[{"window_id":"window-1","column_key":"product",'
            '"claim_text":"Case-changed quote must not ground evidence",'
            '"quote_text":"verifier gated synthesis","relation_type":"SUPPORTS",'
            '"support_score":0.9,"conflict_score":0.0}]}'
        )
    })

    assert extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client) == []


def test_llm_extractor_should_not_trim_model_quote_into_an_exact_match() -> None:
    task_input, plan, _search_hits, read_windows = _read_windows()
    llm_client = FakeLlmClient({
        "research.extract": (
            '{"evidence_cards":[{"window_id":"window-1","column_key":"product",'
            '"claim_text":"Whitespace-altered quote must not ground evidence",'
            '"quote_text":" Verifier gated synthesis must cite opened windows ",'
            '"relation_type":"SUPPORTS","support_score":0.9,"conflict_score":0.0}]}'
        )
    })

    assert extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client) == []


def test_recovery_extract_again_should_require_explicit_llm_extraction() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-resume-extract",
        "checkpoint_no": 1,
        "payload": {"evidence_cards": [{}]},
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "EXTRACT_AGAIN"
    search_hits = run_workspace_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    read_windows[0] = read_windows[0].model_copy(
        update={
            "window_text": (
                "This note is broad. "
                "Verifier gated synthesis must cite opened windows and reject unsupported claims."
            )
        }
    )

    cards = _extract_with_fake(task_input, plan, read_windows)

    assert cards[0].window_id == read_windows[0].window_id
    assert cards[0].relation_type == "SUPPORTS"


def test_counterfactual_recheck_should_emit_counterfactual_evidence_card() -> None:
    payload = _build_task_input().model_dump(mode="json")
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "COUNTERFACTUAL_RECHECK"
    plan.stop_contract["recovery_target_sources"] = ["LLM Evidence Source"]
    search_hits = run_workspace_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    read_windows[0] = read_windows[0].model_copy(
        update={
            "window_text": "However, the strongest source contradicts the current answer and creates conflict risk."
        }
    )

    cards = _extract_with_fake(
        task_input,
        plan,
        read_windows,
        relation_type="CONFLICTS",
    )

    assert cards[0].relation_type == "CONFLICTS"
    assert cards[0].conflict_score >= 0.5


def test_local_verifier_should_reject_missing_evidence() -> None:
    task_input = _build_task_input()
    plan = build_research_plan(task_input)
    ledger = build_state_ledger(task_input, plan, [], [], [])

    result = run_local_verifier(task_input, plan, ledger, [], [], [])

    assert result.status == "WARN"
    assert "state ledger has no evidence rows" in result.warnings
    assert "attach at least one workspace source before final export" in result.recovery_actions


def test_local_verifier_should_merge_llm_judge_warning() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    llm_client = FakeLlmClient(
        {
            "research.verify.local": (
                '{"status":"WARN","warnings":["citation density is weak"],'
                '"recovery_actions":["read one more source window"]}'
            )
        }
    )

    result = run_local_verifier(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        llm_client=llm_client,
    )

    assert result.status == "WARN"
    assert "citation density is weak" in result.warnings
    assert "read one more source window" in result.recovery_actions
    assert any(
        decision.reason_code == "LLM_JUDGE_FEEDBACK"
        for decision in result.decision_records
    )


def test_local_verifier_should_warn_when_read_more_does_not_expand_windows() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-read-more",
        "checkpoint_no": 1,
        "payload": {"read_windows": [{"window_id": "window-0"}]},
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "READ_MORE"
    search_hits = run_workspace_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert "recovery read expansion did not increase retained windows" in result.warnings
    assert any(
        decision.reason_code == "RECOVERY_READ_EXPANSION_INSUFFICIENT"
        for decision in result.decision_records
    )


def test_local_verifier_should_warn_when_extract_again_has_no_gain() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["resume_checkpoint"] = {
        "source_research_run_id": "run-extract-again",
        "checkpoint_no": 1,
        "payload": {"evidence_cards": [{"evidence_id": "ev-0"}]},
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    plan.stop_contract["recovery_mode"] = "EXTRACT_AGAIN"
    search_hits = run_workspace_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    llm_client = FakeLlmClient({"research.extract": '{"evidence_cards":[]}'})
    cards = extract_evidence_cards(task_input, plan, read_windows, llm_client=llm_client)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert "recovery extraction did not produce incremental evidence" in result.warnings
    assert any(
        decision.reason_code == "RECOVERY_EXTRACTION_NO_GAIN"
        for decision in result.decision_records
    )


def test_local_verifier_should_acknowledge_counterfactual_recheck_signal() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    plan.stop_contract["recovery_mode"] = "COUNTERFACTUAL_RECHECK"
    read_windows[0] = read_windows[0].model_copy(
        update={"read_focus": "Counterfactual recheck against the current answer"}
    )
    cards = [
        _extract_with_fake(task_input, plan, read_windows)[0].model_copy(
            update={"relation_type": "CONFLICTS", "conflict_score": 0.62}
        )
    ]
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert "counterfactual recovery produced verifier-visible conflict evidence" in result.passed_checks


def test_state_ledger_should_materialize_cells_and_dual_verifier_decisions() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    assert ledger.cells
    assert ledger.row_status_summary["VERIFIED"] >= 1
    assert ledger.required_finding_progress
    assert ledger.required_finding_progress[0].status == "READY"
    assert ledger.requirement_ready_row_count >= 1
    cards_by_id = {card.evidence_id: card for card in cards}
    for cell in ledger.cells:
        assert all(
            cards_by_id[evidence_id].entity_id == cell.entity_id
            and cards_by_id[evidence_id].column_key == cell.column_key
            for evidence_id in cell.evidence_refs
        )
    assert local_result.decision_records
    assert global_result.decision_records
    assert global_result.completion_score > 0


def test_state_ledger_should_prefer_targeted_requirement_binding_from_read_window() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["research_intent"] = {
        "research_goal": "Verify the primary synthesis answer.",
        "deliverable_format": "Evidence brief",
        "constraints": ["Keep the answer anchored to verified evidence."],
        "time_range": "",
        "depth": "STANDARD",
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    read_windows[0] = read_windows[0].model_copy(
        update={
            "target_requirement_ids": ["constraint_finding_1"],
            "target_requirement_labels": [
                "Evidence finding: Keep the answer anchored to verified evidence."
            ],
            "target_columns": ["claim_text", "evidence_excerpt"],
            "query_family": "verified_evidence",
        }
    )
    cards = _extract_with_fake(task_input, plan, read_windows)

    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    assert ledger.rows[0].matched_requirement_ids == ["constraint_finding_1"]
    by_requirement = {
        item.requirement_id: item.status
        for item in ledger.required_finding_progress
    }
    assert by_requirement["constraint_finding_1"] == "READY"
    assert by_requirement["goal_finding"] == "MISSING"


def test_report_writer_should_only_cite_existing_evidence_cards() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = ResearchStateLedger(
        columns=plan.state_columns,
        rows=[
            ResearchStateRow(
                row_id="row-valid",
                source_id=cards[0].source_id,
                source_title=cards[0].source_title,
                search_query=read_windows[0].query,
                read_focus=read_windows[0].read_focus,
                evidence_id=cards[0].evidence_id,
                claim_text=cards[0].claim_text,
                quote_text=cards[0].quote_text,
                evidence_excerpt=cards[0].quote_text,
                relation_type=cards[0].relation_type,
                support_score=cards[0].support_score,
                conflict_score=cards[0].conflict_score,
                support_level="SUPPORTED",
                verifier_note="valid",
            ),
            ResearchStateRow(
                row_id="row-ghost",
                source_id="src-ghost",
                source_title="Ghost Source",
                search_query="ghost",
                read_focus="ghost",
                evidence_id="ev-ghost",
                claim_text="Ghost claim",
                quote_text="Ghost quote",
                evidence_excerpt="Ghost quote",
                relation_type="SUPPORTS",
                support_score=0.99,
                conflict_score=0.0,
                support_level="SUPPORTED",
                verifier_note="invalid",
            ),
        ],
        search_hit_count=len(search_hits),
        read_window_count=len(read_windows),
        evidence_card_count=len(cards),
    )
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    report = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )
    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert "## Verified Findings" in report
    assert "## Conflict And Counterfactual Review" in report
    assert "## Recovery Status" in report
    assert cards[0].evidence_id in report
    assert "ev-ghost" not in report
    assert "Ghost claim" not in report
    assert any(
        item["evidence_id"] == cards[0].evidence_id
        for item in structure["evidence_ledger"]
    )
    assert all(item["evidence_id"] != "ev-ghost" for item in structure["evidence_ledger"])
    evidence_item = next(
        item
        for item in structure["evidence_ledger"]
        if item["evidence_id"] == cards[0].evidence_id
    )
    assert evidence_item["provenance"]["source_ref"]["source_id"] == "src-llm"
    assert evidence_item["provenance"]["window_ref"]["window_id"] == "window-1"
    assert evidence_item["provenance"]["row_ref"]["row_id"] == "row-valid"
    assert evidence_item["provenance"]["row_ref"]["cell_ids"]
    assert structure["counterfactual_summary"]["has_counterfactual_recheck"] is False
    assert structure["counterfactual_summary"]["counterfactual_branch_count"] == 0


def test_report_writer_should_surface_guardrails_and_conflicts_explicitly() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    plan.stop_contract["recovery_mode"] = "COUNTERFACTUAL_RECHECK"
    cards = [
        _extract_with_fake(task_input, plan, read_windows)[0].model_copy(
            update={
                "relation_type": "CONFLICTS",
                "conflict_score": 0.66,
            }
        )
    ]
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    report = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )
    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert "Recovery mode: COUNTERFACTUAL_RECHECK" in report
    assert "Conflict source:" in report
    assert "Continue recovery before final synthesis because verifier guardrails remain active." in report
    assert structure["counterfactual_summary"]["has_counterfactual_recheck"] is True
    assert structure["counterfactual_summary"]["conflicted_row_count"] == 1


def test_report_structure_should_preserve_requirement_target_provenance() -> None:
    payload = _build_task_input().model_dump(mode="json")
    payload["input_payload"]["research_intent"] = {
        "research_goal": "Verify the primary synthesis answer.",
        "deliverable_format": "Evidence brief",
        "constraints": ["Keep the answer anchored to verified evidence."],
        "time_range": "",
        "depth": "STANDARD",
    }
    task_input = ResearchTaskInput.model_validate(payload)
    plan = build_research_plan(task_input)
    search_hits = run_workspace_search(task_input, plan)
    read_windows = run_research_read(task_input, plan, search_hits)
    read_windows[0] = read_windows[0].model_copy(
        update={
            "query_family": "verified_evidence",
            "target_requirement_ids": ["constraint_finding_1"],
            "target_requirement_labels": [
                "Evidence finding: Keep the answer anchored to verified evidence."
            ],
            "target_columns": ["claim_text", "evidence_excerpt"],
        }
    )
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    verified_item = structure["verified_findings"][0]
    provenance = verified_item["provenance"]
    assert provenance["window_ref"]["query_family"] == "verified_evidence"
    assert provenance["requirement_ref"]["target_requirement_ids"] == ["constraint_finding_1"]
    assert provenance["requirement_ref"]["target_columns"] == ["claim_text", "evidence_excerpt"]
    assert provenance["requirement_ref"]["matched_requirement_ids"] == ["constraint_finding_1"]
    assert provenance["cell_refs"]
    assert any(
        cell["column_key"] == cards[0].column_key
        for cell in provenance["cell_refs"]
    )
    assert verified_item["source_ref"]["source_id"] == "src-llm"


def test_report_structure_should_surface_recovery_targets_from_runtime_hints() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    plan.stop_contract["recovery_mode"] = "READ_MORE"
    plan.stop_contract["recovery_target_requirement_ids"] = ["goal_finding", "constraint_finding_1"]
    plan.stop_contract["recovery_target_requirement_types"] = ["GOAL_FINDING", "CONSTRAINT_FINDING"]
    plan.stop_contract["recovery_target_requirement_labels"] = [
        "Goal finding: verify the primary answer",
        "Evidence finding: add explicit evidence",
    ]
    plan.stop_contract["recovery_target_columns"] = ["claim_text", "evidence_excerpt"]
    plan.stop_contract["recovery_target_queries"] = ["Verifier loop :: verified evidence search"]
    plan.stop_contract["recovery_target_sources"] = ["Workspace Evidence"]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    report = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )
    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    recovery_targets = structure["closed_loop_state"]["recovery_targets"]
    assert recovery_targets["requirement_ids"] == ["goal_finding", "constraint_finding_1"]
    assert recovery_targets["target_columns"] == ["claim_text", "evidence_excerpt"]
    assert structure["recovery_status"]["recovery_targets"]["target_queries"] == [
        "Verifier loop :: verified evidence search"
    ]
    assert "Recovery target requirements: Goal finding: verify the primary answer" in report
    assert "Recovery target columns: claim_text, evidence_excerpt" in report


def test_local_verifier_should_warn_when_verified_rows_rely_on_low_trust_sources() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "search_lane": "WIDE_DISCOVERY",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        window.model_copy(
            update={
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "read_strategy": "WIDE_COVERAGE_READ",
            }
        )
        for window in read_windows
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert any("low-trust" in warning for warning in result.warnings)
    assert any(
        decision.reason_code == "LOW_TRUST_SOURCE_FOUNDATION"
        for decision in result.decision_records
    )


def test_local_verifier_should_warn_when_external_evidence_is_fallback_only() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "fake-web",
                "url": "https://example.com/fallback-only",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.51,
                "search_lane": "WIDE_DISCOVERY",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/fallback-only",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.51,
                "read_strategy": "WIDE_COVERAGE_READ",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "NO_TRANSPORT",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "fetch_error_reason": "no url snapshot transport configured",
                "fetch_attempts": ["NO_TRANSPORT"],
            }
        )
        for window in read_windows
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert any("fallback" in warning.lower() for warning in result.warnings)
    assert any(
        decision.reason_code == "EXTERNAL_FETCH_FALLBACK_HEAVY"
        for decision in result.decision_records
    )


def test_local_verifier_should_warn_when_external_hits_depend_on_provider_fallback_chain() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "backup-web",
                "provider_attempts": ["primary-web", "backup-web"],
                "provider_resolution": "backup-web",
                "provider_fallback_reason": "prior providers returned no retained hits",
                "url": "https://example.com/provider-fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.52,
                "search_lane": "WIDE_DISCOVERY",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "backup-web",
                "url": "https://example.com/provider-fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.52,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "fetch_status": "FETCHED",
                "fetch_method": "HTTP",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["HTTP"],
                "transport_chain": ["HTTP"],
                "transport_resolution": "HTTP",
            }
        )
        for window in read_windows
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert any("provider fallback" in warning.lower() for warning in result.warnings)
    assert any(
        decision.reason_code == "EXTERNAL_PROVIDER_FALLBACK_HEAVY"
        for decision in result.decision_records
    )


def test_local_verifier_should_warn_when_fetched_external_windows_depend_on_transport_fallback_chain() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "fake-web",
                "provider_attempts": ["fake-web"],
                "provider_resolution": "fake-web",
                "url": "https://example.com/transport-fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.53,
                "search_lane": "DEEP_FOCUS",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/transport-fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.53,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "fetch_status": "FETCHED",
                "fetch_method": "HTTP",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA", "HTTP"],
                "transport_chain": ["JINA", "HTTP"],
                "transport_resolution": "HTTP",
                "transport_fallback_reason": "jina fetch failed after retries",
            }
        )
        for window in read_windows
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert any("transport fallback" in warning.lower() for warning in result.warnings)
    assert any(
        decision.reason_code == "EXTERNAL_TRANSPORT_FALLBACK_HEAVY"
        for decision in result.decision_records
    )


def test_local_verifier_should_warn_when_fetched_external_windows_are_not_archive_ready() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "fake-web",
                "provider_attempts": ["fake-web"],
                "provider_resolution": "fake-web",
                "url": "https://example.com/archive-not-ready",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.53,
                "search_lane": "DEEP_FOCUS",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/archive-not-ready",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.53,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "fetch_status": "FETCHED",
                "fetch_method": "HTTP",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["HTTP"],
                "transport_chain": ["HTTP"],
                "transport_resolution": "HTTP",
                "snapshot_key": "",
                "snapshot_archive_ready": False,
            }
        )
        for window in read_windows
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)

    result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)

    assert any("archive-ready" in warning.lower() for warning in result.warnings)
    assert any(
        decision.reason_code == "EXTERNAL_SNAPSHOT_ARCHIVE_NOT_READY"
        for decision in result.decision_records
    )


def test_global_verifier_should_discount_completion_score_when_fetch_is_fallback_only() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    external_search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "fake-web",
                "url": "https://example.com/fetch-compare",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "search_lane": "DEEP_FOCUS",
                "source_domain": "example.com",
            }
        )
        for hit in search_hits
    ]
    fetched_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/fetch-compare/fetched",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "source_domain": "example.com",
                "fetch_status": "FETCHED",
                "fetch_method": "JINA",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA"],
            }
        )
        for window in read_windows
    ]
    fallback_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/fetch-compare/fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "WIDE_COVERAGE_READ",
                "source_domain": "example.com",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "HTTP",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "fetch_error_reason": "http fetch returned no readable text",
                "fetch_attempts": ["HTTP"],
            }
        )
        for window in read_windows
    ]

    fetched_cards = _extract_with_fake(task_input, plan, fetched_windows)
    fetched_ledger = build_state_ledger(task_input, plan, external_search_hits, fetched_windows, fetched_cards)
    fetched_local = run_local_verifier(task_input, plan, fetched_ledger, external_search_hits, fetched_windows, fetched_cards)
    fetched_global = run_global_verifier(fetched_local, fetched_ledger, [])

    fallback_cards = _extract_with_fake(task_input, plan, fallback_windows)
    fallback_ledger = build_state_ledger(task_input, plan, external_search_hits, fallback_windows, fallback_cards)
    fallback_local = run_local_verifier(task_input, plan, fallback_ledger, external_search_hits, fallback_windows, fallback_cards)
    fallback_global = run_global_verifier(fallback_local, fallback_ledger, [])

    assert fetched_global.completion_score > fallback_global.completion_score
    assert fallback_global.decision == "WRITE_WITH_GUARDRAILS"


def test_global_verifier_should_discount_completion_score_when_orchestration_is_fallback_heavy() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    primary_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "primary-web",
                "provider_attempts": ["primary-web"],
                "provider_resolution": "primary-web",
                "url": "https://example.com/orchestration-primary",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "search_lane": "DEEP_FOCUS",
            }
        )
        for hit in search_hits
    ]
    primary_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "primary-web",
                "url": "https://example.com/orchestration-primary",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "fetch_status": "FETCHED",
                "fetch_method": "JINA",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA"],
                "transport_chain": ["JINA"],
                "transport_resolution": "JINA",
            }
        )
        for window in read_windows
    ]
    primary_cards = _extract_with_fake(task_input, plan, primary_windows)
    primary_ledger = build_state_ledger(task_input, plan, primary_hits, primary_windows, primary_cards)
    primary_local = run_local_verifier(task_input, plan, primary_ledger, primary_hits, primary_windows, primary_cards)
    primary_global = run_global_verifier(primary_local, primary_ledger, [])

    fallback_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "backup-web",
                "provider_attempts": ["primary-web", "backup-web"],
                "provider_resolution": "backup-web",
                "provider_fallback_reason": "prior providers returned no retained hits",
                "url": "https://example.com/orchestration-fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "search_lane": "DEEP_FOCUS",
            }
        )
        for hit in search_hits
    ]
    fallback_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "backup-web",
                "url": "https://example.com/orchestration-fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "fetch_status": "FETCHED",
                "fetch_method": "HTTP",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA", "HTTP"],
                "transport_chain": ["JINA", "HTTP"],
                "transport_resolution": "HTTP",
                "transport_fallback_reason": "jina fetch failed after retries",
            }
        )
        for window in read_windows
    ]
    fallback_cards = _extract_with_fake(task_input, plan, fallback_windows)
    fallback_ledger = build_state_ledger(task_input, plan, fallback_hits, fallback_windows, fallback_cards)
    fallback_local = run_local_verifier(task_input, plan, fallback_ledger, fallback_hits, fallback_windows, fallback_cards)
    fallback_global = run_global_verifier(fallback_local, fallback_ledger, [])

    assert primary_global.completion_score > fallback_global.completion_score
    assert fallback_global.decision == "WRITE_WITH_GUARDRAILS"


def test_global_verifier_should_discount_completion_score_when_fetched_windows_are_not_archive_ready() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    ready_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "primary-web",
                "provider_attempts": ["primary-web"],
                "provider_resolution": "primary-web",
                "url": "https://example.com/archive-ready",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "search_lane": "DEEP_FOCUS",
            }
        )
        for hit in search_hits
    ]
    ready_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "primary-web",
                "url": "https://example.com/archive-ready",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "fetch_status": "FETCHED",
                "fetch_method": "JINA",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA"],
                "transport_chain": ["JINA"],
                "transport_resolution": "JINA",
                "snapshot_key": "snap-archive-ready",
                "snapshot_archive_ready": True,
            }
        )
        for window in read_windows
    ]
    ready_cards = _extract_with_fake(task_input, plan, ready_windows)
    ready_ledger = build_state_ledger(task_input, plan, ready_hits, ready_windows, ready_cards)
    ready_local = run_local_verifier(task_input, plan, ready_ledger, ready_hits, ready_windows, ready_cards)
    ready_global = run_global_verifier(ready_local, ready_ledger, [])

    not_ready_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "primary-web",
                "provider_attempts": ["primary-web"],
                "provider_resolution": "primary-web",
                "url": "https://example.com/archive-not-ready-global",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "search_lane": "DEEP_FOCUS",
            }
        )
        for hit in search_hits
    ]
    not_ready_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "primary-web",
                "url": "https://example.com/archive-not-ready-global",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.55,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "fetch_status": "FETCHED",
                "fetch_method": "JINA",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA"],
                "transport_chain": ["JINA"],
                "transport_resolution": "JINA",
                "snapshot_key": "",
                "snapshot_archive_ready": False,
            }
        )
        for window in read_windows
    ]
    not_ready_cards = _extract_with_fake(task_input, plan, not_ready_windows)
    not_ready_ledger = build_state_ledger(task_input, plan, not_ready_hits, not_ready_windows, not_ready_cards)
    not_ready_local = run_local_verifier(task_input, plan, not_ready_ledger, not_ready_hits, not_ready_windows, not_ready_cards)
    not_ready_global = run_global_verifier(not_ready_local, not_ready_ledger, [])

    assert ready_global.completion_score > not_ready_global.completion_score
    assert not_ready_global.decision == "WRITE_WITH_GUARDRAILS"


def test_report_structure_should_surface_source_foundation_summary() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "source_quality": "OFFICIAL_DOC",
                "source_quality_score": 0.93,
                "search_lane": "DEEP_FOCUS",
                "source_domain": "docs.example.com",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        window.model_copy(
            update={
                "source_quality": "OFFICIAL_DOC",
                "source_quality_score": 0.93,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "source_domain": "docs.example.com",
            }
        )
        for window in read_windows
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    report = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )
    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert structure["source_foundation"]["primary_quality"] == "OFFICIAL_DOC"
    assert structure["source_foundation"]["deep_read_count"] >= 1
    assert structure["final_answer"]["source_basis"] == "Official-document grounded"
    assert "Source foundation: OFFICIAL_DOC" in report


def test_report_structure_should_surface_fetch_aware_source_foundation_summary() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "fake-web",
                "url": "https://example.com/fetch-aware",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "search_lane": "WIDE_DISCOVERY",
                "source_domain": "example.com",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        read_windows[0].model_copy(
            update={
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/fetch-aware/fetched",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "source_domain": "example.com",
                "fetch_status": "FETCHED",
                "fetch_method": "JINA",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA"],
            }
        ),
        read_windows[0].model_copy(
            update={
                "window_id": "window-2",
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/fetch-aware/fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "WIDE_COVERAGE_READ",
                "source_domain": "example.com",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "HTTP",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "fetch_error_reason": "http fetch returned no readable text",
                "fetch_attempts": ["HTTP"],
            }
        ),
    ]
    cards = _extract_with_fake(task_input, plan, read_windows[:1])
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows[:1], cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows[:1], cards)
    global_result = run_global_verifier(local_result, ledger, [])

    report = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )
    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert structure["source_foundation"]["external_window_count"] == 2
    assert structure["source_foundation"]["fetched_window_count"] == 1
    assert structure["source_foundation"]["fallback_window_count"] == 1
    assert structure["source_foundation"]["fetch_foundation_label"]
    assert "Fetched external windows: 1" in report
    assert "Fallback external windows: 1" in report


def test_report_structure_should_surface_orchestration_foundation_summary() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "backup-web",
                "provider_attempts": ["primary-web", "backup-web"],
                "provider_resolution": "backup-web",
                "provider_fallback_reason": "prior providers returned no retained hits",
                "url": "https://example.com/orchestration-foundation",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "search_lane": "DEEP_FOCUS",
                "source_domain": "example.com",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        read_windows[0].model_copy(
            update={
                "adapter": "external_url",
                "provider": "backup-web",
                "url": "https://example.com/orchestration-foundation",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "source_domain": "example.com",
                "fetch_status": "FETCHED",
                "fetch_method": "HTTP",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA", "HTTP"],
                "transport_chain": ["JINA", "HTTP"],
                "transport_resolution": "HTTP",
                "transport_fallback_reason": "jina fetch failed after retries",
            }
        )
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])

    report = write_research_report(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )
    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert structure["source_foundation"]["provider_resolution_counts"]["backup-web"] == 1
    assert structure["source_foundation"]["transport_resolution_counts"]["HTTP"] == 1
    assert structure["source_foundation"]["fallback_provider_hit_count"] == 1
    assert structure["source_foundation"]["fallback_transport_window_count"] == 1
    assert "fallback chain" in structure["source_foundation"]["orchestration_foundation_label"].lower()
    assert "Orchestration foundation:" in report


def test_report_structure_should_surface_archive_readiness_and_transport_complexity_summary() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "backup-web",
                "provider_attempts": ["primary-web", "backup-web"],
                "provider_resolution": "backup-web",
                "provider_fallback_reason": "prior providers returned no retained hits",
                "url": "https://example.com/archive-summary",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "search_lane": "DEEP_FOCUS",
                "source_domain": "example.com",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        read_windows[0].model_copy(
            update={
                "adapter": "external_url",
                "provider": "backup-web",
                "url": "https://example.com/archive-summary/ready",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "source_domain": "example.com",
                "fetch_status": "FETCHED",
                "fetch_method": "HTTP",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["JINA", "HTTP"],
                "transport_chain": ["JINA", "HTTP"],
                "transport_resolution": "HTTP",
                "transport_fallback_reason": "jina fetch failed after retries",
                "transport_attempt_count": 2,
                "snapshot_key": "snap-ready",
                "snapshot_archive_ready": True,
            }
        ),
        read_windows[0].model_copy(
            update={
                "window_id": "window-2",
                "adapter": "external_url",
                "provider": "backup-web",
                "url": "https://example.com/archive-summary/not-ready",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "DEEP_EVIDENCE_READ",
                "source_domain": "example.com",
                "fetch_status": "FETCHED",
                "fetch_method": "HTTP",
                "content_origin": "FETCHED_SNAPSHOT",
                "fetch_attempts": ["HTTP"],
                "transport_chain": ["HTTP"],
                "transport_resolution": "HTTP",
                "transport_attempt_count": 1,
                "snapshot_key": "",
                "snapshot_archive_ready": False,
            }
        ),
    ]
    cards = _extract_with_fake(task_input, plan, read_windows[:1])
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows[:1], cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows[:1], cards)
    global_result = run_global_verifier(local_result, ledger, [])

    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert structure["source_foundation"]["archive_ready_window_count"] == 1
    assert structure["source_foundation"]["archive_unready_window_count"] == 1
    assert structure["source_foundation"]["multi_transport_window_count"] == 1
    assert "archive-ready" in structure["source_foundation"]["archive_readiness_label"].lower()
    assert "multi-transport" in structure["source_foundation"]["transport_complexity_label"].lower()


def test_final_answer_should_surface_fetch_aware_source_basis_and_confidence() -> None:
    task_input, plan, search_hits, read_windows = _read_windows()
    search_hits = [
        hit.model_copy(
            update={
                "adapter": "external",
                "provider": "fake-web",
                "url": "https://example.com/final-answer/fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "search_lane": "WIDE_DISCOVERY",
                "source_domain": "example.com",
            }
        )
        for hit in search_hits
    ]
    read_windows = [
        window.model_copy(
            update={
                "adapter": "external_url",
                "provider": "fake-web",
                "url": "https://example.com/final-answer/fallback",
                "source_quality": "GENERAL_WEB",
                "source_quality_score": 0.56,
                "read_strategy": "WIDE_COVERAGE_READ",
                "source_domain": "example.com",
                "fetch_status": "FALLBACK_USED",
                "fetch_method": "NO_TRANSPORT",
                "content_origin": "SEARCH_SNIPPET_FALLBACK",
                "fetch_error_reason": "no url snapshot transport configured",
                "fetch_attempts": ["NO_TRANSPORT"],
            }
        )
        for window in read_windows
    ]
    cards = _extract_with_fake(task_input, plan, read_windows)
    ledger = build_state_ledger(task_input, plan, search_hits, read_windows, cards)
    local_result = run_local_verifier(task_input, plan, ledger, search_hits, read_windows, cards)
    global_result = run_global_verifier(local_result, ledger, [])
    structure = build_research_report_structure(
        task_input,
        plan,
        ledger,
        search_hits,
        read_windows,
        cards,
        [],
        local_result,
        global_result,
    )

    assert "fallback" in structure["final_answer"]["source_basis"].lower()
    assert "fallback" in structure["final_answer"]["confidence_label"].lower()
