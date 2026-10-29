// 研究报告的设计预览：不登录、不连后端，用评测里的真实报告渲染研究页，便于调整排版与配色。
// 入口是 report-preview.html，只在 vite 开发服务器里使用，生产构建只打包 index.html。
import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { ResearchRunView } from "../features/research/ResearchRunView";
import { completedRun } from "./fixtures/research";
import { previewReports } from "./fixtures/researchReports";
import "../styles/index.css";

function titleOf(markdown: string) {
  return markdown.match(/^#\s+(.+)$/m)?.[1]?.trim() ?? "研究报告";
}

function ReportPreview() {
  const initial = new URLSearchParams(window.location.search).get("report") ?? previewReports[0].key;
  const [key, setKey] = useState(initial);
  const [theme, setTheme] = useState(document.documentElement.getAttribute("data-theme") ?? "light");
  const report = previewReports.find((item) => item.key === key) ?? previewReports[0];
  const run = { ...completedRun, research_run_id: report.key, question: titleOf(report.markdown),
    final_report_title: titleOf(report.markdown), final_report_markdown: report.markdown };

  const switchTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    setTheme(next);
  };

  return (
    <div style={{ minHeight: "100dvh", background: "var(--bg)" }}>
      <div style={{ display: "flex", gap: 8, padding: "10px 16px", borderBottom: "1px solid var(--border)",
        background: "var(--surface)", fontSize: 13 }}>
        {previewReports.map((item) => (
          <button key={item.key} type="button" className="secondary-button" aria-pressed={item.key === key}
            onClick={() => setKey(item.key)}>{item.label}</button>
        ))}
        <button type="button" className="secondary-button" onClick={switchTheme}>
          {theme === "dark" ? "浅色" : "深色"}
        </button>
      </div>
      <ResearchRunView
        run={run}
        progressMessage=""
        isBusy={false}
        onExport={() => undefined}
        onSaveAsSource={() => undefined}
        onRefresh={() => undefined}
        onOpenAudit={() => undefined}
        onResume={() => undefined}
      />
    </div>
  );
}

document.documentElement.setAttribute("data-theme", "light");
createRoot(document.getElementById("root")!).render(<StrictMode><ReportPreview /></StrictMode>);
