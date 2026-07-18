import { useCallback, useEffect, useReducer, useRef } from "react";
import { resolveLatestArtifactVersionRequest } from "../../artifactHistorySelection";
import { artifactsApi, type ArtifactsApi } from "./api";
import { type ArtifactVersionDetail } from "./model";
import {
  createArtifactServerState,
  LatestArtifactRequestGate,
  reduceArtifactServerState
} from "./state";

export type ArtifactHistorySelection = {
  key: string;
  artifactJobId: string;
  versionNo: number;
};

export function useArtifactState(
  workspaceId: string,
  api: ArtifactsApi = artifactsApi
) {
  const [state, dispatch] = useReducer(reduceArtifactServerState, workspaceId, createArtifactServerState);
  const gateRef = useRef(new LatestArtifactRequestGate());
  const gate = gateRef.current;

  useEffect(() => {
    gate.setWorkspace(workspaceId);
    dispatch({ type: "workspace", workspaceId });
  }, [gate, workspaceId]);

  useEffect(() => () => gate.cancelAll(), [gate]);

  const visibleState = state.workspaceId === workspaceId ? state : createArtifactServerState(workspaceId);

  const clear = useCallback(() => {
    gate.cancelAll();
    dispatch({ type: "clear" });
  }, [gate]);

  const refreshJobs = useCallback(async () => {
    if (!workspaceId) {
      return [];
    }
    const lease = gate.begin("jobs", workspaceId);
    try {
      const jobs = await api.listJobs(workspaceId, { signal: lease.signal });
      const latestRequest = resolveLatestArtifactVersionRequest(jobs);
      const latestVersion = latestRequest
        ? await api.getVersion(workspaceId, latestRequest.artifactJobId, latestRequest.versionNo, {
          signal: lease.signal
        })
        : null;
      if (lease.isCurrent()) {
        dispatch({ type: "jobs-snapshot", jobs, latestVersion });
      }
      return jobs;
    } catch (error) {
      if (lease.isCurrent() && !isAbortError(error)) {
        dispatch({ type: "jobs-snapshot", jobs: [], latestVersion: null });
      }
      throw error;
    } finally {
      lease.complete();
    }
  }, [api, gate, workspaceId]);

  const selectHistoryVersion = useCallback(async (
    selection: ArtifactHistorySelection,
    reuseLatest: boolean
  ) => {
    if (!workspaceId) {
      return null;
    }
    if (reuseLatest && visibleState.latestArtifactVersion) {
      dispatch({
        type: "history-selected",
        key: selection.key,
        version: visibleState.latestArtifactVersion
      });
      return visibleState.latestArtifactVersion;
    }
    const lease = gate.begin("history", workspaceId);
    dispatch({ type: "history-loading", key: selection.key });
    try {
      const version = await api.getVersion(
        workspaceId,
        selection.artifactJobId,
        selection.versionNo,
        { signal: lease.signal }
      );
      if (lease.isCurrent()) {
        dispatch({ type: "history-selected", key: selection.key, version });
      }
      return version;
    } catch (error) {
      if (lease.isCurrent() && !isAbortError(error)) {
        dispatch({ type: "history-failed" });
      }
      throw error;
    } finally {
      lease.complete();
    }
  }, [api, gate, visibleState.latestArtifactVersion, workspaceId]);

  const recordSavedSource = useCallback((key: string, sourceId: string) => {
    dispatch({ type: "saved-source", key, sourceId });
  }, []);

  const recordWriteback = useCallback((key: string, itemType: string) => {
    dispatch({ type: "writeback", key, itemType });
  }, []);

  const applyRollback = useCallback((version: ArtifactVersionDetail) => {
    dispatch({ type: "rollback", version });
  }, []);

  return {
    ...visibleState,
    clear,
    refreshJobs,
    selectHistoryVersion,
    recordSavedSource,
    recordWriteback,
    applyRollback
  };
}

function isAbortError(error: unknown) {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}
