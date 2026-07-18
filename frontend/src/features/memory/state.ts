import {
  type MemoryReviewDecisionResult,
  type MemoryReviewItem,
  type MemoryVersion
} from "./model";

export type MemoryReviewState = {
  workspaceId: string;
  queue: MemoryReviewItem[];
  selectedReviewId: string;
  versions: MemoryVersion[];
  selectedVersionId: string;
  queueLoading: boolean;
  versionsLoading: boolean;
  mutating: boolean;
  error: string;
  lastDecision: MemoryReviewDecisionResult | null;
};

export type MemoryReviewAction =
  | { type: "workspace"; workspaceId: string }
  | { type: "queue-loading"; loading: boolean }
  | { type: "versions-loading"; loading: boolean }
  | { type: "mutating"; mutating: boolean }
  | { type: "queue"; queue: MemoryReviewItem[] }
  | { type: "select"; reviewId: string }
  | { type: "versions"; versions: MemoryVersion[]; preferredVersionId?: string }
  | { type: "select-version"; versionId: string }
  | { type: "error"; error: string }
  | { type: "decision"; result: MemoryReviewDecisionResult };

export function createMemoryReviewState(workspaceId = ""): MemoryReviewState {
  return {
    workspaceId,
    queue: [],
    selectedReviewId: "",
    versions: [],
    selectedVersionId: "",
    queueLoading: false,
    versionsLoading: false,
    mutating: false,
    error: "",
    lastDecision: null
  };
}

export function reduceMemoryReviewState(
  state: MemoryReviewState,
  action: MemoryReviewAction
): MemoryReviewState {
  switch (action.type) {
    case "workspace":
      return action.workspaceId === state.workspaceId
        ? state
        : createMemoryReviewState(action.workspaceId);
    case "queue-loading":
      return { ...state, queueLoading: action.loading };
    case "versions-loading":
      return { ...state, versionsLoading: action.loading };
    case "mutating":
      return { ...state, mutating: action.mutating };
    case "queue": {
      const selectedStillExists = action.queue.some(
        (item) => item.review_id === state.selectedReviewId
      );
      return {
        ...state,
        queue: action.queue,
        selectedReviewId: selectedStillExists ? state.selectedReviewId : "",
        versions: selectedStillExists ? state.versions : [],
        selectedVersionId: selectedStillExists ? state.selectedVersionId : "",
        queueLoading: false,
        error: ""
      };
    }
    case "select":
      return {
        ...state,
        selectedReviewId: action.reviewId,
        versions: [],
        selectedVersionId: "",
        error: ""
      };
    case "versions": {
      const selectedVersionId = selectVersionId(
        action.versions,
        action.preferredVersionId
      );
      return {
        ...state,
        versions: action.versions,
        selectedVersionId,
        versionsLoading: false,
        error: ""
      };
    }
    case "select-version":
      return action.versionId === state.selectedVersionId
        ? state
        : { ...state, selectedVersionId: action.versionId };
    case "error":
      return {
        ...state,
        queueLoading: false,
        versionsLoading: false,
        mutating: false,
        error: action.error
      };
    case "decision":
      return { ...state, mutating: false, lastDecision: action.result, error: "" };
  }
}

export function selectVersionId(versions: MemoryVersion[], preferredVersionId?: string) {
  if (preferredVersionId && versions.some(
    (version) => version.memory_version_id === preferredVersionId
  )) {
    return preferredVersionId;
  }
  return versions.reduce<MemoryVersion | null>((latest, version) => (
    !latest || version.version_no > latest.version_no ? version : latest
  ), null)?.memory_version_id ?? "";
}

type RequestLease = {
  signal: AbortSignal;
  isCurrent: () => boolean;
  complete: () => void;
};

export class LatestMemoryRequestGate {
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
    this.controllers.get(channel)?.abort();
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
