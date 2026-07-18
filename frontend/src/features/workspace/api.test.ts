import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "../../shared/api";
import { WorkspaceApi } from "./api";

describe("WorkspaceApi", () => {
  it("owns the workspace creation protocol", async () => {
    const post = vi.fn(async () => ({
      workspace_id: "workspace",
      name: "Name",
      status: "ACTIVE"
    }));
    const api = new WorkspaceApi({ post } as unknown as ApiClient);

    await expect(api.create({ name: "Name", description: "Description" }))
      .resolves.toMatchObject({ workspace_id: "workspace" });
    expect(post).toHaveBeenCalledWith("/api/v2/workspaces", {
      name: "Name",
      description: "Description"
    });
  });
});
