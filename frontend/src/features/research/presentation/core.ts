import {
  type ResearchCounterfactualSummary,
  type ResearchFinalAnswer,
  type ResearchHistoryFilter,
  type ResearchRecoveryTargets,
  type ResearchReportStructure,
  type ResearchRowSummary,
  type ResearchRunSummary,
  type ResearchRunSummarySnapshot,
  type ResearchTimelineMilestone,
  type ResearchTimelinePathSummary,
  type ResearchIntentCompletionContract,
  type SaveResearchReportSource,
  type SignalChip,
  type SignalTone
} from "../model";
import { type SourceAsset } from "../../sources/model";
import { type KnowledgeCitation } from "../../knowledge/model";
import {
  type ExecutionEvent as StreamEvent,
  type ExecutionTask as TaskStatus
} from "../../executions/model";
import { asRecord, asRecordArray, asStringArray, numberFromUnknown } from "../../../shared/util/records";
import { formatTimestamp } from "../../../shared/util/datetime";
export function summarizeText(text: string, limit = 140) {
  if (text.length <= limit) {
    return text;
  }
  return `${text.slice(0, limit).trimEnd()}...`;
}



export function readRecoveryTargets(value: unknown): ResearchRecoveryTargets | null {
  const record = asRecord(value);
  if (!Object.keys(record).length) {
    return null;
  }
  const requirementIds = asStringArray(record.requirement_ids);
  const requirementTypes = asStringArray(record.requirement_types);
  const requirementLabels = asStringArray(record.requirement_labels);
  const targetColumns = asStringArray(record.target_columns);
  const targetQueries = asStringArray(record.target_queries);
  const targetSources = asStringArray(record.target_sources);
  if (
    requirementIds.length === 0
    && requirementTypes.length === 0
    && requirementLabels.length === 0
    && targetColumns.length === 0
    && targetQueries.length === 0
    && targetSources.length === 0
  ) {
    return null;
  }
  return {
    requirement_ids: requirementIds,
    requirement_types: requirementTypes,
    requirement_labels: requirementLabels,
    target_columns: targetColumns,
    target_queries: targetQueries,
    target_sources: targetSources,
    requirement_count: numberFromUnknown(record.requirement_count) || requirementIds.length,
    query_count: numberFromUnknown(record.query_count) || targetQueries.length,
    source_count: numberFromUnknown(record.source_count) || targetSources.length,
    column_count: numberFromUnknown(record.column_count) || targetColumns.length,
  };
}

export function asResearchFinalAnswer(value: unknown): ResearchFinalAnswer | null {
  const record = asRecord(value);
  if (!Object.keys(record).length) {
    return null;
  }
  return {
    answer_text: String(record.answer_text || "").trim(),
    answer_status: String(record.answer_status || "").trim(),
    confidence_label: String(record.confidence_label || "").trim(),
    coverage_label: String(record.coverage_label || "").trim(),
    source_basis: String(record.source_basis || "").trim(),
    ledger_row_count: numberFromUnknown(record.ledger_row_count)
  };
}


export function readResearchFinalAnswer(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[],
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[],
  intentContract: ResearchIntentCompletionContract | null
): ResearchFinalAnswer {
  const structured = asResearchFinalAnswer(reportStructure?.final_answer);
  if (structured && structured.answer_text) {
    return structured;
  }
  const topClaims = verifiedFindings
    .map((row) => row.claim_text || "")
    .filter((item) => Boolean(item))
    .slice(0, 3);
  let answerText = "当前还没有形成稳定的最终答案。";
  if (topClaims.length > 0) {
    answerText = `基于当前 verifier 批准的证据，研究结论为：${topClaims.join("；")}`;
  } else if (conflictedRows.length > 0) {
    answerText = `当前仍有 ${conflictedRows.length} 条冲突证据阻塞稳定结论，结果需要继续纠偏。`;
  } else if (guardrailedRows.length > 0) {
    answerText = `当前可以给出受控答案，但还有 ${guardrailedRows.length} 条 guardrailed finding 需要修复后再提升置信度。`;
  }
  const answerStatus = verifiedFindings.length > 0
    ? (conflictedRows.length > 0 ? "GUARDED" : "VERIFIED")
    : "RECOVERY_NEEDED";
  return {
    answer_text: answerText,
    answer_status: answerStatus,
    confidence_label: verifiedFindings.length > 0
      ? `已形成 ${verifiedFindings.length} 条 verifier 批准 finding`
      : "当前仍在恢复与验证阶段",
    coverage_label: intentContract
      ? `${intentContract.satisfied_requirement_count}/${intentContract.total_requirement_count} 个 intent requirement 已满足`
      : `${verifiedFindings.length} 条 finding 可用于合成`,
    source_basis: summarizeResearchSourceBasis(verifiedFindings),
    ledger_row_count: verifiedFindings.length + conflictedRows.length + guardrailedRows.length
  };
}

export function readResearchExecutiveSummary(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[],
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[],
  counterfactualSummary: ResearchCounterfactualSummary | null
) {
  const structured = asStringArray(reportStructure?.executive_summary);
  if (structured.length > 0) {
    return structured;
  }
  const summary = [
    verifiedFindings.length > 0
      ? `本次研究已沉淀 ${verifiedFindings.length} 条可直接合成答案的 finding。`
      : "本次研究仍处于受控恢复中，尚未形成稳定答案。"
  ];
  if (conflictedRows.length > 0) {
    summary.push(`${conflictedRows.length} 条 finding 仍处于冲突状态。`);
  } else if (guardrailedRows.length > 0) {
    summary.push(`${guardrailedRows.length} 条 finding 仍带 guardrails，需要继续修复。`);
  }
  if (counterfactualSummary?.has_counterfactual_recheck) {
    summary.push(`反证分支已介入，共触发 ${counterfactualSummary.counterfactual_branch_count} 个 counterfactual branch。`);
  }
  return summary;
}

export function readResearchKeyTakeaways(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[]
) {
  const structured = asStringArray(reportStructure?.key_takeaways);
  if (structured.length > 0) {
    return structured;
  }
  return verifiedFindings
    .map((row) => row.claim_text || "")
    .filter((item) => Boolean(item))
    .slice(0, 4);
}

export function readResearchEvidenceHighlights(
  reportStructure: ResearchReportStructure | null,
  verifiedFindings: ResearchRowSummary[],
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[]
) {
  const structured = asResearchRows(reportStructure?.evidence_highlights);
  if (structured.length > 0) {
    return structured;
  }
  if (verifiedFindings.length > 0) {
    return verifiedFindings.slice(0, 3);
  }
  return [...conflictedRows, ...guardrailedRows].slice(0, 3);
}

export function readResearchUncertaintyAndRisks(
  reportStructure: ResearchReportStructure | null,
  conflictedRows: ResearchRowSummary[],
  guardrailedRows: ResearchRowSummary[],
  intentContract: ResearchIntentCompletionContract | null
) {
  const structured = asStringArray(reportStructure?.uncertainty_and_risks);
  if (structured.length > 0) {
    return structured;
  }
  const risks: string[] = [];
  if (conflictedRows.length > 0) {
    risks.push(`仍有 ${conflictedRows.length} 条冲突 finding 可能改变最终结论。`);
  }
  if (guardrailedRows.length > 0) {
    risks.push(`仍有 ${guardrailedRows.length} 条 guardrailed finding 需要修复。`);
  }
  if (intentContract && intentContract.pending_requirement_count > 0) {
    risks.push(`还有 ${intentContract.pending_requirement_count} 个 intent requirement 未闭合。`);
  }
  if (risks.length === 0) {
    risks.push("当前没有明显的阻塞性冲突，答案可直接阅读。");
  }
  return risks;
}


export function summarizeResearchSourceBasis(rows: ResearchRowSummary[]) {
  const titles = [...new Set(rows.map((row) => row.source_title || "").filter((item) => Boolean(item)))];
  if (titles.length === 0) {
    return "当前还没有 verifier 批准的来源基础。";
  }
  return `${titles.length} 个来源支撑：${titles.slice(0, 3).join(" / ")}`;
}

export function formatResearchAnswerStatus(status: string) {
  switch ((status || "").toUpperCase()) {
    case "VERIFIED":
      return "Verifier Approved";
    case "GUARDED":
      return "Guarded";
    case "RECOVERY_NEEDED":
      return "Recovery Needed";
    default:
      return status || "Unknown";
  }
}

export function formatRecoveryTargetLabels(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.requirement_labels.slice(0, 2).join(" / ");
}

export function formatRecoveryTargetColumns(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.target_columns.slice(0, 3).join(", ");
}

export function formatRecoveryTargetQueries(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.target_queries.slice(0, 2).join(" / ");
}

export function formatRecoveryTargetSources(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  return targets.target_sources.slice(0, 2).join(" / ");
}

export function summarizeRecoveryTargetTypes(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  const normalized = [...new Set(targets.requirement_types.map((item) => {
    switch (item) {
      case "CONFLICT_FINDING":
        return "conflict";
      case "CONSTRAINT_FINDING":
        return "evidence";
      case "GOAL_FINDING":
        return "goal";
      default:
        return normalizeSignalValue(item).toLowerCase() || "target";
    }
  }))];
  return normalized.slice(0, 2).join(" / ");
}

export function summarizeRecoveryTargetFocus(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  const columns = targets.target_columns.slice(0, 2).join(", ");
  if (columns) {
    return columns;
  }
  const sources = targets.target_sources.slice(0, 1).join(" / ");
  if (sources) {
    return sources;
  }
  const queries = targets.target_queries.slice(0, 1).join(" / ");
  if (queries) {
    return summarizeText(queries, 32);
  }
  return `${targets.requirement_count || 0} targets`;
}

export function buildRecoveryTargetNarrativeFragment(targets: ResearchRecoveryTargets | null) {
  if (!targets) {
    return "";
  }
  const types = summarizeRecoveryTargetTypes(targets);
  const columns = formatRecoveryTargetColumns(targets);
  const queries = formatRecoveryTargetQueries(targets);
  const sources = formatRecoveryTargetSources(targets);
  if (columns) {
    return `当前纠偏靶点：${types || "target"} requirement，恢复焦点已收敛到 columns ${columns}`;
  }
  if (queries) {
    return `当前纠偏靶点：${types || "target"} requirement，恢复焦点正在围绕 queries ${summarizeText(queries, 48)}`;
  }
  if (sources) {
    return `当前纠偏靶点：${types || "target"} requirement，恢复焦点正在围绕 sources ${sources}`;
  }
  if (types) {
    return `当前纠偏靶点：${types} requirement`;
  }
  return `当前纠偏靶点：${targets.requirement_count || 0} 个 requirement`;
}

export function buildCurrentRecoveryNarrative(recoveryMode: string, targets: ResearchRecoveryTargets | null) {
  const normalizedMode = normalizeSignalValue(recoveryMode);
  const targetNarrative = buildRecoveryTargetNarrativeFragment(targets);
  if (normalizedMode && targetNarrative) {
    return `当前恢复说明：strategy=${normalizedMode}；${targetNarrative}。`;
  }
  if (targetNarrative) {
    return `当前恢复说明：${targetNarrative}。`;
  }
  if (normalizedMode) {
    return `当前恢复说明：当前 closed-loop 处于 ${normalizedMode}。`;
  }
  return "";
}


export function buildReportRecoveryNarrative(recoveryMode: string, targets: ResearchRecoveryTargets | null) {
  const narrative = buildCurrentRecoveryNarrative(recoveryMode, targets);
  if (!narrative) {
    return "";
  }
  return narrative.replace("当前恢复说明：", "报告区纠偏说明：");
}

export function buildArtifactRecoveryNarrative(recoveryMode: string, targets: ResearchRecoveryTargets | null) {
  const narrative = buildCurrentRecoveryNarrative(recoveryMode, targets);
  if (!narrative) {
    return "";
  }
  return narrative.replace("当前恢复说明：", "产物出口纠偏说明：");
}


export function buildLifecycleCreationNarrative(
  question: string,
  researchGoal: string,
  depth: string,
  sourceScopeCount: number
) {
  const goalText = researchGoal.trim() || summarizeText(question, 48) || "显式研究问题";
  const scopeText = sourceScopeCount > 0
    ? `显式资料范围 ${sourceScopeCount} 份`
    : "当前未附带显式资料范围";
  return `生命周期说明：新建 run 将围绕 ${goalText} 进入 closed-loop research，depth=${depth || "STANDARD"}，${scopeText}。`;
}















































export function buildSourceOriginBadge(source: SourceAsset | SaveResearchReportSource) {
  if (source.generated_by === "research_agent") {
    return `Research Report(${source.generated_ref_id || "unknown run"})`;
  }
  return "Workspace Source";
}

export function buildKnowledgeCitationLabel(citation: KnowledgeCitation) {
  if (citation.generated_by === "research_agent") {
    return `${citation.title} · Research Report(${citation.generated_ref_id || "unknown run"})`;
  }
  return citation.title;
}

export function buildResearchSourceLabel(
  item: Record<string, unknown> | ResearchRowSummary | null | undefined,
  sourceById: Map<string, SourceAsset>,
  fallback: string
) {
  const record = item && typeof item === "object" ? item as Record<string, unknown> : {};
  const sourceId = String(record.source_id ?? "").trim();
  const sourceTitle = String(record.source_title ?? record.title ?? "").trim();
  const directGeneratedBy = String(record.generated_by ?? "").trim();
  const directGeneratedRefId = String(record.generated_ref_id ?? "").trim();
  const source = sourceId ? sourceById.get(sourceId) : undefined;
  const generatedBy = directGeneratedBy || source?.generated_by || "";
  const generatedRefId = directGeneratedRefId || source?.generated_ref_id || "";
  const title = sourceTitle || source?.title || fallback;
  if (generatedBy === "research_agent") {
    return `${title} · Research Report(${generatedRefId || "unknown run"})`;
  }
  return title;
}

export function buildRunCheckpointNarrative(run: ResearchRunSummary) {
  const checkpointCount = numberFromUnknown(run.checkpoint_count);
  if (checkpointCount <= 0) {
    return "Checkpoint 命中：当前 run 尚未沉淀显式 checkpoint。";
  }
  const recoveryTargets = readRecoveryTargets(run.recovery_targets);
  const recoveryNarrative = buildCurrentRecoveryNarrative(run.recovery_mode || "", recoveryTargets);
  return recoveryNarrative
    ? `Checkpoint 命中：已沉淀 ${checkpointCount} 个 checkpoint；${recoveryNarrative}`
    : `Checkpoint 命中：已沉淀 ${checkpointCount} 个 checkpoint。`;
}

export function branchLaneLabel(branchId: string) {
  const normalized = normalizeSignalValue(branchId);
  if (!normalized || normalized === "branch-main") {
    return "主分支";
  }
  return "反证分支";
}


export function buildBranchCheckpointDeltaNarrative(
  baselineBranchId: string,
  baselineCheckpointCount: number,
  baselineCounterfactualBranchCount: number,
  currentBranchId: string,
  currentCheckpointCount: number,
  currentCounterfactualBranchCount: number
) {
  const baselineLane = branchLaneLabel(baselineBranchId);
  const currentLane = branchLaneLabel(currentBranchId);
  if (baselineCheckpointCount <= 0 && currentCheckpointCount <= 0) {
    return "分支沉淀判断：baseline 与 current 都还没有显式 checkpoint 沉淀。";
  }
  if (baselineLane !== currentLane) {
    return `分支沉淀判断：已从${baselineLane}切换到${currentLane}；baseline checkpoint=${baselineCheckpointCount}，current checkpoint=${currentCheckpointCount}。`;
  }
  if (currentCheckpointCount > baselineCheckpointCount) {
    return `分支沉淀判断：继续在${currentLane}上累积 checkpoint；baseline=${baselineCheckpointCount}，current=${currentCheckpointCount}。`;
  }
  if (currentCheckpointCount < baselineCheckpointCount) {
    return `分支沉淀判断：当前${currentLane}可见 checkpoint 少于 baseline；baseline=${baselineCheckpointCount}，current=${currentCheckpointCount}。`;
  }
  if (currentCounterfactualBranchCount !== baselineCounterfactualBranchCount) {
    return `分支沉淀判断：checkpoint 数量保持 ${currentCheckpointCount}，但 counterfactual branches 从 ${baselineCounterfactualBranchCount} 变为 ${currentCounterfactualBranchCount}。`;
  }
  return `分支沉淀判断：baseline 与 current 都停留在${currentLane}，checkpoint 沉淀规模保持 ${currentCheckpointCount}。`;
}

export function buildContinuityStateNarrative(
  baselineBranchId: string,
  baselineCheckpointCount: number,
  baselineCounterfactualBranchCount: number,
  currentBranchId: string,
  currentCheckpointCount: number,
  currentCounterfactualBranchCount: number,
  baselineVerifiedCount: number,
  currentVerifiedCount: number,
  baselineConflictedCount: number,
  currentConflictedCount: number
) {
  const branchNarrative = buildBranchCheckpointDeltaNarrative(
    baselineBranchId,
    baselineCheckpointCount,
    baselineCounterfactualBranchCount,
    currentBranchId,
    currentCheckpointCount,
    currentCounterfactualBranchCount
  );
  const verifiedDelta = currentVerifiedCount - baselineVerifiedCount;
  const conflictedDelta = currentConflictedCount - baselineConflictedCount;
  let stateJudgement = "闭环状态整体保持平移。";
  if (verifiedDelta > 0 && conflictedDelta < 0) {
    stateJudgement = "闭环状态表现为收敛推进：verified 上升且 conflicted 下降。";
  } else if (verifiedDelta > 0 && conflictedDelta === 0) {
    stateJudgement = "闭环状态表现为结论增厚：verified 上升而 conflicted 持平。";
  } else if (verifiedDelta === 0 && conflictedDelta < 0) {
    stateJudgement = "闭环状态表现为冲突消解：verified 持平而 conflicted 下降。";
  } else if (verifiedDelta < 0 && conflictedDelta > 0) {
    stateJudgement = "闭环状态表现为重新失稳：verified 回落且 conflicted 上升。";
  } else if (verifiedDelta < 0) {
    stateJudgement = "闭环状态仍在重排：verified 数量较基线回落。";
  } else if (conflictedDelta > 0) {
    stateJudgement = "闭环状态仍在扩散冲突：conflicted 数量较基线增加。";
  }
  return `${branchNarrative} Table-as-State 从 verified ${baselineVerifiedCount} -> ${currentVerifiedCount}、conflicted ${baselineConflictedCount} -> ${currentConflictedCount}，${stateJudgement}`;
}

export function buildRunContinuityBaselineLabel(
  baselineRun: ResearchRunSummary | null,
  currentRun: ResearchRunSummary
) {
  if (!baselineRun) {
    return "";
  }
  if (currentRun.resumed_from_research_run_id && baselineRun.research_run_id === currentRun.resumed_from_research_run_id) {
    return `resume source ${baselineRun.research_run_id} #${currentRun.resumed_from_checkpoint_no ?? "-"}`;
  }
  return `previous run ${baselineRun.research_run_id}`;
}

export function buildRunContinuityNarrative(
  baselineRun: ResearchRunSummary | null,
  currentRun: ResearchRunSummary
) {
  if (!baselineRun) {
    return "";
  }
  const baselineSnapshot = extractRunSummarySnapshot(baselineRun);
  const currentSnapshot = extractRunSummarySnapshot(currentRun);
  return buildContinuityStateNarrative(
    baselineSnapshot.activeBranchId,
    baselineSnapshot.checkpointCount,
    baselineSnapshot.counterfactualBranchCount,
    currentSnapshot.activeBranchId,
    currentSnapshot.checkpointCount,
    currentSnapshot.counterfactualBranchCount,
    baselineSnapshot.verifiedRowCount,
    currentSnapshot.verifiedRowCount,
    baselineSnapshot.conflictedRowCount,
    currentSnapshot.conflictedRowCount
  );
}

export function findRunContinuityBaseline(
  runs: ResearchRunSummary[],
  currentRun: ResearchRunSummary | null | undefined
) {
  if (!currentRun) {
    return null;
  }
  const chronologicalRuns = [...runs].sort(compareResearchRunsByTimeAsc);
  const currentIndex = chronologicalRuns.findIndex((run) => run.research_run_id === currentRun.research_run_id);
  if (currentRun.resumed_from_research_run_id) {
    return chronologicalRuns.find((run) => run.research_run_id === currentRun.resumed_from_research_run_id) ?? null;
  }
  if (currentIndex <= 0) {
    return null;
  }
  return chronologicalRuns[currentIndex - 1] ?? null;
}

export function labelTaskType(taskType: string) {
  switch (normalizeSignalValue(taskType)) {
    case "RESEARCH_RUN":
      return "Deep Research 任务";
    case "SOURCE_PARSE":
      return "资料处理任务";
    case "WIKI_INGEST":
      return "Wiki ingest 任务";
    case "WIKI_RETRACT":
      return "Wiki retract 任务";
    case "ARTIFACT_JOB":
      return "产物任务";
    default:
      return normalizeSignalValue(taskType) || "任务";
  }
}

export function readTaskEventPhase(event: StreamEvent, task: TaskStatus | null) {
  const payload = asRecord(event.payload);
  return normalizeSignalValue(payload.phase ?? task?.progress_phase);
}

export function readTaskEventMetrics(event: StreamEvent) {
  const payload = asRecord(event.payload);
  return asRecord(payload.metrics);
}









export function formatTaskMetric(metric: string, value: string | number | null | undefined) {
  if (value == null || value === "") {
    return "";
  }
  return `${metric} ${value}`;
}

export function buildResearchTaskMetricSummary(event: StreamEvent, limit = 6) {
  const payload = asRecord(event.payload);
  const metrics = readTaskEventMetrics(event);
  const items = [
    formatTaskMetric("scope", numberFromUnknown(metrics.source_count) || undefined),
    formatTaskMetric("query", numberFromUnknown(metrics.query_count) || undefined),
    formatTaskMetric("hits", numberFromUnknown(metrics.search_hits) || undefined),
    formatTaskMetric("windows", numberFromUnknown(metrics.read_windows) || undefined),
    formatTaskMetric("evidence", numberFromUnknown(metrics.evidence_cards) || undefined),
    formatTaskMetric("rows", numberFromUnknown(metrics.ledger_rows) || undefined),
    formatTaskMetric("cells", numberFromUnknown(metrics.ledger_cells) || undefined),
    formatTaskMetric("branches", numberFromUnknown(metrics.branch_count) || undefined),
    formatTaskMetric("rounds", numberFromUnknown(metrics.loop_rounds) || undefined),
    formatTaskMetric("local", normalizeSignalValue(metrics.local_status) || undefined),
    formatTaskMetric("global", normalizeSignalValue(metrics.global_status) || undefined),
    formatTaskMetric("loop", normalizeSignalValue(metrics.loop_decision) || undefined),
    formatTaskMetric("progress", numberFromUnknown(payload.progress_percent) > 0 ? `${numberFromUnknown(payload.progress_percent)}%` : undefined),
  ].filter(Boolean);
  if (!items.length) {
    return "";
  }
  return `当前闭环计量：${items.slice(0, limit).join(" · ")}`;
}


export function buildResearchTaskRuntimeSnapshot(event: StreamEvent | null, task: TaskStatus | null) {
  if (!event) {
    return "";
  }
  const phase = readTaskEventPhase(event, task);
  const metricSummary = buildResearchTaskMetricSummary(event, 8);
  if (!metricSummary) {
    return "";
  }
  switch (phase) {
    case "SEARCHING":
      return `Research Harness runtime snapshot：当前处于检索扩展段。${metricSummary}`;
    case "READING":
      return `Research Harness runtime snapshot：当前处于读窗压缩段。${metricSummary}`;
    case "EXTRACTING":
      return `Research Harness runtime snapshot：当前正在把读窗沉淀为 Table-as-State。${metricSummary}`;
    case "VERIFYING":
      return `Research Harness runtime snapshot：当前正在进入 Dual Verifier / 反证分支判断。${metricSummary}`;
    case "WRITING":
      return `Research Harness runtime snapshot：当前正在把闭环结果固化为报告产物。${metricSummary}`;
    default:
      return `Research Harness runtime snapshot：${metricSummary}`;
  }
}







export function buildGenericTaskRuntimeSnapshot(event: StreamEvent | null, task: TaskStatus | null) {
  if (!event || !task) {
    return "";
  }
  const payload = asRecord(event.payload);
  const progressPercent = numberFromUnknown(payload.progress_percent);
  const phase = readTaskEventPhase(event, task);
  const metrics = readTaskEventMetrics(event);
  const base = [
    phase ? `phase ${phase}` : "",
    progressPercent > 0 ? `progress ${progressPercent}%` : "",
    Object.keys(metrics).length ? `metrics ${Object.keys(metrics).length}` : ""
  ].filter(Boolean).join(" · ");
  if (!base) {
    return "";
  }
  return `${labelTaskType(task.task_type)} runtime snapshot：${base}`;
}



export function buildTaskEventNarrative(event: StreamEvent, task: TaskStatus | null) {
  const taskLabel = labelTaskType(task?.task_type || "");
  const phase = readTaskEventPhase(event, task);
  const message = summarizeText(event.message || event.data || task?.progress_message || "", 96);
  switch (event.event) {
    case "task.status":
      switch (normalizeSignalValue(task?.task_type)) {
        case "SOURCE_PARSE":
          return "资料处理链路已接管上传内容，准备解析、切片并建立检索索引。";
        case "WIKI_RETRACT":
          return "工作台已开始清理资料删除带来的 Wiki 回链影响。";
        case "WIKI_INGEST":
          return "工作台已开始按资料更新 Wiki 页面与回链。";
        case "ARTIFACT_JOB":
          return "产物任务已入队，等待版本化与资料回流。";
        default:
          return `${taskLabel}已入队。${message}`;
      }
    case "task.heartbeat": {
      const heartbeatAt = String(asRecord(event.payload).heartbeat_at ?? "").trim();
      return `${taskLabel}保活心跳：${heartbeatAt || "worker running"}`;
    }
    case "task.completed":
      return `${taskLabel}已完成：${message || "当前任务已经处理完成。"}`;
    case "task.failed":
      return `${taskLabel}失败：${message || summarizeText(task?.error_message || "任务执行中断", 96)}。`;
    case "task.progress":
    default:
      switch (normalizeSignalValue(task?.task_type)) {
        case "SOURCE_PARSE":
          if (phase === "PARSING") {
            return "资料正在解析、切片并写入本地检索索引。";
          }
          if (phase === "INDEXED") {
            return "资料已经进入检索索引，可继续用于 QA / Note / Wiki 链路。";
          }
          return `资料处理正在推进：${message || "等待下一个阶段。"}`;
        case "WIKI_RETRACT":
          return `Wiki retract 正在清理受影响页面：${message || "处理中。"}`;
        case "WIKI_INGEST":
          return `Wiki ingest 正在根据资料更新工作台页面：${message || "处理中。"}`;
        case "ARTIFACT_JOB":
          return `产物任务正在推进版本化与资料回流：${message || "处理中。"}`;
        default:
          return `${taskLabel}正在推进：${message || "处理中。"}`;
      }
  }
}

export function readIntentCompletionContract(value: unknown): ResearchIntentCompletionContract | null {
  const record = asRecord(value);
  if (!Object.keys(record).length) {
    return null;
  }
  return {
    status: String(record.status ?? ""),
    reason_code: String(record.reason_code ?? ""),
    total_requirement_count: numberFromUnknown(record.total_requirement_count),
    satisfied_requirement_count: numberFromUnknown(record.satisfied_requirement_count),
    pending_requirement_count: numberFromUnknown(record.pending_requirement_count),
    missing_requirement_labels: asStringArray(record.missing_requirement_labels),
    requirements: asRecordArray(record.requirements).map((requirement) => ({
      requirement_id: String(requirement.requirement_id ?? ""),
      requirement_type: String(requirement.requirement_type ?? ""),
      label: String(requirement.label ?? ""),
      status: String(requirement.status ?? ""),
      evidence_anchor: String(requirement.evidence_anchor ?? ""),
      evidence_refs: asStringArray(requirement.evidence_refs),
      coverage_note: String(requirement.coverage_note ?? ""),
      missing_reason: String(requirement.missing_reason ?? ""),
    })),
  };
}

export function asResearchRows(value: unknown): ResearchRowSummary[] {
  return asRecordArray(value) as ResearchRowSummary[];
}

export function compareResearchRunsByTimeAsc(left: ResearchRunSummary, right: ResearchRunSummary) {
  const leftTime = Date.parse(left.created_at || left.updated_at || "");
  const rightTime = Date.parse(right.created_at || right.updated_at || "");
  return leftTime - rightTime;
}

export function researchHistoryFilterLabel(filter: ResearchHistoryFilter) {
  switch (filter) {
    case "RECOVERY":
      return "恢复";
    case "CONFLICT":
      return "冲突";
    case "STABLE":
      return "稳定";
    case "RESUMED":
      return "续跑";
    default:
      return "全部";
  }
}

export function matchesResearchHistoryFilter(run: ResearchRunSummary, filter: ResearchHistoryFilter) {
  if (filter === "ALL") {
    return true;
  }
  if (filter === "RESUMED") {
    return isResumedRun(run);
  }
  if (filter === "RECOVERY") {
    return isRecoveryRun(run);
  }
  if (filter === "CONFLICT") {
    return isConflictRun(run);
  }
  if (filter === "STABLE") {
    return isStableRun(run);
  }
  return true;
}

export function buildRunPrimaryTone(run: ResearchRunSummary): SignalTone {
  const reasons = [
    run.local_verifier_reason,
    run.global_verifier_reason,
    run.final_loop_reason,
    run.recovery_mode,
    run.research_intent_alignment_reason
  ].map(normalizeSignalValue);
  const hasConflict = run.conflicted_row_count > 0 || reasons.some(isConflictSignal);
  if (hasConflict) {
    return "conflict";
  }
  const hasRecovery = Boolean(run.recovery_mode) || (run.counterfactual_summary?.counterfactual_branch_count ?? 0) > 0 || reasons.some(isRecoverySignal);
  if (hasRecovery) {
    return "recovery";
  }
  if (run.resumed_from_research_run_id) {
    return "resume";
  }
  if (reasons.some(isStableSignal) || run.global_verifier_decision === "READY_TO_WRITE") {
    return "stable";
  }
  return "neutral";
}

export function buildResearchTimelineMilestones(runs: ResearchRunSummary[]): ResearchTimelineMilestone[] {
  const milestones: ResearchTimelineMilestone[] = [];
  const firstConflict = runs.find(isConflictRun);
  const firstRecovery = runs.find(isRecoveryRun);
  const firstResumed = runs.find(isResumedRun);
  const recoveryAnchorIndex = runs.findIndex((run) => isConflictRun(run) || isRecoveryRun(run) || isResumedRun(run));
  const firstStableAfterDrift = recoveryAnchorIndex >= 0
    ? runs.slice(recoveryAnchorIndex + 1).find(isStableRun)
    : null;

  pushMilestone(milestones, firstConflict, "first-conflict", "首次失稳", "首次进入 conflict / guardrail path", "conflict");
  pushMilestone(milestones, firstRecovery, "first-recovery", "首次恢复", "首次进入 recovery / counterfactual path", "recovery");
  pushMilestone(milestones, firstResumed, "first-resume", "首次续跑", "首次从 checkpoint 或 lineage 恢复任务", "resume");
  pushMilestone(milestones, firstStableAfterDrift, "first-restable", "首次回稳", "在 drift / recovery 之后第一次回到 stable path", "stable");

  return milestones;
}

export function buildResearchTimelinePathSummary(
  runs: ResearchRunSummary[],
  milestones: ResearchTimelineMilestone[],
  currentRun: ResearchRunSummary | null
): ResearchTimelinePathSummary {
  if (runs.length === 0 && milestones.length === 0) {
    return {
      stageLabels: [],
      currentStageLabel: "",
      hasRestabilized: false,
      narrative: "",
      currentRunStageLabel: "",
      currentRunAlignedWithPath: false
    };
  }
  const stageLabels: string[] = [];
  const firstStableRun = runs.find(isStableRun);
  const recoveryAnchorRun = currentRun && (isConflictRun(currentRun) || isRecoveryRun(currentRun) || isResumedRun(currentRun))
    ? currentRun
    : [...runs].reverse().find((run) => isConflictRun(run) || isRecoveryRun(run) || isResumedRun(run)) ?? null;
  const recoveryNarrative = buildRecoveryTargetNarrativeFragment(
    recoveryAnchorRun ? readRecoveryTargets(recoveryAnchorRun.recovery_targets) : null
  );
  if (firstStableRun) {
    stageLabels.push("稳定");
  }
  const orderedMilestones = [...milestones].sort((left, right) => compareResearchRunsByTimeAsc(left.run, right.run));
  for (const milestone of orderedMilestones) {
    const nextLabel = timelineStageLabel(milestone);
    if (stageLabels[stageLabels.length - 1] !== nextLabel) {
      stageLabels.push(nextLabel);
    }
  }
  const currentStageLabel = stageLabels[stageLabels.length - 1] ?? "";
  const currentRunStageLabel = currentRun ? currentRunStageLabelFromSummary(currentRun) : "";
  const currentRunAlignedWithPath = Boolean(currentRunStageLabel) && currentRunStageLabel === currentStageLabel;
  const hasRestabilized = stageLabels.lastIndexOf("稳定") > stageLabels.findIndex((label) => label !== "稳定");
  const narrative = hasRestabilized
    ? recoveryNarrative
      ? `研究路径已经经历失稳与恢复，并重新回到相对稳定阶段。最近一次 closed-loop recovery 中，${recoveryNarrative}。`
      : "研究路径已经经历失稳与恢复，并重新回到相对稳定阶段。"
    : stageLabels.includes("恢复")
      ? recoveryNarrative
        ? `研究路径已经进入恢复或反证纠偏阶段，${recoveryNarrative}，尚需继续观察是否重新收敛。`
        : "研究路径已经进入恢复或反证纠偏阶段，尚需继续观察是否重新收敛。"
      : stageLabels.includes("冲突")
        ? recoveryNarrative
          ? `研究路径已经出现冲突或 guardrail 信号，${recoveryNarrative}，后续重点关注 recovery 是否启动。`
          : "研究路径已经出现冲突或 guardrail 信号，后续重点关注 recovery 是否启动。"
        : "当前历史仍以稳定路径为主，尚未出现明显 recovery 闭环。";
  return {
    stageLabels,
    currentStageLabel,
    hasRestabilized,
    narrative,
    currentRunStageLabel,
    currentRunAlignedWithPath
  };
}

export function pushMilestone(
  milestones: ResearchTimelineMilestone[],
  run: ResearchRunSummary | null | undefined,
  key: string,
  label: string,
  description: string,
  tone: SignalTone
) {
  if (!run || milestones.some((entry) => entry.run.research_run_id === run.research_run_id)) {
    return;
  }
  const recoveryNarrative = buildRecoveryTargetNarrativeFragment(readRecoveryTargets(run.recovery_targets));
  milestones.push({
    key,
    label,
    description: recoveryNarrative ? `${description}；${recoveryNarrative}` : description,
    tone,
    run,
    chips: buildRunSignalChips(run)
  });
}

export function timelineStageLabel(milestone: ResearchTimelineMilestone) {
  if (milestone.key === "first-resume") {
    return "续跑";
  }
  if (milestone.key === "first-restable") {
    return "回稳";
  }
  switch (milestone.tone) {
    case "conflict":
      return "冲突";
    case "recovery":
      return "恢复";
    case "stable":
      return "稳定";
    case "resume":
      return "续跑";
    default:
      return milestone.label;
  }
}

export function currentRunStageLabelFromSummary(run: ResearchRunSummary) {
  if (isConflictRun(run)) {
    return "冲突";
  }
  if (isRecoveryRun(run)) {
    return "恢复";
  }
  if (isStableRun(run)) {
    return "稳定";
  }
  if (isResumedRun(run)) {
    return "续跑";
  }
  return run.status === "QUEUED" ? "排队" : "观察";
}

export function sameStageLabel(left: string, right: string) {
  return normalizeStageLabel(left) === normalizeStageLabel(right);
}

export function normalizeStageLabel(label: string) {
  if (label === "回稳") {
    return "稳定";
  }
  return label;
}

export function buildRunSignalChips(run: ResearchRunSummary): SignalChip[] {
  const chips: SignalChip[] = [];
  const recoveryTargets = readRecoveryTargets(run.recovery_targets);
  pushSignalChip(chips, run.resumed_from_research_run_id ? {
    label: "lineage",
    value: `resume #${run.resumed_from_checkpoint_no ?? "-"}`,
    tone: "resume"
  } : null);
  pushSignalChip(chips, run.local_verifier_reason ? {
    label: "local",
    value: run.local_verifier_reason,
    tone: classifySignalTone(run.local_verifier_reason, run.recovery_mode)
  } : null);
  pushSignalChip(chips, run.global_verifier_reason ? {
    label: "global",
    value: run.global_verifier_reason,
    tone: classifySignalTone(run.global_verifier_reason, run.recovery_mode)
  } : null);
  pushSignalChip(chips, run.final_loop_reason ? {
    label: "loop",
    value: run.final_loop_reason,
    tone: classifySignalTone(run.final_loop_reason, run.recovery_mode)
  } : null);
  pushSignalChip(chips, run.recovery_mode ? {
    label: "recovery",
    value: run.recovery_mode,
    tone: "recovery"
  } : null);
  pushSignalChip(chips, recoveryTargets ? {
    label: "target",
    value: summarizeRecoveryTargetTypes(recoveryTargets),
    tone: recoveryTargets.requirement_types.includes("CONFLICT_FINDING") ? "conflict" : "recovery"
  } : null);
  pushSignalChip(chips, recoveryTargets ? {
    label: "focus",
    value: summarizeRecoveryTargetFocus(recoveryTargets),
    tone: "recovery"
  } : null);
  pushSignalChip(chips, run.research_intent_alignment_reason ? {
    label: "intent",
    value: `${run.research_intent_alignment_status || "WARN"}:${run.research_intent_alignment_reason}`,
    tone: run.research_intent_alignment_status === "PASS" ? "stable" : "recovery"
  } : null);
  return chips.slice(0, 6);
}


export function pushSignalChip(chips: SignalChip[], chip: SignalChip | null) {
  if (!chip || !chip.value) {
    return;
  }
  if (chips.some((entry) => entry.label === chip.label && entry.value === chip.value)) {
    return;
  }
  chips.push(chip);
}


export function normalizeSignalValue(value: unknown) {
  return typeof value === "string" ? value.trim() : "";
}

export function classifySignalTone(reason: string, recoveryMode: string): SignalTone {
  if (recoveryMode) {
    return "recovery";
  }
  if (isConflictSignal(reason)) {
    return "conflict";
  }
  if (isRecoverySignal(reason)) {
    return "recovery";
  }
  if (isStableSignal(reason)) {
    return "stable";
  }
  return "neutral";
}

export function isConflictSignal(reason: string) {
  return /CONFLICT|LOW_CONFIDENCE|GUARDRAIL|WRITE_WITH_GUARDRAILS/i.test(reason);
}

export function isRecoverySignal(reason: string) {
  return /COUNTERFACTUAL|RECOVER|READ_MORE|EXTRACT_AGAIN|INTENT_REQUIREMENTS_PARTIAL|RESEARCH_INTENT_PARTIAL/i.test(reason);
}

export function isStableSignal(reason: string) {
  return /STOP_CONTRACT_SATISFIED|CHECKPOINT_VALID|READY_TO_WRITE|VERIFIED_PATH|PASS/i.test(reason);
}

export function isConflictRun(run: ResearchRunSummary) {
  return buildRunPrimaryTone(run) === "conflict";
}

export function isRecoveryRun(run: ResearchRunSummary) {
  return Boolean(run.recovery_mode) || (run.counterfactual_summary?.counterfactual_branch_count ?? 0) > 0 || buildRunPrimaryTone(run) === "recovery";
}

export function isStableRun(run: ResearchRunSummary) {
  return buildRunPrimaryTone(run) === "stable";
}

export function isResumedRun(run: ResearchRunSummary) {
  return Boolean(run.resumed_from_research_run_id);
}


export function extractRunSummarySnapshot(run: ResearchRunSummary | null): ResearchRunSummarySnapshot {
  return {
    status: run?.status ?? "",
    profileKey: run?.profile_key ?? "",
    activeBranchId: run?.active_branch_id ?? "",
    localVerifierStatus: run?.local_verifier_status ?? "",
    localVerifierReason: run?.local_verifier_reason ?? "",
    globalVerifierDecision: run?.global_verifier_decision ?? "",
    globalVerifierReason: run?.global_verifier_reason ?? "",
    finalLoopDecision: run?.final_loop_decision ?? "",
    finalLoopReason: run?.final_loop_reason ?? "",
    recoveryMode: run?.recovery_mode ?? "",
    ledgerRowCount: numberFromUnknown(run?.ledger_row_count),
    verifiedRowCount: numberFromUnknown(run?.verified_row_count),
    conflictedRowCount: numberFromUnknown(run?.conflicted_row_count),
    blockedRowCount: numberFromUnknown(run?.blocked_row_count),
    guardrailedRowCount: numberFromUnknown(run?.guardrailed_row_count),
    targetedBlockedRowCount: numberFromUnknown(run?.recovery_targeted_blocked_row_count),
    uncoveredBlockedRowCount: numberFromUnknown(run?.uncovered_blocked_row_count),
    requirementPartialBlockedRowCount: numberFromUnknown(run?.requirement_partial_blocked_row_count),
    checkpointCount: numberFromUnknown(run?.checkpoint_count),
    sourceScopeCount: numberFromUnknown(run?.source_scope_count),
    counterfactualBranchCount: run?.counterfactual_summary?.counterfactual_branch_count ?? 0,
    researchIntentAlignmentStatus: run?.research_intent_alignment_status ?? "",
    researchIntentAlignmentReason: run?.research_intent_alignment_reason ?? "",
    intentSatisfiedConstraintCount: numberFromUnknown(run?.intent_satisfied_constraint_count),
    intentConstraintCount: numberFromUnknown(run?.intent_constraint_count),
    intentSatisfiedRequirementCount: numberFromUnknown(run?.intent_satisfied_requirement_count),
    intentRequirementCount: numberFromUnknown(run?.intent_requirement_count),
    intentPendingRequirementCount: numberFromUnknown(run?.intent_pending_requirement_count),
    missingIntentRequirements: Array.isArray(run?.missing_intent_requirements) ? run?.missing_intent_requirements : [],
    recoveryTargets: readRecoveryTargets(run?.recovery_targets)
  };
}

