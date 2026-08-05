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
});
