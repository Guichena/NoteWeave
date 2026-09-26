import { describe, expect, it, vi } from "vitest";
import { type ApiClient } from "../../shared/api";
import { ArtifactsApi } from "./api";

describe("ArtifactsApi", () => {
  it("owns skill, job, version, compare and export paths", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => ([]));
    const blob = vi.fn(async (_path: string) => new Blob(["pdf"]));
    const api = new ArtifactsApi({ get, blob } as unknown as ApiClient);

    await api.listSkills();
    await api.listJobs("workspace");
    await api.getVersion("workspace", "job", 3);
    await api.compareVersions("workspace", "job", 2, 3);
    await api.exportPdf("workspace", "job", 3);
    await api.downloadFile("workspace", "job", 3, "file-1");

    expect(get.mock.calls.map(([path]) => path)).toEqual([
      "/api/v2/skills",
      "/api/v2/workspaces/workspace/artifact-jobs",
      "/api/v2/workspaces/workspace/artifact-jobs/job/versions/3",
      "/api/v2/workspaces/workspace/artifact-jobs/job/versions/compare?from=2&to=3"
    ]);
    expect(blob).toHaveBeenCalledWith(
      "/api/v2/workspaces/workspace/artifact-jobs/job/versions/3/export.pdf"
    );
    expect(blob).toHaveBeenCalledWith(
      "/api/v2/workspaces/workspace/artifact-jobs/job/versions/3/files/file-1"
    );
  });

  it("owns create and version mutation paths", async () => {
    const post = vi.fn(async (_path: string, _body?: unknown) => ({}));
    const api = new ArtifactsApi({ post } as unknown as ApiClient);

    await api.createJob("workspace", {
      skill_key: "study_guide",
      user_requirement: "生成学习指南",
      inputs: { language: "zh-CN" }
    });
    await api.saveVersionAsSource("workspace", "job", 4);
    await api.writebackVersion("workspace", "job", 4, { item_type: "WIKI", title: "指南" });
    await api.regenerateVersion("workspace", "job", 4);
    await api.rollbackVersion("workspace", "job", 4);

    expect(post.mock.calls).toEqual([
      ["/api/v2/workspaces/workspace/artifact-jobs", {
        skill_key: "study_guide",
        user_requirement: "生成学习指南",
        inputs: { language: "zh-CN" }
      }],
      ["/api/v2/workspaces/workspace/artifact-jobs/job/versions/4/save-as-source", {}],
      ["/api/v2/workspaces/workspace/artifact-jobs/job/versions/4/writeback", {
        item_type: "WIKI",
        title: "指南"
      }],
      ["/api/v2/workspaces/workspace/artifact-jobs/job/versions/4/regenerate", {}],
      ["/api/v2/workspaces/workspace/artifact-jobs/job/versions/4/rollback", {}]
    ]);
  });

  it("passes cancellation through read protocols", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => ([]));
    const api = new ArtifactsApi({ get } as unknown as ApiClient);
    const controller = new AbortController();

    await api.listSkills({ signal: controller.signal });
    await api.listJobs("workspace", { signal: controller.signal });
    await api.getVersion("workspace", "job", 1, { signal: controller.signal });

    expect(get.mock.calls.every(([, init]) => init?.signal === controller.signal)).toBe(true);
  });

  it("uses the parent video route for list, create and cancellation", async () => {
    const get = vi.fn(async (_path: string) => ({ enabled: false, requests: [] }));
    const post = vi.fn(async (_path: string, _body?: unknown) => ({}));
    const api = new ArtifactsApi({ get, post } as unknown as ApiClient);
    await api.listVideoLearning("workspace");
    await api.createVideoLearning("workspace", {
      client_request_id: "request-1", video_url: "https://www.bilibili.com/video/BV1234567890",
      part: 1, language: "zh-CN", frame_density: "STANDARD", asr_fallback: "ALLOW",
      template_version: "original-v1", user_requirement: "Study",
      selected_skills: ["knowledge_blog"]
    });
    await api.cancelVideoLearning("workspace", "parent-1");
    expect(get).toHaveBeenCalledWith("/api/v2/workspaces/workspace/video-learning-bundles", undefined);
    expect(post.mock.calls.map(([path]) => path)).toEqual([
      "/api/v2/workspaces/workspace/video-learning-bundles",
      "/api/v2/workspaces/workspace/video-learning-bundles/parent-1/cancel"
    ]);
  });
});
