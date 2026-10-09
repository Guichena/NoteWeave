// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MessageBubble } from "./MessageBubble";

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
    const loadEvidence = vi.fn(async () => [{
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
    }]);
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
