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
  buildResearchReportExportArtifact,
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
    const exportArtifact = buildResearchReportExportArtifact({
      final_report_markdown: currentResearchRun?.final_report_markdown,
      final_report_title: currentResearchRun?.final_report_title,
      question: currentResearchRun?.question
    });
    if (!exportArtifact) {
      setStatus("当前没有可导出的 Deep Research Markdown 报告");
      return;
    }
    const blob = new Blob([exportArtifact.content], { type: exportArtifact.mimeType });
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = exportArtifact.fileName;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(objectUrl);
    setStatus(buildResearchReportExportStatusMessage(exportArtifact.fileName));
  }, [currentResearchRun, setStatus]);

  return {
    saveResearchReportAsSource,
    exportResearchReportMarkdown
  };
}
