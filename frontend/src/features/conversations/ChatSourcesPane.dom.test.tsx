// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ChatSourcesPane } from "./ChatSourcesPane";

afterEach(cleanup);

describe("ChatSourcesPane", () => {
  it("shows an empty state and opens the library", () => {
    const onOpenSourceLibrary = vi.fn();
    render(<ChatSourcesPane {...buildProps({ onOpenSourceLibrary })} />);

    expect(screen.getByText("还没有资料")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "打开资料库" }));
    expect(onOpenSourceLibrary).toHaveBeenCalledTimes(1);
  });

  it("lists every source with its status and only lets searchable ones join QA", () => {
    render(<ChatSourcesPane {...buildProps({
      sources: [
        source("ready-1", "架构说明.pdf", "READY", "INDEXED"),
        source("local-1", "本地资料.md", "READY", "DISABLED"),
        source("pending-1", "调研记录.md", "PROCESSING", "PENDING")
      ]
    })} />);

    expect(screen.getByText("架构说明.pdf")).toBeTruthy();
    expect(screen.getByText("已解析 · 未建立检索索引")).toBeTruthy();
    expect(screen.getByRole("checkbox", { name: "在问答中使用 架构说明.pdf" })).toBeTruthy();
    expect(screen.queryByRole("checkbox", { name: "在问答中使用 本地资料.md" })).toBeNull();
    expect(screen.getByText("仅阅读")).toBeTruthy();
    expect(screen.getByText("处理中")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /删除资料/ })).toBeNull();
  });

  it("keeps at least one source selected and collapses a full selection back to all", () => {
    const onChangeSelectedSourceIds = vi.fn();
    const sources = [
      source("a", "A.pdf", "READY", "INDEXED"),
      source("b", "B.pdf", "READY", "INDEXED")
    ];
    const { rerender } = render(<ChatSourcesPane {...buildProps({ sources, selectedSourceIds: ["a"], onChangeSelectedSourceIds })} />);

    fireEvent.click(screen.getByRole("checkbox", { name: "在问答中使用 A.pdf" }));
    expect(onChangeSelectedSourceIds).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("checkbox", { name: "在问答中使用 B.pdf" }));
    expect(onChangeSelectedSourceIds).toHaveBeenCalledWith([]);

    rerender(<ChatSourcesPane {...buildProps({ sources, selectedSourceIds: ["a"], onChangeSelectedSourceIds })} />);
    fireEvent.click(screen.getByRole("checkbox", { name: "选择全部来源" }));
    expect(onChangeSelectedSourceIds).toHaveBeenLastCalledWith([]);
  });

  it("validates files before uploading from the pane", async () => {
    const uploadSourceFile = vi.fn(async () => undefined);
    const { container } = render(<ChatSourcesPane {...buildProps({ uploadSourceFile })} />);
    const input = container.querySelector<HTMLInputElement>(".source-file-input")!;

    fireEvent.change(input, { target: { files: [new File(["x"], "图片.png", { type: "image/png" })] } });
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(uploadSourceFile).not.toHaveBeenCalled();

    const markdown = new File(["# 标题"], "笔记.md", { type: "text/markdown" });
    fireEvent.change(input, { target: { files: [markdown] } });
    await waitFor(() => expect(uploadSourceFile).toHaveBeenCalledWith(markdown));
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
