import { useCallback, useEffect, useReducer, useRef } from "react";
import { executionsApi, type ExecutionsApi, isExecutionTerminal } from "./api";
import {
  createExecutionRegistryState,
  reduceExecutionRegistry
} from "./store";
import { ExecutionObserver } from "./observer";

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
  const pendingLoadsRef = useRef(new Map<string, Promise<Awaited<ReturnType<ExecutionsApi["load"]>>>>());
  const eventCursorRef = useRef(new Map<string, string>());
  const observersRef = useRef(new Map<string, ExecutionObserver>());

  useEffect(() => {
    controllersRef.current.forEach((controller) => controller.abort());
    controllersRef.current.clear();
    pendingLoadsRef.current.clear();
    observersRef.current.forEach((observer) => observer.stop());
    observersRef.current.clear();
    eventCursorRef.current.clear();
    dispatch({ type: "workspace", workspaceId });
  }, [workspaceId]);

  useEffect(() => () => {
    controllersRef.current.forEach((controller) => controller.abort());
    controllersRef.current.clear();
    pendingLoadsRef.current.clear();
    observersRef.current.forEach((observer) => observer.stop());
    observersRef.current.clear();
    eventCursorRef.current.clear();
  }, []);

  const visibleState = state.workspaceId === workspaceId
    ? state
    : createExecutionRegistryState(workspaceId);

  const loadExecution = useCallback((taskId: string) => {
    if (!taskId) {
      return Promise.reject(new Error("taskId 不能为空"));
    }
    const pending = pendingLoadsRef.current.get(taskId);
    if (pending) {
      return pending;
    }
    const controller = new AbortController();
    controllersRef.current.set(taskId, controller);
    dispatch({ type: "loading", taskId });
    let request!: Promise<Awaited<ReturnType<ExecutionsApi["load"]>>>;
    request = (async () => {
      try {
        const snapshot = await api.load(
          taskId,
          { signal: controller.signal },
          eventCursorRef.current.get(taskId) ?? ""
        );
        if (controllersRef.current.get(taskId) === controller && !controller.signal.aborted) {
          const latestEvent = snapshot.events.at(-1);
          if (latestEvent?.id) {
            eventCursorRef.current.set(taskId, latestEvent.id);
          }
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
        if (pendingLoadsRef.current.get(taskId) === request) {
          pendingLoadsRef.current.delete(taskId);
        }
      }
    })();
    pendingLoadsRef.current.set(taskId, request);
    return request;
  }, [api]);

  const activeTaskIds = visibleState.trackedTaskIds.filter((taskId) => {
    const record = visibleState.records[taskId];
    return !record || !isExecutionTerminal(record.task.task_status);
  });
  const activeKey = activeTaskIds.join("|");

  useEffect(() => {
    const active = new Set(activeTaskIds);
    observersRef.current.forEach((observer, taskId) => {
      if (!active.has(taskId)) {
        observer.stop();
        observersRef.current.delete(taskId);
      }
    });
    activeTaskIds.forEach((taskId) => {
      if (observersRef.current.has(taskId)) {
        return;
      }
      const observer = new ExecutionObserver({
        taskId,
        pollIntervalMs,
        refresh: () => loadExecution(taskId)
      });
      observersRef.current.set(taskId, observer);
      observer.start();
    });
    return () => {
      // Individual observers survive state refreshes; the next effect removes
      // only tasks that became terminal or untracked.
    };
  }, [activeKey, loadExecution, pollIntervalMs]);

  const getExecution = useCallback(
    (taskId: string) => visibleState.records[taskId] ?? null,
    [visibleState.records]
  );

  const untrackExecution = useCallback((taskId: string) => {
    controllersRef.current.get(taskId)?.abort();
    controllersRef.current.delete(taskId);
    pendingLoadsRef.current.delete(taskId);
    observersRef.current.get(taskId)?.stop();
    observersRef.current.delete(taskId);
    eventCursorRef.current.delete(taskId);
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
