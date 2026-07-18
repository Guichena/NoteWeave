import { describe, expect, it } from "vitest";
import { type ExecutionEvent, type ExecutionTask } from "./model";
import {
  createExecutionRegistryState,
  mergeExecutionEvents,
  reduceExecutionRegistry
} from "./store";

describe("Execution registry", () => {
  it("deduplicates replayed events by persistent event id", () => {
    const original = event("event-1", "old", "2026-07-15T00:00:00Z");
    const replacement = event("event-1", "new", "2026-07-15T00:00:00Z");
    const second = event("event-2", "second", "2026-07-15T00:01:00Z");

    expect(mergeExecutionEvents([original], [replacement, second]))
      .toEqual([replacement, second]);
  });

  it("normalizes multiple tasks by task id", () => {
    let state = createExecutionRegistryState("workspace");
    state = reduceExecutionRegistry(state, { type: "loading", taskId: "first" });
    state = reduceExecutionRegistry(state, {
      type: "loaded",
      taskId: "first",
      snapshot: { task: task("first", "RUNNING"), events: [event("a", "a", "")], terminalRefetched: false },
      loadedAt: 1
    });
    state = reduceExecutionRegistry(state, {
      type: "loaded",
      taskId: "second",
      snapshot: { task: task("second", "COMPLETED"), events: [event("b", "b", "")], terminalRefetched: true },
      loadedAt: 2
    });

    expect(state.trackedTaskIds).toEqual(["first", "second"]);
    expect(state.records.first.task.task_status).toBe("RUNNING");
    expect(state.records.second.terminalRefetched).toBe(true);
  });

  it("drops every task projection on workspace change", () => {
    const loaded = reduceExecutionRegistry(
      createExecutionRegistryState("first"),
      { type: "loading", taskId: "task" }
    );

    expect(reduceExecutionRegistry(loaded, { type: "workspace", workspaceId: "second" }))
      .toEqual(createExecutionRegistryState("second"));
  });
});

function task(taskId: string, status: string): ExecutionTask {
  return {
    task_id: taskId,
    task_type: "TEST",
    task_status: status,
    progress_phase: status,
    progress_message: status,
    result_ref: "",
    error_message: "",
    target_type: "TEST",
    target_id: taskId,
    wait_context: null
  };
}

function event(id: string, message: string, createdAt: string): ExecutionEvent {
  return {
    id,
    event: "task.progress",
    data: message,
    message,
    payload: {},
    eventType: "TASK_PROGRESS",
    createdAt
  };
}
