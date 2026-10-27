// @vitest-environment jsdom

import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../shared/api/error";
import { useArtifactWorkspace } from "./useArtifactWorkspace";

function createArtifactApi() {
  return {
    listJobs: vi.fn(async () => []),
    getVersion: vi.fn(),
    saveVersionAsSource: vi.fn(async () => ({ source_id: "source-1" })),
    writebackVersion: vi.fn(async () => undefined),
    regenerateVersion: vi.fn(async () => undefined),
    rollbackVersion: vi.fn(),
    compareVersions: vi.fn(async () => ({ summary: "2 lines added" })),
    exportPdf: vi.fn()
  };
}

describe("useArtifactWorkspace", () => {
  it("polls active artifact jobs until the authoritative terminal snapshot arrives", async () => {
    vi.useFakeTimers();
    const api = createArtifactApi();
    api.listJobs
      .mockResolvedValueOnce([artifactJob("QUEUED")] as never)
      .mockResolvedValueOnce([artifactJob("FAILED")] as never);

    const { unmount } = renderHook(() => useArtifactWorkspace({
      workspaceId: "workspace-1",
      latestTask: null,
      run: vi.fn(async (_label: string, action: () => Promise<void>) => action()),
      setStatus: vi.fn(),
      replaceSources: vi.fn(),
      artifactPollIntervalMs: 100,
      api: api as never,
      sourceApi: { list: vi.fn() } as never
    }));

    try {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(api.listJobs).toHaveBeenCalledTimes(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(100);
      });
      expect(api.listJobs).toHaveBeenCalledTimes(2);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(500);
      });
      expect(api.listJobs).toHaveBeenCalledTimes(2);
    } finally {
      unmount();
      vi.useRealTimers();
    }
  });

  it("owns save-as-source and refreshes the workspace source list", async () => {
    const api = createArtifactApi();
    const sourceApi = {
      list: vi.fn(async () => [{ source_id: "source-1", title: "Saved artifact" }])
    };
    const run = vi.fn(async (_label: string, action: () => Promise<void>) => action());
    const setStatus = vi.fn();
    const replaceSources = vi.fn();

    const { result } = renderHook(() => useArtifactWorkspace({
      workspaceId: "workspace-1",
      latestTask: null,
      run,
      setStatus,
      replaceSources,
      api: api as never,
      sourceApi: sourceApi as never
    }));

    await waitFor(() => expect(api.listJobs).toHaveBeenCalledWith("workspace-1", expect.anything()));
    await act(async () => {
      await result.current.saveArtifactVersionAsSource({
        artifact_job_id: "job-1",
        version_no: 1
      });
    });

    expect(run).toHaveBeenCalledWith("保存产物为资料", expect.any(Function), "artifact");
    expect(api.saveVersionAsSource).toHaveBeenCalledWith("workspace-1", "job-1", 1);
    expect(sourceApi.list).toHaveBeenCalledWith("workspace-1");
    expect(replaceSources).toHaveBeenCalledWith([
      { source_id: "source-1", title: "Saved artifact" }
    ]);
    expect(result.current.artifactSavedSourceByVersionId).toEqual({
      "job-1:1": "source-1"
    });
  });

  it("rejects unavailable operations before invoking remote APIs", async () => {
    const api = createArtifactApi();
    const run = vi.fn(async (_label: string, action: () => Promise<void>) => action());
    const setStatus = vi.fn();

    const { result } = renderHook(() => useArtifactWorkspace({
      workspaceId: "",
      latestTask: null,
      run,
      setStatus,
      replaceSources: vi.fn(),
      api: api as never,
      sourceApi: { list: vi.fn() } as never
    }));

    await act(async () => {
      await result.current.saveArtifactVersionAsSource({ artifact_job_id: "job-1", version_no: 1 });
      await result.current.downloadArtifactVersionPdf({
        artifact_job_id: "job-1",
        version_no: 1,
        files: []
      });
    });

    expect(setStatus).toHaveBeenCalledWith("请先创建工作台");
    expect(run).not.toHaveBeenCalled();
    expect(api.saveVersionAsSource).not.toHaveBeenCalled();
    expect(api.exportPdf).not.toHaveBeenCalled();
  });

  it("does not compare the first version", async () => {
    const api = createArtifactApi();
    const run = vi.fn(async (_label: string, action: () => Promise<void>) => action());
    const setStatus = vi.fn();
    const { result } = renderHook(() => useArtifactWorkspace({
      workspaceId: "workspace-1",
      latestTask: null,
      run,
      setStatus,
      replaceSources: vi.fn(),
      api: api as never,
      sourceApi: { list: vi.fn() } as never
    }));

    await act(async () => {
      await result.current.compareArtifactWithPreviousVersion({
        artifact_job_id: "job-1",
        version_no: 1
      });
    });

    expect(setStatus).toHaveBeenCalledWith("当前版本没有可比较的上一版本");
    expect(api.compareVersions).not.toHaveBeenCalled();
    expect(run).not.toHaveBeenCalled();
  });

  // FE-03：版本加载失败要按状态码分成可重试与不可重试，并在重试成功后清掉错误
  it("reports a missing version as a non-retryable load error", async () => {
    const api = createArtifactApi();
    api.getVersion.mockRejectedValue(new ApiError("not found", 404, "NOT_FOUND", "req", "corr"));
    const { result } = renderHook(() => useArtifactWorkspace(workspaceProps(api)));

    await act(async () => {
      await result.current.openArtifactHistoryVersion(historyItem(3) as never);
    });

    expect(result.current.versionError).toMatchObject({ versionNo: 3, status: 404, retryable: false });
    expect(result.current.versionError?.message).toContain("已不存在");
    expect(result.current.artifactHistoryLoadingKey).toBe("");
  });

  it("retries a transient version failure and clears the error on success", async () => {
    const api = createArtifactApi();
    api.getVersion
      .mockRejectedValueOnce(new TypeError("network down"))
      .mockResolvedValueOnce({ artifact_job_id: "job-1", version_no: 2 } as never);
    const { result } = renderHook(() => useArtifactWorkspace(workspaceProps(api)));

    await act(async () => {
      await result.current.openArtifactHistoryVersion(historyItem(2) as never);
    });
    expect(result.current.versionError).toMatchObject({ status: 0, retryable: true });

    await act(async () => {
      await result.current.retryVersion();
    });

    expect(result.current.versionError).toBeNull();
    expect(result.current.selectedArtifactHistoryVersion).toMatchObject({ version_no: 2 });
    expect(api.getVersion).toHaveBeenCalledTimes(2);
  });
});

function workspaceProps(api: ReturnType<typeof createArtifactApi>) {
  return {
    workspaceId: "workspace-1",
    latestTask: null,
    run: vi.fn(async (_label: string, action: () => Promise<void>) => action()),
    setStatus: vi.fn(),
    replaceSources: vi.fn(),
    api: api as never,
    sourceApi: { list: vi.fn() } as never
  };
}

function historyItem(versionNo: number) {
  return {
    key: `job-1:${versionNo}`,
    artifactJobId: "job-1",
    versionNo,
    skillKey: "resume_highlight",
    title: "产物",
    detail: "",
    updatedAt: "2026-07-29T00:00:00Z",
    status: ""
  };
}

function artifactJob(status: string) {
  return {
    artifact_job_id: "job-1",
    workspace_id: "workspace-1",
    task_id: "task-1",
    skill_key: "resume_highlight",
    status,
    task_status: status,
    progress_phase: status,
    progress_message: status,
    result_title: "",
    latest_version_no: 0,
    created_at: "2026-07-29T00:00:00Z",
    updated_at: "2026-07-29T00:00:00Z"
  };
}
