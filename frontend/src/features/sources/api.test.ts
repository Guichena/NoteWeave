import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "../../shared/api";
import { SourcesApi } from "./api";

describe("SourcesApi", () => {
  it("owns create, binary chunk and complete upload ordering", async () => {
    const calls: string[] = [];
    const post = vi.fn(async (path: string) => {
      calls.push(`post:${path}`);
      if (path.endsWith("/uploads")) {
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
