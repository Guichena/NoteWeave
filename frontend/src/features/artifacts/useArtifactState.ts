import { useCallback, useEffect, useReducer, useRef } from "react";
import { resolveLatestArtifactVersionRequest } from "./artifactHistorySelection";
import { artifactsApi, type ArtifactsApi } from "./api";
import { type ArtifactJobSummary, type ArtifactVersionDetail } from "./model";
import {
  createArtifactServerState,
  LatestArtifactRequestGate,
  reduceArtifactServerState
} from "./state";

/** 每次加载更多多取的条数，与后端默认页大小一致。 */
export const ARTIFACT_JOB_PAGE_SIZE = 50;
/** 后端单次请求的上限。 */
const ARTIFACT_JOB_MAX_LIMIT = 200;

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
  // 已展开的页数：轮询刷新时按同样的条数重新拉取，已加载的更早产物不会被收起
  const pagesRef = useRef(1);

  useEffect(() => {
    gate.setWorkspace(workspaceId);
    pagesRef.current = 1;
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
    dispatch({ type: "jobs-loading" });
    try {
      const { jobs, hasMore } = await fetchJobPages(api, workspaceId, pagesRef.current, lease.signal);
      const latestRequest = resolveLatestArtifactVersionRequest(jobs);
      const latestVersion = latestRequest
        ? await api.getVersion(workspaceId, latestRequest.artifactJobId, latestRequest.versionNo, {
          signal: lease.signal
        })
        : null;
      if (lease.isCurrent()) {
        dispatch({ type: "jobs-snapshot", jobs, latestVersion, hasMore });
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

  /** 多加载一页更早的产物。 */
  const loadMoreJobs = useCallback(async () => {
    pagesRef.current += 1;
    try {
      return await refreshJobs();
    } catch (error) {
      pagesRef.current = Math.max(1, pagesRef.current - 1);
      throw error;
    }
  }, [refreshJobs]);

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
    loadMoreJobs,
    selectHistoryVersion,
    recordSavedSource,
    recordWriteback,
    applyRollback
  };
}

/**
 * 按已展开的页数拉取产物列表。第一页沿用后端默认条数；
 * 更多页时按 200 条一批分段请求，最后一批不满说明已经到底。
 */
async function fetchJobPages(api: ArtifactsApi, workspaceId: string, pages: number, signal: AbortSignal) {
  const total = Math.max(1, pages) * ARTIFACT_JOB_PAGE_SIZE;
  if (pages <= 1) {
    const jobs = await api.listJobs(workspaceId, { signal });
    return { jobs, hasMore: jobs.length >= ARTIFACT_JOB_PAGE_SIZE };
  }
  const collected: ArtifactJobSummary[] = [];
  for (let offset = 0; offset < total; offset += ARTIFACT_JOB_MAX_LIMIT) {
    const limit = Math.min(ARTIFACT_JOB_MAX_LIMIT, total - offset);
    const page = await api.listJobs(workspaceId, { signal }, { limit, offset });
    collected.push(...page);
    if (page.length < limit) {
      return { jobs: dedupeJobs(collected), hasMore: false };
    }
  }
  return { jobs: dedupeJobs(collected), hasMore: true };
}

/** 分段请求之间列表可能有变动，同一产物只保留第一次出现的那条。 */
function dedupeJobs(jobs: ArtifactJobSummary[]) {
  const seen = new Set<string>();
  return jobs.filter((job) => {
    if (seen.has(job.artifact_job_id)) return false;
    seen.add(job.artifact_job_id);
    return true;
  });
}

function isAbortError(error: unknown) {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}
