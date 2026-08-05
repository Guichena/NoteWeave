import { apiClient, type ApiClient } from "../../shared/api";
import { parseRawSseEvents } from "../../shared/event-stream";
import {
  type ExecutionEvent,
  type ExecutionSnapshot,
  type ExecutionTask
} from "./model";

export class ExecutionsApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  getTask(taskId: string, init?: RequestInit) {
    return init
      ? this.client.get<ExecutionTask>(`/api/v2/tasks/${taskId}`, init)
      : this.client.get<ExecutionTask>(`/api/v2/tasks/${taskId}`);
  }

  async getEvents(taskId: string, init?: RequestInit, afterEventId = "") {
    const cursor = afterEventId
      ? `?afterEventId=${encodeURIComponent(afterEventId)}`
      : "";
    const path = `/api/v2/tasks/${taskId}/event-history${cursor}`;
    const events = init
      ? await this.client.get<TaskEventHistoryItem[]>(path, init)
      : await this.client.get<TaskEventHistoryItem[]>(path);
    return events.map(mapHistoryEvent);
  }

  async load(taskId: string, init?: RequestInit, afterEventId = ""): Promise<ExecutionSnapshot> {
    let task = await this.getTask(taskId, init);
    const events = await this.getEvents(taskId, init, afterEventId);
    const terminalRefetched = isExecutionTerminal(task.task_status)
      || events.some((event) => isTerminalEvent(event.event));
    if (terminalRefetched) {
      task = await this.getTask(taskId, init);
    }
    return { task, events, terminalRefetched };
  }
}

type TaskEventHistoryItem = {
  event_id: string;
  event_type: string;
  message: string;
  payload_json: string;
  created_at: string;
};

function mapHistoryEvent(item: TaskEventHistoryItem): ExecutionEvent {
  const payload = parseEventData(item.payload_json);
  const event = taskEventName(item.event_type);
  return {
    id: item.event_id,
    event,
    data: JSON.stringify({
      message: item.message,
      payload,
      event_type: item.event_type,
      created_at: item.created_at
    }),
    message: item.message,
    payload,
    eventType: item.event_type,
    createdAt: item.created_at
  };
}

function taskEventName(eventType: string) {
  switch (eventType) {
    case "TASK_CREATED": return "task.status";
    case "TASK_COMPLETED": return "task.completed";
    case "TASK_FAILED": return "task.failed";
    case "TASK_HEARTBEAT": return "task.heartbeat";
    default: return "task.progress";
  }
}

export function parseExecutionEvents(stream: string): ExecutionEvent[] {
  return parseRawSseEvents(stream).map((raw, index) => {
    const parsed = parseEventData(raw.data);
    return {
      id: raw.id || legacyEventId(raw.event, raw.data, index),
      event: raw.event,
      data: raw.data,
      message: text(parsed.message),
      payload: record(parsed.payload),
      eventType: text(parsed.event_type),
      createdAt: text(parsed.created_at)
    };
  });
}

export function isExecutionTerminal(status: string | undefined) {
  return status === "COMPLETED" || status === "FAILED" || status === "CANCELLED";
}

function isTerminalEvent(event: string) {
  return event === "task.completed" || event === "task.failed" || event === "task.cancelled";
}

function parseEventData(data: string) {
  try {
    return record(JSON.parse(data));
  } catch {
    return {};
  }
}

function legacyEventId(event: string, data: string, index: number) {
  let hash = 2166136261;
  for (const char of `${event}\u0000${data}`) {
    hash ^= char.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return `legacy-${index}-${(hash >>> 0).toString(16)}`;
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function text(value: unknown) {
  return value == null ? "" : String(value);
}

export const executionsApi = new ExecutionsApi();
