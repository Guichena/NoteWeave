import { asRecord } from "../../shared/util/records";
import {
  asResearchRows,
  buildResearchTimelineMilestones,
  buildResearchTimelinePathSummary,
  compareResearchRunsByTimeAsc,
  matchesResearchHistoryFilter,
  readIntentCompletionContract,
  readRecoveryTargets,
  readResearchEvidenceHighlights,
  readResearchExecutiveSummary,
  readResearchFinalAnswer,
  readResearchKeyTakeaways,
  readResearchUncertaintyAndRisks,
  summarizeText
} from "./presentation";
import {
  type ResearchHistoryFilter,
  type ResearchRunDetail,
  type ResearchRunSummary
} from "./model";
import type { SourceAsset } from "../sources/model";
import { selectReadyResearchSources } from "./launch";

export function buildResearchDerivedModel(input: {
  sources: SourceAsset[];
  selectedResearchSourceIds: string[];
  researchRuns: ResearchRunSummary[];
  researchHistoryFilter: ResearchHistoryFilter;
  currentResearchRunId: string;
  currentResearchRun: ResearchRunDetail | null;
  researchQuestion: string;
  researchGoal: string;
}) {
  const {
    sources,
    selectedResearchSourceIds,
    researchRuns,
    researchHistoryFilter,
    currentResearchRunId,
    currentResearchRun,
    researchQuestion,
    researchGoal
  } = input;

  const researchScopeSources = selectReadyResearchSources(sources, selectedResearchSourceIds);
  const filteredResearchRuns = researchRuns.filter((run) =>
    matchesResearchHistoryFilter(run, researchHistoryFilter));
  const chronologicalResearchRuns = [...researchRuns].sort(compareResearchRunsByTimeAsc);
  const researchRunById = new Map(researchRuns.map((run) => [run.research_run_id, run] as const));
  const runContinuityBaselineById = new Map<string, ResearchRunSummary | null>();
  chronologicalResearchRuns.forEach((run, index) => {
    const resumeBaseline = run.resumed_from_research_run_id
      ? researchRunById.get(run.resumed_from_research_run_id) ?? null
      : null;
    runContinuityBaselineById.set(
      run.research_run_id,
      resumeBaseline ?? (index > 0 ? chronologicalResearchRuns[index - 1] ?? null : null)
    );
  });
  const currentResearchRunSummary = researchRuns.find((run) =>
    run.research_run_id === currentResearchRunId) ?? null;
  const researchTimelineMilestones = buildResearchTimelineMilestones(chronologicalResearchRuns);
  const researchTimelinePath = buildResearchTimelinePathSummary(
    chronologicalResearchRuns,
    researchTimelineMilestones,
    currentResearchRunSummary
  );
  const summarizedResearchQuestion = summarizeText(researchQuestion.trim(), 88)
    || "当前还没有填写显式研究问题。";
  const summarizedResearchGoal = summarizeText(researchGoal.trim(), 88)
    || "当前还没有填写显式研究目标。";
  const researchScopeCount = researchScopeSources.length;
  const currentResearchProcessSummary = currentResearchRun?.research_process_summary
    ?? currentResearchRunSummary?.research_process_summary
    ?? null;
  const researchReportStructure = currentResearchRun?.report_structure ?? null;
  const researchClosedLoopState = currentResearchRun?.closed_loop_state ?? null;
  const currentResearchSourceEvidenceSummary = currentResearchProcessSummary?.source_evidence_summary
    ?? null;
  const researchReportIntentContract = readIntentCompletionContract(
    researchReportStructure?.intent_completion_contract
  );
  const researchClosedLoopIntentContract = readIntentCompletionContract(
    researchClosedLoopState?.state_ledger?.intent_completion_contract
  );
  const currentRecoveryStatus = asRecord(researchReportStructure?.recovery_status);
  const currentGuardrailedRows = asResearchRows(currentRecoveryStatus["guardrailed_rows"]);
  const currentRecoveryTargets = readRecoveryTargets(
    researchClosedLoopState?.recovery_targets
    ?? researchReportStructure?.closed_loop_state?.["recovery_targets"]
    ?? researchReportStructure?.recovery_status?.["recovery_targets"]
  );
  const currentIntentCompletionContract = researchReportIntentContract
    ?? researchClosedLoopIntentContract;
  const currentVerifiedFindings = researchReportStructure?.verified_findings ?? [];
  const currentConflictReview = asRecord(researchReportStructure?.conflict_and_counterfactual_review);
  const currentConflictedRows = asResearchRows(currentConflictReview["conflicted_rows"]);
  const currentCounterfactualSummary = currentResearchRun?.counterfactual_summary
    ?? researchClosedLoopState?.counterfactual_summary
    ?? researchReportStructure?.counterfactual_summary
    ?? null;
  const currentFinalAnswer = readResearchFinalAnswer(
    researchReportStructure,
    currentVerifiedFindings,
    currentConflictedRows,
    currentGuardrailedRows,
    currentIntentCompletionContract
  );
  const currentExecutiveSummary = readResearchExecutiveSummary(
    researchReportStructure,
    currentVerifiedFindings,
    currentConflictedRows,
    currentGuardrailedRows,
    currentCounterfactualSummary
  );
  const currentKeyTakeaways = readResearchKeyTakeaways(
    researchReportStructure,
    currentVerifiedFindings
  );
  const currentEvidenceHighlights = readResearchEvidenceHighlights(
    researchReportStructure,
    currentVerifiedFindings,
    currentConflictedRows,
    currentGuardrailedRows
  );
  const currentUncertaintyAndRisks = readResearchUncertaintyAndRisks(
    researchReportStructure,
    currentConflictedRows,
    currentGuardrailedRows,
    currentIntentCompletionContract
  );

  return {
    researchScopeSources,
    filteredResearchRuns,
    runContinuityBaselineById,
    currentResearchRunSummary,
    researchTimelineMilestones,
    researchTimelinePath,
    summarizedResearchQuestion,
    summarizedResearchGoal,
    researchScopeCount,
    currentResearchProcessSummary,
    researchReportStructure,
    researchClosedLoopState,
    currentResearchSourceEvidenceSummary,
    currentRecoveryTargets,
    currentIntentCompletionContract,
    currentVerifiedFindings,
    currentConflictedRows,
    currentFinalAnswer,
    currentExecutiveSummary,
    currentKeyTakeaways,
    currentEvidenceHighlights,
    currentUncertaintyAndRisks
  };
}
