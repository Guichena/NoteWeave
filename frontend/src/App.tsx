import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { WorkbenchShell } from "./features/shell/WorkbenchShell";
import { useShellBusy } from "./features/shell/useShellBusy";
import { useWorkbenchNavigation } from "./features/shell/useWorkbenchNavigation";
import { type AppView, parseAppLocation } from "./features/shell/viewRoute";
import { useWorkspaceSession } from "./features/workspace/useWorkspaceSession";
import { WorkspaceSettingsPanel } from "./features/workspace/WorkspaceSettingsPanel";
import { isSourceReady, type SourceAsset } from "./features/sources/model";
import { useChatSessionController } from "./features/conversations/useChatSessionController";
import { buildChatWorkbenchProps } from "./features/conversations/buildChatWorkbenchProps";
import { useWikiWorkbenchController } from "./features/knowledge/useWikiWorkbenchController";
import { buildWikiWorkbenchProps } from "./features/knowledge/buildWikiWorkbenchProps";
import { useResearchWorkbenchController } from "./features/research/useResearchWorkbenchController";
import { buildResearchWorkbenchProps } from "./features/research/buildResearchWorkbenchProps";
import { useExecutionRegistry } from "./features/executions/useExecutionRegistry";
import { isExecutionTerminal } from "./features/executions/api";
import { buildTaskWaitPresentation } from "./features/executions/taskPresentation";
import { useArtifactWorkspace } from "./features/artifacts/useArtifactWorkspace";
import { useArtifactStudioCatalog } from "./features/artifacts/useArtifactStudioCatalog";
import { useArtifactComposerActions } from "./features/artifacts/useArtifactComposerActions";
import { buildArtifactRailProps } from "./features/artifacts/buildArtifactRailProps";
import {
  ARTIFACT_STUDIO_PRESENTATION,
  ARTIFACT_STUDIO_SKILL_ORDER
} from "./features/artifacts/skillCatalog";
import { type Message } from "./features/answers/messageTypes";
import { ChunkLoadBoundary } from "./ChunkLoadBoundary";
import { buildSourceLibraryWorkbenchProps } from "./features/sources/buildSourceLibraryWorkbenchProps";
import { sourcesApi } from "./features/sources/api";

const LazyChatWorkbench = lazy(() =>
  import("./features/conversations/ChatWorkbench").then((m) => ({ default: m.ChatWorkbench }))
);
const LazyWikiWorkbench = lazy(() =>
  import("./features/knowledge/WikiWorkbench").then((m) => ({ default: m.WikiWorkbench }))
);
const LazyResearchWorkbenchView = lazy(() =>
  import("./features/research/ResearchWorkbenchView").then((m) => ({ default: m.ResearchWorkbenchView }))
);
const LazyMemoryReviewWorkbench = lazy(() =>
  import("./features/memory/MemoryReviewWorkbench").then((m) => ({ default: m.MemoryReviewWorkbench }))
);
const LazySourceLibraryWorkbench = lazy(() =>
  import("./features/sources/SourceLibraryWorkbench").then((m) => ({ default: m.SourceLibraryWorkbench }))
);

function ViewFallback({ label }: { label: string }) {
  return (
    <div className="workbench-page view-loading" aria-busy="true" aria-live="polite">
      <p className="section-label">{label}</p>
      <div className="view-loading-body">
        <span className="view-loading-spinner" aria-hidden="true" />
        <span>正在加载工作台…</span>
      </div>
      <div className="view-loading-skeleton" aria-hidden="true">
        <div className="skeleton-line w-40" />
        <div className="skeleton-line w-70" />
        <div className="skeleton-line w-55" />
      </div>
    </div>
  );
}

export function App() {
  const {
    status,
    setStatus,
    run,
    isBusy,
    chatBusy,
    uploadBusy,
    artifactBusy,
    shellBusy,
    researchBusy,
    wikiBusy
  } = useShellBusy();
  const [workspaceSettingsOpen, setWorkspaceSettingsOpen] = useState(false);
  const [view, setView] = useState<AppView>(() =>
    parseAppLocation(typeof window !== "undefined" ? window.location.pathname : "/").view
  );
  const [sources, setSources] = useState<SourceAsset[]>([]);
  const [latestTaskId, setLatestTaskId] = useState("");
  const [latestResearchTaskId, setLatestResearchTaskId] = useState("");
  const [messages, setMessages] = useState<Message[]>([]);

  const scopeResetRef = useRef({
    resetResearch: () => undefined as void,
    clearWiki: () => undefined as void,
    clearArtifact: () => undefined as void,
    clearQaScope: () => undefined as void,
    clearResearchScope: () => undefined as void
  });
  const researchHydratedRef = useRef(false);
  const sourceParseSyncKeysRef = useRef(new Set<string>());

  const appendSystemMessage = useCallback((content: string) => {
    setMessages((current) => [...current, { role: "system", content }]);
  }, []);

  const {
    workspace,
    workspaces,
    conversation,
    conversations,
    sessionLoading,
    switchWorkspace,
    switchConversation,
    createWorkspace,
    createConversation
  } = useWorkspaceSession({
    run,
    setStatus,
    replaceMessages: setMessages,
    replaceSources: setSources,
    resetWorkspaceScope: () => {
      scopeResetRef.current.resetResearch();
      scopeResetRef.current.clearQaScope();
      scopeResetRef.current.clearResearchScope();
      setLatestTaskId("");
      setLatestResearchTaskId("");
      scopeResetRef.current.clearArtifact();
      scopeResetRef.current.clearWiki();
    }
  });

  const {
    records: executionRecords,
    trackedTaskIds,
    getExecution,
    loadExecution
  } = useExecutionRegistry(workspace?.workspace_id ?? "");
  const latestTaskExecution = getExecution(latestTaskId);
  const latestTask = latestTaskExecution?.task ?? null;
  const taskEvents = latestTaskExecution?.events ?? [];
  const terminalSourceParseTasks = trackedTaskIds
    .map((taskId) => executionRecords[taskId]?.task)
    .filter((task) => task?.task_type === "SOURCE_PARSE" && isExecutionTerminal(task.task_status));
  const terminalSourceParseKey = terminalSourceParseTasks
    .map((task) => `${task.task_id}:${task.task_status}`)
    .sort()
    .join("|");
  // Only active view builds heavy props (avoids fake useMemo on unstable controller objects).
  const taskPresentation = view === "chat" || view === "library"
    ? buildTaskWaitPresentation(latestTask, taskEvents)
    : null;

  const wiki = useWikiWorkbenchController({
    workspace,
    view,
    setView,
    isBusy,
    wikiBusy,
    run,
    setStatus,
    appendSystemMessage
  });

  useEffect(() => {
    sourceParseSyncKeysRef.current.clear();
  }, [workspace?.workspace_id]);

  useEffect(() => {
    if (!workspace || !terminalSourceParseKey) return;

    const syncKeys = terminalSourceParseTasks.map(
      (task) => `${workspace.workspace_id}:${task.task_id}:${task.task_status}`
    );
    if (syncKeys.every((key) => sourceParseSyncKeysRef.current.has(key))) return;
    syncKeys.forEach((key) => sourceParseSyncKeysRef.current.add(key));

    let active = true;
    let retryTimer = 0;
    const refreshParsedSources = async () => {
      try {
        const nextSources = await sourcesApi.list(workspace.workspace_id);
        if (!active) return;
        setSources(nextSources);
        await wiki.refreshWikiHome();
      } catch (error) {
        if (active && !isAbortError(error)) {
          setStatus(error instanceof Error ? error.message : "资料处理状态刷新失败");
        }
      }
    };
    void refreshParsedSources();
    retryTimer = window.setTimeout(() => {
      void refreshParsedSources();
    }, 1_500);
    return () => {
      active = false;
      window.clearTimeout(retryTimer);
    };
  }, [
    workspace?.workspace_id,
    terminalSourceParseKey,
    wiki.refreshWikiHome,
    setStatus
  ]);

  const chat = useChatSessionController({
    workspace,
    conversation,
    statusActive: view === "chat",
    run,
    setStatus,
    sources,
    setSources,
    messages,
    setMessages,
    refreshWikiHome: wiki.refreshWikiHome,
    refreshWikiFromServer: wiki.refreshWikiFromServer,
    wikiEnabled: wiki.wikiEnabled,
    wikiGraphMode: wiki.wikiGraphMode,
    wikiGraphKindFilters: wiki.wikiGraphKindFilters,
    loadExecution,
    setLatestTaskId,
    selectedWikiItemId: wiki.selectedWikiItemId
  });

  const research = useResearchWorkbenchController({
    workspace,
    sources,
    replaceSources: setSources,
    setView,
    run,
    setStatus,
    appendSystemMessage,
    loadExecution,
    getExecution,
    latestResearchTaskId,
    setLatestResearchTaskId
  });

  const loadResearchHistory = research.loadResearchRunHistory;
  useEffect(() => {
    if (view !== "research" || !workspace) {
      if (view !== "research") {
        researchHydratedRef.current = false;
      }
      return;
    }
    if (researchHydratedRef.current) {
      return;
    }
    researchHydratedRef.current = true;
    void loadResearchHistory().catch((error) => {
      if (!isAbortError(error)) {
        setStatus(error instanceof Error ? error.message : "Research 工作台加载失败");
      }
    });
  }, [view, workspace?.workspace_id, loadResearchHistory, setStatus]);

  const artifactWorkspace = useArtifactWorkspace({
    workspaceId: workspace?.workspace_id ?? "",
    latestTask,
    run,
    setStatus,
    replaceSources: setSources
  });

  const artifactReadySources = sources.filter(isSourceReady);

  const catalog = useArtifactStudioCatalog(
    ARTIFACT_STUDIO_PRESENTATION,
    ARTIFACT_STUDIO_SKILL_ORDER,
    setStatus
  );

  const artifactComposer = useArtifactComposerActions({
    workspaceId: workspace?.workspace_id ?? "",
    sourceIds: artifactReadySources.map((source) => source.source_id),
    run,
    setStatus,
    setView,
    setChatMode: chat.setMode,
    setQuestion: chat.setQuestion,
    appendSystemMessage,
    catalog,
    loadJobs: artifactWorkspace.loadJobs
  });

  const { openMemoryWorkbench, navigateWorkbenchView } = useWorkbenchNavigation({
    view,
    setView,
    hasWorkspace: Boolean(workspace),
    setStatus,
    openResearch: research.openResearchWorkbench,
    openWiki: wiki.openWikiHome
  });

  scopeResetRef.current = {
    resetResearch: research.resetResearchState,
    clearWiki: wiki.resetWikiSelection,
    clearArtifact: artifactWorkspace.clear,
    clearQaScope: () => chat.setSelectedQaSourceIds([]),
    clearResearchScope: () => research.setSelectedResearchSourceIds([])
  };

  useEffect(() => {
    function onPopState() {
      setView(parseAppLocation(window.location.pathname).view);
    }
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  const wikiProps = view === "wiki"
    ? buildWikiWorkbenchProps(wiki, workspace, wikiBusy || isBusy)
    : null;
  const researchProps = view === "research"
    ? buildResearchWorkbenchProps(research, workspace, sources, researchBusy || isBusy, () => navigateWorkbenchView("library"))
    : null;
  const artifactRailProps = view === "chat"
    ? buildArtifactRailProps({
      catalog,
      composer: artifactComposer,
      workspace,
      artifactBusy,
      artifactWorkspace: artifactWorkspace as Parameters<typeof buildArtifactRailProps>[0]["artifactWorkspace"],
      researchSummary: research.currentResearchRunSummary,
      latestTask,
      sourcesCount: artifactReadySources.length,
      wiki: {
        wikiEnabled: wiki.wikiEnabled,
        wikiIndex: wiki.wikiIndex,
        wikiHome: wiki.wikiHome,
        wikiRebuildAdvice: wiki.wikiRebuildAdvice,
        openWikiHome: wiki.openWikiHome,
        toggleWikiEnabled: wiki.toggleWikiEnabled
      },
      chat: {
        sourceDraftTitle: chat.sourceDraftTitle,
        setSourceDraftTitle: chat.setSourceDraftTitle,
        sourceDraftContent: chat.sourceDraftContent,
        setSourceDraftContent: chat.setSourceDraftContent,
        sourceDraftRewriteMode: chat.sourceDraftRewriteMode,
        rewriteNoteSourceDraft: chat.rewriteNoteSourceDraft,
        saveNoteAnswerAsSource: chat.saveNoteAnswerAsSource,
        lastNoteAssistantMessageId: chat.lastNoteAssistantMessageId
      },
      openResearchWorkbench: research.openResearchWorkbench,
      openMemoryWorkbench
    })
    : null;
  const chatProps = view === "chat" && artifactRailProps
    ? buildChatWorkbenchProps({
      chat,
      sources,
      workspace,
      conversation,
      conversationCount: conversations.length,
      chatBusy,
      artifactComposerOpen: catalog.composerOpen,
      setArtifactComposerOpen: catalog.setComposerOpen,
      artifactRailProps,
      onOpenSourceLibrary: () => navigateWorkbenchView("library")
    })
    : null;
  const sourceLibraryProps = view === "library" && taskPresentation
    ? buildSourceLibraryWorkbenchProps({
      chat,
      sources,
      workspace,
      conversationCount: conversations.length,
      uploadBusy,
      latestTask,
      taskEvents,
      taskPresentation
    })
    : null;

  return (
    <WorkbenchShell
      view={view}
      status={status}
      sessionLoading={sessionLoading}
      workspaceName={workspace ? workspace.name : ""}
      conversationTitle={conversation ? conversation.title : ""}
      workspaces={workspaces}
      conversations={conversations}
      workspaceId={workspace?.workspace_id ?? ""}
      conversationId={conversation?.conversation_id ?? ""}
      sourceCount={sources.length}
      shellBusy={shellBusy}
      onNavigate={navigateWorkbenchView}
      onSwitchWorkspace={(workspaceId) => void switchWorkspace(workspaceId)}
      onSwitchConversation={(conversationId) => void switchConversation(conversationId)}
      onCreateWorkspace={createWorkspace}
      onCreateConversation={createConversation}
      onToggleSettings={() => setWorkspaceSettingsOpen((current) => !current)}
      settingsOpen={workspaceSettingsOpen}
      settingsPanel={workspace && workspaceSettingsOpen ? (
        <WorkspaceSettingsPanel
          workspaceId={workspace.workspace_id}
          showMembers
          onClose={() => setWorkspaceSettingsOpen(false)}
        />
      ) : null}
    >
      <ChunkLoadBoundary label={view}>
        <Suspense fallback={<ViewFallback label={view} />}>
          {view === "wiki" && wikiProps ? (
            <LazyWikiWorkbench {...wikiProps} />
          ) : view === "library" && sourceLibraryProps ? (
            <LazySourceLibraryWorkbench {...sourceLibraryProps} />
          ) : view === "memory" ? (
            <LazyMemoryReviewWorkbench workspaceId={workspace?.workspace_id ?? ""} />
          ) : view === "research" && researchProps ? (
            <div className="workbench-page research-page-shell">
              <LazyResearchWorkbenchView {...researchProps} />
            </div>
          ) : chatProps ? (
            <LazyChatWorkbench {...chatProps} />
          ) : (
            <ViewFallback label="chat" />
          )}
        </Suspense>
      </ChunkLoadBoundary>
    </WorkbenchShell>
  );
}

function isAbortError(error: unknown) {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}
