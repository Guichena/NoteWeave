import {
  type MemoryReviewDecisionResult,
  type MemoryReviewItem
} from "./model";

export type MemoryReviewState = {
  workspaceId: string;
  queue: MemoryReviewItem[];
  selectedRevisionId: string;
  queueLoading: boolean;
  mutating: boolean;
  error: string;
  lastDecision: MemoryReviewDecisionResult | null;
};

export type MemoryReviewAction =
  | { type: "workspace"; workspaceId: string }
  | { type: "queue-loading"; loading: boolean }
  | { type: "mutating"; mutating: boolean }
  | { type: "queue"; queue: MemoryReviewItem[] }
  | { type: "select"; revisionId: string }
  | { type: "error"; error: string }
  | { type: "decision"; result: MemoryReviewDecisionResult };

export function createMemoryReviewState(workspaceId = ""): MemoryReviewState {
  return {
    workspaceId,
    queue: [],
    selectedRevisionId: "",
    queueLoading: false,
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
    case "mutating":
      return { ...state, mutating: action.mutating };
    case "queue": {
      const selectedStillExists = action.queue.some(
        (item) => item.revision_id === state.selectedRevisionId
      );
      return {
        ...state,
        queue: action.queue,
        selectedRevisionId: selectedStillExists ? state.selectedRevisionId : "",
        queueLoading: false,
        error: ""
      };
    }
    case "select":
      return { ...state, selectedRevisionId: action.revisionId, error: "" };
    case "error":
      return { ...state, queueLoading: false, mutating: false, error: action.error };
    case "decision":
      return { ...state, mutating: false, lastDecision: action.result, error: "" };
  }
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
    if (workspaceId === this.workspaceId) return;
    this.workspaceId = workspaceId;
    this.cancelAll();
  }

  begin(channel: string, workspaceId = this.workspaceId): RequestLease {
    this.controllers.get(channel)?.abort();
    const controller = new AbortController();
    this.controllers.set(channel, controller);
    return {
      signal: controller.signal,
      isCurrent: () => !controller.signal.aborted
        && workspaceId === this.workspaceId
        && this.controllers.get(channel) === controller,
      complete: () => {
        if (this.controllers.get(channel) === controller) {
          this.controllers.delete(channel);
        }
      }
    };
  }

  cancelAll() {
    this.controllers.forEach((controller) => controller.abort());
    this.controllers.clear();
  }
}
