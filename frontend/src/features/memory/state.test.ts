import { describe, expect, it } from "vitest";
import { decisionOptions } from "./MemoryReviewWorkbench";
import {
  createMemoryReviewState,
  LatestMemoryRequestGate,
  reduceMemoryReviewState
} from "./state";
import { type MemoryReviewItem } from "./model";

describe("Memory review state", () => {
  it("resets queue and selection on workspace change", () => {
    const loaded = {
      ...createMemoryReviewState("first"),
      queue: [reviewItem()],
      selectedRevisionId: "revision"
    };

    expect(reduceMemoryReviewState(loaded, { type: "workspace", workspaceId: "second" }))
      .toEqual(createMemoryReviewState("second"));
  });

  it("clears selection when a decided revision leaves the queue", () => {
    const selected = {
      ...createMemoryReviewState("workspace"),
      queue: [reviewItem()],
      selectedRevisionId: "revision"
    };

    const next = reduceMemoryReviewState(selected, { type: "queue", queue: [] });

    expect(next.selectedRevisionId).toBe("");
  });
});

describe("Memory request and decision policy", () => {
  it("aborts stale channel requests and all requests on workspace change", () => {
    const gate = new LatestMemoryRequestGate();
    gate.setWorkspace("first");
    const first = gate.begin("queue");
    const second = gate.begin("queue");

    expect(first.signal.aborted).toBe(true);
    expect(second.isCurrent()).toBe(true);

    gate.setWorkspace("second");
    expect(second.signal.aborted).toBe(true);
    expect(second.isCurrent()).toBe(false);
  });

  it("requires explicit replacement or rejection for conflicting proposals", () => {
    const decisions = decisionOptions({
      ...reviewItem(),
      conflict_status: "CONFLICTING_ACTIVE_MEMORY"
    }).map((option) => option.decision);

    expect(decisions).toEqual(["REPLACE_EXISTING", "REJECT"]);
    expect(decisions).not.toContain("ACCEPT");
  });

  it("limits active review to accept or revoke", () => {
    expect(decisionOptions({ ...reviewItem(), review_kind: "ACTIVE" })
      .map((option) => option.decision))
      .toEqual(["ACCEPT", "REVOKE"]);
  });
});

function reviewItem(): MemoryReviewItem {
  return {
    revision_id: "revision",
    memory_item_id: "item",
    review_kind: "PROPOSAL",
    status: "PROPOSED",
    display_text: "statement",
    provenance_ref: "candidate",
    conflict_status: "NO_CONFLICT",
    utility_score: 0.5,
    review_status: "REVIEW_REQUIRED",
    lifecycle_status: "EMPTY"
  };
}
