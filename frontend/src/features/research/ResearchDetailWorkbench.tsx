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

type EvidenceItem = { key: string; title: string; claim: string; meta: string };

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function asRecords(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.map(asRecord).filter((item) => Object.keys(item).length > 0) : [];
}

function text(value: unknown, fallback = "-") {
  const normalized = value == null ? "" : String(value).trim();
  return normalized || fallback;
}

function count(value: unknown) {
  return typeof value === "number" ? value : Number(value) || 0;
}

function stringList(value: unknown) {
  return Array.isArray(value) ? value.map((item) => text(item, "")).filter(Boolean) : [];
}

function evidenceItems(value: unknown, prefix: string): EvidenceItem[] {
  return asRecords(value).map((item, index) => ({
    key: text(item["evidence_id"] ?? item["row_id"] ?? item["id"], `${prefix}-${index}`),
    title: text(item["source_title"] ?? item["title"] ?? item["row_id"], `记录 ${index + 1}`),
    claim: text(item["claim_text"] ?? item["claim"] ?? item["candidate_value"], "暂无摘要"),
    meta: [item["status"], item["relation_type"], item["source_id"]].map((item) => text(item, "")).filter(Boolean).join(" · ")
  }));
}

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
  const audit = process?.audit_summary ?? null;
  const closedLoop = run.closed_loop_state;
  const sourceIds = new Set(run.source_scope.map((source) => source.source_id));
  const rounds = (timeline?.rounds ?? []).map((round) => ({
    roundNo: round.round_no,
    branchId: text(closedLoop.loop_rounds.find((item) => count(item["round_no"]) === round.round_no)?.["branch_id"], ""),
    decision: round.global_decision || round.branch_decision || "推进中",
    reason: text(closedLoop.loop_rounds.find((item) => count(item["round_no"]) === round.round_no)?.["reason"], "未返回原因"),
    searchHitCount: String(round.search_hit_count),
    readWindowCount: String(round.read_window_count),
    evidenceCardCount: String(round.evidence_card_count),
    sourceNarrative: round.search_queries.join(" / "),
    deltaNarrative: `本轮新增 ${round.evidence_ids.length} 条证据引用。`,
    outcomeNarrative: round.global_decision ? `本轮结果：${round.global_decision}` : ""
  }));
  const effectiveRoundNo = activeRoundNo ?? rounds.at(-1)?.roundNo ?? null;

  const traceCards = useMemo(() => run.traces.map((trace, index) => {
    const payload = asRecord(trace.payload);
    const traceType = trace.trace_type.toUpperCase();
    const kind = traceType.includes("SEARCH")
      ? "search" as const
      : traceType.includes("READ") || traceType.includes("FETCH") || traceType.includes("EVIDENCE")
        ? "read" as const
        : "workspace" as const;
    const sourceId = text(payload["source_id"], "");
    const checkpointNo = count(payload["checkpoint_no"]) || null;
    const roundNo = count(payload["round_no"]) || null;
    const key = trace.trace_id || `trace-${index}`;
    return {
      key,
      anchorId: `research-trace-${key}`,
      traceType: trace.trace_type,
      traceMessage: trace.trace_message,
      roundNo,
      narrative: text(payload["narrative"] ?? payload["summary"], "该步骤已记录到 Research trace。"),
      createdAt: trace.created_at,
      checkpointNarrative: checkpointNo ? `关联 checkpoint #${checkpointNo}` : "未关联 checkpoint",
      auditFocusNarrative: "当前审计焦点",
      recoveryNarrative: text(payload["recovery_narrative"], ""),
      sourceNarrative: text(payload["source_title"] ?? payload["source_url"], ""),
      outcomeNarrative: text(payload["outcome"] ?? payload["decision"], ""),
      primaryUrl: text(payload["source_url"] ?? payload["url"], ""),
      primarySourceId: sourceId,
      primarySourceInScope: sourceIds.has(sourceId),
      hasPrimarySourceAsset: Boolean(sourceId),
      checkpointNo,
      kind,
      focused: false,
      selected: selectedTraceKey === key,
      auditTarget: { traceIndex: index, checkpointNo, branchId: text(payload["branch_id"], "") },
      payload
    };
  }), [run.traces, selectedTraceKey, sourceIds]);
  const traceGroups = (["search", "read", "workspace"] as const).map((kind) => ({
    title: kind === "search" ? "搜索线索" : kind === "read" ? "阅读与证据" : "闭环与工作台",
    stageLabel: kind === "search" ? "Search" : kind === "read" ? "Read" : "Workspace",
    description: kind === "search" ? "检索 query 与候选入口" : kind === "read" ? "页面读取、证据提取与快照" : "校验、checkpoint 与结果回流",
    empty: "当前阶段还没有可展示的轨迹。",
    tone: kind,
    roundMessage: effectiveRoundNo ? `当前查看第 ${effectiveRoundNo} 轮` : "整体运行轨迹",
    entries: traceCards.filter((trace) => trace.kind === kind)
  }));
  const selectedTrace = traceCards.find((trace) => trace.key === selectedTraceKey) ?? traceCards[0] ?? null;
  const selectedTraceDetail = selectedTrace ? {
    ...selectedTrace,
    tone: selectedTrace.kind,
    title: selectedTrace.kind === "search" ? "搜索" : selectedTrace.kind === "read" ? "阅读" : "闭环",
    stageLabel: selectedTrace.kind.toUpperCase(),
    primaryUrlLabel: selectedTrace.primaryUrl || selectedTrace.primarySourceId || "未显式返回",
    provider: text(selectedTrace.payload["provider"], ""),
    adapter: text(selectedTrace.payload["adapter"], ""),
    snapshotStatus: text(selectedTrace.payload["snapshot_status"], ""),
    querySamples: stringList(selectedTrace.payload["search_queries"] ?? selectedTrace.payload["query_samples"]),
    searchAngles: stringList(selectedTrace.payload["search_angles"]),
    readFocuses: stringList(selectedTrace.payload["read_focuses"] ?? selectedTrace.payload["read_focus"])
  } : null;

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
    <div className="research-detail-modal research-detail-workbench" onClick={(event) => event.stopPropagation()}>
      <div className="research-detail-header">
        <div>
          <p className="section-label">Research workbench</p>
          <h3>{run.final_report_title || run.question || "Deep Research"}</h3>
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
              searchReadTimeline={timeline}
              sourceEvidenceSummary={evidenceSummary}
              searchTimelineSummary={timeline?.all_search_queries.join(" / ") || "尚未返回检索 query"}
              searchTimelineRoundLabel={`${timeline?.loop_round_count ?? 0} 个研究轮次`}
              fetchTimelineSummary={`${timeline?.total_search_hit_count ?? 0} 个候选入口`}
              fetchTimelineDetail="候选入口会在阅读阶段转为可追溯证据。"
              readTimelineSummary={`${timeline?.total_read_window_count ?? 0} 个阅读窗口`}
              readTimelineDetail={`${timeline?.total_evidence_card_count ?? 0} 张证据卡`}
              sourceEvidenceSummaryLead={`${evidenceSummary?.verified_finding_count ?? 0} 条已验证结论`}
              sourceEvidenceSummaryDetail={`${evidenceSummary?.citation_count ?? 0} 条引用 · ${evidenceSummary?.primary_quality || "质量待评估"}`}
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
              overviewSearchCount={timeline?.total_search_hit_count ?? 0}
              overviewSearchLabel="搜索命中"
              overviewReadCount={timeline?.total_read_window_count ?? 0}
              overviewReadLabel="阅读窗口"
              overviewWorkspaceCount={run.saved_report_source ? 1 : 0}
              overviewWorkspaceLabel="报告回流"
              overviewLoopCount={String(timeline?.loop_round_count ?? 0)}
              overviewLoopLabel="闭环轮次"
            />
            <ResearchProcessStageLanes
              searchSummary={timeline?.all_search_queries.join(" / ") || "尚未返回 query"}
              searchScopeMessage={`${timeline?.total_search_hit_count ?? 0} 个搜索命中`}
              searchAngleMessage={`${timeline?.loop_round_count ?? 0} 个轮次`}
              runtimeSnapshot={run.status}
              readSummary={`${timeline?.total_read_window_count ?? 0} 个阅读窗口`}
              readSourceMessage={`${timeline?.total_evidence_card_count ?? 0} 张证据卡`}
              readScopeMessage={`${evidenceSummary?.verified_finding_count ?? 0} 条已验证结论`}
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
