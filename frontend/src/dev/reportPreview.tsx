// 深度研究的设计预览：不登录、不连后端，用评测里的真实报告渲染研究对话和完整报告页，便于调整排版与配色。
// 入口是 report-preview.html，只在 vite 开发服务器里使用，生产构建只打包 index.html。
import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import type { ResearchWorkbenchViewProps } from "../features/research/buildResearchWorkbenchProps";
import { ChatWorkbench } from "../features/conversations/ChatWorkbench";
import type { ResearchRunDetail } from "../features/research/model";
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
  const [deepResearch, setDeepResearch] = useState(false);
  const [theme, setTheme] = useState(document.documentElement.getAttribute("data-theme") ?? "light");
  const report = previewReports.find((item) => item.key === key) ?? previewReports[0];
  const run: ResearchRunDetail | null = view === "new" ? null : {
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
        <button type="button" className="secondary-button" aria-pressed={view === "qa"} onClick={() => setView("qa")}>问答页里的深度研究</button>
        <button type="button" className="secondary-button" onClick={switchTheme}>{theme === "dark" ? "浅色" : "深色"}</button>
      </div>
      <div className={`research-main${view === "report" ? " is-report" : ""}`} style={{ flex: 1, minHeight: 0 }}>
        {view === "qa" && run ? (
          <QaPreview run={run} deepResearch={deepResearch} setDeepResearch={setDeepResearch} onOpenReport={() => setView("report")} />
        ) : view === "report" && run ? (
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

/** 问答页里发起深度研究后的一段对话：研究轮次显示研究进度与报告，之后的追问照常问答。 */
function QaPreview({ run, deepResearch, setDeepResearch, onOpenReport }: {
  run: ResearchRunDetail; deepResearch: boolean; setDeepResearch: (value: boolean) => void; onOpenReport: () => void;
}) {
  const [question, setQuestion] = useState("");
  const props = {
    mode: "qa", setMode: () => undefined, sources: [], chatBusy: false, uploadBusy: false,
    workspace: { workspace_id: "preview", name: "预览工作台", status: "ACTIVE" },
    messages: [
      { role: "user", content: run.question },
      { role: "assistant", content: "", researchRunId: run.research_run_id, answerStatus: "COMPLETED" },
      { role: "user", content: "按这份报告，如果我需要严格全局有序该怎么选？" },
      { role: "assistant", answerMode: "qa", answerStatus: "COMPLETED",
        content: "按上面的研究结论，两者都只在单个分区内保证顺序。需要全局有序时只能使用单分区主题，代价是失去水平扩展能力。" }
    ],
    question, setQuestion, currentRouteLabel: "问答", selectedQaSourceIds: [], setSelectedQaSourceIds: () => undefined,
    toggleQaScope: () => undefined, sendMessage: () => undefined,
    conversation: { conversation_id: "preview", title: "消息队列选型讨论" }, conversationCount: 1,
    setArtifactComposerOpen: () => undefined, artifactComposerOpen: false, artifactRailProps: {},
    onOpenSourceLibrary: () => undefined,
    deepResearch, setDeepResearch,
    loadResearchRun: async () => run,
    onOpenResearchRun: onOpenReport
  } as unknown as Parameters<typeof ChatWorkbench>[0];
  return <ChatWorkbench {...props} />;
}

document.documentElement.setAttribute("data-theme", "light");
createRoot(document.getElementById("root")!).render(<StrictMode><ReportPreview /></StrictMode>);
