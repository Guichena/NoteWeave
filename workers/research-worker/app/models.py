from __future__ import annotations

from pydantic import BaseModel, Field


class SourceScopeItem(BaseModel):
    source_id: str
    title: str
    summary: str = ""
    sample_text: str = ""


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


class ResearchStepTrace(BaseModel):
    trace_id: str
    phase: str
    status: str
    message: str
    inputs: dict[str, object] = Field(default_factory=dict)
    outputs: dict[str, object] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


class ResearchHarnessDecision(BaseModel):
    decision: str
    reason: str
    next_phase: str = ""


class ResearchLoopDecision(BaseModel):
    decision: str
    reason: str
    round_no: int
    should_continue: bool = False
    recovery_actions: list[str] = Field(default_factory=list)


class ResearchLoopRoundSummary(BaseModel):
    round_no: int
    search_hit_count: int
    read_window_count: int
    evidence_card_count: int
    branch_decision: str
    global_decision: str
    loop_decision: ResearchLoopDecision


class ResearchSearchHit(BaseModel):
    hit_id: str
    source_id: str
    source_title: str
    query: str
    rank: int
    snippet: str
    confidence_score: float
    retrieval_reason: str
    search_angle: str = "direct"
    matched_fields: list[str] = Field(default_factory=list)
    coverage_score: float = 0.0
    url: str = ""
    provider: str = "workspace"
    adapter: str = "workspace"


class ResearchReadWindow(BaseModel):
    window_id: str
    hit_id: str
    source_id: str
    source_title: str
    query: str
    read_focus: str
    window_text: str
    retention_reason: str
    token_estimate: int
    url: str = ""
    provider: str = ""
    adapter: str = "workspace"
    snapshot_status: str = "WORKSPACE"
    snapshot_key: str = ""


class ResearchEvidenceCard(BaseModel):
    evidence_id: str
    window_id: str
    source_id: str
    source_title: str
    claim_text: str
    quote_text: str
    relation_type: str
    support_score: float
    conflict_score: float


class ResearchBranchDecision(BaseModel):
    branch_id: str
    decision: str
    branch_reason: str
    verifier_scope: str
    recovery_actions: list[str] = Field(default_factory=list)


class ResearchStateRow(BaseModel):
    row_id: str
    source_id: str
    source_title: str
    search_query: str
    read_focus: str
    evidence_id: str = ""
    claim_text: str = ""
    quote_text: str = ""
    evidence_excerpt: str
    relation_type: str = "SUPPORTS"
    support_score: float = 0.0
    conflict_score: float = 0.0
    support_level: str
    verifier_note: str


class ResearchStateLedger(BaseModel):
    columns: list[str] = Field(default_factory=list)
    rows: list[ResearchStateRow] = Field(default_factory=list)
    unresolved_questions: list[str] = Field(default_factory=list)
    search_hit_count: int = 0
    read_window_count: int = 0
    evidence_card_count: int = 0
    active_branch_id: str = "branch-main"


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
