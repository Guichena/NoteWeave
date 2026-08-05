// @vitest-environment jsdom

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useShellBusy } from "./useShellBusy";

describe("useShellBusy", () => {
  it("restores the ready status when a superseded request is aborted", async () => {
    const { result } = renderHook(() => useShellBusy());

    await act(async () => {
      await result.current.run("打开 Deep Research 工作台", async () => {
        throw new DOMException("signal is aborted without reason", "AbortError");
      }, "research");
    });

    expect(result.current.status).toBe("准备就绪");
    expect(result.current.researchBusy).toBe(false);
  });

  it("still exposes actionable failures", async () => {
    const { result } = renderHook(() => useShellBusy());

    await act(async () => {
      await result.current.run("打开 Deep Research 工作台", async () => {
        throw new Error("研究历史加载失败");
      }, "research");
    });

    expect(result.current.status).toBe("研究历史加载失败");
  });
});
