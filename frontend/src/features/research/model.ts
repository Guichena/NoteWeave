import { type WaitContext } from "../executions/model";

export type ResearchRunResponse = {
  research_run_id: string;
  task_id: string;
  status: string;
};

export type ResearchRetrievalMode = "WEB_ONLY" | "WEB_PLUS_SEEDS" | "SOURCES_ONLY";

export type CreateResearchRunInput = {
  question: string;
  profile: string;
  research_goal: string;
  deliverable_format: string;
  constraints: string[];
  time_range: string;
  depth: string;
  research_type: string;
  retrieval_mode: ResearchRetrievalMode;
  seed_source_ids: string[];
  source_scope_source_ids: string[];
};

export type SaveResearchReportSource = {
  source_id: string;
  title: string;
  source_type: string;
  status: string;
  parse_status: string;
  index_status: string;
  generated_by: string;
  generated_ref_id: string;
};

export type ResearchCollection = {
  collection_id: string;
  workspace_id: string;
  research_run_id: string;
  title: string;
  status: string;
  report: { title: string; markdown: string };
  adopted_sources: Array<{
    source_kind: string;
    evidence_id: string;
    source_id: string;
    source_snapshot_key: string;
    source_url: string;
    source_domain: string;
    title: string;
    excerpt: string;
    citation_count: number;
  }>;
  notes: Array<{
    note_key: string;
    note_type: string;
    title: string;
    content: string;
    evidence_refs_json: string;
  }>;
  created_at: string;
  updated_at: string;
};

export type ResearchRunSummary = {
  research_run_id: string;
  task_id: string;
  question: string;
  profile_key: string;
  status: string;
  final_report_title: string;
  resumed_from_research_run_id: string;
  resumed_from_checkpoint_no: number | null;
  source_scope_count: number;
  checkpoint_count: number;
  active_branch_id: string;
  local_verifier_status: string;
  local_verifier_reason: string;
  ledger_row_count: number;
  verified_row_count: number;
  conflicted_row_count: number;
  blocked_row_count: number;
  guardrailed_row_count: number;
  recovery_targeted_blocked_row_count: number;
  uncovered_blocked_row_count: number;
  requirement_partial_blocked_row_count: number;
  global_verifier_decision: string;
  global_verifier_reason: string;
  final_loop_decision: string;
  final_loop_reason: string;
  recovery_mode: string;
  research_intent_alignment_status: string;
  research_intent_alignment_reason: string;
  intent_satisfied_constraint_count: number;
  intent_constraint_count: number;
  intent_satisfied_requirement_count: number;
  intent_requirement_count: number;
  intent_pending_requirement_count: number;
  missing_intent_requirements: string[];
  research_process_summary?: ResearchProcessSummary | null;
  wait_context?: WaitContext | null;
  recovery_targets?: Record<string, unknown> | null;
  counterfactual_summary: ResearchCounterfactualSummary | null;
  created_at: string;
  updated_at: string;
};

export type ResearchSourceScopeItem = {
  source_id: string;
  title: string;
  summary: string;
  sample_text: string;
  generated_by: string;
  generated_ref_id: string;
};

export type ResearchIntent = {
  research_goal: string;
  deliverable_format: string;
  constraints: string[];
  time_range: string;
  depth: string;
  research_type: string;
};

export type ResearchIntentAlignment = {
  status: string;
  reason_code: string;
  goal_status: string;
  deliverable_status: string;
  time_range_status: string;
  depth_status: string;
  satisfied_constraint_count: number;
  total_constraint_count: number;
  covered_requirements: string[];
  missing_requirements: string[];
};

export type ResearchIntentRequirement = {
  requirement_id: string;
  requirement_type: string;
  label: string;
  status: string;
  evidence_anchor: string;
  evidence_refs: string[];
  coverage_note: string;
  missing_reason: string;
};

export type ResearchIntentCompletionContract = {
  status: string;
  reason_code: string;
  total_requirement_count: number;
  satisfied_requirement_count: number;
  pending_requirement_count: number;
  missing_requirement_labels: string[];
  requirements: ResearchIntentRequirement[];
};

export type ResearchRecoveryTargets = {
  requirement_ids: string[];
  requirement_types: string[];
  requirement_labels: string[];
  target_columns: string[];
  target_queries: string[];
  target_sources: string[];
  requirement_count: number;
  query_count: number;
  source_count: number;
  column_count: number;
};

export type ResearchRowSummary = {
  row_id?: string;
  source_id?: string;
  source_title?: string;
  generated_by?: string;
  generated_ref_id?: string;
  search_query?: string;
  read_focus?: string;
  evidence_id?: string;
  claim_text?: string;
  quote_text?: string;
  evidence_excerpt?: string;
  relation_type?: string;
  support_score?: number;
  conflict_score?: number;
  support_level?: string;
  row_status?: string;
  branch_id?: string;
  source_quality?: string;
  verification_status?: string;
  verifier_note?: string;
  repair_hint?: string;
  matched_requirement_ids?: string[];
  ready_requirement_ids?: string[];
  required_column_count?: number;
  completed_column_count?: number;
  missing_columns?: string[];
  requirement_completion_status?: string;
};

export type ResearchFinalAnswer = {
  answer_text: string;
  answer_status: string;
  confidence_label: string;
  coverage_label: string;
  source_basis: string;
  ledger_row_count?: number;
};

export type ResearchAgentCapability = {
  capability_key: string;
  title: string;
  status: string;
  summary: string;
  detail: string;
};

export type ResearchReportStructure = {
  research_question: {
    original_question: string;
    research_profile: string;
  };
  research_intent: ResearchIntent;
  final_answer?: ResearchFinalAnswer | null;
  executive_summary?: string[];
  key_takeaways?: string[];
  evidence_highlights?: ResearchRowSummary[];
  uncertainty_and_risks?: string[];
  agent_capabilities?: ResearchAgentCapability[];
  intent_completion_contract?: ResearchIntentCompletionContract | null;
  research_intent_alignment: ResearchIntentAlignment | null;
  key_findings: string[];
  verified_findings: ResearchRowSummary[];
  evidence_ledger: ResearchRowSummary[];
  closed_loop_state: Record<string, unknown>;
  counterfactual_summary: ResearchCounterfactualSummary | null;
  conflict_and_counterfactual_review: {
    local_verifier_status: string;
    global_verifier_decision: string;
    evidence_policy: string;
    recovery_mode: string | null;
    verifier_decisions: Array<Record<string, unknown>>;
    conflicted_rows: ResearchRowSummary[];
    branch_decisions: Array<Record<string, unknown>>;
  };
  recovery_status: {
    local_verifier_status: string;
    global_verifier_decision: string;
    active_recovery_strategy: string | null;
    recovery_targets?: Record<string, unknown> | null;
    guardrailed_rows: ResearchRowSummary[];
    unresolved_questions: string[];
    warnings: string[];
  };
  next_actions: string[];
  resume_checkpoint: Record<string, unknown>;
  recovery_mode: string;
  control_notes: string[];
};

export type ResearchCounterfactualBranch = {
  branch_id: string;
  session_id?: string;
  parent_branch_id: string;
  parent_session_id?: string;
  branch_reason: string;
  branch_status: string;
  execution_mode?: string;
  sibling_branch_ids?: string[];
  decision: string;
  verifier_scope: string;
  hypothesis_summary: string;
  target_evidence_ids: string[];
};

export type ResearchCounterfactualSummary = {
  has_counterfactual_recheck: boolean;
  counterfactual_branch_count: number;
  conflicted_row_count: number;
  local_verifier_status: string;
  global_verifier_decision: string;
  recovery_mode: string;
  counterfactual_branch_ids: string[];
  counterfactual_session_ids?: string[];
  active_counterfactual_branch_ids: string[];
  active_counterfactual_session_ids?: string[];
  branch_reasons: string[];
  target_evidence_ids: string[];
  branches: ResearchCounterfactualBranch[];
};

export type ResearchCheckpointSummary = {
  checkpoint_no?: number;
  snapshot_type?: string;
  loop_decision?: Record<string, unknown>;
  local_verifier?: Record<string, unknown>;
  global_verifier?: Record<string, unknown>;
  state_ledger?: Record<string, unknown>;
  intent_completion_contract?: ResearchIntentCompletionContract | null;
  research_intent_alignment?: ResearchIntentAlignment | null;
  counterfactual_summary?: ResearchCounterfactualSummary | null;
  research_intent_alignment_status?: string;
  research_intent_alignment_reason?: string;
  intent_constraint_count?: number;
  intent_satisfied_constraint_count?: number;
  intent_requirement_count?: number;
  intent_satisfied_requirement_count?: number;
  intent_pending_requirement_count?: number;
  missing_intent_requirements?: string[];
  recovery_targets?: Record<string, unknown> | null;
};

export type ResearchCheckpointListItem = {
  checkpoint_no: number;
  snapshot_type: string;
  object_key: string;
  payload_sha256: string;
  content_size: number;
  active_branch_id: string;
  final_loop_decision: string;
  summary: ResearchCheckpointSummary;
  loop_decision?: Record<string, unknown>;
  local_verifier?: Record<string, unknown>;
  global_verifier?: Record<string, unknown>;
  state_ledger?: Record<string, unknown>;
  intent_completion_contract?: ResearchIntentCompletionContract | null;
  research_intent_alignment?: ResearchIntentAlignment | null;
  counterfactual_summary?: ResearchCounterfactualSummary | null;
  local_verifier_status?: string;
  global_verifier_decision?: string;
  research_intent_alignment_status?: string;
  research_intent_alignment_reason?: string;
  intent_constraint_count?: number;
  intent_satisfied_constraint_count?: number;
  intent_requirement_count?: number;
  intent_satisfied_requirement_count?: number;
  intent_pending_requirement_count?: number;
  missing_intent_requirements?: string[];
  recovery_targets?: Record<string, unknown> | null;
  verified_row_count?: number;
  conflicted_row_count?: number;
  created_at: string;
};

export type ResearchClosedLoopState = {
  active_branch_id: string;
  local_verifier_status: string;
  global_verifier_decision: string;
  final_loop_decision: string;
  loop_rounds_count: number;
  ledger_row_count: number;
  branch_count: number;
  verifier_decision_count: number;
  harness_summary: Record<string, unknown>;
  checkpoint_candidate: Record<string, unknown>;
  counterfactual_summary: ResearchCounterfactualSummary | null;
  recovery_targets?: Record<string, unknown> | null;
  state_ledger: Record<string, unknown>;
  local_verifier: Record<string, unknown>;
  global_verifier: Record<string, unknown>;
  branches: Array<Record<string, unknown>>;
  rows: ResearchRowSummary[];
  cells: Array<Record<string, unknown>>;
  verifier_decisions: Array<Record<string, unknown>>;
  checkpoints: ResearchCheckpointListItem[];
  source_evidence: Array<Record<string, unknown>>;
  cell_evidence: Array<Record<string, unknown>>;
  branch_decisions: Array<Record<string, unknown>>;
  loop_rounds: Array<Record<string, unknown>>;
  loop_decision_payload: Record<string, unknown>;
};

export type ResearchTrace = {
  trace_id: string;
  trace_type: string;
  trace_message: string;
  payload: Record<string, unknown>;
  created_at: string;
};

export type ResearchLoopRoundSummary = {
  round_no: number;
  search_hit_count: number;
  read_window_count: number;
  evidence_card_count: number;
  search_queries: string[];
  evidence_ids: string[];
  branch_decision: string;
  global_decision: string;
};

export type ResearchSearchReadTimeline = {
  loop_round_count: number;
  total_search_hit_count: number;
  total_read_window_count: number;
  total_evidence_card_count: number;
  all_search_queries: string[];
  final_loop_decision: string;
  final_loop_reason: string;
  terminal_disposition: string;
  handoff_required: boolean;
  abandon_reason: string;
  rounds: ResearchLoopRoundSummary[];
};

export type ResearchSourceEvidenceSummary = {
  source_basis: string;
  primary_quality: string;
  quality_mix_label: string;
  read_strategy_mix_label: string;
  fetch_foundation_label: string;
  orchestration_foundation_label: string;
  verified_finding_count: number;
  citation_count: number;
};

export type ResearchAuditSummary = {
  local_verifier_status: string;
  global_verifier_decision: string;
  final_loop_decision: string;
  has_counterfactual_recheck: boolean;
  counterfactual_branch_count: number;
  checkpoint_count: number;
  blocked_row_count: number;
  conflicted_row_count: number;
  guardrailed_row_count: number;
  recovery_target_count: number;
};

export type ResearchProcessSummary = {
  source_scope_count: number;
  search_read_timeline: ResearchSearchReadTimeline;
  source_evidence_summary: ResearchSourceEvidenceSummary;
  audit_summary: ResearchAuditSummary;
};

export type ResearchRunDetail = {
  research_run_id: string;
  workspace_id: string;
  task_id: string;
  question: string;
  profile_key: string;
  research_intent: ResearchIntent;
  resumed_from_research_run_id: string;
  resumed_from_checkpoint_no: number | null;
  status: string;
  completion_terminal_state: string;
  final_report_title: string;
  final_report_markdown: string;
  report_structure: ResearchReportStructure | null;
  counterfactual_summary: ResearchCounterfactualSummary | null;
  research_process_summary?: ResearchProcessSummary | null;
  trace_summary: string;
  source_scope: ResearchSourceScopeItem[];
  control_pack: Record<string, unknown>;
  saved_report_source?: SaveResearchReportSource | null;
  wait_context?: WaitContext | null;
  closed_loop_state: ResearchClosedLoopState;
  traces: ResearchTrace[];
  created_at: string;
  updated_at: string;
};

export type ResearchCheckpoint = {
  checkpoint_no: number;
  snapshot_type: string;
  object_key: string;
  payload_sha256: string;
  content_size: number;
  active_branch_id: string;
  final_loop_decision: string;
  summary: ResearchCheckpointSummary;
  local_verifier_status?: string;
  global_verifier_decision?: string;
  verified_row_count?: number;
  conflicted_row_count?: number;
  counterfactual_summary: ResearchCounterfactualSummary | null;
  recovery_targets?: Record<string, unknown> | null;
  payload: Record<string, unknown>;
  created_at: string;
};

export type ResearchCheckpointSnapshot = {
  rowCount: number;
  verifiedCount: number;
  conflictedCount: number;
  evidenceCount: number;
  localVerifierStatus: string;
  globalVerifierDecision: string;
  loopDecision: string;
  activeBranchId: string;
  counterfactualBranchCount: number;
  recoveryTargets: ResearchRecoveryTargets | null;
  summaryAvailable: boolean;
};

export type ResearchRunSummarySnapshot = {
  status: string;
  profileKey: string;
  activeBranchId: string;
  localVerifierStatus: string;
  localVerifierReason: string;
  globalVerifierDecision: string;
  globalVerifierReason: string;
  finalLoopDecision: string;
  finalLoopReason: string;
  recoveryMode: string;
  ledgerRowCount: number;
  verifiedRowCount: number;
  conflictedRowCount: number;
  blockedRowCount: number;
  guardrailedRowCount: number;
  targetedBlockedRowCount: number;
  uncoveredBlockedRowCount: number;
  requirementPartialBlockedRowCount: number;
  checkpointCount: number;
  sourceScopeCount: number;
  counterfactualBranchCount: number;
  researchIntentAlignmentStatus: string;
  researchIntentAlignmentReason: string;
  intentSatisfiedConstraintCount: number;
  intentConstraintCount: number;
  intentSatisfiedRequirementCount: number;
  intentRequirementCount: number;
  intentPendingRequirementCount: number;
  missingIntentRequirements: string[];
  recoveryTargets: ResearchRecoveryTargets | null;
};

export type ResearchHistoryFilter = "ALL" | "RECOVERY" | "CONFLICT" | "STABLE" | "RESUMED";

export type SignalTone = "conflict" | "recovery" | "stable" | "resume" | "neutral";

export type SignalChip = {
  label: string;
  value: string;
  tone: SignalTone;
};

export type ResearchTimelineMilestone = {
  key: string;
  label: string;
  description: string;
  tone: SignalTone;
  run: ResearchRunSummary;
  chips: SignalChip[];
};

export type ResearchTimelinePathSummary = {
  stageLabels: string[];
  currentStageLabel: string;
  hasRestabilized: boolean;
  narrative: string;
  currentRunStageLabel: string;
  currentRunAlignedWithPath: boolean;
};
