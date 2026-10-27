import { describe, expect, it } from "vitest";
import {
  createArtifactServerState,
  LatestArtifactRequestGate,
  reduceArtifactServerState
} from "./state";

describe("Artifact server state", () => {
  it("clears job, version and receipt projections on workspace replacement", () => {
    const state = reduceArtifactServerState(
      createArtifactServerState("workspace-a"),
      { type: "saved-source", key: "job:1", sourceId: "source" }
    );
    const changed = reduceArtifactServerState(state, { type: "workspace", workspaceId: "workspace-b" });

    expect(changed).toEqual(createArtifactServerState("workspace-b"));
  });

  it("keeps source and writeback receipts deduplicated", () => {
    const initial = createArtifactServerState("workspace");
    const saved = reduceArtifactServerState(initial, { type: "saved-source", key: "job:1", sourceId: "source" });
    const written = reduceArtifactServerState(saved, { type: "writeback", key: "job:1", itemType: "WIKI" });
    const deduplicated = reduceArtifactServerState(written, { type: "writeback", key: "job:1", itemType: "WIKI" });

    expect(deduplicated.artifactSavedSourceByVersionId).toEqual({ "job:1": "source" });
    expect(deduplicated.artifactWritebackByVersionId).toEqual({ "job:1": ["WIKI"] });
  });

  it("keeps the previous artifact snapshot visible while jobs refresh", () => {
    const initial = createArtifactServerState("workspace");
    const loaded = reduceArtifactServerState(initial, {
      type: "jobs-snapshot",
      jobs: [{ artifact_job_id: "job-1" }] as any,
      latestVersion: null
    });
    const refreshing = reduceArtifactServerState(loaded, { type: "jobs-loading" });

    expect(refreshing.jobsLoading).toBe(true);
    expect(refreshing.artifactJobs).toHaveLength(1);
  });

  it("aborts stale history requests and all requests on workspace replacement", () => {
    const gate = new LatestArtifactRequestGate();
    gate.setWorkspace("workspace-a");
    const first = gate.begin("history", "workspace-a");
    const second = gate.begin("history", "workspace-a");

    expect(first.signal.aborted).toBe(true);
    expect(second.isCurrent()).toBe(true);

    gate.setWorkspace("workspace-b");
    expect(second.signal.aborted).toBe(true);
  });

  // FE-03：失败要留住数据与已选版本，否则界面会把「加载失败」画成「没有版本」
  it("records the job list error without dropping the last snapshot", () => {
    const loaded = reduceArtifactServerState(createArtifactServerState("workspace"), {
      type: "jobs-snapshot",
      jobs: [{ artifact_job_id: "job-1" }] as any,
      latestVersion: null
    });
    const failed = reduceArtifactServerState(loaded, { type: "jobs-failed", error: "网络中断" });

    expect(failed.jobsError).toBe("网络中断");
    expect(failed.lastJobsFailed).toBe(true);
    expect(failed.artifactJobs).toHaveLength(1);

    const recovered = reduceArtifactServerState(failed, {
      type: "jobs-snapshot",
      jobs: [{ artifact_job_id: "job-1" }, { artifact_job_id: "job-2" }] as any,
      latestVersion: null
    });
    expect(recovered.jobsError).toBeNull();
    expect(recovered.lastJobsFailed).toBe(false);
  });

  it("keeps the selected version when a version load fails and clears the error on success", () => {
    const selected = reduceArtifactServerState(createArtifactServerState("workspace"), {
      type: "history-selected",
      key: "job-1:2",
      version: { artifact_job_id: "job-1", version_no: 2 } as any
    });
    const failed = reduceArtifactServerState(selected, {
      type: "history-failed",
      error: {
        key: "job-1:3",
        artifactJobId: "job-1",
        versionNo: 3,
        message: "版本状态已变化，请刷新后重试",
        status: 409,
        retryable: false
      }
    });

    expect(failed.versionError).toMatchObject({ versionNo: 3, status: 409, retryable: false });
    expect(failed.artifactHistoryLoadingKey).toBe("");
    // 已选版本仍在，界面显示错误态而不是空态
    expect(failed.selectedArtifactHistoryVersion).toMatchObject({ version_no: 2 });
    expect(failed.selectedArtifactHistoryKey).toBe("job-1:2");

    const retried = reduceArtifactServerState(failed, {
      type: "history-selected",
      key: "job-1:3",
      version: { artifact_job_id: "job-1", version_no: 3 } as any
    });
    expect(retried.versionError).toBeNull();
    expect(retried.selectedArtifactHistoryVersion).toMatchObject({ version_no: 3 });
  });
});
