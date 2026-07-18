import { describe, expect, it } from "vitest";
import { decisionOptions } from "./MemoryReviewWorkbench";
import {
  createMemoryReviewState,
  LatestMemoryRequestGate,
  reduceMemoryReviewState,
  selectVersionId
} from "./state";
import { type MemoryReviewItem, type MemoryVersion } from "./model";

describe("Memory review state", () => {
  it("resets queue, selection and versions on workspace change", () => {
    const loaded = {
      ...createMemoryReviewState("first"),
      queue: [reviewItem()],
      selectedReviewId: "review",
      versions: [memoryVersion(1)],
      selectedVersionId: "version-1"
    };

    expect(reduceMemoryReviewState(loaded, { type: "workspace", workspaceId: "second" }))
      .toEqual(createMemoryReviewState("second"));
  });

  it("clears selection when a decided item leaves the queue", () => {
    const selected = {
      ...createMemoryReviewState("workspace"),
      queue: [reviewItem()],
      selectedReviewId: "review",
      versions: [memoryVersion(1)],
      selectedVersionId: "version-1"
    };

    const next = reduceMemoryReviewState(selected, { type: "queue", queue: [] });

    expect(next.selectedReviewId).toBe("");
    expect(next.versions).toEqual([]);
    expect(next.selectedVersionId).toBe("");
  });

  it("selects a preferred immutable version or the highest version number", () => {
    const versions = [memoryVersion(1), memoryVersion(3), memoryVersion(2)];

    expect(selectVersionId(versions)).toBe("version-3");
    expect(selectVersionId(versions, "version-2")).toBe("version-2");
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

  it("cancels an object version request when selection moves to a candidate", () => {
    const gate = new LatestMemoryRequestGate();
    gate.setWorkspace("workspace");
    const versions = gate.begin("versions");

    gate.cancel("versions");

    expect(versions.signal.aborted).toBe(true);
    expect(versions.isCurrent()).toBe(false);
  });

  it("requires explicit replacement or rejection for conflicting candidates", () => {
    const decisions = decisionOptions({
      ...reviewItem(),
      conflict_status: "CONFLICTING_ACTIVE_MEMORY"
    }).map((option) => option.decision);

    expect(decisions).toEqual(["REPLACE_EXISTING", "REJECT"]);
    expect(decisions).not.toContain("APPROVE");
  });

  it("limits object decisions to approve or revoke", () => {
    expect(decisionOptions({ ...reviewItem(), review_kind: "OBJECT" })
      .map((option) => option.decision))
      .toEqual(["APPROVE", "REVOKE"]);
  });
});

function reviewItem(): MemoryReviewItem {
  return {
    review_kind: "CANDIDATE",
    review_id: "review",
    workspace_id: "workspace",
    statement: "statement",
    task_neighborhoods: ["CHAT_QA"],
    review_status: "NEEDS_REVIEW",
    lifecycle_status: "ACTIVE",
    conflict_status: "NONE",
    evidence_gate_status: "NEEDS_REVIEW",
    risk_score: 0.7,
    utility_score: 0.5,
    policy_version: "memory-candidate-policy-v1",
    latest_version_id: null,
    priority: 70,
    created_at: "2026-07-15T00:00:00Z",
    updated_at: "2026-07-15T00:00:00Z"
  };
}

function memoryVersion(versionNo: number): MemoryVersion {
  return {
    memory_version_id: `version-${versionNo}`,
    memory_object_id: "object",
    workspace_id: "workspace",
    version_no: versionNo,
    canonical_statement: `statement ${versionNo}`,
    task_neighborhoods: ["CHAT_QA"],
    compile_hints: {
      style_constraints: [],
      structure_constraints: [],
      terminology_policy: [],
      forbidden_patterns: [],
      interaction_policy: [],
      review_checklist: []
    },
    forbidden_patterns: [],
    status: "ACTIVE",
    supersedes_version_id: versionNo > 1 ? `version-${versionNo - 1}` : null,
    valid_from: "2026-07-15T00:00:00Z",
    valid_to: null,
    created_from_candidate_id: null,
    policy_version: "memory-lifecycle-policy-v1",
    risk_score: 0.2,
    scope_status: "VALID"
  };
}
