from __future__ import annotations

from pydantic import BaseModel, Field


class SourceScopeItem(BaseModel):
    source_id: str
    title: str
    summary: str = ""


class ContextSnapshot(BaseModel):
    context_snapshot_id: str = ""


class ControlPack(BaseModel):
    pack_type: str
    target_key: str
    task_neighborhood: str
    style_constraints: list[str] = Field(default_factory=list)
    structure_constraints: list[str] = Field(default_factory=list)
    terminology_policy: list[str] = Field(default_factory=list)
    forbidden_patterns: list[str] = Field(default_factory=list)
    evidence_policy: list[str] = Field(default_factory=list)
    interaction_policy: list[str] = Field(default_factory=list)
    review_checklist: list[str] = Field(default_factory=list)
    memory_object_ids: list[str] = Field(default_factory=list)


class ArtifactTaskInputPayload(BaseModel):
    action_key: str
    style_profile_key: str = ""
    context_snapshot_id: str = ""


class ArtifactTaskInput(BaseModel):
    task_id: str
    workspace_id: str
    target_id: str
    source_scope: list[SourceScopeItem] = Field(default_factory=list)
    context_snapshot: ContextSnapshot = Field(default_factory=ContextSnapshot)
    control_pack: ControlPack
    input_payload: ArtifactTaskInputPayload


class ArtifactExecutionPlan(BaseModel):
    action_key: str
    style_profile_key: str
    required_capabilities: list[str] = Field(default_factory=list)
    outline: list[str] = Field(default_factory=list)
    schema_gate_status: str
    schema_gate_rules: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ArtifactSectionDraft(BaseModel):
    heading: str
    body: str
    source_refs: list[str] = Field(default_factory=list)


class ArtifactVerificationResult(BaseModel):
    status: str
    passed_checks: list[str] = Field(default_factory=list)
    repaired_checks: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class ArtifactProgressEvent(BaseModel):
    phase: str
    progress_percent: int
    message: str
    metrics: dict[str, object] = Field(default_factory=dict)


class ArtifactTaskResult(BaseModel):
    result_type: str = "MARKDOWN"
    result_title: str
    result_payload: dict[str, object]
    trace_summary: str
    citations: list[dict[str, object]] = Field(default_factory=list)
