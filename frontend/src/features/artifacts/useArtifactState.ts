import { useCallback, useEffect, useReducer, useRef } from "react";
import { resolveLatestArtifactVersionRequest } from "./artifactHistorySelection";
import { artifactsApi, type ArtifactsApi } from "./api";
import { type ArtifactVersionDetail } from "./model";
import { ApiError } from "../../shared/api/error";
import {
  type ArtifactVersionLoadError,
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
    dispatch({ type: "jobs-loading" });
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
      // NR26：查询失败不清空最后成功数据——错误与数据分离；
      // 轮询不依赖被清空的列表（有历史任务即继续轮询），后台完成后可自动恢复
      if (lease.isCurrent() && !isAbortError(error)) {
        dispatch({ type: "jobs-failed", error: error instanceof Error ? error.message : "产物列表加载失败" });
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
        dispatch({ type: "history-failed", error: artifactVersionLoadError(selection, error) });
      }
      throw error;
    } finally {
      lease.complete();
    }
  }, [api, gate, visibleState.latestArtifactVersion, workspaceId]);

  /** FE-03：用失败时记下的版本身份重试，不用调用方再拼一次选择。 */
  const retryVersion = useCallback(async () => {
    const failure = visibleState.versionError;
    if (!failure) {
      return null;
    }
    return selectHistoryVersion({
      key: failure.key,
      artifactJobId: failure.artifactJobId,
      versionNo: failure.versionNo
    }, false);
  }, [selectHistoryVersion, visibleState.versionError]);

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
    retryVersion,
    recordSavedSource,
    recordWriteback,
    applyRollback
  };
}

/**
 * FE-03：把版本加载失败分成「重试有意义」和「重试没意义」两类。
 * 404 版本不存在、409 状态冲突、403 无权限都不该无限重试；网络中断与 5xx 才值得重试。
 */
export function artifactVersionLoadError(
  selection: ArtifactHistorySelection,
  error: unknown
): ArtifactVersionLoadError {
  const status = error instanceof ApiError ? error.status : 0;
  const retryable = status === 0 || status === 408 || status === 429 || status >= 500;
  return {
    key: selection.key,
    artifactJobId: selection.artifactJobId,
    versionNo: selection.versionNo,
    status,
    retryable,
    message: versionLoadErrorMessage(status)
  };
}

function versionLoadErrorMessage(status: number) {
  if (status === 404) {
    return "该版本已不存在，可能已被回滚或删除";
  }
  if (status === 409) {
    return "版本状态已变化，请刷新后重试";
  }
  if (status === 403) {
    return "没有权限查看该版本";
  }
  return "版本加载失败";
}

function isAbortError(error: unknown) {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}
