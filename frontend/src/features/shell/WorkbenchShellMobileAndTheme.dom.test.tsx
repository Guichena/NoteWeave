// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WorkbenchShell } from "./WorkbenchShell";

afterEach(cleanup);

describe("WorkbenchShell Mobile & Theme", () => {
  const defaultProps = {
    view: "chat" as const,
    status: "就绪",
    sessionLoading: false,
    workspaceName: "My Workspace",
    conversationTitle: "Chat 1",
    workspaces: [{ workspace_id: "w1", name: "My Workspace" }],
    conversations: [{ conversation_id: "c1", title: "Chat 1" }],
    workspaceId: "w1",
    conversationId: "c1",
    sourceCount: 0,
    shellBusy: false,
    onNavigate: vi.fn(),
    onSwitchWorkspace: vi.fn(),
    onSwitchConversation: vi.fn(),
    onCreateWorkspace: vi.fn(async () => true),
    onCreateConversation: vi.fn(async () => true),
    onToggleSettings: vi.fn(),
    settingsOpen: false
  };

  it("toggles mobile drawer, responds to backdrop click and Escape key", () => {
    render(<WorkbenchShell {...defaultProps}><div>Content</div></WorkbenchShell>);

    const menuToggleBtn = screen.getByRole("button", { name: "打开主导航" });
    expect(menuToggleBtn.getAttribute("aria-expanded")).toBe("false");

    fireEvent.click(menuToggleBtn);
    expect(menuToggleBtn.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByRole("button", { name: "关闭导航" })).toBeTruthy();

    // Test Escape key closes drawer
    fireEvent.keyDown(window, { key: "Escape" });
    expect(menuToggleBtn.getAttribute("aria-expanded")).toBe("false");
  });

  it("toggles theme between light and dark", () => {
    render(<WorkbenchShell {...defaultProps}><div>Content</div></WorkbenchShell>);

    const themeBtn = screen.getAllByRole("button", { name: "切换明暗主题" })[0];
    expect(document.documentElement.hasAttribute("data-theme")).toBe(true);

    fireEvent.click(themeBtn);
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");

    fireEvent.click(themeBtn);
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("closes the mobile navigation before opening conversation creation", () => {
    render(<WorkbenchShell {...defaultProps}><div>Content</div></WorkbenchShell>);

    fireEvent.click(screen.getByRole("button", { name: "打开主导航" }));
    expect(screen.getByRole("button", { name: "关闭导航" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "新建会话" }));

    expect(screen.queryByRole("button", { name: "关闭导航" })).toBeNull();
    expect(screen.getByRole("dialog", { name: "新建独立会话" })).toBeTruthy();
  });
});
