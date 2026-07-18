import { describe, expect, it } from "vitest";
import { AnswerRunStore, createAnswerRunState, reduceAnswerRunState } from "./store";

describe("AnswerRunStore", () => {
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
});

describe("reduceAnswerRunState", () => {
  it("supports canonical and compatibility stream event names", () => {
    let state = createAnswerRunState("run");
    state = reduceAnswerRunState(state, "chat.delta", "answer", 1);
    state = reduceAnswerRunState(state, "chat.citation", "citation", 2);
    state = reduceAnswerRunState(state, "chat.completed", "", 3);

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
