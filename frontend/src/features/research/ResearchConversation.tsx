import { ArrowUpRight, CircleCheck, FileText, LoaderCircle, Search, Sparkles, TriangleAlert } from "lucide-react";
import type { ResearchRound } from "./ResearchProcessTimeline";
import type { ResearchTable } from "./researchTable";

export type ResearchConversationState = "running" | "completed" | "insufficient" | "failed" | "cancelled";

type ResearchConversationProps = {
  question: string;
  state: ResearchConversationState;
  table: ResearchTable;
  rounds: ResearchRound[];
  progressMessage: string;
  reportTitle: string;
  reportSummary: string;
  sourceCount: number;
  reportOpen: boolean;
  onOpenReport: () => void;
};

const MAX_CHIPS = 6;

function chips(labels: string[]) {
  const shown = labels.slice(0, MAX_CHIPS);
  return (
    <span className="research-thread-chips">
      {shown.map((label) => <span key={label}>{label}</span>)}
      {labels.length > shown.length ? <span className="is-more">另 {labels.length - shown.length} 项</span> : null}
    </span>
  );
}

/**
 * 研究过程按对话呈现：用户的问题、研究计划、每轮检索与核验，最后给出一张报告卡片，
 * 报告本身在右侧以文档形式展开（与 Gemini、ChatGPT 的 Deep Research 相同的交互）。
 */
export function ResearchConversation({
  question,
  state,
  table,
  rounds,
  progressMessage,
  reportTitle,
  reportSummary,
  sourceCount,
  reportOpen,
  onOpenReport
}: ResearchConversationProps) {
  const verified = table.counts.verified;
  const total = table.counts.total;
  const hasReport = Boolean(reportTitle);

  return (
    <section className="research-thread" aria-label="研究对话">
      <div className="research-thread-message is-user">
        <p>{question}</p>
      </div>

      <div className="research-thread-message is-assistant">
        <span className="research-thread-avatar" aria-hidden="true"><Sparkles size={14} /></span>
        <div className="research-thread-content">
          <p>
            {table.rows.length
              ? `我会围绕 ${table.rows.length} 个研究对象、${table.columns.length} 个方面检索资料，逐项核对原文后再写成报告。`
              : "正在拆解问题，制定研究计划。"}
          </p>
          {table.rows.length ? (
            <div className="research-thread-plan">
              <span>研究对象</span>
              {chips(table.rows.map((row) => row.title))}
              {table.columns.length ? <span>研究方面</span> : null}
              {table.columns.length ? chips(table.columns.map((column) => column.label)) : null}
            </div>
          ) : null}
        </div>
      </div>

      {rounds.length ? (
        <ol className="research-thread-steps" aria-label="研究过程">
          {rounds.map((round) => (
            <li key={round.round_no}>
              <span className="research-thread-step-icon" aria-hidden="true"><Search size={13} /></span>
              <div>
                <strong>第 {round.round_no} 轮</strong>
                <span>
                  检索 {round.search_queries.length || round.search_hit_count} 次 · 阅读 {round.read_window_count} 个网页 · 摘录 {round.evidence_card_count} 条证据
                </span>
                {round.note ? <small>{round.note}</small> : null}
              </div>
            </li>
          ))}
        </ol>
      ) : null}

      {state === "running" ? (
        <p className="research-run-banner is-running">
          <LoaderCircle size={15} className="is-spinning" aria-hidden="true" />
          {progressMessage || "正在检索和核验证据，研究表会随每轮结果更新。"}
        </p>
      ) : state === "insufficient" ? (
        <p className="research-run-banner is-warning">
          <TriangleAlert size={15} aria-hidden="true" />
          现有证据不足以形成可靠结论。可以在研究表中查看缺口，补充资料后重新研究。
        </p>
      ) : state === "failed" ? (
        <p className="research-run-banner is-danger">
          <TriangleAlert size={15} aria-hidden="true" />
          研究运行失败，可以从最近的检查点恢复，或在审计详情中查看原因。
        </p>
      ) : null}

      {hasReport ? (
        <div className="research-thread-message is-assistant">
          <span className="research-thread-avatar" aria-hidden="true"><Sparkles size={14} /></span>
          <div className="research-thread-content">
            <p className="research-thread-done">
              <CircleCheck size={14} aria-hidden="true" />
              {state === "insufficient" ? "研究结束，已把找到的部分整理成报告。" : "研究完成，报告已整理好。"}
            </p>
            <button
              type="button"
              className={`research-thread-doc${reportOpen ? " is-open" : ""}`}
              onClick={onOpenReport}
              aria-label={`打开报告：${reportTitle}`}
            >
              <span className="research-thread-doc-icon" aria-hidden="true"><FileText size={18} /></span>
              <span className="research-thread-doc-main">
                <strong>{reportTitle}</strong>
                {reportSummary ? <small>{reportSummary}</small> : null}
                <span className="research-thread-doc-meta">
                  {total ? <span>已核验 {verified}/{total} 项</span> : null}
                  {sourceCount ? <span>{sourceCount} 个来源</span> : null}
                </span>
              </span>
              <ArrowUpRight size={15} aria-hidden="true" />
            </button>
          </div>
        </div>
      ) : null}
    </section>
  );
}
