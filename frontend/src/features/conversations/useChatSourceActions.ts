import { useEffect, useState } from "react";
import { useOperationScope } from "../../shared/useOperationScope";
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
  /** 先在后台计算文件指纹（用于秒传与续传），再上传分片。 */
  phase: "hashing" | "uploading";
  hashedBytes: number;
  /** 续传时服务端已收到、本次跳过的分片数。 */
  resumedChunks: number;
};

const UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024;

type ChatSourceActionsInput = Pick<
  UseChatSessionControllerInput,
  | "workspace"
  | "conversation"
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
    conversation,
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
  const isWorkspaceCurrent = useOperationScope(workspace?.workspace_id ?? "");
  const isConversationCurrent = useOperationScope(`${workspace?.workspace_id ?? ""}:${conversation?.conversation_id ?? ""}`);
  useEffect(() => { setUploads([]); }, [workspace?.workspace_id]);

  function updateUpload(id: string, patch: Partial<SourceUploadItem>) {
    if (!isWorkspaceCurrent()) return;
    setUploads((current) => current.map((item) => (item.id === id ? { ...item, ...patch } : item)));
  }

  function dismissUpload(id: string) {
    setUploads((current) => current.filter((item) => item.id !== id));
  }

  async function uploadSource() {
    if (!isWorkspaceCurrent()) return;
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("上传资料并解析", async () => {
      const completed = await sourcesApi.uploadText(workspace.workspace_id, sourceText);
      if (!isWorkspaceCurrent()) return;
      await refreshUploadedSource(completed.task_id);
      if (!isWorkspaceCurrent()) return;
      setSourceText("");
      setStatus("文本资料已加入当前工作台，解析与索引状态将持续更新");
    }, "upload");
  }

  async function uploadSourceFile(file: File) {
    if (!isWorkspaceCurrent()) return;
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
      error: "",
      phase: "hashing",
      hashedBytes: 0,
      resumedChunks: 0
    }]);
    await run(`上传资料《${file.name}》`, async () => {
      try {
        const completed = await sourcesApi.uploadFile(workspace.workspace_id, file, (progress) => updateUpload(id, progress));
        if (!isWorkspaceCurrent()) return;
        dismissUpload(id);
        await refreshUploadedSource(completed.task_id);
        if (!isWorkspaceCurrent()) return;
        setStatus(completed.instant
          ? `《${file.name}》与已有文件相同，已直接加入当前工作台`
          : completed.resumed_chunks > 0
            ? `《${file.name}》已从中断处续传完成，解析与索引状态将持续更新`
            : `《${file.name}》已加入当前工作台，解析与索引状态将持续更新`);
      } catch (error) {
        updateUpload(id, { state: "failed", error: error instanceof Error ? error.message : "上传失败" });
        throw error;
      }
    }, "upload");
  }

  async function refreshUploadedSource(taskId: string) {
    if (!workspace || !isWorkspaceCurrent()) return;
    // 秒传命中的资料可能已经处理完成，没有新的处理任务
    if (taskId) {
      await loadExecution(taskId);
      if (!isWorkspaceCurrent()) return;
      setLatestTaskId(taskId);
    }
    const nextSources = await sourcesApi.list(workspace.workspace_id);
    if (!isWorkspaceCurrent()) return;
    await refreshWikiHome();
    if (!isWorkspaceCurrent()) return;
    setSources(nextSources);
  }

  async function deleteSource(source: SourceAsset) {
    if (!isWorkspaceCurrent()) return;
    if (!workspace) {
      return;
    }
    await run(`删除资料《${source.title}》`, async () => {
      const deleted = await sourcesApi.remove(workspace.workspace_id, source.source_id);
      if (!isWorkspaceCurrent()) return;
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      if (!isWorkspaceCurrent()) return;
      setSources(nextSources);
      await refreshWikiFromServer(selectedWikiItemId, {
        mode: wikiGraphMode,
        graphKinds: wikiGraphKindFilters
      });
      if (!isWorkspaceCurrent()) return;
      if (deleted.wiki_retract_task_id) {
        await loadExecution(deleted.wiki_retract_task_id);
        if (!isWorkspaceCurrent()) return;
        setLatestTaskId(deleted.wiki_retract_task_id);
      }
      if (isConversationCurrent()) appendSystemMessage(
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
    const fallback = { title: preferredTitle.trim() || "工作台整理笔记", content: fallbackContent, rewriteMode: "raw" };
    if (!isConversationCurrent()) return fallback;
    try {
      const draft = await sourcesApi.buildNoteSourceDraft(messageId, {
        title: preferredTitle.trim() || "工作台整理笔记"
      });
      const title = draft.title || preferredTitle;
      const content = draft.content || fallbackContent;
      const rewriteMode = draft.rewrite_mode || "template";
      if (!isConversationCurrent()) return { title, content, rewriteMode };
      setSourceDraftTitle(title);
      setSourceDraftContent(content);
      setSourceDraftRewriteMode(rewriteMode);
      return { title, content, rewriteMode };
    } catch {
      if (!isConversationCurrent()) return fallback;
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
    if (!isConversationCurrent()) return;
    if (!lastNoteAssistantMessageId) {
      setStatus("请先完成一次 Note 模式回答");
      return;
    }
    await run("生成中性入库草稿", async () => {
      await prepareNoteSourceDraft(lastNoteAssistantMessageId, sourceDraftTitle, lastNoteAnswerContent);
    }, "chat");
  }

  async function saveNoteAnswerAsSource() {
    if (!isConversationCurrent()) return;
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
        if (!isConversationCurrent()) return;
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
      if (!isConversationCurrent()) return;
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      if (!isConversationCurrent()) return;
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
