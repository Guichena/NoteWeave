// @vitest-environment jsdom
import { renderHook, act } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useKnowledgeActions } from "./useKnowledgeActions";
import { LatestKnowledgeRequestGate } from "./state";
import type { KnowledgeApi } from "./api";

describe("冻结版本打开", () => {
  it("原版本不存在时不选择最新正文", async () => {
    const dispatch = vi.fn();
    const api = { getVersions: vi.fn(async () => []), getItem: vi.fn() } as unknown as KnowledgeApi;
    const { result } = renderHook(() => useKnowledgeActions({ workspaceId: "ws", selectedItemId: "", api, dispatch, gate: new LatestKnowledgeRequestGate() }));
    await act(async () => { expect(await result.current.loadVersionById("item", "gone")).toBeNull(); });
    expect(api.getItem).not.toHaveBeenCalled();
    expect(dispatch).not.toHaveBeenCalled();
  });
  it("目标版本完整读到后仅一次提交该版本，不先发布最新正文", async () => {
    const dispatch = vi.fn();
    const version = { item_id: "item", version_id: "v1", version_no: 1, content: "引用旧正文" };
    const api = { getVersions: vi.fn(async () => [{ version_id: "v1", version_no: 1 }]), getItem: vi.fn(async () => ({ item_id: "item", latest_version_id: "v2", content: "最新正文" })), getVersion: vi.fn(async () => version) } as unknown as KnowledgeApi;
    const { result } = renderHook(() => useKnowledgeActions({ workspaceId: "ws", selectedItemId: "", api, dispatch, gate: new LatestKnowledgeRequestGate() }));
    await act(async () => { await result.current.loadVersionById("item", "v1"); });
    expect(dispatch).toHaveBeenCalledTimes(1);
    expect(dispatch).toHaveBeenCalledWith(expect.objectContaining({ type: "selection", snapshot: expect.objectContaining({ version }) }));
  });
});
