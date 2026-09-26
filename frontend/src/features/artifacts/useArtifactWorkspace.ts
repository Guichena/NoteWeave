import { useCallback, useEffect } from "react";
import { type ArtifactHistoryItem } from "./artifactHistory";
import { shouldReuseLatestArtifactVersion } from "./artifactHistorySelection";
import { type ArtifactRuntimeTrace } from "./artifactRuntimeTrace";
import type { ShellRun } from "../shell/useShellBusy";
import { sourcesApi, type SourcesApi } from "../sources/api";
import { type SourceAsset } from "../sources/model";
import { artifactsApi, type ArtifactsApi } from "./api";
import { type ArtifactFileMetadata, type ArtifactJobSummary } from "./model";
import { useArtifactState } from "./useArtifactState";
import { artifactVersionSaveKey } from "./versionKey";

const ARTIFACT_JOB_POLL_INTERVAL_MS = 2_500;
const ARTIFACT_JOB_TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "CANCELLED"]);

type ArtifactTaskSnapshot = {
  task_id?: string;
  task_type?: string;
  task_status?: string;
  progress_phase?: string;
} | null;

type UseArtifactWorkspaceInput = {
  workspaceId: string;
  latestTask: ArtifactTaskSnapshot;
  run: ShellRun;
  setStatus: (status: string) => void;
  replaceSources: (sources: SourceAsset[]) => void;
  artifactPollIntervalMs?: number;
  api?: ArtifactsApi;
  sourceApi?: SourcesApi;
};

type ArtifactVersionRef = {
  artifact_job_id: string;
  version_no: number;
};

type ArtifactWritebackVersion = ArtifactVersionRef & {
  title: string;
};

type ArtifactPdfVersion = ArtifactVersionRef & {
  runtime_trace?: ArtifactRuntimeTrace | null;
  files?: Array<{ file_format: string; status: string }>;
};

export function useArtifactWorkspace({
  workspaceId,
  latestTask,
  run,
  setStatus,
  replaceSources,
  artifactPollIntervalMs = ARTIFACT_JOB_POLL_INTERVAL_MS,
  api = artifactsApi,
  sourceApi = sourcesApi
}: UseArtifactWorkspaceInput) {
  const state = useArtifactState(workspaceId, api);

  const loadJobs = useCallback(async () => {
    try {
      return await state.refreshJobs();
    } catch {
      return [];
    }
  }, [state.refreshJobs]);

  useEffect(() => {
    if (!workspaceId) {
      state.clear();
      return;
    }
    void loadJobs();
  }, [workspaceId, state.clear, loadJobs]);

  useEffect(() => {
    if (!workspaceId || latestTask?.task_type !== "ARTIFACT_JOB") {
      return;
    }
    void loadJobs();
  }, [
    workspaceId,
    latestTask?.task_id,
    latestTask?.task_status,
    latestTask?.progress_phase,
    latestTask?.task_type,
    loadJobs
  ]);

  useEffect(() => {
    if (
      !workspaceId
      || artifactPollIntervalMs <= 0
      || !state.artifactJobs.some(isArtifactJobActive)
    ) {
      return;
    }
    const timer = window.setTimeout(() => {
      void loadJobs();
    }, artifactPollIntervalMs);
    return () => window.clearTimeout(timer);
  }, [workspaceId, artifactPollIntervalMs, state.artifactJobs, loadJobs]);

  const openArtifactHistoryVersion = useCallback(async (item: ArtifactHistoryItem) => {
    if (!workspaceId) {
      return;
    }
    try {
      await state.selectHistoryVersion({
        key: item.key,
        artifactJobId: item.artifactJobId,
        versionNo: item.versionNo
      }, shouldReuseLatestArtifactVersion({
        artifactJobId: item.artifactJobId,
        versionNo: item.versionNo
      }, state.latestArtifactVersion));
    } catch {
      setStatus("历史产物版本详情加载失败");
    }
  }, [workspaceId, state.selectHistoryVersion, state.latestArtifactVersion, setStatus]);

  const saveArtifactVersionAsSource = useCallback(async (version: ArtifactVersionRef) => {
    if (!workspaceId) {
      setStatus("请先创建工作台");
      return;
    }
    await run("保存产物为资料", async () => {
      const saved = await api.saveVersionAsSource(
        workspaceId,
        version.artifact_job_id,
        version.version_no
      );
      state.recordSavedSource(artifactVersionSaveKey(version), saved.source_id);
      replaceSources(await sourceApi.list(workspaceId));
    }, "artifact");
  }, [workspaceId, run, api, sourceApi, state.recordSavedSource, replaceSources, setStatus]);

  const writeArtifactVersionToKnowledge = useCallback(async (
    version: ArtifactWritebackVersion,
    itemType: "NOTE" | "WIKI"
  ) => {
    if (!workspaceId) {
      setStatus("请先创建工作台");
      return;
    }
    const label = itemType === "NOTE" ? "Note" : "Wiki";
    await run(`写回 ${label}`, async () => {
      await api.writebackVersion(
        workspaceId,
        version.artifact_job_id,
        version.version_no,
        { item_type: itemType, title: version.title }
      );
      state.recordWriteback(artifactVersionSaveKey(version), itemType);
    }, "artifact");
  }, [workspaceId, run, api, state.recordWriteback, setStatus]);

  const regenerateArtifactVersion = useCallback(async (version: ArtifactVersionRef) => {
    if (!workspaceId) {
      setStatus("请先创建工作台");
      return;
    }
    await run("再生成产物版本", async () => {
      await api.regenerateVersion(workspaceId, version.artifact_job_id, version.version_no);
      await loadJobs();
    }, "artifact");
  }, [workspaceId, run, api, loadJobs, setStatus]);

  const rollbackArtifactVersion = useCallback(async (version: ArtifactVersionRef) => {
    if (!workspaceId) {
      setStatus("请先创建工作台");
      return;
    }
    await run("追加式回滚产物", async () => {
      const rolledBack = await api.rollbackVersion(
        workspaceId,
        version.artifact_job_id,
        version.version_no
      );
      state.applyRollback(rolledBack);
      await loadJobs();
    }, "artifact");
  }, [workspaceId, run, api, state.applyRollback, loadJobs, setStatus]);

  const compareArtifactWithPreviousVersion = useCallback(async (version: ArtifactVersionRef) => {
    if (!workspaceId || version.version_no <= 1) {
      setStatus("当前版本没有可比较的上一版本");
      return;
    }
    await run("比较产物版本", async () => {
      const comparison = await api.compareVersions(
        workspaceId,
        version.artifact_job_id,
        version.version_no - 1,
        version.version_no
      );
      setStatus(comparison.summary);
    }, "artifact");
  }, [workspaceId, run, api, setStatus]);

  const downloadArtifactVersionPdf = useCallback(async (version: ArtifactPdfVersion) => {
    if (!workspaceId) {
      setStatus("请先创建工作台");
      return;
    }
    const exportStatus = version.runtime_trace?.export_trace?.status ?? "";
    const hasStoredPdf = Array.isArray(version.files)
      && version.files.some((file) => file.file_format === "PDF" && file.status === "READY");
    if (!hasStoredPdf && exportStatus !== "COMPILED") {
      setStatus("该版本尚未生成可下载的 PDF");
      return;
    }
    try {
      const blob = await api.exportPdf(workspaceId, version.artifact_job_id, version.version_no);
      downloadBlob(blob, `artifact-${version.artifact_job_id}-v${version.version_no}.pdf`);
      setStatus("PDF 已开始下载");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "PDF 下载失败");
    }
  }, [workspaceId, api, setStatus]);

  const downloadArtifactVersionFile = useCallback(async (
    version: ArtifactVersionRef, file: ArtifactFileMetadata
  ) => {
    if (!workspaceId || file.status !== "READY") {
      setStatus("该文件尚不可下载");
      return;
    }
    try {
      const blob = await api.downloadFile(workspaceId, version.artifact_job_id,
        version.version_no, file.file_id);
      const safeName = file.file_name.split(/[\\/]/).pop() ||
        `artifact-${version.artifact_job_id}-v${version.version_no}`;
      downloadBlob(blob, safeName);
      setStatus(`${file.file_format} 已开始下载`);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "产物文件下载失败");
    }
  }, [workspaceId, api, setStatus]);

  return {
    ...state,
    loadJobs,
    openArtifactHistoryVersion,
    saveArtifactVersionAsSource,
    writeArtifactVersionToKnowledge,
    regenerateArtifactVersion,
    rollbackArtifactVersion,
    compareArtifactWithPreviousVersion,
    downloadArtifactVersionPdf,
    downloadArtifactVersionFile
  };
}

function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.rel = "noopener";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function isArtifactJobActive(job: ArtifactJobSummary) {
  const jobStatus = job.status.trim().toUpperCase();
  const taskStatus = job.task_status.trim().toUpperCase();
  return !ARTIFACT_JOB_TERMINAL_STATUSES.has(jobStatus)
    && !ARTIFACT_JOB_TERMINAL_STATUSES.has(taskStatus);
}
