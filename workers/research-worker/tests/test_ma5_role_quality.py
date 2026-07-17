from __future__ import annotations

import pytest

from app.agent_contracts import AgentRole, CellCandidate, CellTaskBundle, TaskCellTarget
from app.blind_candidate_verifier import BlindCandidateVerifier
from app.cell_task_planner import plan_cell_task_bundles
from app.models import ResearchStateCell, ResearchStateLedger
from app.role_executor import RoleExecutorFactory, RoleProfileCatalog
from app.sequential_candidate_executor import execute_sequential_bundle


def test_role_profiles_are_independent_and_fail_closed() -> None:
    catalog = RoleProfileCatalog()

    deep = catalog.resolve("DEEP_CELL")
    counterfactual = catalog.resolve("COUNTERFACTUAL")
    verifier = catalog.resolve("CELL_VERIFIER")

    assert deep.profile_key == "deep-cell-v1"
    assert deep.model_purpose == "deep_cell_extract"
    assert deep.temperature == 0.1
    assert deep.max_llm_calls == 4
    assert deep.allowed_tools == frozenset({"search", "fetch", "read", "extract"})
    assert deep.require_independent_sources is False

    assert counterfactual.profile_key == "counterfactual-v1"
    assert counterfactual.temperature == 0.0
    assert counterfactual.require_independent_sources is True
    assert counterfactual.max_search_calls > deep.max_search_calls

    assert verifier.profile_key == "cell-verifier-v1"
    assert verifier.allowed_tools == frozenset({"read"})
    assert verifier.max_llm_calls == 1

    context = RoleExecutorFactory(catalog).create("DEEP_CELL", "execution-1")
    assert context.profile is deep
    assert context.toolbox.allowed_tools == deep.allowed_tools

    with pytest.raises(ValueError, match="unsupported research agent role"):
        catalog.resolve("UNTRUSTED_ROLE")


def test_high_risk_quorum_requires_independent_blind_candidates() -> None:
    task = CellTaskBundle(
        task_id="task-1",
        research_run_id="run-1",
        role=AgentRole.DEEP_CELL,
        entity_id="entity-1",
        entity_set_version=1,
        plan_revision=1,
        target_cells=[TaskCellTarget(
            cell_id="entity-1:revenue",
            entity_id="entity-1",
            column_key="revenue",
            expected_version=0,
        )],
        candidate_quorum=2,
        high_risk=True,
        lease_epoch=1,
        fencing_token=1,
        idempotency_key="run-1:task-1",
    )
    first = _candidate("candidate-a", "execution-a", 1, "100", "evidence-a")
    second = _candidate("candidate-b", "execution-b", 2, "100", "evidence-b")
    verifier = BlindCandidateVerifier()

    accepted = verifier.verify(task, [second, first], {
        "evidence-a": "primary.example",
        "evidence-b": "filing.example",
    })
    fake_quorum = verifier.verify(task, [first, second], {
        "evidence-a": "primary.example",
        "evidence-b": "primary.example",
    })

    assert accepted.status == "ACCEPTED"
    assert accepted.canonical_value == "100"
    assert accepted.repair_required is False
    assert len(accepted.anonymous_candidate_digests) == 2
    assert fake_quorum.status == "REPAIR_REQUIRED"
    assert fake_quorum.reason_code == "INDEPENDENT_SOURCE_QUORUM_NOT_MET"
    assert fake_quorum.repair_required is True


def _candidate(
    candidate_id: str,
    execution_id: str,
    candidate_slot: int,
    value: str,
    evidence_id: str,
) -> CellCandidate:
    return CellCandidate(
        candidate_id=candidate_id,
        task_id="task-1",
        execution_id=execution_id,
        candidate_slot=candidate_slot,
        entity_id="entity-1",
        cell_id="entity-1:revenue",
        column_key="revenue",
        base_cell_version=0,
        value=value,
        evidence_ids=[evidence_id],
        confidence=0.9,
        source_diversity=1,
        lease_epoch=1,
        fencing_token=1,
    )


def test_planner_isolates_only_explicit_critical_cells_for_double_candidate_quorum() -> None:
    ledger = ResearchStateLedger(
        entity_set_status="FROZEN",
        entity_set_version=3,
        cells=[
            _state_cell("entity-1:revenue", "revenue", required=True),
            _state_cell("entity-1:website", "website", required=True),
        ],
    )

    bundles = plan_cell_task_bundles(
        "run-1",
        ledger,
        plan_revision=2,
        max_cells_per_bundle=3,
        round_no=1,
        critical_columns={"revenue"},
    )

    assert len(bundles) == 2
    by_column = {bundle.target_cells[0].column_key: bundle for bundle in bundles}
    assert len(by_column["revenue"].target_cells) == 1
    assert by_column["revenue"].high_risk is True
    assert by_column["revenue"].candidate_quorum == 2
    assert by_column["website"].high_risk is False
    assert by_column["website"].candidate_quorum == 1

    blocked = execute_sequential_bundle(
        by_column["revenue"],
        {"entity-1:revenue": _state_cell(
            "entity-1:revenue", "revenue", required=True,
        ).model_copy(update={"candidate_value": "100", "evidence_refs": ["evidence-1"]})},
    )
    assert blocked.status.value == "FAILED"
    assert blocked.termination_reason == "QUORUM_EXECUTION_REQUIRED"
    assert blocked.candidates == []


def _state_cell(cell_id: str, column_key: str, *, required: bool) -> ResearchStateCell:
    return ResearchStateCell(
        cell_id=cell_id,
        row_id="entity-1",
        entity_id="entity-1",
        column_key=column_key,
        candidate_value="",
        status="EMPTY",
        is_required=required,
    )
