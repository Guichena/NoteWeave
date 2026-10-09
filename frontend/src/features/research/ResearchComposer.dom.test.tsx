// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResearchComposer } from "./ResearchComposer";

afterEach(cleanup);

function renderComposer(overrides: Record<string, unknown> = {}) {
  const props = {
    isBusy: false,
    workspace: { workspace_id: "workspace" },
    sources: [],
    researchQuestion: "研究问题",
    setResearchQuestion: vi.fn(),
    researchGoal: "",
    setResearchGoal: vi.fn(),
    researchRetrievalMode: "WEB_ONLY",
    setResearchRetrievalMode: vi.fn(),
    researchTimeRange: "",
    setResearchTimeRange: vi.fn(),
    researchDepth: "STANDARD",
    setResearchDepth: vi.fn(),
    researchType: "AUTO",
    setResearchType: vi.fn(),
    researchConstraintsText: "",
    setResearchConstraintsText: vi.fn(),
    selectedResearchSourceIds: [],
    toggleResearchScope: vi.fn(),
    researchScopeSources: [],
    onStart: vi.fn(),
    ...overrides
  } as any;
  const view = render(<ResearchComposer {...props} />);
  return { props, ...view };
}

const startButton = () => screen.getByRole("button", { name: "启动 Deep Research" }) as HTMLButtonElement;

describe("ResearchComposer", () => {
  it("填写问题后可以启动，问题为空时禁用", () => {
    const { props, rerender } = renderComposer();

    expect(screen.getByRole("radio", { name: "仅网络" }).getAttribute("aria-checked")).toBe("true");
    expect(startButton().disabled).toBe(false);
    fireEvent.click(startButton());
    expect(props.onStart).toHaveBeenCalledTimes(1);

    rerender(<ResearchComposer {...props} researchQuestion="  " />);
    expect(startButton().disabled).toBe(true);
  });

  it("使用资料模式但没有已解析资料时提示改用仅网络", () => {
    renderComposer({ researchRetrievalMode: "SOURCES_ONLY" });

    expect(startButton().disabled).toBe(true);
    expect(screen.getByText(/改用「仅网络」模式/)).toBeTruthy();
  });

  it("可以切换模式并勾选资料范围", () => {
    const source = { source_id: "s1", title: "检索设计.md", status: "READY", parse_status: "PARSED", index_status: "INDEXED" };
    const { props } = renderComposer({
      researchRetrievalMode: "WEB_PLUS_SEEDS",
      sources: [source],
      selectedResearchSourceIds: ["s1"],
      researchScopeSources: [source]
    });

    fireEvent.click(screen.getByRole("radio", { name: "仅资料" }));
    expect(props.setResearchRetrievalMode).toHaveBeenCalledWith("SOURCES_ONLY");

    const scope = screen.getByRole("button", { name: "检索设计.md" });
    expect(scope.getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(scope);
    expect(props.toggleResearchScope).toHaveBeenCalledWith("s1");
    expect(startButton().disabled).toBe(false);
  });

  it("问题为空时提供示例问题", () => {
    const { props } = renderComposer({ researchQuestion: "" });

    fireEvent.click(screen.getByRole("button", { name: /Elasticsearch、Milvus 与 pgvector/ }));
    expect(props.setResearchQuestion).toHaveBeenCalledWith(expect.stringContaining("pgvector"));
  });
});
