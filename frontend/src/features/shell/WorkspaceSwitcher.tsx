import { type CSSProperties, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { BookOpenText, Check, ChevronsUpDown, Plus, Search } from "lucide-react";

type WorkspaceSummary = {
  workspace_id: string;
  name: string;
};

type WorkspaceSwitcherProps = {
  workspaces: WorkspaceSummary[];
  workspaceId: string;
  disabled: boolean;
  onSwitchWorkspace: (workspaceId: string) => void;
  onCreateWorkspace: () => void;
};

const DEFAULT_RESULT_LIMIT = 24;
const SEARCH_RESULT_LIMIT = 40;
const DESKTOP_POPOVER_WIDTH = 320;
const VIEWPORT_GUTTER = 12;

export function WorkspaceSwitcher({
  workspaces,
  workspaceId,
  disabled,
  onSwitchWorkspace,
  onCreateWorkspace
}: WorkspaceSwitcherProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const rootRef = useRef<HTMLDivElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const searchRef = useRef<HTMLInputElement | null>(null);
  const [popoverStyle, setPopoverStyle] = useState<CSSProperties>({ visibility: "hidden" });
  const selectedWorkspace = workspaces.find((workspace) => workspace.workspace_id === workspaceId) ?? null;
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const nameCounts = useMemo(() => workspaces.reduce<Map<string, number>>((counts, workspace) => {
    counts.set(workspace.name, (counts.get(workspace.name) ?? 0) + 1);
    return counts;
  }, new Map()), [workspaces]);
  const matchingWorkspaces = useMemo(() => (
    normalizedQuery
      ? workspaces.filter((workspace) => (
          workspace.name.toLocaleLowerCase().includes(normalizedQuery)
          || workspace.workspace_id.toLocaleLowerCase().includes(normalizedQuery)
        ))
      : workspaces
  ), [normalizedQuery, workspaces]);
  const filteredWorkspaces = useMemo(() => {
    const selected = matchingWorkspaces.find((workspace) => workspace.workspace_id === workspaceId);
    const ordered = selected
      ? [selected, ...matchingWorkspaces.filter((workspace) => workspace.workspace_id !== workspaceId)]
      : matchingWorkspaces;
    return ordered.slice(0, normalizedQuery ? SEARCH_RESULT_LIMIT : DEFAULT_RESULT_LIMIT);
  }, [matchingWorkspaces, normalizedQuery, workspaceId]);

  useEffect(() => {
    if (!open) return;
    const focusTimer = window.setTimeout(() => searchRef.current?.focus(), 0);
    function closeOnOutsidePointer(event: PointerEvent) {
      const target = event.target as Node;
      if (!rootRef.current?.contains(target) && !popoverRef.current?.contains(target)) {
        setOpen(false);
        setQuery("");
      }
    }
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      setOpen(false);
      setQuery("");
      triggerRef.current?.focus();
    }
    window.addEventListener("pointerdown", closeOnOutsidePointer);
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.clearTimeout(focusTimer);
      window.removeEventListener("pointerdown", closeOnOutsidePointer);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  useLayoutEffect(() => {
    if (!open) return;
    function positionPopover() {
      const root = rootRef.current;
      if (!root) return;
      const rect = root.getBoundingClientRect();
      const width = Math.min(DESKTOP_POPOVER_WIDTH, window.innerWidth - VIEWPORT_GUTTER * 2);
      const left = Math.max(
        VIEWPORT_GUTTER,
        Math.min(rect.left, window.innerWidth - width - VIEWPORT_GUTTER)
      );
      const top = Math.min(rect.bottom + 6, window.innerHeight - 172);
      setPopoverStyle({
        top,
        left,
        width,
        maxHeight: Math.min(480, window.innerHeight - top - VIEWPORT_GUTTER),
        visibility: "visible"
      });
    }
    positionPopover();
    window.addEventListener("resize", positionPopover);
    window.addEventListener("scroll", positionPopover, true);
    return () => {
      window.removeEventListener("resize", positionPopover);
      window.removeEventListener("scroll", positionPopover, true);
    };
  }, [open]);

  useEffect(() => {
    setOpen(false);
    setQuery("");
  }, [workspaceId]);

  function selectWorkspace(nextWorkspaceId: string) {
    setOpen(false);
    setQuery("");
    if (nextWorkspaceId !== workspaceId) {
      onSwitchWorkspace(nextWorkspaceId);
    }
  }

  return (
    <div className="workspace-switcher" ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="crumb-trigger workspace-switcher-trigger"
        aria-label="切换工作台"
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="crumb-text" title={selectedWorkspace?.name}>{selectedWorkspace?.name || "未创建工作台"}</span>
        <ChevronsUpDown className="crumb-chevron" size={14} aria-hidden="true" />
      </button>

      {open ? createPortal((
        <div
          ref={popoverRef}
          className="popover workspace-switcher-popover"
          role="dialog"
          aria-label="工作台切换器"
          style={popoverStyle}
        >
          <div className="popover-header">
            <strong>切换工作台</strong>
            <span>{workspaces.length}</span>
          </div>
          <label className="popover-search">
            <Search size={14} aria-hidden="true" />
            <input
              ref={searchRef}
              type="search"
              aria-label="搜索工作台"
              value={query}
              placeholder="搜索名称或 ID"
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          <div className="popover-list" role="listbox" aria-label="工作台列表">
            {filteredWorkspaces.length === 0 ? (
              <p className="popover-empty">没有匹配的工作台</p>
            ) : filteredWorkspaces.map((workspace) => {
              const selected = workspace.workspace_id === workspaceId;
              const duplicateName = (nameCounts.get(workspace.name) ?? 0) > 1;
              return (
                <button
                  key={workspace.workspace_id}
                  type="button"
                  role="option"
                  aria-selected={selected}
                  className={selected ? "popover-option active" : "popover-option"}
                  onClick={() => selectWorkspace(workspace.workspace_id)}
                >
                  <BookOpenText size={15} aria-hidden="true" />
                  <span className="popover-option-copy">
                    <strong>{workspace.name}</strong>
                    {duplicateName ? <small>{workspace.workspace_id.slice(0, 8)}</small> : null}
                  </span>
                  {selected ? <Check size={15} aria-hidden="true" /> : null}
                </button>
              );
            })}
          </div>
          <small className="popover-count">
            显示 {filteredWorkspaces.length} / {matchingWorkspaces.length}
          </small>
          <button
            type="button"
            className="popover-footer-action"
            onClick={() => {
              setOpen(false);
              setQuery("");
              onCreateWorkspace();
            }}
          >
            <Plus size={14} aria-hidden="true" />
            新建工作台
          </button>
        </div>
      ), document.body) : null}
    </div>
  );
}
