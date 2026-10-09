import { type KeyboardEvent } from "react";
import { MessageSquareText, Network, NotebookPen } from "lucide-react";
import { routes, type AnswerMode } from "../../routes";

type AnswerModeSelectorProps = {
  mode: AnswerMode;
  setMode: (mode: AnswerMode) => void;
  disabled?: boolean;
};

const modeIcons = {
  qa: MessageSquareText,
  note: NotebookPen,
  wiki: Network
} satisfies Record<AnswerMode, typeof MessageSquareText>;

/** 三种回答模式直接平铺在输入框工具栏上，悬停可查看各自的检索方式。 */
export function AnswerModeSelector({ mode, setMode, disabled = false }: AnswerModeSelectorProps) {
  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== "ArrowRight" && event.key !== "ArrowLeft") return;
    event.preventDefault();
    const index = routes.findIndex((route) => route.key === mode);
    const step = event.key === "ArrowRight" ? 1 : -1;
    const next = routes[(index + step + routes.length) % routes.length];
    setMode(next.key);
    const button = event.currentTarget.querySelector<HTMLButtonElement>(`[data-mode="${next.key}"]`);
    button?.focus();
  }

  return (
    <div className="answer-mode-switch" role="radiogroup" aria-label="回答模式" onKeyDown={handleKeyDown}>
      {routes.map((route) => {
        const ModeIcon = modeIcons[route.key];
        const selected = route.key === mode;
        return (
          <button
            key={route.key}
            type="button"
            role="radio"
            data-mode={route.key}
            aria-checked={selected}
            tabIndex={selected ? 0 : -1}
            title={route.description}
            className={`answer-mode-segment${selected ? " is-active" : ""}`}
            disabled={disabled}
            onClick={() => setMode(route.key)}
          >
            <ModeIcon size={14} aria-hidden="true" />
            {route.label}
          </button>
        );
      })}
    </div>
  );
}
