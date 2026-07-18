import { type RawSseEvent } from "../../shared/event-stream";
import { type AnswerRunSnapshot, type AnswerRunState } from "./model";

type ConversationEventEnvelope = {
  runId: string;
  runSequence: number;
  data: string;
};

type Waiter = {
  resolve: () => void;
  reject: (error: Error) => void;
};

export class AnswerRunStore {
  private readonly states = new Map<string, AnswerRunState>();
  private readonly waiters = new Map<string, Waiter>();
  private readonly seenEventIds = new Set<string>();
  private readonly eventIdOrder: string[] = [];

  constructor(private readonly eventIdCapacity = 2_048) {}

  get(runId: string) {
    return this.states.get(runId);
  }

  applyConversationEvent(event: RawSseEvent): AnswerRunState[] {
    if (event.event === "conversation.snapshot") {
      return this.applySnapshot(event.data);
    }
    if (!this.admitEventId(event.id)) {
      return [];
    }
    const envelope = parseConversationEventEnvelope(event.data);
    if (!envelope.runId) {
      return [];
    }
    const current = this.states.get(envelope.runId) ?? createAnswerRunState(envelope.runId);
    if (envelope.runSequence <= current.lastRunSequence) {
      return [];
    }
    const next = reduceAnswerRunState(
      current,
      event.event,
      envelope.data,
      envelope.runSequence
    );
    if (next === current) {
      return [];
    }
    this.states.set(envelope.runId, next);
    this.settle(envelope.runId, next);
    return [next];
  }

  applySnapshot(data: string): AnswerRunState[] {
    const parsed = JSON.parse(data) as unknown;
    const snapshot = asRecord(parsed);
    const runs = Array.isArray(snapshot.answer_runs) ? snapshot.answer_runs : [];
    const updates: AnswerRunState[] = [];
    for (const value of runs) {
      const run = asRecord(value);
      const runId = text(run.run_id ?? run.id);
      if (!runId) {
        continue;
      }
      updates.push(this.reconcileRun({
        id: runId,
        content: text(run.content),
        status: text(run.status),
        error_code: text(run.error_code),
        error_message: text(run.error_message)
      }));
    }
    return updates;
  }

  reconcileRun(snapshot: AnswerRunSnapshot) {
    const current = this.states.get(snapshot.id) ?? createAnswerRunState(snapshot.id);
    const next: AnswerRunState = {
      ...current,
      content: snapshot.content ?? current.content,
      status: snapshot.status || current.status,
      error: snapshot.error_message || snapshot.error_code || ""
    };
    this.states.set(snapshot.id, next);
    this.settle(snapshot.id, next);
    return next;
  }

  waitFor(runId: string) {
    const current = this.states.get(runId);
    if (current?.status === "COMPLETED") {
      return Promise.resolve();
    }
    if (isFailedTerminal(current?.status)) {
      return Promise.reject(terminalError(current));
    }
    return new Promise<void>((resolve, reject) => {
      const previous = this.waiters.get(runId);
      previous?.reject(new Error("回答等待已被新的订阅替换"));
      this.waiters.set(runId, { resolve, reject });
    });
  }

  clear(reason = "会话已切换") {
    this.waiters.forEach((waiter) => waiter.reject(new Error(reason)));
    this.waiters.clear();
    this.states.clear();
    this.seenEventIds.clear();
    this.eventIdOrder.length = 0;
  }

  private admitEventId(eventId: string) {
    if (!eventId) {
      return true;
    }
    if (this.seenEventIds.has(eventId)) {
      return false;
    }
    this.seenEventIds.add(eventId);
    this.eventIdOrder.push(eventId);
    while (this.eventIdOrder.length > Math.max(32, this.eventIdCapacity)) {
      const oldest = this.eventIdOrder.shift();
      if (oldest) {
        this.seenEventIds.delete(oldest);
      }
    }
    return true;
  }

  private settle(runId: string, state: AnswerRunState) {
    const waiter = this.waiters.get(runId);
    if (!waiter) {
      return;
    }
    if (state.status === "COMPLETED") {
      this.waiters.delete(runId);
      waiter.resolve();
    } else if (isFailedTerminal(state.status)) {
      this.waiters.delete(runId);
      waiter.reject(terminalError(state));
    }
  }
}

export function createAnswerRunState(runId = ""): AnswerRunState {
  return {
    runId,
    content: "",
    citations: [],
    status: "GENERATING",
    error: "",
    lastRunSequence: 0
  };
}

export function reduceAnswerRunState(
  current: AnswerRunState,
  eventType: string,
  data: string,
  runSequence = current.lastRunSequence + 1
): AnswerRunState {
  if (isTerminal(current.status)) {
    return current;
  }
  const next = {
    ...current,
    citations: [...current.citations],
    lastRunSequence: Math.max(current.lastRunSequence, runSequence)
  };
  switch (eventType) {
    case "answer.delta":
    case "chat.delta":
      next.content += data;
      return next;
    case "answer.snapshot":
      next.content = data;
      return next;
    case "citation.upsert":
    case "chat.citation":
      if (!next.citations.includes(data)) {
        next.citations.push(data);
      }
      return next;
    case "answer.completed":
    case "chat.completed":
      next.status = "COMPLETED";
      return next;
    case "answer.failed":
    case "chat.failed":
      next.status = "FAILED";
      next.error = data || "回答FAILED";
      return next;
    case "answer.cancelled":
      next.status = "CANCELLED";
      next.error = data || "回答CANCELLED";
      return next;
    default:
      return next;
  }
}

export function parseConversationEventEnvelope(data: string): ConversationEventEnvelope {
  try {
    const envelope = asRecord(JSON.parse(data));
    return {
      runId: text(envelope.run_id),
      runSequence: finiteNumber(envelope.run_sequence),
      data: text(envelope.data)
    };
  } catch {
    return { runId: "", runSequence: 0, data: "" };
  }
}

function isTerminal(status: string | undefined) {
  return status === "COMPLETED" || isFailedTerminal(status);
}

function isFailedTerminal(status: string | undefined) {
  return status === "FAILED" || status === "CANCELLED";
}

function terminalError(state: AnswerRunState | undefined) {
  return new Error(state?.error || `回答${state?.status ?? "失败"}`);
}

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function text(value: unknown) {
  return value == null ? "" : String(value);
}

function finiteNumber(value: unknown) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}
