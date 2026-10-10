import { useState } from "react";
import { sourcesApi } from "../sources/api";
import { formatSourceIndexStatus, formatSourceProcessingStatus, type SourceAsset } from "../sources/model";
import type { UseChatSessionControllerInput } from "./useChatSessionController";

type StringSetter = (value: string | ((current: string) => string)) => void;

/** 正在上传或上传失败的文件；上传完成后由资料列表中的处理状态接替显示。 */
export type SourceUploadItem = {
  id: string;
  fileName: string;
  totalBytes: number;
  uploadedBytes: number;
  uploadedChunks: number;
  totalChunks: number;
  state: "uploading" | "failed";
  error: string;
};

const UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024;

type ChatSourceActionsInput = Pick<
  UseChatSessionControllerInput,
  | "workspace"
  | "run"
  | "setStatus"
  | "setSources"
  | "refreshWikiHome"
  | "refreshWikiFromServer"
  | "wikiEnabled"
  | "wikiGraphMode"
  | "wikiGraphKindFilters"
  | "loadExecution"
  | "setLatestTaskId"
  | "selectedWikiItemId"
> & {
  sourceText: string;
  setSourceText: StringSetter;
  appendSystemMessage: (content: string) => void;
  lastNoteAssistantMessageId: string;
  lastNoteAnswerContent: string;
  sourceDraftTitle: string;
  sourceDraftContent: string;
  sourceDraftRewriteMode: string;
  setSourceDraftTitle: StringSetter;
  setSourceDraftContent: StringSetter;
  setSourceDraftRewriteMode: StringSetter;
};

export function useChatSourceActions(props: ChatSourceActionsInput) {
  const {
    workspace,
    run,
    setStatus,
    setSources,
    refreshWikiHome,
    refreshWikiFromServer,
    wikiEnabled,
    wikiGraphMode,
    wikiGraphKindFilters,
    loadExecution,
    setLatestTaskId,
    selectedWikiItemId,
    sourceText,
    setSourceText,
    appendSystemMessage,
    lastNoteAssistantMessageId,
    lastNoteAnswerContent,
    sourceDraftTitle,
    sourceDraftContent,
    sourceDraftRewriteMode,
    setSourceDraftTitle,
    setSourceDraftContent,
    setSourceDraftRewriteMode
  } = props;
  const [uploads, setUploads] = useState<SourceUploadItem[]>([]);

  function updateUpload(id: string, patch: Partial<SourceUploadItem>) {
    setUploads((current) => current.map((item) => (item.id === id ? { ...item, ...patch } : item)));
  }

  function dismissUpload(id: string) {
    setUploads((current) => current.filter((item) => item.id !== id));
  }

  async function uploadSource() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("上传资料并解析", async () => {
      const completed = await sourcesApi.uploadText(workspace.workspace_id, sourceText);
      await refreshUploadedSource(completed.task_id);
      setSourceText("");
      setStatus("文本资料已加入当前工作台，解析与索引状态将持续更新");
    }, "upload");
  }

  async function uploadSourceFile(file: File) {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    setUploads((current) => [...current, {
      id,
      fileName: file.name,
      totalBytes: file.size,
      uploadedBytes: 0,
      uploadedChunks: 0,
      totalChunks: Math.max(1, Math.ceil(file.size / UPLOAD_CHUNK_BYTES)),
      state: "uploading",
      error: ""
    }]);
    await run(`上传资料《${file.name}》`, async () => {
      try {
        const completed = await sourcesApi.uploadFile(workspace.workspace_id, file, (progress) => updateUpload(id, progress));
        dismissUpload(id);
        await refreshUploadedSource(completed.task_id);
        setStatus(`《${file.name}》已加入当前工作台，解析与索引状态将持续更新`);
      } catch (error) {
        updateUpload(id, { state: "failed", error: error instanceof Error ? error.message : "上传失败" });
        throw error;
      }
    }, "upload");
  }

  async function refreshUploadedSource(taskId: string) {
    if (!workspace) return;
    await loadExecution(taskId);
    const nextSources = await sourcesApi.list(workspace.workspace_id);
    setLatestTaskId(taskId);
    await refreshWikiHome();
    setSources(nextSources);
  }

  async function deleteSource(source: SourceAsset) {
    if (!workspace) {
      return;
    }
    await run(`删除资料《${source.title}》`, async () => {
      const deleted = await sourcesApi.remove(workspace.workspace_id, source.source_id);
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      setSources(nextSources);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      if (deleted.wiki_retract_task_id) {
        await loadExecution(deleted.wiki_retract_task_id);
        setLatestTaskId(deleted.wiki_retract_task_id);
      }
      appendSystemMessage(
        deleted.wiki_retract_task_id
          ? `资料已删除：${source.title}。相关自动生成页面已同步清理。`
          : `资料已删除：${source.title}。当前未开启 Wiki 构建，因此没有关联页面需要同步处理。`
      );
    }, "upload");
  }

  async function prepareNoteSourceDraft(
    messageId: string,
    preferredTitle: string,
    fallbackContent = lastNoteAnswerContent
  ): Promise<{ title: string; content: string; rewriteMode: string }> {
    try {
      const draft = await sourcesApi.buildNoteSourceDraft(messageId, {
        title: preferredTitle.trim() || "工作台整理笔记"
      });
      const title = draft.title || preferredTitle;
      const content = draft.content || fallbackContent;
      const rewriteMode = draft.rewrite_mode || "template";
      setSourceDraftTitle(title);
      setSourceDraftContent(content);
      setSourceDraftRewriteMode(rewriteMode);
      return { title, content, rewriteMode };
    } catch {
      setSourceDraftContent(fallbackContent);
      setSourceDraftRewriteMode("raw");
      return {
        title: preferredTitle.trim() || "工作台整理笔记",
        content: fallbackContent,
        rewriteMode: "raw"
      };
    }
  }

  async function rewriteNoteSourceDraft() {
    if (!lastNoteAssistantMessageId) {
      setStatus("请先完成一次 Note 模式回答");
      return;
    }
    await run("生成中性入库草稿", async () => {
      await prepareNoteSourceDraft(lastNoteAssistantMessageId, sourceDraftTitle, lastNoteAnswerContent);
    }, "chat");
  }

  async function saveNoteAnswerAsSource() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    if (!lastNoteAssistantMessageId) {
      setStatus("请先完成一次 Note 模式回答");
      return;
    }
    await run("确认入库为资料", async () => {
      let title = sourceDraftTitle.trim();
      let content = sourceDraftContent.trim();
      if (!content) {
        const draft = await prepareNoteSourceDraft(
          lastNoteAssistantMessageId,
          title || "工作台整理笔记",
          lastNoteAnswerContent
        );
        title = draft.title.trim() || title;
        content = draft.content.trim();
      }
      if (!title) {
        throw new Error("请填写入库标题");
      }
      if (!content) {
        throw new Error("请填写或确认入库正文");
      }
      const saved = await sourcesApi.saveAnswerAsSource(lastNoteAssistantMessageId, {
        title,
        content
      });
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      setSources(nextSources);
      appendSystemMessage(
        `资料已加入当前工作台：${saved.title || title}。${formatSourceProcessingStatus(saved.parse_status)}，${formatSourceIndexStatus(saved.index_status)}。`
      );
    }, "chat");
  }

  return {
    uploadSource,
    uploadSourceFile,
    uploads,
    dismissUpload,
    deleteSource,
    prepareNoteSourceDraft,
    rewriteNoteSourceDraft,
    saveNoteAnswerAsSource
  };
}
