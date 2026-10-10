import { lazy, memo, Suspense, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { ResearchWorkbenchViewProps as BuiltResearchWorkbenchViewProps } from "./buildResearchWorkbenchProps";
import { ResearchComposer } from "./ResearchComposer";
import { ResearchRunList } from "./ResearchRunList";
import { ResearchRunView } from "./ResearchRunView";

export type ResearchWorkbenchViewProps = BuiltResearchWorkbenchViewProps;

// 审计详情面向排查问题的场景，按需加载。
const LazyResearchDetailWorkbench = lazy(() => import("./ResearchDetailWorkbench").then((module) => ({
  default: module.ResearchDetailWorkbench
})));

type TaskSnapshot = { result_ref?: string; progress_message?: string } | null;

export const ResearchWorkbenchView = memo(function ResearchWorkbenchView({
  isBusy,
  sidebar,
  report,
  statusPanel,
  detail
}: ResearchWorkbenchViewProps) {
  const [composing, setComposing] = useState(false);
  // 记录发起研究时所在的运行；出现新的运行 ID 说明启动成功，此时离开输入页。
  const launchedFromRef = useRef<string | null>(null);
  const run = report.currentResearchRun;
  const currentRunId = sidebar.currentResearchRunId;
  const showComposer = composing || !run;

  useEffect(() => {
    const launchedFrom = launchedFromRef.current;
    if (launchedFrom === null || !currentRunId || currentRunId === launchedFrom) return;
    launchedFromRef.current = null;
    setComposing(false);
  }, [currentRunId]);

  useEffect(() => {
    if (!detail.researchDetailOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") detail.setResearchDetailOpen(false);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [detail.researchDetailOpen, detail.setResearchDetailOpen]);

  const task = statusPanel.latestResearchTask as TaskSnapshot;
  const progressMessage = run && task?.result_ref === run.research_run_id ? task.progress_message ?? "" : "";

  return (
    <section className={`research-workbench${showComposer ? " is-composing" : ""}`}>
      <ResearchRunList
        runs={sidebar.researchRuns}
        activeRunId={currentRunId}
        composing={showComposer}
        isBusy={isBusy}
        onNewResearch={() => {
          launchedFromRef.current = null;
          setComposing(true);
        }}
        onOpenRun={(summary) => {
          launchedFromRef.current = null;
          setComposing(false);
          void sidebar.openResearchRunHistoryItem(summary);
        }}
      />

      <div className="research-main">
        {showComposer || !run ? (
          <ResearchComposer
            {...sidebar}
            isBusy={isBusy}
            onStart={() => {
              launchedFromRef.current = currentRunId || "";
              void sidebar.startDeepResearch();
            }}
          />
        ) : (
          <ResearchRunView
            run={run}
            progressMessage={progressMessage}
            isBusy={isBusy}
            onExport={() => void report.exportResearchReportMarkdown()}
            onSaveAsSource={() => void report.saveResearchReportAsSource()}
            onRefresh={() => void report.refreshCurrentResearchRun()}
            onOpenAudit={() => detail.setResearchDetailOpen(true)}
            onResume={(checkpointNo) => void detail.resumeResearchFromCheckpoint(checkpointNo)}
          />
        )}
      </div>

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
              <p className="section-label">审计详情</p>
              <div className="view-loading-body">
                <span className="view-loading-spinner" aria-hidden="true" />
                <span>正在加载审计详情…</span>
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
