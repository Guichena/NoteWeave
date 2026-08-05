// @vitest-environment jsdom

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useExecutionRegistry } from "./useExecutionRegistry";
import type { ExecutionSnapshot } from "./model";

describe("useExecutionRegistry", () => {
  it("shares an in-flight task load between the initiating action and observer refresh", async () => {
    let resolveLoad!: (snapshot: ExecutionSnapshot) => void;
    const load = vi.fn(() => new Promise<ExecutionSnapshot>((resolve) => {
      resolveLoad = resolve;
    }));
    const { result } = renderHook(() => useExecutionRegistry(
      "workspace-1",
      2_500,
      { load } as never
    ));

    let first!: Promise<ExecutionSnapshot>;
    let second!: Promise<ExecutionSnapshot>;
    act(() => {
      first = result.current.loadExecution("task-1");
      second = result.current.loadExecution("task-1");
    });

    expect(load).toHaveBeenCalledTimes(1);
    expect(second).toBe(first);

    const snapshot = terminalSnapshot();
    await act(async () => {
      resolveLoad(snapshot);
      await Promise.all([first, second]);
    });

    expect(result.current.getExecution("task-1")?.task.task_status).toBe("COMPLETED");
  });
});

function terminalSnapshot(): ExecutionSnapshot {
  return {
    task: {
      task_id: "task-1",
      task_type: "SOURCE_PARSE",
      task_status: "COMPLETED",
      progress_phase: "SOURCE_READY",
      progress_message: "done",
      result_ref: "source-1",
      error_message: "",
      target_type: "SOURCE",
      target_id: "source-1",
      wait_context: null
    },
    events: [],
    terminalRefetched: true
  };
}
