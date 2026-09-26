import { lazy, memo, Suspense, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { ArrowRight, LibraryBig, MessageSquareText, PanelRightOpen, Send } from "lucide-react";
import { isSourceReadableOnly, isSourceSearchable } from "../sources/model";
import { type AnswerMode } from "../../routes";
import {
  modeContextLine,
  modeExamplePrompts,
  modeQuestionPlaceholder
} from "../shell/modeContext";
import { MessageBubble } from "../answers/MessageBubble";
import { type Message } from "../answers/messageTypes";
import { type SourceAsset } from "../sources/model";
import { type Workspace } from "../workspace/model";
import { AnswerModeSelector } from "./AnswerModeSelector";
import { ChatSourcesPane } from "./ChatSourcesPane";

const LazyArtifactRail = lazy(() => import("../artifacts/ArtifactRail").then((module) => ({
  default: module.ArtifactRail
})));

export type ChatWorkbenchProps = {
  mode: AnswerMode;
  setMode: (mode: AnswerMode) => void;
  sources: SourceAsset[];
  chatBusy: boolean;
  workspace: Workspace | null;
  messages: Message[];
  question: string;
  setQuestion: (value: string) => void;
  currentRouteLabel: string;
  selectedQaSourceIds: string[];
  setSelectedQaSourceIds: (value: string[] | ((current: string[]) => string[])) => void;
  toggleQaScope: (sourceId: string) => void;
  sendMessage: () => void;
  conversation: { conversation_id: string; title: string } | null;
  conversationCount: number;
  setArtifactComposerOpen: (open: boolean) => void;
  artifactComposerOpen: boolean;
  artifactRailProps: import("../artifacts/ArtifactRailProps").ArtifactRailProps;
  onOpenSourceLibrary: () => void;
};

export const ChatWorkbench = memo(function ChatWorkbench(props: ChatWorkbenchProps) {
  const {
  mode,
  setMode,
  sources,
  chatBusy,
  workspace,
  messages,
  question,
  setQuestion,
  currentRouteLabel,
  selectedQaSourceIds,
  setSelectedQaSourceIds,
  toggleQaScope,
  sendMessage,
  conversation,
  conversationCount,
  setArtifactComposerOpen,
  artifactComposerOpen,
  artifactRailProps,
  onOpenSourceLibrary

  } = props;
  const composerBusy = chatBusy;
  const [artifactRailOpen, setArtifactRailOpen] = useState(false);
  const conversationRef = useRef<HTMLDivElement | null>(null);
  const artifactTriggerRef = useRef<HTMLButtonElement | null>(null);
  const artifactModalRef = useRef<HTMLDivElement | null>(null);
  const examples = modeExamplePrompts(mode);
  const searchableSources = sources.filter(isSourceSearchable);
  const readableOnlySourceCount = sources.filter(isSourceReadableOnly).length;
  const workspaceSummary = workspace?.name || "未选择工作台";
  const qaAvailabilitySummary = searchableSources.length > 0
    ? `${searchableSources.length} / ${sources.length} 份可检索${readableOnlySourceCount > 0 ? ` · ${readableOnlySourceCount} 份仅可阅读` : ""}`
    : readableOnlySourceCount > 0
      ? `${readableOnlySourceCount} 份仅可阅读 · 无检索索引`
      : sources.length > 0
        ? `0 / ${sources.length} 份可检索`
        : "等待上传资料";
  const scopeSummary = selectedQaSourceIds.length > 0
    ? `已限定 ${selectedQaSourceIds.length} 份资料`
    : qaAvailabilitySummary;
  const qaUnavailable = mode === "qa" && searchableSources.length === 0;
  const composerActionLabel = qaUnavailable
    ? "等待可检索资料"
    : composerBusy
      ? "回答中…"
      : `发送到 ${currentRouteLabel}`;
  const qaScopeNarrative = selectedQaSourceIds.length > 0
    ? `当前问答已限定 ${selectedQaSourceIds.length} 份可检索资料，回答会附带对应来源引用。`
    : searchableSources.length > 0
      ? `当前使用此 Workspace 的 ${searchableSources.length} 份可检索资料，回答会附带对应来源引用。`
      : readableOnlySourceCount > 0
        ? `当前无可检索资料；${readableOnlySourceCount} 份资料仅可阅读，建立索引后才能参与 RAG。`
        : sources.length > 0
          ? "资料仍在处理，检索索引建立后即可开始基于证据的问答。"
          : "当前 Workspace 暂无资料，上传并完成检索索引后即可开始基于证据的问答。";

  useEffect(() => {
    const node = conversationRef.current;
    if (!node || (messages.length === 0 && !chatBusy)) {
      return;
    }
    node.scrollTop = node.scrollHeight;
  }, [messages.length, chatBusy]);

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      if (!composerBusy && !qaUnavailable && conversation && question.trim()) {
        sendMessage();
      }
    }
  }

  useEffect(() => {
    if (!artifactRailOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    artifactModalRef.current?.focus();
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) {
        setArtifactRailOpen(false);
        setArtifactComposerOpen(false);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
      artifactTriggerRef.current?.focus();
    };
  }, [artifactRailOpen]);

  function toggleArtifactRail() {
    const nextOpen = !artifactRailOpen;
    setArtifactRailOpen(nextOpen);
    setArtifactComposerOpen(false);
  }

  return (
    <section className="layout">
      <ChatSourcesPane
        sources={sources}
        workspace={workspace}
        conversationCount={conversationCount}
        onOpenSourceLibrary={onOpenSourceLibrary}
      />

      <div className="chat-panel">
        <header className="chat-session-header">
          <div className="chat-session-identity">
            <span className="chat-session-mark" aria-hidden="true"><MessageSquareText size={19} /></span>
            <div className="chat-session-copy">
              <p className="section-label">Current conversation</p>
              <h2>{conversation?.title || "等待会话"}</h2>
              <p className="chat-session-meta">
                <span title={workspaceSummary}>{workspaceSummary}</span>
                <span aria-hidden="true">·</span>
                <span>{sources.length} 份工作台资料</span>
                <span aria-hidden="true">·</span>
                <span>{conversationCount} 个会话</span>
              </p>
            </div>
          </div>
          <div className="chat-header-bar">
            <button
              type="button"
              className="mobile-sources-toggle secondary-button"
              aria-label="打开资料库"
              title="打开资料库"
              onClick={onOpenSourceLibrary}
            >
              <LibraryBig size={15} aria-hidden="true" />
              资料 {sources.length}
            </button>
          </div>
        </header>
        <p className="mode-context-line">
          <span className="mode-context-copy">{modeContextLine(mode)}</span>
          <span className="mode-context-metric">{scopeSummary}</span>
        </p>

        <div className="conversation" ref={conversationRef} aria-live="polite">
          {messages.length === 0 ? (
            <div className="chat-welcome">
              <div className="chat-welcome-heading">
                <span className="chat-welcome-icon" aria-hidden="true"><MessageSquareText size={22} /></span>
                <div>
                  <strong>从工作台资料开始提问</strong>
                  <p>当前会话独立保存，回答会保留来源与证据位置。</p>
                </div>
              </div>
              <div className="chat-knowledge-path" aria-label="工作台资料与当前会话的关系">
                <div className="chat-knowledge-node is-library">
                  <LibraryBig size={18} aria-hidden="true" />
                  <span>
                    <small>共享资料库</small>
                    <strong>{sources.length} 份资料 · {conversationCount} 个会话共用</strong>
                  </span>
                </div>
                <ArrowRight className="chat-knowledge-arrow" size={16} aria-hidden="true" />
                <div className="chat-knowledge-node is-conversation">
                  <MessageSquareText size={18} aria-hidden="true" />
                  <span>
                    <small>当前独立会话</small>
                    <strong title={conversation?.title}>{conversation?.title || "等待会话"}</strong>
                  </span>
                </div>
              </div>
              {sources.length > 0 ? (
                <div className="chat-example-row">
                  {examples.map((example) => (
                    <button
                      key={example}
                      type="button"
                      className="chat-example-chip secondary-button"
                      disabled={!conversation || composerBusy}
                      onClick={() => setQuestion(example)}
                    >
                      <span>{example}</span>
                      <ArrowRight size={14} aria-hidden="true" />
                    </button>
                  ))}
                </div>
              ) : (
                <button type="button" className="chat-empty-library-action" onClick={onOpenSourceLibrary}>
                  <LibraryBig size={16} aria-hidden="true" />
                  前往资料库添加资料
                  <ArrowRight size={14} aria-hidden="true" />
                </button>
              )}
            </div>
          ) : null}
          {messages.map((message, index) => (
            <div key={`${message.role}-${index}`} className={`bubble ${message.role}`}>
              <MessageBubble message={message} />
            </div>
          ))}
          {composerBusy ? (
            <div className="bubble assistant chat-typing" aria-label="正在生成回答">
              <span className="chat-typing-dot" />
              <span className="chat-typing-dot" />
              <span className="chat-typing-dot" />
              <span>正在生成回答…</span>
            </div>
          ) : null}
        </div>

        <div className="composer-dock">
          <label className="input-block composer-block">
            <span>{currentRouteLabel} 问题</span>
            <textarea
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={handleComposerKeyDown}
              rows={1}
              placeholder={modeQuestionPlaceholder(mode)}
              disabled={!conversation}
            />
          </label>
          {mode === "qa" ? (
            <div className="qa-scope-hint">
              <span>{qaScopeNarrative}</span>
              {searchableSources.length > 0 ? (
                <details>
                  <summary>指定本次 QA 的资料范围（可选）</summary>
                  <div className="qa-scope-options">
                    {searchableSources.map((source) => (
                      <button
                        type="button"
                        key={source.source_id}
                        className={selectedQaSourceIds.includes(source.source_id) ? "active filter-pill" : "filter-pill"}
                        onClick={() => toggleQaScope(source.source_id)}
                        disabled={composerBusy}
                      >
                        {source.title}
                      </button>
                    ))}
                    {selectedQaSourceIds.length > 0 ? (
                      <button type="button" className="secondary-button" onClick={() => setSelectedQaSourceIds([])} disabled={composerBusy}>
                        使用全部资料
                      </button>
                    ) : null}
                  </div>
                </details>
              ) : null}
            </div>
          ) : null}
          <div className="composer-actions">
            <AnswerModeSelector mode={mode} setMode={setMode} disabled={composerBusy} />
            <small className="composer-hint">Enter 发送 · Shift+Enter 换行</small>
            <button
              type="button"
              className="primary-action composer-send-button"
              aria-label={composerActionLabel}
              title={composerActionLabel}
              onClick={sendMessage}
              disabled={composerBusy || qaUnavailable || !conversation || !question.trim()}
            >
              <Send size={16} aria-hidden="true" />
            </button>
            {!artifactRailOpen ? (
              <button
                type="button"
                className="secondary-button artifact-rail-trigger"
                aria-label="打开产物"
                title="打开产物工作台"
                onClick={toggleArtifactRail}
                disabled={!workspace}
                ref={artifactTriggerRef}
              >
                <PanelRightOpen size={16} aria-hidden="true" />
                产物
              </button>
            ) : null}
          </div>
        </div>
      </div>

      {artifactRailOpen ? createPortal((
        <div
          className="artifact-modal-backdrop"
          role="presentation"
          onClick={toggleArtifactRail}
        >
          <div
            ref={artifactModalRef}
            className="artifact-modal"
            role="dialog"
            aria-modal="true"
            aria-label="产物工作台"
            tabIndex={-1}
            onClick={(event) => event.stopPropagation()}
          >
            <Suspense fallback={(
              <div className="artifact-rail artifact-rail-loading">
                <p className="section-label">Artifact Studio</p>
                <span>正在加载产物控制台…</span>
              </div>
            )}>
              <LazyArtifactRail
                {...artifactRailProps}
                onCloseArtifactRail={() => {
                  setArtifactRailOpen(false);
                  setArtifactComposerOpen(false);
                }}
              />
            </Suspense>
          </div>
        </div>
      ), document.body) : null}
    </section>
  );
});
