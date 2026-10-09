export type MemoryReviewDecision = "ACCEPT" | "REJECT" | "REPLACE_EXISTING" | "REVOKE";

export type MemoryReviewDecisionInput = {
  decision: MemoryReviewDecision;
  reason?: string;
};

export type MemoryReviewDecisionResult = {
  revision_id: string;
  memory_item_id: string;
  status: string;
  revoked_memory_item_ids: string[];
};

/** 候选晋升时的门控打分与阈值，与后端 MemoryCandidateGate 的判定一致。 */
export type MemoryGate = {
  result: "READY" | "NEEDS_REVIEW" | string;
  source_type: string;
  evidence_score: number;
  evidence_threshold: number;
  utility_score: number;
  utility_threshold: number;
  risk_score: number;
  risk_threshold: number;
  scope_status: string;
  conflict_status: string;
  policy_version: string;
  /** 候选的来源引用，从对话中提取时为 conversation-message:{消息 ID}:{序号} */
  source_ref?: string | null;
};

/** 记忆列表中的一行：生效中的当前版本，或等待确认的新版本。 */
export type MemoryItem = {
  memory_item_id: string;
  revision_id: string;
  memory_scope: "USER" | "WORKSPACE" | string;
  item_status: string;
  review_status: string;
  revision_status: "ACTIVE" | "PROPOSED" | string;
  version_no: number;
  display_text: string;
  candidate_type: string;
  task_neighborhoods: string[];
  provenance_type: string;
  utility_score: number;
  application_count: number;
  conflict_status: string;
  gate: MemoryGate | null;
  last_confirmed_at: string | null;
  created_at: string;
};

/** 添加偏好时提交的信号；来源固定为用户反馈。 */
export type CreateMemorySignalInput = {
  signal_type: "PREFERENCE" | "NEGATIVE";
  source_type: "USER_FEEDBACK";
  signal_text: string;
  task_neighborhood: string;
  style_constraints?: string[];
  forbidden_patterns?: string[];
};

export type MemorySignal = {
  signal_id: string;
};

export type MemoryPromotionResult = {
  candidates: Array<{ candidate_id: string; review_status: string; conflict_status: string }>;
};
