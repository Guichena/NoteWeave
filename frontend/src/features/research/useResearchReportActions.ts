import { useCallback } from "react";
import type { Workspace } from "../workspace/model";
import type { ShellRun } from "../shell/useShellBusy";
import { researchApi } from "./api";
import {
  buildArtifactRecoveryNarrative
} from "./presentation";
import type {
  ResearchRecoveryTargets,
  ResearchRunDetail,
  ResearchRunSummary
} from "./model";
import {
  buildResearchReportExportStatusMessage
} from "./researchReportDelivery";
import { formatSourceIndexStatus, formatSourceProcessingStatus } from "../sources/model";
import { sourcesApi, type SourcesApi } from "../sources/api";
import type { SourceAsset } from "../sources/model";

type UseResearchReportActionsInput = {
  workspace: Workspace | null;
  currentResearchRunId: string;
  currentResearchRun: ResearchRunDetail | null;
  currentResearchRunSummary: ResearchRunSummary | null;
  currentRunSummaryRecoveryTargets: ResearchRecoveryTargets | null;
  run: ShellRun;
  setStatus: (status: string) => void;
  appendSystemMessage: (content: string) => void;
  replaceSources: (sources: SourceAsset[]) => void;
  loadResearchRunDetail: (
    researchRunId: string,
    checkpointNo?: number | null
  ) => Promise<ResearchRunDetail | null>;
  loadResearchRunHistory: (preferredRunId?: string) => Promise<ResearchRunSummary[]>;
  sourceApi?: SourcesApi;
};

export function useResearchReportActions({
  workspace,
  currentResearchRunId,
  currentResearchRun,
  currentResearchRunSummary,
  currentRunSummaryRecoveryTargets,
  run,
  setStatus,
  appendSystemMessage,
  replaceSources,
  loadResearchRunDetail,
  loadResearchRunHistory,
  sourceApi = sourcesApi
}: UseResearchReportActionsInput) {
  const saveResearchReportAsSource = useCallback(async () => {
    if (!workspace || !currentResearchRunId) {
      setStatus("当前没有可写回的 Deep Research 报告");
      return;
    }
    await run("保存 Deep Research 报告到资料库", async () => {
      const saved = await researchApi.saveReportAsSource(
        workspace.workspace_id,
        currentResearchRunId
      );
      const nextSources = await sourceApi.list(workspace.workspace_id);
      replaceSources(nextSources);
      await loadResearchRunDetail(currentResearchRunId);
      await loadResearchRunHistory(currentResearchRunId);
      appendSystemMessage(
        `Deep Research 报告已写回资料库。${formatSourceProcessingStatus(saved.parse_status)}，${formatSourceIndexStatus(saved.index_status)}。${
          buildArtifactRecoveryNarrative(
            currentResearchRunSummary?.recovery_mode
              || currentResearchRun?.report_structure?.recovery_status.active_recovery_strategy
              || currentResearchRun?.report_structure?.recovery_mode
              || "",
            currentRunSummaryRecoveryTargets
          ) || ""
        }`
      );
    }, "research");
  }, [
    workspace,
    currentResearchRunId,
    currentResearchRun,
    currentResearchRunSummary,
    currentRunSummaryRecoveryTargets,
    run,
    setStatus,
    appendSystemMessage,
    replaceSources,
    loadResearchRunDetail,
    loadResearchRunHistory,
    sourceApi
  ]);

  const exportResearchReportMarkdown = useCallback(() => {
    if (!workspace || !currentResearchRunId) {
      setStatus("当前没有可导出的 Deep Research Markdown 报告");
      return;
    }
    // 正式导出统一走服务端下载入口：由后端执行当前来源资格判断，
    // 不再从已缓存的正文 Blob 绕过后端导出
    void run("导出 Deep Research Markdown 报告", async () => {
      try {
        const { blob, fileName } = await researchApi.downloadReportMarkdown(
          workspace.workspace_id,
          currentResearchRunId
        );
        const objectUrl = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = objectUrl;
        link.download = fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(objectUrl);
        setStatus(buildResearchReportExportStatusMessage(fileName));
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        setStatus(`报告导出被拒绝：${message}`);
      }
    }, "research");
  }, [workspace, currentResearchRunId, run, setStatus]);

  return {
    saveResearchReportAsSource,
    exportResearchReportMarkdown
  };
}
