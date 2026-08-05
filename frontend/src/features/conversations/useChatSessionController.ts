import { useEffect, useRef, useState } from "react";
import type { AnswerMode } from "../../routes";
import { routes } from "../../routes";
import {
  AnswerRunStore,
  createAnswerRunState,
  reduceAnswerRunState
} from "../answers/store";
import { answersApi } from "../answers/api";
import type { AnswerRunState } from "../answers/model";
import {
  type Message,
  updateAssistantMessageByRun,
  updateLastAssistantMessage
} from "../answers/messageTypes";
import { sourcesApi } from "../sources/api";
import type { SourceAsset } from "../sources/model";
import type { Workspace } from "../workspace/model";
import type { Conversation } from "./model";
import { ConversationStreamClient } from "../../shared/event-stream";
import { streamEvents } from "../../shared/event-stream/streamEvent";
import type { ShellRun } from "../shell/useShellBusy";

type UseChatSessionControllerInput = {
  workspace: Workspace | null;
  conversation: Conversation | null;
  run: ShellRun;
  setStatus: (status: string) => void;
  sources: SourceAsset[];
  setSources: (sources: SourceAsset[] | ((current: SourceAsset[]) => SourceAsset[])) => void;
  messages: Message[];
  setMessages: (messages: Message[] | ((current: Message[]) => Message[])) => void;
  refreshWikiHome: () => Promise<unknown>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  refreshWikiFromServer: (...args: any[]) => Promise<unknown>;
  wikiEnabled: boolean;
  wikiGraphMode: string;
  wikiGraphKindFilters: string[];
  loadExecution: (taskId: string) => Promise<{ task: { task_status?: string; [key: string]: unknown } }>;
  setLatestTaskId: (id: string) => void;
  selectedWikiItemId: string;
};

export function useChatSessionController({
  workspace,
  conversation,
  run,
  setStatus,
  sources,
  setSources,
  messages,
  setMessages,
  refreshWikiHome,
  refreshWikiFromServer,
  wikiEnabled,
  wikiGraphMode,
  wikiGraphKindFilters,
  loadExecution,
  setLatestTaskId,
  selectedWikiItemId
}: UseChatSessionControllerInput) {
  const answerRunStoreRef = useRef(new AnswerRunStore());
  const [mode, setMode] = useState<AnswerMode>("qa");
  const [sourceText, setSourceText] = useState(
    "NoteWeave 支持在同一个研究工作台里使用问答 RAG、Marginalia 式 Note 检索链路和 WebKonra / WeKnora 式 Wiki 检索链路。"
  );
  const [question, setQuestion] = useState("Note 和 Wiki 两种检索方式有什么区别？");
  const [lastNoteAssistantMessageId, setLastNoteAssistantMessageId] = useState("");
  const [lastNoteAnswerContent, setLastNoteAnswerContent] = useState("");
  const [sourceDraftTitle, setSourceDraftTitle] = useState("工作台整理笔记");
  const [sourceDraftContent, setSourceDraftContent] = useState("");
  const [sourceDraftRewriteMode, setSourceDraftRewriteMode] = useState("");
  const [selectedQaSourceIds, setSelectedQaSourceIds] = useState<string[]>([]);
  const [conversationStreamConnected, setConversationStreamConnected] = useState(false);

  useEffect(() => {
    if (!workspace || !conversation) {
      setConversationStreamConnected(false);
      return;
    }
    const streamPath = `/api/v2/workspaces/${workspace.workspace_id}`
      + `/conversations/${conversation.conversation_id}/events`;
    const client = new ConversationStreamClient(streamPath, {
      onConnectionChange: (connected) => {
        if (!connected) {
          setConversationStreamConnected(false);
        }
      },
      onError: () => setStatus("会话流正在重连，回答状态将从快照恢复"),
      onEvent: (event) => {
        try {
          const updates = answerRunStoreRef.current.applyConversationEvent(event);
          if (event.event === "conversation.snapshot") {
            setConversationStreamConnected(true);
          }
          if (updates.length > 0) {
            setMessages((current) => updates.reduce(
              (next, answer) => updateAssistantMessageByRun(next, answer.runId, answer),
              current
            ));
          }
        } catch {
          setStatus("会话快照格式无效，将等待下一次重连恢复");
        }
      }
    });
    client.start();
    return () => {
      client.stop();
      setConversationStreamConnected(false);
      answerRunStoreRef.current.clear("会话已切换");
    };
  }, [workspace?.workspace_id, conversation?.conversation_id, setStatus]);

  function currentRoute() {
    return routes.find((route) => route.key === mode) ?? routes[0];
  }

  function toggleQaScope(sourceId: string) {
    setSelectedQaSourceIds((current) => (
      current.includes(sourceId)
        ? current.filter((entry) => entry !== sourceId)
        : [...current, sourceId]
    ));
  }

  function appendSystemMessage(content: string) {
    setMessages((current) => [...current, { role: "system", content }]);
  }

  async function uploadSource() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("上传资料并解析", async () => {
      const completed = await sourcesApi.uploadText(workspace.workspace_id, sourceText);
      const execution = await loadExecution(completed.task_id);
      const task = execution.task;
      const nextSources = await sourcesApi.list(workspace.workspace_id);
      setLatestTaskId(completed.task_id);
      await refreshWikiHome();
      setSources(nextSources);
      appendSystemMessage(
        `资料已上传并解析：source=${completed.source_id}，parse=${completed.parse_status}，index=${completed.index_status}，task=${task.task_status}。${wikiEnabled ? "Wiki 构建已开启，本次资料变更已进入 Wiki ingest 队列。" : "Wiki 构建未开启，本次只进入问答/Note 检索索引。"}`
      );
    }, "upload");
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
      let rewriteMode = sourceDraftRewriteMode || "manual";
      if (!content) {
        const draft = await prepareNoteSourceDraft(
          lastNoteAssistantMessageId,
          title || "工作台整理笔记",
          lastNoteAnswerContent
        );
        title = draft.title.trim() || title;
        content = draft.content.trim();
        rewriteMode = draft.rewriteMode;
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
        `已确认入库资料：${saved.title || title}（source=${saved.source_id}，parse=${saved.parse_status}，index=${saved.index_status}，rewrite=${rewriteMode}）`
      );
    }, "chat");
  }

  async function reconcileAnswerRun(answerRunId: string, fallback?: AnswerRunState) {
    if (!workspace) {
      return answerRunStoreRef.current.get(answerRunId) ?? fallback;
    }
    const snapshot = await answersApi.getRun(workspace.workspace_id, answerRunId);
    const current = answerRunStoreRef.current.get(answerRunId);
    if (!current && fallback) {
      answerRunStoreRef.current.reconcileRun({
        id: fallback.runId,
        status: fallback.status,
        content: fallback.content,
        error_message: fallback.error
      });
    }
    const reconciled = answerRunStoreRef.current.reconcileRun(snapshot);
    setMessages((currentMessages) => updateAssistantMessageByRun(currentMessages, answerRunId, reconciled));
    return reconciled;
  }

  async function sendConversationPrompt(content: string, runLabel = `${currentRoute().label} 提问`) {
    if (!conversation) {
      setStatus("请先创建工作台和会话");
      return;
    }
    const trimmed = content.trim();
    if (!trimmed) {
      return;
    }
    await run(runLabel, async () => {
      setMessages((current) => [...current, { role: "user", content: trimmed }]);
      let sent;
      try {
        sent = await answersApi.send(conversation.conversation_id, {
          content: trimmed,
          answer_mode: mode.toUpperCase(),
          client_request_id: `${mode}-${Date.now()}`,
          source_scope_source_ids: mode === "qa" ? selectedQaSourceIds : []
        });
      } catch (error) {
        setMessages((current) => {
          const rollbackIndex = [...current]
            .map((message, index) => ({ message, index }))
            .reverse()
            .find(({ message }) => message.role === "user" && message.content === trimmed)?.index;
          if (rollbackIndex === undefined) {
            return current;
          }
          return current.filter((_, index) => index !== rollbackIndex);
        });
        throw error;
      }
      const buffered = answerRunStoreRef.current.get(sent.answer_run_id);
      if (mode === "note") {
        setLastNoteAssistantMessageId(sent.assistant_message_id);
        setLastNoteAnswerContent(buffered?.content ?? "");
        setSourceDraftContent("");
        setSourceDraftRewriteMode("");
        setSourceDraftTitle((current) => current.trim() || "工作台整理笔记");
      } else {
        setLastNoteAssistantMessageId("");
        setLastNoteAnswerContent("");
        setSourceDraftContent("");
        setSourceDraftRewriteMode("");
      }
      setMessages((current) => [...current, {
        role: "assistant",
        content: buffered?.content ?? "",
        answerMode: mode,
        citations: buffered?.citations ?? [],
        answerRunId: sent.answer_run_id,
        answerStatus: buffered?.status ?? "GENERATING",
        answerError: buffered?.error ?? ""
      }]);
      if (conversationStreamConnected) {
        await answerRunStoreRef.current.waitFor(sent.answer_run_id);
        const completed = await reconcileAnswerRun(sent.answer_run_id);
        if (mode === "note" && completed?.content) {
          setLastNoteAnswerContent(completed.content);
          await prepareNoteSourceDraft(sent.assistant_message_id, sourceDraftTitle, completed.content);
        }
        if (!completed?.content) {
          setMessages((current) => updateAssistantMessageByRun(current, sent.answer_run_id, {
            ...(completed ?? createAnswerRunState(sent.answer_run_id)),
            content: "后端已完成回答，但没有返回 delta 内容。",
            status: "COMPLETED"
          }));
        }
        return;
      }
      let fallback = createAnswerRunState(sent.answer_run_id);
      await streamEvents(sent.answer_stream_url || sent.stream_url, (event) => {
        fallback = reduceAnswerRunState(fallback, event.event, event.data);
        if (fallback.status === "FAILED" || fallback.status === "CANCELLED") {
          setMessages((current) => updateLastAssistantMessage(current, fallback));
          throw new Error(fallback.error || "回答流失败");
        }
        setMessages((current) => updateLastAssistantMessage(current, fallback));
      });
      if (!fallback.content) {
        setMessages((current) => updateLastAssistantMessage(current, {
          ...fallback,
          content: "后端已完成回答，但没有返回 delta 内容。"
        }));
      }
      const reconciled = await reconcileAnswerRun(sent.answer_run_id, fallback);
      if (mode === "note" && reconciled?.content) {
        setLastNoteAnswerContent(reconciled.content);
        await prepareNoteSourceDraft(sent.assistant_message_id, sourceDraftTitle, reconciled.content);
      }
    }, "chat");
  }

  async function sendMessage() {
    await sendConversationPrompt(question);
  }

  return {
    mode,
    setMode,
    sourceText,
    setSourceText,
    question,
    setQuestion,
    messages,
    setMessages,
    appendSystemMessage,
    lastNoteAssistantMessageId,
    lastNoteAnswerContent,
    sourceDraftTitle,
    setSourceDraftTitle,
    sourceDraftContent,
    setSourceDraftContent,
    sourceDraftRewriteMode,
    selectedQaSourceIds,
    setSelectedQaSourceIds,
    toggleQaScope,
    currentRoute,
    uploadSource,
    deleteSource,
    rewriteNoteSourceDraft,
    saveNoteAnswerAsSource,
    sendMessage,
    sendConversationPrompt
  };
}

export type ChatSessionController = ReturnType<typeof useChatSessionController>;
