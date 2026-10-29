// 深度研究的设计预览：不登录、不连后端，用评测里的真实报告渲染研究对话和完整报告页，便于调整排版与配色。
// 入口是 report-preview.html，只在 vite 开发服务器里使用，生产构建只打包 index.html。
import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import type { ResearchWorkbenchViewProps } from "../features/research/buildResearchWorkbenchProps";
import { ResearchChatView } from "../features/research/ResearchChatView";
import { ResearchRunView } from "../features/research/ResearchRunView";
import { completedRun } from "./fixtures/research";
import { previewReports } from "./fixtures/researchReports";
import "../styles/index.css";

function titleOf(markdown: string) {
  return markdown.match(/^#\s+(.+)$/m)?.[1]?.trim() ?? "研究报告";
}

function ReportPreview() {
  const params = new URLSearchParams(window.location.search);
  const [key, setKey] = useState(params.get("report") ?? previewReports[0].key);
  const [view, setView] = useState(params.get("view") ?? "chat");
  const [question, setQuestion] = useState("");
  const [theme, setTheme] = useState(document.documentElement.getAttribute("data-theme") ?? "light");
  const report = previewReports.find((item) => item.key === key) ?? previewReports[0];
  const run = view === "new" ? null : {
    ...completedRun, research_run_id: report.key, question: titleOf(report.markdown),
    final_report_title: titleOf(report.markdown), final_report_markdown: report.markdown
  };
  // 预览只需要输入框用到的字段
  const composer = {
    workspace: { workspace_id: "preview" }, sources: [], researchQuestion: question, setResearchQuestion: setQuestion,
    researchGoal: "", setResearchGoal: () => undefined, researchRetrievalMode: "WEB_ONLY",
    setResearchRetrievalMode: () => undefined, researchTimeRange: "", setResearchTimeRange: () => undefined,
    researchDepth: "STANDARD", setResearchDepth: () => undefined, researchType: "AUTO", setResearchType: () => undefined,
    researchConstraintsText: "", setResearchConstraintsText: () => undefined, selectedResearchSourceIds: [],
    toggleResearchScope: () => undefined, researchScopeSources: []
  } as unknown as ResearchWorkbenchViewProps["sidebar"];

  const switchTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    setTheme(next);
  };
  const noop = () => undefined;

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100dvh", background: "var(--bg)" }}>
      <div style={{ display: "flex", flexWrap: "wrap", gap: 8, padding: "10px 16px", borderBottom: "1px solid var(--border)",
        background: "var(--surface)", fontSize: 13 }}>
        {previewReports.map((item) => (
          <button key={item.key} type="button" className="secondary-button" aria-pressed={item.key === key && view !== "new"}
            onClick={() => { setKey(item.key); setView("chat"); }}>{item.label}</button>
        ))}
        <button type="button" className="secondary-button" aria-pressed={view === "new"} onClick={() => setView("new")}>新研究</button>
        <button type="button" className="secondary-button" onClick={switchTheme}>{theme === "dark" ? "浅色" : "深色"}</button>
      </div>
      <div className={`research-main${view === "report" ? " is-report" : ""}`} style={{ flex: 1, minHeight: 0 }}>
        {view === "report" && run ? (
          <ResearchRunView run={run} progressMessage="" isBusy={false} onBack={() => setView("chat")} onExport={noop}
            onSaveAsSource={noop} onRefresh={noop} onOpenAudit={noop} onResume={noop} />
        ) : (
          <ResearchChatView run={run} progressMessage="" isBusy={false} composer={composer} onStart={noop}
            onOpenReport={() => setView("report")} onExport={noop} onSaveAsSource={noop} />
        )}
      </div>
    </div>
  );
}

document.documentElement.setAttribute("data-theme", "light");
createRoot(document.getElementById("root")!).render(<StrictMode><ReportPreview /></StrictMode>);
