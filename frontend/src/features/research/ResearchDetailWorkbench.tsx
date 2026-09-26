import { useMemo, useState } from "react";
import type { ResearchCheckpoint, ResearchRunDetail } from "./model";
import { ResearchAuditOverview } from "./ResearchAuditOverview";
import { ResearchCheckpointCurrentDiffPanel } from "./ResearchCheckpointCurrentDiffPanel";
import { ResearchCheckpointEvidenceReviewPanel } from "./ResearchCheckpointEvidenceReviewPanel";
import { ResearchCheckpointProvenancePanel } from "./ResearchCheckpointProvenancePanel";
import { ResearchCheckpointReplayPanel } from "./ResearchCheckpointReplayPanel";
import { ResearchCheckpointSnapshotPanel } from "./ResearchCheckpointSnapshotPanel";
import { ResearchClosedLoopStatePanel } from "./ResearchClosedLoopStatePanel";
import { ResearchProcessOverview } from "./ResearchProcessOverview";
import { ResearchProcessStageLanes } from "./ResearchProcessStageLanes";
import { ResearchProcessTraceBrowser } from "./ResearchProcessTraceBrowser";
import { ResearchRoundTimeline } from "./ResearchRoundTimeline";
import { deriveResearchEvidenceMetrics } from "./derived";
import {
  asRecord,
  asRecords,
  buildResearchRounds,
  buildTraceCards,
  buildTraceDetail,
  buildTraceGroups,
  count,
  evidenceItems,
  stringList,
  text
} from "./researchDetailViewModel";

type WorkbenchTab = "process" | "audit" | "checkpoints";

type ResearchDetailWorkbenchProps = {
  run: ResearchRunDetail;
  selectedCheckpointNo: number | null;
  selectedCheckpoint: ResearchCheckpoint | null;
  comparisonCheckpointNo: number | null;
  comparisonCheckpoint: ResearchCheckpoint | null;
  isBusy: boolean;
  onClose: () => void;
  onRefresh: () => void;
  onSaveAsSource: () => void;
  onOpenWorkbench: () => void;
  onSetSourceScope: (sourceId: string, inScope: boolean) => void;
  onFocusSource: (sourceId: string) => void;
  onSelectCheckpoint: (checkpointNo: number, comparisonNo: number | null) => void;
  onSelectComparison: (checkpointNo: number | null) => void;
  onResume: (checkpointNo: number) => void;
};

export function ResearchDetailWorkbench({
  run,
  selectedCheckpointNo,
  selectedCheckpoint,
  comparisonCheckpointNo,
  comparisonCheckpoint,
  isBusy,
  onClose,
  onRefresh,
  onSaveAsSource,
  onOpenWorkbench,
  onSetSourceScope,
  onFocusSource,
  onSelectCheckpoint,
  onSelectComparison,
  onResume
}: ResearchDetailWorkbenchProps) {
  const [tab, setTab] = useState<WorkbenchTab>("process");
  const [activeRoundNo, setActiveRoundNo] = useState<number | null>(null);
  const [narrativeMode, setNarrativeMode] = useState<"global" | "round">("global");
  const [selectedTraceKey, setSelectedTraceKey] = useState("");
  const process = run.research_process_summary ?? null;
  const timeline = process?.search_read_timeline ?? null;
  const evidenceSummary = process?.source_evidence_summary ?? null;
  const evidenceMetrics = deriveResearchEvidenceMetrics(run);
  const effectiveTimeline = timeline ? {
    ...timeline,
    total_search_hit_count: evidenceMetrics.searchHitCount,
    total_read_window_count: evidenceMetrics.readWindowCount,
    total_evidence_card_count: evidenceMetrics.evidenceCardCount,
    all_search_queries: evidenceMetrics.searchQueries
  } : null;
  const effectiveEvidenceSummary = evidenceSummary ? {
    ...evidenceSummary,
    source_basis: evidenceMetrics.sourceBasis,
    primary_quality: evidenceMetrics.primaryQuality,
    verified_finding_count: evidenceMetrics.verifiedFindingCount,
    citation_count: evidenceMetrics.citationCount
  } : null;
  const audit = process?.audit_summary ?? null;
  const closedLoop = run.closed_loop_state;
  const sourceIds = useMemo(() => new Set(run.source_scope.map((source) => source.source_id)), [run.source_scope]);
  const rounds = buildResearchRounds(timeline, closedLoop.loop_rounds);
  const effectiveRoundNo = activeRoundNo ?? rounds.at(-1)?.roundNo ?? null;

  const traceCards = useMemo(() => buildTraceCards(run.traces, selectedTraceKey, sourceIds), [run.traces, selectedTraceKey, sourceIds]);
  const traceGroups = buildTraceGroups(traceCards, effectiveRoundNo);
  const selectedTrace = traceCards.find((trace) => trace.key === selectedTraceKey) ?? traceCards[0] ?? null;
  const selectedTraceDetail = buildTraceDetail(selectedTrace);

  const ledger = asRecord(closedLoop.state_ledger);
  const recovery = asRecord(closedLoop.recovery_targets);
  const verifierRows = closedLoop.rows.filter((row) => row.row_status !== "VERIFIED");
  const checkpoints = closedLoop.checkpoints;
  const selectedPayload = asRecord(selectedCheckpoint?.payload);
  const selectedSummary = asRecord(selectedCheckpoint?.summary);
  const comparisonSummary = asRecord(comparisonCheckpoint?.summary);
  const selectedFindings = evidenceItems(selectedPayload["verified_findings"], "verified");
  const selectedConflicts = evidenceItems(selectedPayload["conflicted_rows"], "conflict");
  const selectedEvidence = evidenceItems(selectedPayload["evidence_cards"] ?? selectedPayload["evidence_ledger"], "evidence");

  return (
    <div
      className="research-detail-modal research-detail-workbench"
      role="dialog"
      aria-modal="true"
      aria-labelledby="research-detail-title"
      onClick={(event) => event.stopPropagation()}
    >
      <div className="research-detail-header">
        <div>
          <p className="section-label">研究工作台</p>
          <h3 id="research-detail-title">{run.final_report_title || run.question || "Deep Research"}</h3>
          <small>{run.status} · {run.source_scope.length} 个来源 · {checkpoints.length} 个 checkpoint</small>
        </div>
        <button type="button" className="secondary-button" onClick={onClose}>关闭</button>
      </div>
      <div className="research-detail-content research-detail-workbench-content">
        <nav className="research-detail-layer-switch" aria-label="Research 详情视图">
          {([[
            "process", "过程"
          ], ["audit", "审计"], ["checkpoints", "Checkpoint"]] as const).map(([key, label]) => (
            <button key={key} type="button" className={tab === key ? "active" : ""} onClick={() => setTab(key)}>{label}</button>
          ))}
        </nav>

        {tab === "process" ? (
          <div className="research-detail-section-stack">
            <ResearchProcessOverview
              processSummary={process}
              searchReadTimeline={effectiveTimeline}
              sourceEvidenceSummary={effectiveEvidenceSummary}
              searchTimelineSummary={effectiveTimeline?.all_search_queries.join(" / ") || "尚未返回检索 query"}
              searchTimelineRoundLabel={`${effectiveTimeline?.loop_round_count ?? 0} 个研究轮次`}
              fetchTimelineSummary={`${evidenceMetrics.searchHitCount} 个候选入口`}
              fetchTimelineDetail="候选入口会在阅读阶段转为可追溯证据。"
              readTimelineSummary={`${evidenceMetrics.readWindowCount} 个阅读窗口`}
              readTimelineDetail={`${evidenceMetrics.evidenceCardCount} 张证据卡`}
              sourceEvidenceSummaryLead={`${evidenceMetrics.verifiedFindingCount} 条已验证结论`}
              sourceEvidenceSummaryDetail={`${evidenceMetrics.citationCount} 条引用 · ${evidenceMetrics.primaryQuality || "质量待评估"}`}
              visibleSearchTraceCount={traceCards.filter((trace) => trace.kind === "search").length}
              visibleReadTraceCount={traceCards.filter((trace) => trace.kind === "read").length}
              loopRoundCount={rounds.length}
              selectedRoundNo={effectiveRoundNo}
              latestTask={null}
              taskRuntimeSnapshot=""
              taskDeltaNarrative=""
              taskPathAuditNarrative=""
              taskRuntimeFocusNarrative=""
              taskFocusDriftNarrative=""
              taskEventNarratives={[]}
              recoveryEventNarrative=""
              artifactRecoveryNarrative=""
              continuityBaselineLabel={run.resumed_from_research_run_id}
              continuityNarrative={run.resumed_from_checkpoint_no ? `从 checkpoint #${run.resumed_from_checkpoint_no} 恢复` : ""}
              hasFinalReport={Boolean(run.final_report_markdown)}
              isBusy={isBusy}
              hasCurrentRun={Boolean(run.research_run_id)}
              onRefresh={onRefresh}
              onSaveAsSource={onSaveAsSource}
              overviewTitle={run.question}
              overviewNarrative={run.trace_summary || "当前研究过程已按搜索、阅读、验证和写回阶段归档。"}
              overviewSourceNarrative={`${run.source_scope.length} 个资料来源`}
              overviewSearchCount={evidenceMetrics.searchHitCount}
              overviewSearchLabel="搜索命中"
              overviewReadCount={evidenceMetrics.readWindowCount}
              overviewReadLabel="阅读窗口"
              overviewWorkspaceCount={run.saved_report_source ? 1 : 0}
              overviewWorkspaceLabel="报告回流"
              overviewLoopCount={String(effectiveTimeline?.loop_round_count ?? 0)}
              overviewLoopLabel="闭环轮次"
            />
            <ResearchProcessStageLanes
              searchSummary={effectiveTimeline?.all_search_queries.join(" / ") || "尚未返回 query"}
              searchScopeMessage={`${evidenceMetrics.searchHitCount} 个搜索命中`}
              searchAngleMessage={`${effectiveTimeline?.loop_round_count ?? 0} 个轮次`}
              runtimeSnapshot={run.status}
              readSummary={`${evidenceMetrics.readWindowCount} 个阅读窗口`}
              readSourceMessage={`${evidenceMetrics.evidenceCardCount} 张证据卡`}
              readScopeMessage={`${evidenceMetrics.verifiedFindingCount} 条已验证结论`}
              taskRuntimeFocusNarrative=""
              workspaceScopeMessage={run.saved_report_source ? "报告已写回资料池" : "报告尚未写回资料池"}
              savedReportRoundNarrative=""
              savedReportSource={run.saved_report_source ? {
                sourceId: run.saved_report_source.source_id,
                title: run.saved_report_source.title,
                status: run.saved_report_source.status,
                indexStatus: run.saved_report_source.index_status,
                inScope: sourceIds.has(run.saved_report_source.source_id),
                existsInWorkspace: true
              } : null}
              isBusy={isBusy}
              hasWorkspace={Boolean(run.workspace_id)}
              onOpenWorkbench={onOpenWorkbench}
              onSetSourceScope={onSetSourceScope}
              onFocusSource={onFocusSource}
            />
            <ResearchRoundTimeline
              rounds={rounds}
              activeRoundNo={effectiveRoundNo}
              narrativeMode={narrativeMode}
              onSelectRound={setActiveRoundNo}
              onSetNarrativeMode={setNarrativeMode}
              onViewAudit={() => setTab("audit")}
            />
            <ResearchProcessTraceBrowser
              groups={traceGroups}
              selectedDetail={selectedTraceDetail}
              roundFocusMessage={effectiveRoundNo ? `当前轮次焦点：第 ${effectiveRoundNo} 轮` : ""}
              searchTraceCount={traceCards.filter((trace) => trace.kind === "search").length}
              readTraceCount={traceCards.filter((trace) => trace.kind === "read").length}
              workspaceTraceCount={traceCards.filter((trace) => trace.kind === "workspace").length}
              isBusy={isBusy}
              hasWorkspace={Boolean(run.workspace_id)}
              hasCurrentRun={Boolean(run.research_run_id)}
              onSelectTrace={setSelectedTraceKey}
              onOpenWorkbench={onOpenWorkbench}
              onSetSourceScope={onSetSourceScope}
              onFocusSource={onFocusSource}
              onOpenCheckpoint={(checkpointNo) => { onSelectCheckpoint(checkpointNo, comparisonCheckpointNo); setTab("checkpoints"); }}
              onViewAudit={() => setTab("audit")}
            />
          </div>
        ) : null}

        {tab === "audit" ? (
          <div className="research-detail-section-stack">
            <ResearchAuditOverview
              verifierGateLead={`local=${audit?.local_verifier_status || closedLoop.local_verifier_status || "-"} · global=${audit?.global_verifier_decision || closedLoop.global_verifier_decision || "-"}`}
              verifierGateDetail={`final=${audit?.final_loop_decision || closedLoop.final_loop_decision || "-"}`}
              verifierGateMetrics={`${closedLoop.verifier_decision_count} 条 verifier decision`}
              counterfactualLead={audit?.has_counterfactual_recheck ? "已进入反证复核" : "未进入反证复核"}
              counterfactualDetail={`${audit?.counterfactual_branch_count ?? 0} 个反证分支`}
              counterfactualMetrics={`${audit?.conflicted_row_count ?? 0} 个冲突行`}
              checkpointResumeLead={`${checkpoints.length} 个 checkpoint`}
              checkpointResumeDetail={run.resumed_from_checkpoint_no ? `本 run 从 #${run.resumed_from_checkpoint_no} 恢复` : "本 run 从头开始"}
              checkpointResumeMetrics={`${audit?.recovery_target_count ?? 0} 个恢复目标`}
              harnessControlLead={text(asRecord(closedLoop.harness_summary)["status"], "控制状态已记录")}
              harnessControlDetail={text(asRecord(closedLoop.harness_summary)["reason"], "可在原始审计数据中查看")}
              harnessControlMetrics={`${closedLoop.branch_count} 个 branch`}
              formalAuditSummary={audit ? {
                localVerifierStatus: audit.local_verifier_status,
                globalVerifierDecision: audit.global_verifier_decision,
                finalLoopDecision: audit.final_loop_decision,
                checkpointCount: audit.checkpoint_count,
                counterfactualBranchCount: audit.counterfactual_branch_count,
                recoveryTargetCount: audit.recovery_target_count,
                blockedRowCount: audit.blocked_row_count,
                conflictedRowCount: audit.conflicted_row_count,
                guardrailedRowCount: audit.guardrailed_row_count,
                hasCounterfactualRecheck: audit.has_counterfactual_recheck
              } : null}
            />
            <ResearchClosedLoopStatePanel
              state={{
                activeBranchId: closedLoop.active_branch_id,
                finalLoopDecision: closedLoop.final_loop_decision,
                ledgerRowCount: closedLoop.ledger_row_count,
                branchCount: closedLoop.branch_count,
                verifierDecisionCount: closedLoop.verifier_decision_count,
                checkpointCount: checkpoints.length,
                sourceEvidenceCount: closedLoop.source_evidence.length,
                requirementSummary: `${count(ledger["verified_row_count"])} 个已验证行`,
                requirementRowSummary: `${count(ledger["conflicted_row_count"])} 个冲突行`,
                recoveryTargets: stringList(recovery["requirement_ids"]).join(" / "),
                recoveryNarrative: text(recovery["reason"], ""),
                targetQueries: stringList(recovery["target_queries"]).join(" / "),
                missingRequirements: stringList(ledger["missing_requirement_labels"]).join(" / ")
              }}
              verifierGatedRows={{
                summary: `${verifierRows.length} 个待处理行`,
                statusSummary: `verified=${count(ledger["verified_row_count"])} · conflicted=${count(ledger["conflicted_row_count"])}`,
                narrative: "仅展示尚未通过 verifier 的 ledger rows。",
                recoveryCoverage: stringList(recovery["requirement_ids"]).join(" / "),
                missingRequirements: stringList(ledger["missing_requirement_labels"]).join(" / "),
                rows: verifierRows.map((row, index) => ({
                  key: row.row_id || `row-${index}`,
                  title: row.source_title || row.row_id || `Row ${index + 1}`,
                  status: row.row_status || "UNKNOWN",
                  claim: row.claim_text || "暂无候选值",
                  focus: row.read_focus || row.search_query || "",
                  requirementSummary: (row.matched_requirement_ids ?? []).join(" / "),
                  missingColumns: (row.missing_columns ?? []).join(" / "),
                  verifierSummary: row.verifier_note || row.verification_status || "",
                  targetSummary: row.repair_hint || ""
                }))
              }}
            />
          </div>
        ) : null}

        {tab === "checkpoints" ? (
          <div className="research-detail-section-stack">
            <ResearchCheckpointReplayPanel
              checkpoints={checkpoints.map((checkpoint) => ({
                checkpointNo: checkpoint.checkpoint_no,
                snapshotType: checkpoint.snapshot_type,
                branchLoop: `${checkpoint.active_branch_id || "-"} · ${checkpoint.final_loop_decision || "-"}`,
                verifierSummary: `local=${checkpoint.local_verifier_status || "-"} · global=${checkpoint.global_verifier_decision || "-"}`,
                evidenceSummary: `verified=${checkpoint.verified_row_count ?? 0} · conflicted=${checkpoint.conflicted_row_count ?? 0}`,
                intentSummary: checkpoint.research_intent_alignment_status || "",
                requirementSummary: `${checkpoint.intent_satisfied_requirement_count ?? 0}/${checkpoint.intent_requirement_count ?? 0} requirements`,
                summarySource: checkpoint.object_key,
                recoveryTargets: stringList(asRecord(checkpoint.recovery_targets)["requirement_ids"]).join(" / "),
                recoveryNarrative: "",
                traceNarrative: `创建于 ${checkpoint.created_at}`,
                continuityFocus: "",
                continuityNarrative: "",
                roundDeltaNarrative: "",
                signalChips: []
              }))}
              isBusy={isBusy}
              onReplay={(checkpointNo) => onSelectCheckpoint(checkpointNo, comparisonCheckpointNo)}
              onResume={onResume}
            />
            {selectedCheckpoint ? (
              <>
                <div className="task-card">
                  <strong>Checkpoint #{selectedCheckpoint.checkpoint_no}</strong>
                  <ResearchCheckpointSnapshotPanel
                    replayLabel={`${selectedCheckpoint.snapshot_type} · ${selectedCheckpoint.final_loop_decision || "-"}`}
                    comparisonValue={comparisonCheckpointNo}
                    comparisonOptions={checkpoints.filter((checkpoint) => checkpoint.checkpoint_no !== selectedCheckpoint.checkpoint_no).map((checkpoint) => ({ checkpointNo: checkpoint.checkpoint_no, label: `#${checkpoint.checkpoint_no} · ${checkpoint.snapshot_type}` }))}
                    snapshotCards={[
                      { title: "Ledger", lines: [`verified=${selectedCheckpoint.verified_row_count ?? 0}`, `conflicted=${selectedCheckpoint.conflicted_row_count ?? 0}`] },
                      { title: "Verifier", lines: [`local=${selectedCheckpoint.local_verifier_status || "-"}`, `global=${selectedCheckpoint.global_verifier_decision || "-"}`] },
                      { title: "Loop", lines: [selectedCheckpoint.final_loop_decision || "-", selectedCheckpoint.active_branch_id || "-"] }
                    ]}
                    isBusy={isBusy}
                    onChangeComparison={(value) => onSelectComparison(value ? Number(value) : null)}
                  />
                </div>
                <ResearchCheckpointProvenancePanel
                  cards={[
                    { key: "object", title: "Snapshot object", lines: [selectedCheckpoint.object_key, selectedCheckpoint.payload_sha256] },
                    { key: "source", title: "Run provenance", lines: [run.research_run_id, `${run.source_scope.length} 个来源`] }
                  ]}
                  onFocusAudit={() => setTab("audit")}
                />
                <ResearchCheckpointEvidenceReviewPanel
                  verifiedFindings={selectedFindings}
                  conflictedRows={selectedConflicts}
                  branchDecisions={asRecords(selectedPayload["branch_decisions"]).map((item) => text(item["reason"] ?? item["decision"]))}
                  recoveryLines={stringList(asRecord(selectedPayload["recovery_status"])["notes"])}
                  nextActions={stringList(selectedPayload["next_actions"])}
                  evidenceCards={selectedEvidence}
                />
                <ResearchCheckpointCurrentDiffPanel
                  summaryCards={[
                    { title: "Checkpoint", lines: [`verified=${selectedCheckpoint.verified_row_count ?? 0}`, `conflicted=${selectedCheckpoint.conflicted_row_count ?? 0}`] },
                    { title: "Current", lines: [`verified=${count(ledger["verified_row_count"])}`, `conflicted=${count(ledger["conflicted_row_count"])}`] }
                  ]}
                  progressCards={comparisonCheckpoint ? [{
                    title: `Comparison #${comparisonCheckpoint.checkpoint_no}`,
                    lines: [text(comparisonSummary["snapshot_type"], comparisonCheckpoint.snapshot_type), comparisonCheckpoint.final_loop_decision || "-"]
                  }] : []}
                  newFindings={evidenceItems(selectedPayload["new_verified_findings"], "new")}
                  newFindingsSourceNarrative={text(selectedSummary["source_foundation"], "")}
                />
              </>
            ) : <div className="task-card"><span>选择一个 checkpoint 后可查看快照、来源、证据和当前差异。</span></div>}
          </div>
        ) : null}
      </div>
    </div>
  );
}
