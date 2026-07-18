import { useCallback, useEffect, useReducer, useRef } from "react";
import { memoryApi, type MemoryApi } from "./api";
import {
  type AppendMemoryVersionInput,
  type MemoryReviewDecision,
  type MemoryReviewItem,
  type MemoryReviewKind
} from "./model";
import {
  createMemoryReviewState,
  LatestMemoryRequestGate,
  reduceMemoryReviewState
} from "./state";

export function useMemoryReview(
  workspaceId: string,
  kind: MemoryReviewKind,
  api: MemoryApi = memoryApi
) {
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
      const queue = await api.listReviews(workspaceId, kind, 50, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "queue", queue });
      }
      return queue;
    } catch (error) {
      if (lease.isCurrent() && !isAbortError(error)) {
        dispatch({ type: "error", error: errorMessage(error) });
      }
      return [];
    } finally {
      lease.complete();
    }
  }, [api, gate, kind, workspaceId]);

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
    (item) => item.review_id === visibleState.selectedReviewId
  ) ?? null;
  const selectedVersion = visibleState.versions.find(
    (version) => version.memory_version_id === visibleState.selectedVersionId
  ) ?? null;

  const selectReview = useCallback(async (item: MemoryReviewItem) => {
    dispatch({ type: "select", reviewId: item.review_id });
    if (item.review_kind !== "OBJECT") {
      gate.cancel("versions");
      return;
    }
    const lease = gate.begin("versions", workspaceId);
    dispatch({ type: "versions-loading", loading: true });
    try {
      const versions = await api.listVersions(workspaceId, item.review_id, {
        signal: lease.signal
      });
      if (lease.isCurrent()) {
        dispatch({
          type: "versions",
          versions,
          preferredVersionId: item.latest_version_id ?? undefined
        });
      }
    } catch (error) {
      if (lease.isCurrent() && !isAbortError(error)) {
        dispatch({ type: "error", error: errorMessage(error) });
      }
    } finally {
      lease.complete();
    }
  }, [api, gate, workspaceId]);

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
        item.review_kind,
        item.review_id,
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

  const appendVersion = useCallback(async (
    memoryObjectId: string,
    input: AppendMemoryVersionInput
  ) => {
    const lease = gate.begin("mutation", workspaceId);
    dispatch({ type: "mutating", mutating: true });
    try {
      const version = await api.appendVersion(
        workspaceId,
        memoryObjectId,
        input,
        { signal: lease.signal }
      );
      if (lease.isCurrent()) {
        const versions = await api.listVersions(workspaceId, memoryObjectId, {
          signal: lease.signal
        });
        if (lease.isCurrent()) {
          dispatch({
            type: "versions",
            versions,
            preferredVersionId: version.memory_version_id
          });
          await refreshQueue();
        }
      }
      return version;
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

  const selectVersion = useCallback((versionId: string) => {
    dispatch({ type: "select-version", versionId });
  }, []);

  return {
    ...visibleState,
    selectedItem,
    selectedVersion,
    refreshQueue,
    selectReview,
    decide,
    appendVersion,
    selectVersion
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
