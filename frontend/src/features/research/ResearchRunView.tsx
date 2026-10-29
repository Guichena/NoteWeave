import { useEffect, useMemo, useState } from "react";
import { ArrowLeft, Download, Ellipsis, FilePlus2, LoaderCircle, TriangleAlert } from "lucide-react";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { ResearchRunDetail } from "./model";
import { buildRounds, resolveRunState, RUN_STATE_LABEL, stripLeadingTitle } from "./ResearchChatView";
import { ResearchEvidencePanel, type EvidenceSelection } from "./ResearchEvidencePanel";
import { ResearchProcessTimeline } from "./ResearchProcessTimeline";
import { ResearchReportView } from "./ResearchReportView";
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
  /** 从研究对话跳过来时传入，页头显示返回对话。 */
  onBack?: () => void;
};

/** 完整报告页：报告排成文档，另有研究表与过程两个标签；研究对话在 ResearchChatView。 */
export function ResearchRunView({
  run,
  progressMessage,
  isBusy,
  onExport,
  onSaveAsSource,
  onRefresh,
  onOpenAudit,
  onResume,
  onBack
}: ResearchRunViewProps) {
  const state = resolveRunState(run);
  const hasReport = Boolean(run.final_report_markdown?.trim());
  const table = useMemo(() => buildResearchTable(run.closed_loop_state), [run.closed_loop_state]);
  const rounds = useMemo(() => buildRounds(run), [run]);
  const [tab, setTab] = useState<RunTab>(hasReport ? "report" : "table");
  const [selection, setSelection] = useState<EvidenceSelection | null>(null);
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
          {onBack ? (
            <button type="button" className="research-run-back" onClick={onBack}>
              <ArrowLeft size={15} aria-hidden="true" />返回对话
            </button>
          ) : null}
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
    </article>
  );
}
