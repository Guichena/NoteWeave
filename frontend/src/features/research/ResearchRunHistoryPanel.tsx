import type { ResearchHistoryFilter } from "./model";
import type { ResearchSidebarProps } from "./ResearchSidebar";

type ResearchRunHistoryPanelProps = Pick<
  ResearchSidebarProps,
  | "isBusy"
  | "researchRuns"
  | "filteredResearchRuns"
  | "researchHistoryFilter"
  | "setResearchHistoryFilter"
  | "researchHistoryFilterLabel"
  | "researchTimelineMilestones"
  | "runContinuityBaselineById"
  | "buildRunContinuityBaselineLabel"
  | "buildRunContinuityNarrative"
  | "currentResearchRunSummary"
  | "sameStageLabel"
  | "timelineStageLabel"
  | "currentRunStageLabelFromSummary"
  | "openResearchRunHistoryItem"
  | "summarizeText"
  | "buildRunCheckpointNarrative"
  | "formatTimestamp"
  | "researchTimelinePath"
  | "buildRunSignalChips"
  | "buildRunPrimaryTone"
  | "readRecoveryTargets"
  | "currentResearchRunId"
  | "formatRecoveryTargetLabels"
  | "formatRecoveryTargetColumns"
  | "buildCurrentRecoveryNarrative"
>;

export function ResearchRunHistoryPanel(props: ResearchRunHistoryPanelProps) {
  const {
    isBusy,
    researchRuns,
    filteredResearchRuns,
    researchHistoryFilter,
    setResearchHistoryFilter,
    researchHistoryFilterLabel,
    researchTimelineMilestones,
    runContinuityBaselineById,
    buildRunContinuityBaselineLabel,
    buildRunContinuityNarrative,
    currentResearchRunSummary,
    sameStageLabel,
    timelineStageLabel,
    currentRunStageLabelFromSummary,
    openResearchRunHistoryItem,
    summarizeText,
    buildRunCheckpointNarrative,
    formatTimestamp,
    researchTimelinePath,
    buildRunSignalChips,
    buildRunPrimaryTone,
    readRecoveryTargets,
    currentResearchRunId,
    formatRecoveryTargetLabels,
    formatRecoveryTargetColumns,
    buildCurrentRecoveryNarrative
  } = props;

  return (
    <>
      <div className="wiki-maintenance">
        <strong>Research Run 历史</strong>
        <span>共 {researchRuns.length} 个 run，恢复任务也会保留在这里，便于审计和续跑。</span>
        <span>当前筛选：{filteredResearchRuns.length} / {researchRuns.length}</span>
      </div>
      <div className="research-filter-row">
        {(["ALL", "RECOVERY", "CONFLICT", "STABLE", "RESUMED"] as ResearchHistoryFilter[]).map((filter) => (
          <button
            key={`research-history-filter-${filter}`}
            type="button"
            className={researchHistoryFilter === filter ? "filter-pill active" : "filter-pill"}
            onClick={() => setResearchHistoryFilter(filter)}
            disabled={isBusy}
          >
            {researchHistoryFilterLabel(filter)}
          </button>
        ))}
      </div>
      <div className="wiki-maintenance">
        <strong>关键转折时间线</strong>
        {researchTimelineMilestones.length > 0 ? researchTimelineMilestones.map((milestone) => {
          const milestoneBaseline = runContinuityBaselineById.get(milestone.run.research_run_id) ?? null;
          const milestoneBaselineLabel = buildRunContinuityBaselineLabel(milestoneBaseline, milestone.run);
          const milestoneContinuityNarrative = buildRunContinuityNarrative(milestoneBaseline, milestone.run);
          const stageMatch = currentResearchRunSummary
            ? sameStageLabel(timelineStageLabel(milestone), currentRunStageLabelFromSummary(currentResearchRunSummary))
            : false;
          const milestoneClassName = [
            "research-milestone-card",
            `tone-${milestone.tone}`,
            stageMatch ? "current-stage" : ""
          ].filter(Boolean).join(" ");
          return (
            <button
            key={`research-milestone-${milestone.key}`}
              type="button"
              className={milestoneClassName}
              onClick={() => void openResearchRunHistoryItem(milestone.run)}
              disabled={isBusy}
            >
              <strong>{milestone.label}</strong>
              <span>{milestone.run.final_report_title || summarizeText(milestone.run.question, 32)}</span>
              <small>{milestone.description}</small>
              <small>{buildRunCheckpointNarrative(milestone.run)}</small>
              {milestoneBaselineLabel ? (
                <small>continuity baseline={milestoneBaselineLabel}</small>
              ) : null}
              {milestoneContinuityNarrative ? (
                <small>{milestoneContinuityNarrative}</small>
              ) : null}
              {stageMatch ? <small>匹配当前 run 阶段</small> : null}
              <small>{formatTimestamp(milestone.run.updated_at)}</small>
              <div className="signal-chip-row">
                {milestone.chips.map((chip, index) => (
                  <span key={`milestone-chip-${milestone.key}-${index}`} className={`signal-chip tone-${chip.tone}`}>
                    {chip.label}: {chip.value}
                  </span>
                ))}
              </div>
            </button>
          );
        }) : <span>当前还没有足够的 run 可提炼关键转折。</span>}
      </div>
      <div className="wiki-maintenance">
        <strong>闭环路径摘要</strong>
        {researchTimelinePath.stageLabels.length > 0 ? (
          <>
            <span>{researchTimelinePath.stageLabels.join(" -> ")}</span>
            <small>当前阶段：{researchTimelinePath.currentStageLabel}</small>
            <small>
              当前 run 所在阶段：{researchTimelinePath.currentRunStageLabel || "未选择 run"}
              {" · "}
              {researchTimelinePath.currentRunAlignedWithPath ? "与全局路径当前阶段一致" : "当前查看的是历史阶段或不同阶段"}
            </small>
            {currentResearchRunSummary ? (
              <small>
                {buildRunContinuityNarrative(
                  runContinuityBaselineById.get(currentResearchRunSummary.research_run_id) ?? null,
                  currentResearchRunSummary
                ) || "当前 run 尚无可用 continuity baseline。"}
              </small>
            ) : null}
            <small>{researchTimelinePath.narrative}</small>
          </>
        ) : (
          <span>当前还没有足够的 run 可形成闭环路径摘要。</span>
        )}
      </div>
      <div className="research-history-list">
        {filteredResearchRuns.length > 0 ? filteredResearchRuns.map((run) => {
          const runContinuityBaseline = runContinuityBaselineById.get(run.research_run_id) ?? null;
          const runContinuityBaselineLabel = buildRunContinuityBaselineLabel(runContinuityBaseline, run);
          const runContinuityNarrative = buildRunContinuityNarrative(runContinuityBaseline, run);
          const signalChips = buildRunSignalChips(run);
          const signalTone = buildRunPrimaryTone(run);
          const runRecoveryTargets = readRecoveryTargets(run.recovery_targets);
          const stageMatch = currentResearchRunSummary
            ? sameStageLabel(currentRunStageLabelFromSummary(run), currentRunStageLabelFromSummary(currentResearchRunSummary))
            : false;
          const cardClassName = [
            "research-history-card",
            run.research_run_id === currentResearchRunId ? "active" : "",
            stageMatch ? "current-stage" : "",
            signalTone !== "neutral" ? `signal-${signalTone}` : ""
          ].filter(Boolean).join(" ");
          return (
            <button
              key={`research-run-workbench-${run.research_run_id}`}
              type="button"
              className={cardClassName}
              onClick={() => void openResearchRunHistoryItem(run)}
              disabled={isBusy}
            >
              <strong>{run.final_report_title || summarizeText(run.question, 36)}</strong>
              <span>{run.status} · {run.profile_key || "DEFAULT"} · {formatTimestamp(run.updated_at)}</span>
              <small>
                local={run.local_verifier_status || "-"} ·
                branch={run.active_branch_id || "-"} · global={run.global_verifier_decision || "-"} · loop={run.final_loop_decision || "-"}
              </small>
              <small>
                intent={run.research_intent_alignment_status || "-"} ·
                intent_reason={run.research_intent_alignment_reason || "-"} ·
                constraints={run.intent_satisfied_constraint_count ?? 0}/{run.intent_constraint_count ?? 0} ·
                requirements={run.intent_satisfied_requirement_count ?? 0}/{run.intent_requirement_count ?? 0}
              </small>
              {runRecoveryTargets ? (
                <small>
                  recovery={formatRecoveryTargetLabels(runRecoveryTargets) || "-"} ·
                  columns={formatRecoveryTargetColumns(runRecoveryTargets) || "-"}
                </small>
              ) : null}
              <small>{buildRunCheckpointNarrative(run)}</small>
              {buildCurrentRecoveryNarrative(run.recovery_mode || "", runRecoveryTargets) ? (
                <small>{buildCurrentRecoveryNarrative(run.recovery_mode || "", runRecoveryTargets)}</small>
              ) : null}
              {runContinuityBaselineLabel ? (
                <small>continuity baseline={runContinuityBaselineLabel}</small>
              ) : null}
              {runContinuityNarrative ? (
                <small>{runContinuityNarrative}</small>
              ) : null}
              <div className="signal-chip-row">
                {stageMatch ? (
                  <span className="signal-chip tone-neutral">当前阶段命中: {currentRunStageLabelFromSummary(run)}</span>
                ) : null}
                {signalChips.map((chip, index) => (
                  <span key={`run-signal-chip-${run.research_run_id}-${index}`} className={`signal-chip tone-${chip.tone}`}>
                    {chip.label}: {chip.value}
                  </span>
                ))}
              </div>
              <small>
                rows={run.ledger_row_count} · verified={run.verified_row_count} · conflicted={run.conflicted_row_count}
              </small>
              <small>
                source_scope={run.source_scope_count} · checkpoints={run.checkpoint_count} · cf-branches={run.counterfactual_summary?.counterfactual_branch_count ?? 0}
              </small>
              {run.resumed_from_research_run_id ? (
                <small>resumed from {run.resumed_from_research_run_id} #{run.resumed_from_checkpoint_no ?? "-"}</small>
              ) : null}
            </button>
          );
        }) : (
          <div className="empty-panel">
            <strong>没有匹配的 run</strong>
            <p>调整历史筛选，或新建一次 Deep Research。</p>
          </div>
        )}
      </div>
    </>
  );
}
