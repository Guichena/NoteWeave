// @vitest-environment jsdom

import { createHash } from "node:crypto";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "../../shared/api";
import { SourcesApi, type SourceUploadProgress } from "./api";
import { readUploadResume, saveUploadResume } from "./uploadResume";

const COMPLETED = { source_id: "source", task_id: "task", parse_status: "PENDING", index_status: "PENDING" };

function pdf(content = "%PDF-1.7 正文") {
  return new File([content], "报告.pdf", { type: "application/pdf" });
}

function digest(content = "%PDF-1.7 正文") {
  return createHash("sha256").update(content).digest("hex");
}

describe("SourcesApi 秒传与断点续传", () => {
  afterEach(() => window.localStorage.clear());

  it("服务端已有同样的文件时直接返回资料，不上传分片", async () => {
    const post = vi.fn(async () => ({ upload_id: "u", status: "COMPLETED", chunk_size: 1, total_chunks: 1,
      instant: true, source_id: "existing", task_id: "" }));
    const raw = vi.fn();
    const api = new SourcesApi({ post, raw } as unknown as ApiClient);

    const result = await api.uploadFile("ws", pdf());

    expect(result).toMatchObject({ source_id: "existing", task_id: "", instant: true });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]).toEqual(["/api/v2/workspaces/ws/uploads", expect.objectContaining({ sha256: digest() })]);
    expect(raw).not.toHaveBeenCalled();
    expect(readUploadResume("ws", digest(), pdf().size)).toBeNull();
  });

  it("上传中断后保留续传记录，再次上传只补传服务端缺少的分片", async () => {
    const file = pdf();
    saveUploadResume("ws", digest(), { upload_id: "old", chunk_size: 4, total_chunks: Math.ceil(file.size / 4), file_size: file.size });
    const totalChunks = Math.ceil(file.size / 4);
    const get = vi.fn(async () => ({
      upload_id: "old", status: "UPLOADING", total_chunks: totalChunks, chunk_size: 4, uploaded_chunk_indexes: [0, 1]
    }));
    const post = vi.fn(async () => COMPLETED);
    const raw = vi.fn(async (_path: string, _init: RequestInit) => new Response(null, { status: 200 }));
    const api = new SourcesApi({ get, post, raw } as unknown as ApiClient);
    const progress: SourceUploadProgress[] = [];

    const result = await api.uploadFile("ws", file, (item) => progress.push(item));

    expect(get).toHaveBeenCalledWith("/api/v2/uploads/old");
    expect(raw.mock.calls.map(([path]) => path)).toEqual(
      Array.from({ length: totalChunks - 2 }, (_, index) => `/api/v2/uploads/old/chunks/${index + 2}`)
    );
    expect(post).toHaveBeenCalledWith("/api/v2/uploads/old/complete", {});
    expect(result).toMatchObject({ source_id: "source", instant: false, resumed_chunks: 2 });
    const uploading = progress.filter((item) => item.phase === "uploading");
    expect(uploading[0]).toMatchObject({ uploadedChunks: 2, uploadedBytes: 8, resumedChunks: 2 });
    expect(uploading.at(-1)).toMatchObject({ uploadedChunks: totalChunks, uploadedBytes: file.size });
    expect(progress[0].phase).toBe("hashing");
    expect(readUploadResume("ws", digest(), file.size)).toBeNull();
  });

  it("续传会话已失效时丢弃记录并重新上传", async () => {
    const file = pdf();
    saveUploadResume("ws", digest(), { upload_id: "expired", chunk_size: 8, total_chunks: 2, file_size: file.size });
    const get = vi.fn(async () => { throw new Error("UPLOAD_NOT_FOUND"); });
    const post = vi.fn(async (path: string) => path.endsWith("/uploads")
      ? { upload_id: "fresh", status: "UPLOADING", chunk_size: file.size, total_chunks: 1 }
      : COMPLETED);
    const raw = vi.fn(async (_path: string, _init: RequestInit) => new Response(null, { status: 200 }));
    const api = new SourcesApi({ get, post, raw } as unknown as ApiClient);

    await api.uploadFile("ws", file);

    expect(raw).toHaveBeenCalledWith("/api/v2/uploads/fresh/chunks/0", expect.anything());
    expect(post).toHaveBeenLastCalledWith("/api/v2/uploads/fresh/complete", {});
  });

  it("分片上传失败时保留记录，供刷新后续传", async () => {
    const file = pdf();
    const post = vi.fn(async () => ({ upload_id: "u1", status: "UPLOADING", chunk_size: file.size, total_chunks: 1 }));
    const raw = vi.fn(async () => { throw new Error("网络中断"); });
    const api = new SourcesApi({ post, raw } as unknown as ApiClient);

    await expect(api.uploadFile("ws", file)).rejects.toThrow("网络中断");
    expect(readUploadResume("ws", digest(), file.size)).toMatchObject({ upload_id: "u1", total_chunks: 1 });
  });

  it("按后端返回的格式列表校验文件", async () => {
    const api = new SourcesApi({} as unknown as ApiClient);
    const docx = new File(["x"], "合同.docx");
    await expect(api.uploadFile("ws", docx)).rejects.toThrow(/格式不受支持/);
  });
});
