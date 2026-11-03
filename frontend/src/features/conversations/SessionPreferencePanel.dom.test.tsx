// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionPreferencePanel } from "./SessionPreferencePanel";

afterEach(cleanup);

function api(
  initial: Array<{ id: string; conversation_id: string; requirement_text: string; status: string }> = [],
  proposals: Array<{ revision_id: string; display_text: string; source_message_ids: string[] }> = [],
  active: Array<{ revision_id: string; display_text: string; source_message_ids: string[] }> = []
) {
  return {
    listSessionPreferences: vi.fn().mockResolvedValue(initial),
    addSessionPreference: vi.fn().mockImplementation((_w: string, _c: string, text: string) =>
      Promise.resolve({
        id: "new-id",
        conversation_id: "c",
        requirement_text: text,
        status: "ACTIVE"
      })
    ),
    removeSessionPreference: vi.fn().mockResolvedValue(undefined),
    listPendingProposals: vi.fn().mockResolvedValue(proposals),
    listActiveMemories: vi.fn().mockResolvedValue(active),
    sessionOnlyRevision: vi.fn().mockResolvedValue({
      id: "session-id",
      conversation_id: "c",
      requirement_text: "候选要求",
      status: "ACTIVE"
    }),
    reviewMemoryProposal: vi.fn().mockResolvedValue(undefined)
  };
}

describe("SessionPreferencePanel", () => {
  it("添加只用于本对话的要求并显示在列表中", async () => {
    const fake = api();
    render(<SessionPreferencePanel workspaceId="w" conversationId="c" api={fake} />);

    fireEvent.change(screen.getByLabelText("会话要求"), { target: { value: "回答只用工作台资料" } });
    fireEvent.click(screen.getByRole("button", { name: "添加" }));

    await waitFor(() => expect(fake.addSessionPreference).toHaveBeenCalledWith("w", "c", "回答只用工作台资料"));
    const list = await screen.findByRole("list", { name: "会话要求列表" });
    expect(within(list).getByText("回答只用工作台资料")).toBeTruthy();
  });

  it("删除会话要求后从列表移除", async () => {
    const fake = api([
      { id: "r1", conversation_id: "c", requirement_text: "旧要求", status: "ACTIVE" }
    ]);
    render(<SessionPreferencePanel workspaceId="w" conversationId="c" api={fake} />);

    const list = await screen.findByRole("list", { name: "会话要求列表" });
    fireEvent.click(within(list).getByRole("button", { name: "删除" }));

    await waitFor(() => expect(fake.removeSessionPreference).toHaveBeenCalledWith("w", "c", "r1"));
    await waitFor(() => expect(screen.queryByText("旧要求")).toBeNull());
  });

  it("待确认候选可以记住", async () => {
    const fake = api([], [
      { revision_id: "rev-1", display_text: "回答用中文", source_message_ids: ["m1"] }
    ]);
    render(<SessionPreferencePanel workspaceId="w" conversationId="c" api={fake} />);

    const list = await screen.findByRole("list", { name: "待确认记忆" });
    fireEvent.click(within(list).getByRole("button", { name: "记住" }));

    await waitFor(() => expect(fake.reviewMemoryProposal).toHaveBeenCalledWith("w", "rev-1", "ACCEPT"));
    await waitFor(() => expect(screen.queryByText("回答用中文")).toBeNull());
  });

  it("待确认候选可以只用于本对话", async () => {
    const fake = api([], [
      { revision_id: "rev-1", display_text: "回答用中文", source_message_ids: ["m1"] }
    ]);
    render(<SessionPreferencePanel workspaceId="w" conversationId="c" api={fake} />);

    const list = await screen.findByRole("list", { name: "待确认记忆" });
    fireEvent.click(within(list).getByRole("button", { name: "只用于本对话" }));

    await waitFor(() => expect(fake.sessionOnlyRevision).toHaveBeenCalledWith("w", "c", "rev-1"));
    const items = await screen.findByRole("list", { name: "会话要求列表" });
    expect(within(items).getByText("候选要求")).toBeTruthy();
  });

  it("已记住的记忆可以撤销", async () => {
    const fake = api([], [], [
      { revision_id: "rev-9", display_text: "记住用中文", source_message_ids: ["m9"] }
    ]);
    render(<SessionPreferencePanel workspaceId="w" conversationId="c" api={fake} />);

    const list = await screen.findByRole("list", { name: "已记住的记忆" });
    fireEvent.click(within(list).getByRole("button", { name: "撤销" }));

    await waitFor(() => expect(fake.reviewMemoryProposal).toHaveBeenCalledWith("w", "rev-9", "REVOKE"));
    await waitFor(() => expect(screen.queryByText("记住用中文")).toBeNull());
  });
});
