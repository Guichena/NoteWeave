from __future__ import annotations

from app.agent_contracts import (
    AgentRole,
    CellCandidate,
    CellTaskBundle,
    TaskBudget,
    TaskCellTarget,
)
from app.merge_gate import (
    MergeAuthority,
    MergeDecisionType,
    MergeReasonCode,
    merge_candidate,
)
from app.models import CellSupportStatus, CellVerdict, ResearchEvidenceCard, ResearchStateCell


def _task(**overrides: object) -> CellTaskBundle:
    payload: dict[str, object] = {
        "task_id": "task-1",
        "research_run_id": "run-1",
        "role": AgentRole.DEEP_CELL,
        "entity_id": "entity-1",
        "entity_set_version": 3,
        "plan_revision": 2,
        "target_cells": [
            TaskCellTarget(
                cell_id="entity-1:method",
                entity_id="entity-1",
                column_key="method",
                expected_version=4,
            )
        ],
        "budget": TaskBudget(llm_calls=4),
        "lease_epoch": 1,
        "fencing_token": 7,
        "idempotency_key": "task-1-key",
    }
    payload.update(overrides)
    return CellTaskBundle.model_validate(payload)


def _cell(**overrides: object) -> ResearchStateCell:
    payload: dict[str, object] = {
        "cell_id": "entity-1:method",
        "row_id": "entity-1",
        "entity_id": "entity-1",
        "column_key": "method",
        "candidate_value": "",
        "status": "CANDIDATE_READY",
        "version": 4,
        "plan_revision": 2,
        "entity_set_version": 3,
    }
    payload.update(overrides)
    return ResearchStateCell.model_validate(payload)


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


def _verdict(**overrides: object) -> CellVerdict:
    payload: dict[str, object] = {
        "cell_id": "entity-1:method",
        "status": CellSupportStatus.SUPPORTS,
        "confidence": 0.9,
        "reason": "direct quote grounds value",
        "evidence_ids": ["ev-1"],
        "used_llm": False,
    }
    payload.update(overrides)
    return CellVerdict.model_validate(payload)


def _evidence(**overrides: object) -> ResearchEvidenceCard:
    payload: dict[str, object] = {
        "evidence_id": "ev-1",
        "window_id": "window-1",
        "source_id": "source-1",
        "source_title": "Primary source",
        "claim_text": "The method uses retrieval augmented generation.",
        "quote_text": "The method uses retrieval augmented generation.",
        "relation_type": "SUPPORTS",
        "support_score": 0.9,
        "conflict_score": 0.0,
        "entity_id": "entity-1",
        "column_key": "method",
    }
    payload.update(overrides)
    return ResearchEvidenceCard.model_validate(payload)


def _authority(**overrides: object) -> MergeAuthority:
    payload: dict[str, object] = {
        "plan_revision": 2,
        "entity_set_version": 3,
        "lease_epoch": 1,
        "fencing_token": 7,
    }
    payload.update(overrides)
    return MergeAuthority.model_validate(payload)


def _merge(**overrides: object):
    payload: dict[str, object] = {
        "cell": _cell(),
        "task": _task(),
        "candidate": _candidate(),
        "verdict": _verdict(),
        "evidence_by_id": {"ev-1": _evidence()},
        "authority": _authority(),
        "merge_id": "merge-1",
    }
    payload.update(overrides)
    return merge_candidate(**payload)


def test_merge_gate_should_accept_grounded_current_candidate_without_mutating_input() -> None:
    original = _cell()
    outcome = _merge(cell=original)

    assert outcome.decision == MergeDecisionType.ACCEPTED
    assert outcome.reason_code == MergeReasonCode.VERIFIED_AND_VERSION_MATCHED
    assert outcome.cell.version == 5
    assert outcome.cell.status == "VERIFIED"
    assert outcome.cell.candidate_value == "retrieval augmented generation"
    assert outcome.cell.evidence_refs == ["ev-1"]
    assert outcome.cell.last_merge_id == "merge-1"
    assert original.version == 4
    assert original.status == "CANDIDATE_READY"


def test_merge_gate_should_reject_stale_version_plan_entity_set_and_fencing() -> None:
    assert _merge(candidate=_candidate(base_cell_version=3)).reason_code == MergeReasonCode.STALE_CELL_VERSION
    assert _merge(authority=_authority(plan_revision=3)).reason_code == MergeReasonCode.STALE_PLAN_REVISION
    assert _merge(authority=_authority(entity_set_version=4)).reason_code == MergeReasonCode.STALE_ENTITY_SET_VERSION
    assert _merge(candidate=_candidate(fencing_token=8)).reason_code == MergeReasonCode.FENCING_TOKEN_MISMATCH
    assert _merge(candidate=_candidate(lease_epoch=2)).reason_code == MergeReasonCode.LEASE_EPOCH_MISMATCH


def test_merge_gate_should_reject_task_scope_bad_evidence_non_support_and_frozen_cell() -> None:
    assert _merge(candidate=_candidate(cell_id="entity-1:other", column_key="other")).reason_code == MergeReasonCode.TARGET_NOT_IN_TASK
    assert _merge(evidence_by_id={"ev-1": _evidence(column_key="other")}).reason_code == MergeReasonCode.EVIDENCE_BINDING_INVALID
    assert _merge(verdict=_verdict(status=CellSupportStatus.PARTIALLY_SUPPORTS)).reason_code == MergeReasonCode.VERDICT_NOT_SUPPORTS
    assert _merge(cell=_cell(status="FROZEN")).reason_code == MergeReasonCode.CELL_FROZEN


def test_merge_gate_should_be_idempotent_for_same_merge_id_without_incrementing_version() -> None:
    accepted = _merge()
    replay = _merge(cell=accepted.cell)

    assert replay.decision == MergeDecisionType.IDEMPOTENT_REPLAY
    assert replay.reason_code == MergeReasonCode.MERGE_ALREADY_APPLIED
    assert replay.cell.version == accepted.cell.version


def test_merge_gate_should_not_overwrite_verified_cell_with_different_value() -> None:
    accepted = _merge()
    conflicting = _candidate(base_cell_version=accepted.cell.version, value="a different method")
    next_task = _task(target_cells=[TaskCellTarget(
        cell_id="entity-1:method",
        entity_id="entity-1",
        column_key="method",
        expected_version=accepted.cell.version,
    )])
    outcome = _merge(cell=accepted.cell, task=next_task, candidate=conflicting, merge_id="merge-2")

    assert outcome.decision == MergeDecisionType.REJECTED
    assert outcome.reason_code == MergeReasonCode.VERIFIED_VALUE_CONFLICT
