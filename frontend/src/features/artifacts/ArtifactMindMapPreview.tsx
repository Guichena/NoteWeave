import {
  forwardRef,
  useEffect,
  useId,
  useImperativeHandle,
  useMemo,
  useRef,
  useState
} from "react";
import { createPortal } from "react-dom";
import { Download, Focus, Maximize2, Network, X } from "lucide-react";

type ArtifactMindMapPreviewProps = {
  title: string;
  markdown: string;
  /** teaser 为一行摘要加打开按钮；inline 直接内嵌可缩放的导图，右上角保留全屏按钮。 */
  variant?: "teaser" | "inline";
};

type MindMapCanvasHandle = {
  fit: () => void;
};

type MindMapInstance = import("markmap-view").Markmap;

export function isMindMapArtifact(skillKey: string): boolean {
  return skillKey.trim().toLowerCase() === "mindmap_from_workspace";
}

export function sanitizeMindMapMarkdown(markdown: string): string {
  return stripMarkdownInlineTargets(markdown)
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, "")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, "")
    .replace(/!\[([^\]\n]*)\]\s*(?:\[[^\]\n]*\])?/g, "$1")
    .replace(/<[^>\n]+>/g, "")
    .replace(/javascript:/gi, "")
    .slice(0, 30_000)
    .trim();
}

export function buildMindMapDocument(markdown: string, fallbackTitle: string): string {
  const sanitized = sanitizeMindMapMarkdown(markdown);
  const safeTitle = sanitizeMindMapMarkdown(fallbackTitle).replace(/^#+\s*/, "").trim() || "工作台知识导图";
  const lines = sanitized.split(/\r?\n/);
  const rootIndex = lines.findIndex((line) => /^#\s+\S/.test(line));
  if (rootIndex < 0) {
    return `# ${safeTitle}\n\n${sanitized}`.trim();
  }
  const root = lines[rootIndex].trim();
  const body = lines
    .filter((_, index) => index !== rootIndex)
    .map((line) => /^#\s+\S/.test(line) ? line.replace(/^#\s+/, "## ") : line)
    .join("\n")
    .trim();
  return body ? `${root}\n\n${body}` : root;
}

export function summarizeMindMap(markdown: string, fallbackTitle: string) {
  const document = buildMindMapDocument(markdown, fallbackTitle);
  const lines = document.split(/\r?\n/);
  const root = lines.find((line) => /^#\s+/.test(line))?.replace(/^#\s+/, "").trim() || fallbackTitle;
  const branches = lines
    .filter((line) => /^##\s+/.test(line))
    .map((line) => line.replace(/^##\s+/, "").trim())
    .filter(Boolean);
  return { root, branchCount: branches.length, branches: branches.slice(0, 3) };
}

export function ArtifactMindMapPreview({ title, markdown, variant = "teaser" }: ArtifactMindMapPreviewProps) {
  const [open, setOpen] = useState(false);
  const canvasRef = useRef<MindMapCanvasHandle>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const triggerButtonRef = useRef<HTMLButtonElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const wasOpenRef = useRef(false);
  const titleId = useId();
  const instructionsId = useId();
  const mindMapDocument = useMemo(() => buildMindMapDocument(markdown, title), [markdown, title]);
  const summary = useMemo(() => summarizeMindMap(markdown, title), [markdown, title]);

  useEffect(() => {
    if (!open) {
      if (wasOpenRef.current && returnFocusRef.current?.isConnected) {
        returnFocusRef.current.focus();
      }
      wasOpenRef.current = false;
      returnFocusRef.current = null;
      return undefined;
    }
    wasOpenRef.current = true;
    const previousOverflow = documentBodyStyle("overflow");
    window.document.body.style.overflow = "hidden";
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setOpen(false);
        return;
      }
      if (event.key !== "Tab" || !dialogRef.current) {
        return;
      }
      const focusableElements = getFocusableElements(dialogRef.current);
      if (focusableElements.length === 0) {
        event.preventDefault();
        dialogRef.current.focus();
        return;
      }
      const firstElement = focusableElements[0];
      const lastElement = focusableElements[focusableElements.length - 1];
      const activeElement = window.document.activeElement;
      if (event.shiftKey && (activeElement === firstElement || !dialogRef.current.contains(activeElement))) {
        event.preventDefault();
        lastElement.focus();
      } else if (!event.shiftKey && (activeElement === lastElement || !dialogRef.current.contains(activeElement))) {
        event.preventDefault();
        firstElement.focus();
      }
    };
    window.addEventListener("keydown", handleKeyDown, true);
    const focusTimer = window.setTimeout(() => closeButtonRef.current?.focus(), 0);
    return () => {
      window.clearTimeout(focusTimer);
      window.removeEventListener("keydown", handleKeyDown, true);
      window.document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  function downloadMarkdown() {
    const blob = new Blob([mindMapDocument], { type: "text/markdown;charset=utf-8" });
    const href = URL.createObjectURL(blob);
    const anchor = window.document.createElement("a");
    anchor.href = href;
    anchor.download = `${safeFileName(summary.root)}.md`;
    anchor.click();
    URL.revokeObjectURL(href);
  }

  const dialog = open && typeof window !== "undefined" ? createPortal(
    <div
      className="artifact-mindmap-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          setOpen(false);
        }
      }}
    >
      <section
        ref={dialogRef}
        className="artifact-mindmap-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={instructionsId}
        tabIndex={-1}
      >
        <header className="artifact-mindmap-header">
          <div className="artifact-mindmap-heading">
            <span className="artifact-mindmap-mark" aria-hidden="true"><Network size={18} /></span>
            <span>
              <small>交互式思维导图</small>
              <strong id={titleId}>{summary.root}</strong>
            </span>
          </div>
          <div className="artifact-mindmap-toolbar" aria-label="导图工具">
            <button type="button" className="secondary-button" onClick={() => canvasRef.current?.fit()}>
              <Focus size={16} aria-hidden="true" />
              <span>适合窗口</span>
            </button>
            <button type="button" className="secondary-button" onClick={downloadMarkdown}>
              <Download size={16} aria-hidden="true" />
              <span>下载源文件</span>
            </button>
            <button
              ref={closeButtonRef}
              type="button"
              className="artifact-mindmap-close secondary-button"
              onClick={() => setOpen(false)}
              aria-label="关闭思维导图"
            >
              <X size={18} aria-hidden="true" />
            </button>
          </div>
        </header>
        <MindMapCanvas ref={canvasRef} markdown={mindMapDocument} title={summary.root} />
        <footer id={instructionsId} className="artifact-mindmap-footer">
          <span>滚轮缩放，拖拽移动</span>
          <span>点击节点圆点折叠或展开分支</span>
          <span>{summary.branchCount} 条一级分支</span>
        </footer>
      </section>
    </div>,
    window.document.body
  ) : null;

  return (
    variant === "inline" ? (
      <>
        <section className="artifact-mindmap-inline" aria-label="思维导图预览">
          {open ? null : <MindMapCanvas markdown={mindMapDocument} title={summary.root} />}
          <button
            ref={triggerButtonRef}
            type="button"
            className="secondary-button artifact-mindmap-expand"
            onClick={() => {
              returnFocusRef.current = triggerButtonRef.current;
              setOpen(true);
            }}
          >
            <Maximize2 size={14} aria-hidden="true" />
            <span>全屏查看</span>
          </button>
        </section>
        {dialog}
      </>
    ) : (
    <>
      <section className="artifact-mindmap-teaser" aria-label="思维导图预览">
        <span className="artifact-mindmap-teaser-icon" aria-hidden="true"><Network size={18} /></span>
        <span className="artifact-mindmap-teaser-copy">
          <small>交互式导图</small>
          <strong>{summary.root}</strong>
          <span>
            {summary.branchCount > 0 ? `${summary.branchCount} 条主分支` : "层级结构已生成"}
            {summary.branches.length > 0 ? ` · ${summary.branches.join(" / ")}` : ""}
          </span>
        </span>
        <button
          ref={triggerButtonRef}
          type="button"
          className="secondary-button"
          onClick={() => {
            returnFocusRef.current = triggerButtonRef.current;
            setOpen(true);
          }}
        >
          打开导图
        </button>
      </section>
      {dialog}
    </>
    )
  );
}

const MindMapCanvas = forwardRef<MindMapCanvasHandle, { markdown: string; title: string }>(
  function MindMapCanvas({ markdown, title }, ref) {
    const svgRef = useRef<SVGSVGElement>(null);
    const instanceRef = useRef<MindMapInstance | null>(null);
    const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");

    useImperativeHandle(ref, () => ({
      fit: () => {
        void instanceRef.current?.fit();
      }
    }), []);

    useEffect(() => {
      let cancelled = false;
      let instance: MindMapInstance | null = null;
      setStatus("loading");
      void Promise.all([import("markmap-lib"), import("markmap-view")])
        .then(async ([{ Transformer }, { Markmap }]) => {
          if (cancelled || !svgRef.current) {
            return;
          }
          const transformed = new Transformer().transform(markdown);
          const isDark = document.documentElement.getAttribute("data-theme") === "dark";
          const palette = isDark
            ? ["#5eead4", "#93c5fd", "#fcd34d", "#c4b5fd", "#f9a8d4"]
            : ["#0d8f70", "#2563eb", "#b7791f", "#7c3aed", "#be185d"];
          const reducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
          const mobileViewport = window.matchMedia?.("(max-width: 767px)").matches
            ?? window.innerWidth <= 767;
          instance = Markmap.create(svgRef.current, {
            autoFit: !mobileViewport,
            duration: reducedMotion ? 0 : 220,
            fitRatio: 0.88,
            initialExpandLevel: mobileViewport ? 2 : -1,
            maxInitialScale: 1.35,
            maxWidth: 220,
            nodeMinHeight: 20,
            paddingX: 10,
            pan: true,
            scrollForPan: false,
            spacingHorizontal: 82,
            spacingVertical: 12,
            toggleRecursively: false,
            zoom: true,
            color: (node) => palette[Math.max(0, node.state.depth - 1) % palette.length],
            lineWidth: (node) => node.state.depth === 1 ? 2.5 : 1.65
          }, transformed.root);
          instance.zoom.extent(() => {
            const bounds = svgRef.current?.getBoundingClientRect();
            return [
              [0, 0],
              [Math.max(1, bounds?.width ?? 1), Math.max(1, bounds?.height ?? 1)]
            ];
          });
          instanceRef.current = instance;
          await instance.fit();
          if (!cancelled) {
            setStatus("ready");
          }
        })
        .catch(() => {
          if (!cancelled) {
            setStatus("error");
          }
        });
      return () => {
        cancelled = true;
        instanceRef.current = null;
        instance?.destroy();
      };
    }, [markdown]);

    return (
      <div className={`artifact-mindmap-canvas is-${status}`}>
        {status === "loading" ? <p role="status">正在构建导图</p> : null}
        {status === "error" ? <p role="alert">导图渲染失败，源 Markdown 仍可下载或写回。</p> : null}
        <svg ref={svgRef} className="markmap" role="img" aria-label={`${title} 思维导图`} />
      </div>
    );
  }
);

function safeFileName(value: string): string {
  const normalized = value.replace(/[\\/:*?"<>|]/g, "-").replace(/\s+/g, " ").trim();
  return normalized.slice(0, 80) || "noteweave-mindmap";
}

function documentBodyStyle(property: "overflow"): string {
  return typeof document === "undefined" ? "" : document.body.style[property];
}

function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>([
    "a[href]",
    "button:not([disabled])",
    "input:not([disabled])",
    "select:not([disabled])",
    "textarea:not([disabled])",
    "[tabindex]"
  ].join(","))).filter((element) => (
    element.tabIndex >= 0
    && !element.hasAttribute("hidden")
    && element.getAttribute("aria-hidden") !== "true"
  ));
}

function stripMarkdownInlineTargets(markdown: string): string {
  let current = markdown;
  for (let pass = 0; pass < 8; pass += 1) {
    const stripped = stripMarkdownInlineTargetsOnce(current);
    if (stripped === current) {
      return stripped;
    }
    current = stripped;
  }
  return current;
}

function stripMarkdownInlineTargetsOnce(markdown: string): string {
  let output = "";
  let index = 0;
  while (index < markdown.length) {
    const bracketStart = markdown[index] === "!" && markdown[index + 1] === "["
      ? index + 1
      : markdown[index] === "[" ? index : -1;
    if (bracketStart < 0) {
      output += markdown[index];
      index += 1;
      continue;
    }
    const bracketEnd = findBalancedEnd(markdown, bracketStart, "[", "]");
    if (bracketEnd < 0 || markdown[bracketEnd + 1] !== "(") {
      output += markdown[index];
      index += 1;
      continue;
    }
    const targetEnd = findBalancedEnd(markdown, bracketEnd + 1, "(", ")");
    if (targetEnd < 0) {
      output += markdown[index];
      index += 1;
      continue;
    }
    output += markdown.slice(bracketStart + 1, bracketEnd);
    index = targetEnd + 1;
  }
  return output;
}

function findBalancedEnd(value: string, start: number, open: string, close: string): number {
  let depth = 0;
  for (let index = start; index < value.length; index += 1) {
    if (value[index] === "\\") {
      index += 1;
      continue;
    }
    if (value[index] === open) {
      depth += 1;
    } else if (value[index] === close) {
      depth -= 1;
      if (depth === 0) {
        return index;
      }
    }
  }
  return -1;
}
