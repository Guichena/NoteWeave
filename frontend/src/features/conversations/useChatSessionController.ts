import { useEffect, useRef, useState } from "react";
import type { AnswerMode } from "../../routes";
import { routes } from "../../routes";
import { AnswerRunWaitTimeoutError,
  AnswerRunStore,
  createAnswerRunState,
  reduceAnswerRunState
} from "../answers/store";
import { answersApi } from "../answers/api";
import type { AnswerFeedbackInput, AnswerRunState } from "../answers/model";
import {
  type Message,
  updateAssistantMessageByRun,
  updateLastAssistantMessage
} from "../answers/messageTypes";
import { useChatSourceActions } from "./useChatSourceActions";
import { resolveMissingContentState } from "./emptyAnswer";
import { isSourceSearchable, type SourceAsset } from "../sources/model";
import type { Workspace } from "../workspace/model";
import type { Conversation } from "./model";
import { ConversationStreamClient } from "../../shared/event-stream";
import { streamEvents } from "../../shared/event-stream/streamEvent";
import type { ShellRun } from "../shell/useShellBusy";
import { readAnswerModePreference, writeAnswerModePreference } from "./answerModePreference";

export type UseChatSessionControllerInput = {
  workspace: Workspace | null;
  conversation: Conversation | null;
  statusActive: boolean;
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
  statusActive,
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
  const statusActiveRef = useRef(statusActive);
  statusActiveRef.current = statusActive;
  const [mode, setModeState] = useState<AnswerMode>(readAnswerModePreference);
  const [sourceText, setSourceText] = useState("");
  const [question, setQuestion] = useState("");
  const [lastNoteAssistantMessageId, setLastNoteAssistantMessageId] = useState("");
  const [lastNoteAnswerContent, setLastNoteAnswerContent] = useState("");
  const [sourceDraftTitle, setSourceDraftTitle] = useState("工作台整理笔记");
  const [sourceDraftContent, setSourceDraftContent] = useState("");
  const [sourceDraftRewriteMode, setSourceDraftRewriteMode] = useState("");
  const [selectedQaSourceIds, setSelectedQaSourceIds] = useState<string[]>([]);
  // 输入框上的深度研究开关：打开时这一轮发起深度研究，发送后自动关闭，之后的追问照常问答
  const [deepResearch, setDeepResearch] = useState(false);
  const [conversationStreamConnected, setConversationStreamConnected] = useState(false);

  // NR12：草稿绑定作用域——工作台/会话切换后旧草稿不得在新作用域提交。
  // 问题输入、Note 入库草稿与来源选择一并复位，避免 B 工作台拿到 A 的内容
  // 或把 A 的 messageId 保存结果误报为 B 的成功。
  const draftScopeKey = `${workspace?.workspace_id ?? ""}:${conversation?.conversation_id ?? ""}`;
  const draftScopeRef = useRef(draftScopeKey);
  useEffect(() => {
    if (draftScopeRef.current === draftScopeKey) {
      return;
    }
    draftScopeRef.current = draftScopeKey;
    setSourceText("");
    setQuestion("");
    setSourceDraftTitle("工作台整理笔记");
    setSourceDraftContent("");
    setSourceDraftRewriteMode("");
    setSelectedQaSourceIds([]);
    setLastNoteAssistantMessageId("");
    setLastNoteAnswerContent("");
  }, [draftScopeKey]);

  function setMode(nextMode: AnswerMode) {
    setModeState(nextMode);
    writeAnswerModePreference(nextMode);
  }

  useEffect(() => {
    setSelectedQaSourceIds((current) => {
      const searchableIds = new Set(sources.filter(isSourceSearchable).map((source) => source.source_id));
      const next = current.filter((sourceId) => searchableIds.has(sourceId));
      return next.length === current.length ? current : next;
    });
  }, [sources]);

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
      onError: () => {
        if (statusActiveRef.current) {
          setStatus("实时更新暂不可用，回答将从任务快照同步");
        }
      },
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
          if (statusActiveRef.current) {
            setStatus("会话快照格式无效，将等待下一次重连恢复");
          }
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

  const {
    uploadSource,
    uploadSourceFile,
    uploads,
    dismissUpload,
    deleteSource,
    prepareNoteSourceDraft,
    rewriteNoteSourceDraft,
    saveNoteAnswerAsSource
  } = useChatSourceActions({
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
  });



  async function waitForRunWithAuthorityFallback(answerRunId: string, roundsLeft = 4) {
    try {
      await answerRunStoreRef.current.waitFor(answerRunId);
      return;
    } catch (error) {
      if (!(error instanceof AnswerRunWaitTimeoutError)) {
        throw error;
      }
      const authoritative = await reconcileAnswerRun(answerRunId);
      const status = authoritative?.status ?? "GENERATING";
      if (status !== "GENERATING" && status !== "FINALIZING") {
        // 权威状态已是终态：把结果写回 store，让 waitFor 后续逻辑正常走完
        answerRunStoreRef.current.reconcileRun({
          id: answerRunId,
          content: authoritative?.content ?? "",
          status,
          error_message: authoritative?.error ?? ""
        });
        return;
      }
      if (roundsLeft <= 1) {
        // 有限等待耗尽：保留当前状态返回（长生成由 SSE 继续推进界面）
        return;
      }
      await waitForRunWithAuthorityFallback(answerRunId, roundsLeft - 1);
    }
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

  /**
   * 回答没有正文时的界面状态：严格按权威状态分支，不再把失败或取消伪装成完成。
   * 具体判定见 {@link resolveMissingContentState}。
   */
  function settleEmptyAnswer(answerRunId: string, state?: AnswerRunState) {
    const next = resolveMissingContentState(answerRunId, state);
    setMessages((current) => updateAssistantMessageByRun(current, answerRunId, next));
  }

  async function sendConversationPrompt(
    content: string,
    runLabel = `${currentRoute().label} 提问`,
    options: { mode?: AnswerMode; regeneratedFrom?: string } = {}
  ) {
    const turnMode = options.mode ?? mode;
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
          answer_mode: turnMode.toUpperCase(),
          client_request_id: `${turnMode}-${Date.now()}`,
          source_scope_source_ids: turnMode === "qa" ? selectedQaSourceIds : [],
          ...(options.regeneratedFrom ? { regenerated_from: options.regeneratedFrom } : {})
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
      setQuestion((current) => (current.trim() === trimmed ? "" : current));
      const buffered = answerRunStoreRef.current.get(sent.answer_run_id);
      if (turnMode === "note") {
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
        answerMode: turnMode,
        citations: buffered?.citations ?? [],
        answerRunId: sent.answer_run_id,
        answerStatus: buffered?.status ?? "GENERATING",
        answerError: buffered?.error ?? "",
        retrievalDegraded: sent.retrieval_degraded === true,
        retrievalDegradationReasons: sent.retrieval_degradation_reasons ?? []
      }]);
      if (conversationStreamConnected) {
        // NR27：SSE 已连通但回答超过等待窗口时——查询权威状态而非直接放弃；
        // 仍在运行则继续有限等待（再等一轮），终态照常走后续恢复与 Note 草稿
        await waitForRunWithAuthorityFallback(sent.answer_run_id);
        const completed = await reconcileAnswerRun(sent.answer_run_id);
        if (turnMode === "note" && completed?.content) {
          setLastNoteAnswerContent(completed.content);
          await prepareNoteSourceDraft(sent.assistant_message_id, sourceDraftTitle, completed.content);
        }
        if (!completed?.content) {
          settleEmptyAnswer(sent.answer_run_id, completed);
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
      const reconciled = await reconcileAnswerRun(sent.answer_run_id, fallback);
      if (!reconciled?.content) {
        // 先按权威状态回填，再判断是否真的没有正文，避免用兜底文案覆盖 FAILED/CANCELLED
        settleEmptyAnswer(sent.answer_run_id, reconciled ?? fallback);
      }
      if (turnMode === "note" && reconciled?.content) {
        setLastNoteAnswerContent(reconciled.content);
        await prepareNoteSourceDraft(sent.assistant_message_id, sourceDraftTitle, reconciled.content);
      }
    }, "chat");
  }

  async function sendMessage() {
    if (deepResearch) {
      await sendDeepResearchPrompt(question);
      return;
    }
    await sendConversationPrompt(question);
  }

  /**
   * 在当前对话里发起深度研究：后端把这一轮的问答记作研究轮次并启动研究，研究会带上这段对话的上文；
   * 助手消息按研究编号展示进度与报告，研究完成后报告正文写回这条消息，之后的普通追问能看到它。
   */
  async function sendDeepResearchPrompt(content: string) {
    if (!conversation) {
      setStatus("请先创建工作台和会话");
      return;
    }
    const trimmed = content.trim();
    if (!trimmed) {
      return;
    }
    await run("深度研究", async () => {
      setMessages((current) => [...current, { role: "user", content: trimmed }]);
      let sent;
      try {
        sent = await answersApi.send(conversation.conversation_id, {
          content: trimmed,
          answer_mode: "DEEP_RESEARCH",
          client_request_id: `research-${Date.now()}`,
          source_scope_source_ids: selectedQaSourceIds
        });
      } catch (error) {
        setMessages((current) => {
          const rollbackIndex = current.map((message) => message.role === "user" && message.content === trimmed)
            .lastIndexOf(true);
          return rollbackIndex < 0 ? current : current.filter((_, index) => index !== rollbackIndex);
        });
        throw error;
      }
      setQuestion((current) => (current.trim() === trimmed ? "" : current));
      setDeepResearch(false);
      if (sent.research_run_id) {
        setMessages((current) => [...current, {
          role: "assistant",
          content: "",
          researchRunId: sent.research_run_id ?? undefined,
          answerStatus: "GENERATING"
        }]);
      }
    }, "chat");
  }

  /** 用触发这条回答的用户问题重新提问，并告诉后端这是对原回答的重新生成。 */
  async function regenerateAnswer(answerRunId: string) {
    const index = messages.findIndex((message) => message.answerRunId === answerRunId);
    const original = messages[index];
    const userMessage = messages.slice(0, Math.max(index, 0)).reverse().find((message) => message.role === "user");
    if (index < 0 || !userMessage) {
      setStatus("找不到这条回答对应的问题，无法重新生成");
      return;
    }
    await sendConversationPrompt(userMessage.content, "重新生成", {
      mode: original.answerMode,
      regeneratedFrom: answerRunId
    });
  }

  async function sendAnswerFeedback(answerRunId: string, input: AnswerFeedbackInput) {
    if (!workspace) throw new Error("请先选择工作台");
    await answersApi.sendFeedback(workspace.workspace_id, answerRunId, input);
  }

  /** 停止正在生成的回答：调用后端取消接口，并把权威终态写回消息与运行状态。 */
  async function cancelAnswer(answerRunId: string) {
    if (!workspace) {
      setStatus("请先选择工作台");
      return;
    }
    try {
      const cancelled = await answersApi.cancel(workspace.workspace_id, answerRunId);
      const state = answerRunStoreRef.current.reconcileRun({
        id: answerRunId,
        status: cancelled?.status || "CANCELLED",
        content: cancelled?.content ?? "",
        error_code: cancelled?.error_code,
        error_message: cancelled?.error_message || "回答已取消"
      });
      setMessages((current) => updateAssistantMessageByRun(current, answerRunId, state));
      setStatus("已停止生成");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : "停止生成失败，请稍后重试");
    }
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
    uploadSourceFile,
    uploads,
    dismissUpload,
    deleteSource,
    rewriteNoteSourceDraft,
    saveNoteAnswerAsSource,
    sendMessage,
    sendConversationPrompt,
    deepResearch,
    setDeepResearch,
    regenerateAnswer,
    cancelAnswer,
    sendAnswerFeedback
  };
}

export type ChatSessionController = ReturnType<typeof useChatSessionController>;
