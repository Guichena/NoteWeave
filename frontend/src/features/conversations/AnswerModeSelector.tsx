import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, MessageSquareText, Network, NotebookPen } from "lucide-react";
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

export function AnswerModeSelector({ mode, setMode, disabled = false }: AnswerModeSelectorProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const activeRoute = routes.find((route) => route.key === mode) ?? routes[0];
  const ActiveIcon = modeIcons[activeRoute.key];

  useEffect(() => {
    if (!open) return;
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", closeOnOutsidePointer);
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsidePointer);
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  return (
    <div className="answer-mode-selector" ref={rootRef}>
      <button
        type="button"
        className="answer-mode-trigger"
        aria-label={`回答模式：${activeRoute.label}`}
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
      >
        <ActiveIcon size={15} aria-hidden="true" />
        <span>{activeRoute.label}</span>
        <ChevronDown className={open ? "is-open" : ""} size={14} aria-hidden="true" />
      </button>

      {open ? (
        <div className="answer-mode-menu" role="menu" aria-label="选择回答模式">
          <p className="answer-mode-menu-label">回答方式</p>
          {routes.map((route) => {
            const ModeIcon = modeIcons[route.key];
            const selected = route.key === mode;
            return (
              <button
                type="button"
                role="menuitemradio"
                aria-checked={selected}
                className={selected ? "answer-mode-option is-selected" : "answer-mode-option"}
                key={route.key}
                onClick={() => {
                  setMode(route.key);
                  setOpen(false);
                }}
              >
                <span className="answer-mode-option-icon" aria-hidden="true"><ModeIcon size={16} /></span>
                <span className="answer-mode-option-copy">
                  <strong>{route.label}</strong>
                  <small>{route.description}</small>
                </span>
                {selected ? <Check size={15} aria-hidden="true" /> : <span aria-hidden="true" />}
              </button>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
