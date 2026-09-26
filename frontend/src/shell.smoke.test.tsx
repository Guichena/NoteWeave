// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WorkbenchShell } from "./features/shell/WorkbenchShell";

afterEach(cleanup);

describe("frontend shell smoke", () => {
  it("renders left nav entries for all workspace views", () => {
    render(
      <WorkbenchShell
        view="chat"
        status="准备就绪"
        sessionLoading={false}
        workspaceName="Demo"
        conversationTitle="会话"
        workspaces={[{ workspace_id: "w1", name: "Demo" }]}
        conversations={[{ conversation_id: "c1", title: "会话" }]}
        workspaceId="w1"
        conversationId="c1"
        sourceCount={0}
        shellBusy={false}
        onNavigate={vi.fn()}
        onSwitchWorkspace={vi.fn()}
        onSwitchConversation={vi.fn()}
        onCreateWorkspace={vi.fn(async () => true)}
        onCreateConversation={vi.fn(async () => true)}
        onToggleSettings={vi.fn()}
        settingsOpen={false}
      >
        <div>main-canvas</div>
      </WorkbenchShell>
    );

    expect(screen.getByRole("heading", { name: "NoteWeave" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Chat · QA / Note / Wiki" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "工作台资料库" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Deep Research 工作台" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Wiki 治理工作台" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Memory 人工审核" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "新建会话" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "会话" })).toBeTruthy();
    expect(screen.getByText("main-canvas")).toBeTruthy();
  });
});
