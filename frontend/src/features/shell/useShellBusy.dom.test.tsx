// @vitest-environment jsdom

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useShellBusy } from "./useShellBusy";

describe("useShellBusy", () => {
  it("publishes natural pending and completed status copy", async () => {
    const { result } = renderHook(() => useShellBusy());
    let resolveAction: (() => void) | undefined;
    const action = new Promise<void>((resolve) => {
      resolveAction = resolve;
    });

    let runPromise: Promise<void> | undefined;
    act(() => {
      runPromise = result.current.run("重建工作台 Wiki", () => action, "wiki");
    });
    expect(result.current.status).toBe("正在重建工作台 Wiki...");

    await act(async () => {
      resolveAction?.();
      await runPromise;
    });
    expect(result.current.status).toBe("已完成：重建工作台 Wiki");
  });

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

  it("keeps a more specific status published by the completed action", async () => {
    const { result } = renderHook(() => useShellBusy());

    await act(async () => {
      await result.current.run("上传资料并解析", async () => {
        result.current.setStatus("文本资料已加入当前工作台，正在建立检索索引");
      }, "upload");
    });

    expect(result.current.status).toBe("文本资料已加入当前工作台，正在建立检索索引");
    expect(result.current.uploadBusy).toBe(false);
  });
});
