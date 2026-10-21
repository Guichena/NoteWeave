import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "../../shared/api";
import { WorkspaceApi } from "./api";

describe("WorkspaceApi", () => {
  it("lists workspaces visible to the current session", async () => {
    const get = vi.fn(async () => [{ workspace_id: "workspace" }]);
    const api = new WorkspaceApi({ get } as unknown as ApiClient);

    await expect(api.list()).resolves.toEqual([{ workspace_id: "workspace" }]);
    expect(get).toHaveBeenCalledWith("/api/v2/workspaces");
  });

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

  it("owns member administration contracts", async () => {
    const get = vi.fn(async () => []);
    const put = vi.fn(async () => ({}));
    const remove = vi.fn(async () => undefined);
    const api = new WorkspaceApi({ get, put, delete: remove } as unknown as ApiClient);

    await api.listMembers("workspace");
    await api.putMember("workspace", "user/id", { role: "EDITOR", status: "ACTIVE" });
    await api.removeMember("workspace", "user/id");

    expect(get).toHaveBeenCalledWith("/api/v2/workspaces/workspace/members");
    expect(put).toHaveBeenCalledWith("/api/v2/workspaces/workspace/members/user%2Fid", {
      role: "EDITOR",
      status: "ACTIVE"
    });
    expect(remove).toHaveBeenCalledWith("/api/v2/workspaces/workspace/members/user%2Fid");
  });
});
