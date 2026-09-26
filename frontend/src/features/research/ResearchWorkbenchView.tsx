import { lazy, memo, Suspense, useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { Activity, X } from "lucide-react";
import { summarizeRunStatus } from "../../runStatus";
import type { ResearchWorkbenchViewProps as BuiltResearchWorkbenchViewProps } from "./buildResearchWorkbenchProps";

export type ResearchWorkbenchViewProps = BuiltResearchWorkbenchViewProps;

const LazyResearchSidebar = lazy(() => import("./ResearchSidebar").then((module) => ({
  default: module.ResearchSidebar
})));
const LazyResearchReportPanel = lazy(() => import("./ResearchReportPanel").then((module) => ({
  default: module.ResearchReportPanel
})));
const LazyResearchDetailWorkbench = lazy(() => import("./ResearchDetailWorkbench").then((module) => ({
  default: module.ResearchDetailWorkbench
})));

export const ResearchWorkbenchView = memo(function ResearchWorkbenchView({
  isBusy,
  sidebar,
  report,
  statusPanel,
  detail
}: ResearchWorkbenchViewProps) {
  const [statusOpen, setStatusOpen] = useState(false);
  const {
    currentResearchRunSummary,
    currentResearchWaitContext,
    currentResearchWaitSignals,
    currentResearchWaitDetails,
    latestResearchTask,
    latestResearchTaskWaitSignals,
    latestResearchTaskWaitDetails,
    latestResearchProgressEvent,
    currentResearchRun,
    buildWaitContextNarrative,
    buildResearchTaskRuntimeSnapshot,
    setResearchDetailOpen
  } = statusPanel;

  const task = latestResearchTask as {
    task_id?: string;
    task_status?: string;
    progress_phase?: string;
    progress_message?: string;
    wait_context?: unknown;
  } | null;

  useEffect(() => {
    if (!detail.researchDetailOpen && !statusOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        if (detail.researchDetailOpen) detail.setResearchDetailOpen(false);
        else setStatusOpen(false);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [detail.researchDetailOpen, detail.setResearchDetailOpen, statusOpen]);

  return (
    <section className={`research-workbench${currentResearchRun ? "" : " is-empty"}${statusOpen ? " status-open" : ""}`}>
      <Suspense fallback={(
        <aside className="research-index research-sidebar-loading">
          <p className="section-label">Deep Research</p>
          <span>正在加载独立研究控制台…</span>
        </aside>
      )}>
        <LazyResearchSidebar {...sidebar} isBusy={isBusy} />
      </Suspense>

      <Suspense fallback={(
        <article className="research-page research-report-loading">
          <p className="section-label">Research Run</p>
          <span>正在加载研究主报告…</span>
        </article>
      )}>
        <LazyResearchReportPanel {...report} isBusy={isBusy} />
      </Suspense>

      {currentResearchRun ? (
        <button
          type="button"
          className="research-status-toggle secondary-button"
          aria-label="打开研究运行状态"
          aria-controls="research-status-panel"
          aria-expanded={statusOpen}
          title="打开研究运行状态"
          onClick={() => setStatusOpen(true)}
        >
          <Activity size={18} aria-hidden="true" />
        </button>
      ) : null}
      {statusOpen ? (
        <button
          type="button"
          className="research-status-backdrop"
          aria-label="点击背景关闭研究运行状态"
          onClick={() => setStatusOpen(false)}
        />
      ) : null}
      <aside className="research-side" id="research-status-panel" aria-label="研究运行状态">
        <p className="section-label">Research Detail</p>
        <button
          type="button"
          className="research-status-close secondary-button"
          aria-label="关闭研究运行状态"
          title="关闭研究运行状态"
          onClick={() => setStatusOpen(false)}
        >
          <X size={17} aria-hidden="true" />
        </button>
        <div className="task-card">
          <strong>研究详情</strong>
          <span>主界面只保留研究主流程；checkpoint、verifier、trace、counterfactual 等高级信息统一放到详情弹窗。</span>
          {currentResearchRunSummary ? (
            <>
              <small>
                {currentResearchRunSummary.status}
                {" · "}
                {currentResearchRunSummary.profile_key || "DEFAULT"}
                {" · checkpoints="}
                {currentResearchRunSummary.checkpoint_count}
              </small>
              <small>
                rows={currentResearchRunSummary.ledger_row_count}
                {" · verified="}
                {currentResearchRunSummary.verified_row_count}
                {" · conflicted="}
                {currentResearchRunSummary.conflicted_row_count}
              </small>
              {buildWaitContextNarrative(currentResearchWaitContext as any) ? (
                <small>{buildWaitContextNarrative(currentResearchWaitContext as any)}</small>
              ) : null}
              {currentResearchWaitSignals.length > 0 ? (
                <div className="signal-chip-row artifact-wait-signal-row">
                  {currentResearchWaitSignals.map((chip, index) => (
                    <span key={`current-research-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>
                      {chip.label}: {chip.value}
                    </span>
                  ))}
                </div>
              ) : null}
              {currentResearchWaitDetails.length > 0 ? (
                <div className="artifact-runtime-trace">
                  {currentResearchWaitDetails.map((line, index) => (
                    <small key={`current-research-wait-detail-${index}`} className="artifact-runtime-trace-line">
                      <strong>{line.label}</strong> · {line.value}
                    </small>
                  ))}
                </div>
              ) : null}
            </>
          ) : (
            <small>选择一个 run 后可查看完整研究详情。</small>
          )}
          <div className="research-inline-actions">
            <button
              className="secondary-button"
              type="button"
              onClick={() => setResearchDetailOpen(true)}
              disabled={!currentResearchRun}
            >
              打开研究详情
            </button>
          </div>
        </div>
        {task ? (
          <div className="task-card research-task-card" data-task-id={task.task_id || undefined}>
            <strong>当前任务进度</strong>
            <span>
              {summarizeRunStatus(task.task_status || "")}
              {" · "}
              {task.progress_phase}
            </span>
            <small>{task.progress_message}</small>
            {buildWaitContextNarrative(task.wait_context as any) ? (
              <small>{buildWaitContextNarrative(task.wait_context as any)}</small>
            ) : null}
            {latestResearchTaskWaitSignals.length > 0 ? (
              <div className="signal-chip-row artifact-wait-signal-row">
                {latestResearchTaskWaitSignals.map((chip, index) => (
                  <span key={`latest-research-task-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>
                    {chip.label}: {chip.value}
                  </span>
                ))}
              </div>
            ) : null}
            {latestResearchTaskWaitDetails.length > 0 ? (
              <div className="artifact-runtime-trace">
                {latestResearchTaskWaitDetails.map((line, index) => (
                  <small key={`latest-research-task-wait-detail-${index}`} className="artifact-runtime-trace-line">
                    <strong>{line.label}</strong> · {line.value}
                  </small>
                ))}
              </div>
            ) : null}
            {buildResearchTaskRuntimeSnapshot(
              latestResearchProgressEvent as any,
              latestResearchTask as any
            ) ? (
              <small>
                {buildResearchTaskRuntimeSnapshot(
                  latestResearchProgressEvent as any,
                  latestResearchTask as any
                )}
              </small>
            ) : null}
          </div>
        ) : null}
      </aside>

      {detail.researchDetailOpen && detail.currentResearchRun ? createPortal((
        <div
          className="research-detail-overlay"
          role="presentation"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) {
              detail.setResearchDetailOpen(false);
            }
          }}
        >
          <Suspense fallback={(
            <aside className="research-detail-dialog research-detail-loading" aria-busy="true" aria-live="polite">
              <p className="section-label">Research Detail</p>
              <div className="view-loading-body">
                <span className="view-loading-spinner" aria-hidden="true" />
                <span>正在加载研究详情工作台…</span>
              </div>
              <div className="view-loading-skeleton" aria-hidden="true">
                <span className="skeleton-line w-70" />
                <span className="skeleton-line w-55" />
                <span className="skeleton-line w-40" />
              </div>
            </aside>
          )}>
            <LazyResearchDetailWorkbench
              run={detail.currentResearchRun}
              selectedCheckpointNo={detail.selectedResearchCheckpointNo}
              selectedCheckpoint={detail.selectedResearchCheckpoint}
              comparisonCheckpointNo={detail.compareResearchCheckpointNo}
              comparisonCheckpoint={detail.compareResearchCheckpoint}
              isBusy={isBusy}
              onClose={() => detail.setResearchDetailOpen(false)}
              onRefresh={() => void detail.refreshCurrentResearchRun()}
              onSaveAsSource={() => void detail.saveResearchReportAsSource()}
              onOpenWorkbench={() => void detail.openResearchWorkbench()}
              onSetSourceScope={(sourceId, inScope) => {
                if (inScope) {
                  detail.addResearchSourceToScope(sourceId);
                } else {
                  detail.removeResearchSourceFromScope(sourceId);
                }
              }}
              onFocusSource={detail.setFocusedResearchSourceId}
              onSelectCheckpoint={(checkpointNo, comparisonNo) => {
                void detail.openResearchCheckpoint(checkpointNo, comparisonNo);
              }}
              onSelectComparison={(checkpointNo) => {
                void detail.updateResearchCheckpointComparison(checkpointNo);
              }}
              onResume={(checkpointNo) => {
                void detail.resumeResearchFromCheckpoint(checkpointNo);
              }}
            />
          </Suspense>
        </div>
      ), document.body) : null}
    </section>
  );
});
