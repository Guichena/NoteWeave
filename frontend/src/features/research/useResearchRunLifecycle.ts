import { useCallback, useEffect, useRef, useState } from "react";
import type { Workspace } from "../workspace/model";
import { isExecutionTerminal } from "../executions/api";
import { researchApi } from "./api";
import type {
  ResearchCollection,
  ResearchRunDetail,
  ResearchRunSummary
} from "./model";

export type ResearchExecutionSnapshot = {
  task: {
    task_id?: string;
    task_status?: string;
    result_ref?: string;
    wait_context?: unknown;
    [key: string]: unknown;
  };
  events: Array<{ event: string; [key: string]: unknown }>;
};

type ResearchTask = ResearchExecutionSnapshot["task"] | null;

type UseResearchRunLifecycleInput = {
  workspace: Workspace | null;
  currentResearchRunId: string;
  latestResearchTask: ResearchTask;
  fetchResearchRunHistory: () => Promise<ResearchRunSummary[]>;
  fetchResearchRunDetail: (
    researchRunId: string,
    checkpointNo?: number | null
  ) => Promise<ResearchRunDetail | null>;
  prepareResearchRun: (researchRunId: string) => void;
  loadExecution: (taskId: string) => Promise<ResearchExecutionSnapshot>;
  setLatestResearchTaskId: (id: string) => void;
};

export function useResearchRunLifecycle({
  workspace,
  currentResearchRunId,
  latestResearchTask,
  fetchResearchRunHistory,
  fetchResearchRunDetail,
  prepareResearchRun,
  loadExecution,
  setLatestResearchTaskId
}: UseResearchRunLifecycleInput) {
  const [currentResearchCollection, setCurrentResearchCollection] = useState<ResearchCollection | null>(null);
  const terminalResearchRefreshRef = useRef("");

  const loadResearchRunDetail = useCallback(async (
    researchRunId: string,
    checkpointNo?: number | null
  ) => {
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
  }, [fetchResearchRunDetail, workspace]);

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
    currentResearchRunId,
    fetchResearchRunHistory,
    loadExecution,
    loadResearchRunDetail,
    prepareResearchRun,
    setLatestResearchTaskId,
    workspace
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
    currentResearchRunId,
    latestResearchTask?.result_ref,
    latestResearchTask?.task_id,
    latestResearchTask?.task_status,
    loadResearchRunDetail,
    loadResearchRunHistory,
    workspace
  ]);

  const refreshResearchTask = useCallback(async (taskId: string, researchRunId: string) => {
    const execution = await loadExecution(taskId);
    const task = execution.task;
    setLatestResearchTaskId(taskId);
    const runs = await loadResearchRunHistory(task.result_ref || researchRunId);
    const detail = await loadResearchRunDetail(task.result_ref || researchRunId);
    return { task, runs, detail };
  }, [loadExecution, loadResearchRunDetail, loadResearchRunHistory, setLatestResearchTaskId]);

  return {
    currentResearchCollection,
    loadResearchRunDetail,
    loadResearchRunHistory,
    refreshResearchTask
  };
}
