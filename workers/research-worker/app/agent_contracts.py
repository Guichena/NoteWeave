"""MA0 typed contracts for future controlled multi-agent research execution.

These objects are deliberately independent from the current sequential runtime.
They describe immutable work/candidate data; they do not grant an agent authority
to mutate the canonical research ledger.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field, model_validator


class AgentRole(str, Enum):
    WIDE_DISCOVERY = "WIDE_DISCOVERY"
    DEEP_CELL = "DEEP_CELL"
    COUNTERFACTUAL = "COUNTERFACTUAL"
    CELL_VERIFIER = "CELL_VERIFIER"
    GLOBAL_VERIFIER = "GLOBAL_VERIFIER"


class AgentTaskStatus(str, Enum):
    PENDING = "PENDING"
    CLAIMED = "CLAIMED"
    RUNNING = "RUNNING"
    SUBMITTED = "SUBMITTED"
    VERIFIED = "VERIFIED"
    REJECTED = "REJECTED"
    RETRY_WAIT = "RETRY_WAIT"
    EXPIRED = "EXPIRED"
    FROZEN = "FROZEN"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"


class TaskBudget(BaseModel):
    search_calls: int = Field(default=0, ge=0)
    fetch_calls: int = Field(default=0, ge=0)
    read_windows: int = Field(default=0, ge=0)
    llm_calls: int = Field(default=0, ge=0)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    wall_clock_seconds: int = Field(default=0, ge=0)


class ExecutionUsage(BaseModel):
    search_calls: int = Field(default=0, ge=0)
    fetch_calls: int = Field(default=0, ge=0)
    read_windows: int = Field(default=0, ge=0)
    llm_calls: int = Field(default=0, ge=0)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    estimated_cost: float = Field(default=0.0, ge=0.0)
    latency_ms: float = Field(default=0.0, ge=0.0)


class TaskCellTarget(BaseModel):
    cell_id: str = Field(min_length=1)
    entity_id: str = Field(min_length=1)
    column_key: str = Field(min_length=1)
    expected_version: int = Field(ge=0)


class CellTaskBundle(BaseModel):
    task_id: str = Field(min_length=1)
    research_run_id: str = Field(min_length=1)
    role: AgentRole
    entity_id: str = Field(min_length=1)
    entity_set_version: int = Field(ge=0)
    plan_revision: int = Field(ge=0)
    branch_id: str = "branch-main"
    target_cells: list[TaskCellTarget] = Field(min_length=1, max_length=3)
    query_hints: list[str] = Field(default_factory=list)
    excluded_source_ids: list[str] = Field(default_factory=list)
    excluded_domains: list[str] = Field(default_factory=list)
    candidate_quorum: int = Field(default=1, ge=1, le=2)
    high_risk: bool = False
    budget: TaskBudget = Field(default_factory=TaskBudget)
    lease_epoch: int = Field(ge=1)
    fencing_token: int = Field(ge=1)
    idempotency_key: str = Field(min_length=1)

    @model_validator(mode="after")
    def targets_must_be_unique_and_for_bundle_entity(self) -> "CellTaskBundle":
        if self.candidate_quorum > 1 and not self.high_risk:
            raise ValueError("candidate_quorum greater than one requires high_risk task")
        target_ids: set[str] = set()
        for target in self.target_cells:
            if target.entity_id != self.entity_id:
                raise ValueError("all task targets must belong to bundle entity_id")
            if target.cell_id != f"{target.entity_id}:{target.column_key}":
                raise ValueError("target cell_id must equal entity_id:column_key")
            if target.cell_id in target_ids:
                raise ValueError("task target cell_id must be unique")
            target_ids.add(target.cell_id)
        return self


class CellCandidate(BaseModel):
    candidate_id: str = Field(min_length=1)
    task_id: str = Field(min_length=1)
    execution_id: str = Field(min_length=1)
    candidate_slot: int = Field(default=1, ge=1, le=2)
    entity_id: str = Field(min_length=1)
    cell_id: str = Field(min_length=1)
    column_key: str = Field(min_length=1)
    base_cell_version: int = Field(ge=0)
    value: str = Field(min_length=1)
    evidence_ids: list[str] = Field(min_length=1)
    confidence: float = Field(ge=0.0, le=1.0)
    source_diversity: int = Field(default=0, ge=0)
    agent_reason: str = ""
    lease_epoch: int = Field(ge=1)
    fencing_token: int = Field(ge=1)

    @model_validator(mode="after")
    def candidate_cell_key_must_be_canonical(self) -> "CellCandidate":
        if self.cell_id != f"{self.entity_id}:{self.column_key}":
            raise ValueError("candidate cell_id must equal entity_id:column_key")
        if len(set(self.evidence_ids)) != len(self.evidence_ids):
            raise ValueError("candidate evidence_ids must be unique")
        return self


class AgentExecutionResult(BaseModel):
    execution_id: str = Field(min_length=1)
    task_id: str = Field(min_length=1)
    lease_epoch: int = Field(ge=1)
    fencing_token: int = Field(ge=1)
    status: AgentTaskStatus
    termination_reason: str = ""
    candidates: list[CellCandidate] = Field(default_factory=list)
    usage: ExecutionUsage = Field(default_factory=ExecutionUsage)

    @model_validator(mode="after")
    def submitted_result_must_be_self_consistent(self) -> "AgentExecutionResult":
        if self.status in {AgentTaskStatus.SUBMITTED, AgentTaskStatus.FAILED, AgentTaskStatus.CANCELLED} and not self.termination_reason.strip():
            raise ValueError("terminal execution result requires termination_reason")
        candidate_ids: set[str] = set()
        for candidate in self.candidates:
            if candidate.task_id != self.task_id:
                raise ValueError("candidate task_id must match execution task_id")
            if candidate.execution_id != self.execution_id:
                raise ValueError("candidate execution_id must match execution_id")
            if candidate.lease_epoch != self.lease_epoch:
                raise ValueError("candidate lease_epoch must match execution lease_epoch")
            if candidate.fencing_token != self.fencing_token:
                raise ValueError("candidate fencing_token must match execution fencing_token")
            if candidate.candidate_id in candidate_ids:
                raise ValueError("candidate_id must be unique within execution result")
            candidate_ids.add(candidate.candidate_id)
        return self
