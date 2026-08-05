import type { ReactNode } from "react";
import type { AppView } from "./viewRoute";

export type WorkbenchNavItem = {
  view: AppView;
  label: string;
  letter: string;
  title: string;
};

export const WORKBENCH_NAV_ITEMS: WorkbenchNavItem[] = [
  { view: "chat", label: "对话", letter: "C", title: "Chat · QA / Note / Wiki" },
  { view: "research", label: "研究", letter: "R", title: "Deep Research 工作台" },
  { view: "wiki", label: "Wiki", letter: "W", title: "Wiki 治理工作台" },
  { view: "memory", label: "记忆", letter: "M", title: "Memory 人工审核" }
];

const VIEW_BADGE: Record<AppView, string> = {
  chat: "对话",
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
  conversations: Array<{ conversation_id: string; title: string }>;
  workspaceId: string;
  conversationId: string;
  shellBusy: boolean;
  onNavigate: (view: AppView) => void;
  onSwitchWorkspace: (workspaceId: string) => void;
  onSwitchConversation: (conversationId: string) => void;
  onCreateWorkspace: () => void;
  onCreateConversation: () => void;
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
  return (
    <div className="workbench-shell" data-view={view}>
      <aside className="global-rail" aria-label="主导航">
        <div className="global-rail-brand" title="NoteWeave">
          <span className="global-rail-brand-mark">NW</span>
          <h1 className="global-rail-brand-text">NoteWeave</h1>
        </div>
        <nav className="global-rail-nav">
          {WORKBENCH_NAV_ITEMS.map((item) => (
            <button
              key={item.view}
              type="button"
              className={view === item.view ? "global-rail-item active" : "global-rail-item"}
              title={item.title}
              aria-label={item.title}
              aria-current={view === item.view ? "page" : undefined}
              disabled={shellBusy && item.view !== view}
              onClick={() => onNavigate(item.view)}
            >
              <span className="global-rail-letter">{item.letter}</span>
              <span className="global-rail-label">{item.label}</span>
            </button>
          ))}
        </nav>
        <div className="global-rail-footer">
          <label className="global-rail-field">
            <span>工作台</span>
            <select
              aria-label="切换工作台"
              value={workspaceId}
              disabled={shellBusy || sessionLoading || workspaces.length === 0}
              onChange={(event) => onSwitchWorkspace(event.target.value)}
            >
              {workspaces.length === 0 ? <option value="">未创建</option> : null}
              {workspaces.map((item) => (
                <option key={item.workspace_id} value={item.workspace_id}>{item.name}</option>
              ))}
            </select>
          </label>
          <label className="global-rail-field">
            <span>会话</span>
            <select
              aria-label="切换会话"
              value={conversationId}
              disabled={shellBusy || sessionLoading || conversations.length === 0}
              onChange={(event) => onSwitchConversation(event.target.value)}
            >
              {conversations.length === 0 ? <option value="">暂无会话</option> : null}
              {conversations.map((item) => (
                <option key={item.conversation_id} value={item.conversation_id}>{item.title}</option>
              ))}
            </select>
          </label>
          <div className="global-rail-actions">
            <button type="button" className="secondary-button" disabled={shellBusy} onClick={onCreateWorkspace}>
              新建工作台
            </button>
            <button
              type="button"
              className="secondary-button"
              disabled={shellBusy || sessionLoading || !workspaceId}
              onClick={onCreateConversation}
            >
              新建会话
            </button>
            <button
              type="button"
              className="secondary-button"
              disabled={shellBusy || sessionLoading || !workspaceId}
              onClick={onToggleSettings}
            >
              {settingsOpen ? "关闭设置" : "工作台设置"}
            </button>
          </div>
        </div>
      </aside>

      <div className="workbench-main">
        <div className="context-strip" role="status">
          <div className="context-strip-lead">
            <span className="context-view-badge">{VIEW_BADGE[view]}</span>
            <strong>{workspaceName || (sessionLoading ? "正在恢复…" : "未选择工作台")}</strong>
            <span>{conversationTitle || "暂无会话"}</span>
            {sessionLoading ? <span className="context-loading-dot" aria-hidden="true" /> : null}
          </div>
          <p
            className={`status-line context-strip-status${shellBusy ? " is-busy" : ""}${/失败|错误|无法/.test(status) ? " is-error" : ""}`}
          >
            {status}
          </p>
        </div>
        {settingsPanel}
        <div className="workbench-canvas">
          {children}
        </div>
      </div>
    </div>
  );
}
