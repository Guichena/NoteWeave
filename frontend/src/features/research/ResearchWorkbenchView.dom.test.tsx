// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResearchWorkbenchView } from "./ResearchWorkbenchView";

vi.mock("./ResearchComposer", () => ({
  ResearchComposer: ({ onStart }: { onStart: () => void }) => (
    <div>
      <p>研究输入</p>
      <button type="button" onClick={onStart}>开始</button>
    </div>
  )
}));

vi.mock("./ResearchRunView", () => ({
  ResearchRunView: ({ run, onOpenAudit }: { run: { question: string }; onOpenAudit: () => void }) => (
    <article>
      <h2>{run.question}</h2>
      <button type="button" onClick={onOpenAudit}>审计详情</button>
    </article>
  )
}));

vi.mock("./ResearchDetailWorkbench", () => ({
  ResearchDetailWorkbench: ({ onClose }: { onClose: () => void }) => (
    <aside role="dialog" aria-label="Research detail fixture">
      <button type="button" onClick={onClose}>Close detail</button>
    </aside>
  )
}));

afterEach(() => cleanup());

const currentResearchRun = { research_run_id: "run-1", question: "Test research question" };
const runSummary = {
  research_run_id: "run-1",
  question: "Test research question",
  final_report_title: "",
  status: "COMPLETED",
  updated_at: "2026-09-20T10:00:00Z"
};

function renderView({
  run = currentResearchRun as typeof currentResearchRun | null,
  detailOpen = false,
  setResearchDetailOpen = vi.fn(),
  openResearchRunHistoryItem = vi.fn(),
  startDeepResearch = vi.fn()
} = {}) {
  return render(
    <ResearchWorkbenchView
      {...({
        isBusy: false,
        sidebar: {
          researchRuns: run ? [runSummary] : [],
          currentResearchRunId: run?.research_run_id ?? "",
          openResearchRunHistoryItem,
          startDeepResearch
        },
        report: { currentResearchRun: run },
        statusPanel: { latestResearchTask: null },
        detail: {
          researchDetailOpen: detailOpen,
          currentResearchRun: run,
          setResearchDetailOpen
        }
      } as any)}
    />
  );
}

describe("ResearchWorkbenchView", () => {
  it("没有研究时展示输入页，有研究时展示运行页", () => {
    const { unmount } = renderView({ run: null });
    expect(screen.getByText("研究输入")).toBeTruthy();
    expect(screen.getByText("还没有研究记录")).toBeTruthy();
    unmount();

    renderView();
    expect(screen.getByRole("heading", { name: "Test research question" })).toBeTruthy();
  });

  it("可以在新研究和历史记录之间切换", () => {
    const openResearchRunHistoryItem = vi.fn();
    renderView({ openResearchRunHistoryItem });

    fireEvent.click(screen.getByRole("button", { name: "新研究" }));
    expect(screen.getByText("研究输入")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Test research question/ }));
    expect(openResearchRunHistoryItem).toHaveBeenCalledWith(runSummary);
    expect(screen.getByRole("heading", { name: "Test research question" })).toBeTruthy();
  });

  it("通过 portal 渲染审计详情，并支持点击背景或 Esc 关闭", async () => {
    const setResearchDetailOpen = vi.fn();
    const { container } = renderView({ detailOpen: true, setResearchDetailOpen });

    const dialog = await screen.findByRole("dialog", { name: "Research detail fixture" });
    expect(container.contains(dialog)).toBe(false);

    const overlay = document.querySelector<HTMLElement>(".research-detail-overlay");
    expect(overlay).toBeTruthy();
    fireEvent.mouseDown(overlay!);
    expect(setResearchDetailOpen).toHaveBeenCalledWith(false);

    setResearchDetailOpen.mockClear();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(setResearchDetailOpen).toHaveBeenCalledWith(false);
  });
});
