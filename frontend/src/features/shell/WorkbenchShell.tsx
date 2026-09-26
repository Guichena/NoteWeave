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
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Settings2,
  SquarePen,
  Sun,
  X,
  type LucideIcon
} from "lucide-react";
import { getAuthSession, logoutAuthSession } from "../../shared/api/auth";
import { applyTheme, getInitialTheme, type ThemeMode } from "../../shared/theme";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { AppView } from "./viewRoute";
import { WorkspaceSwitcher } from "./WorkspaceSwitcher";
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
  { view: "chat", label: "对话", letter: "C", title: "对话", icon: MessageSquareText },
  { view: "library", label: "资料库", letter: "L", title: "工作台资料库", icon: LibraryBig },
  { view: "research", label: "深度研究", letter: "R", title: "Deep Research 工作台", icon: FlaskConical },
  { view: "wiki", label: "知识库", letter: "W", title: "Wiki 知识库", icon: Network },
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
const SIDEBAR_COLLAPSED_KEY = "noteweave.sidebar.collapsed";

function readSidebarCollapsed() {
  try {
    return window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "1";
  } catch {
    return false;
  }
}

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
  const [collapsed, setCollapsed] = useState(readSidebarCollapsed);
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const [workspaceCreateOpen, setWorkspaceCreateOpen] = useState(false);
  const [conversationCreateOpen, setConversationCreateOpen] = useState(false);
  const [theme, setTheme] = useState<ThemeMode>(getInitialTheme);
  const [loggingOut, setLoggingOut] = useState(false);
  const workspaceCreateTriggerRef = useRef<HTMLElement | null>(null);
  const conversationCreateTriggerRef = useRef<HTMLElement | null>(null);
  const accountTriggerRef = useRef<HTMLButtonElement | null>(null);
  const accountMenuRef = useRef<HTMLDivElement | null>(null);
  const newConversationRef = useRef<HTMLButtonElement | null>(null);
  const authSession = getAuthSession();
  const accountName = authSession?.user.display_name || authSession?.user.username || "";
  const statusIsError = ERROR_STATUS_PATTERN.test(status);
  const sidebarCollapsed = collapsed && !mobileNavOpen;

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    try {
      window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? "1" : "0");
    } catch {
      // 仅为个人偏好，存不下也无妨
    }
  }, [collapsed]);

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

  const shellClassName = [
    "workbench-shell",
    sidebarCollapsed ? "is-sidebar-collapsed" : "",
    mobileNavOpen ? "is-mobile-nav-open" : ""
  ].filter(Boolean).join(" ");

  return (
    <div className={shellClassName} data-view={view}>
      <a className="skip-link" href="#workbench-main-content">跳到主要内容</a>

      {mobileNavOpen ? (
        <div className="mobile-drawer-backdrop" aria-hidden="true" onClick={() => setMobileNavOpen(false)} />
      ) : null}

      <aside id="app-sidebar" className="app-sidebar" aria-label="主导航">
        <div className="sidebar-header">
          <div className="brand" title="NoteWeave">
            <BrandMark className="brand-mark" />
            <h1 className="brand-name">NoteWeave</h1>
          </div>
          {mobileNavOpen ? (
            <button
              type="button"
              className="icon-button sidebar-close"
              aria-label="关闭导航"
              onClick={() => setMobileNavOpen(false)}
            >
              <X size={18} aria-hidden="true" />
            </button>
          ) : (
            <button
              type="button"
              className="icon-button sidebar-collapse"
              aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}
              title={collapsed ? "展开侧边栏" : "收起侧边栏"}
              onClick={() => setCollapsed((current) => !current)}
            >
              {collapsed ? <PanelLeftOpen size={17} aria-hidden="true" /> : <PanelLeftClose size={17} aria-hidden="true" />}
            </button>
          )}
        </div>

        <WorkspaceSwitcher
          workspaces={workspaces}
          workspaceId={workspaceId}
          meta={workspaceId ? `${sourceCount} 份资料 · ${conversations.length} 个对话` : "暂无工作台"}
          disabled={shellBusy || sessionLoading || workspaces.length === 0}
          onSwitchWorkspace={onSwitchWorkspace}
          onCreateWorkspace={() => openWorkspaceCreate(accountTriggerRef.current)}
        />

        <button
          ref={newConversationRef}
          type="button"
          className="sidebar-new-chat"
          aria-label="新建会话"
          title="新建会话"
          disabled={shellBusy || sessionLoading || !workspaceId}
          onClick={(event) => openConversationCreate(event.currentTarget)}
        >
          <SquarePen size={16} aria-hidden="true" />
          <span className="sidebar-label">新对话</span>
        </button>

        <nav className="sidebar-nav">
          {WORKBENCH_NAV_ITEMS.filter((item) => item.view !== "chat").map((item) => {
            const Icon = item.icon;
            const active = view === item.view;
            return (
              <button
                key={item.view}
                type="button"
                className={active ? "sidebar-nav-item active" : "sidebar-nav-item"}
                title={item.title}
                aria-label={item.title}
                aria-current={active ? "page" : undefined}
                disabled={sessionLoading || (shellBusy && !active)}
                onClick={() => handleNavClick(item.view)}
              >
                <Icon size={17} strokeWidth={1.9} aria-hidden="true" />
                <span className="sidebar-label">{item.label}</span>
                {item.view === "library" && sourceCount > 0 ? (
                  <span className="sidebar-count" aria-hidden="true">{sourceCount}</span>
                ) : null}
              </button>
            );
          })}
        </nav>

        <section className="sidebar-conversations" aria-label="工作台会话">
          <div className="sidebar-section-title">
            <span>对话</span>
            <small>共享当前工作台资料库</small>
          </div>
          <div className="sidebar-conversation-list" aria-label="对话列表">
            {conversations.length === 0 ? (
              <p className="sidebar-empty">{workspaceId ? "还没有对话" : "先创建一个工作台"}</p>
            ) : conversations.map((item) => {
              const active = item.conversation_id === conversationId && view === "chat";
              return (
                <button
                  key={item.conversation_id}
                  type="button"
                  className={active ? "sidebar-conversation active" : "sidebar-conversation"}
                  aria-current={active ? "page" : undefined}
                  title={item.title}
                  disabled={shellBusy || sessionLoading}
                  onClick={() => {
                    if (item.conversation_id !== conversationId) onSwitchConversation(item.conversation_id);
                    if (view !== "chat") onNavigate("chat");
                    setMobileNavOpen(false);
                  }}
                >
                  <span className="sidebar-conversation-title">{item.title}</span>
                  {item.last_active_at ? <small>{formatRelativeTime(item.last_active_at)}</small> : null}
                </button>
              );
            })}
          </div>
        </section>

        <div className="sidebar-footer">
          <p
            className={`shell-status status-line${shellBusy ? " is-busy" : ""}${statusIsError ? " is-error" : ""}`}
            role="status"
            title={status}
          >
            <span className="shell-status-dot" aria-hidden="true" />
            <span className="shell-status-text">{status}</span>
          </p>
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
              <span className="account-copy sidebar-label">
                <strong>{accountName || "设置"}</strong>
                <small>{workspaceName || "NoteWeave"}</small>
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
                  disabled={shellBusy || sessionLoading || !workspaceId}
                  onClick={() => {
                    setAccountMenuOpen(false);
                    onToggleSettings();
                  }}
                >
                  <Settings2 size={15} aria-hidden="true" />{settingsOpen ? "关闭设置" : "工作台设置"}
                </button>
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
                  className="account-menu-item theme-toggle-btn"
                  aria-label="切换明暗主题"
                  onClick={() => setTheme((prev) => (prev === "light" ? "dark" : "light"))}
                >
                  {theme === "dark" ? <Sun size={15} aria-hidden="true" /> : <Moon size={15} aria-hidden="true" />}
                  {theme === "dark" ? "浅色模式" : "深色模式"}
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
      </aside>

      <div className="workbench-main">
        <header className="mobile-topbar">
          <button
            type="button"
            className="icon-button mobile-menu-toggle"
            aria-expanded={mobileNavOpen}
            aria-controls="app-sidebar"
            aria-label={mobileNavOpen ? "关闭主导航" : "打开主导航"}
            onClick={() => setMobileNavOpen((current) => !current)}
          >
            <Menu size={18} aria-hidden="true" />
          </button>
          <strong className="mobile-topbar-title">
            {view === "chat" ? (conversationTitle || workspaceName || "NoteWeave") : workspaceName || "NoteWeave"}
          </strong>
          <button
            type="button"
            className="icon-button"
            aria-label="新建会话（移动端）"
            disabled={shellBusy || sessionLoading || !workspaceId}
            onClick={(event) => openConversationCreate(event.currentTarget)}
          >
            <SquarePen size={17} aria-hidden="true" />
          </button>
        </header>

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
              <p>一个工作台就是一个资料库：上传的资料由其中的所有对话、研究与知识页共享。</p>
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
      </div>

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
          onClose={() => closeCreateDialog(setConversationCreateOpen, conversationCreateTriggerRef, newConversationRef)}
          onCreate={onCreateConversation}
        />
      ) : null}
    </div>
  );
}
