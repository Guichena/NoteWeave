import { useEffect, useMemo, useRef, useState } from "react";
import { Check, ChevronDown, MessageSquareText, Plus, Search } from "lucide-react";
import { formatRelativeTime } from "../../shared/util/datetime";

type ConversationSummary = {
  conversation_id: string;
  title: string;
  last_active_at?: string;
};

type ConversationSwitcherProps = {
  conversations: ConversationSummary[];
  conversationId: string;
  conversationTitle: string;
  disabled: boolean;
  onSwitchConversation: (conversationId: string) => void;
  onCreateConversation: (trigger: HTMLElement | null) => void;
};

/** 笔记本内的会话切换：会话是这一本笔记本的次级上下文，不是一级导航。 */
export function ConversationSwitcher({
  conversations,
  conversationId,
  conversationTitle,
  disabled,
  onSwitchConversation,
  onCreateConversation
}: ConversationSwitcherProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const rootRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const visibleConversations = useMemo(() => (
    normalizedQuery
      ? conversations.filter((item) => item.title.toLocaleLowerCase().includes(normalizedQuery))
      : conversations
  ), [conversations, normalizedQuery]);
  const currentTitle = conversationTitle
    || conversations.find((item) => item.conversation_id === conversationId)?.title
    || "未选择会话";

  useEffect(() => {
    if (!open) return;
    function closeOnOutsidePointer(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
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
      window.removeEventListener("pointerdown", closeOnOutsidePointer);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  useEffect(() => {
    setOpen(false);
    setQuery("");
  }, [conversationId]);

  return (
    <div className="conversation-switcher" ref={rootRef}>
      <button
        ref={triggerRef}
        type="button"
        className="crumb-trigger conversation-switcher-trigger"
        aria-label={`切换会话，当前：${currentTitle}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="crumb-text" title={currentTitle}>{currentTitle}</span>
        <ChevronDown className="crumb-chevron" size={14} aria-hidden="true" />
      </button>

      {open ? (
        <div className="popover conversation-switcher-popover" role="dialog" aria-label="会话列表">
          <div className="popover-header">
            <strong>本工作台的会话</strong>
            <span>{conversations.length}</span>
          </div>
          {conversations.length > 6 ? (
            <label className="popover-search">
              <Search size={14} aria-hidden="true" />
              <input
                type="search"
                aria-label="搜索会话"
                value={query}
                placeholder="搜索会话"
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
          ) : null}
          <div className="popover-list" role="listbox" aria-label="会话">
            {visibleConversations.length === 0 ? (
              <p className="popover-empty">{conversations.length === 0 ? "还没有会话" : "没有匹配的会话"}</p>
            ) : visibleConversations.map((item) => {
              const selected = item.conversation_id === conversationId;
              return (
                <button
                  key={item.conversation_id}
                  type="button"
                  role="option"
                  aria-selected={selected}
                  className={selected ? "popover-option active" : "popover-option"}
                  onClick={() => {
                    setOpen(false);
                    setQuery("");
                    if (!selected) onSwitchConversation(item.conversation_id);
                  }}
                >
                  <MessageSquareText size={15} aria-hidden="true" />
                  <span className="popover-option-copy">
                    <strong>{item.title}</strong>
                    {item.last_active_at ? <small>{formatRelativeTime(item.last_active_at)}</small> : null}
                  </span>
                  {selected ? <Check size={15} aria-hidden="true" /> : null}
                </button>
              );
            })}
          </div>
          <button
            type="button"
            className="popover-footer-action"
            onClick={() => {
              setOpen(false);
              setQuery("");
              onCreateConversation(triggerRef.current);
            }}
          >
            <Plus size={14} aria-hidden="true" />
            新建会话…
          </button>
        </div>
      ) : null}
    </div>
  );
}
