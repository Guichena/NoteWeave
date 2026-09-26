// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SourceLibraryWorkbench } from "./SourceLibraryWorkbench";

afterEach(cleanup);

describe("SourceLibraryWorkbench", () => {
  it("separates parsed sources from indexed sources", () => {
    render(<SourceLibraryWorkbench {...buildProps()} />);

    expect(screen.getByRole("heading", { name: "资料库" })).toBeTruthy();
    expect(screen.getByText("可参与 Chat / RAG").nextElementSibling?.textContent).toBe("1");
    expect(screen.getByText("可用于 Research").nextElementSibling?.textContent).toBe("2");
    expect(screen.getAllByText("已解析").length).toBeGreaterThan(0);
    expect(screen.getByText("未建立检索索引")).toBeTruthy();
  });

  it("filters sources and requires deletion confirmation", () => {
    const deleteSource = vi.fn();
    render(<SourceLibraryWorkbench {...buildProps({ deleteSource })} />);

    fireEvent.change(screen.getByPlaceholderText("搜索标题"), { target: { value: "本地" } });
    expect(screen.getByText("本地资料.md")).toBeTruthy();
    expect(screen.queryByText("架构说明.pdf")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "删除资料 本地资料.md" }));
    expect(deleteSource).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "确认删除" }));
    expect(deleteSource).toHaveBeenCalledWith(expect.objectContaining({ source_id: "local-1" }));
  });

  it("hides empty search controls and keeps upload as the primary task", () => {
    render(<SourceLibraryWorkbench {...buildProps({ sources: [] })} />);

    expect(screen.queryByPlaceholderText("搜索标题")).toBeNull();
    expect(screen.queryByRole("group", { name: "筛选资料状态" })).toBeNull();
    expect(screen.getByRole("button", { name: "选择第一份资料" })).toBeTruthy();
  });

  it("rejects unsupported and oversized files before upload", () => {
    const uploadSourceFile = vi.fn(async () => undefined);
    const { container } = render(<SourceLibraryWorkbench {...buildProps({ uploadSourceFile })} />);
    const fileInput = container.querySelector<HTMLInputElement>('.source-file-input')!;

    fireEvent.change(fileInput, {
      target: { files: [new File(["binary"], "payload.exe", { type: "application/octet-stream" })] }
    });
    expect(screen.getByRole("alert").textContent).toContain("格式不受支持");
    expect(uploadSourceFile).not.toHaveBeenCalled();

    const oversized = new File(["content"], "large.pdf", { type: "application/pdf" });
    Object.defineProperty(oversized, "size", { value: 128 * 1024 * 1024 + 1 });
    fireEvent.change(fileInput, { target: { files: [oversized] } });
    expect(screen.getByRole("alert").textContent).toContain("超过 128 MB");
    expect(uploadSourceFile).not.toHaveBeenCalled();
  });
});

function buildProps(overrides: Record<string, unknown> = {}) {
  return {
    sources: [
      source("ready-1", "架构说明.pdf", "READY", "INDEXED"),
      source("local-1", "本地资料.md", "READY", "DISABLED"),
      source("pending-1", "调研记录.md", "PROCESSING", "PENDING")
    ],
    sourceText: "",
    setSourceText: vi.fn(),
    uploadSource: vi.fn(),
    uploadSourceFile: vi.fn(async () => undefined),
    workspace: { workspace_id: "workspace-1", name: "企业研究工作台", status: "ACTIVE" },
    conversationCount: 3,
    latestTask: null,
    latestWorkspaceTaskWaitSignals: [],
    latestWorkspaceTaskWaitDetails: [],
    latestWorkspaceProgressEvent: null,
    taskEvents: [],
    deleteSource: vi.fn(),
    buildSourceOriginBadge: vi.fn(() => "Research 生成"),
    buildGenericTaskRuntimeSnapshot: vi.fn(() => ""),
    buildTaskEventNarrative: vi.fn(() => ""),
    sourcesBusy: false,
    ...overrides
  } as any;
}

function source(source_id: string, title: string, status: string, index_status: string) {
  return {
    source_id,
    title,
    source_type: "FILE",
    status,
    parse_status: status,
    index_status,
    generated_by: "",
    generated_ref_id: "",
    updated_at: "2026-08-12T12:00:00Z"
  };
}
