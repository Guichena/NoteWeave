import { afterEach, describe, expect, it, vi } from "vitest";
import { ExecutionObserver } from "./observer";

afterEach(() => {
  vi.useRealTimers();
});

describe("ExecutionObserver", () => {
  it("does not poll while the task SSE connection is healthy", async () => {
    vi.useFakeTimers();
    const refresh = vi.fn(async () => undefined);
    const stream = new ReadableStream<Uint8Array>({ start() {} });
    const observer = new ExecutionObserver({
      taskId: "task-1",
      pollIntervalMs: 2_500,
      refresh,
      fetcher: vi.fn(async () => new Response(stream, { status: 200 }))
    });

    observer.start();
    await vi.advanceTimersByTimeAsync(10_000);

    expect(refresh).not.toHaveBeenCalled();
    observer.stop();
  });

  it("arms periodic refresh only while task SSE is unavailable", async () => {
    vi.useFakeTimers();
    const refresh = vi.fn(async () => undefined);
    const observer = new ExecutionObserver({
      taskId: "task-2",
      pollIntervalMs: 2_500,
      refresh,
      fetcher: vi.fn(async () => { throw new Error("stream unavailable"); })
    });

    observer.start();
    await vi.advanceTimersByTimeAsync(2_500);

    expect(refresh).toHaveBeenCalledTimes(1);
    observer.stop();
  });
});
