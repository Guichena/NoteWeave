import { describe, expect, it, vi } from "vitest";
import { WorkspaceSessionLoader } from "./session";

describe("WorkspaceSessionLoader", () => {
  it("restores the latest conversation, message history, and sources", async () => {
    const port = createPort({
      conversations: [conversation("latest"), conversation("older")],
      messages: [message(1, "USER", "question"), message(2, "ASSISTANT", "answer")],
      sources: [{ source_id: "source-1" }]
    });
    const loader = new WorkspaceSessionLoader(port as never);

    await expect(loader.restoreWorkspace(workspace("workspace"))).resolves.toMatchObject({
      conversation: { conversation_id: "latest" },
      messages: [
        { role: "user", content: "question" },
        { role: "assistant", content: "answer" }
      ],
      sources: [{ source_id: "source-1" }]
    });
  });

  it("returns an explicit empty conversation state", async () => {
    const loader = new WorkspaceSessionLoader(createPort() as never);

    await expect(loader.restoreWorkspace(workspace("empty"))).resolves.toMatchObject({
      conversation: null,
      conversations: [],
      messages: []
    });
  });

  it("restores the persisted AnswerRun failure instead of treating current history as completed", async () => {
    const port = createPort({
      conversations: [conversation("failed")],
      messages: [{
        ...message(1, "ASSISTANT", "> **[TEMPLATE PLACEHOLDER]** partial content"),
        context_status: "CURRENT",
        answer_status: "FAILED",
        answer_error: "Answer LLM is not configured"
      }]
    });
    const loader = new WorkspaceSessionLoader(port as never);

    await expect(loader.restoreWorkspace(workspace("workspace"))).resolves.toMatchObject({
      messages: [{
        role: "assistant",
        answerStatus: "FAILED",
        answerError: "Answer LLM is not configured"
      }]
    });
  });

  it("paginates complete message history", async () => {
    const firstPage = Array.from({ length: 200 }, (_, index) => message(index + 1));
    const listMessages = vi.fn()
      .mockResolvedValueOnce(firstPage)
      .mockResolvedValueOnce([message(201)]);
    const port = createPort({ conversations: [conversation("conversation")], listMessages });
    const loader = new WorkspaceSessionLoader(port as never);

    const restored = await loader.restoreWorkspace(workspace("workspace"));

    expect(restored?.messages).toHaveLength(201);
    expect(listMessages).toHaveBeenNthCalledWith(1, "workspace", "conversation", 0, 200);
    expect(listMessages).toHaveBeenNthCalledWith(2, "workspace", "conversation", 200, 200);
  });

  it("discards a slower restore after a newer request wins", async () => {
    let releaseSlow: (value: unknown[]) => void = () => undefined;
    const slowConversations = new Promise<unknown[]>((resolve) => { releaseSlow = resolve; });
    const listConversations = vi.fn((workspaceId: string) => workspaceId === "slow"
      ? slowConversations
      : Promise.resolve([]));
    const loader = new WorkspaceSessionLoader(createPort({ listConversations }) as never);

    const slow = loader.restoreWorkspace(workspace("slow"));
    const fast = loader.restoreWorkspace(workspace("fast"));
    releaseSlow([]);

    await expect(fast).resolves.toMatchObject({ workspace: { workspace_id: "fast" } });
    await expect(slow).resolves.toBeNull();
  });
});

function workspace(workspaceId: string) {
  return { workspace_id: workspaceId, name: workspaceId, status: "ACTIVE" };
}

function conversation(conversationId: string) {
  return {
    conversation_id: conversationId,
    title: conversationId,
    conversation_type: "WORKSPACE_CHAT",
    status: "ACTIVE",
    active_head_message_id: null,
    created_at: "2026-07-20T00:00:00Z",
    last_active_at: "2026-07-20T00:00:00Z"
  };
}

function message(messageSeq: number, role = "SYSTEM", content = `message-${messageSeq}`) {
  return {
    message_id: `message-${messageSeq}`,
    message_seq: messageSeq,
    role,
    requested_turn_mode: null,
    content,
    reply_to_message_id: null,
    context_status: null,
    content_hash: null,
    created_at: "2026-07-20T00:00:00Z"
  };
}

function createPort(overrides: Record<string, unknown> = {}) {
  return {
    listWorkspaces: vi.fn(async () => []),
    listConversations: vi.fn(async () => overrides.conversations ?? []),
    listMessages: overrides.listMessages ?? vi.fn(async () => overrides.messages ?? []),
    listSources: vi.fn(async () => overrides.sources ?? []),
    ...overrides
  };
}
