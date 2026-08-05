import { describe, expect, it, vi } from "vitest";
import { type ApiClient } from "../../shared/api";
import { ExecutionsApi, parseExecutionEvents } from "./api";
import { type ExecutionTask } from "./model";

describe("ExecutionsApi", () => {
  it("parses persistent task event ids and structured payloads", () => {
    const events = parseExecutionEvents([
      "id: event-1",
      "event: task.progress",
      'data: {"message":"reading","payload":{"phase":"READ"},"event_type":"TASK_PROGRESS","created_at":"2026-07-15T00:00:00Z"}',
      "",
      "id: event-2",
      "event: task.completed",
      'data: {"message":"done","payload":{},"event_type":"TASK_COMPLETED","created_at":"2026-07-15T00:01:00Z"}',
      ""
    ].join("\n"));

    expect(events).toHaveLength(2);
    expect(events[0]).toMatchObject({
      id: "event-1",
      event: "task.progress",
      message: "reading",
      payload: { phase: "READ" },
      eventType: "TASK_PROGRESS"
    });
    expect(events[1].id).toBe("event-2");
  });

  it("refetches the authoritative task snapshot after a terminal event", async () => {
    const get = vi.fn()
      .mockResolvedValueOnce(task("RUNNING", ""))
      .mockResolvedValueOnce([historyEvent("TASK_COMPLETED", "done")])
      .mockResolvedValueOnce(task("COMPLETED", "result"));
    const api = new ExecutionsApi({ get } as unknown as ApiClient);

    const snapshot = await api.load("task");

    expect(get).toHaveBeenCalledTimes(3);
    expect(get).toHaveBeenNthCalledWith(2, "/api/v2/tasks/task/event-history");
    expect(snapshot.task.task_status).toBe("COMPLETED");
    expect(snapshot.task.result_ref).toBe("result");
    expect(snapshot.terminalRefetched).toBe(true);
  });

  it("does not refetch a non-terminal task snapshot", async () => {
    const get = vi.fn()
      .mockResolvedValueOnce(task("RUNNING", ""))
      .mockResolvedValueOnce([historyEvent("TASK_PROGRESS", "working")]);
    const api = new ExecutionsApi({ get } as unknown as ApiClient);

    const snapshot = await api.load("task");

    expect(get).toHaveBeenCalledTimes(2);
    expect(snapshot.terminalRefetched).toBe(false);
  });

  it("requests only events after the last observed event", async () => {
    const get = vi.fn()
      .mockResolvedValueOnce(task("RUNNING", ""))
      .mockResolvedValueOnce([historyEvent("TASK_PROGRESS", "next")]);
    const api = new ExecutionsApi({ get } as unknown as ApiClient);

    await api.load("task", undefined, "event previous/1");

    expect(get).toHaveBeenNthCalledWith(
      2,
      "/api/v2/tasks/task/event-history?afterEventId=event%20previous%2F1"
    );
  });
});

function historyEvent(eventType: string, message: string) {
  return {
    event_id: `event-${eventType}`,
    event_type: eventType,
    message,
    payload_json: "{}",
    created_at: "2026-07-15T00:01:00Z"
  };
}

function task(status: string, resultRef: string): ExecutionTask {
  return {
    task_id: "task",
    task_type: "RESEARCH_RUN",
    task_status: status,
    progress_phase: status,
    progress_message: status,
    result_ref: resultRef,
    error_message: "",
    target_type: "RESEARCH_RUN",
    target_id: "run",
    wait_context: null
  };
}
