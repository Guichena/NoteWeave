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
      .mockResolvedValueOnce(task("COMPLETED", "result"));
    const text = vi.fn(async () => [
      "id: terminal",
      "event: task.completed",
      'data: {"message":"done","payload":{},"event_type":"TASK_COMPLETED","created_at":"2026-07-15T00:01:00Z"}',
      ""
    ].join("\n"));
    const api = new ExecutionsApi({ get, text } as unknown as ApiClient);

    const snapshot = await api.load("task");

    expect(get).toHaveBeenCalledTimes(2);
    expect(snapshot.task.task_status).toBe("COMPLETED");
    expect(snapshot.task.result_ref).toBe("result");
    expect(snapshot.terminalRefetched).toBe(true);
  });

  it("does not refetch a non-terminal task snapshot", async () => {
    const get = vi.fn(async () => task("RUNNING", ""));
    const text = vi.fn(async () => [
      "id: progress",
      "event: task.progress",
      'data: {"message":"working","payload":{},"event_type":"TASK_PROGRESS","created_at":"2026-07-15T00:00:00Z"}',
      ""
    ].join("\n"));
    const api = new ExecutionsApi({ get, text } as unknown as ApiClient);

    const snapshot = await api.load("task");

    expect(get).toHaveBeenCalledTimes(1);
    expect(snapshot.terminalRefetched).toBe(false);
  });
});

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
