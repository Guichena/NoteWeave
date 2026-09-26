// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WikiWorkbench } from "./WikiWorkbench";

afterEach(cleanup);

describe("WikiWorkbench Navigation Resilience & Retry", () => {
  it("renders loading view when workspace exists, wikiHome is null and isBusy is true", () => {
    render(
      <WikiWorkbench
        isBusy={true}
        workspace={{ workspace_id: "ws-1", name: "Test Workspace", status: "ACTIVE", created_at: "" }}
        filters={{} as any}
        draft={{} as any}
        selection={{} as any}
        data={{ wikiHome: null, wikiIssues: [] } as any}
        actions={{} as any}
        helpers={{} as any}
        derived={{ wikiIssueTypes: [], wikiIssueSeverities: [] } as any}
      />
    );
    expect(screen.getByText("正在加载 Wiki 工作台数据...")).toBeTruthy();
  });

  it("renders loading view while the workspace session is still being restored", () => {
    render(
      <WikiWorkbench
        isBusy={false}
        workspace={null}
        filters={{} as any}
        draft={{} as any}
        selection={{} as any}
        data={{ wikiHome: null, wikiIssues: [] } as any}
        actions={{} as any}
        helpers={{} as any}
        derived={{ wikiIssueTypes: [], wikiIssueSeverities: [] } as any}
      />
    );
    expect(screen.getByText("正在加载 Wiki 工作台数据...")).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("renders error state with retry button when wikiHome is null and isBusy is false", () => {
    const refreshFn = vi.fn();
    render(
      <WikiWorkbench
        isBusy={false}
        workspace={{ workspace_id: "ws-1", name: "Test Workspace", status: "ACTIVE", created_at: "" }}
        filters={{} as any}
        draft={{} as any}
        selection={{} as any}
        data={{ wikiHome: null, wikiIssues: [] } as any}
        actions={{ refreshWikiFromServer: refreshFn } as any}
        helpers={{} as any}
        derived={{ wikiIssueTypes: [], wikiIssueSeverities: [] } as any}
      />
    );

    const alertMsg = screen.getByRole("alert");
    expect(alertMsg.textContent).toContain("Wiki 工作台加载失败");
    expect(alertMsg.closest(".wiki-load-state")).toBeTruthy();
    const retryBtn = screen.getByRole("button", { name: "重试加载 Wiki" });
    expect(retryBtn).toBeTruthy();

    fireEvent.click(retryBtn);
    expect(refreshFn).toHaveBeenCalled();
  });

  it("renders a focused preparation surface instead of empty governance controls", () => {
    const createWikiPage = vi.fn();
    const setWikiTitle = vi.fn();
    const setWikiDraft = vi.fn();
    render(
      <WikiWorkbench
        isBusy={false}
        workspace={{ workspace_id: "ws-1", name: "Test Workspace", status: "ACTIVE", created_at: "" }}
        filters={{} as any}
        draft={{
          wikiTitle: "工作台知识页",
          setWikiTitle,
          wikiDraft: "",
          setWikiDraft
        } as any}
        selection={{} as any}
        data={{
          wikiHome: { pages: [] },
          wikiIndex: { page_count: 0, ready_source_count: 2, wiki_enabled: false },
          wikiRebuildAdvice: { message: "开启 Wiki 构建" },
          wikiIssues: []
        } as any}
        actions={{ createWikiPage } as any}
        helpers={{} as any}
        derived={{ wikiIssueTypes: [], wikiIssueSeverities: [], wikiAdviceAction: null } as any}
      />
    );

    expect(screen.getByRole("heading", { name: "准备工作台知识网络" })).toBeTruthy();
    expect(screen.getByText("开启 Wiki 构建")).toBeTruthy();
    expect(screen.queryByLabelText("搜索 Wiki 页面")).toBeNull();
    expect(screen.queryByRole("button", { name: "打开关系面板" })).toBeNull();

    const submit = screen.getByRole("button", { name: "创建首个 Wiki 页面" });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("首个 Wiki 页面标题"), { target: { value: "首个页面" } });
    fireEvent.change(screen.getByLabelText("首个 Wiki 页面正文"), { target: { value: "首个页面正文" } });
    expect(setWikiTitle).toHaveBeenCalledWith("首个页面");
    expect(setWikiDraft).toHaveBeenCalledWith("首个页面正文");
  });

  it("submits a complete manual first-page draft from the empty state", () => {
    const createWikiPage = vi.fn();
    render(
      <WikiWorkbench
        isBusy={false}
        workspace={{ workspace_id: "ws-1", name: "Test Workspace", status: "ACTIVE", created_at: "" }}
        filters={{} as any}
        draft={{
          wikiTitle: "首个页面",
          setWikiTitle: vi.fn(),
          wikiDraft: "首个页面正文",
          setWikiDraft: vi.fn()
        } as any}
        selection={{} as any}
        data={{
          wikiHome: { pages: [] },
          wikiIndex: { page_count: 0, ready_source_count: 0, wiki_enabled: false },
          wikiRebuildAdvice: { message: "准备 Wiki" },
          wikiIssues: []
        } as any}
        actions={{ createWikiPage } as any}
        helpers={{} as any}
        derived={{ wikiIssueTypes: [], wikiIssueSeverities: [], wikiAdviceAction: null } as any}
      />
    );

    fireEvent.click(screen.getByRole("button", { name: "创建首个 Wiki 页面" }));
    expect(createWikiPage).toHaveBeenCalledTimes(1);
  });
});
