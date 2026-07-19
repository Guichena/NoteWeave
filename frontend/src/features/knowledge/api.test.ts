import { describe, expect, it, vi } from "vitest";
import { type ApiClient } from "../../shared/api";
import { KnowledgeApi } from "./api";

describe("KnowledgeApi", () => {
  it("owns workspace read paths and encodes search input", async () => {
    const get = vi.fn(async (_path: string) => ({}));
    const api = new KnowledgeApi({ get } as unknown as ApiClient);

    await api.getSettings("workspace");
    await api.getHome("workspace");
    await api.search("workspace", "图谱 / 版本");
    await api.getIssues("workspace", {
      issueType: "BROKEN_LINK",
      severity: "HIGH",
      scope: "AUTO",
      page: "CURRENT",
      selectedItemId: "item"
    });
    await api.getLog("workspace", "item / 1");

    expect(get.mock.calls.map(([path]) => path)).toEqual([
      "/api/v2/workspaces/workspace/wiki-settings",
      "/api/v2/workspaces/workspace/wiki-home",
      "/api/v2/workspaces/workspace/wiki-search?q=%E5%9B%BE%E8%B0%B1+%2F+%E7%89%88%E6%9C%AC",
      "/api/v2/workspaces/workspace/wiki-issues?issue_type=BROKEN_LINK&severity=HIGH&auto_fixable=true&item_id=item",
      "/api/v2/workspaces/workspace/wiki-log?item_id=item+%2F+1"
    ]);
  });

  it("owns graph budgets and repeated kind filters", async () => {
    const get = vi.fn(async (_path: string) => ({}));
    const api = new KnowledgeApi({ get } as unknown as ApiClient);

    await api.getGraph("workspace", {
      mode: "ego",
      selectedItemId: "center",
      graphKinds: ["CONCEPT", "TOPIC"]
    });
    await api.getGraph("workspace", { mode: "overview" });

    expect(get).toHaveBeenNthCalledWith(
      1,
      "/api/v2/workspaces/workspace/wiki-graph?mode=ego&center=center&depth=1&limit=12&kinds=CONCEPT&kinds=TOPIC"
    );
    expect(get).toHaveBeenNthCalledWith(
      2,
      "/api/v2/workspaces/workspace/wiki-graph?mode=overview&limit=24"
    );
  });

  it("passes cancellation through every read protocol", async () => {
    const get = vi.fn(async (_path: string, _init?: RequestInit) => ({}));
    const api = new KnowledgeApi({ get } as unknown as ApiClient);
    const controller = new AbortController();

    await api.getHome("workspace", { signal: controller.signal });
    await api.search("workspace", "keyword", { signal: controller.signal });
    await api.getGraph("workspace", {}, { signal: controller.signal });
    await api.getItem("item", { signal: controller.signal });

    expect(get.mock.calls.every(([, init]) => init?.signal === controller.signal)).toBe(true);
  });

  it("owns item, version and governance mutation paths", async () => {
    const post = vi.fn(async () => ({}));
    const put = vi.fn(async () => ({}));
    const patch = vi.fn(async () => ({}));
    const deleteRequest = vi.fn(async () => undefined);
    const api = new KnowledgeApi({
      post,
      put,
      patch,
      delete: deleteRequest
    } as unknown as ApiClient);

    await api.updateSettings("workspace", true);
    await api.createItem("workspace", {
      item_type: "WIKI",
      title: "标题",
      content: "正文",
      source_message_id: null
    });
    await api.appendVersion("item", { content: "新正文", source_message_id: null });
    await api.renameItem("item", "新标题");
    await api.deleteItem("item");
    await api.rebuildLinks("workspace");
    await api.rebuild("workspace");
    await api.autoFix("workspace");

    expect(put).toHaveBeenCalledWith("/api/v2/workspaces/workspace/wiki-settings", { wiki_enabled: true });
    expect(post).toHaveBeenCalledWith("/api/v2/knowledge-items/item/versions", {
      content: "新正文",
      source_message_id: null
    });
    expect(patch).toHaveBeenCalledWith("/api/v2/knowledge-items/item/title", { title: "新标题" });
    expect(deleteRequest).toHaveBeenCalledWith("/api/v2/knowledge-items/item");
    expect(post).toHaveBeenCalledWith("/api/v2/workspaces/workspace/wiki/rebuild-links", {});
    expect(post).toHaveBeenCalledWith("/api/v2/workspaces/workspace/wiki/rebuild", {});
    expect(post).toHaveBeenCalledWith("/api/v2/workspaces/workspace/wiki/auto-fix", {});
  });
});
