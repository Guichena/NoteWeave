import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import { navigateAppView } from "../shell/viewRoute";
import type { Workspace } from "../workspace/model";
import { researchApi } from "./api";
import {
  buildResearchSourceSelection,
  researchModeRequiresSeeds
} from "./launch";
import type {
  ResearchRetrievalMode,
  ResearchRunDetail,
  ResearchRunSummary
} from "./model";
import {
  buildLifecycleCreationNarrative
} from "./presentation";

type ResearchExecutionTask = {
  task_id?: string;
  task_status?: string;
  result_ref?: string;
  wait_context?: unknown;
  [key: string]: unknown;
};

type ResearchTaskRefreshResult = {
  task: ResearchExecutionTask;
  runs: ResearchRunSummary[];
  detail: ResearchRunDetail | null;
};

type UseResearchRunActionsInput = {
  workspace: Workspace | null;
  researchQuestion: string;
  researchProfile: string;
  researchGoal: string;
  researchDeliverableFormat: string;
  researchConstraintsText: string;
  researchTimeRange: string;
  researchDepth: string;
  researchType: string;
  researchRetrievalMode: ResearchRetrievalMode;
  researchScopeSources: Array<{ source_id: string }>;
  currentResearchRunId: string;
  latestResearchTask: ResearchExecutionTask | null;
  run: ShellRun;
  setStatus: (status: string) => void;
  appendSystemMessage: (content: string) => void;
  setView: (view: AppView) => void;
  prepareResearchRun: (researchRunId: string) => void;
  resetResearchState: () => void;
  loadExecution: (taskId: string) => Promise<unknown>;
  setLatestResearchTaskId: (taskId: string) => void;
  loadResearchRunHistory: (preferredRunId?: string) => Promise<ResearchRunSummary[]>;
  loadResearchRunDetail: (
    researchRunId: string,
    checkpointNo?: number | null
  ) => Promise<ResearchRunDetail | null>;
  refreshResearchTask: (
    taskId: string,
    researchRunId: string
  ) => Promise<ResearchTaskRefreshResult>;
  selectResearchCheckpoint: (
    researchRunId: string,
    checkpointNo: number,
    comparisonNo: number | null
  ) => Promise<unknown>;
  selectResearchComparison: (
    researchRunId: string,
    checkpointNo: number | null
  ) => Promise<unknown>;
  setResearchDetailOpen: (open: boolean) => void;
};

export function useResearchRunActions({
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
  researchScopeSources,
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
}: UseResearchRunActionsInput) {
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
    const readySourceIds = researchScopeSources.map((source) => source.source_id);
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
      await refreshResearchTask(created.task_id, created.research_run_id);
      const questionLabel = nextQuestion.trim().replace(/\s+/g, " ").slice(0, 64);
      appendSystemMessage(
        `Deep Research 已启动：${questionLabel || "当前研究问题"}。深度 ${nextDepth}，显式资料范围 ${readySourceIds.length} 份。${buildLifecycleCreationNarrative(
          nextQuestion,
          nextGoal,
          nextDepth,
          readySourceIds.length
        )}`
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


  return {
    startDeepResearch,
    refreshCurrentResearchRun,
    openResearchRunHistoryItem,
    openResearchWorkbench,
    openResearchCheckpoint,
    updateResearchCheckpointComparison,
    resumeResearchFromCheckpoint
  };
}

export type ResearchRunActions = ReturnType<typeof useResearchRunActions>;
