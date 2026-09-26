// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WorkbenchShell } from "./WorkbenchShell";

afterEach(cleanup);

describe("WorkbenchShell", () => {
  it("renders the top navigation and notifies navigation", () => {
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
        sourceCount={2}
        shellBusy={false}
        onNavigate={onNavigate}
        onSwitchWorkspace={vi.fn()}
        onSwitchConversation={vi.fn()}
        onCreateWorkspace={vi.fn(async () => true)}
        onCreateConversation={vi.fn(async () => true)}
        onToggleSettings={vi.fn()}
        settingsOpen={false}
      >
        <div>canvas</div>
      </WorkbenchShell>
    );

    expect(screen.getByRole("heading", { name: "NoteWeave" })).toBeTruthy();
    expect(screen.getByText("canvas")).toBeTruthy();
    expect(screen.getByRole("button", { name: "工作台资料库" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "工作台资料库" }));
    expect(onNavigate).toHaveBeenCalledWith("library");
    fireEvent.click(screen.getByRole("button", { name: "Deep Research 工作台" }));
    expect(onNavigate).toHaveBeenCalledWith("research");
  });

  it("searches and switches duplicate workspaces without rendering the entire list", () => {
    const onSwitchWorkspace = vi.fn();
    const workspaces = Array.from({ length: 30 }, (_, index) => ({
      workspace_id: `${String(index).padStart(8, "0")}-workspace`,
      name: index < 2 ? "NoteWeave 研究工作台" : `工作台 ${index}`
    }));
    render(
      <WorkbenchShell
        view="chat"
        status="ready"
        sessionLoading={false}
        workspaceName="NoteWeave 研究工作台"
        conversationTitle="会话 A"
        workspaces={workspaces}
        conversations={[{ conversation_id: "c1", title: "会话 A" }]}
        workspaceId="00000000-workspace"
        conversationId="c1"
        sourceCount={0}
        shellBusy={false}
        onNavigate={vi.fn()}
        onSwitchWorkspace={onSwitchWorkspace}
        onSwitchConversation={vi.fn()}
        onCreateWorkspace={vi.fn(async () => true)}
        onCreateConversation={vi.fn(async () => true)}
        onToggleSettings={vi.fn()}
        settingsOpen={false}
      >
        <div>canvas</div>
      </WorkbenchShell>
    );

    const trigger = screen.getByRole("button", { name: "切换工作台" });
    fireEvent.click(trigger);
    expect(screen.getAllByRole("option")).toHaveLength(24);
    expect(screen.getByText("显示 24 / 30")).toBeTruthy();

    fireEvent.change(screen.getByRole("searchbox", { name: "搜索工作台" }), {
      target: { value: "00000001" }
    });
    const options = screen.getAllByRole("option");
    expect(options).toHaveLength(1);
    expect(options[0].textContent).toContain("NoteWeave 研究工作台");
    expect(options[0].textContent).toContain("00000001");
    fireEvent.click(options[0]);
    expect(onSwitchWorkspace).toHaveBeenCalledWith("00000001-workspace");

    fireEvent.click(trigger);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "工作台切换器" })).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it("shows a pending surface instead of final page content while restoring the session", () => {
    render(
      <WorkbenchShell
        view="library"
        status="正在恢复"
        sessionLoading
        workspaceName=""
        conversationTitle=""
        workspaces={[]}
        conversations={[]}
        workspaceId=""
        conversationId=""
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
        <div>资料库还是空的</div>
      </WorkbenchShell>
    );

    expect(screen.getByRole("status", { name: "正在恢复工作台会话" }).textContent)
      .toContain("正在恢复工作台与会话");
    expect(screen.queryByText("资料库还是空的")).toBeNull();
  });

  it("collects workspace identity before creating a workspace", async () => {
    const onCreateWorkspace = vi.fn(async () => true);
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
        sourceCount={2}
        shellBusy={false}
        onNavigate={vi.fn()}
        onSwitchWorkspace={vi.fn()}
        onSwitchConversation={vi.fn()}
        onCreateWorkspace={onCreateWorkspace}
        onCreateConversation={vi.fn(async () => true)}
        onToggleSettings={vi.fn()}
        settingsOpen={false}
      >
        <div>canvas</div>
      </WorkbenchShell>
    );

    fireEvent.click(screen.getByRole("button", { name: "账户与工作台菜单" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "新建工作台" }));
    expect(screen.getByRole("dialog", { name: "创建研究工作台" })).toBeTruthy();
    const submit = screen.getByRole("button", { name: "创建工作台" });
    expect(submit.hasAttribute("disabled")).toBe(true);

    fireEvent.change(screen.getByLabelText("工作台名称"), { target: { value: "法规证据库" } });
    fireEvent.change(screen.getByLabelText(/用途说明/), { target: { value: "用于政策资料审查" } });
    fireEvent.click(submit);

    expect(onCreateWorkspace).toHaveBeenCalledWith({
      name: "法规证据库",
      description: "用于政策资料审查"
    });
  });

  it("collects a conversation name and explains its workspace scope", () => {
    const onCreateConversation = vi.fn(async () => true);
    render(
      <WorkbenchShell
        view="chat"
        status="准备就绪"
        sessionLoading={false}
        workspaceName="法规证据库"
        conversationTitle="会话 A"
        workspaces={[{ workspace_id: "w1", name: "法规证据库" }]}
        conversations={[{ conversation_id: "c1", title: "会话 A" }]}
        workspaceId="w1"
        conversationId="c1"
        sourceCount={2}
        shellBusy={false}
        onNavigate={vi.fn()}
        onSwitchWorkspace={vi.fn()}
        onSwitchConversation={vi.fn()}
        onCreateWorkspace={vi.fn(async () => true)}
        onCreateConversation={onCreateConversation}
        onToggleSettings={vi.fn()}
        settingsOpen={false}
      >
        <div>canvas</div>
      </WorkbenchShell>
    );

    fireEvent.click(screen.getByRole("button", { name: "新建会话" }));
    expect(screen.getByRole("dialog", { name: "新建独立会话" }).textContent)
      .toContain("消息记录与问答范围独立保存");
    fireEvent.change(screen.getByLabelText("会话名称"), { target: { value: "竞品证据梳理" } });
    fireEvent.click(screen.getByRole("button", { name: "创建会话" }));
    expect(onCreateConversation).toHaveBeenCalledWith("竞品证据梳理");
  });

  it("returns keyboard focus to creation triggers after Escape", async () => {
    render(
      <WorkbenchShell
        view="chat"
        status="准备就绪"
        sessionLoading={false}
        workspaceName="法规证据库"
        conversationTitle="会话 A"
        workspaces={[{ workspace_id: "w1", name: "法规证据库" }]}
        conversations={[{ conversation_id: "c1", title: "会话 A" }]}
        workspaceId="w1"
        conversationId="c1"
        sourceCount={2}
        shellBusy={false}
        onNavigate={vi.fn()}
        onSwitchWorkspace={vi.fn()}
        onSwitchConversation={vi.fn()}
        onCreateWorkspace={vi.fn(async () => true)}
        onCreateConversation={vi.fn(async () => true)}
        onToggleSettings={vi.fn()}
        settingsOpen={false}
      >
        <div>canvas</div>
      </WorkbenchShell>
    );

    const workspaceTrigger = screen.getByRole("button", { name: "账户与工作台菜单" });
    fireEvent.click(workspaceTrigger);
    fireEvent.click(screen.getByRole("menuitem", { name: "新建工作台" }));
    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(document.activeElement).toBe(workspaceTrigger));

    const conversationTrigger = screen.getByRole("button", { name: "新建会话" });
    fireEvent.click(conversationTrigger);
    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(document.activeElement).toBe(conversationTrigger));
  });

  it("replaces unusable page content with one workspace onboarding action", () => {
    render(
      <WorkbenchShell
        view="chat"
        status="准备就绪"
        sessionLoading={false}
        workspaceName=""
        conversationTitle=""
        workspaces={[]}
        conversations={[]}
        workspaceId=""
        conversationId=""
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
        <div>不可操作的业务页面</div>
      </WorkbenchShell>
    );

    expect(screen.getByRole("heading", { name: "先建立你的研究边界" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "创建第一个工作台" })).toBeTruthy();
    expect(screen.queryByText("不可操作的业务页面")).toBeNull();
  });
});
