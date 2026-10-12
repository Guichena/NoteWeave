// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MemoryApi } from "./api";
import type { MemoryGate, MemoryItem } from "./model";
import { MemoryReviewWorkbench } from "./MemoryReviewWorkbench";

afterEach(cleanup);

function gate(overrides: Partial<MemoryGate> = {}): MemoryGate {
  return {
    result: "READY", source_type: "USER_FEEDBACK",
    evidence_score: 0.98, evidence_threshold: 0.7,
    utility_score: 0.93, utility_threshold: 0.6,
    risk_score: 0.15, risk_threshold: 0.7,
    scope_status: "VALID", conflict_status: "NO_CONFLICT", policy_version: "memory-candidate-policy-v1",
    ...overrides
  };
}

function memory(id: string, overrides: Partial<MemoryItem> = {}): MemoryItem {
  return {
    memory_item_id: id, revision_id: `rev-${id}`, memory_scope: "WORKSPACE",
    item_status: "ACTIVE", review_status: "APPROVED", revision_status: "ACTIVE", version_no: 1,
    display_text: `记忆 ${id}`, candidate_type: "PREFERENCE", task_neighborhoods: ["COMMON"],
    provenance_type: "MEMORY_CANDIDATE", utility_score: 0.9, application_count: 0,
    conflict_status: "NO_CONFLICT", gate: gate(), last_confirmed_at: null,
    created_at: "2026-09-29T10:00:00Z",
    ...overrides
  };
}

const inferred = memory("inferred", {
  item_status: "EMPTY", review_status: "REVIEW_REQUIRED", revision_status: "PROPOSED",
  display_text: "用户可能偏好更长的解释", task_neighborhoods: ["CHAT_NOTE"],
  gate: gate({ result: "NEEDS_REVIEW", source_type: "MODEL_INFERENCE", evidence_score: 0.35, utility_score: 0.4, risk_score: 0.65 })
});
const conflicting = memory("conflict", {
  item_status: "EMPTY", review_status: "REVIEW_REQUIRED", revision_status: "PROPOSED", candidate_type: "NEGATIVE",
  display_text: "不要先给结论，按推导顺序展开", conflict_status: "CONFLICTING_ACTIVE_MEMORY",
  gate: gate({ result: "NEEDS_REVIEW", risk_score: 0.85, conflict_status: "CONFLICTING_ACTIVE_MEMORY" })
});
const recheck = memory("recheck", { review_status: "REVIEW_REQUIRED", utility_score: 0.38, display_text: "引用统一放在段落末尾" });
const active = memory("active", { display_text: "回答先给结论，再展开证据", application_count: 14 });
const personal = memory("personal", {
  memory_scope: "USER", provenance_type: "USER_FEEDBACK", gate: null,
  display_text: "称呼我为你", task_neighborhoods: ["CHAT_QA"]
});

function fakeApi(lists: MemoryItem[][]) {
  let call = 0;
  return {
    listItems: vi.fn(async () => lists[Math.min(call++, lists.length - 1)]),
    createSignal: vi.fn(async (_workspaceId: string, _input: unknown) => ({ signal_id: "signal-1" })),
    promoteSignals: vi.fn(async () => ({ candidates: [{ candidate_id: "new", review_status: "READY", conflict_status: "NO_CONFLICT" }] })),
    decideReview: vi.fn(async () => ({ revision_id: "", memory_item_id: "", status: "ACTIVE", revoked_memory_item_ids: [] })),
    previewChatControlPack: vi.fn(async (_workspaceId: string, _mode: string, _query: string) => ({ memory_references: [
      { memory_object_id: "active", memory_class: "ALWAYS_ON", selection_reason: "适用于全部回答的常驻约束" },
      { memory_object_id: "other", memory_class: "ON_DEMAND", relevance: 0.82, selection_reason: "与问题相关", display_text: "保留页码" }
    ] }))
  };
}

function renderPage(api: ReturnType<typeof fakeApi>) {
  return render(<MemoryReviewWorkbench workspaceId="workspace" api={api as unknown as MemoryApi} />);
}

describe("MemoryReviewWorkbench", () => {
  it("separates expression memory from source evidence and explains why candidates wait", async () => {
    renderPage(fakeApi([[inferred, conflicting, recheck, active, personal]]));

    expect(screen.getByRole("heading", { name: "记忆" })).toBeTruthy();
    const layers = screen.getByLabelText("记忆与资料的分工");
    expect(within(layers).getByText(/记忆 · 表达偏好/)).toBeTruthy();
    expect(within(layers).getByText(/资料 · 事实证据/)).toBeTruthy();

    const pending = await screen.findByRole("region", { name: "待确认" });
    const inferredCard = within(pending).getByText("用户可能偏好更长的解释").closest("article")!;
    expect(inferredCard.textContent).toContain("适用于精读 · 来自模型推断");
    const checks = within(inferredCard as HTMLElement).getByRole("list", { name: "门控检查" });
    expect(within(checks).getAllByRole("listitem").map((node) => node.className)).toEqual([
      "is-failed", "is-failed", "is-passed", "is-passed"
    ]);
    expect(inferredCard.textContent).toContain("来源可信度低于 0.70，模型推断需要你确认；预期效用低于 0.60");

    const conflictCard = within(pending).getByText("不要先给结论，按推导顺序展开").closest("article")!;
    expect(within(conflictCard as HTMLElement).getByRole("button", { name: "替换原有记忆" })).toBeTruthy();
    expect(within(conflictCard as HTMLElement).getByRole("button", { name: "保留原有记忆" })).toBeTruthy();

    const recheckCard = within(pending).getByText("引用统一放在段落末尾").closest("article")!;
    expect(recheckCard.textContent).toContain("效用降到 0.38");
    expect(within(recheckCard as HTMLElement).getByRole("button", { name: "继续使用" })).toBeTruthy();

    const activeSection = screen.getByRole("region", { name: "生效中" });
    expect(within(activeSection).getByText(/已用于 14 次回答/)).toBeTruthy();
    expect(within(activeSection).getByText(/适用于问答 · 来自你的反馈 · 尚未使用 · 仅自己可见/)).toBeTruthy();
  });

  it("adds a preference and reports the gate result", async () => {
    const created = memory("new", { display_text: "术语保留英文原文" });
    const api = fakeApi([[], [created]]);
    renderPage(api);

    fireEvent.change(screen.getByPlaceholderText(/回答先给结论/), { target: { value: " 术语保留英文原文 " } });
    fireEvent.change(screen.getByLabelText("适用于"), { target: { value: "ARTIFACT" } });
    fireEvent.click(screen.getByRole("button", { name: "添加" }));

    expect(await screen.findByText("已生效：通过了来源、效用、风险和冲突四项检查。")).toBeTruthy();
    expect(api.createSignal).toHaveBeenCalledWith("workspace", {
      signal_type: "PREFERENCE",
      source_type: "USER_FEEDBACK",
      signal_text: "术语保留英文原文",
      task_neighborhood: "ARTIFACT",
      style_constraints: ["术语保留英文原文"]
    });
    expect(api.promoteSignals).toHaveBeenCalledWith("workspace", ["signal-1"]);
    expect(within(screen.getByRole("region", { name: "生效中" })).getByText("术语保留英文原文")).toBeTruthy();
  });

  it("explains when a new avoidance rule conflicts with an active memory", async () => {
    const created = memory("new", {
      revision_status: "PROPOSED", item_status: "EMPTY", candidate_type: "NEGATIVE",
      conflict_status: "CONFLICTING_ACTIVE_MEMORY",
      gate: gate({ result: "NEEDS_REVIEW", risk_score: 0.85, conflict_status: "CONFLICTING_ACTIVE_MEMORY" })
    });
    const api = fakeApi([[active], [active, created]]);
    renderPage(api);

    fireEvent.click(screen.getByRole("radio", { name: "避免" }));
    fireEvent.change(screen.getByPlaceholderText(/不要使用营销式/), { target: { value: "不要先给结论" } });
    fireEvent.click(screen.getByRole("button", { name: "添加" }));

    expect(await screen.findByText(/需要确认：风险达到 0.70 以上；与一条生效中的记忆意思相反/)).toBeTruthy();
    expect(api.createSignal.mock.calls[0][1]).toMatchObject({ signal_type: "NEGATIVE", forbidden_patterns: ["不要先给结论"] });
  });

  it("confirms candidates and lets an active memory be stopped", async () => {
    const api = fakeApi([[inferred, active]]);
    renderPage(api);

    const pending = await screen.findByRole("region", { name: "待确认" });
    fireEvent.click(within(pending).getByRole("button", { name: "生效" }));
    await waitFor(() => expect(api.decideReview).toHaveBeenCalledWith("workspace", "rev-inferred", { decision: "ACCEPT" }));

    fireEvent.click(screen.getByRole("button", { name: "停用记忆：回答先给结论，再展开证据" }));
    expect(api.decideReview).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "确认停用" }));
    await waitFor(() => expect(api.decideReview).toHaveBeenLastCalledWith("workspace", "rev-active", { decision: "REVOKE" }));
  });

  it("shows an empty state", async () => {
    renderPage(fakeApi([[]]));

    expect(await screen.findByText(/还没有生效的记忆/)).toBeTruthy();
    expect(screen.queryByRole("region", { name: "待确认" })).toBeNull();
  });

  it("surfaces a failed decision", async () => {
    const api = fakeApi([[inferred]]);
    api.decideReview.mockRejectedValue(new Error("服务不可用"));
    renderPage(api);

    fireEvent.click(await screen.findByRole("button", { name: "生效" }));
    expect((await screen.findByRole("alert")).textContent).toBe("服务不可用");
  });

  it("待确认项说明冲突的记忆，冲突检查未执行时要求人工确认", async () => {
    const slotConflict = memory("slot", {
      item_status: "EMPTY", review_status: "REVIEW_REQUIRED", revision_status: "PROPOSED",
      display_text: "回答使用英文",
      conflict: { type: "SLOT_VALUE", conflicting_item_id: "active", conflicting_text: "回答使用中文" }
    });
    const unchecked = memory("unchecked", {
      item_status: "EMPTY", review_status: "REVIEW_REQUIRED", revision_status: "PROPOSED",
      display_text: "表格优先", conflict: { type: "CHECK_NOT_RUN" }
    });
    renderPage(fakeApi([[slotConflict, unchecked]]));

    const pending = await screen.findByRole("region", { name: "待确认" });
    const slotCard = within(pending).getByText("回答使用英文").closest("article") as HTMLElement;
    expect(within(slotCard).getByRole("note").textContent).toContain("与生效中的记忆冲突：同一设置项取值不同回答使用中文");
    expect(within(slotCard).getByRole("button", { name: "替换原有记忆" })).toBeTruthy();

    const uncheckedCard = within(pending).getByText("表格优先").closest("article") as HTMLElement;
    expect(uncheckedCard.textContent).toContain("没有完成冲突检查");
    expect(within(uncheckedCard).getByRole("button", { name: "生效" })).toBeTruthy();
  });

  it("生效记忆展示置信度和强化次数，并能预览某个问题会编入哪些记忆", async () => {
    const scored = memory("active", {
      display_text: "回答先给结论", confidence: { alpha: 7, beta: 1, mean: 0.875, variance: 0.01 }, reinforcement_count: 5
    });
    const api = fakeApi([[scored]]);
    renderPage(api);

    expect(await screen.findByText(/置信度 88% · 强化 5 次/)).toBeTruthy();

    fireEvent.change(screen.getByLabelText("回答模式"), { target: { value: "NOTE" } });
    fireEvent.change(screen.getByLabelText("预览用的问题"), { target: { value: "第 3 页讲了什么" } });
    fireEvent.click(screen.getByRole("button", { name: "预览" }));

    const list = await screen.findByRole("list", { name: "编入的记忆" });
    expect(api.previewChatControlPack).toHaveBeenCalledWith("workspace", "NOTE", "第 3 页讲了什么");
    expect(within(list).getByText("回答先给结论")).toBeTruthy();
    expect(within(list).getByText("常驻")).toBeTruthy();
    expect(within(list).getByText("相关度 82% · 与问题相关")).toBeTruthy();
  });
});
