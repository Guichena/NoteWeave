// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useMemoryItems } from "./useMemoryItems";
import type { MemoryApi } from "./api";
import type { MemoryItem } from "./model";

afterEach(cleanup);

it("A 的记忆写入完成不能覆盖已经加载的 B", async () => {
  const promotion = deferred<Awaited<ReturnType<MemoryApi["promoteSignals"]>>>();
  const api = {
    listItems: vi.fn(async (workspace: string) => [{ memory_item_id: `memory-${workspace}` } as MemoryItem]),
    createSignal: vi.fn(async () => ({ signal_id: "signal-A" })),
    promoteSignals: vi.fn(() => promotion.promise)
  } as unknown as MemoryApi;
  const { result, rerender } = renderHook(({ workspace }) => useMemoryItems(workspace, api), { initialProps: { workspace: "A" } });
  await waitFor(() => expect(result.current.items[0]?.memory_item_id).toBe("memory-A"));
  let saving!: ReturnType<typeof result.current.addPreference>;
  act(() => { saving = result.current.addPreference({ text: "简洁", kind: "PREFERENCE", neighborhood: "COMMON" }); });
  await waitFor(() => expect(api.promoteSignals).toHaveBeenCalled());
  rerender({ workspace: "B" });
  await waitFor(() => expect(result.current.items[0]?.memory_item_id).toBe("memory-B"));
  await act(async () => { promotion.resolve({ candidates: [] } as Awaited<ReturnType<MemoryApi["promoteSignals"]>>); await saving; });
  expect(result.current.items[0]?.memory_item_id).toBe("memory-B");
  expect(vi.mocked(api.listItems).mock.calls.filter(([workspace]) => workspace === "A")).toHaveLength(1);
});

it("旧工作台审核完成不能清除新工作台的审核状态", async () => {
  const first = deferred<Awaited<ReturnType<MemoryApi["decideReview"]>>>();
  const second = deferred<Awaited<ReturnType<MemoryApi["decideReview"]>>>();
  const api = { listItems: vi.fn(async () => []),
    decideReview: vi.fn((workspace: string) => workspace === "A" ? first.promise : second.promise)
  } as unknown as MemoryApi;
  const { result, rerender } = renderHook(({ workspace }) => useMemoryItems(workspace, api), { initialProps: { workspace: "A" } });
  let oldReview!: ReturnType<typeof result.current.decide>;
  act(() => { oldReview = result.current.decide({ revision_id: "rev-A" } as MemoryItem, "REJECT"); });
  rerender({ workspace: "B" });
  let newReview!: ReturnType<typeof result.current.decide>;
  act(() => { newReview = result.current.decide({ revision_id: "rev-B" } as MemoryItem, "REJECT"); });
  await act(async () => { first.resolve({} as Awaited<ReturnType<MemoryApi["decideReview"]>>); await oldReview; });
  expect(result.current.pendingRevisionId).toBe("rev-B");
  await act(async () => { second.resolve({} as Awaited<ReturnType<MemoryApi["decideReview"]>>); await newReview; });
  expect(result.current.pendingRevisionId).toBe("");
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
