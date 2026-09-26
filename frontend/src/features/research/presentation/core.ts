import {
  type ResearchRowSummary,
  type ResearchRunSummary,
  type ResearchRunSummarySnapshot,
  type ResearchIntentCompletionContract,
  type SaveResearchReportSource
} from "../model";
import { type SourceAsset } from "../../sources/model";
import { type KnowledgeCitation } from "../../knowledge/model";
import { asRecord, asRecordArray, asStringArray, numberFromUnknown } from "../../../shared/util/records";
import { normalizeSignalValue, summarizeText } from "./primitives";
import { buildCurrentRecoveryNarrative, readRecoveryTargets } from "./recovery";
export * from "./recovery";
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
  const depthLabel = ({ QUICK: "快速", STANDARD: "标准", DEEP: "深度" } as Record<string, string>)[depth.toUpperCase()] || "标准";
  return `系统会围绕“${goalText}”依次完成检索、阅读、证据核验与报告整理，使用${depthLabel}研究模式，${scopeText}。`;
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

export function compareResearchRunsByTimeAsc(left: ResearchRunSummary, right: ResearchRunSummary) {
  const leftTime = Date.parse(left.created_at || left.updated_at || "");
  const rightTime = Date.parse(right.created_at || right.updated_at || "");
  return leftTime - rightTime;
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

