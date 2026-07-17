from __future__ import annotations

import pytest


def _snapshot() -> dict[str, object]:
    payload: dict[str, object] = {
        "schema_version": "research-agent-task-snapshot.v1",
        "task_id": "task-1",
        "research_run_id": "run-1",
        "workspace_id": "workspace-1",
        "role": "DEEP_CELL",
        "entity_id": "entity-1",
        "branch_id": "branch-main",
        "plan_revision": 2,
        "entity_set_version": 3,
        "lease_epoch": 2,
        "fencing_token": 7,
        "target_cells": [{"cell_id": "entity-1:method", "expected_version": 3}],
        "budget": {"llm_calls": 2},
        "provider_key": "research-default",
        "source_policy": {"allow_workspace_sources": True},
        "query_policy": {"query": "test question"},
    }
    from app.task_snapshot_contract import snapshot_digest
    return {**payload, "snapshot_digest": snapshot_digest(payload)}


def _quorum_snapshot() -> dict[str, object]:
    payload = {**_snapshot()}
    payload.pop("snapshot_digest")
    payload.update(
        schema_version="research-agent-task-snapshot.v2",
        logical_task_key="deep-cell:logical-1",
        quorum_group_key="quorum:group-1",
        candidate_quorum=2,
        candidate_slot=2,
        high_risk=True,
    )
    from app.task_snapshot_contract import snapshot_digest
    return {**payload, "snapshot_digest": snapshot_digest(payload)}


def test_task_snapshot_should_require_authoritative_scope_before_executor_creation() -> None:
    from app.task_snapshot_contract import ResearchAgentTaskSnapshot

    snapshot = ResearchAgentTaskSnapshot.model_validate(_snapshot())

    assert snapshot.role == "DEEP_CELL"
    assert snapshot.target_cells[0].expected_version == 3
    snapshot.require_valid_digest()


def test_task_snapshot_v2_should_bind_a_high_risk_candidate_slot_to_its_quorum_group() -> None:
    from app.task_snapshot_contract import ResearchAgentTaskSnapshot

    snapshot = ResearchAgentTaskSnapshot.model_validate(_quorum_snapshot())

    assert snapshot.schema_version == "research-agent-task-snapshot.v2"
    assert snapshot.quorum_group_key == "quorum:group-1"
    assert snapshot.candidate_quorum == 2
    assert snapshot.candidate_slot == 2
    assert snapshot.high_risk is True
    snapshot.require_valid_digest()


@pytest.mark.parametrize("field", ["workspace_id", "role", "target_cells", "budget", "provider_key", "snapshot_digest"])
def test_task_snapshot_should_reject_missing_scope_fields(field: str) -> None:
    from app.task_snapshot_contract import ResearchAgentTaskSnapshot

    payload = _snapshot()
    payload.pop(field)
    with pytest.raises(Exception):
        ResearchAgentTaskSnapshot.model_validate(payload)


def test_task_snapshot_should_reject_tampered_digest_before_execution() -> None:
    from app.task_snapshot_contract import ResearchAgentTaskSnapshot

    payload = _snapshot()
    payload["lease_epoch"] = 9
    snapshot = ResearchAgentTaskSnapshot.model_validate(payload)
    with pytest.raises(ValueError, match="digest"):
        snapshot.require_valid_digest()
