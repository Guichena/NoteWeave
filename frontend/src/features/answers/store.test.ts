import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AnswerRunStore,
  AnswerRunWaitTimeoutError,
  createAnswerRunState,
  reduceAnswerRunState
} from "./store";

afterEach(() => {
  vi.useRealTimers();
});

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

describe("引用绑定协议事件", () => {
  const readyData = JSON.stringify({
    protocol_version: "citation-binding-v1",
    allowed_evidence_ids: ["knowledge-version:k1"],
    citation_bindings: [{ number: 1, evidence_id: "knowledge-version:k1", citation_ids: ["citation-1"] }]
  });

  it("evidence.ready 在正文增量之前建立编号绑定", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("1", "evidence.ready", 1, readyData));
    store.applyConversationEvent(event("2", "answer.delta", 2, "按名次融合 [1]"));

    const state = store.get("run");
    expect(state?.content).toBe("按名次融合 [1]");
    expect(state?.citationProtocol?.protocolVersion).toBe("citation-binding-v1");
    expect(state?.citationProtocol?.citationBindings).toEqual([
      { number: 1, evidenceId: "knowledge-version:k1", citationIds: ["citation-1"] }
    ]);
  });

  it("冲突协议不能覆盖原绑定，转为 INVALID", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("1", "evidence.ready", 1, readyData));
    store.applyConversationEvent(event("2", "evidence.ready", 2, JSON.stringify({
      protocol_version: "citation-binding-v1",
      allowed_evidence_ids: [],
      citation_bindings: []
    })));

    expect(store.get("run")?.citationProtocol).toMatchObject({ state: "INVALID", diagnostic: "CONFLICTING_FROZEN_PROTOCOL" });
  });

  it("无协议事件保持 UNKNOWN，不把新运行当历史", () => {
    const state = reduceAnswerRunState(createAnswerRunState("run"), "answer.delta", "旧回答 [1]", 1);
    expect(state.citationProtocol).toMatchObject({ state: "UNKNOWN" });
  });

  it("无法解析的协议正文不影响正文累计", () => {
    const store = new AnswerRunStore();
    store.applyConversationEvent(event("1", "evidence.ready", 1, "not-json"));
    store.applyConversationEvent(event("2", "answer.delta", 2, "answer"));

    expect(store.get("run")?.content).toBe("answer");
    expect(store.get("run")?.citationProtocol).toMatchObject({ state: "INVALID" });
  });
});

describe("F1 快照和冲突反例", () => {
  const ready = { protocol_state: "BINDING_V1", protocol_version: "citation-binding-v1", allowed_evidence_ids: ["p:1"], citation_bindings: [{ number: 1, evidence_id: "p:1", citation_ids: [] }] };
  it("页面刷新通过快照恢复协议且冲突事件转 INVALID", () => {
    const store = new AnswerRunStore();
    store.applySnapshot(JSON.stringify({ answer_runs: [{ id: "run", status: "GENERATING", content: "答案[1]", citation_protocol: ready }] }));
    expect(store.get("run")?.citationProtocol).toMatchObject({ state: "BINDING_V1" });
    store.applyConversationEvent(event("2", "evidence.ready", 2, JSON.stringify({ ...ready, allowed_evidence_ids: ["p:2"], citation_bindings: [{ number: 1, evidence_id: "p:2", citation_ids: [] }] })));
    expect(store.get("run")?.citationProtocol).toMatchObject({ state: "INVALID" });
  });
});


describe("F1 运行级快照", () => {
  const protocol = { protocol_state: "BINDING_V1", protocol_version: "citation-binding-v1", allowed_evidence_ids: ["p:1"], citation_bindings: [{ number: 1, evidence_id: "p:1", citation_ids: [] }] };
  it("生成中快照只恢复绑定，不把草稿或水位冒充已恢复的正文", () => {
    let current = createAnswerRunState("run");
    current = reduceAnswerRunState(current, "run.snapshot", JSON.stringify({ id: "run", status: "GENERATING", content: "SECRET DRAFT", event_sequence: 5, citation_protocol: protocol }));
    expect(current.content).toBe("");
    expect(current.citationProtocol).toMatchObject({ state: "BINDING_V1" });
    expect(current.lastRunSequence).toBe(0);
    current = reduceAnswerRunState(current, "answer.delta", "可见正文[1]", 3);
    expect(current.content).toBe("可见正文[1]");
  });
  it("完成快照恢复正文与水位，重放增量不会重复拼接", () => {
    let current = createAnswerRunState("run");
    current = reduceAnswerRunState(current, "run.snapshot", JSON.stringify({ id: "run", status: "COMPLETED", content: "最终正文[1]", event_sequence: 5, citation_protocol: protocol }));
    current = reduceAnswerRunState(current, "answer.delta", "重复正文", 3);
    expect(current.content).toBe("最终正文[1]");
    expect(current.lastRunSequence).toBe(5);
  });
  it("其他运行的快照不能覆盖当前正文或绑定", () => {
    const current = reduceAnswerRunState(createAnswerRunState("run"), "run.snapshot", JSON.stringify({ id: "other", status: "COMPLETED", content: "其他正文", citation_protocol: protocol }));
    expect(current.content).toBe("");
    expect(current.citationProtocol).toMatchObject({ state: "INVALID" });
  });
});


it("同一事件ID和序号的冲突协议仍可诊断，不能被去重提前吞掉", () => {
  const store = new AnswerRunStore();
  const payload = (id: string) => JSON.stringify({ protocol_state: "BINDING_V1", protocol_version: "citation-binding-v1", allowed_evidence_ids: [id], citation_bindings: [{ number: 1, evidence_id: id, citation_ids: [] }] });
  store.applyConversationEvent(event("same", "evidence.ready", 1, payload("p:1")));
  store.applyConversationEvent(event("same", "evidence.ready", 1, payload("p:2")));
  expect(store.get("run")?.citationProtocol).toMatchObject({ state: "INVALID", diagnostic: "CONFLICTING_FROZEN_PROTOCOL" });
  expect(store.get("run")?.lastRunSequence).toBe(1);
});

describe("回答准备阶段事件", () => {
  it("阶段事件不占运行序号，按到达顺序更新进度，正文开始后不再覆盖", () => {
    const store = new AnswerRunStore();

    store.applyConversationEvent(event("s1", "answer.stage", 0, JSON.stringify({ stage: "understanding" })));
    store.applyConversationEvent(event("s2", "answer.stage", 0, JSON.stringify({ stage: "retrieved", evidence_count: 4 })));
    expect(store.get("run")).toMatchObject({ stage: "retrieved", evidenceCount: 4, lastRunSequence: 0 });

    store.applyConversationEvent(event("s3", "answer.stage", 0, JSON.stringify({ stage: "writing" })));
    expect(store.get("run")).toMatchObject({ stage: "writing", evidenceCount: 4 });

    store.applyConversationEvent(event("d1", "answer.delta", 1, "正文"));
    expect(store.applyConversationEvent(event("s4", "answer.stage", 0, JSON.stringify({ stage: "retrieving" })))).toEqual([]);
    expect(store.get("run")).toMatchObject({ content: "正文", stage: "writing", lastRunSequence: 1 });
  });

  it("格式不对的阶段事件被忽略", () => {
    const store = new AnswerRunStore();

    expect(store.applyConversationEvent(event("bad", "answer.stage", 0, "不是 JSON"))).toEqual([]);
    expect(store.applyConversationEvent(event("empty", "answer.stage", 0, JSON.stringify({})))).toEqual([]);
  });
});
