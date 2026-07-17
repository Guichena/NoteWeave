from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field


class SourceScopeItem(BaseModel):
    source_id: str
    title: str
    summary: str = ""
    sample_text: str = ""
    source_type: str = "WORKSPACE_SOURCE"
    author: str = ""
    institution: str = ""
    published_at: str = ""
    updated_at: str = ""


class CellSupportStatus(str, Enum):
    """Four-way cell-level evidence verdict (借鉴 DeepWideSearch llm_judge 模板)。

    - SUPPORTS: 证据直接支持 cell value
    - PARTIALLY_SUPPORTS: 证据部分支持,需要保留 uncertainty 标注
    - CONTRADICTS: 证据与 cell value 冲突或不同来源给出相反结论
    - NOT_ENOUGH_INFO: 证据不足,无法判断
    """

    SUPPORTS = "SUPPORTS"
    PARTIALLY_SUPPORTS = "PARTIALLY_SUPPORTS"
    CONTRADICTS = "CONTRADICTS"
    NOT_ENOUGH_INFO = "NOT_ENOUGH_INFO"


class ResearchColumnDtype(str, Enum):
    TEXT = "text"
    ENUM = "enum"
    NUMBER = "number"
    BOOLEAN = "boolean"
    LIST = "list"


class ResearchColumn(BaseModel):
    """Table-as-Search 的"列" = 实体的一个研究维度(借鉴 Table-as-Search schema)。"""

    key: str
    label: str
    dtype: ResearchColumnDtype = ResearchColumnDtype.TEXT
    required: bool = True
    acceptance: list[str] = Field(default_factory=list)  # for enum
    description: str = ""


class ResearchSchema(BaseModel):
    """不可变的 Research Table schema(借鉴 Table-as-Search __schema__ 文档)。

    main agent / planner 在 PLANNING 阶段一次性创建,运行期不再修改。
    每个 row 必须严格按 schema 的 columns 提交 cell value,否则视为 schema mismatch。
    """

    columns: list[ResearchColumn] = Field(default_factory=list)
    research_type: str = "GENERIC"  # PAPER_SURVEY / GITHUB_REPO_ANALYSIS / PRODUCT_COMPARISON / TECH_SOLUTION_COMPARISON / CONCEPT_RESEARCH
    entity_type: str = ""
    required_column_count: int = 0


class ResearchEntityRow(BaseModel):
    """Table-as-Search 的"行" = 一个候选研究实体(借鉴 Table-as-Search add_records)。

    entity_id 由 URL/title 归一化(EntityResolver)生成,确保跨轮稳定。
    """

    entity_id: str
    entity_type: str = ""
    display_name: str
    source_id: str = ""
    source_title: str = ""
    source_url: str = ""
    source_quality: str = "GENERAL_WEB"
    source_ids: list[str] = Field(default_factory=list)
    source_urls: list[str] = Field(default_factory=list)
    first_seen_round: int = 1
    matched_requirement_ids: list[str] = Field(default_factory=list)


class CellVerdict(BaseModel):
    """Cell-level evidence verdict result.

    由 CellVerifier 在 LLM 4-way 判定 / 规则降级后输出。
    """

    cell_id: str
    status: CellSupportStatus = CellSupportStatus.NOT_ENOUGH_INFO
    confidence: float = 0.0
    reason: str = ""
    suggested_revision: str = ""
    evidence_ids: list[str] = Field(default_factory=list)
    used_llm: bool = False


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


class ResumeCheckpointPayload(BaseModel):
    source_research_run_id: str
    checkpoint_no: int
    snapshot_type: str = ""
    active_branch_id: str = ""
    final_loop_decision: str = ""
    payload: dict[str, object] = Field(default_factory=dict)


class ResearchIntent(BaseModel):
    research_goal: str = ""
    deliverable_format: str = ""
    constraints: list[str] = Field(default_factory=list)
    time_range: str = ""
    depth: str = "STANDARD"
    research_type: str = "AUTO"


class ResearchTaskInputPayload(BaseModel):
    question: str
    profile_key: str
    research_intent: ResearchIntent = Field(default_factory=ResearchIntent)
    resume_checkpoint: ResumeCheckpointPayload | None = None


class ResearchTaskInput(BaseModel):
    task_id: str
    workspace_id: str
    target_id: str
    source_scope: list[SourceScopeItem] = Field(default_factory=list)
    control_pack: ControlPack
    input_payload: ResearchTaskInputPayload


class ResearchPlan(BaseModel):
    normalized_question: str
    query_set: list[str] = Field(default_factory=list)
    report_sections: list[str] = Field(default_factory=list)
    # P0-1: 实体化的 schema,替换旧的 state_columns 11 个元数据列
    research_schema: ResearchSchema = Field(default_factory=ResearchSchema, alias="schema")
    # 兼容旧字段:state_columns 现在直接由 schema.columns[*].key 派生
    state_columns: list[str] = Field(default_factory=list)
    stop_contract: dict[str, object] = Field(default_factory=dict)
    notes: list[str] = Field(default_factory=list)
    research_type: str = "GENERIC"
    target_entity_type: str = ""
    plan_horizon: list[dict[str, object]] = Field(default_factory=list)
    plan_revision: int = 0
    replan_history: list[dict[str, object]] = Field(default_factory=list)

    model_config = {"populate_by_name": True}


class ResearchStepTrace(BaseModel):
    trace_id: str
    event_id: str = ""
    run_id: str = ""
    round_no: int = 0
    branch_id: str = "branch-main"
    tool: str = ""
    attempt: int = 1
    started_at: str = ""
    ended_at: str = ""
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


class ResearchHarnessLoopState(BaseModel):
    loop_round_count: int = 0
    final_decision: str = ""
    final_reason: str = ""
    should_continue: bool = False
    recovery_mode: str = "DEFAULT"
    branch_count: int = 0
    active_branch_id: str = "branch-main"
    recovery_action_count: int = 0


class ResearchHarnessVerifierLatestDecision(BaseModel):
    decision_scope: str = "VERIFY"
    decision_type: str = ""
    status: str = "INFO"
    target_id: str = ""


class ResearchHarnessVerifierGateState(BaseModel):
    local_status: str = ""
    global_status: str = ""
    global_decision: str = ""
    completion_score: float = 0.0
    warning_count: int = 0
    unresolved_question_count: int = 0
    recovery_action_count: int = 0
    latest_reason_code: str = ""
    latest_decision: ResearchHarnessVerifierLatestDecision = Field(default_factory=ResearchHarnessVerifierLatestDecision)


class ResearchHarnessVerifierGatePolicyState(BaseModel):
    policy_version: str = "v1"
    loop_gate_action: str = ""
    final_loop_decision: str = ""
    report_gate_action: str = ""
    should_continue_loop: bool = False
    allows_final_write: bool = False
    allows_guarded_write: bool = False
    requires_source_expansion: bool = False
    requires_read_more: bool = False
    requires_extract_again: bool = False
    requires_counterfactual_recheck: bool = False
    blocking_reason_codes: list[str] = Field(default_factory=list)
    pending_requirement_count: int = 0
    unresolved_question_count: int = 0
    warning_count: int = 0


class ResearchHarnessBranchRecoveryState(BaseModel):
    active_branch_id: str = "branch-main"
    branch_count: int = 0
    has_recovery_branch: bool = False
    latest_branch_id: str = ""
    latest_branch_decision: str = ""
    latest_branch_reason: str = ""
    latest_verifier_scope: str = ""
    target_evidence_ids: list[str] = Field(default_factory=list)
    recovery_action_count: int = 0


class ResearchHarnessResumeState(BaseModel):
    resumed_from_checkpoint: bool = False
    source_research_run_id: str = ""
    checkpoint_no: int = 0
    restored_search_hit_count: int = 0
    restored_fetch_document_count: int = 0
    restored_read_window_count: int = 0
    restored_evidence_card_count: int = 0
    restored_loop_round_count: int = 0


class ResearchHarnessEvidenceCoverageState(BaseModel):
    search_hit_count: int = 0
    fetch_document_count: int = 0
    read_window_count: int = 0
    evidence_card_count: int = 0
    verified_row_count: int = 0
    conflicted_row_count: int = 0
    requirement_ready_row_count: int = 0
    coverage_score: float = 0.0


class ResearchHarnessCheckpointSummaryState(BaseModel):
    checkpoint_no: int = 0
    snapshot_type: str = ""
    loop_round_count: int = 0
    final_loop_decision: str = ""
    report_gate_action: str = ""
    active_branch_id: str = "branch-main"
    recovery_mode: str = "DEFAULT"
    resumed_from_checkpoint: bool = False
    allows_final_write: bool = False
    allows_guarded_write: bool = False


class ResearchHarnessCounterfactualSummaryState(BaseModel):
    has_counterfactual_recheck: bool = False
    counterfactual_branch_count: int = 0
    counterfactual_branch_ids: list[str] = Field(default_factory=list)
    counterfactual_session_ids: list[str] = Field(default_factory=list)
    active_counterfactual_branch_ids: list[str] = Field(default_factory=list)
    active_counterfactual_session_ids: list[str] = Field(default_factory=list)
    target_evidence_ids: list[str] = Field(default_factory=list)
    conflicted_row_count: int = 0
    recovery_mode: str = "DEFAULT"
    latest_branch_decision: str = ""
    latest_branch_reason: str = ""


class ResearchHarnessResumeSummaryState(BaseModel):
    resumed_from_checkpoint: bool = False
    source_research_run_id: str = ""
    checkpoint_no: int = 0
    source_final_loop_decision: str = ""
    source_active_branch_id: str = ""
    restored_search_hit_count: int = 0
    restored_fetch_document_count: int = 0
    restored_read_window_count: int = 0
    restored_evidence_card_count: int = 0
    restored_loop_round_count: int = 0


class ResearchHarnessEvidenceCoverageSummaryState(BaseModel):
    search_hit_count: int = 0
    fetch_document_count: int = 0
    read_window_count: int = 0
    evidence_card_count: int = 0
    verified_row_count: int = 0
    conflicted_row_count: int = 0
    requirement_ready_row_count: int = 0
    coverage_score: float = 0.0
    pending_requirement_count: int = 0
    unresolved_question_count: int = 0


class ResearchHarnessAuditSummaryState(BaseModel):
    checkpoint_summary: ResearchHarnessCheckpointSummaryState = Field(default_factory=ResearchHarnessCheckpointSummaryState)
    counterfactual_summary: ResearchHarnessCounterfactualSummaryState = Field(default_factory=ResearchHarnessCounterfactualSummaryState)
    resume_summary: ResearchHarnessResumeSummaryState = Field(default_factory=ResearchHarnessResumeSummaryState)
    evidence_coverage_summary: ResearchHarnessEvidenceCoverageSummaryState = Field(default_factory=ResearchHarnessEvidenceCoverageSummaryState)


class ResearchHarnessControlState(BaseModel):
    control_loop: ResearchHarnessLoopState = Field(default_factory=ResearchHarnessLoopState)
    verifier_gate: ResearchHarnessVerifierGateState = Field(default_factory=ResearchHarnessVerifierGateState)
    verifier_gate_policy: ResearchHarnessVerifierGatePolicyState = Field(default_factory=ResearchHarnessVerifierGatePolicyState)
    branch_recovery: ResearchHarnessBranchRecoveryState = Field(default_factory=ResearchHarnessBranchRecoveryState)
    resume_checkpoint: ResearchHarnessResumeState = Field(default_factory=ResearchHarnessResumeState)
    evidence_coverage: ResearchHarnessEvidenceCoverageState = Field(default_factory=ResearchHarnessEvidenceCoverageState)


class ResearchLoopDecision(BaseModel):
    decision: str
    reason: str
    round_no: int
    should_continue: bool = False
    recovery_actions: list[str] = Field(default_factory=list)
    terminal_disposition: str = "CONTINUE"
    handoff_required: bool = False
    abandon_reason: str = ""


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
    provider_attempts: list[str] = Field(default_factory=list)
    provider_attempt_count: int = 1
    provider_resolution: str = "workspace"
    provider_fallback_reason: str = ""
    source_domain: str = ""
    source_quality: str = "GENERAL_WEB"
    source_quality_score: float = 0.5
    search_lane: str = "BALANCED"


class ResearchFetchedDocument(BaseModel):
    fetch_id: str
    hit_id: str
    source_id: str
    source_title: str
    query: str
    rank: int = 0
    url: str = ""
    provider: str = ""
    adapter: str = "workspace"
    search_angle: str = "direct"
    snapshot_text: str
    snapshot_status: str = "WORKSPACE"
    snapshot_key: str = ""
    fetch_status: str = "WORKSPACE_READY"
    fetch_method: str = "WORKSPACE"
    content_origin: str = "WORKSPACE_TEXT"
    content_type_label: str = "WORKSPACE_TEXT"
    fetch_error_reason: str = ""
    fetch_failure_code: str = ""
    fetch_attempts: list[str] = Field(default_factory=list)
    transport_chain: list[str] = Field(default_factory=list)
    transport_resolution: str = "WORKSPACE"
    transport_fallback_reason: str = ""
    transport_fallback_code: str = ""
    transport_attempt_count: int = 0
    snapshot_archive_ready: bool = False
    source_domain: str = ""
    source_quality: str = "GENERAL_WEB"
    source_quality_score: float = 0.5
    untrusted_content: bool = False
    prompt_injection_detected: bool = False
    prompt_injection_signals: list[str] = Field(default_factory=list)
    source_type: str = "UNKNOWN"
    author: str = ""
    institution: str = ""
    published_at: str = ""
    updated_at: str = ""
    freshness_status: str = "UNKNOWN"


class ResearchReadWindow(BaseModel):
    window_id: str
    hit_id: str
    source_id: str
    source_title: str
    query: str
    query_family: str = "direct"
    read_focus: str
    window_text: str
    retention_reason: str
    token_estimate: int
    target_requirement_ids: list[str] = Field(default_factory=list)
    target_requirement_labels: list[str] = Field(default_factory=list)
    target_columns: list[str] = Field(default_factory=list)
    url: str = ""
    provider: str = ""
    adapter: str = "workspace"
    snapshot_status: str = "WORKSPACE"
    snapshot_key: str = ""
    fetch_status: str = "WORKSPACE_READY"
    fetch_method: str = "WORKSPACE"
    content_origin: str = "WORKSPACE_TEXT"
    content_type_label: str = "WORKSPACE_TEXT"
    fetch_error_reason: str = ""
    fetch_failure_code: str = ""
    fetch_attempts: list[str] = Field(default_factory=list)
    transport_chain: list[str] = Field(default_factory=list)
    transport_resolution: str = "WORKSPACE"
    transport_fallback_reason: str = ""
    transport_fallback_code: str = ""
    transport_attempt_count: int = 0
    snapshot_archive_ready: bool = False
    source_domain: str = ""
    source_quality: str = "GENERAL_WEB"
    source_quality_score: float = 0.5
    read_strategy: str = "BALANCED_READ"
    untrusted_content: bool = False
    prompt_injection_detected: bool = False
    prompt_injection_signals: list[str] = Field(default_factory=list)
    source_type: str = "UNKNOWN"
    author: str = ""
    institution: str = ""
    published_at: str = ""
    updated_at: str = ""
    freshness_status: str = "UNKNOWN"


class ResearchToolboxSearchSummary(BaseModel):
    query_count: int = 0
    hit_count: int = 0
    selected_query_count: int = 0
    query_family_counts: dict[str, int] = Field(default_factory=dict)
    query_family_budget_counts: dict[str, int] = Field(default_factory=dict)
    query_lane_counts: dict[str, int] = Field(default_factory=dict)
    search_angle_counts: dict[str, int] = Field(default_factory=dict)
    provider_resolution_counts: dict[str, int] = Field(default_factory=dict)
    provider_fallback_reason_counts: dict[str, int] = Field(default_factory=dict)
    source_quality_counts: dict[str, int] = Field(default_factory=dict)
    effective_query_count: int = 0
    zero_hit_query_count: int = 0
    selected_queries: list[dict[str, object]] = Field(default_factory=list)
    query_effectiveness: list[dict[str, object]] = Field(default_factory=list)


class ResearchToolboxFetchSummary(BaseModel):
    fetch_document_count: int = 0
    fetched_document_count: int = 0
    fallback_document_count: int = 0
    snapshot_archive_ready_count: int = 0
    multi_transport_document_count: int = 0
    fetch_status_counts: dict[str, int] = Field(default_factory=dict)
    fetch_method_counts: dict[str, int] = Field(default_factory=dict)
    content_origin_counts: dict[str, int] = Field(default_factory=dict)
    content_type_counts: dict[str, int] = Field(default_factory=dict)
    transport_resolution_counts: dict[str, int] = Field(default_factory=dict)
    fetch_failure_code_counts: dict[str, int] = Field(default_factory=dict)
    transport_fallback_code_counts: dict[str, int] = Field(default_factory=dict)


class ResearchToolboxReadSummary(BaseModel):
    read_window_count: int = 0
    fallback_window_count: int = 0
    snapshot_archive_ready_count: int = 0
    multi_transport_window_count: int = 0
    query_family_counts: dict[str, int] = Field(default_factory=dict)
    read_strategy_counts: dict[str, int] = Field(default_factory=dict)
    fetch_status_counts: dict[str, int] = Field(default_factory=dict)
    content_origin_counts: dict[str, int] = Field(default_factory=dict)
    content_type_counts: dict[str, int] = Field(default_factory=dict)
    fetch_failure_code_counts: dict[str, int] = Field(default_factory=dict)
    target_requirement_window_count: int = 0


class ResearchToolboxSearchTopHit(BaseModel):
    hit_id: str = ""
    source_id: str = ""
    source_title: str = ""
    rank: int = 0
    provider_resolution: str = ""
    search_angle: str = ""
    coverage_score: float = 0.0
    url: str = ""


class ResearchToolboxSearchItem(BaseModel):
    query: str = ""
    family: str = ""
    lane: str = ""
    priority: int = 0
    family_budget: int = 0
    selection_reason: str = ""
    hit_count: int = 0
    top_hits: list[ResearchToolboxSearchTopHit] = Field(default_factory=list)


class ResearchToolboxFetchItem(BaseModel):
    fetch_id: str = ""
    hit_id: str = ""
    source_id: str = ""
    source_title: str = ""
    query: str = ""
    fetch_status: str = ""
    fetch_method: str = ""
    content_origin: str = ""
    content_type_label: str = ""
    fetch_failure_code: str = ""
    transport_resolution: str = ""
    transport_fallback_code: str = ""
    transport_chain: list[str] = Field(default_factory=list)
    transport_attempt_count: int = 0
    snapshot_status: str = ""
    snapshot_key: str = ""
    snapshot_archive_ready: bool = False
    url: str = ""


class ResearchToolboxReadItem(BaseModel):
    window_id: str = ""
    hit_id: str = ""
    source_id: str = ""
    source_title: str = ""
    query: str = ""
    query_family: str = ""
    read_focus: str = ""
    read_strategy: str = ""
    fetch_status: str = ""
    content_origin: str = ""
    content_type_label: str = ""
    fetch_failure_code: str = ""
    transport_resolution: str = ""
    transport_attempt_count: int = 0
    target_requirement_ids: list[str] = Field(default_factory=list)
    target_requirement_labels: list[str] = Field(default_factory=list)
    target_columns: list[str] = Field(default_factory=list)
    snapshot_status: str = ""
    snapshot_key: str = ""
    snapshot_archive_ready: bool = False
    url: str = ""


class ResearchToolboxSummary(BaseModel):
    search_summary: ResearchToolboxSearchSummary = Field(default_factory=ResearchToolboxSearchSummary)
    fetch_summary: ResearchToolboxFetchSummary = Field(default_factory=ResearchToolboxFetchSummary)
    read_summary: ResearchToolboxReadSummary = Field(default_factory=ResearchToolboxReadSummary)
    search_items: list[ResearchToolboxSearchItem] = Field(default_factory=list)
    fetch_items: list[ResearchToolboxFetchItem] = Field(default_factory=list)
    read_items: list[ResearchToolboxReadItem] = Field(default_factory=list)


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
    entity_id: str = ""
    column_key: str = ""
    entity_name: str = ""
    source_url: str = ""
    source_quality: str = "GENERAL_WEB"
    quote_start: int = -1
    quote_end: int = -1
    snapshot_key: str = ""
    content_sha256: str = ""
    source_type: str = "UNKNOWN"
    author: str = ""
    institution: str = ""
    published_at: str = ""
    updated_at: str = ""
    freshness_status: str = "UNKNOWN"
    discovery_round: int = 0
    branch_id: str = "branch-main"
    grounding_verified: bool = False


class ResearchBranchSession(BaseModel):
    session_id: str
    branch_id: str
    parent_session_id: str = "session-main"
    parent_branch_id: str = "branch-main"
    session_role: str = "COUNTERFACTUAL_RECHECK"
    execution_mode: str = "PARALLEL_CANDIDATE"
    target_evidence_ids: list[str] = Field(default_factory=list)
    sibling_branch_ids: list[str] = Field(default_factory=list)
    status: str = "ACTIVE"
    created_round: int = 1


class ResearchBranchDecision(BaseModel):
    branch_id: str
    session_id: str = "session-main"
    decision: str
    branch_reason: str
    verifier_scope: str
    parent_branch_id: str = "branch-main"
    parent_session_id: str = "session-main"
    hypothesis_summary: str = ""
    target_evidence_ids: list[str] = Field(default_factory=list)
    sibling_branch_ids: list[str] = Field(default_factory=list)
    execution_mode: str = "SEQUENTIAL"
    branch_status: str = "ACTIVE_BRANCH"
    created_round: int = 1
    resolution: str = ""
    resolved_round: int = 0
    result_evidence_ids: list[str] = Field(default_factory=list)
    recovery_actions: list[str] = Field(default_factory=list)


class ResearchStateRow(BaseModel):
    # P0-1: row 现在是候选研究实体,不再是 evidence_card
    row_id: str
    # 实体元数据(可选,旧 row=evidence_card 时这些字段可为空)
    entity_id: str = ""           # 兼容旧 row_id 作为 fallback
    entity_type: str = ""
    display_name: str = ""
    # 兼容字段(用于旧 evidence_card 模型下 row = 一张卡片)
    source_id: str = ""
    source_title: str = ""
    source_url: str = ""
    search_query: str = ""
    read_focus: str = ""
    evidence_id: str = ""
    claim_text: str = ""
    quote_text: str = ""
    evidence_excerpt: str = ""
    relation_type: str = "SUPPORTS"
    support_score: float = 0.0
    conflict_score: float = 0.0
    # P0-1: row_status 现在表示该实体的整体收敛状态
    row_status: str = "CANDIDATE_READY"
    branch_id: str = "branch-main"
    source_quality: str = "WORKSPACE_SOURCE"
    source_domain: str = ""
    read_strategy: str = "BALANCED_READ"
    verification_status: str = "PENDING"
    support_level: str = "PENDING"
    verifier_note: str = ""
    repair_hint: str = ""
    matched_requirement_ids: list[str] = Field(default_factory=list)
    ready_requirement_ids: list[str] = Field(default_factory=list)
    required_column_count: int = 0
    completed_column_count: int = 0
    missing_columns: list[str] = Field(default_factory=list)
    requirement_completion_status: str = "NOT_REQUIRED"
    # P0-1: 实体级冲突证据 id 集合(用于分支决策定位)
    conflicting_evidence_ids: list[str] = Field(default_factory=list)


class ResearchStateCell(BaseModel):
    # P0-1: cell 现在严格按 (entity_id, column_key) 唯一定位
    cell_id: str
    row_id: str                       # 兼容旧字段;新代码应使用 entity_id
    entity_id: str = ""               # P0-1 新增
    column_key: str                   # 取自 ResearchSchema.columns[*].key
    candidate_value: str
    status: str = "CANDIDATE_READY"
    confidence: float = 0.0
    evidence_refs: list[str] = Field(default_factory=list)
    branch_id: str = "branch-main"
    last_verifier_decision: str = "PENDING"
    repair_count: int = 0
    # P0-1: 4-way cell-level verdict(借鉴 DeepWideSearch llm_judge)
    verdict: CellSupportStatus = CellSupportStatus.NOT_ENOUGH_INFO
    verdict_reason: str = ""
    verdict_confidence: float = 0.0
    verdict_round: int = 0
    verdict_used_llm: bool = False
    is_required: bool = False
    required_by_requirement_ids: list[str] = Field(default_factory=list)
    satisfied_requirement_ids: list[str] = Field(default_factory=list)
    requirement_completion_status: str = "NOT_REQUIRED"
    # MA0: optimistic-concurrency binding for future agent candidate merges.
    # Defaults preserve the current sequential checkpoint/result contract.
    version: int = Field(default=0, ge=0)
    plan_revision: int = Field(default=0, ge=0)
    entity_set_version: int = Field(default=0, ge=0)
    last_merge_id: str = ""


class ResearchStateBranch(BaseModel):
    branch_id: str
    session_id: str = "session-main"
    parent_branch_id: str = ""
    parent_session_id: str = ""
    branch_reason: str
    hypothesis_summary: str
    target_evidence_ids: list[str] = Field(default_factory=list)
    sibling_branch_ids: list[str] = Field(default_factory=list)
    execution_mode: str = "SEQUENTIAL"
    status: str
    created_round: int = 1
    resolution: str = ""
    resolved_round: int = 0
    result_evidence_ids: list[str] = Field(default_factory=list)


class ResearchVerifierDecision(BaseModel):
    decision_scope: str
    decision_type: str
    reason_code: str
    target_id: str = ""
    evidence_ids: list[str] = Field(default_factory=list)
    action: str = ""
    status: str = "INFO"
    notes: list[str] = Field(default_factory=list)


class ResearchIntentRequirement(BaseModel):
    requirement_id: str
    requirement_type: str
    label: str
    status: str = "PASS"
    evidence_anchor: str = ""
    evidence_refs: list[str] = Field(default_factory=list)
    required_columns: list[str] = Field(default_factory=list)
    accepted_row_statuses: list[str] = Field(default_factory=list)
    target_row_count: int = 0
    ready_row_ids: list[str] = Field(default_factory=list)
    coverage_note: str = ""
    missing_reason: str = ""


class ResearchIntentCompletionContract(BaseModel):
    status: str = "PASS"
    reason_code: str = "INTENT_REQUIREMENTS_COMPLETE"
    total_requirement_count: int = 0
    satisfied_requirement_count: int = 0
    pending_requirement_count: int = 0
    missing_requirement_labels: list[str] = Field(default_factory=list)
    requirements: list[ResearchIntentRequirement] = Field(default_factory=list)


class ResearchRequiredFindingProgress(BaseModel):
    requirement_id: str
    requirement_type: str
    label: str
    completion_mode: str = "ROW_EVIDENCE"
    target_row_count: int = 1
    accepted_row_statuses: list[str] = Field(default_factory=list)
    required_columns: list[str] = Field(default_factory=list)
    matched_row_ids: list[str] = Field(default_factory=list)
    ready_row_ids: list[str] = Field(default_factory=list)
    partial_row_ids: list[str] = Field(default_factory=list)
    status: str = "MISSING"


class ResearchStateLedger(BaseModel):
    # P0-1: schema 字段(借鉴 Table-as-Search __schema__ 文档)
    research_schema: ResearchSchema = Field(default_factory=ResearchSchema, alias="schema")
    entities: list[ResearchEntityRow] = Field(default_factory=list)
    entity_set_status: str = "DISCOVERING"
    entity_set_version: int = 0
    frozen_entity_ids: list[str] = Field(default_factory=list)
    rejected_candidate_entity_ids: list[str] = Field(default_factory=list)
    columns: list[str] = Field(default_factory=list)
    rows: list[ResearchStateRow] = Field(default_factory=list)
    cells: list[ResearchStateCell] = Field(default_factory=list)
    branch_sessions: list[ResearchBranchSession] = Field(default_factory=list)
    branches: list[ResearchStateBranch] = Field(default_factory=list)
    verifier_decisions: list[ResearchVerifierDecision] = Field(default_factory=list)
    cell_verdicts: list[CellVerdict] = Field(default_factory=list)  # P0-5 新增
    unresolved_questions: list[str] = Field(default_factory=list)
    row_status_summary: dict[str, int] = Field(default_factory=dict)
    search_hit_count: int = 0
    read_window_count: int = 0
    evidence_card_count: int = 0
    verified_row_count: int = 0
    conflicted_row_count: int = 0
    # P0-5 新增:cell 级别的 verdict 分布统计
    cell_verdict_counts: dict[str, int] = Field(default_factory=dict)
    coverage_score: float = 0.0
    active_branch_id: str = "branch-main"
    active_branch_ids: list[str] = Field(default_factory=list)
    required_finding_contract: list[dict[str, object]] = Field(default_factory=list)
    required_finding_progress: list[ResearchRequiredFindingProgress] = Field(default_factory=list)
    requirement_ready_row_count: int = 0
    requirement_partial_row_count: int = 0
    intent_completion_contract: ResearchIntentCompletionContract = Field(default_factory=ResearchIntentCompletionContract)

    model_config = {"populate_by_name": True}


class ResearchIntentAlignment(BaseModel):
    status: str = "PASS"
    reason_code: str = "INTENT_ALIGNED"
    goal_status: str = "NOT_REQUESTED"
    deliverable_status: str = "NOT_REQUESTED"
    time_range_status: str = "NOT_REQUESTED"
    depth_status: str = "PASS"
    satisfied_constraint_count: int = 0
    total_constraint_count: int = 0
    covered_requirements: list[str] = Field(default_factory=list)
    missing_requirements: list[str] = Field(default_factory=list)


class LocalVerifierResult(BaseModel):
    status: str
    passed_checks: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    recovery_actions: list[str] = Field(default_factory=list)
    decision_records: list[ResearchVerifierDecision] = Field(default_factory=list)
    intent_completion_contract: ResearchIntentCompletionContract = Field(default_factory=ResearchIntentCompletionContract)
    research_intent_alignment: ResearchIntentAlignment = Field(default_factory=ResearchIntentAlignment)


class GlobalVerifierResult(BaseModel):
    status: str
    decision: str
    summary: str
    counterfactual_checks: list[str] = Field(default_factory=list)
    recovery_actions: list[str] = Field(default_factory=list)
    completion_score: float = 0.0
    decision_records: list[ResearchVerifierDecision] = Field(default_factory=list)
    intent_completion_contract: ResearchIntentCompletionContract = Field(default_factory=ResearchIntentCompletionContract)
    research_intent_alignment: ResearchIntentAlignment = Field(default_factory=ResearchIntentAlignment)


class ResearchProgressEvent(BaseModel):
    phase: str
    progress_percent: int
    message: str
    metrics: dict[str, object] = Field(default_factory=dict)
    payload: dict[str, object] = Field(default_factory=dict)


class ResearchTaskResult(BaseModel):
    result_type: str = "RESEARCH_REPORT"
    result_title: str
    result_payload: dict[str, object]
    trace_summary: str
    citations: list[dict[str, object]] = Field(default_factory=list)
