// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ResearchLaunchboard } from "./ResearchLaunchboard";

beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
    configurable: true,
    value: vi.fn()
  });
});

afterEach(() => cleanup());

describe("ResearchLaunchboard", () => {
  it("renders real workspace state and keeps both launch actions operational", () => {
    const onOpenSourceLibrary = vi.fn();
    render(
      <>
        <textarea id="research-question-input" aria-label="研究问题" />
        <ResearchLaunchboard
          workspaceName="企业知识研究"
          sourceCount={3}
          readySourceCount={2}
          selectedSourceCount={0}
          selectedSourceTitles={[]}
          researchHistoryCount={4}
          researchRetrievalMode="WEB_PLUS_SEEDS"
          researchQuestion=""
          onOpenSourceLibrary={onOpenSourceLibrary}
        />
      </>
    );

    expect(screen.getByRole("heading", { name: "从一个问题开始，留下可核验的报告" })).toBeTruthy();
    expect(screen.getByText("企业知识研究")).toBeTruthy();
    const context = document.querySelector(".research-launchboard-context");
    expect(context?.textContent).toContain("网络 + 资料");
    expect(context?.textContent).toContain("2 / 3 已解析");
    expect(context?.textContent).toContain("4 个 run");
    expect(screen.getByLabelText("Research 工作流").children).toHaveLength(3);
    expect(screen.queryByText("还没有选中的 Research Run")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /填写研究问题/ }));
    expect(document.activeElement).toBe(screen.getByRole("textbox", { name: "研究问题" }));

    fireEvent.click(screen.getByRole("button", { name: "打开资料库" }));
    expect(onOpenSourceLibrary).toHaveBeenCalledTimes(1);
  });

  it("shows only actual selected source titles and the remaining count", () => {
    render(
      <ResearchLaunchboard
        workspaceName="研发工作台"
        sourceCount={5}
        readySourceCount={5}
        selectedSourceCount={3}
        selectedSourceTitles={["架构说明.pdf", "调研记录.md"]}
        researchHistoryCount={0}
        researchRetrievalMode="SOURCES_ONLY"
        researchQuestion="对比两种检索架构"
        onOpenSourceLibrary={vi.fn()}
      />
    );

    expect(screen.getByRole("button", { name: /检查研究问题/ })).toBeTruthy();
    expect(screen.getByText("架构说明.pdf")).toBeTruthy();
    expect(screen.getByText("调研记录.md")).toBeTruthy();
    expect(screen.getByText("+1 份")).toBeTruthy();
    expect(screen.getByText("只读取显式资料范围")).toBeTruthy();
  });
});
