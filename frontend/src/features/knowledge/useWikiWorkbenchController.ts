import { useEffect, useState, type ReactNode } from "react";
import { createElement } from "react";
import { formatDateTime } from "../../shared/util/datetime";
import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import { navigateAppView } from "../shell/viewRoute";
import type { Workspace } from "../workspace/model";
import { knowledgeApi } from "./api";
import {
  type WikiGraphMode,
  type WikiIndexSource,
  type WikiIssue,
  type WikiPage,
  type WikiTaskSummary
} from "./model";
import { useKnowledgeState } from "./useKnowledgeState";
import {
  buildAvailableWikiKinds,
  buildGraphSearchHits,
  buildGroupedWikiPages,
  buildWikiIssueSeverities,
  buildWikiIssueTypes,
  composeMissingWikiPageDraft,
  composeWikiAppendDraft,
  getWikiKindRank
} from "./wikiUtils";

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
  const [wikiTitle, setWikiTitle] = useState("工作台知识页");
  const [wikiDraft, setWikiDraft] = useState("");
  const [wikiAppendDraft, setWikiAppendDraft] = useState("");
  const [wikiSearch, setWikiSearch] = useState("");
  const [wikiRenameTitle, setWikiRenameTitle] = useState("");
  const [wikiGraphMode, setWikiGraphMode] = useState<WikiGraphMode>("overview");
  const [wikiKindFilter, setWikiKindFilter] = useState<string>("ALL");
  const [wikiGraphKindFilters, setWikiGraphKindFilters] = useState<string[]>([]);
  const [wikiGraphSearch, setWikiGraphSearch] = useState("");
  const [wikiIssueTypeFilter, setWikiIssueTypeFilter] = useState<string>("ALL");
  const [wikiIssueScopeFilter, setWikiIssueScopeFilter] = useState<"ALL" | "AUTO" | "MANUAL">("ALL");
  const [wikiIssueSeverityFilter, setWikiIssueSeverityFilter] = useState<string>("ALL");
  const [wikiIssuePageFilter, setWikiIssuePageFilter] = useState<"ALL" | "CURRENT">("ALL");

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
    clearSelection: clearWikiSelection,
    selectPage: selectKnowledgePage,
    refreshGraph: refreshWikiGraph,
    loadVersion: loadKnowledgeVersion,
    restoreLatestVersion: restoreLatestKnowledgeVersion,
    findFirstIssue: findFirstWikiIssue,
    updateEnabled: updateWikiEnabled
  } = knowledge;

  useEffect(() => {
    setWikiRenameTitle(selectedWikiDetail?.title ?? "");
  }, [selectedWikiDetail?.item_id, selectedWikiDetail?.title]);

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

  async function openWikiHome() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("读取默认 Wiki 工作台入口", async () => {
      const wiki = await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      setView("wiki");
      navigateAppView("wiki", wiki.wiki_url);
      appendSystemMessage(
        `默认 Wiki 工作台：${wiki.wiki_url}，页面数 ${wiki.pages.length}，链接数 ${wiki.links.length}`
      );
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

  async function createWikiPage() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const content = wikiDraft.trim();
    if (!content) {
      setStatus("请先填写要补充的页面正文。主流程是工作台级自动 Wiki 构建，手动补充只用于缺口修正。");
      return;
    }
    await run("手动补缺 / 修正 Wiki 页面", async () => {
      const created = await knowledgeApi.createItem(workspace.workspace_id, {
        item_type: "WIKI",
        title: wikiTitle,
        content,
        source_message_id: null
      });
      const wiki = await refreshWikiFromServer(created.item_id, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      setWikiDraft("");
      appendSystemMessage(
        created.latest_version_no > 1
          ? `已将《${created.title}》追加为 v${created.latest_version_no}，用于手动修正现有 Wiki 页面。`
          : `已创建补缺页《${created.title}》。`
      );
      setView("wiki");
      navigateAppView("wiki", wiki.wiki_url);
    }, "wiki");
  }

  async function appendWikiVersion() {
    if (!selectedWikiPage) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    const content = wikiAppendDraft.trim();
    if (!content) {
      setStatus("请先填写新的 Wiki 版本正文");
      return;
    }
    await run(`追加 Wiki 页面《${selectedWikiPage.title}》版本`, async () => {
      await knowledgeApi.appendVersion(selectedWikiPage.item_id, {
        content,
        source_message_id: null
      });
      if (workspace) {
        await refreshWikiFromServer(selectedWikiPage.item_id, {
          mode: wikiGraphMode,
          graphKinds: wikiGraphKindFilters
        });
      }
      setWikiAppendDraft("");
    }, "wiki");
  }

  async function renameSelectedWikiPage() {
    if (!selectedWikiPage || !workspace) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    const title = wikiRenameTitle.trim();
    if (!title) {
      setStatus("请先填写新的 Wiki 标题");
      return;
    }
    await run(`重命名 Wiki 页面《${selectedWikiPage.title}》`, async () => {
      const renamed = await knowledgeApi.renameItem(selectedWikiPage.item_id, title);
      await refreshWikiFromServer(renamed.item_id, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
    }, "wiki");
  }

  async function deleteSelectedWikiPage() {
    if (!selectedWikiPage || !workspace) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    await run(`删除 Wiki 页面《${selectedWikiPage.title}》`, async () => {
      await knowledgeApi.deleteItem(selectedWikiPage.item_id);
      await refreshWikiFromServer("", { mode: "overview", graphKinds: wikiGraphKindFilters });
    }, "wiki");
  }

  async function rebuildWikiLinks() {
    if (!workspace) {
      return;
    }
    await run("重建 Wiki 链接", async () => {
      await knowledgeApi.rebuildLinks(workspace.workspace_id);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
    }, "wiki");
  }

  async function rebuildWorkspaceWiki() {
    if (!workspace) {
      return;
    }
    await run("重建工作台 Wiki", async () => {
      const rebuilt = await knowledgeApi.rebuild(workspace.workspace_id);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      appendSystemMessage(
        `已按当前工作台资料重建 Wiki：资料 ${rebuilt.source_count} 个，任务 ${rebuilt.task_count} 个。`
      );
    }, "wiki");
  }

  async function autoFixWiki() {
    if (!workspace) {
      return;
    }
    await run("自动修复 Wiki", async () => {
      const fixed = await knowledgeApi.autoFix(workspace.workspace_id);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      appendSystemMessage(
        `自动补缺已创建 ${fixed.created_pages} 个补缺页面，当前仍有 ${fixed.remaining_issues} 个问题待进一步处理。`
      );
    }, "wiki");
  }

  function clearWikiRepairDraft() {
    setWikiTitle("工作台知识页");
    setWikiDraft("");
    setStatus("已清空手动补页草稿");
  }

  function prefillWikiRepairDraft(title: string, draft: string) {
    setWikiTitle(title);
    setWikiDraft(draft);
    setStatus(`已为《${title}》预填手动补页草稿`);
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

  async function switchWikiGraphMode(nextMode: WikiGraphMode) {
    if (!workspace) {
      return;
    }
    setWikiGraphMode(nextMode);
    await refreshWikiGraph({
      mode: nextMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: wikiGraphKindFilters
    });
  }

  async function toggleWikiGraphKind(kind: string) {
    if (!workspace) {
      return;
    }
    const nextKinds = wikiGraphKindFilters.includes(kind)
      ? wikiGraphKindFilters.filter((entry) => entry !== kind)
      : [...wikiGraphKindFilters, kind].sort((left, right) => getWikiKindRank(left) - getWikiKindRank(right));
    setWikiGraphKindFilters(nextKinds);
    await refreshWikiGraph({
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: nextKinds
    });
  }

  async function resetWikiGraphKinds() {
    if (!workspace) {
      return;
    }
    setWikiGraphKindFilters([]);
    await refreshWikiGraph({
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: []
    });
  }

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

  function renderWikiTaskCard(task: WikiTaskSummary, key: string): ReactNode {
    return createElement(
      "div",
      { className: "link-card", key },
      createElement("strong", null, `${task.task_type} · ${task.task_status}`),
      createElement("span", null, `${task.progress_phase} · ${task.progress_message}`),
      createElement(
        "span",
        null,
        `${task.target_type || "WORKSPACE"} · ${task.target_title || task.target_id || "当前工作台"} · ${formatDateTime(task.updated_at)}`
      ),
      task.related_pages.length
        ? createElement(
          "div",
          { className: "wiki-inline-actions" },
          ...task.related_pages.map((page) => createElement(
            "button",
            {
              key: `${task.task_id}-${page.item_id}`,
              className: "secondary-button",
              disabled: uiBusy,
              onClick: () => void openWikiPageById(page.item_id)
            },
            `打开 ${page.title}`
          ))
        )
        : null
    );
  }

  function renderWikiRecentUpdateCard(page: WikiPage, key: string): ReactNode {
    return createElement(
      "div",
      { className: "link-card", key },
      createElement("strong", null, page.title),
      createElement(
        "span",
        null,
        `${page.page_kind || "TOPIC"} · v${page.latest_version_no} · 出链 ${page.outgoing_count} · 反链 ${page.backlink_count} · 引用 ${page.citation_count}${page.unresolved_count > 0 ? ` · 断链 ${page.unresolved_count}` : ""}`
      ),
      createElement("span", null, formatDateTime(page.updated_at)),
      createElement(
        "div",
        { className: "wiki-inline-actions" },
        createElement(
          "button",
          { className: "secondary-button", disabled: uiBusy, onClick: () => void selectWikiPage(page) },
          "打开页面"
        ),
        createElement(
          "button",
          { className: "secondary-button", disabled: uiBusy, onClick: () => void openWikiGraphPage(page.item_id) },
          "查看子图"
        )
      )
    );
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

  const selectedWikiPage = wikiHome?.pages.find((page) => page.item_id === selectedWikiItemId) ?? null;
  const selectedWikiIssues = selectedWikiItemId
    ? wikiIssues.filter((issue) => issue.item_id === selectedWikiItemId)
    : [];
  const baseWikiPages = wikiSearchResults ?? wikiHome?.pages ?? [];
  const availableWikiKinds = buildAvailableWikiKinds(wikiHome?.pages ?? []);
  const graphSearchHits = buildGraphSearchHits(wikiHome?.pages ?? [], wikiGraphSearch);
  const graphFilterLabel = wikiGraphKindFilters.length === 0 ? "全部页面类型" : wikiGraphKindFilters.join(" / ");
  const autoFixableIssues = wikiIssues.filter((issue) => issue.auto_fixable);
  const reviewRequiredIssues = wikiIssues.filter((issue) => !issue.auto_fixable);
  const wikiIssueTypes = buildWikiIssueTypes(wikiIssues);
  const wikiIssueSeverities = buildWikiIssueSeverities(wikiIssues);
  const visibleWikiPages = baseWikiPages.filter(
    (page) => wikiKindFilter === "ALL" || (page.page_kind || "TOPIC") === wikiKindFilter
  );
  const groupedWikiPages = buildGroupedWikiPages(visibleWikiPages);
  const wikiAdviceAction = getWikiAdviceAction();

  return {
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
    selectedWikiPage,
    selectedWikiIssues,
    refreshWikiHome,
    refreshWikiFromServer,
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
    availableWikiKinds,
    groupedWikiPages,
    visibleWikiPages,
    autoFixableIssues,
    reviewRequiredIssues,
    graphFilterLabel,
    graphSearchHits,
    wikiIssueTypes,
    wikiIssueSeverities,
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
