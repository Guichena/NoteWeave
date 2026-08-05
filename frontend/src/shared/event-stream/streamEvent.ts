import { consumeSse } from "./consume";
import { type ExecutionEvent as StreamEvent } from "../../features/executions/model";
import { asRecord } from "../util/records";

export async function streamEvents(path: string, onEvent: (event: StreamEvent) => void) {
  await consumeSse(path, (event) => onEvent(toStreamEvent(event.event, event.data, event.id)));
}

function parseStreamEventData(data: string) {
  try {
    const parsed = JSON.parse(data);
    const record = asRecord(parsed);
    return {
      message: String(record.message ?? data),
      payload: asRecord(record.payload),
      eventType: String(record.event_type ?? ""),
      createdAt: String(record.created_at ?? ""),
    };
  } catch {
    return {
      message: data,
      payload: {},
      eventType: "",
      createdAt: "",
    };
  }
}

export function toStreamEvent(event: string, data: string, id = ""): StreamEvent {
  const parsed = parseStreamEventData(data);
  return {
    id: id || `${event}-${parsed.createdAt}-${data.length}`,
    event,
    data,
    message: parsed.message,
    payload: parsed.payload,
    eventType: parsed.eventType,
    createdAt: parsed.createdAt
  };
}