// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ArtifactMindMapPreview,
  buildMindMapDocument,
  sanitizeMindMapMarkdown,
  summarizeMindMap
} from "./ArtifactMindMapPreview";

const fit = vi.fn(async () => undefined);
const destroy = vi.fn();
const setZoomExtent = vi.fn();
const markmapRoot = { content: "root", children: [], payload: {} };
const createMarkmap = vi.fn((
  _svg: SVGSVGElement,
  _options: Record<string, unknown>,
  _data: unknown
) => ({
  fit,
  destroy,
  zoom: { extent: setZoomExtent },
  state: { data: markmapRoot }
}));

vi.mock("markmap-lib", () => ({
  Transformer: class {
    transform(content: string) {
      return { root: { content, children: [], payload: {} } };
    }
  }
}));

vi.mock("markmap-view", () => ({
  Markmap: {
    create: createMarkmap
  }
}));

describe("ArtifactMindMapPreview", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    document.body.style.overflow = "";
  });

  it("normalizes a safe single-root mind map document", () => {
    const unsafe = [
      "## 分支",
      "- [证据](javascript:alert((1)))",
      "- ![跟踪像素](https://example.com/a_(b_(c)).png)",
      "<script>alert(2)</script>"
    ].join("\n");

    expect(sanitizeMindMapMarkdown(unsafe)).toBe("## 分支\n- 证据\n- 跟踪像素");
    expect(buildMindMapDocument(unsafe, "工作台导图")).toBe(
      "# 工作台导图\n\n## 分支\n- 证据\n- 跟踪像素"
    );
    expect(buildMindMapDocument("前置摘要\n# 主题\n# 另一根", "备用")).toBe(
      "# 主题\n\n前置摘要\n## 另一根"
    );
    expect(summarizeMindMap("# 主题\n\n## A\n## B", "备用")).toEqual({
      root: "主题",
      branchCount: 2,
      branches: ["A", "B"]
    });
  });

  it("keeps keyboard focus in the dialog and restores it after Escape", async () => {
    document.body.style.overflow = "clip";
    render(
      <ArtifactMindMapPreview
        title="工作台知识导图"
        markdown={"# 工作台知识导图\n\n## 证据\n- 来源 A\n\n## 结论\n- 结论 B"}
      />
    );

    expect(screen.getByText("2 条主分支 · 证据 / 结论")).toBeTruthy();
    const trigger = screen.getByRole("button", { name: "打开导图" });
    trigger.focus();
    fireEvent.click(trigger);

    expect(screen.getByRole("dialog", { name: "工作台知识导图" })).toBeTruthy();
    expect(document.body.style.overflow).toBe("hidden");
    const closeButton = screen.getByRole("button", { name: "关闭思维导图" });
    const fitButton = screen.getByRole("button", { name: "适合窗口" });
    await waitFor(() => expect(document.activeElement).toBe(closeButton));
    await waitFor(() => expect(fit).toHaveBeenCalled());
    fireEvent.click(fitButton);
    expect(fit.mock.calls.length).toBeGreaterThanOrEqual(2);

    closeButton.focus();
    fireEvent.keyDown(closeButton, { key: "Tab" });
    expect(document.activeElement).toBe(fitButton);
    fireEvent.keyDown(fitButton, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(closeButton);

    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.body.style.overflow).toBe("clip");
    expect(document.activeElement).toBe(trigger);
    expect(destroy).toHaveBeenCalled();
  });

  it("starts mobile previews with readable first-level branches", async () => {
    vi.stubGlobal("matchMedia", vi.fn((query: string) => ({
      matches: query === "(max-width: 767px)",
      media: query
    })));

    render(
      <ArtifactMindMapPreview
        title="工作台知识导图"
        markdown={"# 工作台知识导图\n\n## 证据\n- 来源 A"}
      />
    );
    fireEvent.click(screen.getByRole("button", { name: "打开导图" }));

    await waitFor(() => expect(createMarkmap).toHaveBeenCalled());
    expect(setZoomExtent).toHaveBeenCalledWith(expect.any(Function));
    expect(createMarkmap.mock.calls.at(-1)?.[1]).toEqual(expect.objectContaining({
      autoFit: false,
      initialExpandLevel: 2
    }));
  });
});
