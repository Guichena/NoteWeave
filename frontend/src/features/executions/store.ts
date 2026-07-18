import {
  type ExecutionEvent,
  type ExecutionRecord,
  type ExecutionSnapshot
} from "./model";

export type ExecutionRegistryState = {
  workspaceId: string;
  records: Record<string, ExecutionRecord>;
  trackedTaskIds: string[];
};

export type ExecutionRegistryAction =
  | { type: "workspace"; workspaceId: string }
  | { type: "loading"; taskId: string }
  | { type: "loaded"; taskId: string; snapshot: ExecutionSnapshot; loadedAt: number }
  | { type: "error"; taskId: string; error: string }
  | { type: "untrack"; taskId: string };

export function createExecutionRegistryState(workspaceId = ""): ExecutionRegistryState {
  return { workspaceId, records: {}, trackedTaskIds: [] };
}

export function reduceExecutionRegistry(
  state: ExecutionRegistryState,
  action: ExecutionRegistryAction
): ExecutionRegistryState {
  switch (action.type) {
    case "workspace":
      return action.workspaceId === state.workspaceId
        ? state
        : createExecutionRegistryState(action.workspaceId);
    case "loading":
      return {
        ...state,
        trackedTaskIds: include(state.trackedTaskIds, action.taskId),
        records: {
          ...state.records,
          [action.taskId]: {
            ...(state.records[action.taskId] ?? emptyRecord(action.taskId)),
            loading: true,
            error: ""
          }
        }
      };
    case "loaded": {
      const current = state.records[action.taskId];
      return {
        ...state,
        trackedTaskIds: include(state.trackedTaskIds, action.taskId),
        records: {
          ...state.records,
          [action.taskId]: {
            task: action.snapshot.task,
            events: mergeExecutionEvents(current?.events ?? [], action.snapshot.events),
            terminalRefetched: action.snapshot.terminalRefetched,
            loading: false,
            error: "",
            lastLoadedAt: action.loadedAt
          }
        }
      };
    }
    case "error":
      return {
        ...state,
        records: {
          ...state.records,
          [action.taskId]: {
            ...(state.records[action.taskId] ?? emptyRecord(action.taskId)),
            loading: false,
            error: action.error
          }
        }
      };
    case "untrack": {
      const records = { ...state.records };
      delete records[action.taskId];
      return {
        ...state,
        records,
        trackedTaskIds: state.trackedTaskIds.filter((taskId) => taskId !== action.taskId)
      };
    }
  }
}

export function mergeExecutionEvents(current: ExecutionEvent[], incoming: ExecutionEvent[]) {
  const byId = new Map<string, ExecutionEvent>();
  [...current, ...incoming].forEach((event) => byId.set(event.id, event));
  return Array.from(byId.values()).sort((left, right) => {
    const timeOrder = left.createdAt.localeCompare(right.createdAt);
    return timeOrder !== 0 ? timeOrder : left.id.localeCompare(right.id);
  });
}

function include(values: string[], value: string) {
  return values.includes(value) ? values : [...values, value];
}

function emptyRecord(taskId: string): ExecutionRecord {
  return {
    task: {
      task_id: taskId,
      task_type: "",
      task_status: "PENDING",
      progress_phase: "",
      progress_message: "",
      result_ref: "",
      error_message: "",
      target_type: "",
      target_id: "",
      wait_context: null
    },
    events: [],
    terminalRefetched: false,
    loading: false,
    error: "",
    lastLoadedAt: 0
  };
}
