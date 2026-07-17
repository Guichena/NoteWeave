from __future__ import annotations

from app.models import ResearchEvidenceCard, ResearchStateCell, ResearchStateLedger, ResearchTaskInput
from app.planner import build_research_plan
from app.research_tools import ResearchToolbox


def _task_input() -> ResearchTaskInput:
    return ResearchTaskInput.model_validate(
        {
            "task_id": "task-ma2",
            "workspace_id": "ws-1",
            "target_id": "run-ma2",
            "source_scope": [],
            "control_pack": {"pack_type": "research", "target_key": "DEFAULT", "task_neighborhood": "RESEARCH_DEFAULT"},
            "input_payload": {"question": "Which methods are used?", "profile_key": "DEFAULT"},
        }
    )


def _cell(entity_id: str, value: str) -> ResearchStateCell:
    return ResearchStateCell(
        cell_id=f"{entity_id}:method",
        row_id=entity_id,
        entity_id=entity_id,
        column_key="method",
        candidate_value=value,
        evidence_refs=[f"evidence-{entity_id}"],
        confidence=0.8,
    )


def _evidence(entity_id: str, value: str) -> ResearchEvidenceCard:
    return ResearchEvidenceCard(
        evidence_id=f"evidence-{entity_id}",
        window_id=f"window-{entity_id}",
        source_id=f"source-{entity_id}",
        source_title=f"Primary {entity_id}",
        claim_text=f"The method uses {value}.",
        quote_text=f"The method uses {value}.",
        relation_type="SUPPORTS",
        support_score=0.9,
        conflict_score=0.0,
        entity_id=entity_id,
        column_key="method",
    )


def test_local_parallel_should_use_ma1_merge_semantics_with_bounded_scheduler(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE", "LOCAL_PARALLEL")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_MAX_CONCURRENCY", "2")
    task_input = _task_input()
    plan = build_research_plan(task_input)
    cells = [_cell("entity-a", "retrieval"), _cell("entity-b", "generation")]

    updated, trace = ResearchToolbox().verify_cells(
        ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=1, cells=cells),
        [_evidence("entity-a", "retrieval"), _evidence("entity-b", "generation")],
        plan=plan,
        research_run_id=task_input.target_id,
        round_no=1,
    )

    assert [cell.status for cell in updated.cells] == ["VERIFIED", "VERIFIED"]
    assert [cell.version for cell in updated.cells] == [1, 1]
    assert trace.outputs["execution_mode"] == "LOCAL_PARALLEL"
    assert trace.outputs["configured_max_concurrency"] == 2
    assert trace.outputs["effective_max_concurrency"] == 2
    assert trace.outputs["candidate_execution_count"] == 2
    assert trace.outputs["execution_failure_count"] == 0
    assert trace.outputs["merge_accepted_count"] == 2


def test_sequential_v2_should_keep_single_in_flight_scheduler_contract(monkeypatch) -> None:
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_EXECUTION_MODE", "SEQUENTIAL_V2")
    monkeypatch.setenv("NOTEWEAVE_RESEARCH_AGENT_MAX_CONCURRENCY", "3")
    task_input = _task_input()
    plan = build_research_plan(task_input)
    cells = [_cell("entity-a", "retrieval"), _cell("entity-b", "generation")]

    _updated, trace = ResearchToolbox().verify_cells(
        ResearchStateLedger(entity_set_status="FROZEN", entity_set_version=1, cells=cells),
        [_evidence("entity-a", "retrieval"), _evidence("entity-b", "generation")],
        plan=plan,
        research_run_id=task_input.target_id,
        round_no=1,
    )

    assert trace.outputs["execution_mode"] == "SEQUENTIAL_V2"
    assert trace.outputs["configured_max_concurrency"] == 1
    assert trace.outputs["effective_max_concurrency"] == 1
    assert trace.outputs["observed_max_in_flight"] == 1
