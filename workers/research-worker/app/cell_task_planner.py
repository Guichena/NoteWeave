"""MA1 deterministic planning of sequential cell-task bundles."""

from __future__ import annotations

import hashlib

from app.agent_contracts import AgentRole, CellTaskBundle, TaskBudget, TaskCellTarget
from app.models import ResearchStateCell, ResearchStateLedger


def plan_cell_task_bundles(
    research_run_id: str,
    ledger: ResearchStateLedger,
    *,
    plan_revision: int,
    max_cells_per_bundle: int,
    round_no: int,
    critical_columns: set[str] | None = None,
) -> list[CellTaskBundle]:
    """Build stable, single-entity work bundles from a frozen ledger snapshot.

    MA1 deliberately plans only existing cells.  It does not perform tool
    execution or create entities, so it cannot bypass entity freeze.
    """
    if ledger.entity_set_status != "FROZEN":
        return []
    critical_columns = {item.strip() for item in (critical_columns or set()) if item.strip()}
    bundle_size = max(1, min(3, max_cells_per_bundle))
    eligible = [
        cell
        for cell in ledger.cells
        if cell.status not in {"FROZEN", "VERIFIED"}
        and (cell.entity_id or cell.row_id)
    ]
    grouped: dict[tuple[str, str], list[ResearchStateCell]] = {}
    for cell in eligible:
        entity_id = cell.entity_id or cell.row_id
        grouped.setdefault((entity_id, cell.branch_id or "branch-main"), []).append(cell)

    bundles: list[CellTaskBundle] = []
    for (entity_id, branch_id), cells in sorted(grouped.items()):
        ordered = sorted(cells, key=lambda item: (not item.is_required, item.column_key, item.cell_id))
        chunks: list[tuple[list[ResearchStateCell], bool]] = []
        normal_chunk: list[ResearchStateCell] = []
        for cell in ordered:
            high_risk = cell.is_required and cell.column_key in critical_columns
            if high_risk:
                if normal_chunk:
                    chunks.append((normal_chunk, False))
                    normal_chunk = []
                chunks.append(([cell], True))
                continue
            normal_chunk.append(cell)
            if len(normal_chunk) == bundle_size:
                chunks.append((normal_chunk, False))
                normal_chunk = []
        if normal_chunk:
            chunks.append((normal_chunk, False))

        for selected, high_risk in chunks:
            targets = [
                TaskCellTarget(
                    cell_id=cell.cell_id,
                    entity_id=entity_id,
                    column_key=cell.column_key,
                    expected_version=cell.version,
                )
                for cell in selected
            ]
            task_key = _stable_key(
                research_run_id,
                str(round_no),
                str(plan_revision),
                str(ledger.entity_set_version),
                branch_id,
                entity_id,
                *(["quorum-2"] if high_risk else []),
                *(target.cell_id for target in targets),
            )
            bundles.append(
                CellTaskBundle(
                    task_id=f"cell-task-{task_key}",
                    research_run_id=research_run_id,
                    role=AgentRole.DEEP_CELL,
                    entity_id=entity_id,
                    entity_set_version=ledger.entity_set_version,
                    plan_revision=plan_revision,
                    branch_id=branch_id,
                    target_cells=targets,
                    candidate_quorum=2 if high_risk else 1,
                    high_risk=high_risk,
                    budget=TaskBudget(),
                    lease_epoch=1,
                    fencing_token=1,
                    idempotency_key=f"{research_run_id}:r{round_no}:{task_key}",
                )
            )
    return bundles


def _stable_key(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()[:20]
