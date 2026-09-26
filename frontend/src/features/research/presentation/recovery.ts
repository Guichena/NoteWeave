import {
  type ResearchCounterfactualSummary,
  type ResearchFinalAnswer,
  type ResearchRecoveryTargets,
  type ResearchReportStructure,
  type ResearchRowSummary,
  type ResearchIntentCompletionContract
} from "../model";
import { asRecord, asRecordArray, asStringArray, numberFromUnknown } from "../../../shared/util/records";
import { normalizeSignalValue, summarizeText } from "./primitives";

export function asResearchRows(value: unknown): ResearchRowSummary[] {
  return asRecordArray(value) as ResearchRowSummary[];
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
    case "IN_PROGRESS":
      return "In Progress";
    case "FAILED":
      return "Run Failed";
    case "INSUFFICIENT_EVIDENCE":
      return "Evidence Insufficient";
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
