from __future__ import annotations

from app.cell_task_planner import plan_cell_task_bundles
from app.models import ResearchEvidenceCard, ResearchStateCell, ResearchStateLedger
from app.planner import build_research_plan
from app.research_tools import ResearchToolbox, _merge_ledger_history, _seed_cell_history
from app.sequential_candidate_executor import execute_sequential_bundle
from app.models import ResearchTaskInput


def _cell(column_key: str, *, value: str, evidence_refs: list[str]) -> ResearchStateCell:
    return ResearchStateCell(
        cell_id=f"entity-1:{column_key}",
        row_id="entity-1",
        entity_id="entity-1",
        column_key=column_key,
        candidate_value=value,
        evidence_refs=evidence_refs,
        confidence=0.75,
        version=2,
    )


def test_sequential_executor_should_propose_only_grounded_existing_cell_values() -> None:
    cells = [
        _cell("method", value="retrieval", evidence_refs=["ev-1"]),
        _cell("result", value="", evidence_refs=[]),
    ]
    bundle = plan_cell_task_bundles(
        "run-1",
        ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=1, cells=cells),
        plan_revision=1,
        max_cells_per_bundle=3,
        round_no=1,
    )[0]

    result = execute_sequential_bundle(bundle, {cell.cell_id: cell for cell in cells})

    assert result.task_id == bundle.task_id
    assert result.lease_epoch == bundle.lease_epoch
    assert result.fencing_token == bundle.fencing_token
    assert result.termination_reason == "PARTIAL_CANDIDATE_PROPOSAL"
    assert len(result.candidates) == 1
    candidate = result.candidates[0]
    assert candidate.cell_id == "entity-1:method"
    assert candidate.value == "retrieval"
    assert candidate.evidence_ids == ["ev-1"]
    assert candidate.base_cell_version == 2


def test_sequential_executor_should_not_fabricate_candidate_for_missing_cell_data() -> None:
    cell = _cell("method", value="", evidence_refs=[])
    bundle = plan_cell_task_bundles(
        "run-1",
        ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=1, cells=[cell]),
        plan_revision=1,
        max_cells_per_bundle=3,
        round_no=1,
    )[0]

    result = execute_sequential_bundle(bundle, {cell.cell_id: cell})

    assert result.candidates == []
    assert result.termination_reason == "NO_CANDIDATE_VALUE_OR_EVIDENCE"


def test_sequential_executor_should_be_deterministic_for_replay() -> None:
    cell = _cell("method", value="retrieval", evidence_refs=["ev-1"])
    bundle = plan_cell_task_bundles(
        "run-1",
        ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=1, cells=[cell]),
        plan_revision=1,
        max_cells_per_bundle=3,
        round_no=1,
    )[0]

    first = execute_sequential_bundle(bundle, {cell.cell_id: cell})
    second = execute_sequential_bundle(bundle, {cell.cell_id: cell})

    assert first.model_dump(mode="json") == second.model_dump(mode="json")


def test_sequential_v2_should_merge_supported_candidate_through_task_contract(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE", "SEQUENTIAL_V2")
    task_input = ResearchTaskInput.model_validate(
        {
            "task_id": "task-ma1",
            "workspace_id": "ws-1",
            "target_id": "run-ma1",
            "source_scope": [],
            "control_pack": {"pack_type": "research", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
            "input_payload": {"question": "What method is used?", "profile_key": "DEFAULT"},
        }
    )
    plan = build_research_plan(task_input)
    cell = _cell("method", value="retrieval", evidence_refs=["ev-1"])
    evidence = ResearchEvidenceCard(
        evidence_id="ev-1",
        window_id="window-1",
        source_id="source-1",
        source_title="Primary source",
        claim_text="The method uses retrieval.",
        quote_text="The method uses retrieval.",
        relation_type="SUPPORTS",
        support_score=0.9,
        conflict_score=0.0,
        entity_id="entity-1",
        column_key="method",
    )
    ledger = ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=1, cells=[cell])

    updated, trace = ResearchToolbox().verify_cells(
        ledger,
        [evidence],
        plan=plan,
        research_run_id="run-ma1",
        round_no=1,
    )

    assert updated.cells[0].status == "VERIFIED"
    assert updated.cells[0].version == cell.version + 1
    assert updated.cells[0].last_merge_id
    assert updated.cells[0].plan_revision == plan.plan_revision
    assert updated.cells[0].entity_set_version == 1
    assert trace.outputs["task_bundle_count"] == 1
    assert trace.outputs["merge_accepted_count"] == 1


def test_v1_should_preserve_legacy_cell_version_without_merge_audit(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE", "SEQUENTIAL_V1")
    cell = _cell("method", value="retrieval", evidence_refs=["ev-1"])
    evidence = ResearchEvidenceCard(
        evidence_id="ev-1",
        window_id="window-1",
        source_id="source-1",
        source_title="Primary source",
        claim_text="The method uses retrieval.",
        quote_text="The method uses retrieval.",
        relation_type="SUPPORTS",
        support_score=0.9,
        conflict_score=0.0,
        entity_id="entity-1",
        column_key="method",
    )

    updated, _trace = ResearchToolbox().verify_cells(
        ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=1, cells=[cell]),
        [evidence],
        round_no=1,
    )

    assert updated.cells[0].status == "VERIFIED"
    assert updated.cells[0].version == cell.version
    assert updated.cells[0].last_merge_id == ""


def test_cross_round_history_should_preserve_agent_merge_versions() -> None:
    prior_cell = _cell("method", value="retrieval", evidence_refs=["ev-1"]).model_copy(
        update={
            "status": "VERIFIED",
            "version": 7,
            "plan_revision": 3,
            "entity_set_version": 4,
            "last_merge_id": "merge-7",
        }
    )
    rebuilt_cell = prior_cell.model_copy(
        update={
            "status": "CANDIDATE_READY",
            "version": 0,
            "plan_revision": 0,
            "entity_set_version": 0,
            "last_merge_id": "",
        }
    )
    prior = ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=4, cells=[prior_cell])
    rebuilt = ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=4, cells=[rebuilt_cell])

    seeded = _seed_cell_history(prior, rebuilt)
    merged = _merge_ledger_history(prior, rebuilt)

    for ledger in (seeded, merged):
        restored = ledger.cells[0]
        assert restored.status == "VERIFIED"
        assert restored.version == 7
        assert restored.plan_revision == 3
        assert restored.entity_set_version == 4
        assert restored.last_merge_id == "merge-7"
