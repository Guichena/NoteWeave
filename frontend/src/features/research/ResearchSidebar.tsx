import { type SourceAsset } from "../sources/model";
import {
  type ResearchHistoryFilter,
  type ResearchRecoveryTargets,
  type ResearchRunSummary,
  type ResearchTimelineMilestone,
  type ResearchTimelinePathSummary,
  type SignalChip,
  type SignalTone
} from "./model";

type ResearchSidebarProps = Record<string, any> & {
  sources: SourceAsset[];
  researchScopeSources: SourceAsset[];
  researchRuns: ResearchRunSummary[];
  filteredResearchRuns: ResearchRunSummary[];
  researchHistoryFilter: ResearchHistoryFilter;
  researchTimelineMilestones: ResearchTimelineMilestone[];
  researchTimelinePath: ResearchTimelinePathSummary;
  currentResearchRunSummary: ResearchRunSummary | null;
  currentResearchRunId: string;
  buildRunSignalChips: (run: ResearchRunSummary) => SignalChip[];
  buildRunPrimaryTone: (run: ResearchRunSummary) => SignalTone;
  readRecoveryTargets: (value: unknown) => ResearchRecoveryTargets | null;
};

export function ResearchSidebar(props: ResearchSidebarProps) {
  const {
    summarizedResearchQuestion,
    summarizedResearchGoal,
    researchDeliverableFormat,
    researchProfile,
    researchDepth,
    researchType,
    researchScopeCount,
    researchQuestion,
    setResearchQuestion,
    researchGoal,
    setResearchGoal,
    setResearchProfile,
    setResearchDeliverableFormat,
    researchTimeRange,
    setResearchTimeRange,
    setResearchDepth,
    setResearchType,
    researchConstraintsText,
    setResearchConstraintsText,
    startDeepResearch,
    isBusy,
    workspace,
    loadResearchRunHistory,
    sources,
    selectedResearchSourceIds,
    currentSavedReportSource,
    focusedResearchSourceId,
    toggleResearchScope,
    setFocusedResearchSourceId,
    buildSourceOriginBadge,
    researchScopeSources,
    currentSavedReportSourceInScope,
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
          <aside className="research-index">
            <p className="section-label">Deep Research</p>
            <h2>独立研究工作台</h2>
            <p className="phase-note">
              Deep Research 已提升为工作台内独立视图。Deep Research 只读取你在这里显式填写的问题和显式勾选的资料范围，不从聊天上下文隐式升级。
            </p>
            <div className="task-card">
              <strong>Snapshot Research Question</strong>
              <span>{summarizedResearchQuestion}</span>
              <small>goal={summarizedResearchGoal}</small>
              <small>deliverable={researchDeliverableFormat.trim() || "Evidence-backed research report"}</small>
              <small>profile={researchProfile.trim() || "default"} · depth={researchDepth.trim() || "STANDARD"} · type={researchType.trim() || "AUTO"} · source_scope={researchScopeCount}</small>
              <small>
                {researchScopeCount > 0
                  ? `当前将携带 ${researchScopeCount} 份显式资料进入 Deep Research。`
                  : "当前将以显式问题直接发起 Deep Research，不默认继承工作台资料。"}
              </small>
            </div>
            <label className="rail-field">
              <span>研究问题</span>
              <textarea value={researchQuestion} onChange={(event) => setResearchQuestion(event.target.value)} rows={5} />
            </label>
            <label className="rail-field">
              <span>研究目标</span>
              <textarea value={researchGoal} onChange={(event) => setResearchGoal(event.target.value)} rows={3} />
            </label>
            <label className="rail-field">
              <span>研究 Profile</span>
              <input value={researchProfile} onChange={(event) => setResearchProfile(event.target.value)} placeholder="default" />
            </label>
            <label className="rail-field">
              <span>交付格式</span>
              <input value={researchDeliverableFormat} onChange={(event) => setResearchDeliverableFormat(event.target.value)} placeholder="Evidence-backed research report" />
            </label>
            <label className="rail-field">
              <span>时间范围</span>
              <input value={researchTimeRange} onChange={(event) => setResearchTimeRange(event.target.value)} placeholder="例如：2024-2026 / 当前季度 / 不限" />
            </label>
            <label className="rail-field">
              <span>研究深度</span>
              <select value={researchDepth} onChange={(event) => setResearchDepth(event.target.value)}>
                <option value="QUICK">QUICK</option>
                <option value="STANDARD">STANDARD</option>
                <option value="DEEP">DEEP</option>
              </select>
            </label>
            <label className="rail-field">
              <span>研究类型</span>
              <select value={researchType} onChange={(event) => setResearchType(event.target.value)}>
                <option value="AUTO">AUTO</option>
                <option value="PAPER_SURVEY">PAPER_SURVEY</option>
                <option value="GITHUB_REPO_ANALYSIS">GITHUB_REPO_ANALYSIS</option>
                <option value="PRODUCT_COMPARISON">PRODUCT_COMPARISON</option>
                <option value="TECH_SOLUTION_COMPARISON">TECH_SOLUTION_COMPARISON</option>
                <option value="CONCEPT_RESEARCH">CONCEPT_RESEARCH</option>
              </select>
            </label>
            <label className="rail-field">
              <span>显式约束</span>
              <textarea value={researchConstraintsText} onChange={(event) => setResearchConstraintsText(event.target.value)} rows={4} />
            </label>
            <div className="research-inline-actions">
              <button onClick={startDeepResearch} disabled={isBusy || !workspace}>
                启动 Deep Research
              </button>
              <button className="secondary-button" onClick={() => void loadResearchRunHistory()} disabled={isBusy || !workspace}>
                刷新历史
              </button>
            </div>
            <div className="task-card">
              <strong>Launch Contract</strong>
              <span>独立入口只会提交这里显式填写的问题、目标、交付格式、研究类型、深度与 source scope。</span>
              <small>question_ready={researchQuestion.trim() ? "YES" : "NO"} · goal_ready={researchGoal.trim() ? "YES" : "NO"}</small>
              <small>constraints={(researchConstraintsText.trim() && researchConstraintsText.split(/\n+/).filter(Boolean).length) || 0} · time_range={researchTimeRange.trim() || "不限"}</small>
              <small>entry gate：不从 Ask / Note / Wiki 上下文隐式升级。</small>
            </div>
            <div className="wiki-maintenance">
              <strong>显式资料范围</strong>
              {sources.length > 0 ? (
                <>
                  <div className="research-scope">
                    {sources.map((source) => (
                      <button
                        key={`research-scope-workbench-${source.source_id}`}
                        id={`research-source-scope-${source.source_id}`}
                        type="button"
                        className={[
                          selectedResearchSourceIds.includes(source.source_id) ? "active" : "",
                          currentSavedReportSource?.source_id === source.source_id ? "generated-source" : "",
                          focusedResearchSourceId === source.source_id ? "scope-focus" : ""
                        ].filter(Boolean).join(" ")}
                        onClick={() => {
                          toggleResearchScope(source.source_id);
                          setFocusedResearchSourceId(source.source_id);
                        }}
                        disabled={isBusy}
                      >
                        {source.title}
                        {currentSavedReportSource?.source_id === source.source_id ? " · 当前报告" : ""}
                        {source.generated_by === "research_agent" && currentSavedReportSource?.source_id !== source.source_id
                          ? ` · ${buildSourceOriginBadge(source)}`
                          : ""}
                      </button>
                    ))}
                  </div>
                  <span>
                    {researchScopeSources.length > 0
                      ? `当前 source scope：${researchScopeSources.map((source) => source.title).join(" / ")}`
                      : "当前未选择显式 source scope。"}
                  </span>
                  {currentSavedReportSource ? (
                    <small>
                      {currentSavedReportSourceInScope
                        ? "闭环回流：当前报告 source 已纳入显式 source scope。"
                        : "闭环回流：当前报告 source 已写回资料池，但尚未纳入显式 source scope。"}
                    </small>
                  ) : null}
                </>
              ) : (
                <span>当前工作台还没有 READY 资料。你仍然可以直接发起 Deep Research。</span>
              )}
            </div>
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
              }) : <p className="empty-state">当前筛选下没有匹配的 Deep Research run。</p>}
            </div>
          </aside>
  );
}
