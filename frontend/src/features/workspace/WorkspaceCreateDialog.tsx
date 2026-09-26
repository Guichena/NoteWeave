import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { BookOpenText, FileStack, MessagesSquare, Network, Sparkles, X } from "lucide-react";
import { type CreateWorkspaceInput } from "./api";

type WorkspaceCreateDialogProps = {
  busy: boolean;
  onClose: () => void;
  onCreate: (input: CreateWorkspaceInput) => Promise<boolean>;
};

const WORKSPACE_CAPABILITIES = [
  { icon: FileStack, label: "共享资料库" },
  { icon: MessagesSquare, label: "多个独立会话" },
  { icon: Network, label: "研究、Wiki 与记忆" }
];

export function WorkspaceCreateDialog({ busy, onClose, onCreate }: WorkspaceCreateDialogProps) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const nameInputRef = useRef<HTMLInputElement | null>(null);
  const titleId = useId();
  const descriptionId = useId();
  const normalizedName = name.trim();

  useEffect(() => {
    const focusTimer = window.setTimeout(() => nameInputRef.current?.focus(), 0);
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
    if (!normalizedName || busy) return;
    const created = await onCreate({ name: normalizedName, description: description.trim() });
    if (created) onClose();
  }

  return (
    <div className="workspace-create-backdrop" onMouseDown={() => !busy && onClose()}>
      <section
        className="workspace-create-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="workspace-create-header">
          <span className="workspace-create-mark" aria-hidden="true"><BookOpenText size={20} /></span>
          <div>
            <h2 id={titleId}>创建研究工作台</h2>
          </div>
          <button type="button" className="workspace-create-close" aria-label="关闭" disabled={busy} onClick={onClose}>
            <X size={18} aria-hidden="true" />
          </button>
        </header>

        <p id={descriptionId} className="workspace-create-intro">
          工作台是资料、对话与知识产物的独立边界。创建后会自动准备第一个会话。
        </p>

        <div className="workspace-create-capabilities" aria-label="工作台包含的能力">
          {WORKSPACE_CAPABILITIES.map(({ icon: Icon, label }) => (
            <span key={label}><Icon size={14} aria-hidden="true" />{label}</span>
          ))}
        </div>

        <form className="workspace-create-form" onSubmit={(event) => void handleSubmit(event)}>
          <label htmlFor="workspace-create-name">
            <span>工作台名称</span>
            <input
              ref={nameInputRef}
              id="workspace-create-name"
              value={name}
              maxLength={80}
              autoComplete="off"
              placeholder="例如：产品战略研究"
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <label htmlFor="workspace-create-description">
            <span>用途说明 <small>可选</small></span>
            <textarea
              id="workspace-create-description"
              value={description}
              maxLength={240}
              rows={3}
              placeholder="说明这个工作台要解决的问题或覆盖的资料范围"
              onChange={(event) => setDescription(event.target.value)}
            />
          </label>
          <footer className="workspace-create-actions">
            <span><Sparkles size={14} aria-hidden="true" />创建后可立即上传 PDF、Markdown 等资料</span>
            <div>
              <button type="button" className="secondary-button" disabled={busy} onClick={onClose}>取消</button>
              <button type="submit" className="workspace-create-submit" disabled={!normalizedName || busy}>
                {busy ? "正在创建" : "创建工作台"}
              </button>
            </div>
          </footer>
        </form>
      </section>
    </div>
  );
}
