// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SidebarConversationItem } from "./SidebarConversationItem";

afterEach(cleanup);

function renderItem(overrides: Partial<Parameters<typeof SidebarConversationItem>[0]> = {}) {
  const props = {
    conversationId: "c1",
    title: "会话 A",
    meta: "刚刚",
    active: true,
    disabled: false,
    onOpen: vi.fn(),
    onRename: vi.fn(async () => true),
    onDelete: vi.fn(async () => true),
    ...overrides
  };
  render(<SidebarConversationItem {...props} />);
  return props;
}

describe("SidebarConversationItem", () => {
  it("opens the conversation on click", () => {
    const props = renderItem();
    fireEvent.click(screen.getByRole("button", { name: "会话 A 刚刚" }));
    expect(props.onOpen).toHaveBeenCalled();
  });

  it("renames inline and submits the trimmed title", async () => {
    const props = renderItem();
    fireEvent.click(screen.getByRole("button", { name: "重命名会话：会话 A" }));
    const input = screen.getByRole("textbox", { name: "会话名称" });
    fireEvent.change(input, { target: { value: "  新名字 " } });
    fireEvent.click(screen.getByRole("button", { name: "保存会话名称" }));
    await waitFor(() => expect(props.onRename).toHaveBeenCalledWith("c1", "新名字"));
    await waitFor(() => expect(screen.queryByRole("textbox", { name: "会话名称" })).toBeNull());
  });

  it("leaves rename mode with Escape without calling the API", () => {
    const props = renderItem();
    fireEvent.click(screen.getByRole("button", { name: "重命名会话：会话 A" }));
    fireEvent.keyDown(screen.getByRole("textbox", { name: "会话名称" }), { key: "Escape" });
    expect(props.onRename).not.toHaveBeenCalled();
    expect(screen.queryByRole("textbox", { name: "会话名称" })).toBeNull();
  });

  it("asks for confirmation before deleting", async () => {
    const props = renderItem();
    fireEvent.click(screen.getByRole("button", { name: "删除会话：会话 A" }));
    expect(props.onDelete).not.toHaveBeenCalled();
    expect(screen.getByText("删除「会话 A」？")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "确认删除会话" }));
    await waitFor(() => expect(props.onDelete).toHaveBeenCalledWith("c1"));
  });

  it("can cancel a pending delete", () => {
    const props = renderItem();
    fireEvent.click(screen.getByRole("button", { name: "删除会话：会话 A" }));
    fireEvent.click(screen.getByRole("button", { name: "取消删除" }));
    expect(props.onDelete).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "删除会话：会话 A" })).toBeTruthy();
  });

  it("hides the actions when no handlers are given", () => {
    renderItem({ onRename: undefined, onDelete: undefined });
    expect(screen.queryByRole("button", { name: "删除会话：会话 A" })).toBeNull();
  });
});
