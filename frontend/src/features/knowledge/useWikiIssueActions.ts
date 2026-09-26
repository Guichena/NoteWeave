import type { AppView } from "../shell/viewRoute";
import { navigateAppView } from "../shell/viewRoute";
import type { ShellRun } from "../shell/useShellBusy";
import type {
  WikiGraphMode,
  WikiGraphOptions,
  WikiHome,
  WikiIndexSource,
  WikiIssue,
  WikiRebuildAdvice
} from "./model";
import { composeMissingWikiPageDraft } from "./wikiUtils";

type AsyncAction = () => void | Promise<unknown>;
type StringSetter = (value: string) => void;
type IssueScope = "ALL" | "AUTO" | "MANUAL";
type IssuePage = "ALL" | "CURRENT";

type WikiIssueActionsInput = {
  workspace: { workspace_id: string } | null;
  view: AppView;
  setView: (view: AppView) => void;
  run: ShellRun;
  setStatus: (status: string) => void;
  appendSystemMessage: (content: string) => void;
  wikiHome: WikiHome | null;
  wikiEnabled: boolean;
  wikiGraphMode: WikiGraphMode;
  wikiGraphKindFilters: string[];
  wikiRebuildAdvice: WikiRebuildAdvice | null;
  refreshWikiFromServer: (preferredItemId: string, options: WikiGraphOptions) => Promise<WikiHome>;
  openWikiHome: AsyncAction;
  openWikiIndex: AsyncAction;
  openWikiPageById: (itemId: string) => Promise<unknown>;
  prepareWikiIssueRepair: (issue: WikiIssue) => Promise<unknown>;
  prefillWikiRepairDraft: (title: string, draft: string) => void;
  findFirstWikiIssue: (issueType: string) => Promise<WikiIssue | null>;
  updateWikiEnabled: (enabled: boolean, graphMode: WikiGraphMode, graphKinds: string[]) => Promise<{ wiki_enabled: boolean }>;
  rebuildWorkspaceWiki: AsyncAction;
  autoFixWiki: AsyncAction;
  setWikiIssueScopeFilter: (value: IssueScope) => void;
  setWikiIssueTypeFilter: StringSetter;
  setWikiIssueSeverityFilter: StringSetter;
  setWikiIssuePageFilter: (value: IssuePage) => void;
};

export function useWikiIssueActions(props: WikiIssueActionsInput) {
  const {
    workspace,
    view,
    setView,
    run,
    setStatus,
    appendSystemMessage,
    wikiHome,
    wikiEnabled,
    wikiGraphMode,
    wikiGraphKindFilters,
    wikiRebuildAdvice,
    refreshWikiFromServer,
    openWikiHome,
    openWikiIndex,
    openWikiPageById,
    prepareWikiIssueRepair,
    prefillWikiRepairDraft,
    findFirstWikiIssue,
    updateWikiEnabled,
    rebuildWorkspaceWiki,
    autoFixWiki,
    setWikiIssueScopeFilter,
    setWikiIssueTypeFilter,
    setWikiIssueSeverityFilter,
    setWikiIssuePageFilter
  } = props;

  async function focusWikiIssue(issue: WikiIssue) {
    setWikiIssueScopeFilter(issue.auto_fixable ? "AUTO" : "MANUAL");
    setWikiIssueTypeFilter(issue.issue_type || "ALL");
    setWikiIssueSeverityFilter(issue.severity || "ALL");
    if (issue.item_id) {
      await openWikiPageById(issue.item_id);
      setWikiIssuePageFilter("CURRENT");
      return;
    }
    setWikiIssuePageFilter("ALL");
  }

  async function focusWikiIssueType(issueType: string) {
    if (!workspace) {
      return;
    }
    if (!wikiHome) {
      const wiki = await refreshWikiFromServer("", {
        mode: "overview",
        graphKinds: wikiGraphKindFilters
      });
      setView("wiki");
      navigateAppView("wiki", wiki.wiki_url);
    } else {
      setView("wiki");
      await openWikiIndex();
    }
    const firstIssue = await findFirstWikiIssue(issueType);
    setWikiIssueScopeFilter(firstIssue?.auto_fixable ? "AUTO" : issueType === "BROKEN_LINK" ? "AUTO" : "MANUAL");
    setWikiIssueTypeFilter(issueType || "ALL");
    setWikiIssueSeverityFilter("ALL");
    if (!firstIssue) {
      setWikiIssuePageFilter("ALL");
      setStatus(`已定位到 ${issueType} 维护视图`);
      return;
    }
    await prepareWikiIssueRepair(firstIssue);
    if (firstIssue.item_id) {
      setWikiIssuePageFilter("CURRENT");
    } else {
      setWikiIssuePageFilter("ALL");
    }
    setStatus(`已定位到 ${issueType} 的首个相关对象`);
  }

  async function toggleWikiEnabled() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run(wikiEnabled ? "关闭 Wiki 构建" : "开启 Wiki 构建", async () => {
      const next = !wikiEnabled;
      const settings = await updateWikiEnabled(next, wikiGraphMode, wikiGraphKindFilters);
      appendSystemMessage(
        settings.wiki_enabled
          ? "已开启工作台级 Wiki 构建。已有资料会自动回补，后续资料上传或更新会进入 Wiki ingest 队列。"
          : "已关闭工作台级 Wiki 构建。资料上传只进入普通检索索引。"
      );
    }, "wiki");
  }

  async function focusWikiAdviceTarget() {
    if (!wikiRebuildAdvice) {
      return;
    }
    const issueType = wikiRebuildAdvice.recommended_issue_type || "MISSING_SOURCE";
    const focusItemId = wikiRebuildAdvice.focus_item_id || "";
    const focusTitle = wikiRebuildAdvice.focus_title || "";
    setWikiIssueTypeFilter(issueType || "ALL");
    setWikiIssueSeverityFilter("ALL");
    if (issueType === "BROKEN_LINK" && focusTitle) {
      const sourceTitle = focusItemId
        ? wikiHome?.pages.find((page) => page.item_id === focusItemId)?.title ?? ""
        : "";
      if (focusItemId) {
        await openWikiPageById(focusItemId);
        setWikiIssuePageFilter("CURRENT");
      } else {
        setWikiIssuePageFilter("ALL");
      }
      setWikiIssueScopeFilter("AUTO");
      prefillWikiRepairDraft(focusTitle, composeMissingWikiPageDraft(focusTitle, sourceTitle));
      setStatus(`已根据建议预填《${focusTitle}》的补缺草稿`);
      return;
    }
    if (focusItemId) {
      await openWikiPageById(focusItemId);
      setWikiIssuePageFilter("CURRENT");
      setWikiIssueScopeFilter("MANUAL");
      setStatus(`已定位到《${focusTitle || focusItemId}》的修正视图`);
      return;
    }
    await focusWikiIssueType(issueType);
  }

  function getRecentSourceAction(source: WikiIndexSource) {
    switch (source.recommended_action) {
      case "OPEN_WIKI_PAGE":
        if (!source.focus_item_id) {
          return null;
        }
        return {
          label: `打开 ${source.focus_title || "关联页面"}`,
          run: () => void openWikiPageById(source.focus_item_id)
        };
      case "ENABLE_WIKI":
        if (!workspace) {
          return null;
        }
        return { label: "开启 Wiki 构建", run: () => void toggleWikiEnabled() };
      case "REBUILD_WIKI":
        if (!workspace) {
          return null;
        }
        return { label: "按当前资料重建 Wiki", run: () => void rebuildWorkspaceWiki() };
      default:
        return null;
    }
  }

  function getWikiAdviceAction() {
    if (!wikiRebuildAdvice) {
      return null;
    }
    switch (wikiRebuildAdvice.recommended_action) {
      case "ENABLE_WIKI":
        return { label: "按建议开启 Wiki 构建", run: () => void toggleWikiEnabled() };
      case "REBUILD_WIKI":
        return { label: "按建议重建 Wiki", run: () => void rebuildWorkspaceWiki() };
      case "AUTO_FIX_WIKI":
        return { label: "按建议执行自动补缺", run: () => void autoFixWiki() };
      case "FOCUS_MANUAL_REPAIR":
        return { label: "按建议进入人工修正", run: () => void focusWikiAdviceTarget() };
      case "OPEN_WIKI_HOME":
        return {
          label: view === "wiki" ? "查看工作台总览" : "进入 Wiki 工作台",
          run: () => void (view === "wiki" ? openWikiIndex() : openWikiHome())
        };
      default:
        return null;
    }
  }

  function getWikiIssuePrimaryAction(issue: WikiIssue) {
    switch (issue.action_code || issue.issue_type) {
      case "REBUILD_WIKI":
        if (!workspace) {
          return null;
        }
        return { label: "按资料重建 Wiki", run: () => void rebuildWorkspaceWiki() };
      case "PREFILL_MISSING_PAGE":
      case "BROKEN_LINK":
        return { label: "预填补缺页", run: () => void prepareWikiIssueRepair(issue) };
      case "FOCUS_MANUAL_REPAIR":
      case "PLACEHOLDER_CONTENT":
      case "MISSING_SOURCE":
      case "ORPHAN_PAGE":
        return { label: "进入修正模式", run: () => void prepareWikiIssueRepair(issue) };
      default:
        return null;
    }
  }

  return {
    focusWikiIssue,
    focusWikiIssueType,
    focusWikiAdviceTarget,
    getWikiAdviceAction,
    getWikiIssuePrimaryAction,
    getRecentSourceAction,
    toggleWikiEnabled
  };
}
