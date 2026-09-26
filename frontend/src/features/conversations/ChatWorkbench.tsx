import { lazy, memo, Suspense, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import { ArrowUp, Files, Layers3, LibraryBig } from "lucide-react";
import { isSourceReadableOnly, isSourceSearchable } from "../sources/model";
import { type AnswerMode } from "../../routes";
import { modeExamplePrompts, modeQuestionPlaceholder } from "../shell/modeContext";
import { BrandMark } from "../shell/BrandMark";
import { MessageBubble } from "../answers/MessageBubble";
import { type Message } from "../answers/messageTypes";
import { type SourceAsset } from "../sources/model";
import { type Workspace } from "../workspace/model";
import { AnswerModeSelector } from "./AnswerModeSelector";
import { ChatSourcesPane } from "./ChatSourcesPane";

const LazyArtifactRail = lazy(() => import("../artifacts/ArtifactRail").then((module) => ({
  default: module.ArtifactRail
})));

type PanelTab = "sources" | "studio";

/** 宽屏默认展开右侧面板；窄屏默认收起，避免挤压对话。 */
function prefersOpenPanel() {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
  return window.matchMedia("(min-width: 1200px)").matches;
}

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
  uploadSourceFile?: (file: File) => Promise<void>;
  uploadBusy?: boolean;
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
    sendMessage,
    conversation,
    conversationCount,
    setArtifactComposerOpen,
    artifactRailProps,
    onOpenSourceLibrary,
    uploadSourceFile,
    uploadBusy = false
  } = props;
  const composerBusy = chatBusy;
  const [panelOpen, setPanelOpen] = useState(prefersOpenPanel);
  const [panelTab, setPanelTab] = useState<PanelTab>("sources");
  const conversationRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const examples = modeExamplePrompts(mode);
  const searchableSources = sources.filter(isSourceSearchable);
  const readableOnlySourceCount = sources.filter(isSourceReadableOnly).length;
  const scopeCount = selectedQaSourceIds.length > 0 ? selectedQaSourceIds.length : searchableSources.length;
  const qaUnavailable = mode === "qa" && searchableSources.length === 0;
  const composerActionLabel = qaUnavailable
    ? "等待可检索资料"
    : composerBusy
      ? "回答中…"
      : `发送到 ${currentRouteLabel}`;
  const scopeSummary = mode !== "qa"
    ? `${sources.length} 个来源`
    : searchableSources.length > 0
      ? selectedQaSourceIds.length > 0
        ? `已选 ${scopeCount} / ${searchableSources.length} 个来源`
        : `${searchableSources.length} 个来源`
      : readableOnlySourceCount > 0
        ? `${readableOnlySourceCount} 份仅可阅读 · 无检索索引`
        : sources.length > 0
          ? "来源处理中"
          : "暂无来源";
  const qaUnavailableNote = readableOnlySourceCount > 0
    ? `${readableOnlySourceCount} 份资料仅可阅读，建立索引后才能参与问答。`
    : sources.length > 0
      ? "资料仍在处理，检索索引建立后即可开始提问。"
      : "添加来源并完成索引后即可开始提问。";
  const lastMessage = messages[messages.length - 1];
  const showTyping = composerBusy
    && !(lastMessage?.role === "assistant" && lastMessage.answerStatus === "GENERATING");
  const canSend = !composerBusy && !qaUnavailable && Boolean(conversation) && Boolean(question.trim());

  useEffect(() => {
    const node = conversationRef.current;
    if (!node || (messages.length === 0 && !chatBusy)) {
      return;
    }
    node.scrollTop = node.scrollHeight;
  }, [messages.length, chatBusy]);

  useLayoutEffect(() => {
    const node = textareaRef.current;
    if (!node) return;
    node.style.height = "auto";
    node.style.height = `${Math.min(node.scrollHeight, 220)}px`;
  }, [question]);

  useEffect(() => {
    if (!panelOpen) return;
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      // 仅在面板以抽屉形式覆盖对话时响应 Esc
      if (typeof window.matchMedia === "function" && window.matchMedia("(min-width: 1200px)").matches) return;
      setPanelOpen(false);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [panelOpen]);

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (canSend) sendMessage();
    }
  }

  function showPanel(tab: PanelTab) {
    if (panelOpen && panelTab === tab) {
      setPanelOpen(false);
    } else {
      setPanelTab(tab);
      setPanelOpen(true);
    }
    if (tab === "studio") setArtifactComposerOpen(false);
  }

  function closePanel() {
    setPanelOpen(false);
    setArtifactComposerOpen(false);
  }

  function applyExample(example: string) {
    setQuestion(example);
    window.requestAnimationFrame(() => textareaRef.current?.focus());
  }

  return (
    <section
      className={`chat-page layout${panelOpen ? " is-panel-open" : ""}`}
      data-panel-tab={panelOpen ? panelTab : undefined}
    >
      <div className="chat-panel">
        <header className="page-header chat-header">
          <div className="page-header-title">
            <h2>{conversation?.title || "新对话"}</h2>
            <small>{workspace?.name || "未选择工作台"} · 共享 {sources.length} 个来源 · {conversationCount} 个对话</small>
          </div>
          <div className="page-header-actions panel-switch" role="group" aria-label="侧边面板">
            <button
              type="button"
              className="panel-switch-button"
              aria-label="打开来源"
              aria-pressed={panelOpen && panelTab === "sources"}
              onClick={() => showPanel("sources")}
            >
              <Files size={16} aria-hidden="true" />
              <span>来源</span>
              <span className="panel-switch-count">{sources.length}</span>
            </button>
            <button
              type="button"
              className="panel-switch-button"
              aria-label="打开产物"
              aria-pressed={panelOpen && panelTab === "studio"}
              onClick={() => showPanel("studio")}
              disabled={!workspace}
            >
              <Layers3 size={16} aria-hidden="true" />
              <span>产物</span>
            </button>
          </div>
        </header>

        <div className="conversation" ref={conversationRef} aria-live="polite">
          {messages.length === 0 ? (
            <div className="chat-welcome">
              <BrandMark className="chat-welcome-mark" size={40} />
              <h2 className="chat-welcome-title">今天想从资料里弄清楚什么？</h2>
              <p className="chat-welcome-meta">
                {sources.length > 0
                  ? `「${workspace?.name || "当前工作台"}」的 ${sources.length} 个来源由所有对话共享，其中 ${searchableSources.length} 个可检索`
                  : "这个工作台还没有来源"}
              </p>
              {sources.length > 0 ? (
                <div className="chat-example-list" aria-label="示例问题">
                  {examples.map((example) => (
                    <button
                      key={example}
                      type="button"
                      className="chat-example-chip"
                      disabled={!conversation || composerBusy}
                      onClick={() => applyExample(example)}
                    >
                      {example}
                    </button>
                  ))}
                </div>
              ) : (
                <div className="chat-welcome-empty">
                  <p>添加 PDF、Markdown 或文本资料后，这个工作台下的每个对话都能基于它们回答并给出引用。</p>
                  <button type="button" className="secondary-button chat-empty-library-action" onClick={onOpenSourceLibrary}>
                    <LibraryBig size={15} aria-hidden="true" />
                    前往资料库添加资料
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="message-list">
              {messages.map((message, index) => (
                <div key={`${message.role}-${index}`} className={`bubble message-row ${message.role}`}>
                  {message.role === "assistant" ? <BrandMark className="message-avatar" size={26} /> : null}
                  <div className="message-content">
                    <MessageBubble message={message} />
                  </div>
                </div>
              ))}
              {showTyping ? (
                <div className="bubble message-row assistant chat-typing" aria-label="正在生成回答">
                  <BrandMark className="message-avatar is-thinking" size={26} />
                  <div className="message-content">
                    <span className="chat-typing-dots" aria-hidden="true"><i /><i /><i /></span>
                  </div>
                </div>
              ) : null}
            </div>
          )}
        </div>

        <div className="composer-dock">
          <div className={`composer-box${composerBusy ? " is-busy" : ""}`}>
            <textarea
              ref={textareaRef}
              aria-label={`${currentRouteLabel} 问题`}
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={handleComposerKeyDown}
              rows={1}
              placeholder={qaUnavailable ? qaUnavailableNote : modeQuestionPlaceholder(mode)}
              disabled={!conversation}
            />
            <div className="composer-toolbar">
              <AnswerModeSelector mode={mode} setMode={setMode} disabled={composerBusy} />
              <button
                type="button"
                className="composer-scope"
                title="在“来源”面板中调整本次问答范围"
                onClick={() => {
                  setPanelTab("sources");
                  setPanelOpen(true);
                }}
              >
                {scopeSummary}
              </button>
              <button
                type="button"
                className="composer-send-button"
                aria-label={composerActionLabel}
                title={composerActionLabel}
                onClick={sendMessage}
                disabled={!canSend}
              >
                {composerBusy ? <span className="composer-send-spinner" aria-hidden="true" /> : <ArrowUp size={18} aria-hidden="true" />}
              </button>
            </div>
          </div>
          <p className="composer-footnote">
            {qaUnavailable ? qaUnavailableNote : "回答基于所选来源生成，请结合引用核对。Enter 发送，Shift + Enter 换行"}
          </p>
        </div>
      </div>

      {panelOpen ? (
        <button
          type="button"
          className="context-panel-backdrop"
          aria-label="关闭侧边面板"
          onClick={closePanel}
        />
      ) : null}

      <aside className="context-panel" aria-label="来源与产物" hidden={!panelOpen}>
        <div className="context-panel-view" hidden={panelTab !== "sources"}>
          <ChatSourcesPane
            sources={sources}
            workspace={workspace}
            conversationCount={conversationCount}
            onOpenSourceLibrary={onOpenSourceLibrary}
            selectedSourceIds={selectedQaSourceIds}
            onChangeSelectedSourceIds={(next) => setSelectedQaSourceIds(next)}
            scopeApplies={mode === "qa"}
            uploadSourceFile={uploadSourceFile}
            uploadBusy={uploadBusy}
            disabled={composerBusy}
            onClose={closePanel}
          />
        </div>
        <div className="context-panel-view studio-pane" hidden={panelTab !== "studio"}>
          {panelOpen && panelTab === "studio" ? (
            <Suspense fallback={(
              <div className="artifact-rail artifact-rail-loading" role="status">
                <span className="view-loading-spinner" aria-hidden="true" />
                <span>正在加载产物…</span>
              </div>
            )}>
              <LazyArtifactRail {...artifactRailProps} onCloseArtifactRail={closePanel} />
            </Suspense>
          ) : null}
        </div>
      </aside>
    </section>
  );
});
