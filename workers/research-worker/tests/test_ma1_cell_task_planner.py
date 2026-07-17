from __future__ import annotations

from app.agent_contracts import AgentRole
from app.cell_task_planner import plan_cell_task_bundles
from app.models import ResearchStateCell, ResearchStateLedger


def _cell(
    entity_id: str,
    column_key: str,
    *,
    version: int,
    required: bool = False,
    status: str = "CANDIDATE_READY",
    branch_id: str = "branch-main",
) -> ResearchStateCell:
    return ResearchStateCell(
        cell_id=f"{entity_id}:{column_key}",
        row_id=entity_id,
        entity_id=entity_id,
        column_key=column_key,
        candidate_value=f"{column_key} value",
        status=status,
        is_required=required,
        version=version,
    )


def test_planner_should_bundle_same_entity_required_cells_first_with_stable_max_size() -> None:
    ledger = ResearchStateLedger(
        entity_set_status="FROZEN",
        entity_set_version=5,
        cells=[
            _cell("entity-a", "optional", version=1),
            _cell("entity-a", "method", version=2, required=True),
            _cell("entity-a", "result", version=3, required=True),
            _cell("entity-a", "limitation", version=4),
            _cell("entity-b", "method", version=6, required=True),
        ],
    )

    bundles = plan_cell_task_bundles(
        research_run_id="run-1",
        ledger=ledger,
        plan_revision=7,
        max_cells_per_bundle=3,
        round_no=2,
    )

    assert len(bundles) == 3
    assert bundles[0].role == AgentRole.DEEP_CELL
    assert bundles[0].entity_id == "entity-a"
    assert [item.column_key for item in bundles[0].target_cells] == ["method", "result", "limitation"]
    assert [item.expected_version for item in bundles[0].target_cells] == [2, 3, 4]
    assert bundles[1].entity_id == "entity-a"
    assert [item.column_key for item in bundles[1].target_cells] == ["optional"]
    assert bundles[2].entity_id == "entity-b"
    assert bundles[0].entity_set_version == 5
    assert bundles[0].plan_revision == 7


def test_planner_should_not_schedule_frozen_or_already_verified_cells() -> None:
    ledger = ResearchStateLedger(
        entity_set_status="FROZEN",
        entity_set_version=1,
        cells=[
            _cell("entity-a", "frozen", version=1, status="FROZEN"),
            _cell("entity-a", "verified", version=2, status="VERIFIED"),
            _cell("entity-a", "needs-evidence", version=3, status="NEED_MORE_EVIDENCE"),
        ],
    )

    bundles = plan_cell_task_bundles(
        research_run_id="run-1",
        ledger=ledger,
        plan_revision=0,
        max_cells_per_bundle=3,
        round_no=1,
    )

    assert len(bundles) == 1
    assert [item.column_key for item in bundles[0].target_cells] == ["needs-evidence"]


def test_planner_should_be_deterministic_for_the_same_ledger_snapshot() -> None:
    ledger = ResearchStateLedger(
        entity_set_status="FROZEN",
        entity_set_version=3,
        cells=[
            _cell("entity-b", "method", version=1),
            _cell("entity-a", "result", version=2),
        ],
    )

    first = plan_cell_task_bundles("run-1", ledger, plan_revision=1, max_cells_per_bundle=2, round_no=1)
    second = plan_cell_task_bundles("run-1", ledger, plan_revision=1, max_cells_per_bundle=2, round_no=1)

    assert [item.model_dump(mode="json") for item in first] == [item.model_dump(mode="json") for item in second]
