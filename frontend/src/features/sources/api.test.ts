import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "../../shared/api";
import { SourcesApi } from "./api";
import { formatSourceIndexStatus, formatSourceProcessingStatus, isSourceReady } from "./model";

describe("SourcesApi", () => {
  it("owns create, binary chunk and complete upload ordering", async () => {
    const calls: string[] = [];
    const post = vi.fn(async (path: string, body?: unknown) => {
      calls.push(`post:${path}`);
      if (path.endsWith("/uploads")) {
        expect(body).toMatchObject({ file_name: "正文.md" });
        return { upload_id: "upload" };
      }
      return {
        source_id: "source",
        task_id: "task",
        parse_status: "PENDING",
        index_status: "PENDING"
      };
    });
    const raw = vi.fn(async (path: string, init: RequestInit) => {
      calls.push(`raw:${path}`);
      expect(init.method).toBe("PUT");
      expect(init.body).toBeInstanceOf(Uint8Array);
      return new Response(null, { status: 200 });
    });
    const api = new SourcesApi({ post, raw } as unknown as ApiClient);

    await expect(api.uploadText("workspace", "正文"))
      .resolves.toMatchObject({ source_id: "source", task_id: "task" });
    expect(calls).toEqual([
      "post:/api/v2/workspaces/workspace/uploads",
      "raw:/api/v2/uploads/upload/chunks/0",
      "post:/api/v2/uploads/upload/complete"
    ]);
  });

  it("derives a readable pasted-source name from the first meaningful heading", async () => {
    const post = vi.fn(async (path: string, body?: unknown) => path.endsWith("/uploads")
      ? (expect(body).toMatchObject({ file_name: "研究 结论.md" }), { upload_id: "upload" })
      : { source_id: "source", task_id: "task", parse_status: "PENDING", index_status: "PENDING" });
    const raw = vi.fn(async () => new Response(null, { status: 200 }));
    const api = new SourcesApi({ post, raw } as unknown as ApiClient);

    await api.uploadText("workspace", "\n# 研究/结论\n正文");

    expect(post).toHaveBeenCalled();
  });

  it("uploads a PDF with its workspace-scoped metadata", async () => {
    const post = vi.fn(async (path: string) => path.endsWith("/uploads")
      ? { upload_id: "upload-pdf" }
      : { source_id: "source", task_id: "task", parse_status: "PENDING", index_status: "PENDING" });
    const raw = vi.fn(async () => new Response(null, { status: 200 }));
    const api = new SourcesApi({ post, raw } as unknown as ApiClient);
    const file = new File(["%PDF-1.7"], "evidence.pdf", { type: "application/pdf" });

    await api.uploadFile("workspace", file);

    expect(post).toHaveBeenNthCalledWith(1, "/api/v2/workspaces/workspace/uploads", {
      file_name: "evidence.pdf",
      file_size: 8,
      mime_type: "application/pdf",
      chunk_size: 8,
      total_chunks: 1
    });
    expect(raw).toHaveBeenCalledWith("/api/v2/uploads/upload-pdf/chunks/0", expect.objectContaining({
      method: "PUT"
    }));
  });

  it("owns list and delete source paths", async () => {
    const get = vi.fn(async () => []);
    const deleteJson = vi.fn(async () => ({
      source_id: "source",
      status: "DELETED",
      wiki_retract_task_id: ""
    }));
    const api = new SourcesApi({ get, deleteJson } as unknown as ApiClient);

    await api.list("workspace");
    await api.remove("workspace", "source");

    expect(get).toHaveBeenCalledWith("/api/v2/workspaces/workspace/sources");
    expect(deleteJson).toHaveBeenCalledWith("/api/v2/workspaces/workspace/sources/source");
  });

  it("owns note answer draft and save-as-source paths", async () => {
    const post = vi.fn(async (path: string) => {
      if (path.endsWith("/source-draft")) {
        return {
          message_id: "message",
          title: "标题",
          content: "# 标题\n\n中性正文",
          rewrite_mode: "template",
          source_content: "原始回答"
        };
      }
      return {
        source_id: "source",
        message_id: "message",
        title: "标题",
        status: "READY",
        parse_status: "PARSED",
        index_status: "INDEXED",
        generated_by: "note_answer",
        generated_ref_id: "message"
      };
    });
    const api = new SourcesApi({ post } as unknown as ApiClient);

    await api.buildNoteSourceDraft("message", { title: "标题" });
    await api.saveAnswerAsSource("message", {
      title: "标题",
      content: "中性正文"
    });

    expect(post).toHaveBeenCalledWith("/api/v2/messages/message/source-draft", { title: "标题" });
    expect(post).toHaveBeenCalledWith("/api/v2/messages/message/save-as-source", {
      title: "标题",
      content: "中性正文"
    });
  });
});

describe("source status presentation", () => {
  it("only exposes ready sources to downstream artifact generation", () => {
    expect(isSourceReady({ status: "READY" })).toBe(true);
    expect(isSourceReady({ status: "FAILED" })).toBe(false);
    expect(isSourceReady({ status: "PROCESSING" })).toBe(false);
  });

  it("keeps parsed readiness separate from retrieval index readiness", () => {
    expect(formatSourceProcessingStatus("READY")).toBe("已解析");
    expect(formatSourceIndexStatus("INDEXED")).toBe("已建立检索索引");
    expect(formatSourceIndexStatus("DISABLED")).toBe("未建立检索索引");
  });

  it("does not invent a friendly status for unknown backend values", () => {
    expect(formatSourceProcessingStatus("CUSTOM_BACKEND_STATE")).toBe("CUSTOM_BACKEND_STATE");
    expect(formatSourceIndexStatus("CUSTOM_INDEX_STATE")).toBe("CUSTOM_INDEX_STATE");
  });
});
