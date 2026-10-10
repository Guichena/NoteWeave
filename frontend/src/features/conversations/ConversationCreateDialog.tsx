import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { LibraryBig, MessageSquareText, ShieldCheck, X } from "lucide-react";

type ConversationCreateDialogProps = {
  workspaceName: string;
  busy: boolean;
  onClose: () => void;
  onCreate: (title: string) => Promise<boolean>;
};

export function ConversationCreateDialog({
  workspaceName,
  busy,
  onClose,
  onCreate
}: ConversationCreateDialogProps) {
  const [title, setTitle] = useState("");
  const titleInputRef = useRef<HTMLInputElement | null>(null);
  const headingId = useId();
  const normalizedTitle = title.trim();

  useEffect(() => {
    const focusTimer = window.setTimeout(() => titleInputRef.current?.focus(), 0);
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !busy) onClose();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.clearTimeout(focusTimer);
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [busy, onClose]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!normalizedTitle || busy) return;
    const created = await onCreate(normalizedTitle);
    if (created) onClose();
  }

  return (
    <div className="conversation-create-backdrop" onMouseDown={() => !busy && onClose()}>
      <section
        className="conversation-create-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={headingId}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="conversation-create-header">
          <span className="conversation-create-mark" aria-hidden="true"><MessageSquareText size={19} /></span>
          <div>
            <h2 id={headingId}>新建独立会话</h2>
          </div>
          <button type="button" className="conversation-create-close" aria-label="关闭" disabled={busy} onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>

        <div className="conversation-create-context">
          <strong title={workspaceName}>{workspaceName}</strong>
          <div><LibraryBig size={15} aria-hidden="true" /><span>共享这个工作台的资料库</span></div>
          <div><ShieldCheck size={15} aria-hidden="true" /><span>消息记录与问答范围独立保存</span></div>
        </div>

        <form className="conversation-create-form" onSubmit={(event) => void handleSubmit(event)}>
          <label htmlFor="conversation-create-title">
            <span>会话名称</span>
            <input
              ref={titleInputRef}
              id="conversation-create-title"
              value={title}
              maxLength={120}
              autoComplete="off"
              placeholder="例如：竞品证据梳理"
              onChange={(event) => setTitle(event.target.value)}
            />
          </label>
          <footer className="conversation-create-actions">
            <button type="button" className="secondary-button" disabled={busy} onClick={onClose}>取消</button>
            <button type="submit" className="conversation-create-submit" disabled={!normalizedTitle || busy}>
              {busy ? "正在创建" : "创建会话"}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}
