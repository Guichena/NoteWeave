from __future__ import annotations

from dataclasses import replace
import json

import pytest

from app.benchmark import (
    BenchmarkArchive,
    BenchmarkCase,
    BenchmarkComparator,
    BenchmarkExecution,
    BenchmarkProfile,
    BenchmarkRunner,
    BenchmarkSuiteRunner,
)
from app.benchmark_runtime import ResearchTaskBenchmarkBoundary
from app.run_ma5_benchmark import main as run_benchmark_main
from app.run_ma5_benchmark_suite import main as run_benchmark_suite_main


class FixedExecutionBoundary:
    def execute(self, profile: BenchmarkProfile, case: BenchmarkCase) -> BenchmarkExecution:
        del profile, case
        return BenchmarkExecution(
            result_payload={
                "state_ledger": {
                    "rows": [{
                        "entity_id": "entity-acme",
                        "display_name": "Acme",
                        "requirement_completion_status": "READY",
                    }],
                    "cells": [{
                        "entity_id": "entity-acme",
                        "column_key": "revenue",
                        "candidate_value": "100",
                        "status": "VERIFIED",
                    }],
                },
                "citation_verification": {
                    "association_accuracy": 1.0,
                    "support_accuracy": 1.0,
                },
                "counterfactual_summary": {"has_counterfactual_recheck": False},
            },
            usage={
                "search_calls": 3,
                "fetch_calls": 2,
                "read_calls": 2,
                "llm_calls": 4,
                "input_tokens": 120,
                "output_tokens": 30,
                "estimated_cost": 0.0042,
                "retry_count": 1,
            },
            http_429_count=1,
            http_5xx_count=0,
            termination_reason="COMPLETED",
        )


def test_benchmark_runner_emits_quality_cost_reliability_and_fairness_manifest() -> None:
    ticks = iter([10.0, 12.5])
    runner = BenchmarkRunner(FixedExecutionBoundary(), clock=lambda: next(ticks))
    case = BenchmarkCase(
        case_key="company-revenue",
        question="What is Acme revenue?",
        schema={"columns": ["revenue"]},
        gold_version="gold-v1",
        source_snapshot_digest="sha256:sources-v1",
        gold={
            "case_key": "company-revenue",
            "expected_entities": ["Acme"],
            "required_cells": [{"entity": "Acme", "column": "revenue", "value": "100"}],
            "require_exact_table_match": True,
        },
    )
    profile = BenchmarkProfile(
        profile_key="parallel-real-v1",
        execution_mode="PARALLEL",
        provider_kind="REAL",
        provider="openai-compatible",
        model="research-model",
        source_policy={"allow_external": True, "archive_required": True},
        budget={"max_llm_calls": 8, "max_cost": 0.05},
        random_policy={"temperature": 0.0, "seed": 7},
        rollout_no=1,
    )

    record = runner.run(profile, case)

    assert record.quality["exact_table_success"] is True
    assert record.quality["citation_support_accuracy"] == 1.0
    assert record.wall_clock_ms == 2500.0
    assert record.calls == {"search": 3, "fetch": 2, "read": 2, "llm": 4}
    assert record.input_tokens == 120
    assert record.output_tokens == 30
    assert record.estimated_cost == 0.0042
    assert record.retry_count == 1
    assert record.http_429_count == 1
    assert record.http_5xx_count == 0
    assert record.termination_reason == "COMPLETED"
    assert record.provider_kind == "REAL"
    assert len(record.manifest_digest) == 64
    assert len(record.comparison_digest) == 64


def test_benchmark_archive_is_immutable_and_simulation_cannot_claim_quality_improvement(tmp_path) -> None:
    ticks = iter(float(item) for item in range(16))
    runner = BenchmarkRunner(FixedExecutionBoundary(), clock=lambda: next(ticks))
    case = BenchmarkCase(
        case_key="company-revenue",
        question="What is Acme revenue?",
        schema={"columns": ["revenue"]},
        gold_version="gold-v1",
        source_snapshot_digest="sha256:sources-v1",
        gold={
            "case_key": "company-revenue",
            "expected_entities": ["Acme"],
            "required_cells": [{"entity": "Acme", "column": "revenue", "value": "100"}],
            "require_exact_table_match": True,
        },
    )
    records = []
    for mode in ("SEQUENTIAL", "PARALLEL"):
        for rollout_no in range(1, 5):
            records.append(runner.run(BenchmarkProfile(
                profile_key=f"{mode.lower()}-sim-v1",
                execution_mode=mode,
                provider_kind="SIMULATED",
                provider="deterministic-fake",
                model="fake-model-v1",
                source_policy={"allow_external": False, "archive_required": True},
                budget={"max_llm_calls": 8, "max_cost": 0.0},
                random_policy={"temperature": 0.0, "seed": 7},
                rollout_no=rollout_no,
            ), case))

    archive = BenchmarkArchive(tmp_path)
    artifact = archive.write(records[0])
    replay = archive.write(records[0])

    assert replay == artifact
    assert json.loads(artifact.read_text(encoding="utf-8"))["schema_version"] == "research-agent-benchmark-record.v1"
    with pytest.raises(FileExistsError, match="immutable benchmark artifact conflict"):
        archive.write(replace(records[0], wall_clock_ms=999.0))

    comparison = BenchmarkComparator(min_rollouts_per_mode=4).compare(records)
    assert comparison.comparable is True
    assert comparison.real_provider_evidence is False
    assert comparison.verdict == "EXTERNAL_EVIDENCE_PENDING"
    assert comparison.quality_improvement_claim_allowed is False

    with pytest.raises(ValueError, match="benchmark conditions differ"):
        BenchmarkComparator(min_rollouts_per_mode=4).compare([
            *records[:-1],
            replace(records[-1], comparison_digest="different"),
        ])


def test_benchmark_suite_should_run_each_mode_four_times_archive_and_compare(tmp_path) -> None:
    case = BenchmarkCase(
        case_key="suite-case",
        question="What is Acme revenue?",
        schema={"columns": ["revenue"]},
        gold_version="gold-v1",
        source_snapshot_digest="sha256:sources-v1",
        gold={
            "case_key": "suite-case",
            "expected_entities": ["Acme"],
            "required_cells": [{"entity": "Acme", "column": "revenue", "value": "100"}],
            "require_exact_table_match": True,
        },
    )
    profile = BenchmarkProfile(
        profile_key="suite-sim-v1",
        execution_mode="SEQUENTIAL",
        provider_kind="SIMULATED",
        provider="deterministic-fake",
        model="fake-model-v1",
        source_policy={"allow_external": False, "archive_required": True},
        budget={"max_concurrency": 1},
        random_policy={"temperature": 0.0, "seed": 7},
        rollout_no=1,
    )

    records, comparison = BenchmarkSuiteRunner(FixedExecutionBoundary(), tmp_path / "archive").run(
        profile, case, rollouts_per_mode=4
    )

    assert len(records) == 8
    assert {record.execution_mode for record in records} == {"SEQUENTIAL", "PARALLEL"}
    assert {record.rollout_no for record in records} == {1, 2, 3, 4}
    assert comparison.verdict == "EXTERNAL_EVIDENCE_PENDING"
    assert comparison.quality_improvement_claim_allowed is False
    assert len(list((tmp_path / "archive").rglob("rollout-*.json"))) == 8


def test_benchmark_suite_cli_writes_comparison_summary(monkeypatch, tmp_path) -> None:
    monkeypatch.setattr(
        "app.run_ma5_benchmark_suite.ResearchTaskBenchmarkBoundary",
        lambda: FixedExecutionBoundary(),
    )
    spec = {
        "case": {
            "case_key": "suite-cli-case",
            "question": "What is Acme revenue?",
            "schema": {"columns": ["revenue"]},
            "gold_version": "gold-v1",
            "source_snapshot_digest": "sha256:sources-v1",
            "gold": {
                "case_key": "suite-cli-case",
                "expected_entities": ["Acme"],
                "required_cells": [{"entity": "Acme", "column": "revenue", "value": "100"}],
                "require_exact_table_match": True,
            },
        },
        "profile": {
            "profile_key": "suite-cli-sim-v1",
            "execution_mode": "SEQUENTIAL",
            "provider_kind": "SIMULATED",
            "provider": "deterministic-fake",
            "model": "fake-model-v1",
            "source_policy": {"allow_external": False, "archive_required": True},
            "budget": {"max_concurrency": 1},
            "random_policy": {"temperature": 0.0, "seed": 7},
            "rollout_no": 1,
        },
    }
    spec_path = tmp_path / "suite-spec.json"
    summary_path = tmp_path / "suite-summary.json"
    spec_path.write_text(json.dumps(spec), encoding="utf-8")

    assert run_benchmark_suite_main([
        "--spec", str(spec_path),
        "--archive", str(tmp_path / "archive"),
        "--summary", str(summary_path),
    ]) == 0

    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    assert summary["schema_version"] == "research-agent-benchmark-suite.v1"
    assert summary["rollout_count"] == 8
    assert summary["comparison"]["verdict"] == "EXTERNAL_EVIDENCE_PENDING"


def test_real_provider_boundary_fails_closed_when_independent_worker_config_is_missing(monkeypatch) -> None:
    for name in (
        "NOTEWEAVE_RESEARCH_LLM_API_KEY",
        "NOTEWEAVE_RESEARCH_LLM_BASE_URL",
        "NOTEWEAVE_RESEARCH_LLM_MODEL",
    ):
        monkeypatch.delenv(name, raising=False)
    case = BenchmarkCase(
        case_key="provider-preflight",
        question="question",
        schema={},
        gold_version="gold-v1",
        source_snapshot_digest="sha256:sources-v1",
        gold={"case_key": "provider-preflight"},
        task_input={"input_payload": {"question": "question"}},
    )
    profile = BenchmarkProfile(
        profile_key="real-v1",
        execution_mode="SEQUENTIAL",
        provider_kind="REAL",
        provider="openai-compatible",
        model="research-model",
        source_policy={},
        budget={},
        random_policy={"temperature": 0.0},
        rollout_no=1,
    )

    with pytest.raises(RuntimeError, match="Research LLM configuration incomplete"):
        ResearchTaskBenchmarkBoundary().execute(profile, case)


def test_real_external_benchmark_fails_before_execution_when_search_config_is_missing(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_API_KEY", "llm-secret")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_BASE_URL", "https://llm.example/v1")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_MODEL", "research-model")
    for name in (
        "NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN",
        "NOTEWEAVE_RESEARCH_SEARCH_PROVIDER",
        "NOTEWEAVE_RESEARCH_SEARCH_API_KEY",
        "SERPER_API_KEY",
        "SEARCH_API_KEY",
    ):
        monkeypatch.delenv(name, raising=False)
    case = BenchmarkCase(
        case_key="external-provider-preflight",
        question="question",
        schema={},
        gold_version="gold-v1",
        source_snapshot_digest="sha256:sources-v1",
        gold={"case_key": "external-provider-preflight"},
        task_input={"input_payload": {"question": "question"}},
    )
    profile = BenchmarkProfile(
        profile_key="real-external-v1",
        execution_mode="SEQUENTIAL",
        provider_kind="REAL",
        provider="openai-compatible",
        model="research-model",
        source_policy={"allow_external": True, "archive_required": True},
        budget={},
        random_policy={"temperature": 0.0},
        rollout_no=1,
    )

    with pytest.raises(RuntimeError, match="Research Search configuration incomplete"):
        ResearchTaskBenchmarkBoundary().execute(profile, case)


def test_real_external_benchmark_requires_archive_ready_snapshot_transport(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_API_KEY", "llm-secret")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_BASE_URL", "https://llm.example/v1")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_LLM_MODEL", "research-model")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_PROVIDER", "serper")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_SEARCH_API_KEY", "search-secret")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_ENABLE_URL_READER", "false")
    for name in ("NOTEWEAVE_RESEARCH_JINA_API_KEY", "JINA_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    case = BenchmarkCase(
        case_key="external-snapshot-preflight",
        question="question",
        schema={},
        gold_version="gold-v1",
        source_snapshot_digest="sha256:sources-v1",
        gold={"case_key": "external-snapshot-preflight"},
        task_input={"input_payload": {"question": "question"}},
    )
    profile = BenchmarkProfile(
        profile_key="real-external-v1",
        execution_mode="SEQUENTIAL",
        provider_kind="REAL",
        provider="openai-compatible",
        model="research-model",
        source_policy={"allow_external": True, "archive_required": True},
        budget={},
        random_policy={"temperature": 0.0},
        rollout_no=1,
    )

    with pytest.raises(RuntimeError, match="archive-ready snapshot transport is required"):
        ResearchTaskBenchmarkBoundary().execute(profile, case)


def test_benchmark_cli_runs_worker_and_archives_simulated_rollout(tmp_path) -> None:
    spec = {
        "case": {
            "case_key": "workspace-focus",
            "question": "AlphaResearch should focus on what?",
            "schema": {"columns": ["paper_title", "problem", "method"]},
            "gold_version": "gold-v1",
            "source_snapshot_digest": "sha256:workspace-focus-v1",
            "gold": {
                "case_key": "workspace-focus",
                "expected_entities": ["Alpha Source"],
                "required_cells": [],
                "min_row_completeness": 0.0,
                "min_citation_accuracy": 0.0,
            },
            "task_input": {
                "task_id": "ma5-task-1",
                "workspace_id": "ma5-workspace",
                "target_id": "ma5-run-1",
                "source_scope": [{
                    "source_id": "src-ma5-1",
                    "title": "Alpha Source",
                    "summary": "Verified evidence windows are the primary research focus.",
                }],
                "control_pack": {
                    "pack_type": "research",
                    "target_key": "DEFAULT",
                    "task_neighborhood": "RESEARCH_DEFAULT",
                    "evidence_policy": ["workspace evidence only"],
                },
                "input_payload": {
                    "question": "AlphaResearch should focus on what?",
                    "profile_key": "DEFAULT",
                    "research_intent": {
                        "research_goal": "Identify the primary focus.",
                        "deliverable_format": "comparison table",
                        "depth": "DEEP",
                    },
                },
            },
        },
        "profile": {
            "profile_key": "sequential-sim-v1",
            "execution_mode": "SEQUENTIAL",
            "provider_kind": "SIMULATED",
            "provider": "rule-mode",
            "model": "no-llm",
            "source_policy": {"allow_external": False},
            "budget": {"max_concurrency": 1, "max_cost": 0.0},
            "random_policy": {"temperature": 0.0, "seed": 7},
            "rollout_no": 1,
        },
    }
    spec_path = tmp_path / "spec.json"
    spec_path.write_text(json.dumps(spec), encoding="utf-8")

    assert run_benchmark_main(["--spec", str(spec_path), "--archive", str(tmp_path / "archive")]) == 0
    artifact = tmp_path / "archive" / "workspace-focus" / "sequential" / "rollout-0001.json"
    assert artifact.exists()
    archived = json.loads(artifact.read_text(encoding="utf-8"))["record"]
    assert archived["provider_kind"] == "SIMULATED"
    assert archived["execution_mode"] == "SEQUENTIAL"
