export type MemoryReviewKind = "PROPOSAL" | "ACTIVE";
export type MemoryReviewDecision = "ACCEPT" | "REJECT" | "REPLACE_EXISTING" | "REVOKE";

export type MemoryReviewItem = {
  revision_id: string;
  memory_item_id: string;
  review_kind: MemoryReviewKind;
  status: string;
  display_text: string;
  provenance_ref: string | null;
  conflict_status: string;
  utility_score: number;
  review_status: string;
  lifecycle_status: string;
};

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
