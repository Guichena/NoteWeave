// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResearchWorkbenchView } from "./ResearchWorkbenchView";

vi.mock("./ResearchComposer", () => ({
  RESEARCH_EXAMPLES: [],
  ResearchComposer: ({ onStart }: { onStart: () => void }) => (
    <div>
      <p>研究输入</p>
      <button type="button" onClick={onStart}>开始</button>
    </div>
  )
}));

vi.mock("./ResearchRunView", () => ({
  ResearchRunView: ({ run, onOpenAudit, onBack }: { run: { question: string }; onOpenAudit: () => void; onBack: () => void }) => (
    <article aria-label="完整报告页">
      <h2>{run.question}</h2>
      <button type="button" onClick={onOpenAudit}>审计详情</button>
      <button type="button" onClick={onBack}>返回对话</button>
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
          startDeepResearch,
          researchQuestion: "",
          setResearchQuestion: vi.fn()
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

  it("研究默认以对话呈现，从报告消息进入完整报告页后可以返回对话", () => {
    const markdown = ["# 报告", "", "结论 [evidence:e1]。", "", "## 直接回答", "", "细节 [evidence:e1]。"].join("\n");
    renderView({ run: { ...currentResearchRun, final_report_markdown: markdown } as any });

    expect(screen.getByRole("region", { name: "研究对话" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /查看完整报告/ }));
    expect(screen.getByRole("article", { name: "完整报告页" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "返回对话" }));
    expect(screen.getByRole("region", { name: "研究对话" })).toBeTruthy();
  });

  it("从问答页的研究消息跳过来时直接打开这次研究的完整报告", () => {
    const onReportRunOpened = vi.fn();
    render(
      <ResearchWorkbenchView
        {...({
          isBusy: false,
          sidebar: {
            researchRuns: [runSummary],
            currentResearchRunId: "run-1",
            openResearchRunHistoryItem: vi.fn(),
            startDeepResearch: vi.fn(),
            researchQuestion: "",
            setResearchQuestion: vi.fn()
          },
          report: { currentResearchRun: currentResearchRun },
          statusPanel: { latestResearchTask: null },
          detail: { researchDetailOpen: false, currentResearchRun, setResearchDetailOpen: vi.fn() },
          reportRunId: "run-1",
          onReportRunOpened
        } as any)}
      />
    );

    expect(screen.getByRole("article", { name: "完整报告页" })).toBeTruthy();
    expect(onReportRunOpened).toHaveBeenCalled();
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
