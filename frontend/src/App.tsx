import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { WorkbenchShell } from "./features/shell/WorkbenchShell";
import { useShellBusy } from "./features/shell/useShellBusy";
import { useWorkbenchNavigation } from "./features/shell/useWorkbenchNavigation";
import { type AppView, parseAppLocation } from "./features/shell/viewRoute";
import { useWorkspaceSession } from "./features/workspace/useWorkspaceSession";
import { WorkspaceSettingsPanel } from "./features/workspace/WorkspaceSettingsPanel";
import { type SourceAsset } from "./features/sources/model";
import { useChatSessionController } from "./features/conversations/useChatSessionController";
import { buildChatWorkbenchProps } from "./features/conversations/buildChatWorkbenchProps";
import { useWikiWorkbenchController } from "./features/knowledge/useWikiWorkbenchController";
import { buildWikiWorkbenchProps } from "./features/knowledge/buildWikiWorkbenchProps";
import { useResearchWorkbenchController } from "./features/research/useResearchWorkbenchController";
import { buildResearchWorkbenchProps } from "./features/research/buildResearchWorkbenchProps";
import { useExecutionRegistry } from "./features/executions/useExecutionRegistry";
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
  const [messages, setMessages] = useState<Message[]>([
    {
      role: "assistant",
      content: "先创建工作台并上传一段资料，然后就可以在同一个聊天框里切换问答、Note、Wiki 三种链路。"
    }
  ]);

  const scopeResetRef = useRef({
    resetResearch: () => undefined as void,
    clearWiki: async () => undefined as void,
    clearArtifact: () => undefined as void,
    clearQaScope: () => undefined as void,
    clearResearchScope: () => undefined as void
  });
  const researchHydratedRef = useRef(false);

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
      void scopeResetRef.current.clearWiki();
    }
  });

  const { getExecution, loadExecution } = useExecutionRegistry(workspace?.workspace_id ?? "");
  const latestTaskExecution = getExecution(latestTaskId);
  const latestTask = latestTaskExecution?.task ?? null;
  const taskEvents = latestTaskExecution?.events ?? [];
  // Only active view builds heavy props (avoids fake useMemo on unstable controller objects).
  const taskPresentation = view === "chat"
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

  const chat = useChatSessionController({
    workspace,
    conversation,
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
    void loadResearchHistory();
  }, [view, workspace?.workspace_id, loadResearchHistory]);

  const artifactWorkspace = useArtifactWorkspace({
    workspaceId: workspace?.workspace_id ?? "",
    latestTask,
    run,
    setStatus,
    replaceSources: setSources
  });

  const catalog = useArtifactStudioCatalog(
    ARTIFACT_STUDIO_PRESENTATION,
    ARTIFACT_STUDIO_SKILL_ORDER,
    setStatus
  );

  const artifactComposer = useArtifactComposerActions({
    workspaceId: workspace?.workspace_id ?? "",
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
    clearWiki: async () => {
      await wiki.clearWikiSelection({ mode: "overview", graphKinds: [] });
    },
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
    ? buildResearchWorkbenchProps(research, workspace, sources, researchBusy || isBusy)
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
      sourcesCount: sources.length,
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
  const chatProps = view === "chat" && taskPresentation && artifactRailProps
    ? buildChatWorkbenchProps({
      chat,
      sources,
      workspace,
      conversation,
      chatBusy,
      uploadBusy,
      latestTask,
      taskEvents,
      taskPresentation,
      artifactComposerOpen: catalog.composerOpen,
      setArtifactComposerOpen: catalog.setComposerOpen,
      artifactRailProps
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
      shellBusy={shellBusy}
      onNavigate={navigateWorkbenchView}
      onSwitchWorkspace={(workspaceId) => void switchWorkspace(workspaceId)}
      onSwitchConversation={(conversationId) => void switchConversation(conversationId)}
      onCreateWorkspace={() => void createWorkspace()}
      onCreateConversation={() => void createConversation()}
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
      <Suspense fallback={<ViewFallback label={view} />}>
        {view === "wiki" && wikiProps ? (
          <LazyWikiWorkbench {...wikiProps} />
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
    </WorkbenchShell>
  );
}
