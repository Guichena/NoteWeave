import {
  type ResearchHistoryFilter,
  type ResearchRecoveryTargets,
  type ResearchRunSummary,
  type ResearchTimelineMilestone,
  type ResearchTimelinePathSummary,
  type SignalChip,
  type SignalTone
} from "../model";
import { normalizeSignalValue } from "./primitives";
import {
  buildRecoveryTargetNarrativeFragment,
  compareResearchRunsByTimeAsc,
  readRecoveryTargets,
  summarizeRecoveryTargetFocus,
  summarizeRecoveryTargetTypes
} from "./core";

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
  const hasRecovery = Boolean(run.recovery_mode)
    || (run.counterfactual_summary?.counterfactual_branch_count ?? 0) > 0
    || reasons.some(isRecoverySignal);
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
  return label === "回稳" ? "稳定" : label;
}

export function buildRunSignalChips(run: ResearchRunSummary): SignalChip[] {
  const chips: SignalChip[] = [];
  const recoveryTargets: ResearchRecoveryTargets | null = readRecoveryTargets(run.recovery_targets);
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
  return Boolean(run.recovery_mode)
    || (run.counterfactual_summary?.counterfactual_branch_count ?? 0) > 0
    || buildRunPrimaryTone(run) === "recovery";
}

export function isStableRun(run: ResearchRunSummary) {
  return buildRunPrimaryTone(run) === "stable";
}

export function isResumedRun(run: ResearchRunSummary) {
  return Boolean(run.resumed_from_research_run_id);
}
