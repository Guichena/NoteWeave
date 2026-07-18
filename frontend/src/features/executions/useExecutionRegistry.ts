import { useCallback, useEffect, useReducer, useRef } from "react";
import { executionsApi, type ExecutionsApi, isExecutionTerminal } from "./api";
import {
  createExecutionRegistryState,
  reduceExecutionRegistry
} from "./store";

export function useExecutionRegistry(
  workspaceId: string,
  pollIntervalMs = 2_500,
  api: ExecutionsApi = executionsApi
) {
  const [state, dispatch] = useReducer(
    reduceExecutionRegistry,
    workspaceId,
    createExecutionRegistryState
  );
  const controllersRef = useRef(new Map<string, AbortController>());

  useEffect(() => {
    controllersRef.current.forEach((controller) => controller.abort());
    controllersRef.current.clear();
    dispatch({ type: "workspace", workspaceId });
  }, [workspaceId]);

  useEffect(() => () => {
    controllersRef.current.forEach((controller) => controller.abort());
    controllersRef.current.clear();
  }, []);

  const visibleState = state.workspaceId === workspaceId
    ? state
    : createExecutionRegistryState(workspaceId);

  const loadExecution = useCallback(async (taskId: string) => {
    if (!taskId) {
      throw new Error("taskId 不能为空");
    }
    controllersRef.current.get(taskId)?.abort();
    const controller = new AbortController();
    controllersRef.current.set(taskId, controller);
    dispatch({ type: "loading", taskId });
    try {
      const snapshot = await api.load(taskId, { signal: controller.signal });
      if (controllersRef.current.get(taskId) === controller && !controller.signal.aborted) {
        dispatch({ type: "loaded", taskId, snapshot, loadedAt: Date.now() });
      }
      return snapshot;
    } catch (error) {
      if (!controller.signal.aborted && controllersRef.current.get(taskId) === controller) {
        dispatch({
          type: "error",
          taskId,
          error: error instanceof Error ? error.message : "Execution 加载失败"
        });
      }
      throw error;
    } finally {
      if (controllersRef.current.get(taskId) === controller) {
        controllersRef.current.delete(taskId);
      }
    }
  }, [api]);

  const activeTaskIds = visibleState.trackedTaskIds.filter((taskId) => {
    const record = visibleState.records[taskId];
    return !record || !isExecutionTerminal(record.task.task_status);
  });
  const activeKey = activeTaskIds.join("|");

  useEffect(() => {
    if (!activeKey || pollIntervalMs <= 0) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      activeTaskIds.forEach((taskId) => void loadExecution(taskId).catch(() => undefined));
    }, pollIntervalMs);
    return () => window.clearTimeout(timeoutId);
  }, [activeKey, loadExecution, pollIntervalMs]);

  const getExecution = useCallback(
    (taskId: string) => visibleState.records[taskId] ?? null,
    [visibleState.records]
  );

  const untrackExecution = useCallback((taskId: string) => {
    controllersRef.current.get(taskId)?.abort();
    controllersRef.current.delete(taskId);
    dispatch({ type: "untrack", taskId });
  }, []);

  return {
    records: visibleState.records,
    trackedTaskIds: visibleState.trackedTaskIds,
    getExecution,
    loadExecution,
    untrackExecution
  };
}
