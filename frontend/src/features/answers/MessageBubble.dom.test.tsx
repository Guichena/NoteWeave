// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { AnswerEvidence } from "./evidence";
import type { Message } from "./messageTypes";
import { MessageBubble, splitCitedViews } from "./MessageBubble";

afterEach(cleanup);

describe("MessageBubble failed answers", () => {
  it("hides partial placeholder content and cards when an answer run fails", () => {
    render(
      <MessageBubble message={{
        role: "assistant",
        answerMode: "note",
        answerStatus: "FAILED",
        answerError: "Answer LLM is not configured",
        content: [
          "## 直接回答",
          "> **[TEMPLATE PLACEHOLDER]** This is not a real answer.",
          "## 来源引用",
          "- hidden-source.md"
        ].join("\n")
      }} />
    );

    expect(screen.getByRole("alert").textContent).toContain("Answer LLM is not configured");
    expect(screen.getByText("本次回答未能完成。")).toBeTruthy();
    expect(screen.queryByText(/TEMPLATE PLACEHOLDER/)).toBeNull();
    expect(screen.queryByText(/hidden-source/)).toBeNull();
  });

  it("keeps completed answer content visible", () => {
    render(
      <MessageBubble message={{
        role: "assistant",
        answerMode: "wiki",
        answerStatus: "COMPLETED",
        content: "已完成的回答"
      }} />
    );

    expect(screen.getByText("已完成的回答")).toBeTruthy();
  });

  it("does not turn redundant Wiki metadata into large answer cards", () => {
    render(
      <MessageBubble message={{
        role: "assistant",
        answerMode: "wiki",
        answerStatus: "COMPLETED",
        content: [
          "## 来源回链",
          "当前命中页面暂时没有绑定来源引用。",
          "## 页面关系",
          "0 条页面关系",
          "## 默认 Wiki 工作台",
          "/workspaces/wiki-default"
        ].join("\n")
      }} />
    );

    expect(screen.getByText("来源回链")).toBeTruthy();
    expect(screen.getByText("当前命中页面暂时没有绑定来源引用。")).toBeTruthy();
    expect(screen.queryByText("页面关系")).toBeNull();
    expect(screen.queryByText("默认 Wiki 工作台")).toBeNull();
    expect(document.querySelector(".message-card")).toBeNull();
  });

  it("keeps useful Wiki prose while hiding a lone workspace heading and internal path", () => {
    render(
      <MessageBubble message={{
        role: "assistant",
        answerMode: "wiki",
        answerStatus: "COMPLETED",
        content: [
          "## 默认 Wiki 工作台",
          "/workspaces/workspace-1/wiki",
          "",
          "当前工作台还没有可展示的 Wiki 页面。"
        ].join("\n")
      }} />
    );

    expect(screen.getByText("当前工作台还没有可展示的 Wiki 页面。")).toBeTruthy();
    expect(screen.queryByText("默认 Wiki 工作台")).toBeNull();
    expect(screen.queryByText("/workspaces/workspace-1/wiki")).toBeNull();
  });

  it("keeps the lead and turns low-cost model h3 relation aliases into Wiki cards", () => {
    render(
      <MessageBubble message={{
        role: "assistant",
        answerMode: "wiki",
        answerStatus: "COMPLETED",
        citations: ["frontend-source.md | NoteWeave 连接资料、会话、研究、Wiki、记忆与产物。"],
        content: [
          "NoteWeave 将资料、会话与研究结果组织为持续演进的知识网络。",
          "",
          "### 关键关系梳理",
          "- frontend-source.md 是知识链路枢纽。",
          "",
          "### 知识网络状态",
          "- 页面关系均已解析。"
        ].join("\n")
      }} />
    );

    expect(screen.getByText("NoteWeave 将资料、会话与研究结果组织为持续演进的知识网络。")).toBeTruthy();
    expect(screen.getByText("关键页面关系")).toBeTruthy();
    expect(screen.getByText("1 条页面关系")).toBeTruthy();
    expect(screen.getByText("知识网络状态")).toBeTruthy();
    expect(screen.getByText("来源引用")).toBeTruthy();
  });

  it("keeps h3 subsections inside non-Wiki answer bodies", () => {
    render(
      <MessageBubble message={{
        role: "assistant",
        answerMode: "qa",
        answerStatus: "COMPLETED",
        content: [
          "## 直接回答",
          "主要结论。",
          "",
          "### 解释细节",
          "这是正文内部的小标题。"
        ].join("\n")
      }} />
    );

    expect(screen.getByText("解释细节")).toBeTruthy();
    expect(document.querySelector(".message-card")).toBeNull();
  });
});

describe("MessageBubble 检索链路与证据", () => {
  it("标注回答所用的检索方式，展开引用时读取证据清单并展示位置和摘录", async () => {
    const loadEvidence = vi.fn(async () => ({ run_id: "run-1", evidence: [{
      rank: 1,
      evidence_id: "note-window:c1:12",
      kind: "PASSAGE",
      source_id: "s1",
      passage_id: "c1",
      knowledge_item_id: null,
      title: "RAG 检索链路设计.md",
      excerpt: "命中锚点窗口后向前后各扩展一个窗口",
      location: "第 5 节",
      character_cost: 20
    }] }));
    render(
      <MessageBubble
        loadEvidence={loadEvidence}
        message={{
          role: "assistant",
          answerMode: "note",
          answerStatus: "COMPLETED",
          answerRunId: "run-1",
          content: "先定位、再连续阅读 [1]。",
          citations: ["RAG 检索链路设计.md | 命中锚点窗口后向前后各扩展一个窗口"]
        }}
      />
    );

    expect(screen.getByText("精读 · 原文窗口")).toBeTruthy();
    expect(loadEvidence).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /来源 1/ }));
    expect(loadEvidence).toHaveBeenCalledWith("run-1");
    expect(await screen.findByText("阅读窗口 · 第 5 节")).toBeTruthy();
    expect(screen.getAllByText("命中锚点窗口后向前后各扩展一个窗口").length).toBeGreaterThan(0);
  });

  it("点击回答中的 Wiki 链接跳转到对应页面", () => {
    const onOpenWikiPage = vi.fn();
    render(
      <MessageBubble
        onOpenWikiPage={onOpenWikiPage}
        message={{ role: "assistant", answerMode: "wiki", answerStatus: "COMPLETED", content: "参见 [[混合检索]]。" }}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "混合检索" }));
    expect(onOpenWikiPage).toHaveBeenCalledWith("混合检索");
  });
});

function evidenceItem(overrides: Partial<AnswerEvidence> = {}): AnswerEvidence {
  return {
    rank: 1, evidence_id: "knowledge-version:k1", kind: "KNOWLEDGE_VERSION", source_id: null, passage_id: null,
    knowledge_item_id: "k1", title: "RRF 融合排序", excerpt: "按名次融合", location: null, character_cost: 5,
    ...overrides
  };
}

function completedAnswer(overrides: Partial<Message> = {}): Message {
  return {
    role: "assistant", answerMode: "qa", answerStatus: "COMPLETED", answerRunId: "run-9",
    content: "按名次融合 [1]。", citations: ["RRF 融合排序 | 按名次融合"], ...overrides
  };
}

describe("MessageBubble 检索改写、证据充分性与反馈", () => {
  it("本次会话生成完的回答立即读取证据清单，证据不足时在回答上方提示", async () => {
    const loadEvidence = vi.fn(async () => ({ run_id: "run-9", evidence: [], evidence_sufficiency: "INSUFFICIENT" }));
    const { rerender } = render(<MessageBubble loadEvidence={loadEvidence} message={completedAnswer({ answerStatus: "GENERATING" })} />);
    expect(loadEvidence).not.toHaveBeenCalled();

    rerender(<MessageBubble loadEvidence={loadEvidence} message={completedAnswer()} />);
    expect((await screen.findByRole("note")).textContent).toContain("没有足够的信息");
  });

  it("历史回答不主动读取证据清单", () => {
    const loadEvidence = vi.fn(async () => ({ run_id: "run-9", evidence: [] }));
    render(<MessageBubble loadEvidence={loadEvidence} message={completedAnswer()} />);
    expect(loadEvidence).not.toHaveBeenCalled();
  });

  it("展开引用后展示检索改写和 Wiki 页面的到达路径", async () => {
    const loadEvidence = vi.fn(async () => ({
      run_id: "run-9",
      evidence: [evidenceItem({ graph_path: ["混合检索", "RRF 融合排序"] })],
      evidence_sufficiency: "PARTIAL",
      retrieval_rewrite: {
        is_standalone: false, standalone_query: "RRF 融合排序的参数 K 取多少", keyword_query: "RRF K",
        method: "RULE", degradation_reasons: ["QUERY_REWRITE_LLM_UNAVAILABLE"]
      }
    }));
    render(<MessageBubble loadEvidence={loadEvidence} message={completedAnswer({ answerMode: "wiki" })} />);

    fireEvent.click(screen.getByRole("button", { name: /来源 1/ }));
    expect(await screen.findByText("经由页面：混合检索 → RRF 融合排序")).toBeTruthy();
    expect(screen.getByText(/检索时理解为：RRF 融合排序的参数 K 取多少/)).toBeTruthy();
    expect(screen.getByText("（模型不可用，按规则补全）")).toBeTruthy();
    expect(screen.getByRole("note").textContent).toContain("只能回答这个问题的一部分");
  });

  it("点踩后可以补充原因，两次提交都带上回答 ID", async () => {
    const onFeedback = vi.fn(async () => undefined);
    render(<MessageBubble onFeedback={onFeedback} message={completedAnswer()} />);

    fireEvent.click(screen.getByRole("button", { name: "回答没有帮助" }));
    expect(onFeedback).toHaveBeenCalledWith("run-9", { rating: "NEGATIVE" });
    fireEvent.click(await screen.findByRole("button", { name: "引用不支持结论" }));
    expect(onFeedback).toHaveBeenLastCalledWith("run-9", { rating: "NEGATIVE", reason: "引用不支持结论" });
    expect(await screen.findByText("感谢反馈")).toBeTruthy();
  });

  it("反馈提交失败时恢复按钮状态并提示", async () => {
    const onFeedback = vi.fn(async () => { throw new Error("boom"); });
    render(<MessageBubble onFeedback={onFeedback} message={completedAnswer()} />);

    fireEvent.click(screen.getByRole("button", { name: "回答有帮助" }));
    expect((await screen.findByRole("alert")).textContent).toContain("没有提交成功");
    expect(screen.getByRole("button", { name: "回答有帮助" }).getAttribute("aria-pressed")).toBe("false");
  });

  it("重新生成把原回答 ID 交给调用方", () => {
    const onRegenerate = vi.fn();
    render(<MessageBubble onRegenerate={onRegenerate} message={completedAnswer()} />);
    fireEvent.click(screen.getByRole("button", { name: "重新生成回答" }));
    expect(onRegenerate).toHaveBeenCalledWith("run-9");
  });
});

describe("splitCitedViews", () => {
  it("puts sources cited inline first and keeps the rest apart", () => {
    const views = [1, 2, 3, 4].map((index) => ({ index }));
    const { cited, uncited } = splitCitedViews(views, "第一步是召回 [3]，第二步是重排 [1][3]。");
    expect(cited.map((view) => view.index)).toEqual([1, 3]);
    expect(uncited.map((view) => view.index)).toEqual([2, 4]);
  });

  it("treats every source as uncited when the answer has no numbered citations", () => {
    const { cited } = splitCitedViews([{ index: 1 }], "Wiki 回答引用页面 [[Memory]]。");
    expect(cited).toEqual([]);
  });
});
