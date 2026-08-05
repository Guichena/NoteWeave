import { lazy, memo, Suspense, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { routes, type AnswerMode } from "../../routes";
import {
  modeContextLine,
  modeExamplePrompts,
  modeQuestionPlaceholder
} from "../shell/modeContext";
import { MessageBubble } from "../answers/MessageBubble";
import { type Message } from "../answers/messageTypes";
import { type SourceAsset } from "../sources/model";
import {
  type ExecutionEvent as StreamEvent,
  type ExecutionTask as TaskStatus
} from "../executions/model";
import { type Workspace } from "../workspace/model";
import {
  buildWaitContextNarrative,
  summarizeRunStatus,
  type WaitContextDetailLine
} from "../../runStatus";
import { type SignalChip } from "../research/model";

const LazyArtifactRail = lazy(() => import("../artifacts/ArtifactRail").then((module) => ({
  default: module.ArtifactRail
})));

export type ChatWorkbenchProps = {
  mode: AnswerMode;
  setMode: (mode: AnswerMode) => void;
  sources: SourceAsset[];
  sourceText: string;
  setSourceText: (value: string) => void;
  uploadSource: () => void;
  chatBusy: boolean;
  uploadBusy: boolean;
  workspace: Workspace | null;
  latestTask: TaskStatus | null;
  latestWorkspaceTaskWaitSignals: SignalChip[];
  latestWorkspaceTaskWaitDetails: WaitContextDetailLine[];
  latestWorkspaceProgressEvent: StreamEvent | null;
  taskEvents: StreamEvent[];
  deleteSource: (source: SourceAsset) => void;
  buildSourceOriginBadge: (source: SourceAsset) => string;
  buildGenericTaskRuntimeSnapshot: (
    event: StreamEvent | null,
    task: TaskStatus | null
  ) => string;
  buildTaskEventNarrative: (event: StreamEvent, task: TaskStatus | null) => string;
  messages: Message[];
  question: string;
  setQuestion: (value: string) => void;
  currentRouteLabel: string;
  selectedQaSourceIds: string[];
  setSelectedQaSourceIds: (value: string[] | ((current: string[]) => string[])) => void;
  toggleQaScope: (sourceId: string) => void;
  sendMessage: () => void;
  conversation: { conversation_id: string } | null;
  setArtifactComposerOpen: (open: boolean) => void;
  artifactComposerOpen: boolean;
  artifactRailProps: import("../artifacts/ArtifactRailProps").ArtifactRailProps;
};

export const ChatWorkbench = memo(function ChatWorkbench({
  mode,
  setMode,
  sources,
  sourceText,
  setSourceText,
  uploadSource,
  chatBusy,
  uploadBusy,
  workspace,
  latestTask,
  latestWorkspaceTaskWaitSignals,
  latestWorkspaceTaskWaitDetails,
  latestWorkspaceProgressEvent,
  taskEvents,
  deleteSource,
  buildSourceOriginBadge,
  buildGenericTaskRuntimeSnapshot,
  buildTaskEventNarrative,
  messages,
  question,
  setQuestion,
  currentRouteLabel,
  selectedQaSourceIds,
  setSelectedQaSourceIds,
  toggleQaScope,
  sendMessage,
  conversation,
  setArtifactComposerOpen,
  artifactComposerOpen,
  artifactRailProps
}: ChatWorkbenchProps) {
  const sourcesBusy = uploadBusy;
  const composerBusy = chatBusy;
  const [artifactRailOpen, setArtifactRailOpen] = useState(false);
  const conversationRef = useRef<HTMLDivElement | null>(null);
  const examples = modeExamplePrompts(mode);
  const readySources = sources.filter(
    (source) => source.status === "READY"
      && (source.index_status === "INDEXED" || source.index_status === "DISABLED")
  );

  useEffect(() => {
    const node = conversationRef.current;
    if (!node) {
      return;
    }
    node.scrollTop = node.scrollHeight;
  }, [messages.length, chatBusy]);

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      if (!composerBusy && conversation && question.trim()) {
        sendMessage();
      }
    }
  }

  function toggleArtifactRail() {
    const nextOpen = !artifactRailOpen;
    setArtifactRailOpen(nextOpen);
    setArtifactComposerOpen(nextOpen);
  }

  return (
    <section className={artifactRailOpen ? "layout layout-with-inspector" : "layout"}>
      <aside className="sources-pane source-drawer" aria-label="资料库">
        <div className="sources-pane-header">
          <p className="section-label">Sources</p>
          <small>{sources.length} 份资料</small>
        </div>
        <div className="source-drawer-body">
          <label className="input-block">
            <span>粘贴文本资料</span>
            <textarea
              value={sourceText}
              onChange={(event) => setSourceText(event.target.value)}
              rows={4}
              placeholder="把要分析的文本粘贴到这里，然后点击上传并解析。"
            />
          </label>
          <button onClick={uploadSource} disabled={sourcesBusy || !workspace || !sourceText.trim()}>
            {sourcesBusy ? "上传中…" : "上传并解析"}
          </button>
          {!workspace ? (
            <p className="phase-note">请先创建或选择工作台，再上传资料。</p>
          ) : null}
          {latestTask && (
            <div className="task-card source-task-card">
              <strong>资料处理任务</strong>
              <span>{latestTask.task_type} · {summarizeRunStatus(latestTask.task_status)} · {latestTask.progress_phase}</span>
              <span>{latestTask.progress_message}</span>
              {buildWaitContextNarrative(latestTask.wait_context) ? (
                <small>{buildWaitContextNarrative(latestTask.wait_context)}</small>
              ) : null}
              {latestWorkspaceTaskWaitSignals.length > 0 ? (
                <div className="signal-chip-row artifact-wait-signal-row">
                  {latestWorkspaceTaskWaitSignals.map((chip, index) => (
                    <span key={`workspace-task-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>
                      {chip.label}: {chip.value}
                    </span>
                  ))}
                </div>
              ) : null}
              {latestWorkspaceTaskWaitDetails.length > 0 ? (
                <div className="artifact-runtime-trace">
                  {latestWorkspaceTaskWaitDetails.map((line, index) => (
                    <small key={`workspace-task-wait-detail-${index}`} className="artifact-runtime-trace-line">
                      <strong>{line.label}</strong> · {line.value}
                    </small>
                  ))}
                </div>
              ) : null}
              {buildGenericTaskRuntimeSnapshot(latestWorkspaceProgressEvent, latestTask) ? (
                <small>{buildGenericTaskRuntimeSnapshot(latestWorkspaceProgressEvent, latestTask)}</small>
              ) : null}
              {taskEvents.slice(-4).map((event, index) => (
                <small key={`${event.event}-${index}`}>{buildTaskEventNarrative(event, latestTask)}</small>
              ))}
            </div>
          )}
          {sources.length > 0 ? (
            <div className="source-list">
              <strong className="source-list-title">工作台资料</strong>
              {sources.map((source) => (
                <div key={source.source_id} className="source-list-item">
                  <div className="source-list-copy">
                    <strong>{source.title}</strong>
                    <small>
                      {source.status} · {source.index_status}
                      {source.generated_by === "research_agent" ? ` · ${buildSourceOriginBadge(source)}` : ""}
                    </small>
                  </div>
                  <button
                    type="button"
                    className="inline-action secondary-button"
                    onClick={() => void deleteSource(source)}
                    disabled={sourcesBusy}
                  >
                    删除
                  </button>
                </div>
              ))}
            </div>
          ) : (
            <p className="empty-state source-empty">
              还没有资料。上传后即可在 QA / Note / Wiki 中检索回答。
            </p>
          )}
        </div>
      </aside>

      <div className="chat-panel">
        <div className="mode-tabs" role="tablist" aria-label="回答模式">
          {routes.map((route) => (
            <button
              key={route.key}
              type="button"
              role="tab"
              aria-selected={route.key === mode}
              className={route.key === mode ? "active" : ""}
              title={route.description}
              onClick={() => setMode(route.key)}
            >
              {route.label}
            </button>
          ))}
        </div>
        <p className="mode-context-line">{modeContextLine(mode)}</p>

        <div className="conversation" ref={conversationRef} aria-live="polite">
          {messages.length === 0 ? (
            <div className="chat-welcome">
              <strong>开始一次研究对话</strong>
              <p>切换模式后点选示例问题，或直接在下方输入。Enter 发送，Shift+Enter 换行。</p>
              <div className="chat-example-row">
                {examples.map((example) => (
                  <button
                    key={example}
                    type="button"
                    className="chat-example-chip secondary-button"
                    disabled={!conversation || composerBusy}
                    onClick={() => setQuestion(example)}
                  >
                    {example}
                  </button>
                ))}
              </div>
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
              rows={3}
              placeholder={modeQuestionPlaceholder(mode)}
              disabled={!conversation}
            />
          </label>
          {mode === "qa" ? (
            <div className="qa-scope-hint">
              当前问答范围：{sources.length > 0 ? `此 Workspace 的 ${sources.length} 份资料` : "暂未上传资料"}。
              {selectedQaSourceIds.length > 0
                ? ` 已显式限定 ${selectedQaSourceIds.length} 份资料。`
                : " 回答会附带对应来源引用。"}
              {readySources.length > 0 ? (
                <details>
                  <summary>指定本次 QA 的资料范围（可选）</summary>
                  <div className="qa-scope-options">
                    {readySources.map((source) => (
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
              ) : " 先上传资料后即可开始基于证据的问答。"}
            </div>
          ) : null}
          <div className="composer-actions">
            <small className="composer-hint">Enter 发送 · Shift+Enter 换行</small>
            <button
              type="button"
              onClick={sendMessage}
              disabled={composerBusy || !conversation || !question.trim()}
            >
              {composerBusy ? "回答中…" : `发送到 ${currentRouteLabel}`}
            </button>
            <button
              type="button"
              className="secondary-button"
              onClick={toggleArtifactRail}
              disabled={!workspace}
            >
              {artifactRailOpen ? "关闭产物" : "打开产物"}
            </button>
          </div>
        </div>
      </div>

      {artifactRailOpen ? <Suspense fallback={(
        <aside className="artifact-rail artifact-rail-loading">
          <p className="section-label">Artifact Studio</p>
          <span>正在加载产物控制台…</span>
        </aside>
      )}>
        <LazyArtifactRail {...artifactRailProps} />
      </Suspense> : null}
    </section>
  );
});
