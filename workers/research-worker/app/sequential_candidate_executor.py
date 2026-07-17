"""MA1 adapter from existing ledger values to immutable agent candidates."""

from __future__ import annotations

import hashlib

from app.agent_contracts import AgentExecutionResult, AgentTaskStatus, CellCandidate, CellTaskBundle, ExecutionUsage
from app.models import ResearchStateCell


def execute_sequential_bundle(
    bundle: CellTaskBundle,
    cells_by_id: dict[str, ResearchStateCell],
) -> AgentExecutionResult:
    """Create candidates from already-grounded ledger cells without new tools.

    A missing value or evidence reference is an explicit no-candidate result;
    this function never manufactures a value to make a task appear successful.
    """
    execution_id = f"execution-{_stable_key(bundle.task_id, str(bundle.lease_epoch), str(bundle.fencing_token))}"
    if bundle.candidate_quorum > 1:
        return AgentExecutionResult(
            execution_id=execution_id,
            task_id=bundle.task_id,
            lease_epoch=bundle.lease_epoch,
            fencing_token=bundle.fencing_token,
            status=AgentTaskStatus.FAILED,
            termination_reason="QUORUM_EXECUTION_REQUIRED",
            usage=ExecutionUsage(),
        )
    candidates: list[CellCandidate] = []
    for target in bundle.target_cells:
        cell = cells_by_id.get(target.cell_id)
        if cell is None:
            continue
        if (
            cell.entity_id != bundle.entity_id
            or cell.column_key != target.column_key
            or cell.version != target.expected_version
            or not cell.candidate_value.strip()
            or not cell.evidence_refs
        ):
            continue
        candidate_id = f"candidate-{_stable_key(bundle.task_id, cell.cell_id, str(cell.version), cell.candidate_value, *cell.evidence_refs)}"
        candidates.append(
            CellCandidate(
                candidate_id=candidate_id,
                task_id=bundle.task_id,
                execution_id=execution_id,
                entity_id=cell.entity_id,
                cell_id=cell.cell_id,
                column_key=cell.column_key,
                base_cell_version=cell.version,
                value=cell.candidate_value,
                evidence_ids=list(dict.fromkeys(cell.evidence_refs)),
                confidence=cell.confidence,
                source_diversity=0,
                agent_reason="proposal derived from current ledger evidence bindings",
                lease_epoch=bundle.lease_epoch,
                fencing_token=bundle.fencing_token,
            )
        )
    if not candidates:
        termination_reason = "NO_CANDIDATE_VALUE_OR_EVIDENCE"
    elif len(candidates) < len(bundle.target_cells):
        termination_reason = "PARTIAL_CANDIDATE_PROPOSAL"
    else:
        termination_reason = "TASK_CONTRACT_SATISFIED"
    return AgentExecutionResult(
        execution_id=execution_id,
        task_id=bundle.task_id,
        lease_epoch=bundle.lease_epoch,
        fencing_token=bundle.fencing_token,
        status=AgentTaskStatus.SUBMITTED,
        termination_reason=termination_reason,
        candidates=candidates,
        usage=ExecutionUsage(),
    )


def _stable_key(*parts: str) -> str:
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()[:20]
