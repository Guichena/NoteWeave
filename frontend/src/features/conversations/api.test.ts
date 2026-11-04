import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "../../shared/api";
import { ConversationsApi } from "./api";

describe("ConversationsApi", () => {
  it("owns the workspace conversation creation contract", async () => {
    const post = vi.fn(async () => ({ conversation_id: "conversation" }));
    const api = new ConversationsApi({ post } as unknown as ApiClient);
    const input = { title: "Default", conversation_type: "WORKSPACE_CHAT" };

    await api.create("workspace", input);

    expect(post).toHaveBeenCalledWith(
      "/api/v2/workspaces/workspace/conversations",
      input
    );
  });

  it("owns conversation recovery endpoints", async () => {
    const get = vi.fn(async () => []);
    const api = new ConversationsApi({ get } as unknown as ApiClient);

    await api.list("workspace");
    await api.listMessages("workspace", "conversation", 12, 50);

    expect(get).toHaveBeenNthCalledWith(1, "/api/v2/workspaces/workspace/conversations");
    expect(get).toHaveBeenNthCalledWith(
      2,
      "/api/v2/workspaces/workspace/conversations/conversation/messages?after_seq=12&limit=50"
    );
  });

  it("renames and deletes a conversation through workspace scoped routes", async () => {
    const patch = vi.fn(async () => ({ conversation_id: "conversation", title: "新标题" }));
    const deleteJson = vi.fn(async () => ({ conversation_id: "conversation", status: "DELETED", deleted: true }));
    const api = new ConversationsApi({ patch, deleteJson } as unknown as ApiClient);

    await api.rename("workspace", "conversation", "新标题");
    await api.remove("workspace", "conversation");

    expect(patch).toHaveBeenCalledWith(
      "/api/v2/workspaces/workspace/conversations/conversation",
      { title: "新标题" }
    );
    expect(deleteJson).toHaveBeenCalledWith("/api/v2/workspaces/workspace/conversations/conversation");
  });
});
