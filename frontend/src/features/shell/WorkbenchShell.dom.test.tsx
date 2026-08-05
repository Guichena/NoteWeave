// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WorkbenchShell } from "./WorkbenchShell";

afterEach(cleanup);

describe("WorkbenchShell", () => {
  it("renders left nav and notifies navigation", () => {
    const onNavigate = vi.fn();
    render(
      <WorkbenchShell
        view="chat"
        status="准备就绪"
        sessionLoading={false}
        workspaceName="Demo"
        conversationTitle="会话 A"
        workspaces={[{ workspace_id: "w1", name: "Demo" }]}
        conversations={[{ conversation_id: "c1", title: "会话 A" }]}
        workspaceId="w1"
        conversationId="c1"
        shellBusy={false}
        onNavigate={onNavigate}
        onSwitchWorkspace={vi.fn()}
        onSwitchConversation={vi.fn()}
        onCreateWorkspace={vi.fn()}
        onCreateConversation={vi.fn()}
        onToggleSettings={vi.fn()}
        settingsOpen={false}
      >
        <div>canvas</div>
      </WorkbenchShell>
    );

    expect(screen.getByRole("heading", { name: "NoteWeave" })).toBeTruthy();
    expect(screen.getByText("canvas")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Deep Research 工作台" }));
    expect(onNavigate).toHaveBeenCalledWith("research");
  });
});
