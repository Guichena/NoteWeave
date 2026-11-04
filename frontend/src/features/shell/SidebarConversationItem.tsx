import { useEffect, useRef, useState, type FormEvent } from "react";
import { Check, Pencil, Trash2, X } from "lucide-react";

type SidebarConversationItemProps = {
  conversationId: string;
  title: string;
  meta?: string;
  active: boolean;
  disabled: boolean;
  onOpen: () => void;
  onRename?: (conversationId: string, title: string) => Promise<boolean>;
  onDelete?: (conversationId: string) => Promise<boolean>;
};

type Mode = "idle" | "renaming" | "confirm-delete";

/** 侧栏里的一条会话：点击切换，悬停时可以重命名或删除。 */
export function SidebarConversationItem({
  conversationId,
  title,
  meta,
  active,
  disabled,
  onOpen,
  onRename,
  onDelete
}: SidebarConversationItemProps) {
  const [mode, setMode] = useState<Mode>("idle");
  const [draft, setDraft] = useState(title);
  const [submitting, setSubmitting] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const normalized = draft.trim();

  useEffect(() => {
    if (mode === "renaming") {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  }, [mode]);

  function startRename() {
    setDraft(title);
    setMode("renaming");
  }

  async function submitRename(event?: FormEvent) {
    event?.preventDefault();
    if (!onRename || submitting) return;
    if (!normalized || normalized === title) {
      setMode("idle");
      return;
    }
    setSubmitting(true);
    try {
      if (await onRename(conversationId, normalized)) setMode("idle");
    } finally {
      setSubmitting(false);
    }
  }

  async function confirmDelete() {
    if (!onDelete || submitting) return;
    setSubmitting(true);
    try {
      if (!(await onDelete(conversationId))) setMode("idle");
    } finally {
      setSubmitting(false);
    }
  }

  if (mode === "renaming") {
    return (
      <form className="sidebar-conversation-row is-editing" onSubmit={(event) => void submitRename(event)}>
        <input
          ref={inputRef}
          className="sidebar-conversation-input"
          aria-label="会话名称"
          value={draft}
          maxLength={120}
          disabled={submitting}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              // 只退出编辑，不让外层抽屉跟着关闭
              event.preventDefault();
              event.stopPropagation();
              setMode("idle");
            }
          }}
        />
        <button type="submit" className="sidebar-conversation-action" aria-label="保存会话名称" title="保存"
          disabled={submitting || !normalized}>
          <Check size={14} aria-hidden="true" />
        </button>
        <button type="button" className="sidebar-conversation-action" aria-label="取消重命名" title="取消"
          disabled={submitting} onClick={() => setMode("idle")}>
          <X size={14} aria-hidden="true" />
        </button>
      </form>
    );
  }

  if (mode === "confirm-delete") {
    return (
      <div className="sidebar-conversation-row is-confirming" role="group" aria-label={`确认删除会话：${title}`}>
        <span className="sidebar-conversation-confirm">删除「{title}」？</span>
        <button type="button" className="sidebar-conversation-action is-danger" aria-label="确认删除会话"
          title="删除" disabled={submitting} onClick={() => void confirmDelete()}>
          <Trash2 size={14} aria-hidden="true" />
        </button>
        <button type="button" className="sidebar-conversation-action" aria-label="取消删除" title="取消"
          disabled={submitting} onClick={() => setMode("idle")}>
          <X size={14} aria-hidden="true" />
        </button>
      </div>
    );
  }

  return (
    <div className={active ? "sidebar-conversation-row active" : "sidebar-conversation-row"}>
      <button
        type="button"
        className={active ? "sidebar-conversation active" : "sidebar-conversation"}
        aria-current={active ? "page" : undefined}
        title={title}
        disabled={disabled}
        onClick={onOpen}
      >
        <span className="sidebar-conversation-title">{title}</span>
        {meta ? <small>{meta}</small> : null}
      </button>
      {onRename || onDelete ? (
        <span className="sidebar-conversation-actions">
          {onRename ? (
            <button type="button" className="sidebar-conversation-action" aria-label={`重命名会话：${title}`}
              title="重命名" disabled={disabled} onClick={startRename}>
              <Pencil size={13} aria-hidden="true" />
            </button>
          ) : null}
          {onDelete ? (
            <button type="button" className="sidebar-conversation-action" aria-label={`删除会话：${title}`}
              title="删除" disabled={disabled} onClick={() => setMode("confirm-delete")}>
              <Trash2 size={13} aria-hidden="true" />
            </button>
          ) : null}
        </span>
      ) : null}
    </div>
  );
}
