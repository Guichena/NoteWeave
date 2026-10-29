import { useEffect, useMemo, useState } from "react";
import { Download, Ellipsis, FilePlus2, LoaderCircle, Maximize2, Minimize2 } from "lucide-react";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { ResearchRunDetail } from "./model";
import { ResearchConversation } from "./ResearchConversation";
import { ResearchEvidencePanel, type EvidenceSelection } from "./ResearchEvidencePanel";
import { ResearchProcessTimeline, type ResearchRound } from "./ResearchProcessTimeline";
import { parseReportSources, ResearchReportView, splitReportSections, splitResearchReport } from "./ResearchReportView";
import { ResearchStateTable } from "./ResearchStateTable";
import { buildResearchTable } from "./researchTable";

type RunTab = "report" | "table" | "process";

type ResearchRunViewProps = {
  run: ResearchRunDetail;
  progressMessage: string;
  isBusy: boolean;
  onExport: () => void;
  onSaveAsSource: () => void;
  onRefresh: () => void;
  onOpenAudit: () => void;
  onResume: (checkpointNo: number) => void;
};

type RunState = "running" | "completed" | "insufficient" | "failed" | "cancelled";

function resolveRunState(run: ResearchRunDetail): RunState {
  const status = (run.status || "").toUpperCase();
  if (status === "QUEUED" || status === "RUNNING") return "running";
  if (status === "FAILED") return "failed";
  if (status === "CANCELLED") return "cancelled";
  if ((run.completion_terminal_state || "").toUpperCase() === "INSUFFICIENT_EVIDENCE") return "insufficient";
  return "completed";
}

const RUN_STATE_LABEL: Record<RunState, string> = {
  running: "研究中",
  completed: "已完成",
  insufficient: "证据不足",
  failed: "运行失败",
  cancelled: "已取消"
};

function text(value: unknown) {
  return typeof value === "string" ? value : "";
}

// 页面标题已经展示报告标题，正文开头重复的一级标题需要去掉。
function stripLeadingTitle(markdown: string) {
  return markdown.replace(/^\s*#\s+[^\n]*\n+/, "");
}

// 每轮的规划说明存放在 loop_rounds 的原始记录里，字段名随 Worker 版本略有差异。
function buildRounds(run: ResearchRunDetail): ResearchRound[] {
  const rawRounds = run.closed_loop_state?.loop_rounds ?? [];
  const summaries = run.research_process_summary?.search_read_timeline?.rounds ?? [];
  return summaries.map((round) => {
    const raw = rawRounds.find((item) => Number(item.round_no) === round.round_no) ?? {};
    const note = text(raw.plan_note) || text(raw.replan_reason) || text(raw.decision_reason);
    return { ...round, note };
  });
}

export function ResearchRunView({
  run,
  progressMessage,
  isBusy,
  onExport,
  onSaveAsSource,
  onRefresh,
  onOpenAudit,
  onResume
}: ResearchRunViewProps) {
  const state = resolveRunState(run);
  const hasReport = Boolean(run.final_report_markdown?.trim());
  const table = useMemo(() => buildResearchTable(run.closed_loop_state), [run.closed_loop_state]);
  const rounds = useMemo(() => buildRounds(run), [run]);
  const [tab, setTab] = useState<RunTab>(hasReport ? "report" : "table");
  const [selection, setSelection] = useState<EvidenceSelection | null>(null);
  const [focused, setFocused] = useState(false);
  // 对话里的报告卡片：取摘要的第一段（去掉引用标记）和来源数
  const report = useMemo(() => {
    if (!hasReport) return { summary: "", sourceCount: 0 };
    const { body, audit } = splitResearchReport(stripLeadingTitle(run.final_report_markdown));
    const summary = splitReportSections(body).summary.split(/\n\s*\n/)[0]
      .replace(/\[evidence[:-][^\]]+\]/g, "").replace(/\*\*/g, "").replace(/\s+/g, " ").trim();
    return {
      summary: summary.length > 110 ? `${summary.slice(0, 110)}…` : summary,
      sourceCount: parseReportSources(audit).length
    };
  }, [hasReport, run.final_report_markdown]);

  // 切换到另一个研究时回到默认标签，并关闭证据面板。
  useEffect(() => {
    setTab(hasReport ? "report" : "table");
    setSelection(null);
  }, [run.research_run_id, hasReport]);

  useEffect(() => {
    if (!selection) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) setSelection(null);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [selection]);

  const title = run.final_report_title || run.question;
  const verified = table.counts.verified;
  const unresolved = table.counts.conflict + table.counts.repair + table.counts.missing;
  const checkpoints = run.closed_loop_state?.checkpoints ?? [];
  const saved = Boolean(run.saved_report_source);
  const selectedCellId = selection?.kind === "cell" ? selection.cell.id : null;
  const activeEvidence = selection?.kind === "evidence" ? selection.evidenceId : null;

  const tabs: Array<{ key: RunTab; label: string; disabled?: boolean }> = [
    { key: "report", label: "报告", disabled: !hasReport },
    { key: "table", label: `研究表${table.counts.total ? ` ${verified}/${table.counts.total}` : ""}` },
    { key: "process", label: "过程" }
  ];

  return (
    <article className="research-run" aria-label="研究运行">
      <header className="research-run-header">
        <div className="research-run-heading">
          <span className={`research-run-state is-${state}`}>
            {state === "running" ? <LoaderCircle size={13} className="is-spinning" aria-hidden="true" /> : <i aria-hidden="true" />}
            {RUN_STATE_LABEL[state]}
          </span>
          <h2>{title}</h2>
          <p>
            {title !== run.question ? <>{run.question} · </> : null}
            {run.updated_at ? `更新于 ${formatRelativeTime(run.updated_at)}` : ""}
          </p>
        </div>
        <div className="research-run-actions">
          <button type="button" className="secondary-button" onClick={onExport} disabled={!hasReport}>
            <Download size={15} aria-hidden="true" />导出
          </button>
          <button
            type="button"
            className="secondary-button"
            onClick={onSaveAsSource}
            disabled={isBusy || !hasReport || saved || state === "insufficient"}
          >
            <FilePlus2 size={15} aria-hidden="true" />{saved ? "已存入资料库" : "存入资料库"}
          </button>
          <details className="research-run-more">
            <summary aria-label="更多操作"><Ellipsis size={16} aria-hidden="true" /></summary>
            <div role="menu">
              <button type="button" role="menuitem" className="research-menu-item" onClick={onRefresh} disabled={isBusy}>刷新状态</button>
              <button type="button" role="menuitem" className="research-menu-item" onClick={onOpenAudit}>审计详情</button>
            </div>
          </details>
        </div>
      </header>

      <div className={`research-run-split${focused ? " is-focused" : ""}`}>
        {focused ? null : (
          <ResearchConversation
            question={run.question}
            state={state}
            table={table}
            rounds={rounds}
            progressMessage={progressMessage}
            reportTitle={hasReport ? title : ""}
            reportSummary={report.summary}
            sourceCount={report.sourceCount}
            reportOpen={tab === "report"}
            onOpenReport={() => setTab("report")}
          />
        )}

        <section className="research-doc-pane" aria-label="研究文档">
        <div className="research-doc-toolbar">
          <div className="research-run-tabs" role="tablist" aria-label="研究内容">
            {tabs.map((item) => (
              <button
                key={item.key}
                type="button"
                role="tab"
                aria-selected={tab === item.key}
                disabled={item.disabled}
                className={`research-tab${tab === item.key ? " is-active" : ""}`}
                onClick={() => setTab(item.key)}
              >
                {item.label}
              </button>
            ))}
          </div>
          <button
            type="button"
            className="icon-button research-doc-focus"
            aria-label={focused ? "退出全屏阅读" : "全屏阅读"}
            title={focused ? "退出全屏阅读" : "全屏阅读"}
            aria-pressed={focused}
            onClick={() => setFocused((value) => !value)}
          >
            {focused ? <Minimize2 size={15} aria-hidden="true" /> : <Maximize2 size={15} aria-hidden="true" />}
          </button>
        </div>

        <div className="research-run-body">
          <div className="research-run-content" role="tabpanel">
            {tab === "report" && hasReport ? (
              <ResearchReportView
                markdown={stripLeadingTitle(run.final_report_markdown)}
                activeEvidence={activeEvidence}
                onEvidenceClick={(evidenceId) => setSelection({ kind: "evidence", evidenceId })}
              />
            ) : tab === "process" ? (
              <ResearchProcessTimeline
                rounds={rounds}
                counterfactual={run.counterfactual_summary ?? run.closed_loop_state?.counterfactual_summary ?? null}
                checkpoints={checkpoints}
                running={state === "running"}
                canResume={!isBusy && state !== "running"}
                onResume={onResume}
              />
            ) : (
              <div className="research-table-tab">
                <dl className="research-run-metrics" aria-label="研究概况">
                  <div>
                    <dt>研究对象</dt>
                    <dd>{table.rows.length}</dd>
                  </div>
                  <div className="is-progress">
                    <dt>已验证字段</dt>
                    <dd>{verified}<small> / {table.counts.total}</small></dd>
                    <span className="research-run-bar" aria-hidden="true">
                      <i style={{ width: `${table.counts.total ? (verified / table.counts.total) * 100 : 0}%` }} />
                    </span>
                  </div>
                  <div>
                    <dt>待解决</dt>
                    <dd className={unresolved > 0 ? "is-warning" : undefined}>{unresolved}</dd>
                  </div>
                  <div>
                    <dt>证据</dt>
                    <dd>{table.evidenceById.size}</dd>
                  </div>
                  <div>
                    <dt>轮次</dt>
                    <dd>{rounds.length}</dd>
                  </div>
                </dl>
                <ResearchStateTable
                  table={table}
                  selectedCellId={selectedCellId}
                  onSelectCell={(cell) => setSelection({ kind: "cell", cell })}
                />
              </div>
            )}
          </div>
          {selection ? (
            <button
              type="button"
              className="research-evidence-backdrop"
              aria-label="点击背景关闭证据详情"
              tabIndex={-1}
              onClick={() => setSelection(null)}
            />
          ) : null}
          {selection ? (
            <ResearchEvidencePanel
              table={table}
              selection={selection}
              onClose={() => setSelection(null)}
              onSelectCell={(cell) => setSelection({ kind: "cell", cell })}
            />
          ) : null}
        </div>
        </section>
      </div>
    </article>
  );
}
