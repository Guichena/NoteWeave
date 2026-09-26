import {
  buildWaitContextDetailLines,
  buildWaitContextNarrative,
  buildWaitContextSignalChips,
  summarizeRunStatus
} from "../../runStatus";
import { type ExecutionTask } from "../executions/model";
import {
  type ResearchProcessSummary,
  type ResearchSearchReadTimeline,
  type ResearchSourceEvidenceSummary
} from "./model";

type ResearchProcessOverviewProps = {
  processSummary: ResearchProcessSummary | null;
  searchReadTimeline: ResearchSearchReadTimeline | null;
  sourceEvidenceSummary: ResearchSourceEvidenceSummary | null;
  searchTimelineSummary: string;
  searchTimelineRoundLabel: string;
  fetchTimelineSummary: string;
  fetchTimelineDetail: string;
  readTimelineSummary: string;
  readTimelineDetail: string;
  sourceEvidenceSummaryLead: string;
  sourceEvidenceSummaryDetail: string;
  visibleSearchTraceCount: number;
  visibleReadTraceCount: number;
  loopRoundCount: number;
  selectedRoundNo: number | null;
  latestTask: ExecutionTask | null;
  taskRuntimeSnapshot: string;
  taskDeltaNarrative: string;
  taskPathAuditNarrative: string;
  taskRuntimeFocusNarrative: string;
  taskFocusDriftNarrative: string;
  taskEventNarratives: string[];
  recoveryEventNarrative: string;
  artifactRecoveryNarrative: string;
  continuityBaselineLabel: string;
  continuityNarrative: string;
  hasFinalReport: boolean;
  isBusy: boolean;
  hasCurrentRun: boolean;
  onRefresh: () => void;
  onSaveAsSource: () => void;
  overviewTitle: string;
  overviewNarrative: string;
  overviewSourceNarrative: string;
  overviewSearchCount: number;
  overviewSearchLabel: string;
  overviewReadCount: number;
  overviewReadLabel: string;
  overviewWorkspaceCount: number;
  overviewWorkspaceLabel: string;
  overviewLoopCount: string;
  overviewLoopLabel: string;
};

export function ResearchProcessOverview({
  processSummary,
  searchReadTimeline,
  sourceEvidenceSummary,
  searchTimelineSummary,
  searchTimelineRoundLabel,
  fetchTimelineSummary,
  fetchTimelineDetail,
  readTimelineSummary,
  readTimelineDetail,
  sourceEvidenceSummaryLead,
  sourceEvidenceSummaryDetail,
  visibleSearchTraceCount,
  visibleReadTraceCount,
  loopRoundCount,
  selectedRoundNo,
  latestTask,
  taskRuntimeSnapshot,
  taskDeltaNarrative,
  taskPathAuditNarrative,
  taskRuntimeFocusNarrative,
  taskFocusDriftNarrative,
  taskEventNarratives,
  recoveryEventNarrative,
  artifactRecoveryNarrative,
  continuityBaselineLabel,
  continuityNarrative,
  hasFinalReport,
  isBusy,
  hasCurrentRun,
  onRefresh,
  onSaveAsSource,
  overviewTitle,
  overviewNarrative,
  overviewSourceNarrative,
  overviewSearchCount,
  overviewSearchLabel,
  overviewReadCount,
  overviewReadLabel,
  overviewWorkspaceCount,
  overviewWorkspaceLabel,
  overviewLoopCount,
  overviewLoopLabel
}: ResearchProcessOverviewProps) {
  return (
    <>
      <p className="section-label">研究流程</p>
      {processSummary ? (
        <div className="task-card">
          <strong>Research Process</strong>
          <span>
            final loop={searchReadTimeline?.final_loop_decision || "-"} ·
            reason={searchReadTimeline?.final_loop_reason || "-"}
          </span>
          <span>
            disposition={searchReadTimeline?.terminal_disposition || "-"} ·
            handoff={searchReadTimeline?.handoff_required ? "required" : "no"}
            {searchReadTimeline?.abandon_reason ? ` · abandon=${searchReadTimeline.abandon_reason}` : ""}
          </span>
          <span>
            source basis={sourceEvidenceSummary?.source_basis || "-"} ·
            quality={sourceEvidenceSummary?.primary_quality || "-"}
          </span>
          <small>
            search hits={searchReadTimeline?.total_search_hit_count ?? 0} ·
            read windows={searchReadTimeline?.total_read_window_count ?? 0} ·
            evidence cards={searchReadTimeline?.total_evidence_card_count ?? 0}
          </small>
          <small>
            verified findings={sourceEvidenceSummary?.verified_finding_count ?? 0} ·
            citations={sourceEvidenceSummary?.citation_count ?? 0} ·
            source_scope={processSummary.source_scope_count}
          </small>
          {searchReadTimeline?.all_search_queries.length ? (
            <small>queries={searchReadTimeline.all_search_queries.join(" / ")}</small>
          ) : (
            <small>queries=当前未返回正式 query samples，视图会继续回退到 trace 推导。</small>
          )}
          <small>Research Process 这一层优先按 search → fetch → read → source evidence 来看清本次调研过程，下面的轮次回看和网页轨迹则继续保留为细节层。</small>
        </div>
      ) : null}
      <div className="checkpoint-structured-grid process-lane-grid">
        <div className="link-card process-lane-card tone-search">
          <small className="process-lane-badge">Search Timeline</small>
          <strong>搜了什么</strong>
          <span>{searchTimelineSummary}</span>
          <small>
            search hits={searchReadTimeline?.total_search_hit_count ?? visibleSearchTraceCount} ·
            rounds={searchReadTimeline?.loop_round_count ?? loopRoundCount}
          </small>
          <small>{searchTimelineRoundLabel}</small>
        </div>
        <div className="link-card process-lane-card tone-neutral">
          <small className="process-lane-badge">Fetch Timeline</small>
          <strong>抓到了什么</strong>
          <span>{fetchTimelineSummary}</span>
          <small>{fetchTimelineDetail}</small>
          <small>
            read windows={searchReadTimeline?.total_read_window_count ?? visibleReadTraceCount} ·
            evidence cards={searchReadTimeline?.total_evidence_card_count ?? 0}
          </small>
        </div>
        <div className="link-card process-lane-card tone-read">
          <small className="process-lane-badge">Read Timeline</small>
          <strong>读了什么</strong>
          <span>{readTimelineSummary}</span>
          <small>{readTimelineDetail}</small>
          <small>
            read windows={searchReadTimeline?.total_read_window_count ?? visibleReadTraceCount} ·
            current round={selectedRoundNo ?? searchReadTimeline?.loop_round_count ?? 0}
          </small>
        </div>
        <div className="link-card process-lane-card tone-workspace">
          <small className="process-lane-badge">Source Evidence Summary</small>
          <strong>最后证据基础是什么</strong>
          <span>{sourceEvidenceSummaryLead}</span>
          <small>{sourceEvidenceSummaryDetail}</small>
          <small>
            fetch={sourceEvidenceSummary?.fetch_foundation_label || "-"} ·
            quality={sourceEvidenceSummary?.primary_quality || "-"}
          </small>
        </div>
      </div>
      {latestTask ? (
        <div className="task-card">
          <strong>研究正在如何推进</strong>
          <span>当前阶段：{latestTask.progress_phase} · 任务状态：{summarizeRunStatus(latestTask.task_status)}</span>
          <small>任务类型：{latestTask.task_type}</small>
          <span>{latestTask.progress_message}</span>
          {buildWaitContextNarrative(latestTask.wait_context) ? <small>{buildWaitContextNarrative(latestTask.wait_context)}</small> : null}
          {buildWaitContextSignalChips(latestTask.wait_context).length > 0 ? (
            <div className="signal-chip-row artifact-wait-signal-row">
              {buildWaitContextSignalChips(latestTask.wait_context).map((chip, index) => (
                <span key={`research-process-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>
                  {chip.label}: {chip.value}
                </span>
              ))}
            </div>
          ) : null}
          {buildWaitContextDetailLines(latestTask.wait_context).length > 0 ? (
            <div className="artifact-runtime-trace">
              {buildWaitContextDetailLines(latestTask.wait_context).map((line, index) => (
                <small key={`research-process-wait-detail-${index}`} className="artifact-runtime-trace-line">
                  <strong>{line.label}</strong> · {line.value}
                </small>
              ))}
            </div>
          ) : null}
          {taskRuntimeSnapshot ? <small>{taskRuntimeSnapshot}</small> : null}
          {taskDeltaNarrative ? <small>{taskDeltaNarrative}</small> : null}
          {taskPathAuditNarrative ? <small>{taskPathAuditNarrative}</small> : null}
          {taskRuntimeFocusNarrative ? <small>{taskRuntimeFocusNarrative}</small> : null}
          {taskFocusDriftNarrative ? <small>{taskFocusDriftNarrative}</small> : null}
          {taskEventNarratives.map((narrative, index) => <small key={`research-side-event-${index}`}>{narrative}</small>)}
          {recoveryEventNarrative ? <small>{recoveryEventNarrative}</small> : null}
          {artifactRecoveryNarrative ? <small>{artifactRecoveryNarrative}</small> : null}
          {continuityBaselineLabel ? <small>continuity baseline={continuityBaselineLabel}</small> : null}
          {continuityNarrative ? <small>{continuityNarrative}</small> : null}
          <div className="research-inline-actions">
            <button className="secondary-button" onClick={onRefresh} disabled={isBusy || !hasCurrentRun}>刷新运行状态</button>
            <button className="secondary-button" onClick={onSaveAsSource} disabled={isBusy || !hasFinalReport}>保存为资料</button>
          </div>
        </div>
      ) : null}
      <div className="research-process-overview">
        <div className="research-process-overview-card">
          <small className="process-lane-badge">研究总览</small>
          <strong>{overviewTitle}</strong>
          <span>{overviewNarrative}</span>
          <small>{overviewSourceNarrative}</small>
        </div>
        <div className="research-process-metrics">
          <div className="research-process-metric tone-search"><small>搜索推进</small><strong>{overviewSearchCount}</strong><span>{overviewSearchLabel}</span></div>
          <div className="research-process-metric tone-read"><small>阅读推进</small><strong>{overviewReadCount}</strong><span>{overviewReadLabel}</span></div>
          <div className="research-process-metric tone-workspace"><small>资料回流</small><strong>{overviewWorkspaceCount}</strong><span>{overviewWorkspaceLabel}</span></div>
          <div className="research-process-metric tone-neutral"><small>闭环推进</small><strong>{overviewLoopCount}</strong><span>{overviewLoopLabel}</span></div>
        </div>
      </div>
    </>
  );
}
