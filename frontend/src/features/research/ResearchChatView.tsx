import { useEffect, useMemo, useRef } from "react";
import {
  ArrowRight, BookOpen, CircleCheck, CircleDashed, Download, FilePlus2, FileText, Search, TriangleAlert
} from "lucide-react";
import { MarkdownSurface } from "../../shared/ui/MarkdownSurface";
import { BrandMark } from "../shell/BrandMark";
import type { ResearchWorkbenchViewProps } from "./buildResearchWorkbenchProps";
import type { ResearchRunDetail } from "./model";
import { RESEARCH_EXAMPLES, ResearchComposer } from "./ResearchComposer";
import type { ResearchRound } from "./ResearchProcessTimeline";
import { parseReportSources, splitReportSections, splitResearchReport } from "./ResearchReportView";
import { buildResearchTable, orderReportEvidence } from "./researchTable";

export type ResearchRunState = "running" | "completed" | "insufficient" | "failed" | "cancelled";

export const RUN_STATE_LABEL: Record<ResearchRunState, string> = {
  running: "研究中",
  completed: "已完成",
  insufficient: "证据不足",
  failed: "运行失败",
  cancelled: "已取消"
};

export function resolveRunState(run: ResearchRunDetail): ResearchRunState {
  const status = (run.status || "").toUpperCase();
  if (status === "QUEUED" || status === "RUNNING") return "running";
  if (status === "FAILED") return "failed";
  if (status === "CANCELLED") return "cancelled";
  if ((run.completion_terminal_state || "").toUpperCase() === "INSUFFICIENT_EVIDENCE") return "insufficient";
  return "completed";
}

function text(value: unknown) {
  return typeof value === "string" ? value : "";
}

/** 报告标题已经在别处展示，正文开头重复的一级标题去掉。 */
export function stripLeadingTitle(markdown: string) {
  return markdown.replace(/^\s*#\s+[^\n]*\n+/, "");
}

/** 每轮的规划说明存放在 loop_rounds 的原始记录里，字段名随 Worker 版本略有差异。 */
export function buildRounds(run: ResearchRunDetail): ResearchRound[] {
  const rawRounds = run.closed_loop_state?.loop_rounds ?? [];
  const summaries = run.research_process_summary?.search_read_timeline?.rounds ?? [];
  return summaries.map((round) => {
    const raw = rawRounds.find((item) => Number(item.round_no) === round.round_no) ?? {};
    const note = text(raw.plan_note) || text(raw.replan_reason) || text(raw.decision_reason);
    return { ...round, note };
  });
}

type ResearchChatViewProps = {
  run: ResearchRunDetail | null;
  progressMessage: string;
  isBusy: boolean;
  composer: ResearchWorkbenchViewProps["sidebar"];
  onStart: () => void;
  onOpenReport: () => void;
  onExport: () => void;
  onSaveAsSource: () => void;
};

/**
 * 深度研究的对话页，与问答页同一套对话样式：用户的问题、研究计划、可展开的研究过程，
 * 研究完成后报告直接以一条消息出现在对话里（摘要和直接回答），可以跳到完整报告页查看研究表与过程。
 */
export function ResearchChatView({
  run,
  progressMessage,
  isBusy,
  composer,
  onStart,
  onOpenReport,
  onExport,
  onSaveAsSource
}: ResearchChatViewProps) {
  const conversationRef = useRef<HTMLDivElement>(null);
  const state = run ? resolveRunState(run) : null;
  const roundCount = run?.research_process_summary?.search_read_timeline?.rounds?.length ?? 0;
  const hasReport = Boolean(run?.final_report_markdown?.trim());
  const title = run ? run.final_report_title || run.question : "深度研究";

  // 有新的进度或报告时滚到对话底部
  useEffect(() => {
    const element = conversationRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [run?.research_run_id, roundCount, hasReport, progressMessage]);

  return (
    <section className="chat-panel research-chat" aria-label="研究对话">
      <header className="page-header chat-header">
        <div className="page-header-title research-chat-title">
          <h2>{title}</h2>
          {state ? <span className={`research-run-state is-${state}`}><i aria-hidden="true" />{RUN_STATE_LABEL[state]}</span> : null}
        </div>
        {hasReport ? (
          <div className="page-header-actions">
            <button type="button" className="secondary-button" onClick={onOpenReport}>
              <FileText size={15} aria-hidden="true" />完整报告
            </button>
          </div>
        ) : null}
      </header>

      <div className="conversation" ref={conversationRef} aria-live="polite">
        {!run ? (
          <div className="chat-welcome">
            <BrandMark className="chat-welcome-mark" size={40} />
            <h2 className="chat-welcome-title">想深入研究什么问题？</h2>
            <p className="chat-welcome-meta">研究会拆成一张研究表，逐项检索网页和资料、核对原文，最后写成带引用的报告</p>
            {!composer.researchQuestion.trim() ? (
              <div className="chat-example-list" aria-label="示例问题">
                {RESEARCH_EXAMPLES.map((example) => (
                  <button key={example} type="button" className="chat-example-chip" onClick={() => composer.setResearchQuestion(example)}>
                    {example}
                  </button>
                ))}
              </div>
            ) : null}
          </div>
        ) : (
          <div className="message-list">
            <div className="bubble message-row user">
              <div className="message-content"><div className="bubble-body">{run.question}</div></div>
            </div>

            <div className="bubble message-row assistant">
              <BrandMark className={`message-avatar${state === "running" ? " is-thinking" : ""}`} size={26} />
              <div className="message-content">
                <ResearchRunTurn
                  run={run}
                  progressMessage={progressMessage}
                  canSave={!isBusy && !run.saved_report_source && state !== "insufficient"}
                  onOpenReport={onOpenReport}
                  onExport={onExport}
                  onSaveAsSource={onSaveAsSource}
                />
              </div>
            </div>
          </div>
        )}
      </div>

      <ResearchComposer {...composer} isBusy={isBusy} onStart={onStart} />
    </section>
  );
}

type ResearchRunTurnProps = {
  run: ResearchRunDetail;
  progressMessage?: string;
  canSave?: boolean;
  onOpenReport: () => void;
  onExport?: () => void;
  onSaveAsSource?: () => void;
};

/**
 * 一次深度研究在对话里的助手消息：研究计划、可展开的研究过程、进行中的进度，完成后是报告消息。
 * 研究页的对话和问答页里用深度研究发起的轮次都用它渲染。
 */
export function ResearchRunTurn({ run, progressMessage = "", canSave = false, onOpenReport, onExport, onSaveAsSource }: ResearchRunTurnProps) {
  const state = resolveRunState(run);
  const table = useMemo(() => buildResearchTable(run.closed_loop_state), [run.closed_loop_state]);
  const rounds = useMemo(() => buildRounds(run), [run]);
  const hasReport = Boolean(run.final_report_markdown?.trim());

  return (
    <div className="research-chat-answer">
      <p>
        {table.rows.length
          ? `我会围绕 ${table.rows.length} 个研究对象、${table.columns.length} 个方面检索资料，逐项核对原文后再写成报告。`
          : "正在拆解问题，制定研究计划。"}
      </p>
      {table.rows.length ? (
        <div className="research-chat-plan">
          <span>研究对象</span>
          <span className="research-chat-chips">{table.rows.map((row) => <span key={row.id}>{row.title}</span>)}</span>
          {table.columns.length ? <span>研究方面</span> : null}
          {table.columns.length ? (
            <span className="research-chat-chips">{table.columns.map((column) => <span key={column.key}>{column.label}</span>)}</span>
          ) : null}
        </div>
      ) : null}

      {rounds.length ? (
        <details className="research-chat-activity" open={state === "running"}>
          <summary>
            <Search size={14} aria-hidden="true" />
            研究过程 · {rounds.length} 轮检索 · 阅读 {rounds.reduce((total, round) => total + round.read_window_count, 0)} 个网页
          </summary>
          <ol aria-label="研究过程">
            {rounds.map((round) => (
              <li key={round.round_no}>
                <strong>第 {round.round_no} 轮</strong>
                <span>
                  检索 {round.search_queries.length || round.search_hit_count} 次 · 阅读 {round.read_window_count} 个网页 · 摘录 {round.evidence_card_count} 条证据
                </span>
                {round.note ? <small>{round.note}</small> : null}
              </li>
            ))}
          </ol>
        </details>
      ) : null}

      {state === "running" ? (
        <p className="research-chat-progress">
          <span className="chat-typing-dots" aria-hidden="true"><i /><i /><i /></span>
          {progressMessage || "正在检索和核验证据"}
        </p>
      ) : state === "insufficient" ? (
        <p className="research-run-banner is-warning">
          <TriangleAlert size={15} aria-hidden="true" />
          现有证据不足以形成可靠结论，下面只整理了找到的部分。
        </p>
      ) : state === "failed" ? (
        <p className="research-run-banner is-danger">
          <TriangleAlert size={15} aria-hidden="true" />
          研究运行失败，可以在完整报告页的过程中从最近的检查点恢复。
        </p>
      ) : null}

      {hasReport && run ? (
        <ResearchReportMessage
          run={run}
          verified={table.counts.verified}
          total={table.counts.total}
          canSave={canSave}
          saved={Boolean(run.saved_report_source)}
          onOpenReport={onOpenReport}
          onExport={onExport}
          onSaveAsSource={onSaveAsSource}
        />
      ) : null}
    </div>
  );
}

type ResearchReportMessageProps = {
  run: ResearchRunDetail;
  verified: number;
  total: number;
  canSave: boolean;
  saved: boolean;
  onOpenReport: () => void;
  /** 问答页里的研究消息不传，只保留跳到完整报告的入口 */
  onExport?: () => void;
  onSaveAsSource?: () => void;
};

/** 对话里的报告消息：摘要与第一节（直接回答），底部是核验情况和跳到完整报告的入口；点引用角标也打开完整报告。 */
function ResearchReportMessage({ run, verified, total, canSave, saved, onOpenReport, onExport, onSaveAsSource }: ResearchReportMessageProps) {
  const { body, audit } = splitResearchReport(stripLeadingTitle(run.final_report_markdown));
  const { summary, sections } = splitReportSections(body);
  const sources = parseReportSources(audit);
  const evidenceOrder = orderReportEvidence(body);
  const first = sections[0];
  const title = run.final_report_title || run.question;

  return (
    <article className="research-report-message" aria-label={`研究报告：${title}`}>
      <header>
        <span className="research-report-message-kicker"><FileText size={14} aria-hidden="true" />研究报告</span>
        <h3>{title}</h3>
      </header>
      {summary ? (
        <MarkdownSurface content={summary} className="research-report-message-summary" compactCitations evidenceOrder={evidenceOrder}
          onEvidenceClick={onOpenReport} />
      ) : null}
      {first ? (
        <div className="research-report-message-section">
          <h4>{first.heading}</h4>
          <MarkdownSurface content={first.body} className="research-report-message-body" compactCitations
            evidenceOrder={evidenceOrder} onEvidenceClick={onOpenReport} />
        </div>
      ) : null}
      <footer>
        <span className="research-report-message-facts">
          {total ? <span><CircleCheck size={13} aria-hidden="true" />已核验 {verified}/{total} 项</span> : null}
          {total > verified ? <span><CircleDashed size={13} aria-hidden="true" />待补充 {total - verified} 项</span> : null}
          {sources.length ? <span><BookOpen size={13} aria-hidden="true" />{sources.length} 个来源</span> : null}
          {sections.length > 1 ? <span>共 {sections.length} 节</span> : null}
        </span>
        <span className="research-report-message-actions">
          {onExport ? (
            <button type="button" className="icon-button" aria-label="导出报告" title="导出报告" onClick={onExport}>
              <Download size={15} aria-hidden="true" />
            </button>
          ) : null}
          {onSaveAsSource ? (
            <button
              type="button"
              className="icon-button"
              aria-label={saved ? "已存入资料库" : "存入资料库"}
              title={saved ? "已存入资料库" : "存入资料库"}
              onClick={onSaveAsSource}
              disabled={!canSave}
            >
              <FilePlus2 size={15} aria-hidden="true" />
            </button>
          ) : null}
          <button type="button" className="primary-action research-report-message-open" onClick={onOpenReport}>
            查看完整报告<ArrowRight size={15} aria-hidden="true" />
          </button>
        </span>
      </footer>
    </article>
  );
}
