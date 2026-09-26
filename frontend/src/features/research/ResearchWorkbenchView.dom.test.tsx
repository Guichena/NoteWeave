// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResearchWorkbenchView } from "./ResearchWorkbenchView";

vi.mock("./ResearchSidebar", () => ({
  ResearchSidebar: () => <aside>Research controls</aside>
}));

vi.mock("./ResearchReportPanel", () => ({
  ResearchReportPanel: () => <article>Research report</article>
}));

vi.mock("./ResearchDetailWorkbench", () => ({
  ResearchDetailWorkbench: ({ onClose }: { onClose: () => void }) => (
    <aside role="dialog" aria-label="Research detail fixture">
      <button type="button" onClick={onClose}>Close detail</button>
    </aside>
  )
}));

afterEach(() => cleanup());

describe("ResearchWorkbenchView overlays", () => {
  it("renders detail through a portal and closes it with backdrop or Escape", async () => {
    const setResearchDetailOpen = vi.fn();
    const { container } = renderView({
      detailOpen: true,
      setResearchDetailOpen
    });

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

  it("opens and closes the compact runtime status panel", () => {
    renderView({ detailOpen: false, setResearchDetailOpen: vi.fn() });

    const toggle = screen.getByRole("button", { name: "打开研究运行状态" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");

    fireEvent.click(toggle);
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByRole("button", { name: "关闭研究运行状态" })).toBeTruthy();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
  });
});

function renderView({
  detailOpen,
  setResearchDetailOpen
}: {
  detailOpen: boolean;
  setResearchDetailOpen: (open: boolean) => void;
}) {
  const currentResearchRun = {
    research_run_id: "run-1",
    question: "Test research question"
  };

  return render(
    <ResearchWorkbenchView
      {...({
        isBusy: false,
        sidebar: {},
        report: {},
        statusPanel: {
          currentResearchRunSummary: null,
          currentResearchWaitContext: null,
          currentResearchWaitSignals: [],
          currentResearchWaitDetails: [],
          latestResearchTask: null,
          latestResearchTaskWaitSignals: [],
          latestResearchTaskWaitDetails: [],
          latestResearchProgressEvent: null,
          currentResearchRun,
          buildWaitContextNarrative: () => "",
          buildResearchTaskRuntimeSnapshot: () => "",
          setResearchDetailOpen
        },
        detail: {
          researchDetailOpen: detailOpen,
          currentResearchRun,
          setResearchDetailOpen
        }
      } as any)}
    />
  );
}
