import { describe, expect, it, vi } from "vitest";
import { type ApiClient } from "../../shared/api";
import { MemoryApi } from "./api";

describe("MemoryApi", () => {
  it("uses only the canonical review endpoints", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => []);
    const post = vi.fn(async (_path: string, _body: unknown, _init?: RequestInit) => ({}));
    const api = new MemoryApi({ get, post } as unknown as ApiClient);

    await api.listReviews("workspace");
    await api.decideReview("workspace", "revision", { decision: "ACCEPT" });

    expect(get).toHaveBeenCalledWith(
      "/api/v2/workspaces/workspace/memory/review"
    );
    expect(post).toHaveBeenCalledWith(
      "/api/v2/workspaces/workspace/memory/revisions/revision/review",
      { decision: "ACCEPT" }
    );
  });

  it("passes AbortSignal through reads and mutations", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => []);
    const post = vi.fn(async (_path: string, _body: unknown, _init?: RequestInit) => ({}));
    const api = new MemoryApi({ get, post } as unknown as ApiClient);
    const controller = new AbortController();

    await api.listReviews("workspace", { signal: controller.signal });
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
