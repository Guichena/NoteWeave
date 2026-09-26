import { useMemo, useState } from "react";
import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import type { SourceAsset } from "../sources/model";
import type { Workspace } from "../workspace/model";
import { buildResearchDerivedModel } from "./derived";
import {
  DEFAULT_RESEARCH_RETRIEVAL_MODE
} from "./launch";
import {
  type ResearchHistoryFilter,
  type ResearchRetrievalMode
} from "./model";
import {
  extractRunSummarySnapshot
} from "./presentation";
import { useResearchState } from "./useResearchState";
import { useResearchReportActions } from "./useResearchReportActions";
import { useResearchRunActions } from "./useResearchRunActions";
import {
  type ResearchExecutionSnapshot,
  useResearchRunLifecycle
} from "./useResearchRunLifecycle";
import { useResearchSourceScope } from "./useResearchSourceScope";
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

type UseResearchWorkbenchControllerInput = {
  workspace: Workspace | null;
  sources: SourceAsset[];
  replaceSources: (sources: SourceAsset[]) => void;
  setView: (view: AppView) => void;
  run: ShellRun;
  setStatus: (status: string) => void;
  appendSystemMessage: (content: string) => void;
  loadExecution: (taskId: string) => Promise<ResearchExecutionSnapshot>;
  getExecution: (taskId: string) => ResearchExecutionSnapshot | undefined;
  latestResearchTaskId: string;
  setLatestResearchTaskId: (id: string) => void;
};

export function useResearchWorkbenchController({
  workspace,
  sources,
  replaceSources,
  setView,
  run,
  setStatus,
  appendSystemMessage,
  loadExecution,
  getExecution,
  latestResearchTaskId,
  setLatestResearchTaskId
}: UseResearchWorkbenchControllerInput) {
  const [researchQuestion, setResearchQuestion] = useState("");
  const [researchProfile, setResearchProfile] = useState("default");
  const [researchGoal, setResearchGoal] = useState("");
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
  const [researchHistoryFilter, setResearchHistoryFilter] = useState<ResearchHistoryFilter>("ALL");
  const [researchDetailOpen, setResearchDetailOpen] = useState(false);

  const {
    selectedResearchSourceIds,
    setSelectedResearchSourceIds,
    focusedResearchSourceId,
    setFocusedResearchSourceId,
    toggleResearchScope,
    addResearchSourceToScope,
    removeResearchSourceFromScope
  } = useResearchSourceScope({ sources });

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

  const {
    currentResearchCollection,
    loadResearchRunDetail,
    loadResearchRunHistory,
    refreshResearchTask
  } = useResearchRunLifecycle({
    workspace,
    currentResearchRunId,
    latestResearchTask,
    fetchResearchRunHistory,
    fetchResearchRunDetail,
    prepareResearchRun,
    loadExecution,
    setLatestResearchTaskId
  });

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

  const {
    startDeepResearch,
    refreshCurrentResearchRun,
    openResearchRunHistoryItem,
    openResearchWorkbench,
    openResearchCheckpoint,
    updateResearchCheckpointComparison,
    resumeResearchFromCheckpoint
  } = useResearchRunActions({
    workspace,
    researchQuestion,
    researchProfile,
    researchGoal,
    researchDeliverableFormat,
    researchConstraintsText,
    researchTimeRange,
    researchDepth,
    researchType,
    researchRetrievalMode,
    researchScopeSources: derived.researchScopeSources,
    currentResearchRunId,
    latestResearchTask,
    run,
    setStatus,
    appendSystemMessage,
    setView,
    prepareResearchRun,
    resetResearchState,
    loadExecution,
    setLatestResearchTaskId,
    loadResearchRunHistory,
    loadResearchRunDetail,
    refreshResearchTask,
    selectResearchCheckpoint,
    selectResearchComparison,
    setResearchDetailOpen
  });

  const { saveResearchReportAsSource, exportResearchReportMarkdown } = useResearchReportActions({
    workspace,
    currentResearchRunId,
    currentResearchRun,
    currentResearchRunSummary: derived.currentResearchRunSummary,
    currentRunSummaryRecoveryTargets,
    run,
    setStatus,
    appendSystemMessage,
    replaceSources,
    loadResearchRunDetail,
    loadResearchRunHistory
  });

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
