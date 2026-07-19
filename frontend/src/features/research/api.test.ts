import { describe, expect, it, vi } from "vitest";
import { type ApiClient } from "../../shared/api";
import { ResearchApi } from "./api";

describe("ResearchApi", () => {
  it("owns run and checkpoint read paths with cancellation", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => ({}));
    const api = new ResearchApi({ get } as unknown as ApiClient);
    const controller = new AbortController();
    const init = { signal: controller.signal };

    await api.listRuns("workspace", init);
    await api.getRun("workspace", "run", init);
    await api.getCollection("workspace", "run", init);
    await api.getCheckpoint("workspace", "run", 7, init);

    expect(get.mock.calls).toEqual([
      ["/api/v2/workspaces/workspace/research-runs", init],
      ["/api/v2/workspaces/workspace/research-runs/run", init],
      ["/api/v2/workspaces/workspace/research-runs/run/collection", init],
      ["/api/v2/workspaces/workspace/research-runs/run/checkpoints/7", init]
    ]);
  });

  it("owns create, resume and report writeback paths", async () => {
    const post = vi.fn(async (_path: string, _body?: unknown) => ({}));
    const api = new ResearchApi({ post } as unknown as ApiClient);
    const input = {
      question: "问题",
      profile: "default",
      research_goal: "目标",
      deliverable_format: "report",
      constraints: ["可验证"],
      time_range: "2025-2026",
      depth: "STANDARD",
      research_type: "AUTO",
      retrieval_mode: "WEB_PLUS_SEEDS" as const,
      seed_source_ids: ["source"],
      source_scope_source_ids: []
    };

    await api.createRun("workspace", input);
    await api.resumeFromCheckpoint("workspace", "run", 2);
    await api.saveReportAsSource("workspace", "run");

    expect(post.mock.calls).toEqual([
      ["/api/v2/workspaces/workspace/research-runs", input],
      ["/api/v2/workspaces/workspace/research-runs/run/resume-from-checkpoint/2", {}],
      ["/api/v2/workspaces/workspace/research-runs/run/save-report-as-source", {}]
    ]);
  });
});
