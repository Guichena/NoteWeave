import {
  type ArtifactJobSummary,
  type ArtifactVersionDetail
} from "./model";

export type ArtifactServerState = {
  workspaceId: string;
  revision: number;
  jobsLoading: boolean;
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
  | { type: "history-loading"; key: string }
  | { type: "history-selected"; key: string; version: ArtifactVersionDetail | null }
  | { type: "history-failed" }
  | { type: "saved-source"; key: string; sourceId: string }
  | { type: "writeback"; key: string; itemType: string }
  | { type: "rollback"; version: ArtifactVersionDetail }
  | { type: "clear" };

export function createArtifactServerState(workspaceId = ""): ArtifactServerState {
  return {
    workspaceId,
    revision: 0,
    jobsLoading: false,
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
        artifactJobs: action.jobs,
        latestArtifactVersion: action.latestVersion
      };
    case "history-loading":
      return {
        ...state,
        selectedArtifactHistoryKey: action.key,
        artifactHistoryLoadingKey: action.key
      };
    case "history-selected":
      return {
        ...state,
        revision: state.revision + 1,
        selectedArtifactHistoryKey: action.key,
        artifactHistoryLoadingKey: "",
        selectedArtifactHistoryVersion: action.version
      };
    case "history-failed":
      return {
        ...state,
        revision: state.revision + 1,
        selectedArtifactHistoryKey: "",
        artifactHistoryLoadingKey: "",
        selectedArtifactHistoryVersion: null
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
