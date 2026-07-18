import { describe, expect, it, vi } from "vitest";
import { type ApiClient } from "../../shared/api";
import { MemoryApi } from "./api";

describe("MemoryApi", () => {
  it("owns bounded review queue and version read paths", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => []);
    const api = new MemoryApi({ get } as unknown as ApiClient);

    await api.listReviews("workspace", "OBJECT", 999);
    await api.listVersions("workspace", "object");
    await api.getVersion("workspace", "object", "version");

    expect(get.mock.calls.map(([path]) => path)).toEqual([
      "/api/v2/workspaces/workspace/memory/reviews?kind=OBJECT&limit=200",
      "/api/v2/workspaces/workspace/memory/objects/object/versions",
      "/api/v2/workspaces/workspace/memory/objects/object/versions/version"
    ]);
  });

  it("owns review decisions, append and revoke protocols", async () => {
    const post = vi.fn(async () => ({}));
    const api = new MemoryApi({ post } as unknown as ApiClient);

    await api.decideReview("workspace", "CANDIDATE", "candidate", {
      decision: "REPLACE_EXISTING",
      reason: "conflict"
    });
    await api.appendVersion("workspace", "object", {
      canonical_statement: "statement",
      task_neighborhoods: ["CHAT_QA"],
      style_constraints: [],
      structure_constraints: [],
      terminology_policy: [],
      forbidden_patterns: [],
      interaction_policy: [],
      review_checklist: []
    });
    await api.revoke("workspace", "object");

    expect(post).toHaveBeenNthCalledWith(
      1,
      "/api/v2/workspaces/workspace/memory/reviews/CANDIDATE/candidate/decisions",
      { decision: "REPLACE_EXISTING", reason: "conflict" }
    );
    expect(post).toHaveBeenNthCalledWith(
      3,
      "/api/v2/workspaces/workspace/memory/objects/object/revoke",
      {}
    );
  });

  it("passes AbortSignal through reads and mutations", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => []);
    const post = vi.fn(async (_path: string, _body: unknown, _init?: RequestInit) => ({}));
    const api = new MemoryApi({ get, post } as unknown as ApiClient);
    const controller = new AbortController();

    await api.listReviews("workspace", "ALL", 50, { signal: controller.signal });
    await api.decideReview(
      "workspace",
      "OBJECT",
      "object",
      { decision: "APPROVE" },
      { signal: controller.signal }
    );

    expect(get.mock.calls[0][1]?.signal).toBe(controller.signal);
    expect(post.mock.calls[0][2]?.signal).toBe(controller.signal);
  });
});
