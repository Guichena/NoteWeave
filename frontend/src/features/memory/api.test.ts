import { describe, expect, it, vi } from "vitest";
import { type ApiClient } from "../../shared/api";
import { MemoryApi } from "./api";

describe("MemoryApi", () => {
  it("reads memory items and submits user feedback through signal promotion", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => []);
    const post = vi.fn(async (_path: string, _body: unknown, _init?: RequestInit) => ({}));
    const api = new MemoryApi({ get, post } as unknown as ApiClient);

    await api.listItems("workspace");
    await api.createSignal("workspace", {
      signal_type: "PREFERENCE",
      source_type: "USER_FEEDBACK",
      signal_text: "先给结论",
      task_neighborhood: "COMMON",
      style_constraints: ["先给结论"]
    });
    await api.promoteSignals("workspace", ["signal-1"]);
    await api.decideReview("workspace", "revision", { decision: "ACCEPT" });

    expect(get).toHaveBeenCalledWith("/api/v2/workspaces/workspace/memory/items");
    expect(post.mock.calls.map((call) => call[0])).toEqual([
      "/api/v2/workspaces/workspace/memory/signals",
      "/api/v2/workspaces/workspace/memory/promotions",
      "/api/v2/workspaces/workspace/memory/revisions/revision/review"
    ]);
    expect(post.mock.calls[1][1]).toEqual({ signal_ids: ["signal-1"] });
  });

  it("passes AbortSignal through reads and mutations", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => []);
    const post = vi.fn(async (_path: string, _body: unknown, _init?: RequestInit) => ({}));
    const api = new MemoryApi({ get, post } as unknown as ApiClient);
    const controller = new AbortController();

    await api.listItems("workspace", { signal: controller.signal });
    await api.decideReview(
      "workspace",
      "revision",
      { decision: "REJECT" },
      { signal: controller.signal }
    );

    expect(get.mock.calls[0][1]?.signal).toBe(controller.signal);
    expect(post.mock.calls[0][2]?.signal).toBe(controller.signal);
  });
});
