import { useEffect, useState } from "react";
import { conversationsApi, type ConversationsApi } from "../conversations/api";
import { type Conversation, type ConversationSummary } from "../conversations/model";
import { type Message } from "../answers/messageTypes";
import { type SourceAsset } from "../sources/model";
import { workspaceApi, type WorkspaceApi } from "./api";
import { type Workspace } from "./model";
import {
  workspaceSessionLoader,
  type RestoredWorkspaceSession,
  type WorkspaceSessionLoader
} from "./session";
import type { ShellRun } from "../shell/useShellBusy";

type UseWorkspaceSessionInput = {
  run: ShellRun;
  setStatus: (status: string) => void;
  replaceMessages: (messages: Message[]) => void;
  replaceSources: (sources: SourceAsset[]) => void;
  resetWorkspaceScope: () => void;
  loader?: WorkspaceSessionLoader;
  workspaceClient?: WorkspaceApi;
  conversationClient?: ConversationsApi;
};

const DEFAULT_MESSAGES: Message[] = [{
  role: "assistant",
  content: "工作台已恢复。可以继续上传资料或提问。"
}];

const SESSION_SELECTION_KEY = "noteweave.workspace.selection";

type SessionSelection = {
  workspaceId: string;
  conversationId?: string;
};

function readSessionSelection(): SessionSelection | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.sessionStorage.getItem(SESSION_SELECTION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<SessionSelection>;
    return typeof parsed.workspaceId === "string" && parsed.workspaceId.trim()
      ? {
        workspaceId: parsed.workspaceId,
        conversationId: typeof parsed.conversationId === "string" ? parsed.conversationId : undefined
      }
      : null;
  } catch {
    return null;
  }
}

function writeSessionSelection(workspaceId: string, conversationId?: string | null) {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(SESSION_SELECTION_KEY, JSON.stringify({
      workspaceId,
      ...(conversationId ? { conversationId } : {})
    }));
  } catch {
    // Session persistence is best effort; server state remains authoritative.
  }
}

export function useWorkspaceSession({
  run,
  setStatus,
  replaceMessages,
  replaceSources,
  resetWorkspaceScope,
  loader = workspaceSessionLoader,
  workspaceClient = workspaceApi,
  conversationClient = conversationsApi
}: UseWorkspaceSessionInput) {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [sessionLoading, setSessionLoading] = useState(true);

  function applyRestoredWorkspace(restored: RestoredWorkspaceSession, resetScope = true) {
    setWorkspace(restored.workspace);
    setConversations(restored.conversations);
    setConversation(restored.conversation ? toConversation(restored.conversation) : null);
    replaceSources(restored.sources);
    replaceMessages(restored.messages.length > 0 ? restored.messages : DEFAULT_MESSAGES);
    if (resetScope) {
      resetWorkspaceScope();
    }
  }

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const available = await loader.listAvailableWorkspaces();
        if (cancelled) {
          return;
        }
        setWorkspaces(available);
        if (available.length === 0) {
          return;
        }
        const saved = readSessionSelection();
        const target = available.find((item) => item.workspace_id === saved?.workspaceId)
          ?? available[0];
        const restored = await loader.restoreWorkspace(target, saved?.conversationId);
        if (!cancelled && restored) {
          applyRestoredWorkspace(restored, true);
          writeSessionSelection(restored.workspace.workspace_id, restored.conversation?.conversation_id);
        }
      } catch (error) {
        if (!cancelled) {
          setStatus(error instanceof Error ? error.message : "恢复工作台会话失败");
        }
      } finally {
        if (!cancelled) {
          setSessionLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  async function switchWorkspace(workspaceId: string) {
    if (!workspaceId || workspaceId === workspace?.workspace_id) {
      return;
    }
    await run("切换工作台", async () => {
      const target = workspaces.find((item) => item.workspace_id === workspaceId);
      if (!target) {
        throw new Error("工作台不存在");
      }
      const restored = await loader.restoreWorkspace(target);
      if (!restored) {
        throw new Error("无法恢复工作台会话");
      }
      applyRestoredWorkspace(restored, true);
      writeSessionSelection(restored.workspace.workspace_id, restored.conversation?.conversation_id);
    });
  }

  async function switchConversation(conversationId: string) {
    if (!workspace || !conversationId || conversationId === conversation?.conversation_id) {
      return;
    }
    await run("切换会话", async () => {
      const target = conversations.find((item) => item.conversation_id === conversationId);
      if (!target) {
        throw new Error("会话不存在");
      }
      const restored = await loader.switchConversation(workspace, conversations, target);
      if (!restored) {
        throw new Error("无法恢复会话消息");
      }
      setConversation(toConversation(target));
      replaceMessages(restored.messages);
      writeSessionSelection(workspace.workspace_id, target.conversation_id);
    });
  }

  async function createWorkspace() {
    await run("创建工作台", async () => {
      const created = await workspaceClient.create({
        name: "NoteWeave 研究工作台",
        description: "用于上传资料、持续对话和维护工作台级 Wiki 的研究空间"
      });
      const createdConversation = await conversationClient.create(created.workspace_id, {
        title: "默认研究会话",
        conversation_type: "WORKSPACE_CHAT"
      });
      setWorkspace(created);
      setWorkspaces((current) => [
        created,
        ...current.filter((item) => item.workspace_id !== created.workspace_id)
      ]);
      setConversation(createdConversation);
      setConversations([toConversationSummary(createdConversation)]);
      replaceSources([]);
      replaceMessages([{
        role: "system",
        content: `已创建工作台 ${created.name}，并创建默认会话。`
      }]);
      resetWorkspaceScope();
      writeSessionSelection(created.workspace_id, createdConversation.conversation_id);
    });
  }

  async function createConversation() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("新建会话", async () => {
      const created = await conversationClient.create(workspace.workspace_id, {
        title: `研究会话 ${conversations.length + 1}`,
        conversation_type: "WORKSPACE_CHAT"
      });
      setConversation(created);
      setConversations((current) => [
        toConversationSummary(created),
        ...current.filter((item) => item.conversation_id !== created.conversation_id)
      ]);
      replaceMessages([{
        role: "assistant",
        content: "新会话已创建，可以继续提问。"
      }]);
      writeSessionSelection(workspace.workspace_id, created.conversation_id);
    });
  }

  return {
    workspace,
    workspaces,
    conversation,
    conversations,
    sessionLoading,
    switchWorkspace,
    switchConversation,
    createWorkspace,
    createConversation
  };
}

function toConversation(summary: ConversationSummary): Conversation {
  return {
    conversation_id: summary.conversation_id,
    title: summary.title,
    conversation_type: summary.conversation_type,
    created_at: summary.created_at
  };
}

function toConversationSummary(conversation: Conversation): ConversationSummary {
  const createdAt = conversation.created_at || new Date().toISOString();
  return {
    conversation_id: conversation.conversation_id,
    title: conversation.title,
    conversation_type: conversation.conversation_type || "WORKSPACE_CHAT",
    status: "ACTIVE",
    active_head_message_id: null,
    created_at: createdAt,
    last_active_at: createdAt
  };
}
