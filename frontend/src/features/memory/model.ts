export type MemoryReviewKind = "ALL" | "CANDIDATE" | "OBJECT";
export type MemoryItemKind = Exclude<MemoryReviewKind, "ALL">;
export type MemoryCandidateDecision = "APPROVE" | "REJECT" | "REPLACE_EXISTING";
export type MemoryObjectDecision = "APPROVE" | "REVOKE";
export type MemoryReviewDecision = MemoryCandidateDecision | MemoryObjectDecision;

export type MemoryReviewItem = {
  review_kind: MemoryItemKind;
  review_id: string;
  workspace_id: string;
  statement: string;
  task_neighborhoods: string[];
  review_status: string;
  lifecycle_status: string;
  conflict_status: string;
  evidence_gate_status: string;
  risk_score: number;
  utility_score: number;
  policy_version: string;
  latest_version_id: string | null;
  priority: number;
  created_at: string;
  updated_at: string;
};

export type MemoryObject = {
  memory_object_id: string;
  workspace_id: string;
  memory_type: string;
  memory_scope: string;
  canonical_statement: string;
  task_neighborhoods: string[];
  status: string;
};

export type MemoryReviewDecisionInput = {
  decision: MemoryReviewDecision;
  reason?: string;
};

export type MemoryReviewDecisionResult = {
  review_decision_id: string;
  review_kind: MemoryItemKind;
  review_id: string;
  decision: MemoryReviewDecision;
  review_status: string;
  lifecycle_status: string;
  promoted_memory_object: MemoryObject | null;
  revoked_memory_object_ids: string[];
};

export type MemoryCompileHints = {
  style_constraints: string[];
  structure_constraints: string[];
  terminology_policy: string[];
  forbidden_patterns: string[];
  interaction_policy: string[];
  review_checklist: string[];
};

export type MemoryVersion = {
  memory_version_id: string;
  memory_object_id: string;
  workspace_id: string;
  version_no: number;
  canonical_statement: string;
  task_neighborhoods: string[];
  compile_hints: MemoryCompileHints;
  forbidden_patterns: string[];
  status: string;
  supersedes_version_id: string | null;
  valid_from: string;
  valid_to: string | null;
  created_from_candidate_id: string | null;
  policy_version: string;
  risk_score: number;
  scope_status: string;
};

export type AppendMemoryVersionInput = {
  canonical_statement: string;
  task_neighborhoods: string[];
  style_constraints: string[];
  structure_constraints: string[];
  terminology_policy: string[];
  forbidden_patterns: string[];
  interaction_policy: string[];
  review_checklist: string[];
};
