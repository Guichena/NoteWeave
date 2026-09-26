import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import { navigateAppView } from "../shell/viewRoute";
import type { Workspace } from "../workspace/model";
import { knowledgeApi } from "./api";
import type { WikiGraphMode, WikiGraphOptions, WikiHome, WikiPage } from "./model";

type UseWikiPageActionsInput = {
  workspace: Workspace | null;
  wikiTitle: string;
  wikiDraft: string;
  wikiAppendDraft: string;
  wikiRenameTitle: string;
  selectedWikiPage: WikiPage | null;
  wikiGraphMode: WikiGraphMode;
  wikiGraphKindFilters: string[];
  selectedWikiItemId: string;
  run: ShellRun;
  setStatus: (status: string) => void;
  appendSystemMessage: (content: string) => void;
  setView: (view: AppView) => void;
  setWikiTitle: (title: string) => void;
  setWikiDraft: (draft: string) => void;
  setWikiAppendDraft: (draft: string) => void;
  refreshWikiFromServer: (
    preferredItemId: string,
    graphOptions?: WikiGraphOptions
  ) => Promise<WikiHome>;
};

export function useWikiPageActions({
  workspace,
  wikiTitle,
  wikiDraft,
  wikiAppendDraft,
  wikiRenameTitle,
  selectedWikiPage,
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
}: UseWikiPageActionsInput) {
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

  return {
    createWikiPage,
    appendWikiVersion,
    renameSelectedWikiPage,
    deleteSelectedWikiPage,
    rebuildWikiLinks,
    rebuildWorkspaceWiki,
    autoFixWiki,
    clearWikiRepairDraft,
    prefillWikiRepairDraft
  };
}

export type WikiPageActions = ReturnType<typeof useWikiPageActions>;
