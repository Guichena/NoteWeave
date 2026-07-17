"""Strict server-authoritative Agent task snapshot consumed after claim."""

from __future__ import annotations

import hashlib
import hmac
import json
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class SnapshotTargetCell(_Strict):
    cell_id: str = Field(min_length=1, max_length=256)
    expected_version: int = Field(ge=0)


class TaskSnapshotTrustError(RuntimeError):
    """Claimed task scope is incomplete or differs from the server authority."""


class ResearchAgentTaskSnapshot(_Strict):
    schema_version: Literal["research-agent-task-snapshot.v1", "research-agent-task-snapshot.v2"]
    task_id: str = Field(min_length=1, max_length=64)
    research_run_id: str = Field(min_length=1, max_length=64)
    workspace_id: str = Field(min_length=1, max_length=64)
    role: Literal["DEEP_CELL", "WIDE_DISCOVERY", "COUNTERFACTUAL", "EVIDENCE_AUDIT", "SYNTHESIS"]
    entity_id: str = Field(min_length=1, max_length=160)
    branch_id: str = Field(min_length=1, max_length=96)
    plan_revision: int = Field(ge=0)
    entity_set_version: int = Field(ge=0)
    lease_epoch: int = Field(ge=1)
    fencing_token: int = Field(ge=1)
    target_cells: list[SnapshotTargetCell] = Field(min_length=1, max_length=3)
    budget: dict[str, int | float] = Field(min_length=1)
    provider_key: str = Field(min_length=1, max_length=128)
    source_policy: dict[str, object] = Field(min_length=1)
    query_policy: dict[str, object] = Field(min_length=1)
    logical_task_key: str | None = Field(default=None, min_length=1, max_length=191)
    quorum_group_key: str | None = Field(default=None, min_length=1, max_length=128)
    candidate_quorum: int | None = Field(default=None, ge=1, le=2)
    candidate_slot: int | None = Field(default=None, ge=1, le=2)
    high_risk: bool | None = None
    snapshot_digest: str = Field(min_length=8, max_length=128)

    @model_validator(mode="after")
    def validate_quorum_scope(self) -> "ResearchAgentTaskSnapshot":
        values = (self.logical_task_key, self.quorum_group_key, self.candidate_quorum, self.candidate_slot, self.high_risk)
        if self.schema_version == "research-agent-task-snapshot.v1":
            if any(value is not None for value in values):
                raise ValueError("v1 task snapshot cannot carry quorum scope")
            return self
        if self.logical_task_key is None or self.candidate_quorum is None or self.candidate_slot is None or self.high_risk is None:
            raise ValueError("v2 task snapshot requires complete quorum scope")
        if self.candidate_slot > self.candidate_quorum:
            raise ValueError("candidate slot exceeds quorum")
        if self.candidate_quorum == 2 and (not self.high_risk or self.quorum_group_key is None):
            raise ValueError("quorum task requires high-risk group scope")
        if self.candidate_quorum == 1 and self.high_risk:
            raise ValueError("high-risk task requires quorum")
        return self

    def require_valid_digest(self) -> None:
        expected = snapshot_digest(self.model_dump(mode="json", exclude={"snapshot_digest"}, exclude_none=True))
        if not hmac.compare_digest(self.snapshot_digest, expected):
            raise ValueError("task snapshot digest is invalid")


def snapshot_digest(payload_without_digest: dict[str, object]) -> str:
    """Return the cross-runtime canonical SHA-256 used by the Backend claim response."""
    canonical = json.dumps(
        payload_without_digest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


def require_trusted_claim_snapshot(claim: object) -> ResearchAgentTaskSnapshot:
    """Validate a Backend claim before any Agent executor may create or call a tool."""
    raw_snapshot = getattr(claim, "task_snapshot_json", None)
    claim_digest = getattr(claim, "snapshot_digest", None)
    if not isinstance(raw_snapshot, str) or not raw_snapshot.strip() or not isinstance(claim_digest, str) or not claim_digest.strip():
        raise TaskSnapshotTrustError("claimed task has no trusted task snapshot")
    try:
        snapshot = ResearchAgentTaskSnapshot.model_validate_json(raw_snapshot)
        snapshot.require_valid_digest()
    except Exception as exc:
        raise TaskSnapshotTrustError("claimed task snapshot is invalid") from exc
    if (
        snapshot.snapshot_digest != claim_digest
        or snapshot.task_id != getattr(claim, "agent_task_id", None)
        or snapshot.lease_epoch != getattr(claim, "lease_epoch", None)
        or snapshot.fencing_token != getattr(claim, "fencing_token", None)
    ):
        raise TaskSnapshotTrustError("claimed task snapshot does not match claim identity")
    return snapshot
