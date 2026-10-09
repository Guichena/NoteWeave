import { describe, expect, it } from "vitest";
import { classifyMemory, describeNeighborhoods, describeSource, memoryActions } from "./memoryItems";
import type { MemoryItem } from "./model";

function memory(overrides: Partial<MemoryItem>): MemoryItem {
  return {
    memory_item_id: "m", revision_id: "r", memory_scope: "WORKSPACE", item_status: "ACTIVE",
    review_status: "APPROVED", revision_status: "ACTIVE", version_no: 1, display_text: "偏好",
    candidate_type: "PREFERENCE", task_neighborhoods: ["COMMON"], provenance_type: "MEMORY_CANDIDATE",
    utility_score: 0.9, application_count: 0, conflict_status: "NO_CONFLICT", gate: null,
    last_confirmed_at: null, created_at: "2026-09-29T00:00:00Z",
    ...overrides
  };
}

describe("memoryItems", () => {
  it("separates proposals, memories needing a recheck and active memories", () => {
    expect(classifyMemory(memory({ revision_status: "PROPOSED", item_status: "EMPTY" }))).toBe("proposal");
    expect(classifyMemory(memory({ review_status: "REVIEW_REQUIRED" }))).toBe("recheck");
    expect(classifyMemory(memory({ item_status: "STALE" }))).toBe("recheck");
    expect(classifyMemory(memory({}))).toBe("active");
  });

  it("offers the review decisions the backend accepts for each state", () => {
    expect(memoryActions(memory({})).map((action) => action.decision)).toEqual(["REVOKE"]);
    expect(memoryActions(memory({ review_status: "REVIEW_REQUIRED" })).map((action) => action.decision))
      .toEqual(["ACCEPT", "REVOKE"]);
    expect(memoryActions(memory({ revision_status: "PROPOSED", conflict_status: "CONFLICTING_ACTIVE_MEMORY" }))
      .map((action) => action.decision)).toEqual(["REPLACE_EXISTING", "REJECT"]);
  });

  it("names neighborhoods and sources in plain words", () => {
    expect(describeNeighborhoods([])).toBe("全部回答");
    expect(describeNeighborhoods(["ARTIFACT_REPORT_DRAFT", "ARTIFACT", "CHAT_NOTE"])).toBe("产物、精读");
    expect(describeSource(memory({ provenance_type: "USER_FEEDBACK" }))).toBe("你的反馈");
    expect(describeSource(memory({
      gate: { result: "NEEDS_REVIEW", source_type: "MODEL_INFERENCE", evidence_score: 0.35, evidence_threshold: 0.7,
        utility_score: 0.4, utility_threshold: 0.6, risk_score: 0.65, risk_threshold: 0.7,
        scope_status: "VALID", conflict_status: "NO_CONFLICT", policy_version: "v1" }
    }))).toBe("模型推断");
    const fromChat = (source_type: string) => memory({
      gate: { result: "READY", source_type, evidence_score: 0.72, evidence_threshold: 0.7,
        utility_score: 0.75, utility_threshold: 0.6, risk_score: 0.35, risk_threshold: 0.7,
        scope_status: "VALID", conflict_status: "NO_CONFLICT", policy_version: "v1",
        source_ref: "conversation-message:m1:0" }
    });
    expect(describeSource(fromChat("CONVERSATION_FEEDBACK"))).toBe("对话中的明确要求");
    expect(describeSource(fromChat("MODEL_INFERENCE"))).toBe("对话推断");
  });
});
