// @vitest-environment jsdom

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { KnowledgeApi } from "./api";
import type { KnowledgeVersionDetail, KnowledgeVersionSummary } from "./model";
import { LatestKnowledgeRequestGate } from "./state";
import { useKnowledgeActions } from "./useKnowledgeActions";

function versionSummary(overrides: Partial<KnowledgeVersionSummary>): KnowledgeVersionSummary {
  return {
    version_id: "version-1",
    version_no: 1,
    summary: "第一版",
    source_message_id: null,
    citation_count: 0,
    created_at: "2026-11-01T00:00:00Z",
    ...overrides
  };
}

function versionDetail(versionId: string, versionNo: number): KnowledgeVersionDetail {
  return {
    version_id: versionId,
    item_id: "item-1",
    version_no: versionNo,
    content: `第 ${versionNo} 版正文`,
    summary: `第 ${versionNo} 版摘要`,
    source_message_id: null,
    citations: [],
    created_at: "2026-11-01T00:00:00Z"
  };
}

describe("useKnowledgeActions.loadVersionById", () => {
  it("按版本 ID 找到版本号后加载那一版，不按标题取最新正文", async () => {
    const versions = [
      versionSummary({ version_id: "version-3", version_no: 3 }),
      versionSummary({ version_id: "version-2", version_no: 2 })
    ];
    const api = {
      getVersions: vi.fn(async () => versions),
      getItem: vi.fn(async () => ({ item_id: "item-1", latest_version_id: "version-3", content: "最新正文" })),
      getVersion: vi.fn(async (_itemId: string, versionNo: number) => versionDetail("version-2", versionNo))
    } as unknown as KnowledgeApi;
    const dispatch = vi.fn();

    const { result } = renderHook(() => useKnowledgeActions({
      workspaceId: "workspace-1",
      api,
      gate: new LatestKnowledgeRequestGate(),
      dispatch,
      selectedItemId: ""
    }));

    await act(async () => {
      await result.current.loadVersionById("item-1", "version-2");
    });

    expect(api.getVersions).toHaveBeenCalledWith("item-1", expect.anything());
    expect(api.getVersion).toHaveBeenCalledWith("item-1", 2, expect.anything());
    expect(dispatch).toHaveBeenCalledWith({
      type: "selection",
      snapshot: expect.objectContaining({ version: expect.objectContaining({ version_id: "version-2", version_no: 2 }) })
    });
  });

  it("版本 ID 不在版本列表里时不做任何替换", async () => {
    const api = {
      getVersions: vi.fn(async () => [versionSummary({ version_id: "version-3", version_no: 3 })]),
      getVersion: vi.fn()
    } as unknown as KnowledgeApi;
    const dispatch = vi.fn();

    const { result } = renderHook(() => useKnowledgeActions({
      workspaceId: "workspace-1",
      api,
      gate: new LatestKnowledgeRequestGate(),
      dispatch,
      selectedItemId: ""
    }));

    await act(async () => {
      await result.current.loadVersionById("item-1", "missing-version");
    });

    expect(api.getVersion).not.toHaveBeenCalled();
    expect(dispatch).not.toHaveBeenCalled();
  });
});