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


class ResearchTaskInputPayload(BaseModel):
    question: str
    profile_key: str
    context_snapshot_id: str = ""


class ResearchTaskInput(BaseModel):
    task_id: str
    workspace_id: str
    target_id: str
    source_scope: list[SourceScopeItem] = Field(default_factory=list)
    context_snapshot: ContextSnapshot = Field(default_factory=ContextSnapshot)
    control_pack: ControlPack
    input_payload: ResearchTaskInputPayload


class ResearchPlan(BaseModel):
    normalized_question: str
    query_set: list[str] = Field(default_factory=list)
    report_sections: list[str] = Field(default_factory=list)
    state_columns: list[str] = Field(default_factory=list)
    stop_contract: dict[str, object] = Field(default_factory=dict)
    notes: list[str] = Field(default_factory=list)


class ResearchStateRow(BaseModel):
    row_id: str
    source_id: str
    source_title: str
    search_query: str
    read_focus: str
    evidence_excerpt: str
    support_level: str
    verifier_note: str


class ResearchStateLedger(BaseModel):
    columns: list[str] = Field(default_factory=list)
    rows: list[ResearchStateRow] = Field(default_factory=list)
    unresolved_questions: list[str] = Field(default_factory=list)


class LocalVerifierResult(BaseModel):
    status: str
    passed_checks: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    recovery_actions: list[str] = Field(default_factory=list)


class GlobalVerifierResult(BaseModel):
    status: str
    decision: str
    summary: str
    counterfactual_checks: list[str] = Field(default_factory=list)
    recovery_actions: list[str] = Field(default_factory=list)


class ResearchProgressEvent(BaseModel):
    phase: str
    progress_percent: int
    message: str
    metrics: dict[str, object] = Field(default_factory=dict)


class ResearchTaskResult(BaseModel):
    result_type: str = "RESEARCH_REPORT"
    result_title: str
    result_payload: dict[str, object]
    trace_summary: str
    citations: list[dict[str, object]] = Field(default_factory=list)
