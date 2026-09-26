import { type ReactNode, useEffect, useRef, useState } from "react";
import {
  BrainCircuit,
  FlaskConical,
  LibraryBig,
  LogOut,
  Menu,
  MessageSquareText,
  Moon,
  Network,
  NotebookPen,
  Plus,
  Settings2,
  SquarePen,
  Sun,
  X,
  type LucideIcon
} from "lucide-react";
import { getAuthSession, logoutAuthSession } from "../../shared/api/auth";
import { applyTheme, getInitialTheme, type ThemeMode } from "../../shared/theme";
import type { AppView } from "./viewRoute";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";
import { ConversationSwitcher } from "./ConversationSwitcher";
import { BrandMark } from "./BrandMark";
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
  { view: "chat", label: "笔记本", letter: "C", title: "笔记本：来源、对话与产物", icon: NotebookPen },
  { view: "library", label: "资料库", letter: "L", title: "工作台资料库", icon: LibraryBig },
  { view: "research", label: "研究", letter: "R", title: "Deep Research 工作台", icon: FlaskConical },
  { view: "wiki", label: "Wiki", letter: "W", title: "Wiki 知识库", icon: Network },
  { view: "memory", label: "记忆", letter: "M", title: "Memory 审核", icon: BrainCircuit }
];

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

const ERROR_STATUS_PATTERN = /失败|错误|无法|不可用|异常/;

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
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const [workspaceCreateOpen, setWorkspaceCreateOpen] = useState(false);
  const [conversationCreateOpen, setConversationCreateOpen] = useState(false);
  const [theme, setTheme] = useState<ThemeMode>(getInitialTheme);
  const [loggingOut, setLoggingOut] = useState(false);
  const workspaceCreateTriggerRef = useRef<HTMLElement | null>(null);
  const conversationCreateTriggerRef = useRef<HTMLElement | null>(null);
  const accountTriggerRef = useRef<HTMLButtonElement | null>(null);
  const accountMenuRef = useRef<HTMLDivElement | null>(null);
  const conversationCreateFallbackRef = useRef<HTMLButtonElement | null>(null);
  const authSession = getAuthSession();
  const accountName = authSession?.user.display_name || authSession?.user.username || "";
  const statusIsError = ERROR_STATUS_PATTERN.test(status);
  const showConversationCrumb = view === "chat" && Boolean(workspaceId);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    if (!mobileNavOpen) return;
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setMobileNavOpen(false);
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [mobileNavOpen]);

  useEffect(() => {
    if (!accountMenuOpen) return;
    function closeOnOutsidePointer(event: PointerEvent) {
      const target = event.target as Node;
      if (!accountMenuRef.current?.contains(target) && !accountTriggerRef.current?.contains(target)) {
        setAccountMenuOpen(false);
      }
    }
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      setAccountMenuOpen(false);
      accountTriggerRef.current?.focus();
    }
    window.addEventListener("pointerdown", closeOnOutsidePointer);
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("pointerdown", closeOnOutsidePointer);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [accountMenuOpen]);

  useEffect(() => {
    if (!settingsOpen) return;
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !event.defaultPrevented) onToggleSettings();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [settingsOpen, onToggleSettings]);

  function handleNavClick(nextView: AppView) {
    onNavigate(nextView);
    setMobileNavOpen(false);
  }

  function openWorkspaceCreate(trigger?: HTMLElement | null) {
    workspaceCreateTriggerRef.current = trigger
      ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
    setMobileNavOpen(false);
    setAccountMenuOpen(false);
    setWorkspaceCreateOpen(true);
  }

  function openConversationCreate(trigger?: HTMLElement | null) {
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

      <header className="app-topbar">
        <div className="topbar-lead">
          <button
            type="button"
            className="mobile-menu-toggle icon-button"
            aria-expanded={mobileNavOpen}
            aria-controls="app-nav"
            aria-label={mobileNavOpen ? "关闭主导航" : "打开主导航"}
            onClick={() => setMobileNavOpen((current) => !current)}
          >
            <Menu size={18} aria-hidden="true" />
          </button>
          <div className="brand" title="NoteWeave">
            <BrandMark className="brand-mark" />
            <h1 className="brand-name">NoteWeave</h1>
          </div>
          <span className="topbar-sep" aria-hidden="true" />
          <WorkspaceSwitcher
            workspaces={workspaces}
            workspaceId={workspaceId}
            disabled={shellBusy || sessionLoading || workspaces.length === 0}
            onSwitchWorkspace={onSwitchWorkspace}
            onCreateWorkspace={() => openWorkspaceCreate(accountTriggerRef.current)}
          />
          {showConversationCrumb ? (
            <>
              <span className="topbar-sep is-conversation" aria-hidden="true" />
              <ConversationSwitcher
                conversations={conversations}
                conversationId={conversationId}
                conversationTitle={conversationTitle}
                disabled={shellBusy || sessionLoading}
                onSwitchConversation={onSwitchConversation}
                onCreateConversation={(trigger) => openConversationCreate(trigger)}
              />
              <button
                ref={conversationCreateFallbackRef}
                type="button"
                className="icon-button topbar-new-conversation"
                aria-label="新建会话"
                title="新建会话"
                disabled={shellBusy || sessionLoading || !workspaceId}
                onClick={(event) => openConversationCreate(event.currentTarget)}
              >
                <SquarePen size={16} aria-hidden="true" />
              </button>
            </>
          ) : null}
        </div>

        {mobileNavOpen ? (
          <div className="mobile-drawer-backdrop" aria-hidden="true" onClick={() => setMobileNavOpen(false)} />
        ) : null}
        <nav
          id="app-nav"
          className={`app-nav${mobileNavOpen ? " is-mobile-open" : ""}`}
          aria-label="主导航"
        >
          {mobileNavOpen ? (
            <div className="app-nav-drawer-header">
              <div className="brand">
                <BrandMark className="brand-mark" />
                <span className="brand-name">NoteWeave</span>
              </div>
              <button
                type="button"
                className="icon-button"
                aria-label="关闭导航"
                onClick={() => setMobileNavOpen(false)}
              >
                <X size={18} aria-hidden="true" />
              </button>
            </div>
          ) : null}
          <div className="app-nav-items">
            {WORKBENCH_NAV_ITEMS.map((item) => {
              const Icon = item.icon;
              const active = view === item.view;
              return (
                <button
                  key={item.view}
                  type="button"
                  className={active ? "app-nav-item active" : "app-nav-item"}
                  title={item.title}
                  aria-label={item.title}
                  aria-current={active ? "page" : undefined}
                  disabled={sessionLoading || (shellBusy && !active)}
                  onClick={() => handleNavClick(item.view)}
                >
                  <Icon size={16} strokeWidth={1.9} aria-hidden="true" />
                  <span className="app-nav-label">{item.label}</span>
                  {item.view === "library" && sourceCount > 0 ? (
                    <span className="app-nav-count" aria-hidden="true">{sourceCount}</span>
                  ) : null}
                </button>
              );
            })}
          </div>
          {workspaceId ? (
            <section className="app-nav-conversations" aria-label="工作台会话">
              <div className="app-nav-section-title">
                <span>会话</span>
                <button
                  type="button"
                  className="icon-button"
                  aria-label="新建会话（导航）"
                  disabled={shellBusy || sessionLoading}
                  onClick={(event) => openConversationCreate(event.currentTarget)}
                >
                  <Plus size={15} aria-hidden="true" />
                </button>
              </div>
              <p className="app-nav-section-note">共享当前工作台资料库</p>
              {conversations.map((item) => (
                <button
                  key={item.conversation_id}
                  type="button"
                  className={item.conversation_id === conversationId ? "app-nav-conversation active" : "app-nav-conversation"}
                  disabled={shellBusy || sessionLoading}
                  onClick={() => {
                    onSwitchConversation(item.conversation_id);
                    if (view !== "chat") onNavigate("chat");
                    setMobileNavOpen(false);
                  }}
                >
                  <MessageSquareText size={14} aria-hidden="true" />
                  <span>{item.title}</span>
                </button>
              ))}
            </section>
          ) : null}
        </nav>

        <div className="topbar-trail">
          <p
            className={`shell-status status-line${shellBusy ? " is-busy" : ""}${statusIsError ? " is-error" : ""}`}
            role="status"
            title={status}
          >
            <span className="shell-status-dot" aria-hidden="true" />
            <span className="shell-status-text">{status}</span>
          </p>
          <button
            type="button"
            className="icon-button theme-toggle-btn"
            aria-label="切换明暗主题"
            title={theme === "dark" ? "切换到浅色" : "切换到深色"}
            onClick={() => setTheme((prev) => (prev === "light" ? "dark" : "light"))}
          >
            {theme === "dark" ? <Sun size={17} aria-hidden="true" /> : <Moon size={17} aria-hidden="true" />}
          </button>
          <div className="account-menu">
            <button
              ref={accountTriggerRef}
              type="button"
              className="account-trigger"
              aria-label="账户与工作台菜单"
              aria-haspopup="menu"
              aria-expanded={accountMenuOpen}
              onClick={() => setAccountMenuOpen((current) => !current)}
            >
              <span className="account-avatar" aria-hidden="true">
                {accountName ? accountName.slice(0, 1).toUpperCase() : <Settings2 size={15} />}
              </span>
            </button>
            {accountMenuOpen ? (
              <div ref={accountMenuRef} className="account-popover" role="menu" aria-label="账户与工作台菜单">
                {authSession ? (
                  <div className="account-identity">
                    <strong>{accountName}</strong>
                    {authSession.user.email ? <small>{authSession.user.email}</small> : null}
                  </div>
                ) : null}
                <button
                  type="button"
                  role="menuitem"
                  className="account-menu-item"
                  disabled={shellBusy}
                  onClick={() => openWorkspaceCreate(accountTriggerRef.current)}
                >
                  <Plus size={15} aria-hidden="true" />新建工作台
                </button>
                <button
                  type="button"
                  role="menuitem"
                  className="account-menu-item"
                  disabled={shellBusy || sessionLoading || !workspaceId}
                  onClick={() => {
                    setAccountMenuOpen(false);
                    onToggleSettings();
                  }}
                >
                  <Settings2 size={15} aria-hidden="true" />{settingsOpen ? "关闭设置" : "工作台设置"}
                </button>
                {authSession ? (
                  <>
                    <span className="account-menu-divider" aria-hidden="true" />
                    <button
                      type="button"
                      role="menuitem"
                      className="account-menu-item"
                      disabled={loggingOut}
                      onClick={() => void handleLogout()}
                    >
                      <LogOut size={15} aria-hidden="true" />{loggingOut ? "退出中" : "退出登录"}
                    </button>
                  </>
                ) : null}
              </div>
            ) : null}
          </div>
        </div>
      </header>

      {settingsPanel ? (
        <div className="settings-sheet-layer">
          <button
            type="button"
            className="settings-sheet-backdrop"
            aria-label="关闭工作台设置"
            onClick={onToggleSettings}
          />
          <div className="settings-sheet">{settingsPanel}</div>
        </div>
      ) : null}

      <main id="workbench-main-content" className="workbench-canvas">
        {sessionLoading ? (
          <section
            className="workspace-session-loading"
            role="status"
            aria-label="正在恢复工作台会话"
            aria-busy="true"
            aria-live="polite"
          >
            <div className="view-loading-body">
              <span className="view-loading-spinner" aria-hidden="true" />
              <span>正在恢复工作台与会话…</span>
            </div>
            <div className="view-loading-skeleton" aria-hidden="true">
              <div className="skeleton-line w-40" />
              <div className="skeleton-line w-70" />
              <div className="skeleton-line w-55" />
            </div>
          </section>
        ) : !workspaceId ? (
          <section className="workspace-zero-state" aria-labelledby="workspace-zero-title">
            <BrandMark className="workspace-zero-mark" />
            <h2 id="workspace-zero-title">先建立你的研究边界</h2>
            <p>一个工作台就是一本笔记本：资料、会话、研究、Wiki 与记忆都归属于它。</p>
            <button
              type="button"
              className="primary-action workspace-zero-create"
              disabled={shellBusy}
              onClick={(event) => openWorkspaceCreate(event.currentTarget)}
            >
              <Plus size={16} aria-hidden="true" />创建第一个工作台
            </button>
          </section>
        ) : children}
      </main>

      {workspaceCreateOpen ? (
        <WorkspaceCreateDialog
          busy={shellBusy}
          onClose={() => closeCreateDialog(setWorkspaceCreateOpen, workspaceCreateTriggerRef, accountTriggerRef)}
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
