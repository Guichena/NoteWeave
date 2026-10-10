// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WikiMaintenancePanel } from "./WikiMaintenancePanel";

function panelProps(overrides: Record<string, unknown> = {}) {
  return {
    isBusy: false,
    workspace: { workspace_id: "workspace", name: "研究工作台", status: "ACTIVE" },
    wikiEnabled: false,
    toggleWikiEnabled: vi.fn(),
    rebuildWikiLinks: vi.fn(),
    autoFixWiki: vi.fn(),
    autoFixableIssues: [],
    reviewRequiredIssues: [],
    wikiIssues: [],
    filteredWikiIssues: [],
    wikiAdviceAction: null,
    wikiRebuildAdvice: null,
    wikiStats: null,
    wikiLog: [],
    onClose: vi.fn(),
    ...overrides
  } as any;
}

describe("WikiMaintenancePanel", () => {
  afterEach(() => cleanup());

  it("switches workspace Wiki building from the management drawer", () => {
    const props = panelProps();
    const { rerender } = render(<WikiMaintenancePanel {...props} />);

    fireEvent.click(screen.getByRole("button", { name: /开启 Wiki 构建/ }));
    expect(props.toggleWikiEnabled).toHaveBeenCalledOnce();

    rerender(<WikiMaintenancePanel {...panelProps({ wikiEnabled: true })} />);
    expect(screen.getByRole("button", { name: /关闭 Wiki 构建/ })).toBeTruthy();
  });
});
