import { type ReactNode, useEffect, useRef, useState } from "react";
import {
  BookOpenText,
  BrainCircuit,
  FileStack,
  FlaskConical,
  LibraryBig,
  LogOut,
  Menu,
  MessageSquareText,
  Moon,
  Network,
  Plus,
  Settings2,
  Sparkles,
  Sun,
  X,
  type LucideIcon
} from "lucide-react";
import { getAuthSession, logoutAuthSession } from "../../shared/api/auth";
import { applyTheme, getInitialTheme, type ThemeMode } from "../../shared/theme";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { AppView } from "./viewRoute";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";
import { WorkspaceCreateDialog } from "../workspace/WorkspaceCreateDialog";
import { type CreateWorkspaceInput } from "../workspace/api";
import { ConversationCreateDialog } from "../conversations/ConversationCreateDialog";

export type WorkbenchNavItem = {
  view: AppView;
  label: string;
  letter: string;
  title: string;
  icon: LucideIcon;
};

export const WORKBENCH_NAV_ITEMS: WorkbenchNavItem[] = [
  { view: "chat", label: "对话", letter: "C", title: "Chat · QA / Note / Wiki", icon: MessageSquareText },
  { view: "library", label: "资料库", letter: "L", title: "工作台资料库", icon: LibraryBig },
  { view: "research", label: "研究", letter: "R", title: "Deep Research 工作台", icon: FlaskConical },
  { view: "wiki", label: "Wiki", letter: "W", title: "Wiki 治理工作台", icon: Network },
  { view: "memory", label: "记忆", letter: "M", title: "Memory 人工审核", icon: BrainCircuit }
];

const VIEW_BADGE: Record<AppView, string> = {
  chat: "对话",
  library: "资料库",
  research: "深度研究",
  wiki: "Wiki",
  memory: "Memory"
};

export type WorkbenchShellProps = {
  view: AppView;
  status: string;
  sessionLoading: boolean;
  workspaceName: string;
  conversationTitle: string;
  workspaces: Array<{ workspace_id: string; name: string }>;
  conversations: Array<{ conversation_id: string; title: string; last_active_at?: string }>;
  workspaceId: string;
  conversationId: string;
  sourceCount?: number;
  shellBusy: boolean;
  onNavigate: (view: AppView) => void;
  onSwitchWorkspace: (workspaceId: string) => void;
  onSwitchConversation: (conversationId: string) => void;
  onCreateWorkspace: (input: CreateWorkspaceInput) => Promise<boolean>;
  onCreateConversation: (title: string) => Promise<boolean>;
  onToggleSettings: () => void;
  settingsOpen: boolean;
  settingsPanel?: ReactNode;
  children: ReactNode;
};

export function WorkbenchShell({
  view,
  status,
  sessionLoading,
  workspaceName,
  conversationTitle,
  workspaces,
  conversations,
  workspaceId,
  conversationId,
  sourceCount = 0,
  shellBusy,
  onNavigate,
  onSwitchWorkspace,
  onSwitchConversation,
  onCreateWorkspace,
  onCreateConversation,
  onToggleSettings,
  settingsOpen,
  settingsPanel,
  children
}: WorkbenchShellProps) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [workspaceCreateOpen, setWorkspaceCreateOpen] = useState(false);
  const [conversationCreateOpen, setConversationCreateOpen] = useState(false);
  const [theme, setTheme] = useState<ThemeMode>(getInitialTheme);
  const [loggingOut, setLoggingOut] = useState(false);
  const workspaceCreateTriggerRef = useRef<HTMLElement | null>(null);
  const conversationCreateTriggerRef = useRef<HTMLElement | null>(null);
  const workspaceCreateFallbackRef = useRef<HTMLButtonElement | null>(null);
  const conversationCreateFallbackRef = useRef<HTMLButtonElement | null>(null);
  const authSession = getAuthSession();

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    if (!mobileNavOpen) return;
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setMobileNavOpen(false);
      }
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [mobileNavOpen]);

  function handleToggleTheme() {
    setTheme((prev) => (prev === "light" ? "dark" : "light"));
  }

  function handleNavClick(nextView: AppView) {
    onNavigate(nextView);
    setMobileNavOpen(false);
  }

  function openWorkspaceCreate(trigger?: HTMLElement) {
    workspaceCreateTriggerRef.current = trigger
      ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setMobileNavOpen(false);
    setWorkspaceCreateOpen(true);
  }

  function openConversationCreate(trigger?: HTMLElement) {
    conversationCreateTriggerRef.current = trigger
      ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setMobileNavOpen(false);
    setConversationCreateOpen(true);
  }

  function closeCreateDialog(
    setOpen: (open: boolean) => void,
    trigger: { current: HTMLElement | null },
    fallback: { current: HTMLElement | null }
  ) {
    setOpen(false);
    window.requestAnimationFrame(() => {
      const target = trigger.current?.isConnected ? trigger.current : fallback.current;
      target?.focus();
    });
  }

  async function handleLogout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await logoutAuthSession();
    } finally {
      setLoggingOut(false);
    }
  }

  return (
    <div className="workbench-shell" data-view={view}>
      <a className="skip-link" href="#workbench-main-content">跳到主要内容</a>
      {mobileNavOpen ? (
        <div
          className="mobile-drawer-backdrop"
          aria-hidden="true"
          onClick={() => setMobileNavOpen(false)}
        />
      ) : null}

      <aside className={`global-rail${mobileNavOpen ? " is-mobile-open" : ""}`} aria-label="主导航">
        <div className="global-rail-header-row">
          <div className="global-rail-brand" title="NoteWeave">
            <span className="global-rail-brand-mark" aria-hidden="true"><BookOpenText size={20} strokeWidth={1.8} /></span>
            <div className="global-rail-brand-copy">
              <h1 className="global-rail-brand-text">NoteWeave</h1>
              <span>Research notebook</span>
            </div>
          </div>
          {mobileNavOpen ? (
            <button
              ref={conversationCreateFallbackRef}
              type="button"
              className="mobile-close-btn"
              aria-label="关闭导航"
              onClick={() => setMobileNavOpen(false)}
            >
              <X size={18} aria-hidden="true" />
            </button>
          ) : null}
        </div>

        <nav className="global-rail-nav">
          {WORKBENCH_NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.view}
                type="button"
                className={view === item.view ? "global-rail-item active" : "global-rail-item"}
                title={item.title}
                aria-label={item.title}
                aria-current={view === item.view ? "page" : undefined}
                disabled={sessionLoading || (shellBusy && item.view !== view)}
                onClick={() => handleNavClick(item.view)}
              >
                <span className="global-rail-letter" aria-hidden="true"><Icon size={18} strokeWidth={1.8} /></span>
                <span className="global-rail-label">{item.label}</span>
                {item.view === "library" ? (
                  <span className="global-rail-item-count" aria-hidden="true">{sourceCount}</span>
                ) : null}
              </button>
            );
          })}
        </nav>

        <section className="global-rail-context" aria-label="工作台与对话">
          <WorkspaceSwitcher
            workspaces={workspaces}
            workspaceId={workspaceId}
            disabled={shellBusy || sessionLoading || workspaces.length === 0}
            onSwitchWorkspace={onSwitchWorkspace}
            onCreateWorkspace={openWorkspaceCreate}
          />
          <div className="global-rail-conversation-header">
            <span>工作台会话</span>
            <small>{conversations.length}</small>
            <button
              type="button"
              className="global-rail-icon-button"
              aria-label="新建会话"
              title="新建会话"
              disabled={shellBusy || sessionLoading || !workspaceId}
              onClick={(event) => openConversationCreate(event.currentTarget)}
            >
              <Plus size={15} aria-hidden="true" />
            </button>
          </div>
          <p className="global-rail-conversation-note">共享当前工作台资料库</p>
          <div className="global-rail-conversation-list" aria-label="对话列表">
            {conversations.length === 0 ? (
              <p className="global-rail-empty">还没有对话</p>
            ) : conversations.map((item) => (
              <button
                key={item.conversation_id}
                type="button"
                className={item.conversation_id === conversationId
                  ? "global-rail-conversation active"
                  : "global-rail-conversation"}
                aria-current={item.conversation_id === conversationId ? "page" : undefined}
                title={item.title}
                disabled={shellBusy || sessionLoading}
                onClick={() => onSwitchConversation(item.conversation_id)}
              >
                <MessageSquareText size={14} aria-hidden="true" />
                <span className="global-rail-conversation-copy">
                  <strong>{item.title}</strong>
                  {item.last_active_at ? <small>{formatRelativeTime(item.last_active_at)}</small> : null}
                </span>
              </button>
            ))}
          </div>
        </section>

        <div className="global-rail-footer">
          <div className="global-rail-actions">
            <button ref={workspaceCreateFallbackRef} type="button" className="secondary-button" disabled={shellBusy} onClick={(event) => openWorkspaceCreate(event.currentTarget)}>
              <Plus size={15} aria-hidden="true" />新建工作台
            </button>
            <button
              type="button"
              className="secondary-button"
              disabled={shellBusy || sessionLoading || !workspaceId}
              onClick={onToggleSettings}
            >
              <Settings2 size={15} aria-hidden="true" />{settingsOpen ? "关闭设置" : "工作台设置"}
            </button>
            <button
              type="button"
              className="secondary-button theme-toggle-btn"
              aria-label="切换明暗主题"
              onClick={handleToggleTheme}
            >
              {theme === "dark" ? <Sun size={15} aria-hidden="true" /> : <Moon size={15} aria-hidden="true" />}
              {theme === "dark" ? "浅色" : "深色"}
            </button>
          </div>
          {authSession ? (
            <div className="global-rail-account">
              <span title={authSession.user.email}>
                {authSession.user.display_name || authSession.user.username}
              </span>
              <button
                type="button"
                className="global-rail-logout"
                disabled={loggingOut}
                onClick={() => void handleLogout()}
              >
                <LogOut size={14} aria-hidden="true" />{loggingOut ? "退出中" : "退出"}
              </button>
            </div>
          ) : null}
        </div>
      </aside>

      <div className="workbench-main">
        <div className="context-strip" role="status">
          <div className="context-strip-lead">
            <button
              type="button"
              className="mobile-menu-toggle"
              aria-expanded={mobileNavOpen}
              aria-label={mobileNavOpen ? "关闭主导航" : "打开主导航"}
              onClick={() => setMobileNavOpen(!mobileNavOpen)}
            >
              <Menu className="mobile-menu-icon" size={18} aria-hidden="true" />
            </button>
            <span className="context-view-badge">{VIEW_BADGE[view]}</span>
            <strong>{workspaceName || (sessionLoading ? "正在恢复…" : "未选择工作台")}</strong>
            <span>{view === "library"
              ? `${sourceCount} 份资料，共享给 ${conversations.length} 个会话`
              : conversationTitle || "暂无会话"}</span>
            {sessionLoading ? <span className="context-loading-dot" aria-hidden="true" /> : null}
          </div>
          <p
            className={`status-line context-strip-status${shellBusy ? " is-busy" : ""}${/失败|错误|无法/.test(status) ? " is-error" : ""}`}
          >
            {status}
          </p>
        </div>
        {settingsPanel}
        <div id="workbench-main-content" className="workbench-canvas">
          {sessionLoading ? (
            <section
              className="workbench-page view-loading workspace-session-loading"
              role="status"
              aria-label="正在恢复工作台会话"
              aria-busy="true"
              aria-live="polite"
            >
              <p className="section-label">Workspace session</p>
              <div className="view-loading-body">
                <span className="view-loading-spinner" aria-hidden="true" />
                <span>正在恢复工作台与会话...</span>
              </div>
              <div className="view-loading-skeleton" aria-hidden="true">
                <div className="skeleton-line w-40" />
                <div className="skeleton-line w-70" />
                <div className="skeleton-line w-55" />
              </div>
            </section>
          ) : !workspaceId ? (
            <section className="workspace-zero-state" aria-labelledby="workspace-zero-title">
              <div className="workspace-zero-mark" aria-hidden="true"><BookOpenText size={27} /></div>
              <p className="section-label">Start a workspace</p>
              <h2 id="workspace-zero-title">先建立你的研究边界</h2>
              <p>资料、多个会话、Research、Wiki 和记忆都会归属于同一个工作台。</p>
              <div className="workspace-zero-flow" aria-label="工作台使用流程">
                <span><FileStack size={16} aria-hidden="true" />集中资料</span>
                <span><MessageSquareText size={16} aria-hidden="true" />独立对话</span>
                <span><Sparkles size={16} aria-hidden="true" />沉淀结论</span>
              </div>
              <button type="button" className="workspace-zero-create" disabled={shellBusy} onClick={(event) => openWorkspaceCreate(event.currentTarget)}>
                <Plus size={16} aria-hidden="true" />创建第一个工作台
              </button>
            </section>
          ) : children}
        </div>
      </div>
      {workspaceCreateOpen ? (
        <WorkspaceCreateDialog
          busy={shellBusy}
          onClose={() => closeCreateDialog(setWorkspaceCreateOpen, workspaceCreateTriggerRef, workspaceCreateFallbackRef)}
          onCreate={onCreateWorkspace}
        />
      ) : null}
      {conversationCreateOpen && workspaceId ? (
        <ConversationCreateDialog
          workspaceName={workspaceName}
          busy={shellBusy}
          onClose={() => closeCreateDialog(setConversationCreateOpen, conversationCreateTriggerRef, conversationCreateFallbackRef)}
          onCreate={onCreateConversation}
        />
      ) : null}
    </div>
  );
}
