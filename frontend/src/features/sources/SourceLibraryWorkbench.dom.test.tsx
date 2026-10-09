// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../shared/api/error";
import { SourceLibraryWorkbench } from "./SourceLibraryWorkbench";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("SourceLibraryWorkbench", () => {
  it("shows where each source is in the parse, chunk, embed and index pipeline", () => {
    render(<SourceLibraryWorkbench {...buildProps()} />);

    expect(screen.getByRole("heading", { name: "资料库" })).toBeTruthy();
    expect(rowOf("架构说明.pdf").textContent).toContain("PDF · 12 页 · 48 个切片");
    expect(rowOf("架构说明.pdf").textContent).toContain("可检索");
    expect(rowOf("本地资料.md").textContent).toContain("仅可阅读");
    expect(rowOf("调研记录.md").textContent).toContain("切片中");
    expect(rowOf("访谈转写.txt").textContent).toContain("向量化中");
    expect(rowOf("旧版纪要.pdf").textContent).toContain("向量化失败，稍后自动重试");
    // 失败的资料单独出现在筛选里
    expect(screen.getByRole("button", { name: "失败 1" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "处理中 2" }));
    expect(screen.queryByText("架构说明.pdf")).toBeNull();
    expect(screen.getByText("访谈转写.txt")).toBeTruthy();
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

  it("opens the processing record of a failed source and reprocesses it from the embedding stage", async () => {
    const refreshSources = vi.fn(async () => undefined);
    const reprocessSource = vi.fn(async (sourceId: string) => ({
      source_id: sourceId, task_id: "task-retry", restart_from: "INDEX"
    }));
    const loadTask = vi.fn(async (taskId: string) => ({
      task: { task_id: taskId, task_type: "SOURCE_PARSE", task_status: "FAILED", progress_phase: "INDEX_FAILED",
        progress_message: "", result_ref: "", error_message: "EMBEDDING_REQUEST_FAILED: 向量服务超时",
        target_type: "SOURCE", target_id: "failed-1" },
      events: [
        event("e1", "TASK_CREATED", "2026-08-12T12:00:00Z"),
        { ...event("e1p", "TASK_PROGRESS", "2026-08-12T12:00:05Z"), message: "切片完成，共 31 个片段，等待向量化" },
        event("e2", "TASK_FAILED", "2026-08-12T12:00:12Z", { error_code: "EMBEDDING_REQUEST_FAILED", retryable: true }),
        event("e3", "TASK_REDRIVEN", "2026-08-12T12:00:20Z"),
        event("e4", "TASK_HEARTBEAT", "2026-08-12T12:00:25Z")
      ],
      terminalRefetched: true
    }));
    render(<SourceLibraryWorkbench {...buildProps({ refreshSources, reprocessSource, loadTask })} />);

    fireEvent.click(within(rowOf("旧版纪要.pdf")).getByRole("button", { expanded: false }));
    expect(loadTask).toHaveBeenCalledWith("task-failed-1");
    const record = await screen.findByRole("region", { name: "处理记录" });
    expect(within(record).getByText("已加入处理队列")).toBeTruthy();
    // 阶段进度直接展示后端写入的说明
    expect(within(record).getByText("切片完成，共 31 个片段，等待向量化")).toBeTruthy();
    expect(within(record).getByText("EMBEDDING_REQUEST_FAILED · 可自动重试")).toBeTruthy();
    expect(within(record).getByText("重新投递 1 次")).toBeTruthy();
    expect(within(record).getByText("耗时 20 秒")).toBeTruthy();
    // 心跳事件不进入处理记录
    expect(within(record).getAllByRole("listitem")).toHaveLength(4);
    expect(screen.getByText(/向量服务超时/)).toBeTruthy();
    // 还会自动重试时说明重试时间，也可以立即重新处理
    expect(screen.getByText(/分钟后自动重试（已自动重试 1 次，最多 3 次）/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "重新处理" }));
    expect(await screen.findByText("已从向量化阶段重新开始，已有的切片保留。")).toBeTruthy();
    expect(reprocessSource).toHaveBeenCalledWith("failed-1");
    expect(refreshSources).toHaveBeenCalled();
  });

  it("explains a missing edit permission when reprocessing", async () => {
    const reprocessSource = vi.fn(async () => {
      throw new ApiError("forbidden", 403, "FORBIDDEN", "", "");
    });
    render(<SourceLibraryWorkbench {...buildProps({ reprocessSource })} />);

    fireEvent.click(within(rowOf("旧版纪要.pdf")).getByRole("button", { expanded: false }));
    fireEvent.click(screen.getByRole("button", { name: "重新处理" }));
    expect(await screen.findByText("你没有编辑这份资料的权限。")).toBeTruthy();
  });

  it("restarts a failed parse from the parse stage", async () => {
    const reprocessSource = vi.fn(async (sourceId: string) => ({
      source_id: sourceId, task_id: "task-reparse", restart_from: "PARSE"
    }));
    render(<SourceLibraryWorkbench {...buildProps({
      reprocessSource,
      sources: [{ ...source("broken-1", "扫描件.pdf", "FAILED", "FAILED", "PENDING"), task_id: null }]
    })} />);

    fireEvent.click(within(rowOf("扫描件.pdf")).getByRole("button", { expanded: false }));
    expect(rowOf("扫描件.pdf").textContent).toContain("解析失败");
    expect(screen.getByText(/重新处理会从解析阶段开始/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "重新处理" }));
    expect(await screen.findByText("已从解析阶段重新开始处理。")).toBeTruthy();
  });

  it("shows chunked upload progress and lets a failed upload be dismissed", () => {
    const dismissUpload = vi.fn();
    render(<SourceLibraryWorkbench {...buildProps({
      dismissUpload,
      uploads: [
        { id: "u1", fileName: "评测报告.pdf", totalBytes: 20 * 1024 * 1024, uploadedBytes: 8 * 1024 * 1024,
          uploadedChunks: 1, totalChunks: 3, state: "uploading", error: "" },
        { id: "u2", fileName: "损坏.pdf", totalBytes: 1024, uploadedBytes: 0,
          uploadedChunks: 0, totalChunks: 1, state: "failed", error: "网络中断" }
      ]
    })} />);

    const uploads = screen.getByRole("list", { name: "正在上传" });
    expect(within(uploads).getByText("上传中 · 第 2 / 3 片 · 8.0 MB / 20.0 MB")).toBeTruthy();
    expect(within(uploads).getByRole("progressbar", { name: "评测报告.pdf 上传进度" }).getAttribute("aria-valuenow")).toBe("40");
    expect(within(uploads).getByText("上传失败：网络中断")).toBeTruthy();
    fireEvent.click(within(uploads).getByRole("button", { name: "移除 损坏.pdf" }));
    expect(dismissUpload).toHaveBeenCalledWith("u2");
  });

  it("refreshes the list while sources are still processing", () => {
    vi.useFakeTimers();
    const refreshSources = vi.fn(async () => undefined);
    const { rerender } = render(<SourceLibraryWorkbench {...buildProps({ refreshSources })} />);

    act(() => { vi.advanceTimersByTime(4000); });
    expect(refreshSources).toHaveBeenCalledOnce();

    rerender(<SourceLibraryWorkbench {...buildProps({
      refreshSources,
      sources: [source("ready-1", "架构说明.pdf", "READY", "PARSED", "INDEXED")]
    })} />);
    act(() => { vi.advanceTimersByTime(12000); });
    expect(refreshSources).toHaveBeenCalledOnce();
  });

  it("hides empty search controls and keeps upload as the primary task", () => {
    render(<SourceLibraryWorkbench {...buildProps({ sources: [] })} />);

    expect(screen.queryByPlaceholderText("搜索标题")).toBeNull();
    expect(screen.queryByRole("group", { name: "筛选资料状态" })).toBeNull();
    expect(screen.getByRole("button", { name: /拖放或选择文件/ })).toBeTruthy();
  });

  it("accepts audio recordings for transcription", async () => {
    const uploadSourceFile = vi.fn(async () => undefined);
    const { container } = render(<SourceLibraryWorkbench {...buildProps({ uploadSourceFile })} />);
    const fileInput = container.querySelector<HTMLInputElement>(".source-file-input")!;

    fireEvent.change(fileInput, { target: { files: [new File(["ID3"], "周会录音.mp3", { type: "audio/mpeg" })] } });

    await waitFor(() => expect(uploadSourceFile).toHaveBeenCalledOnce());
    expect(screen.getByText("MP3")).toBeTruthy();
  });

  it("rejects unsupported and oversized files before upload", async () => {
    const uploadSourceFile = vi.fn(async () => undefined);
    const { container } = render(<SourceLibraryWorkbench {...buildProps({ uploadSourceFile })} />);
    const fileInput = container.querySelector<HTMLInputElement>(".source-file-input")!;

    fireEvent.change(fileInput, {
      target: { files: [new File(["binary"], "payload.exe", { type: "application/octet-stream" })] }
    });
    expect(screen.getByRole("alert").textContent).toContain("格式不受支持");
    expect(uploadSourceFile).not.toHaveBeenCalled();

    const oversized = new File(["content"], "large.pdf", { type: "application/pdf" });
    Object.defineProperty(oversized, "size", { value: 128 * 1024 * 1024 + 1 });
    fireEvent.change(fileInput, { target: { files: [oversized] } });
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("超过 128 MB"));
    expect(uploadSourceFile).not.toHaveBeenCalled();
  });
});

function rowOf(title: string) {
  const row = screen.getByText(title).closest("article");
  if (!row) throw new Error(`row not found: ${title}`);
  return row as HTMLElement;
}

function event(id: string, eventType: string, createdAt: string, payload: Record<string, unknown> = {}) {
  return { id, event: "task.progress", data: "", message: "", payload, eventType, createdAt };
}

function buildProps(overrides: Record<string, unknown> = {}) {
  return {
    sources: [
      { ...source("ready-1", "架构说明.pdf", "READY", "PARSED", "INDEXED"), chunk_count: 48, page_count: 12 },
      source("local-1", "本地资料.md", "READY", "PARSED", "DISABLED"),
      { ...source("pending-1", "调研记录.md", "PROCESSING", "PENDING", "PENDING"), processing_stage: "CHUNKING" },
      { ...source("indexing-1", "访谈转写.txt", "PROCESSING", "PARSED", "INDEXING"), processing_stage: "EMBEDDING" },
      {
        ...source("failed-1", "旧版纪要.pdf", "FAILED", "PARSED", "FAILED"), processing_stage: "EMBEDDING",
        index_attempt_count: 1, next_index_retry_at: new Date(Date.now() + 3 * 60_000).toISOString()
      }
    ],
    sourceText: "",
    setSourceText: vi.fn(),
    uploadSource: vi.fn(),
    uploadSourceFile: vi.fn(async () => undefined),
    uploads: [],
    dismissUpload: vi.fn(),
    workspace: { workspace_id: "workspace-1", name: "企业研究工作台", status: "ACTIVE" },
    deleteSource: vi.fn(),
    refreshSources: vi.fn(async () => undefined),
    reprocessSource: vi.fn(),
    loadTask: vi.fn(async () => ({ task: {}, events: [], terminalRefetched: true })),
    sourcesBusy: false,
    ...overrides
  } as any;
}

function source(source_id: string, title: string, status: string, parse_status: string, index_status: string) {
  return {
    source_id,
    title,
    source_type: "USER_UPLOAD",
    status,
    parse_status,
    index_status,
    generated_by: "",
    generated_ref_id: "",
    updated_at: "2026-08-12T12:00:00Z",
    task_id: `task-${source_id}`
  };
}
