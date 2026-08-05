import {
  buildWaitContextDetailLines,
  buildWaitContextSignalChips,
  type WaitContext
} from "../../runStatus";

function asWaitContext(value: unknown): WaitContext | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  return value as WaitContext;
}

export function buildTaskWaitPresentation<TEvent extends { event: string }>(
  task: { wait_context?: unknown } | null | undefined,
  events: TEvent[]
) {
  const waitContext = asWaitContext(task?.wait_context);
  return {
    waitSignals: buildWaitContextSignalChips(waitContext),
    waitDetails: buildWaitContextDetailLines(waitContext),
    progressEvent: ([...events].reverse().find((event) => event.event === "task.progress") ?? null) as TEvent | null
  };
}
