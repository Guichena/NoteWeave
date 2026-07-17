import pytest

pytestmark = pytest.mark.usefixtures("fake_default_llm")

from app.gate1_smoke import (
    build_gate1_smoke_suite,
    run_gate1_smoke_case,
    run_gate1_smoke_suite,
)


def test_gate1_smoke_suite_should_cover_three_formal_gate_cases() -> None:
    suite = build_gate1_smoke_suite()

    assert [case.case_key for case in suite] == [
        "wide_deep_report",
        "conflict_counterfactual",
        "external_fetch_fallback",
    ]


def test_gate1_smoke_case_should_validate_wide_deep_report() -> None:
    evaluation = run_gate1_smoke_case("wide_deep_report")

    assert evaluation["case_key"] == "wide_deep_report"
    assert evaluation["passed"] is True
    assert evaluation["failures"] == []
    assert evaluation["actual"]["report_generated"] is True
    assert evaluation["actual"]["source_count"] >= 1
    assert evaluation["actual"]["process_summary_ready"] is True
    assert evaluation["actual"]["audit_summary_ready"] is True
    assert evaluation["actual"]["has_counterfactual_recheck"] is False
    assert evaluation["actual"]["fallback_document_count"] == 0


def test_gate1_smoke_case_should_validate_conflict_counterfactual() -> None:
    evaluation = run_gate1_smoke_case("conflict_counterfactual")

    assert evaluation["case_key"] == "conflict_counterfactual"
    assert evaluation["passed"] is True
    assert evaluation["failures"] == []
    assert evaluation["actual"]["report_generated"] is True
    assert evaluation["actual"]["process_summary_ready"] is True
    assert evaluation["actual"]["audit_summary_ready"] is True
    assert evaluation["actual"]["has_counterfactual_recheck"] is True
    assert evaluation["actual"]["counterfactual_branch_count"] >= 1
    assert evaluation["actual"]["report_gate_action"]


def test_gate1_smoke_case_should_validate_external_fetch_fallback() -> None:
    evaluation = run_gate1_smoke_case("external_fetch_fallback")

    assert evaluation["case_key"] == "external_fetch_fallback"
    assert evaluation["passed"] is True
    assert evaluation["failures"] == []
    assert evaluation["actual"]["report_generated"] is True
    assert evaluation["actual"]["process_summary_ready"] is True
    assert evaluation["actual"]["audit_summary_ready"] is True
    assert evaluation["actual"]["fallback_document_count"] >= 1
    assert evaluation["actual"]["fallback_read_window_count"] >= 1
    assert evaluation["actual"]["external_source_count"] >= 1


def test_gate1_smoke_suite_should_run_all_cases_and_return_summary() -> None:
    summary = run_gate1_smoke_suite()

    assert summary["suite_key"] == "gate1"
    assert summary["case_count"] == 3
    assert summary["passed_count"] == 3
    assert summary["failed_case_keys"] == []
    assert len(summary["evaluations"]) == 3
