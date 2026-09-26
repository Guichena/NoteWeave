// @vitest-environment jsdom

import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useWorkspaceSession } from "./useWorkspaceSession";

const workspaceOne = {
  workspace_id: "workspace-1",
  name: "Workspace One"
};

const conversationOne = {
  conversation_id: "conversation-1",
  title: "Conversation One",
  conversation_type: "WORKSPACE_CHAT",
  status: "ACTIVE",
  active_head_message_id: null,
  created_at: "2026-07-22T00:00:00Z",
  last_active_at: "2026-07-22T00:00:00Z"
};

function createRun() {
  return vi.fn(async (_label: string, action: () => Promise<void>) => action());
}

describe("useWorkspaceSession", () => {
  it("restores the first workspace and switches conversations through the loader", async () => {
    const conversationTwo = {
      ...conversationOne,
      conversation_id: "conversation-2",
      title: "Conversation Two"
    };
    const loader = {
      listAvailableWorkspaces: vi.fn(async () => [workspaceOne]),
      restoreWorkspace: vi.fn(async () => ({
        workspace: workspaceOne,
        conversations: [conversationOne, conversationTwo],
        conversation: conversationOne,
        messages: [{ role: "assistant", content: "restored" }],
        sources: [{ source_id: "source-1", title: "Source One" }]
      })),
      switchConversation: vi.fn(async () => ({
        workspace: workspaceOne,
        conversations: [conversationOne, conversationTwo],
        conversation: conversationTwo,
        messages: [{ role: "user", content: "second conversation" }],
        sources: []
      }))
    };
    const replaceMessages = vi.fn();
    const replaceSources = vi.fn();
    const resetWorkspaceScope = vi.fn();
    const { result } = renderHook(() => useWorkspaceSession({
      run: createRun(),
      setStatus: vi.fn(),
      replaceMessages,
      replaceSources,
      resetWorkspaceScope,
      loader: loader as never,
      workspaceClient: {} as never,
      conversationClient: {} as never
    }));

    await waitFor(() => expect(result.current.sessionLoading).toBe(false));
    expect(result.current.workspace?.workspace_id).toBe("workspace-1");
    expect(result.current.conversation?.conversation_id).toBe("conversation-1");
    expect(replaceSources).toHaveBeenCalledWith([
      { source_id: "source-1", title: "Source One" }
    ]);
    expect(replaceMessages).toHaveBeenCalledWith([
      { role: "assistant", content: "restored" }
    ]);
    expect(resetWorkspaceScope).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.switchConversation("conversation-2");
    });
    expect(loader.switchConversation).toHaveBeenCalledWith(
      workspaceOne,
      [conversationOne, conversationTwo],
      conversationTwo
    );
    expect(result.current.conversation?.conversation_id).toBe("conversation-2");
    expect(replaceMessages).toHaveBeenLastCalledWith([
      { role: "user", content: "second conversation" }
    ]);
  });

  it("creates a workspace with its default conversation as one transition", async () => {
    const createdWorkspace = { workspace_id: "workspace-2", name: "Created Workspace" };
    const createdConversation = {
      conversation_id: "conversation-created",
      title: "默认研究会话",
      conversation_type: "WORKSPACE_CHAT",
      created_at: "2026-07-22T01:00:00Z"
    };
    const workspaceClient = {
      create: vi.fn(async () => createdWorkspace)
    };
    const conversationClient = {
      create: vi.fn(async () => createdConversation)
    };
    const replaceMessages = vi.fn();
    const replaceSources = vi.fn();
    const resetWorkspaceScope = vi.fn();
    const run = createRun();
    const { result } = renderHook(() => useWorkspaceSession({
      run,
      setStatus: vi.fn(),
      replaceMessages,
      replaceSources,
      resetWorkspaceScope,
      loader: {
        listAvailableWorkspaces: vi.fn(async () => []),
        restoreWorkspace: vi.fn(),
        switchConversation: vi.fn()
      } as never,
      workspaceClient: workspaceClient as never,
      conversationClient: conversationClient as never
    }));

    await waitFor(() => expect(result.current.sessionLoading).toBe(false));
    await act(async () => {
      await result.current.createWorkspace({
        name: "法规证据库",
        description: "用于政策资料与结论审查"
      });
    });

    expect(run).toHaveBeenCalledWith("创建工作台", expect.any(Function));
    expect(workspaceClient.create).toHaveBeenCalledTimes(1);
    expect(workspaceClient.create).toHaveBeenCalledWith({
      name: "法规证据库",
      description: "用于政策资料与结论审查"
    });
    expect(conversationClient.create).toHaveBeenCalledWith("workspace-2", {
      title: "默认研究会话",
      conversation_type: "WORKSPACE_CHAT"
    });
    expect(result.current.workspace).toEqual(createdWorkspace);
    expect(result.current.conversation?.conversation_id).toBe("conversation-created");
    expect(result.current.conversations).toHaveLength(1);
    expect(replaceSources).toHaveBeenCalledWith([]);
    expect(replaceMessages).toHaveBeenCalledWith([]);
    expect(resetWorkspaceScope).toHaveBeenCalledTimes(1);
  });

  it("rejects creating a conversation without a workspace", async () => {
    const setStatus = vi.fn();
    const run = createRun();
    const conversationClient = { create: vi.fn() };
    const { result } = renderHook(() => useWorkspaceSession({
      run,
      setStatus,
      replaceMessages: vi.fn(),
      replaceSources: vi.fn(),
      resetWorkspaceScope: vi.fn(),
      loader: {
        listAvailableWorkspaces: vi.fn(async () => []),
        restoreWorkspace: vi.fn(),
        switchConversation: vi.fn()
      } as never,
      workspaceClient: {} as never,
      conversationClient: conversationClient as never
    }));

    await waitFor(() => expect(result.current.sessionLoading).toBe(false));
    await act(async () => {
      await result.current.createConversation("证据复核");
    });

    expect(setStatus).toHaveBeenCalledWith("请先创建工作台");
    expect(run).not.toHaveBeenCalled();
    expect(conversationClient.create).not.toHaveBeenCalled();
  });

  it("creates a named conversation without copying prior messages", async () => {
    const createdConversation = {
      conversation_id: "conversation-new",
      title: "竞品证据梳理",
      conversation_type: "WORKSPACE_CHAT",
      created_at: "2026-07-22T02:00:00Z"
    };
    const conversationClient = { create: vi.fn(async () => createdConversation) };
    const replaceMessages = vi.fn();
    const { result } = renderHook(() => useWorkspaceSession({
      run: createRun(),
      setStatus: vi.fn(),
      replaceMessages,
      replaceSources: vi.fn(),
      resetWorkspaceScope: vi.fn(),
      loader: {
        listAvailableWorkspaces: vi.fn(async () => [workspaceOne]),
        restoreWorkspace: vi.fn(async () => ({
          workspace: workspaceOne,
          conversations: [conversationOne],
          conversation: conversationOne,
          messages: [{ role: "assistant", content: "existing" }],
          sources: []
        })),
        switchConversation: vi.fn()
      } as never,
      workspaceClient: {} as never,
      conversationClient: conversationClient as never
    }));

    await waitFor(() => expect(result.current.sessionLoading).toBe(false));
    await act(async () => {
      await result.current.createConversation("竞品证据梳理");
    });

    expect(conversationClient.create).toHaveBeenCalledWith("workspace-1", {
      title: "竞品证据梳理",
      conversation_type: "WORKSPACE_CHAT"
    });
    expect(result.current.conversation?.title).toBe("竞品证据梳理");
    expect(result.current.conversations[0]?.conversation_id).toBe("conversation-new");
    expect(replaceMessages).toHaveBeenLastCalledWith([]);
  });
});
