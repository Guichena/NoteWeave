import { useCallback, useEffect, useReducer, useRef } from "react";
import { memoryApi, type MemoryApi } from "./api";
import {
  type MemoryReviewDecision,
  type MemoryReviewItem
} from "./model";
import {
  createMemoryReviewState,
  LatestMemoryRequestGate,
  reduceMemoryReviewState
} from "./state";

export function useMemoryReview(workspaceId: string, api: MemoryApi = memoryApi) {
  const [state, dispatch] = useReducer(
    reduceMemoryReviewState,
    workspaceId,
    createMemoryReviewState
  );
  const gateRef = useRef(new LatestMemoryRequestGate());
  const gate = gateRef.current;

  const refreshQueue = useCallback(async () => {
    if (!workspaceId) {
      dispatch({ type: "queue", queue: [] });
      return [];
    }
    const lease = gate.begin("queue", workspaceId);
    dispatch({ type: "queue-loading", loading: true });
    try {
      const queue = await api.listReviews(workspaceId, { signal: lease.signal });
      if (lease.isCurrent()) dispatch({ type: "queue", queue });
      return queue;
    } catch (error) {
      if (lease.isCurrent() && !isAbortError(error)) {
        dispatch({ type: "error", error: errorMessage(error) });
      }
      return [];
    } finally {
      lease.complete();
    }
  }, [api, gate, workspaceId]);

  useEffect(() => {
    gate.setWorkspace(workspaceId);
    dispatch({ type: "workspace", workspaceId });
    dispatch({ type: "queue", queue: [] });
    void refreshQueue();
  }, [gate, refreshQueue, workspaceId]);

  useEffect(() => () => gate.cancelAll(), [gate]);

  const visibleState = state.workspaceId === workspaceId
    ? state
    : createMemoryReviewState(workspaceId);
  const selectedItem = visibleState.queue.find(
    (item) => item.revision_id === visibleState.selectedRevisionId
  ) ?? null;

  const selectReview = useCallback((item: MemoryReviewItem) => {
    dispatch({ type: "select", revisionId: item.revision_id });
  }, []);

  const decide = useCallback(async (
    item: MemoryReviewItem,
    decision: MemoryReviewDecision,
    reason: string
  ) => {
    const lease = gate.begin("mutation", workspaceId);
    dispatch({ type: "mutating", mutating: true });
    try {
      const result = await api.decideReview(
        workspaceId,
        item.revision_id,
        { decision, reason: reason.trim() || undefined },
        { signal: lease.signal }
      );
      if (lease.isCurrent()) {
        dispatch({ type: "decision", result });
        await refreshQueue();
      }
      return result;
    } catch (error) {
      if (lease.isCurrent() && !isAbortError(error)) {
        dispatch({ type: "error", error: errorMessage(error) });
      }
      throw error;
    } finally {
      lease.complete();
      dispatch({ type: "mutating", mutating: false });
    }
  }, [api, gate, refreshQueue, workspaceId]);

  return {
    ...visibleState,
    selectedItem,
    refreshQueue,
    selectReview,
    decide
  };
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Memory 请求失败";
}

function isAbortError(error: unknown) {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}
