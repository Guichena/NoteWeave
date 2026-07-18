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
});
