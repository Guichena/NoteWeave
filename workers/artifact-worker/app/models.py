"""共享流水线、冻结资料和回调使用的数据结构。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class SourceScopeItem(BaseModel):
    source_id: str
    title: str
    summary: str = ""
    sample_text: str = ""
    source_snapshot_id: str = ""
    source_window_id: str = ""
    material_windows: list[dict[str, object]] = Field(default_factory=list)
    material_gap: str = ""
    generated_by: str = ""
    generated_ref_id: str = ""
    source_type: str = "DOCUMENT_TEXT"
    source_uri: str = ""
    source_metadata: dict[str, object] = Field(default_factory=dict)
    related_source_ids: list[str] = Field(default_factory=list)


class ContextSnapshot(BaseModel):
    context_snapshot_id: str = ""


class ArtifactUpstreamRef(BaseModel):
    ref_type: str
    ref_id: str
    revision_id: str


class CanonicalContentSegment(BaseModel):
    segment_id: str
    text: str
    segment_role: str = "BODY"
    timestamp_hint: str = ""


class CanonicalContentObject(BaseModel):
    cco_id: str
    kind: str
    title: str
    plain_text: str
    segments: list[CanonicalContentSegment] = Field(default_factory=list)
    metadata: dict[str, object] = Field(default_factory=dict)
    source_trace: list[str] = Field(default_factory=list)


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
    skill_key: str = ""
    style_profile_key: str = ""
    context_snapshot_id: str = ""
    user_requirement: str = ""
    generation_brief: str = ""
    inputs: dict[str, object] = Field(default_factory=dict)


class ArtifactTaskInput(BaseModel):
    task_id: str
    workspace_id: str
    target_id: str
    input_snapshot_id: str = ""
    catalog_digest: str = ""
    replay_availability: str = "FULL"
    source_scope: list[SourceScopeItem] = Field(default_factory=list)
    upstream_refs: list[ArtifactUpstreamRef] = Field(default_factory=list)
    context_snapshot: ContextSnapshot = Field(default_factory=ContextSnapshot)
    control_pack: ControlPack
    input_payload: ArtifactTaskInputPayload


class ExecutionSpec(BaseModel):
    output_language: str = "zh"
    compilation_method: str = "RULE"
    compilation_trace: dict[str, object] = Field(default_factory=dict)
    skill_key: str = ""
    requested_action_key: str = ""
    resolved_action_key: str = ""
    goal: str = ""
    audience: str = ""
    focus_points: list[str] = Field(default_factory=list)
    style_directives: list[str] = Field(default_factory=list)
    constraints: list[str] = Field(default_factory=list)
    output_shape: str = "STRUCTURED_ARTIFACT"
    generation_brief: str = ""
    inputs: dict[str, object] = Field(default_factory=dict)
    notes: list[str] = Field(default_factory=list)


class ArtifactSectionDraft(BaseModel):
    heading: str
    body: str
    source_refs: list[str] = Field(default_factory=list)


class ArtifactSkillNodeTrace(BaseModel):
    node_id: str
    skill_key: str
    node_kind: str = ""
    purpose: str = ""
    prompt_recipe_id: str = ""
    llm_calls: int = 0
    validation_errors: list[str] = Field(default_factory=list)
    input_fields: list[str] = Field(default_factory=list)
    output_fields: list[str] = Field(default_factory=list)
    execution_trace: dict[str, object] = Field(default_factory=dict)
    output_summary: str
    verification_status: str
    verification_checks: list[str] = Field(default_factory=list)
    repair_actions: list[str] = Field(default_factory=list)
    repaired: bool = False


class ArtifactProgressEvent(BaseModel):
    phase: str
    progress_percent: int
    message: str
    metrics: dict[str, object] = Field(default_factory=dict)
    payload: dict[str, object] = Field(default_factory=dict)


class ContextPack(BaseModel):
    task_brief: str
    main_content: str
    segments: list[str] = Field(default_factory=list)
    execution_spec: dict[str, object] = Field(default_factory=dict)
    style_profile: dict[str, object] = Field(default_factory=dict)
    prompt_recipe: dict[str, object] = Field(default_factory=dict)
    user_preference: dict[str, object] = Field(default_factory=dict)
    output_contract: list[str] = Field(default_factory=list)
    source_trace: list[str] = Field(default_factory=list)


class ArtifactJobSnapshot(BaseModel):
    task_id: str
    workspace_id: str
    target_id: str
    action_key: str
    status: str


class ArtifactVersionSnapshot(BaseModel):
    version_id: str
    artifact_type: str
    title: str
    status: str
    summary: str
    parent_version_id: str = ""
    rollback_of_version_id: str = ""


class ArtifactTaskResult(BaseModel):
    result_type: str = "MARKDOWN"
    result_title: str
    result_payload: dict[str, object]
    trace_summary: str
    citations: list[dict[str, object]] = Field(default_factory=list)
    job_snapshot: ArtifactJobSnapshot
    version_snapshot: ArtifactVersionSnapshot
