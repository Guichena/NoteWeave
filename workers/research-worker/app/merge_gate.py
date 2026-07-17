"""Deterministic MA0 Merge Gate for agent candidates.

This module owns no storage and has no network/LLM dependencies.  It makes the
future single-writer rule explicit: an agent submits a candidate, while only the
merge gate can derive a new canonical ``ResearchStateCell``.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field

from app.agent_contracts import CellCandidate, CellTaskBundle
from app.models import CellSupportStatus, CellVerdict, ResearchEvidenceCard, ResearchStateCell


class MergeDecisionType(str, Enum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    IDEMPOTENT_REPLAY = "IDEMPOTENT_REPLAY"


class MergeReasonCode(str, Enum):
    VERIFIED_AND_VERSION_MATCHED = "VERIFIED_AND_VERSION_MATCHED"
    MERGE_ALREADY_APPLIED = "MERGE_ALREADY_APPLIED"
    STALE_PLAN_REVISION = "STALE_PLAN_REVISION"
    STALE_ENTITY_SET_VERSION = "STALE_ENTITY_SET_VERSION"
    STALE_CELL_VERSION = "STALE_CELL_VERSION"
    FENCING_TOKEN_MISMATCH = "FENCING_TOKEN_MISMATCH"
    LEASE_EPOCH_MISMATCH = "LEASE_EPOCH_MISMATCH"
    TARGET_NOT_IN_TASK = "TARGET_NOT_IN_TASK"
    CELL_BINDING_MISMATCH = "CELL_BINDING_MISMATCH"
    EVIDENCE_BINDING_INVALID = "EVIDENCE_BINDING_INVALID"
    VERDICT_NOT_SUPPORTS = "VERDICT_NOT_SUPPORTS"
    CELL_FROZEN = "CELL_FROZEN"
    VERIFIED_VALUE_CONFLICT = "VERIFIED_VALUE_CONFLICT"


class MergeAuthority(BaseModel):
    plan_revision: int = Field(ge=0)
    entity_set_version: int = Field(ge=0)
    lease_epoch: int = Field(ge=1)
    fencing_token: int = Field(ge=1)


class MergeOutcome(BaseModel):
    decision: MergeDecisionType
    reason_code: MergeReasonCode
    cell: ResearchStateCell
    accepted_evidence_ids: list[str] = Field(default_factory=list)


def merge_candidate(
    *,
    cell: ResearchStateCell,
    task: CellTaskBundle,
    candidate: CellCandidate,
    verdict: CellVerdict,
    evidence_by_id: dict[str, ResearchEvidenceCard],
    authority: MergeAuthority,
    merge_id: str,
) -> MergeOutcome:
    """Validate and merge one candidate without mutating the input cell."""
    if cell.last_merge_id and cell.last_merge_id == merge_id:
        return _outcome(MergeDecisionType.IDEMPOTENT_REPLAY, MergeReasonCode.MERGE_ALREADY_APPLIED, cell)
    if task.plan_revision != authority.plan_revision or cell.plan_revision != authority.plan_revision:
        return _reject(MergeReasonCode.STALE_PLAN_REVISION, cell)
    if task.entity_set_version != authority.entity_set_version or cell.entity_set_version != authority.entity_set_version:
        return _reject(MergeReasonCode.STALE_ENTITY_SET_VERSION, cell)
    if candidate.fencing_token != task.fencing_token or task.fencing_token != authority.fencing_token:
        return _reject(MergeReasonCode.FENCING_TOKEN_MISMATCH, cell)
    if candidate.lease_epoch != task.lease_epoch or task.lease_epoch != authority.lease_epoch:
        return _reject(MergeReasonCode.LEASE_EPOCH_MISMATCH, cell)
    target = next((item for item in task.target_cells if item.cell_id == candidate.cell_id), None)
    if target is None:
        return _reject(MergeReasonCode.TARGET_NOT_IN_TASK, cell)
    if (
        candidate.entity_id != task.entity_id
        or candidate.entity_id != cell.entity_id
        or candidate.column_key != target.column_key
        or candidate.column_key != cell.column_key
        or candidate.cell_id != cell.cell_id
    ):
        return _reject(MergeReasonCode.CELL_BINDING_MISMATCH, cell)
    if target.expected_version != cell.version or candidate.base_cell_version != cell.version:
        return _reject(MergeReasonCode.STALE_CELL_VERSION, cell)
    if cell.status == "FROZEN":
        return _reject(MergeReasonCode.CELL_FROZEN, cell)
    if verdict.cell_id != cell.cell_id or verdict.status != CellSupportStatus.SUPPORTS:
        return _reject(MergeReasonCode.VERDICT_NOT_SUPPORTS, cell)

    accepted_evidence_ids = _validated_evidence_ids(candidate, verdict, evidence_by_id)
    if accepted_evidence_ids is None:
        return _reject(MergeReasonCode.EVIDENCE_BINDING_INVALID, cell)
    if cell.status == "VERIFIED" and _normalize(cell.candidate_value) != _normalize(candidate.value):
        return _reject(MergeReasonCode.VERIFIED_VALUE_CONFLICT, cell)

    evidence_refs = list(dict.fromkeys([*cell.evidence_refs, *accepted_evidence_ids]))
    merged = cell.model_copy(
        update={
            "candidate_value": candidate.value,
            "status": "VERIFIED",
            "confidence": candidate.confidence,
            "evidence_refs": evidence_refs,
            "last_verifier_decision": f"MERGE_GATE:{MergeReasonCode.VERIFIED_AND_VERSION_MATCHED.value}",
            "verdict": verdict.status,
            "verdict_reason": verdict.reason,
            "verdict_confidence": verdict.confidence,
            "verdict_used_llm": verdict.used_llm,
            "version": cell.version + 1,
            "last_merge_id": merge_id,
        }
    )
    return _outcome(
        MergeDecisionType.ACCEPTED,
        MergeReasonCode.VERIFIED_AND_VERSION_MATCHED,
        merged,
        accepted_evidence_ids,
    )


def _validated_evidence_ids(
    candidate: CellCandidate,
    verdict: CellVerdict,
    evidence_by_id: dict[str, ResearchEvidenceCard],
) -> list[str] | None:
    verdict_ids = set(verdict.evidence_ids)
    accepted: list[str] = []
    for evidence_id in candidate.evidence_ids:
        evidence = evidence_by_id.get(evidence_id)
        if evidence is None or evidence_id not in verdict_ids:
            return None
        if evidence.entity_id != candidate.entity_id or evidence.column_key != candidate.column_key:
            return None
        accepted.append(evidence_id)
    return accepted or None


def _normalize(value: str) -> str:
    return " ".join(value.strip().lower().split())


def _reject(reason_code: MergeReasonCode, cell: ResearchStateCell) -> MergeOutcome:
    return _outcome(MergeDecisionType.REJECTED, reason_code, cell)


def _outcome(
    decision: MergeDecisionType,
    reason_code: MergeReasonCode,
    cell: ResearchStateCell,
    accepted_evidence_ids: list[str] | None = None,
) -> MergeOutcome:
    return MergeOutcome(
        decision=decision,
        reason_code=reason_code,
        cell=cell,
        accepted_evidence_ids=accepted_evidence_ids or [],
    )
