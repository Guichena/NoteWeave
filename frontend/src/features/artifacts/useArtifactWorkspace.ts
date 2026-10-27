import { useCallback, useEffect } from "react";
import { type ArtifactHistoryItem } from "./artifactHistory";
import { shouldReuseLatestArtifactVersion } from "./artifactHistorySelection";
import { type ArtifactRuntimeTrace } from "./artifactRuntimeTrace";
import type { ShellRun } from "../shell/useShellBusy";
import { sourcesApi, type SourcesApi } from "../sources/api";
import { type SourceAsset } from "../sources/model";
import { artifactsApi, type ArtifactsApi } from "./api";
import { ApiError } from "../../shared/api/error";
import { type ArtifactFileMetadata, type ArtifactJobSummary, type ArtifactVersionComparison } from "./model";
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

// NR22：编辑保存结果契约——阅读器据此决定是否清理草稿
export type ArtifactEditOutcome = { ok: true } | { ok: false; code: string };

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
    if (!workspaceId || artifactPollIntervalMs <= 0) {
      return;
    }
    // NR26：轮询不完全依赖列表里的活动任务——曾出现过活动任务但列表查询失败
    // （列表被清空后不再轮询，后台完成永远不恢复）。有活动任务或最近一次查询
    // 失败时都继续按间隔重试，正常空闲（无活动任务且查询正常）不无意义轮询。
    const hasActiveJob = state.artifactJobs.some(isArtifactJobActive);
    if (!hasActiveJob && !state.lastJobsFailed) {
      return;
    }
    const timer = window.setTimeout(() => {
      void loadJobs();
    }, artifactPollIntervalMs);
    return () => window.clearTimeout(timer);
  }, [workspaceId, artifactPollIntervalMs, state.artifactJobs, state.lastJobsFailed, loadJobs]);

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

  // NR22：编辑保存返回明确结果——阅读器只在成功后清理草稿；失败/冲突保留编辑器内容。
  // run() 会吞掉 action 内的错误，因此结果经闭包变量带出。
  const editArtifactVersion = useCallback(async (
    version: ArtifactVersionRef,
    markdown: string,
    editReason?: string,
    requestId?: string
  ): Promise<ArtifactEditOutcome> => {
    if (!workspaceId) {
      setStatus("请先创建工作台");
      return { ok: false, code: "WORKSPACE_MISSING" };
    }
    if (!markdown.trim()) {
      setStatus("编辑内容为空，未生成新版本");
      return { ok: false, code: "CONTENT_EMPTY" };
    }
    let outcome: ArtifactEditOutcome = { ok: true };
    await run("保存产物正文编辑", async () => {
      try {
        const edited = await api.editVersion(
          workspaceId,
          version.artifact_job_id,
          version.version_no,
          {
            request_id: requestId ?? `edit-${version.artifact_job_id}-${version.version_no}-${crypto.randomUUID()}`,
            expected_base_version_no: version.version_no,
            markdown,
            edit_reason: editReason ?? ""
          }
        );
        if (!edited.created) {
          outcome = { ok: false, code: "CONTENT_UNCHANGED" };
          setStatus("正文没有变化，未生成新版本");
          return;
        }
        setStatus(`已生成编辑版本 v${edited.version_no}，原生成任务保持不变`);
        await loadJobs();
      } catch (error) {
        // NR22：按 ApiError.code 分支，不再从 message 字符串匹配
        const code =
          error instanceof ApiError
            ? error.code
            : error instanceof Error
              ? error.message
              : "UNKNOWN";
        outcome = { ok: false, code };
        if (code === "ARTIFACT_EDIT_BASE_CONFLICT") {
          setStatus("基础版本已变化，请刷新后基于最新版本重新编辑（未保存的内容仍在编辑器中）");
        } else if (code === "ARTIFACT_EDIT_REQUEST_CONFLICT") {
          setStatus("该编辑请求身份已提交过不同内容，请重新发起编辑");
        } else if (code === "ARTIFACT_EDIT_TYPE_UNSUPPORTED") {
          setStatus("包含 PDF/PPTX 产物的版本不能通过 Markdown 编辑修改，请使用再生成");
        } else {
          setStatus("保存编辑失败，请稍后重试（未保存的内容仍在编辑器中）");
        }
      }
    });
    return outcome;
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
      return null;
    }
    let comparison: ArtifactVersionComparison | null = null;
    await run("比较产物版本", async () => {
      const result = await api.compareVersions(
        workspaceId,
        version.artifact_job_id,
        version.version_no - 1,
        version.version_no
      );
      comparison = result;
      setStatus(result.summary);
    }, "artifact");
    return comparison;
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
    retryVersion: state.retryVersion,
    openArtifactHistoryVersion,
    saveArtifactVersionAsSource,
    writeArtifactVersionToKnowledge,
    regenerateArtifactVersion,
    rollbackArtifactVersion,
    editArtifactVersion,
    compareArtifactWithPreviousVersion,
    downloadArtifactVersionPdf,
    downloadArtifactVersionFile
  };
}

export function downloadBlob(blob: Blob, fileName: string) {
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
