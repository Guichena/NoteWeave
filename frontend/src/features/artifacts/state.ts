import {
  type ArtifactJobSummary,
  type ArtifactVersionDetail
} from "./model";

export type ArtifactVersionLoadError = {
  /** 出错时正在加载的版本键，用于把错误绑到具体版本上 */
  key: string;
  artifactJobId: string;
  versionNo: number;
  message: string;
  /** 0 表示没拿到 HTTP 状态（网络中断等） */
  status: number;
  /** false 表示重试也没用：版本不存在、状态冲突、无权限 */
  retryable: boolean;
};

export type ArtifactServerState = {
  workspaceId: string;
  revision: number;
  jobsLoading: boolean;
  /** NR26：最近一次任务列表查询是否失败（失败时保留旧数据并继续轮询重试） */
  lastJobsFailed: boolean;
  /** FE-03：任务列表失败原因；失败时列表保留最后一次成功数据，不渲染空态 */
  jobsError: string | null;
  /** FE-03：版本加载失败原因；失败时保留已选版本，不渲染空态 */
  versionError: ArtifactVersionLoadError | null;
  artifactJobs: ArtifactJobSummary[];
  latestArtifactVersion: ArtifactVersionDetail | null;
  selectedArtifactHistoryVersion: ArtifactVersionDetail | null;
  selectedArtifactHistoryKey: string;
  artifactHistoryLoadingKey: string;
  artifactSavedSourceByVersionId: Record<string, string>;
  artifactWritebackByVersionId: Record<string, string[]>;
};

export type ArtifactStateAction =
  | { type: "workspace"; workspaceId: string }
  | { type: "jobs-loading" }
  | { type: "jobs-snapshot"; jobs: ArtifactJobSummary[]; latestVersion: ArtifactVersionDetail | null }
  | { type: "jobs-failed"; error: string }
  | { type: "history-loading"; key: string }
  | { type: "history-selected"; key: string; version: ArtifactVersionDetail | null }
  | { type: "history-failed"; error: ArtifactVersionLoadError }
  | { type: "saved-source"; key: string; sourceId: string }
  | { type: "writeback"; key: string; itemType: string }
  | { type: "rollback"; version: ArtifactVersionDetail }
  | { type: "clear" };

export function createArtifactServerState(workspaceId = ""): ArtifactServerState {
  return {
    workspaceId,
    revision: 0,
    jobsLoading: false,
    lastJobsFailed: false,
    jobsError: null,
    versionError: null,
    artifactJobs: [],
    latestArtifactVersion: null,
    selectedArtifactHistoryVersion: null,
    selectedArtifactHistoryKey: "",
    artifactHistoryLoadingKey: "",
    artifactSavedSourceByVersionId: {},
    artifactWritebackByVersionId: {}
  };
}

export function reduceArtifactServerState(
  state: ArtifactServerState,
  action: ArtifactStateAction
): ArtifactServerState {
  switch (action.type) {
    case "workspace":
      return action.workspaceId === state.workspaceId ? state : createArtifactServerState(action.workspaceId);
    case "jobs-loading":
      return {
        ...state,
        jobsLoading: true
      };
    case "jobs-snapshot":
      return {
        ...state,
        revision: state.revision + 1,
        jobsLoading: false,
        lastJobsFailed: false,
        jobsError: null,
        artifactJobs: action.jobs,
        latestArtifactVersion: action.latestVersion
      };
    case "jobs-failed":
      // NR26：失败保留最后成功数据（jobsLoading 复位，错误经调用方 status 呈现），
      // 标记失败让轮询继续重试，后台完成后自动恢复
      return {
        ...state,
        jobsLoading: false,
        lastJobsFailed: true,
        jobsError: action.error
      };
    case "history-loading":
      return {
        ...state,
        selectedArtifactHistoryKey: action.key,
        artifactHistoryLoadingKey: action.key,
        versionError: null
      };
    case "history-selected":
      return {
        ...state,
        revision: state.revision + 1,
        selectedArtifactHistoryKey: action.key,
        artifactHistoryLoadingKey: "",
        selectedArtifactHistoryVersion: action.version,
        versionError: null
      };
    case "history-failed":
      // FE-03：失败只记录错误，不清掉已选版本——否则界面会把「加载失败」显示成「没有版本」。
      return {
        ...state,
        revision: state.revision + 1,
        artifactHistoryLoadingKey: "",
        versionError: action.error
      };
    case "saved-source":
      return {
        ...state,
        artifactSavedSourceByVersionId: {
          ...state.artifactSavedSourceByVersionId,
          [action.key]: action.sourceId
        }
      };
    case "writeback": {
      const current = state.artifactWritebackByVersionId[action.key] ?? [];
      return {
        ...state,
        artifactWritebackByVersionId: {
          ...state.artifactWritebackByVersionId,
          [action.key]: Array.from(new Set([...current, action.itemType]))
        }
      };
    }
    case "rollback":
      return {
        ...state,
        revision: state.revision + 1,
        latestArtifactVersion: action.version,
        selectedArtifactHistoryVersion: action.version
      };
    case "clear":
      return createArtifactServerState(state.workspaceId);
  }
}

type RequestLease = {
  signal: AbortSignal;
  isCurrent: () => boolean;
  complete: () => void;
};

export class LatestArtifactRequestGate {
  private workspaceId = "";
  private readonly controllers = new Map<string, AbortController>();

  setWorkspace(workspaceId: string) {
    if (workspaceId === this.workspaceId) {
      return;
    }
    this.workspaceId = workspaceId;
    this.cancelAll();
  }

  begin(channel: string, workspaceId = this.workspaceId): RequestLease {
    this.cancel(channel);
    const controller = new AbortController();
    this.controllers.set(channel, controller);
    return {
      signal: controller.signal,
      isCurrent: () => (
        !controller.signal.aborted
        && workspaceId === this.workspaceId
        && this.controllers.get(channel) === controller
      ),
      complete: () => {
        if (this.controllers.get(channel) === controller) {
          this.controllers.delete(channel);
        }
      }
    };
  }

  cancel(channel: string) {
    this.controllers.get(channel)?.abort();
    this.controllers.delete(channel);
  }

  cancelAll() {
    this.controllers.forEach((controller) => controller.abort());
    this.controllers.clear();
  }
}
