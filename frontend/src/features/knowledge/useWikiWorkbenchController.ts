import { useEffect, type ReactNode } from "react";
import { formatDateTime } from "../../shared/util/datetime";
import type { WikiEvidenceVersionTarget } from "../answers/evidence";
import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import { navigateAppView } from "../shell/viewRoute";
import type { Workspace } from "../workspace/model";
import {
  type WikiGraphMode,
  type WikiIssue,
  type WikiPage,
  type WikiTaskSummary
} from "./model";
import { useKnowledgeState } from "./useKnowledgeState";
import { composeMissingWikiPageDraft, composeWikiAppendDraft } from "./wikiUtils";
import { buildWikiWorkbenchViewModel } from "./wikiWorkbenchViewModel";
import {
  renderWikiRecentUpdateCard as renderWikiRecentUpdateCardView,
  renderWikiTaskCard as renderWikiTaskCardView
} from "./WikiOverviewCards";
import { useWikiGraphActions } from "./useWikiGraphActions";
import { useWikiPageActions } from "./useWikiPageActions";
import { useWikiIssueActions } from "./useWikiIssueActions";
import { useWikiWorkbenchFormState } from "./useWikiWorkbenchFormState";

type UseWikiWorkbenchControllerInput = {
  workspace: Workspace | null;
  view: AppView;
  setView: (view: AppView) => void;
  /** Prefer wiki-scoped busy; falls back to aggregate when omitted. */
  isBusy: boolean;
  wikiBusy?: boolean;
  run: ShellRun;
  setStatus: (status: string) => void;
  appendSystemMessage: (content: string) => void;
};

export function useWikiWorkbenchController({
  workspace,
  view,
  setView,
  isBusy,
  wikiBusy,
  run,
  setStatus,
  appendSystemMessage
}: UseWikiWorkbenchControllerInput) {
  const uiBusy = wikiBusy ?? isBusy;
  const {
    wikiTitle,
    setWikiTitle,
    wikiDraft,
    setWikiDraft,
    wikiAppendDraft,
    setWikiAppendDraft,
    wikiSearch,
    setWikiSearch,
    wikiRenameTitle,
    setWikiRenameTitle,
    wikiGraphMode,
    setWikiGraphMode,
    wikiKindFilter,
    setWikiKindFilter,
    wikiGraphKindFilters,
    setWikiGraphKindFilters,
    wikiGraphSearch,
    setWikiGraphSearch,
    wikiIssueTypeFilter,
    setWikiIssueTypeFilter,
    wikiIssueScopeFilter,
    setWikiIssueScopeFilter,
    wikiIssueSeverityFilter,
    setWikiIssueSeverityFilter,
    wikiIssuePageFilter,
    setWikiIssuePageFilter
  } = useWikiWorkbenchFormState();

  const knowledge = useKnowledgeState({
    workspaceId: workspace?.workspace_id ?? "",
    search: wikiSearch,
    issueType: wikiIssueTypeFilter,
    issueSeverity: wikiIssueSeverityFilter,
    issueScope: wikiIssueScopeFilter,
    issuePage: wikiIssuePageFilter
  });

  const {
    wikiHome,
    wikiIndex,
    wikiEnabled,
    wikiStats,
    wikiIssues,
    wikiLog,
    wikiGraph,
    wikiRebuildAdvice,
    selectedWikiItemId,
    selectedWikiDetail,
    selectedWikiVersions,
    selectedWikiVersionDetail,
    selectedWikiLog,
    wikiSearchResults,
    filteredWikiIssues,
    refreshHome: refreshWikiHome,
    refreshFromServer: refreshWikiFromServer,
    resetSelection: resetWikiSelection,
    clearSelection: clearWikiSelection,
    selectPage: selectKnowledgePage,
    refreshGraph: refreshWikiGraph,
    loadVersion: loadKnowledgeVersion,
    loadVersionById: loadKnowledgeVersionById,
    restoreLatestVersion: restoreLatestKnowledgeVersion,
    findFirstIssue: findFirstWikiIssue,
    updateEnabled: updateWikiEnabled
  } = knowledge;

  useEffect(() => {
    setWikiRenameTitle(selectedWikiDetail?.title ?? "");
  }, [selectedWikiDetail?.item_id, selectedWikiDetail?.title, setWikiRenameTitle]);

  useEffect(() => {
    if (view !== "wiki" || !workspace || wikiHome) {
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        await refreshWikiFromServer(selectedWikiItemId, {
          mode: wikiGraphMode,
          graphKinds: wikiGraphKindFilters
        });
      } catch (error) {
        if (!cancelled) {
          setStatus(error instanceof Error ? error.message : "打开 Wiki 工作台失败");
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [view, workspace?.workspace_id, wikiHome]);

  const {
    switchWikiGraphMode,
    toggleWikiGraphKind,
    resetWikiGraphKinds
  } = useWikiGraphActions({
    workspace,
    wikiGraphMode,
    wikiGraphKindFilters,
    selectedWikiItemId,
    setWikiGraphMode,
    setWikiGraphKindFilters,
    refreshWikiGraph
  });

  async function openWikiHome() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    setView("wiki");
    navigateAppView("wiki");
    await run("读取默认 Wiki 工作台入口", async () => {
      const wiki = await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      if (wiki?.wiki_url) {
        navigateAppView("wiki", wiki.wiki_url);
      }
    }, "wiki");
  }

  async function openWikiIndex() {
    if (!workspace || !wikiHome) {
      return;
    }
    setWikiGraphMode("overview");
    setWikiRenameTitle("");
    await clearWikiSelection({ mode: "overview", graphKinds: wikiGraphKindFilters });
  }


  async function selectWikiPage(page: WikiPage, nextGraphMode?: WikiGraphMode) {
    await run(`打开 Wiki 页面《${page.title}》`, async () => {
      const graphMode = nextGraphMode ?? wikiGraphMode;
      setWikiGraphMode(graphMode);
      await selectKnowledgePage(page, workspace ? {
        mode: graphMode,
        graphKinds: wikiGraphKindFilters
      } : undefined);
      setWikiAppendDraft("");
    }, "wiki");
  }

  async function openWikiGraphPage(itemId: string) {
    const page = wikiHome?.pages.find((entry) => entry.item_id === itemId);
    if (!page) {
      return;
    }
    await selectWikiPage(page, "ego");
  }

  async function openWikiPageById(itemId: string) {
    const page = wikiHome?.pages.find((entry) => entry.item_id === itemId);
    if (!page) {
      return;
    }
    await selectWikiPage(page);
  }

  /**
   * 从对话等页面按标题跳转到 Wiki 页面。先在当前视图加载页面列表，再切换到 Wiki，
   * 这样切换后的自动加载会跳过，不会覆盖刚选中的页面。
   */
  async function openWikiPageByTitle(title: string) {
    if (!workspace) {
      return;
    }
    const target = title.trim().toLowerCase();
    await run("打开 Wiki 页面", async () => {
      const home = wikiHome ?? await refreshWikiFromServer("", {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      setView("wiki");
      navigateAppView("wiki");
      const page = home?.pages.find((entry) => entry.title.trim().toLowerCase() === target);
      if (page) {
        await selectWikiPage(page);
      } else {
        setStatus(`知识库中还没有「${title}」页面`);
      }
    }, "wiki");
  }

  /**
   * 打开引用证据对应的知识版本。证据绑定的是版本身份，页面之后被改过也要展示那一版，
   * 不能用最新正文冒充当时的证据。
   */
  async function openWikiVersionById(target: WikiEvidenceVersionTarget) {
    if (!workspace) {
      return;
    }
    await run(`打开引用版本《${target.title}》`, async () => {
      const home = wikiHome ?? await refreshWikiFromServer("", {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      const page = home?.pages.find((entry) => entry.item_id === target.itemId);
      if (!page) {
        setStatus(`知识库中已找不到证据对应的页面「${target.title}」`);
        return;
      }
      setView("wiki");
      navigateAppView("wiki");
      const detail = await selectKnowledgePage(page);
      if (detail?.latest_version_id === target.versionId) {
        setStatus(`已打开《${page.title}》引用的版本`);
        return;
      }
      const version = await loadKnowledgeVersionById(page.item_id, target.versionId);
      if (version) {
        setStatus(`已打开《${page.title}》第 ${version.version_no} 版，即本次回答引用的版本`);
      } else {
        // 找不到那一版时保持当前页面的最新正文并明确说明，不假装它就是证据对应的版本
        setStatus(`《${page.title}》引用的版本已不在当前版本列表中，未用最新正文代替`);
      }
    }, "wiki");
  }

  async function prepareWikiIssueRepair(issue: WikiIssue) {
    if (issue.issue_type === "BROKEN_LINK") {
      const sourceTitle = wikiHome?.pages.find((page) => page.item_id === issue.item_id)?.title;
      prefillWikiRepairDraft(issue.title, composeMissingWikiPageDraft(issue.title, sourceTitle));
      return;
    }
    if (!issue.item_id) {
      return;
    }
    await openWikiPageById(issue.item_id);
    const pageTitle = wikiHome?.pages.find((page) => page.item_id === issue.item_id)?.title ?? issue.title;
    setWikiAppendDraft(composeWikiAppendDraft(issue.issue_type, pageTitle));
    setStatus(`已定位到《${pageTitle}》，并预填页面修正草稿`);
  }

  function prepareWikiLinkRepair(targetTitle: string, sourceTitle: string) {
    prefillWikiRepairDraft(targetTitle, composeMissingWikiPageDraft(targetTitle, sourceTitle));
  }

  async function loadWikiVersion(itemId: string, versionNo: number) {
    await loadKnowledgeVersion(itemId, versionNo);
  }

  function restoreLatestWikiVersion() {
    restoreLatestKnowledgeVersion();
  }




  function renderWikiTaskCard(task: WikiTaskSummary, key: string): ReactNode {
    return renderWikiTaskCardView(task, key, {
      uiBusy,
      formatDateTime,
      openWikiPageById,
      selectWikiPage,
      openWikiGraphPage
    });
  }

  function renderWikiRecentUpdateCard(page: WikiPage, key: string): ReactNode {
    return renderWikiRecentUpdateCardView(page, key, {
      uiBusy,
      formatDateTime,
      openWikiPageById,
      selectWikiPage,
      openWikiGraphPage
    });
  }


  const wikiViewModel = buildWikiWorkbenchViewModel({
    wikiHome,
    wikiIssues,
    wikiSearchResults,
    selectedWikiItemId,
    wikiKindFilter,
    wikiGraphKindFilters,
    wikiGraphSearch
  });
  const {
    createWikiPage,
    appendWikiVersion,
    renameSelectedWikiPage,
    deleteSelectedWikiPage,
    rebuildWikiLinks,
    rebuildWorkspaceWiki,
    autoFixWiki,
    clearWikiRepairDraft,
    prefillWikiRepairDraft
  } = useWikiPageActions({
    workspace,
    wikiTitle,
    wikiDraft,
    wikiAppendDraft,
    wikiRenameTitle,
    selectedWikiPage: wikiViewModel.selectedWikiPage,
    wikiGraphMode,
    wikiGraphKindFilters,
    selectedWikiItemId,
    run,
    setStatus,
    appendSystemMessage,
    setView,
    setWikiTitle,
    setWikiDraft,
    setWikiAppendDraft,
    refreshWikiFromServer
  });
  const {
    focusWikiIssue,
    focusWikiIssueType,
    focusWikiAdviceTarget,
    getWikiAdviceAction,
    getWikiIssuePrimaryAction,
    getRecentSourceAction,
    toggleWikiEnabled
  } = useWikiIssueActions({
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
  });
  const wikiAdviceAction = getWikiAdviceAction();

  return {
    openWikiPageByTitle,
    wikiHome,
    wikiIndex,
    wikiEnabled,
    wikiStats,
    wikiIssues,
    wikiLog,
    wikiGraph,
    wikiRebuildAdvice,
    selectedWikiItemId,
    selectedWikiDetail,
    selectedWikiVersions,
    selectedWikiVersionDetail,
    selectedWikiLog,
    filteredWikiIssues,
    ...wikiViewModel,
    refreshWikiHome,
    refreshWikiFromServer,
    resetWikiSelection,
    clearWikiSelection,
    wikiGraphMode,
    wikiGraphKindFilters,
    openWikiHome,
    openWikiIndex,
    createWikiPage,
    appendWikiVersion,
    renameSelectedWikiPage,
    deleteSelectedWikiPage,
    rebuildWikiLinks,
    rebuildWorkspaceWiki,
    autoFixWiki,
    clearWikiRepairDraft,
    prepareWikiLinkRepair,
    openWikiPageById,
    loadWikiVersion,
    openWikiVersionById,
    restoreLatestWikiVersion,
    switchWikiGraphMode,
    toggleWikiGraphKind,
    resetWikiGraphKinds,
    getRecentSourceAction,
    focusWikiIssue,
    openWikiGraphPage,
    selectWikiPage,
    toggleWikiEnabled,
    getWikiIssuePrimaryAction,
    renderWikiTaskCard,
    renderWikiRecentUpdateCard,
    wikiAdviceAction,
    filters: {
      wikiSearch, setWikiSearch,
      wikiKindFilter, setWikiKindFilter,
      wikiGraphMode, wikiGraphKindFilters, wikiGraphSearch, setWikiGraphSearch,
      wikiIssueTypeFilter, setWikiIssueTypeFilter,
      wikiIssueScopeFilter, setWikiIssueScopeFilter,
      wikiIssueSeverityFilter, setWikiIssueSeverityFilter,
      wikiIssuePageFilter, setWikiIssuePageFilter
    },
    draft: {
      wikiTitle, setWikiTitle,
      wikiDraft, setWikiDraft,
      wikiAppendDraft, setWikiAppendDraft,
      wikiRenameTitle, setWikiRenameTitle
    }
  };
}

export type WikiWorkbenchController = ReturnType<typeof useWikiWorkbenchController>;
