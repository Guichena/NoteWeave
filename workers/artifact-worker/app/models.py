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
    action_key: str = ""
    style_profile_key: str = ""
    prompt_recipe_id: str = ""
    context_snapshot_id: str = ""
    user_requirement: str = ""
    generation_brief: str = ""
    inputs: dict[str, object] = Field(default_factory=dict)
    requested_capabilities: list[str] = Field(default_factory=list)
    writeback_mode: str = "NONE"


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
    # Worker-local material fetched through the Host's fenced reference endpoint.
    frozen_video_material: dict[str, object] = Field(default_factory=dict)
    frozen_video_knowledge_plan: dict[str, object] = Field(default_factory=dict)


class ArtifactSkillDefinition(BaseModel):
    skill_key: str
    display_name: str
    description: str
    input_schema: dict[str, object] = Field(default_factory=dict)

    @property
    def requires_url_input(self) -> bool:
        required_keys = self._read_required_input_keys()
        return any(self._is_url_like_key(key) for key in required_keys)

    @property
    def url_input_keys(self) -> list[str]:
        properties = self.input_schema.get("properties", {})
        if not isinstance(properties, dict):
            return []
        candidate_keys = [
            str(key).strip()
            for key in properties.keys()
            if self._is_url_like_key(str(key))
        ]
        normalized_keys: list[str] = []
        for key in candidate_keys:
            if key and key not in normalized_keys:
                normalized_keys.append(key)
        return normalized_keys

    def _read_required_input_keys(self) -> list[str]:
        required_values = self.input_schema.get("required", [])
        if not isinstance(required_values, list):
            return []
        return [
            str(value).strip()
            for value in required_values
            if str(value).strip()
        ]

    def _is_url_like_key(self, key: str) -> bool:
        normalized_key = key.strip().lower()
        return normalized_key == "url" or normalized_key.endswith("_url")


class ExecutionSpec(BaseModel):
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


class ProductionAction(BaseModel):
    action_key: str
    display_name: str
    artifact_type: str
    action_origin: str = "BUILTIN"
    template_action_key: str = ""
    resolver_keywords: list[str] = Field(default_factory=list)
    resolver_priority: int = 0
    preferred_routes: list[str] = Field(default_factory=list)
    preferred_style_profiles: list[str] = Field(default_factory=list)
    preferred_source_platforms: list[str] = Field(default_factory=list)
    preferred_structure_keywords: list[str] = Field(default_factory=list)
    preferred_task_neighborhoods: list[str] = Field(default_factory=list)
    default_style_profile_key: str
    default_skill_graph_key: str
    default_prompt_recipe_id: str
    required_evidence_level: str
    allow_custom_mcp: bool = False
    allow_writeback: bool = False
    allowed_writeback_modes: list[str] = Field(default_factory=list)
    supported_capabilities: list[str] = Field(default_factory=list)
    output_sections: list[str] = Field(default_factory=list)
    required_phrases: list[str] = Field(default_factory=list)


class StyleProfile(BaseModel):
    profile_key: str
    profile_name: str
    tone: str
    structure_mode: str
    audience_type: str
    length_preference: str
    citation_density: str
    format_constraints: list[str] = Field(default_factory=list)


class CustomStyleProfileRegistration(BaseModel):
    profile_key: str
    profile_name: str
    base_profile_key: str
    tone: str = ""
    structure_mode: str = ""
    audience_type: str = ""
    length_preference: str = ""
    citation_density: str = ""
    format_constraints: list[str] = Field(default_factory=list)


class SkillDefinition(BaseModel):
    skill_key: str
    template_skill_key: str = ""
    skill_version: str
    skill_type: str
    input_contract: list[str] = Field(default_factory=list)
    output_contract: list[str] = Field(default_factory=list)
    required_capabilities: list[str] = Field(default_factory=list)
    verifier_policy: list[str] = Field(default_factory=list)
    repair_policy: list[str] = Field(default_factory=list)


class SkillGraphNode(BaseModel):
    node_id: str
    skill_key: str
    purpose: str
    optional: bool = False


class SkillGraphEdge(BaseModel):
    from_node: str
    to_node: str
    edge_type: str


class SkillGraphTemplate(BaseModel):
    graph_key: str
    graph_name: str
    action_type: str
    nodes: list[SkillGraphNode] = Field(default_factory=list)
    edges: list[SkillGraphEdge] = Field(default_factory=list)
    schema_contract: list[str] = Field(default_factory=list)


class CustomSkillDefinitionRegistration(BaseModel):
    skill_key: str
    base_skill_key: str
    skill_version: str = ""
    skill_type: str = ""
    input_contract: list[str] = Field(default_factory=list)
    output_contract: list[str] = Field(default_factory=list)
    required_capabilities: list[str] = Field(default_factory=list)
    verifier_policy: list[str] = Field(default_factory=list)
    repair_policy: list[str] = Field(default_factory=list)


class CustomSkillGraphTemplateRegistration(BaseModel):
    graph_key: str
    graph_name: str
    base_graph_key: str
    action_type: str = ""
    nodes: list[SkillGraphNode] = Field(default_factory=list)
    edges: list[SkillGraphEdge] = Field(default_factory=list)
    schema_contract: list[str] = Field(default_factory=list)


class PromptRecipe(BaseModel):
    recipe_id: str
    recipe_name: str
    supported_actions: list[str] = Field(default_factory=list)
    generation_mode: str
    system_intent: str
    section_guidance: dict[str, str] = Field(default_factory=dict)
    node_guidance: dict[str, str] = Field(default_factory=dict)
    citation_policy: list[str] = Field(default_factory=list)
    repair_hints: list[str] = Field(default_factory=list)
    recipe_notes: list[str] = Field(default_factory=list)


class CustomMcpToolRegistration(BaseModel):
    capability_name: str
    tool_name: str
    scope_type: str = ""
    approval_mode: str = ""
    risk_level: str = ""
    allowed_actions: list[str] = Field(default_factory=list)
    supported_routes: list[str] = Field(default_factory=list)
    supported_actions: list[str] = Field(default_factory=list)
    supported_skill_graphs: list[str] = Field(default_factory=list)
    preference_rank: int = 100
    selection_reason_hint: str = ""
    output_kind: str = ""
    notes: list[str] = Field(default_factory=list)


class CustomMcpServerRegistration(BaseModel):
    server_id: str
    display_name: str
    endpoint_kind: str = "mcp"
    launch_transport: str = "stdio"
    launch_command: str = ""
    launch_args: list[str] = Field(default_factory=list)
    working_directory: str = ""
    launch_env: dict[str, str] = Field(default_factory=dict)
    blueprint_key: str = ""
    registration_origin: str = "CUSTOM"
    server_notes: list[str] = Field(default_factory=list)
    tools: list[CustomMcpToolRegistration] = Field(default_factory=list)


class ActionResolutionDecision(BaseModel):
    requested_action_key: str
    resolved_action_key: str
    resolution_mode: str
    reason_code: str
    route_basis: list[str] = Field(default_factory=list)
    winning_signals: list[str] = Field(default_factory=list)
    candidate_scores: list[dict[str, object]] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class CapabilityBinding(BaseModel):
    capability_name: str
    server_id: str
    tool_name: str
    scope_type: str
    approval_mode: str
    risk_level: str
    allowed_actions: list[str] = Field(default_factory=list)


class CapabilityUnionPolicyDecision(BaseModel):
    policy_key: str
    action_scope: str
    skill_scope: str
    capability_scope: list[str] = Field(default_factory=list)
    workspace_scope: str
    decision: str
    reason_code: str
    notes: list[str] = Field(default_factory=list)
    blocked_capabilities: list[str] = Field(default_factory=list)


class ContentAcquisitionStep(BaseModel):
    step_id: str
    step_type: str
    description: str
    capability_name: str = ""
    status: str = "PLANNED"


class ContentAcquisitionSourcePlan(BaseModel):
    source_id: str
    title: str
    source_type: str
    source_platform: str
    adapter_route: str
    normalization_target_kind: str
    planned_operations: list[str] = Field(default_factory=list)
    required_capabilities: list[str] = Field(default_factory=list)
    related_source_ids: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ContentAcquisitionPlan(BaseModel):
    strategy_key: str
    primary_strategy: str
    required_capabilities: list[str] = Field(default_factory=list)
    steps: list[ContentAcquisitionStep] = Field(default_factory=list)
    route_summary: dict[str, int] = Field(default_factory=dict)
    source_plans: list[ContentAcquisitionSourcePlan] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class AcquisitionProviderAttempt(BaseModel):
    provider_id: str = ""
    server_id: str = ""
    tool_name: str = ""
    discovery_status: str = ""
    provider_status: str = ""
    health_status: str = ""
    approval_status: str = ""
    selection_reason: str = ""
    preference_rank: int = 0
    attempt_result: str = ""


class AcquisitionOperationReceipt(BaseModel):
    operation_key: str
    status: str
    request_id: str = ""
    input_locator: str = ""
    tool_arguments: dict[str, object] = Field(default_factory=dict)
    requested_at: str = ""
    completed_at: str = ""
    provider_receipt_id: str = ""
    provider_job_id: str = ""
    adapter_callback_token: str = ""
    delivery_id: str = ""
    callback_token: str = ""
    provider_job_status: str = ""
    callback_status: str = ""
    callback_received_at: str = ""
    capability_name: str = ""
    provider_id: str = ""
    server_id: str = ""
    tool_name: str = ""
    selection_reason: str = ""
    discovery_status: str = ""
    last_discovered_at: str = ""
    provider_status: str = ""
    health_status: str = ""
    last_checked_at: str = ""
    approval_status: str = ""
    output_kind: str = ""
    result_locator: str = ""
    output_summary: str = ""
    content_digest: str = ""
    payload_char_count: int = 0
    segment_count: int = 0
    source_ref_count: int = 0
    error_code: str = ""
    error_message: str = ""
    retryable: bool = False
    provider_attempts: list[AcquisitionProviderAttempt] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class AcquisitionSourceReceipt(BaseModel):
    source_id: str
    title: str
    source_platform: str
    adapter_route: str
    normalization_target_kind: str
    status: str
    operations: list[AcquisitionOperationReceipt] = Field(default_factory=list)
    produced_cco_ids: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class AcquisitionReceipt(BaseModel):
    receipt_id: str
    status: str
    execution_mode: str
    primary_strategy: str
    route_summary: dict[str, int] = Field(default_factory=dict)
    source_receipts: list[AcquisitionSourceReceipt] = Field(default_factory=list)
    executed_capabilities: list[str] = Field(default_factory=list)
    wait_reason: dict[str, object] = Field(default_factory=dict)
    notes: list[str] = Field(default_factory=list)


class AcquisitionRuntimeOperation(BaseModel):
    request_id: str
    task_id: str
    source_id: str
    operation_key: str
    status: str
    capability_name: str = ""
    provider_id: str = ""
    server_id: str = ""
    tool_name: str = ""
    provider_status: str = ""
    health_status: str = ""
    input_locator: str = ""
    tool_arguments: dict[str, object] = Field(default_factory=dict)
    input_digest: str = ""
    requested_at: str = ""
    completed_at: str = ""
    provider_receipt_id: str = ""
    provider_job_id: str = ""
    adapter_callback_token: str = ""
    delivery_id: str = ""
    callback_token: str = ""
    provider_job_status: str = ""
    callback_status: str = ""
    dispatch_count: int = 0
    dispatched_at: str = ""
    callback_deadline_at: str = ""
    callback_received_at: str = ""
    result_locator: str = ""
    output_summary: str = ""
    content_digest: str = ""
    payload_char_count: int = 0
    segment_count: int = 0
    source_ref_count: int = 0
    error_code: str = ""
    error_message: str = ""
    retryable: bool = False
    last_error_code: str = ""
    last_error_message: str = ""
    provider_delivery_attempts: list[dict[str, object]] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class AcquisitionRuntimeCallbackReceipt(BaseModel):
    receipt_id: str
    request_id: str
    task_id: str
    source_id: str
    operation_key: str
    delivery_id: str = ""
    callback_token: str = ""
    provider_job_status: str = ""
    callback_status: str
    provider_receipt_id: str = ""
    provider_job_id: str = ""
    result_locator: str = ""
    completed_at: str = ""
    input_digest: str = ""
    dispatch_count: int = 0
    error_code: str = ""
    error_message: str = ""
    notes: list[str] = Field(default_factory=list)


class ApprovalGateDecision(BaseModel):
    decision: str
    reason_code: str
    required_capabilities: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ApprovalRuntimeTrace(BaseModel):
    status: str
    decision: str
    reason_code: str
    required_capabilities: list[str] = Field(default_factory=list)
    pending_capabilities: list[str] = Field(default_factory=list)
    satisfied_capabilities: list[str] = Field(default_factory=list)
    approval_request: dict[str, object] = Field(default_factory=dict)
    capability_decisions: list[dict[str, object]] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class CapabilityUnionRuntimeTrace(BaseModel):
    status: str
    decision: str
    reason_code: str
    policy_key: str
    action_scope: str
    skill_scope: str
    workspace_scope: str
    capability_scope: list[str] = Field(default_factory=list)
    external_network_capabilities: list[str] = Field(default_factory=list)
    writeback_capabilities: list[str] = Field(default_factory=list)
    blocked_capabilities: list[str] = Field(default_factory=list)
    capability_decisions: list[dict[str, object]] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class EvidenceGateDecision(BaseModel):
    decision: str
    reason_code: str
    minimum_source_count: int = 0
    required_citation_density: str = "LOW"
    notes: list[str] = Field(default_factory=list)


class EvidenceCoverageReport(BaseModel):
    status: str
    required_citation_density: str = "LOW"
    section_count: int = 0
    covered_section_count: int = 0
    coverage_ratio: float = 0.0
    supporting_source_ids: list[str] = Field(default_factory=list)
    section_evidence: list[dict[str, object]] = Field(default_factory=list)
    sections_missing_evidence: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class WritebackGateDecision(BaseModel):
    decision: str
    reason_code: str
    requested_mode: str
    allowed_target: str = ""
    execution_mode: str = "NONE"
    required_capabilities: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ArtifactSkillNodePlan(BaseModel):
    node_id: str
    skill_key: str
    purpose: str


class ArtifactRuntimePlan(BaseModel):
    graph_key: str
    graph_name: str
    activated_nodes: list[str] = Field(default_factory=list)
    allowed_capabilities: list[str] = Field(default_factory=list)
    verifier_policies: list[str] = Field(default_factory=list)
    repair_policies: list[str] = Field(default_factory=list)
    output_contract: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ArtifactExecutionPlan(BaseModel):
    plan_id: str
    skill_key: str = ""
    action_key: str
    action_display_name: str
    artifact_type: str
    action_origin: str = "BUILTIN"
    template_action_key: str = ""
    action_resolution: ActionResolutionDecision
    execution_spec: ExecutionSpec
    style_profile_key: str
    style_profile_name: str
    skill_graph_key: str
    runtime_plan: ArtifactRuntimePlan
    prompt_recipe: PromptRecipe
    content_acquisition_plan: ContentAcquisitionPlan
    required_capabilities: list[str] = Field(default_factory=list)
    lazy_loaded_capabilities: list[str] = Field(default_factory=list)
    deferred_capabilities: list[str] = Field(default_factory=list)
    outline: list[str] = Field(default_factory=list)
    schema_gate_status: str
    schema_gate_rules: list[str] = Field(default_factory=list)
    capability_union_policy: CapabilityUnionPolicyDecision
    approval_gate: ApprovalGateDecision
    evidence_gate: EvidenceGateDecision
    writeback_gate: WritebackGateDecision
    node_sequence: list[ArtifactSkillNodePlan] = Field(default_factory=list)
    output_contract: list[str] = Field(default_factory=list)
    required_phrases: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ArtifactSectionDraft(BaseModel):
    heading: str
    body: str
    source_refs: list[str] = Field(default_factory=list)


class ArtifactSkillNodeTrace(BaseModel):
    node_id: str
    skill_key: str
    output_summary: str
    verification_status: str
    verification_checks: list[str] = Field(default_factory=list)
    repair_actions: list[str] = Field(default_factory=list)
    repaired: bool = False


class ArtifactVerificationResult(BaseModel):
    status: str
    passed_checks: list[str] = Field(default_factory=list)
    repaired_checks: list[str] = Field(default_factory=list)
    failed_checks: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)


class ArtifactContractCheckTrace(BaseModel):
    label: str
    status: str
    detail: str
    metadata: dict[str, object] = Field(default_factory=dict)


class ArtifactRepairSummaryTrace(BaseModel):
    total_repair_count: int = 0
    local_repair_count: int = 0
    node_repair_count: int = 0
    affected_sections: list[str] = Field(default_factory=list)
    affected_nodes: list[str] = Field(default_factory=list)
    local_repair_checks: list[str] = Field(default_factory=list)
    node_repair_actions: list[str] = Field(default_factory=list)
    category_counts: dict[str, int] = Field(default_factory=dict)
    notes: list[str] = Field(default_factory=list)


class ArtifactOutputContractTrace(BaseModel):
    status: str
    outline_checks: list[ArtifactContractCheckTrace] = Field(default_factory=list)
    phrase_checks: list[ArtifactContractCheckTrace] = Field(default_factory=list)
    action_checks: list[ArtifactContractCheckTrace] = Field(default_factory=list)
    evidence_checks: list[ArtifactContractCheckTrace] = Field(default_factory=list)
    repair_summary: ArtifactRepairSummaryTrace = Field(default_factory=ArtifactRepairSummaryTrace)
    repaired_checks: list[str] = Field(default_factory=list)
    passed_checks: list[str] = Field(default_factory=list)
    failed_checks: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ArtifactLifecycleStepTrace(BaseModel):
    phase: str
    status: str
    progress_percent: int
    message: str
    metrics: dict[str, object] = Field(default_factory=dict)


class ArtifactLifecycleTrace(BaseModel):
    status: str
    current_phase: str
    steps: list[ArtifactLifecycleStepTrace] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ArtifactProgressEvent(BaseModel):
    phase: str
    progress_percent: int
    message: str
    metrics: dict[str, object] = Field(default_factory=dict)
    payload: dict[str, object] = Field(default_factory=dict)


class ArtifactCapabilityResolution(BaseModel):
    available_capabilities: list[str] = Field(default_factory=list)
    lazy_loaded_capabilities: list[str] = Field(default_factory=list)
    deferred_capabilities: list[str] = Field(default_factory=list)
    resolved_bindings: list[dict[str, object]] = Field(default_factory=list)
    provider_bindings: list[dict[str, object]] = Field(default_factory=list)
    unavailable_capabilities: list[str] = Field(default_factory=list)


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


class CustomProductionActionRegistration(BaseModel):
    action_key: str
    display_name: str
    base_action_key: str
    default_style_profile_key: str = ""
    default_skill_graph_key: str = ""
    default_prompt_recipe_id: str = ""
    artifact_type: str = ""
    output_sections: list[str] = Field(default_factory=list)
    required_phrases: list[str] = Field(default_factory=list)
    resolver_keywords: list[str] = Field(default_factory=list)
    resolver_priority: int = 100
    preferred_routes: list[str] = Field(default_factory=list)
    preferred_style_profiles: list[str] = Field(default_factory=list)
    preferred_source_platforms: list[str] = Field(default_factory=list)
    preferred_structure_keywords: list[str] = Field(default_factory=list)
    preferred_task_neighborhoods: list[str] = Field(default_factory=list)


class CustomPromptRecipeRegistration(BaseModel):
    recipe_id: str
    recipe_name: str
    base_recipe_id: str
    supported_actions: list[str] = Field(default_factory=list)
    generation_mode: str = ""
    system_intent: str = ""
    section_guidance: dict[str, str] = Field(default_factory=dict)
    node_guidance: dict[str, str] = Field(default_factory=dict)
    citation_policy: list[str] = Field(default_factory=list)
    repair_hints: list[str] = Field(default_factory=list)
    recipe_notes: list[str] = Field(default_factory=list)


class RetrievalFeedback(BaseModel):
    retrieval_label: str
    index_status: str
    retrieval_weight_hint: str
    supporting_source_ids: list[str] = Field(default_factory=list)
    derived_passage_count: int = 0
    derived_passages: list[dict[str, object]] = Field(default_factory=list)
    promotion_candidate_count: int = 0
    notes: list[str] = Field(default_factory=list)


class MemoryPromotionPreview(BaseModel):
    eligible: bool
    candidate_memory_count: int
    novelty_gate_status: str
    supporting_source_ids: list[str] = Field(default_factory=list)
    candidate_memories: list[dict[str, object]] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class WritebackPreview(BaseModel):
    status: str
    requested_mode: str
    allowed_target: str = ""
    execution_mode: str = "NONE"
    required_capabilities: list[str] = Field(default_factory=list)
    version_id: str = ""
    request_id: str = ""
    target_locator_preview: str = ""
    notes: list[str] = Field(default_factory=list)


class WritebackRequest(BaseModel):
    request_id: str
    status: str
    requested_mode: str
    allowed_target: str
    execution_mode: str
    required_capabilities: list[str] = Field(default_factory=list)
    task_id: str
    workspace_id: str
    target_id: str
    action_key: str
    version_id: str
    artifact_title: str
    target_locator_preview: str
    payload_char_count: int = 0
    payload_digest: str = ""
    section_count: int = 0
    dispatch_count: int = 0
    delivery_id: str = ""
    callback_token: str = ""
    created_at: str
    dispatched_at: str = ""
    callback_deadline_at: str = ""
    acked_at: str = ""
    executed_at: str = ""
    last_error_code: str = ""
    last_error_message: str = ""
    delivery_attempts: list[dict[str, object]] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class WritebackExecutionReceipt(BaseModel):
    receipt_id: str
    request_id: str
    delivery_id: str = ""
    callback_token: str = ""
    status: str
    requested_mode: str
    allowed_target: str
    execution_mode: str
    version_id: str
    target_locator: str
    executed_capability: str = ""
    executed_at: str
    payload_digest: str = ""
    dispatch_count: int = 0
    error_code: str = ""
    error_message: str = ""
    notes: list[str] = Field(default_factory=list)


class ArtifactCommitReceipt(BaseModel):
    commit_status: str
    version_id: str
    retrieval_entry_id: str
    persisted_version_count: int
    persisted_retrieval_count: int


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
