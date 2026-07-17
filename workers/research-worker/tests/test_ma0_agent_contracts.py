from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.agent_contracts import (
    AgentExecutionResult,
    AgentRole,
    AgentTaskStatus,
    CellCandidate,
    CellTaskBundle,
    ExecutionUsage,
    TaskBudget,
    TaskCellTarget,
)
from app.config import Settings


def _bundle(**overrides: object) -> CellTaskBundle:
    payload: dict[str, object] = {
        "task_id": "task-1",
        "research_run_id": "run-1",
        "role": AgentRole.DEEP_CELL,
        "entity_id": "entity-1",
        "entity_set_version": 3,
        "plan_revision": 2,
        "branch_id": "branch-main",
        "target_cells": [
            {
                "cell_id": "entity-1:method",
                "entity_id": "entity-1",
                "column_key": "method",
                "expected_version": 4,
            }
        ],
        "budget": {"search_calls": 2, "fetch_calls": 3, "read_windows": 3, "llm_calls": 4},
        "lease_epoch": 1,
        "fencing_token": 7,
        "idempotency_key": "run-1:wave-1:entity-1:method",
    }
    payload.update(overrides)
    return CellTaskBundle.model_validate(payload)


def _candidate(**overrides: object) -> CellCandidate:
    payload: dict[str, object] = {
        "candidate_id": "candidate-1",
        "task_id": "task-1",
        "execution_id": "execution-1",
        "entity_id": "entity-1",
        "cell_id": "entity-1:method",
        "column_key": "method",
        "base_cell_version": 4,
        "value": "retrieval augmented generation",
        "evidence_ids": ["ev-1"],
        "confidence": 0.82,
        "lease_epoch": 1,
        "fencing_token": 7,
    }
    payload.update(overrides)
    return CellCandidate.model_validate(payload)


def test_bundle_should_require_nonempty_unique_targets_for_its_entity() -> None:
    with pytest.raises(ValidationError):
        _bundle(target_cells=[])
    with pytest.raises(ValidationError):
        _bundle(
            target_cells=[
                {"cell_id": "entity-1:method", "entity_id": "entity-1", "column_key": "method", "expected_version": 4},
                {"cell_id": "entity-1:method", "entity_id": "entity-1", "column_key": "method", "expected_version": 4},
            ]
        )
    with pytest.raises(ValidationError):
        _bundle(
            target_cells=[
                {"cell_id": "entity-2:method", "entity_id": "entity-2", "column_key": "method", "expected_version": 4},
            ]
        )


def test_bundle_should_reject_invalid_budget_or_fencing_values() -> None:
    with pytest.raises(ValidationError):
        _bundle(budget=TaskBudget(llm_calls=-1))
    with pytest.raises(ValidationError):
        _bundle(lease_epoch=0)
    with pytest.raises(ValidationError):
        _bundle(fencing_token=0)


def test_execution_result_should_bind_candidates_to_its_task_and_terminal_reason() -> None:
    bundle = _bundle()
    with pytest.raises(ValidationError):
        AgentExecutionResult(
            execution_id="execution-1",
            task_id=bundle.task_id,
            lease_epoch=1,
            fencing_token=7,
            status=AgentTaskStatus.SUBMITTED,
            termination_reason="",
            candidates=[_candidate()],
            usage=ExecutionUsage(llm_calls=1),
        )
    with pytest.raises(ValidationError):
        AgentExecutionResult(
            execution_id="execution-1",
            task_id=bundle.task_id,
            lease_epoch=1,
            fencing_token=7,
            status=AgentTaskStatus.SUBMITTED,
            termination_reason="TASK_CONTRACT_SATISFIED",
            candidates=[_candidate(task_id="other-task")],
            usage=ExecutionUsage(llm_calls=1),
        )


def test_execution_result_should_reject_negative_usage() -> None:
    with pytest.raises(ValidationError):
        ExecutionUsage(search_calls=-1)


def test_ma0_execution_mode_should_default_to_legacy_sequential_runtime() -> None:
    assert Settings().research_agent_execution_mode == "SEQUENTIAL_V1"
    assert Settings().research_agent_max_concurrency == 1
    assert Settings().research_agent_bundle_max_cells == 3


def test_ma0_execution_mode_should_use_research_worker_scoped_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE", "SEQUENTIAL_V2")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_MAX_CONCURRENCY", "2")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_BUNDLE_MAX_CELLS", "2")

    settings = Settings()

    assert settings.research_agent_execution_mode == "SEQUENTIAL_V2"
    assert settings.research_agent_max_concurrency == 2
    assert settings.research_agent_bundle_max_cells == 2
