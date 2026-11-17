import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AnswerRunStore,
  AnswerRunWaitTimeoutError,
  createAnswerRunState,
  reduceAnswerRunState
} from "./store";
import { updateAssistantMessageByRun, type Message } from "./messageTypes";

afterEach(() => {
  vi.useRealTimers();
});

describe("AnswerRunStore", () => {
  it("preserves restored citations when an initial snapshot omits them and accepts late citation metadata", () => {
    const store = new AnswerRunStore();
    const restored: Message[] = [{ role: "assistant", answerRunId: "run", content: "结论 [1]", citations: ["资料A | 证据"] }];
    const snapshot = store.applySnapshot(JSON.stringify({ answer_runs: [{ id: "run", status: "COMPLETED", content: "结论 [1]" }] }))[0];
    expect(updateAssistantMessageByRun(restored, "run", snapshot)[0].citations).toEqual(["资料A | 证据"]);
    const update = store.applyConversationEvent(event("1", "citation.upsert", 1, "资料A | 证据"));
    expect(update[0].citations).toEqual(["资料A | 证据"]);
    expect(store.applyConversationEvent(event("2", "answer.delta", 2, "晚到正文"))).toEqual([]);
    expect(store.get("run")?.content).toBe("结论 [1]");
  });

  it("clears body and citations on redaction and cannot restore them from stale snapshots or events", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("1", "answer.delta", 1, "旧正文"));
    store.applyConversationEvent(event("2", "citation.upsert", 2, "旧证据"));
    const redacted = store.reconcileRun({ id: "run", status: "COMPLETED", content: "", revision_status: "REDACTED" });
    expect(redacted).toMatchObject({ content: "", citations: [], redacted: true });
    expect(updateAssistantMessageByRun([{ role: "assistant", answerRunId: "run", content: "旧正文", citations: ["旧证据"] }], "run", redacted)[0])
      .toMatchObject({ content: "", citations: [] });
    store.reconcileRun({ id: "run", status: "COMPLETED", content: "旧快照正文" });
    store.applyConversationEvent(event("3", "citation.upsert", 3, "旧证据"));
    expect(store.get("run")).toMatchObject({ content: "", citations: [] });
  });

  it("distinguishes explicit empty citations from a missing citation field", () => {
    const store = new AnswerRunStore();
    const state = store.reconcileRun({ id: "run", status: "COMPLETED", content: "无引用", citations: [] });
    expect(updateAssistantMessageByRun([{ role: "assistant", answerRunId: "run", content: "正文", citations: ["旧证据"] }], "run", state)[0].citations).toEqual([]);
  });
  it("deduplicates conversation event ids and rejects out-of-order run sequences", () => {
    const store = new AnswerRunStore();

    expect(store.applyConversationEvent(event("10", "answer.delta", 2, "B"))[0].content).toBe("B");
    expect(store.applyConversationEvent(event("10", "answer.delta", 2, "duplicate"))).toEqual([]);
    expect(store.applyConversationEvent(event("11", "answer.delta", 1, "older"))).toEqual([]);
    expect(store.get("run")?.content).toBe("B");
  });

  it("deduplicates citations and ignores late mutations after terminal", () => {
    const store = new AnswerRunStore();

    store.applyConversationEvent(event("1", "citation.upsert", 1, "citation"));
    store.applyConversationEvent(event("2", "citation.upsert", 2, "citation"));
    store.applyConversationEvent(event("3", "answer.completed", 3, ""));
    store.applyConversationEvent(event("4", "answer.delta", 4, "late"));
    store.applyConversationEvent(event("5", "answer.failed", 5, "conflicting terminal"));

    expect(store.get("run")).toMatchObject({
      citations: ["citation"],
      content: "",
      status: "COMPLETED",
      lastRunSequence: 3,
      error: ""
    });
  });

  it("uses the database snapshot as reconnect truth without lowering run cursor", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("7", "answer.delta", 7, "partial"));

    const updates = store.applyConversationEvent({
      id: "",
      event: "conversation.snapshot",
      data: JSON.stringify({
        conversation_id: "conversation",
        answer_runs: [{
          id: "run",
          status: "COMPLETED",
          content: "canonical",
          error_message: ""
        }]
      })
    });

    expect(updates).toHaveLength(1);
    expect(store.get("run")).toMatchObject({
      content: "canonical",
      status: "COMPLETED",
      lastRunSequence: 7
    });
  });

  it("reconciles terminal query truth while preserving streamed citations", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("1", "citation.upsert", 1, "citation"));
    store.applyConversationEvent(event("2", "answer.completed", 2, ""));

    const state = store.reconcileRun({
      id: "run",
      status: "COMPLETED",
      content: "canonical answer",
      error_message: ""
    });

    expect(state).toMatchObject({
      content: "canonical answer",
      citations: ["citation"],
      status: "COMPLETED",
      lastRunSequence: 2
    });
  });

  it("preserves streamed content when a generating snapshot has empty content", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("1", "answer.delta", 1, "streamed answer"));

    const state = store.reconcileRun({
      id: "run",
      status: "GENERATING",
      content: "",
      error_message: ""
    });

    expect(state.content).toBe("streamed answer");
    expect(state.status).toBe("GENERATING");
  });

  it("preserves streamed content when a completed snapshot is temporarily empty", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("1", "answer.delta", 1, "streamed answer"));

    const state = store.reconcileRun({
      id: "run",
      status: "COMPLETED",
      content: "",
      error_message: ""
    });

    expect(state.content).toBe("streamed answer");
    expect(state.status).toBe("COMPLETED");
  });

  it("settles waiters on terminal and rejects them when conversation changes", async () => {
    const completedStore = new AnswerRunStore();
    const completed = completedStore.waitFor("run");
    completedStore.applyConversationEvent(event("1", "answer.completed", 1, ""));
    await expect(completed).resolves.toBeUndefined();

    const failedStore = new AnswerRunStore();
    const failed = failedStore.waitFor("run");
    failedStore.applyConversationEvent(event("1", "answer.failed", 1, "model failed"));
    await expect(failed).rejects.toThrow("model failed");

    const clearedStore = new AnswerRunStore();
    const cleared = clearedStore.waitFor("run");
    clearedStore.clear("conversation changed");
    await expect(cleared).rejects.toThrow("conversation changed");
  });

  it("times out a missing terminal event so callers can reconcile from the API", async () => {
    vi.useFakeTimers();
    const store = new AnswerRunStore();
    const waiting = store.waitFor("run", 1_000);
    const assertion = expect(waiting).rejects.toBeInstanceOf(AnswerRunWaitTimeoutError);

    await vi.advanceTimersByTimeAsync(1_000);

    await assertion;
  });
});

describe("reduceAnswerRunState", () => {
  it("supports the canonical answer stream event names", () => {
    let state = createAnswerRunState("run");
    state = reduceAnswerRunState(state, "answer.delta", "answer", 1);
    state = reduceAnswerRunState(state, "citation.upsert", "citation", 2);
    state = reduceAnswerRunState(state, "answer.completed", "", 3);

    expect(state).toMatchObject({
      content: "answer",
      citations: ["citation"],
      status: "COMPLETED"
    });
  });
});

function event(id: string, type: string, runSequence: number, data: string) {
  return {
    id,
    event: type,
    data: JSON.stringify({
      run_id: "run",
      run_sequence: runSequence,
      data
    })
  };
}
