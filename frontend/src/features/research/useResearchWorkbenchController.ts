import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { isExecutionTerminal } from "../executions/api";
import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import { navigateAppView } from "../shell/viewRoute";
import type { SourceAsset } from "../sources/model";
import type { Workspace } from "../workspace/model";
import { researchApi } from "./api";
import { buildResearchDerivedModel } from "./derived";
import {
  buildResearchSourceSelection,
  DEFAULT_RESEARCH_RETRIEVAL_MODE,
  isResearchSourceReady,
  researchModeRequiresSeeds
} from "./launch";
import {
  type ResearchCollection,
  type ResearchHistoryFilter,
  type ResearchRetrievalMode,
  type ResearchRunSummary
} from "./model";
import {
  buildArtifactRecoveryNarrative,
  buildLifecycleCreationNarrative,
  buildRunContinuityBaselineLabel,
  buildRunContinuityNarrative,
  extractRunSummarySnapshot,
  findRunContinuityBaseline
} from "./presentation";
import { useResearchState } from "./useResearchState";
import {
  buildResearchReportExportArtifact,
  buildResearchReportExportStatusMessage
} from "./researchReportDelivery";
import {
  buildWaitContextDetailLines,
  buildWaitContextSignalChips,
  type WaitContext
} from "../../runStatus";

function asWaitContext(value: unknown): WaitContext | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  return value as WaitContext;
}

type ExecutionSnapshot = {
  task: {
    task_id?: string;
    task_status?: string;
    result_ref?: string;
    wait_context?: unknown;
    [key: string]: unknown;
  };
  events: Array<{ event: string; [key: string]: unknown }>;
};

type UseResearchWorkbenchControllerInput = {
  workspace: Workspace | null;
  sources: SourceAsset[];
  setView: (view: AppView) => void;
  run: ShellRun;
  setStatus: (status: string) => void;
  appendSystemMessage: (content: string) => void;
  loadExecution: (taskId: string) => Promise<ExecutionSnapshot>;
  getExecution: (taskId: string) => ExecutionSnapshot | undefined;
  latestResearchTaskId: string;
  setLatestResearchTaskId: (id: string) => void;
};

export function useResearchWorkbenchController({
  workspace,
  sources,
  setView,
  run,
  setStatus,
  appendSystemMessage,
  loadExecution,
  getExecution,
  latestResearchTaskId,
  setLatestResearchTaskId
}: UseResearchWorkbenchControllerInput) {
  const [researchQuestion, setResearchQuestion] = useState(
    "请围绕当前主题开展 Deep Research，并明确给出已验证结论、冲突点和后续恢复建议。"
  );
  const [researchProfile, setResearchProfile] = useState("default");
  const [researchGoal, setResearchGoal] = useState("沉淀一份可验证、可恢复的研究结论摘要。");
  const [researchDeliverableFormat, setResearchDeliverableFormat] = useState(
    "Evidence-backed research report"
  );
  const [researchConstraintsText, setResearchConstraintsText] = useState(
    "必须显式区分已验证结论、冲突点与后续恢复动作。"
  );
  const [researchTimeRange, setResearchTimeRange] = useState("");
  const [researchDepth, setResearchDepth] = useState("STANDARD");
  const [researchType, setResearchType] = useState("AUTO");
  const [researchRetrievalMode, setResearchRetrievalMode] = useState<ResearchRetrievalMode>(
    DEFAULT_RESEARCH_RETRIEVAL_MODE
  );
  const [selectedResearchSourceIds, setSelectedResearchSourceIds] = useState<string[]>([]);
  const [currentResearchCollection, setCurrentResearchCollection] = useState<ResearchCollection | null>(null);
  const [researchHistoryFilter, setResearchHistoryFilter] = useState<ResearchHistoryFilter>("ALL");
  const [focusedResearchSourceId, setFocusedResearchSourceId] = useState("");
  const [researchDetailOpen, setResearchDetailOpen] = useState(false);

  const {
    researchRuns,
    currentResearchRunId,
    currentResearchRun,
    selectedResearchCheckpointNo,
    selectedResearchCheckpoint,
    compareResearchCheckpointNo,
    compareResearchCheckpoint,
    clear: resetResearchState,
    prepareRun: prepareResearchRun,
    loadHistory: fetchResearchRunHistory,
    loadRunDetail: fetchResearchRunDetail,
    selectCheckpoint: selectResearchCheckpoint,
    selectComparison: selectResearchComparison
  } = useResearchState({
    workspaceId: workspace?.workspace_id ?? ""
  });

  const latestResearchTaskExecution = getExecution(latestResearchTaskId);
  const latestResearchTask = latestResearchTaskExecution?.task ?? null;
  const researchTaskEvents = latestResearchTaskExecution?.events ?? [];
  const terminalResearchRefreshRef = useRef("");

  useEffect(() => {
    setSelectedResearchSourceIds((current) =>
      current.filter((sourceId) => sources.some((source) =>
        source.source_id === sourceId && isResearchSourceReady(source)))
    );
  }, [sources]);

  useEffect(() => {
    if (!focusedResearchSourceId) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      const target = document.getElementById(`research-source-scope-${focusedResearchSourceId}`);
      if (target) {
        target.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [focusedResearchSourceId, sources.length]);

  function toggleResearchScope(sourceId: string) {
    if (!sources.some((source) => source.source_id === sourceId && isResearchSourceReady(source))) {
      return;
    }
    setSelectedResearchSourceIds((current) => (
      current.includes(sourceId)
        ? current.filter((entry) => entry !== sourceId)
        : [...current, sourceId]
    ));
  }

  function addResearchSourceToScope(sourceId: string) {
    if (!sources.some((source) => source.source_id === sourceId && isResearchSourceReady(source))) {
      return;
    }
    setSelectedResearchSourceIds((current) => (
      current.includes(sourceId) ? current : [...current, sourceId]
    ));
    setFocusedResearchSourceId(sourceId);
  }

  function removeResearchSourceFromScope(sourceId: string) {
    setSelectedResearchSourceIds((current) => current.filter((entry) => entry !== sourceId));
    setFocusedResearchSourceId(sourceId);
  }

  const loadResearchRunDetail = useCallback(async (researchRunId: string, checkpointNo?: number | null) => {
    if (!workspace) {
      return null;
    }
    const detail = await fetchResearchRunDetail(researchRunId, checkpointNo);
    if (detail?.status === "COMPLETED") {
      try {
        setCurrentResearchCollection(await researchApi.getCollection(workspace.workspace_id, researchRunId));
      } catch {
        setCurrentResearchCollection(null);
      }
    } else {
      setCurrentResearchCollection(null);
    }
    return detail;
  }, [workspace, fetchResearchRunDetail]);

  const loadResearchRunHistory = useCallback(async (preferredRunId?: string) => {
    if (!workspace) {
      return [];
    }
    const runs = await fetchResearchRunHistory();
    const nextRunId = preferredRunId || currentResearchRunId || runs[0]?.research_run_id || "";
    if (nextRunId) {
      prepareResearchRun(nextRunId);
      const selected = runs.find((run) => run.research_run_id === nextRunId) ?? runs[0];
      if (selected?.task_id) {
        await loadExecution(selected.task_id);
        setLatestResearchTaskId(selected.task_id);
      }
      await loadResearchRunDetail(nextRunId);
    }
    return runs;
  }, [
    workspace,
    fetchResearchRunHistory,
    currentResearchRunId,
    prepareResearchRun,
    loadExecution,
    setLatestResearchTaskId,
    loadResearchRunDetail
  ]);

  useEffect(() => {
    const taskId = latestResearchTask?.task_id || "";
    const taskStatus = latestResearchTask?.task_status || "";
    const runId = latestResearchTask?.result_ref || currentResearchRunId;
    if (!workspace || !taskId || !runId || !isExecutionTerminal(taskStatus)) {
      return;
    }
    const refreshKey = `${taskId}:${taskStatus}`;
    if (terminalResearchRefreshRef.current === refreshKey) {
      return;
    }
    terminalResearchRefreshRef.current = refreshKey;
    void (async () => {
      try {
        await loadResearchRunHistory(runId);
        await loadResearchRunDetail(runId);
      } catch {
        terminalResearchRefreshRef.current = "";
      }
    })();
  }, [
    workspace,
    latestResearchTask?.task_id,
    latestResearchTask?.task_status,
    latestResearchTask?.result_ref,
    currentResearchRunId,
    loadResearchRunHistory,
    loadResearchRunDetail
  ]);

  async function refreshResearchTask(taskId: string, researchRunId: string) {
    const execution = await loadExecution(taskId);
    const task = execution.task;
    setLatestResearchTaskId(taskId);
    const runs = await loadResearchRunHistory(task.result_ref || researchRunId);
    const detail = await loadResearchRunDetail(task.result_ref || researchRunId);
    return { task, runs, detail };
  }

  async function startDeepResearch() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const nextQuestion = researchQuestion.trim();
    if (!nextQuestion) {
      setStatus("请先填写 Deep Research 问题");
      return;
    }
    const readySourceIds = derived.researchScopeSources.map((source) => source.source_id);
    if (researchModeRequiresSeeds(researchRetrievalMode) && readySourceIds.length === 0) {
      setStatus(`${researchRetrievalMode} 模式至少需要一份已解析资料`);
      return;
    }
    const nextProfile = researchProfile.trim() || "default";
    const nextGoal = researchGoal.trim();
    const nextDeliverableFormat = researchDeliverableFormat.trim();
    const nextConstraints = researchConstraintsText
      .split(/\r?\n/)
      .map((item) => item.trim())
      .filter(Boolean);
    const nextTimeRange = researchTimeRange.trim();
    const nextDepth = researchDepth.trim() || "STANDARD";
    const nextResearchType = researchType.trim() || "AUTO";
    await run("启动 Deep Research", async () => {
      const created = await researchApi.createRun(workspace.workspace_id, {
        question: nextQuestion,
        profile: nextProfile,
        research_goal: nextGoal,
        deliverable_format: nextDeliverableFormat,
        constraints: nextConstraints,
        time_range: nextTimeRange,
        depth: nextDepth,
        research_type: nextResearchType,
        retrieval_mode: researchRetrievalMode,
        ...buildResearchSourceSelection(researchRetrievalMode, readySourceIds)
      });
      prepareResearchRun(created.research_run_id);
      const refreshed = await refreshResearchTask(created.task_id, created.research_run_id);
      const createdRunSummary = refreshed.runs.find((item) => item.research_run_id === created.research_run_id) ?? null;
      const createdRunBaseline = findRunContinuityBaseline(refreshed.runs, createdRunSummary);
      const createdRunBaselineLabel = createdRunSummary
        ? buildRunContinuityBaselineLabel(createdRunBaseline, createdRunSummary)
        : "";
      const createdRunContinuityNarrative = createdRunSummary
        ? buildRunContinuityNarrative(createdRunBaseline, createdRunSummary)
        : "";
      appendSystemMessage(
        `已创建 Deep Research：run=${created.research_run_id}，profile=${nextProfile}，depth=${nextDepth}，显式资料范围 ${readySourceIds.length} 份。${buildLifecycleCreationNarrative(
          nextQuestion,
          nextGoal,
          nextDepth,
          readySourceIds.length
        )}${createdRunBaselineLabel ? ` continuity baseline=${createdRunBaselineLabel}。` : ""}${createdRunContinuityNarrative ? ` ${createdRunContinuityNarrative}` : ""}`
      );
    }, "research");
  }

  async function refreshCurrentResearchRun() {
    if (!workspace || !currentResearchRunId) {
      setStatus("当前还没有可刷新的 Deep Research run");
      return;
    }
    await run("刷新 Deep Research", async () => {
      if (latestResearchTask?.task_id) {
        await refreshResearchTask(latestResearchTask.task_id, currentResearchRunId);
      } else {
        await loadResearchRunHistory(currentResearchRunId);
        await loadResearchRunDetail(currentResearchRunId);
      }
    }, "research");
  }

  async function openResearchRunHistoryItem(runSummary: ResearchRunSummary) {
    if (!workspace) {
      return;
    }
    await run(`打开 Research Run ${runSummary.research_run_id}`, async () => {
      prepareResearchRun(runSummary.research_run_id);
      if (runSummary.task_id) {
        await loadExecution(runSummary.task_id);
        setLatestResearchTaskId(runSummary.task_id);
      }
      await loadResearchRunDetail(runSummary.research_run_id);
    }, "research");
  }

  async function openResearchWorkbench() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    setView("research");
    navigateAppView("research");
    await run("打开 Deep Research 工作台", async () => {
      const runs = await loadResearchRunHistory();
      if (!currentResearchRunId && runs.length === 0) {
        resetResearchState();
      }
    }, "research");
  }

  async function openResearchCheckpoint(checkpointNo: number, comparisonNo: number | null) {
    if (!currentResearchRunId) {
      return;
    }
    await selectResearchCheckpoint(currentResearchRunId, checkpointNo, comparisonNo);
    setResearchDetailOpen(true);
  }

  async function updateResearchCheckpointComparison(checkpointNo: number | null) {
    if (!currentResearchRunId) {
      return;
    }
    await selectResearchComparison(currentResearchRunId, checkpointNo);
  }

  async function resumeResearchFromCheckpoint(checkpointNo: number) {
    if (!workspace || !currentResearchRunId) {
      setStatus("当前没有可恢复的 Research run");
      return;
    }
    await run(`从 checkpoint #${checkpointNo} 恢复研究`, async () => {
      const created = await researchApi.resumeFromCheckpoint(
        workspace.workspace_id,
        currentResearchRunId,
        checkpointNo
      );
      prepareResearchRun(created.research_run_id);
      await refreshResearchTask(created.task_id, created.research_run_id);
      setResearchDetailOpen(true);
    }, "research");
  }

  const derived = useMemo(() => buildResearchDerivedModel({
    sources,
    selectedResearchSourceIds,
    researchRuns,
    researchHistoryFilter,
    currentResearchRunId,
    currentResearchRun,
    researchQuestion,
    researchGoal
  }), [
    sources,
    selectedResearchSourceIds,
    researchRuns,
    researchHistoryFilter,
    currentResearchRunId,
    currentResearchRun,
    researchQuestion,
    researchGoal
  ]);

  const currentSavedReportSource = currentResearchRun?.saved_report_source ?? null;
  const currentSavedReportSourceAsset = currentSavedReportSource
    ? sources.find((source) => source.source_id === currentSavedReportSource.source_id) ?? null
    : null;
  const currentSavedReportSourceInScope = currentSavedReportSource
    ? selectedResearchSourceIds.includes(currentSavedReportSource.source_id)
    : false;
  const currentRunSummarySnapshot = extractRunSummarySnapshot(derived.currentResearchRunSummary);
  const currentRunSummaryRecoveryTargets = currentRunSummarySnapshot.recoveryTargets;
  const currentResearchWaitContext = currentResearchRun?.wait_context
    ?? derived.currentResearchRunSummary?.wait_context
    ?? latestResearchTask?.wait_context
    ?? null;
  const currentResearchWaitSignals = buildWaitContextSignalChips(asWaitContext(currentResearchWaitContext));
  const currentResearchWaitDetails = buildWaitContextDetailLines(asWaitContext(currentResearchWaitContext));
  const latestResearchTaskWaitSignals = buildWaitContextSignalChips(asWaitContext(latestResearchTask?.wait_context));
  const latestResearchTaskWaitDetails = buildWaitContextDetailLines(asWaitContext(latestResearchTask?.wait_context));
  const resultSnapshotTitle = currentResearchRun?.final_report_title
    || derived.currentResearchRunSummary?.final_report_title
    || currentResearchRun?.question
    || researchQuestion
    || "Deep Research";
  const resultSnapshotNarrative = derived.currentFinalAnswer.answer_text
    || derived.currentExecutiveSummary[0]
    || "研究完成后会在这里展示结论摘要。";
  const latestResearchProgressEvent = [...researchTaskEvents]
    .reverse()
    .find((event) => event.event === "task.progress")
    ?? null;

  async function saveResearchReportAsSource() {
    if (!workspace || !currentResearchRunId) {
      setStatus("当前没有可写回的 Deep Research 报告");
      return;
    }
    await run("保存 Deep Research 报告到资料池", async () => {
      const saved = await researchApi.saveReportAsSource(
        workspace.workspace_id,
        currentResearchRunId
      );
      await loadResearchRunDetail(currentResearchRunId);
      const runs = await loadResearchRunHistory(currentResearchRunId);
      const savedRunSummary = runs.find((item) => item.research_run_id === currentResearchRunId) ?? null;
      const savedRunBaseline = findRunContinuityBaseline(runs, savedRunSummary);
      const savedRunBaselineLabel = savedRunSummary
        ? buildRunContinuityBaselineLabel(savedRunBaseline, savedRunSummary)
        : "";
      const savedRunContinuityNarrative = savedRunSummary
        ? buildRunContinuityNarrative(savedRunBaseline, savedRunSummary)
        : "";
      appendSystemMessage(
        `Deep Research 报告已写回资料池：source=${saved.source_id}，status=${saved.status}，index=${saved.index_status}。${
          buildArtifactRecoveryNarrative(
            derived.currentResearchRunSummary?.recovery_mode
              || currentResearchRun?.report_structure?.recovery_status.active_recovery_strategy
              || currentResearchRun?.report_structure?.recovery_mode
              || "",
            currentRunSummaryRecoveryTargets
          ) || ""
        }${savedRunBaselineLabel ? ` continuity baseline=${savedRunBaselineLabel}。` : ""}${savedRunContinuityNarrative ? ` ${savedRunContinuityNarrative}` : ""}`
      );
    }, "research");
  }

  function exportResearchReportMarkdown() {
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
  }

  return {
    resetResearchState,
    selectedResearchSourceIds,
    setSelectedResearchSourceIds,
    researchQuestion,
    setResearchQuestion,
    researchProfile,
    setResearchProfile,
    researchGoal,
    setResearchGoal,
    researchDeliverableFormat,
    setResearchDeliverableFormat,
    researchConstraintsText,
    setResearchConstraintsText,
    researchTimeRange,
    setResearchTimeRange,
    researchDepth,
    setResearchDepth,
    researchType,
    setResearchType,
    researchRetrievalMode,
    setResearchRetrievalMode,
    researchHistoryFilter,
    setResearchHistoryFilter,
    focusedResearchSourceId,
    setFocusedResearchSourceId,
    researchDetailOpen,
    setResearchDetailOpen,
    currentResearchCollection,
    researchRuns,
    currentResearchRunId,
    currentResearchRun,
    selectedResearchCheckpointNo,
    selectedResearchCheckpoint,
    compareResearchCheckpointNo,
    compareResearchCheckpoint,
    latestResearchTask,
    researchTaskEvents,
    latestResearchProgressEvent,
    toggleResearchScope,
    addResearchSourceToScope,
    removeResearchSourceFromScope,
    loadResearchRunHistory,
    startDeepResearch,
    refreshCurrentResearchRun,
    openResearchRunHistoryItem,
    openResearchWorkbench,
    openResearchCheckpoint,
    updateResearchCheckpointComparison,
    resumeResearchFromCheckpoint,
    saveResearchReportAsSource,
    exportResearchReportMarkdown,
    ...derived,
    currentSavedReportSource,
    currentSavedReportSourceAsset,
    currentSavedReportSourceInScope,
    currentRunSummaryRecoveryTargets,
    currentResearchWaitContext,
    currentResearchWaitSignals,
    currentResearchWaitDetails,
    latestResearchTaskWaitSignals,
    latestResearchTaskWaitDetails,
    resultSnapshotTitle,
    resultSnapshotNarrative
  };
}

export type ResearchWorkbenchController = ReturnType<typeof useResearchWorkbenchController>;
