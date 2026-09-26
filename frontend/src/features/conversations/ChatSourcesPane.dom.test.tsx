// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ChatSourcesPane } from "./ChatSourcesPane";

afterEach(cleanup);

describe("ChatSourcesPane workspace summary", () => {
  it("shows workspace ownership and opens the dedicated library", () => {
    const onOpenSourceLibrary = vi.fn();
    render(<ChatSourcesPane {...buildProps({ onOpenSourceLibrary })} />);

    expect(screen.getAllByText("企业研究工作台").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("3 个会话、Research 与 Wiki 共用")).toBeTruthy();
    expect(screen.getByText("还没有资料")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "打开资料库" }));
    expect(onOpenSourceLibrary).toHaveBeenCalledTimes(1);
  });

  it("summarizes searchable, readable-only and processing assets", () => {
    render(<ChatSourcesPane {...buildProps({
      sources: [
        source("ready-1", "架构说明.pdf", "READY", "INDEXED"),
        source("local-1", "本地资料.md", "READY", "DISABLED"),
        source("pending-1", "调研记录.md", "PROCESSING", "PENDING")
      ]
    })} />);

    expect(screen.getByText("可检索").nextElementSibling?.textContent).toBe("1");
    expect(screen.getByText("仅可阅读").nextElementSibling?.textContent).toBe("1");
    expect(screen.getByText("处理中").nextElementSibling?.textContent).toBe("1");
    expect(screen.getByText("已解析 · 未建立检索索引")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /删除资料/ })).toBeNull();
  });
});

function source(source_id: string, title: string, status: string, index_status: string) {
  return { source_id, title, status, index_status, parse_status: status, source_type: "FILE", generated_by: "", generated_ref_id: "", updated_at: "" };
}

function buildProps(overrides: Record<string, unknown> = {}) {
  return {
    sources: [],
    workspace: { workspace_id: "workspace-1", name: "企业研究工作台", status: "ACTIVE" },
    conversationCount: 3,
    onOpenSourceLibrary: vi.fn(),
    ...overrides
  } as any;
}
