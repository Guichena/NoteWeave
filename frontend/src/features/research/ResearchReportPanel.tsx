import type { ResearchWorkbenchViewProps } from "./buildResearchWorkbenchProps";

export type ResearchReportPanelProps = ResearchWorkbenchViewProps["report"] & {
  isBusy: boolean;
};

export function ResearchReportPanel(props: ResearchReportPanelProps) {
  const {
    currentResearchRun,
    currentResearchCollection,
    formatTimestamp,
    buildReportRecoveryNarrative,
    currentResearchRunSummary,
    researchReportStructure,
    currentRecoveryTargets,
    refreshCurrentResearchRun,
    isBusy,
    currentResearchRunId,
    exportResearchReportMarkdown,
    saveResearchReportAsSource,
    setResearchDetailOpen,
    formatResearchAnswerStatus,
    currentFinalAnswer,
    resultSnapshotTitle,
    resultSnapshotNarrative,
    currentExecutiveSummary,
    currentResearchSourceEvidenceSummary,
    currentVerifiedFindings,
    currentIntentCompletionContract,
    currentKeyTakeaways,
    currentUncertaintyAndRisks,
    buildResearchSourceLabel,
    sourceById,
    summarizeText,
    currentSavedReportSource,
    currentEvidenceHighlights,
    researchClosedLoopState,
    buildArtifactRecoveryNarrative,
    currentRunSummaryRecoveryTargets,
    currentSavedReportSourceAsset,
    currentSavedReportSourceInScope,
    removeResearchSourceFromScope,
    addResearchSourceToScope,
    setFocusedResearchSourceId,
    researchTimelinePath
  } = props;
  return (
          <article className="research-page">
            <p className="section-label">Research Run</p>
            {currentResearchRun ? (
              <>
                <h2>{currentResearchRun.final_report_title || currentResearchRun.question}</h2>
                <div className="wiki-maintenance">
                  <span>{currentResearchRun.research_run_id} · {currentResearchRun.status}</span>
                  <span>Profile {currentResearchRun.profile_key || "DEFAULT"} · 更新时间 {formatTimestamp(currentResearchRun.updated_at)}</span>
                  {currentResearchRun.resumed_from_research_run_id ? (
                    <span>恢复来源：{currentResearchRun.resumed_from_research_run_id} #{currentResearchRun.resumed_from_checkpoint_no ?? "-"}</span>
                  ) : null}
                  {buildReportRecoveryNarrative(
                    currentResearchRunSummary?.recovery_mode || researchReportStructure?.recovery_status.active_recovery_strategy || researchReportStructure?.recovery_mode || "",
                    currentRecoveryTargets
                  ) ? (
                    <small>
                      {buildReportRecoveryNarrative(
                        currentResearchRunSummary?.recovery_mode || researchReportStructure?.recovery_status.active_recovery_strategy || researchReportStructure?.recovery_mode || "",
                        currentRecoveryTargets
                      )}
                    </small>
                  ) : null}
                  <div className="research-inline-actions">
                    <button className="secondary-button" onClick={refreshCurrentResearchRun} disabled={isBusy || !currentResearchRunId}>
                      刷新运行状态
                    </button>
                    <button
                      className="secondary-button"
                      onClick={exportResearchReportMarkdown}
                      disabled={isBusy || !currentResearchRun?.final_report_markdown}
                    >
                      导出 Markdown
                    </button>
                    <button
                      className="secondary-button"
                      onClick={saveResearchReportAsSource}
                      disabled={isBusy || !currentResearchRun?.final_report_markdown}
                    >
                      保存为资料
                    </button>
                    <button
                      className="secondary-button"
                      type="button"
                      onClick={() => {
                        setResearchDetailOpen(true);
                      }}
                      disabled={!currentResearchRun}
                    >
                      打开研究详情
                    </button>
                  </div>
                </div>
                <div className="wiki-summary">
                  <strong>Result Snapshot</strong>
                  <div className="research-deliverable-card">
                    <span className="process-lane-badge">
                      调研成果 · {formatResearchAnswerStatus(currentFinalAnswer.answer_status)}
                    </span>
                    <p>{currentFinalAnswer.answer_text || "当前还没有形成稳定的调研成果摘要。"}</p>
                    <small>{resultSnapshotTitle}</small>
                    <small>{resultSnapshotNarrative}</small>
                  </div>
                  {currentExecutiveSummary.length > 0 ? (
                    <div className="research-evidence-grid">
                      {currentExecutiveSummary.slice(0, 3).map((item, index) => (
                        <div key={`research-executive-${index}`} className="link-card research-evidence-card">
                          <strong>成果摘要 {index + 1}</strong>
                          <span>{item}</span>
                        </div>
                      ))}
                    </div>
                  ) : null}
                  <small>
                    source_scope={currentResearchRun.source_scope.length} ·
                    verified_findings={currentResearchSourceEvidenceSummary?.verified_finding_count ?? currentVerifiedFindings.length} ·
                    citations={currentResearchSourceEvidenceSummary?.citation_count ?? 0}
                  </small>
                  <small>检索过程、网页阅读轨迹和 verifier / checkpoint 审计已收纳到“研究详情”中展示。</small>
                </div>
                {currentResearchCollection ? (
                  <div className="wiki-citations">
                    <strong>Workspace Research Collection</strong>
                    <span>
                      {currentResearchCollection.adopted_sources.length} 个采用来源 · {currentResearchCollection.notes.length} 条研究笔记
                    </span>
                    {currentResearchCollection.adopted_sources.map((source) => (
                      <div key={`${source.source_kind}-${source.source_snapshot_key || source.source_id}`} className="link-card research-evidence-card">
                        <strong>{source.title || source.source_url || source.source_id}</strong>
                        <span>{source.excerpt}</span>
                        <small>{source.source_kind} · citations={source.citation_count} · {source.source_domain || "workspace seed"}</small>
                      </div>
                    ))}
                    {currentResearchCollection.notes.map((note) => (
                      <div key={note.note_key} className="link-card">
                        <strong>{note.note_type} · {note.title}</strong>
                        <span>{note.content}</span>
                      </div>
                    ))}
                  </div>
                ) : null}
                {researchReportStructure ? (
                  <>
                    <div className="wiki-citations">
                      <strong>Research Report</strong>
                      <span>{researchReportStructure.research_question.original_question}</span>
                      <small>
                        goal={researchReportStructure.research_intent.research_goal || currentResearchRun.research_intent.research_goal || "未显式填写"} ·
                        deliverable={researchReportStructure.research_intent.deliverable_format || currentResearchRun.research_intent.deliverable_format || "默认研究报告"}
                      </small>
                      <small>
                        time_range={researchReportStructure.research_intent.time_range || currentResearchRun.research_intent.time_range || "不限"} ·
                        depth={researchReportStructure.research_intent.depth || currentResearchRun.research_intent.depth || "STANDARD"} ·
                        type={researchReportStructure.research_intent.research_type || currentResearchRun.research_intent.research_type || "AUTO"} ·
                        requirements={currentIntentCompletionContract?.satisfied_requirement_count ?? 0}/{currentIntentCompletionContract?.total_requirement_count ?? 0}
                      </small>
                      <small>
                        alignment={researchReportStructure.research_intent_alignment?.status || "-"} ·
                        reason={researchReportStructure.research_intent_alignment?.reason_code || "-"} ·
                        constraints={researchReportStructure.research_intent_alignment?.satisfied_constraint_count ?? 0}/{researchReportStructure.research_intent_alignment?.total_constraint_count ?? 0}
                      </small>
                      {currentKeyTakeaways.length > 0 ? (
                        <div className="research-evidence-grid">
                          {currentKeyTakeaways.slice(0, 3).map((item, index) => (
                            <div key={`research-takeaway-${index}`} className="link-card research-evidence-card">
                              <strong>关键结论 {index + 1}</strong>
                              <span>{item}</span>
                            </div>
                          ))}
                        </div>
                      ) : null}
                      {currentUncertaintyAndRisks.length > 0 ? (
                        currentUncertaintyAndRisks.slice(0, 3).map((item, index) => (
                          <small key={`research-risk-${index}`}>pending={item}</small>
                        ))
                      ) : (
                        <small>pending=none</small>
                      )}
                      {currentResearchRun.final_report_markdown ? (
                        <pre className="research-json">{currentResearchRun.final_report_markdown}</pre>
                      ) : (
                        <small>当前 final report markdown 尚未就绪，可先刷新状态或稍后查看。</small>
                      )}
                    </div>
                    <div className="wiki-citations">
                      <strong>Workspace Sources</strong>
                      <span>
                        {currentResearchRun.source_scope.length > 0
                          ? `${currentResearchRun.source_scope.length} 份显式资料已进入本次研究。`
                          : "当前 run 未附带工作台资料，研究主要依赖问题输入与执行时检索。 "}
                      </span>
                      {currentResearchRun.source_scope.length > 0 ? currentResearchRun.source_scope.slice(0, 6).map((item, index) => (
                        <div key={`research-source-scope-card-${index}`} className="link-card">
                          <strong>{buildResearchSourceLabel(item, sourceById, item.title || "未命名输入")}</strong>
                          <span>{item.summary || "当前未返回资料摘要。"}</span>
                          <small>{item.sample_text ? summarizeText(item.sample_text, 140) : "当前未返回 sample text。"}</small>
                        </div>
                      )) : null}
                      {currentSavedReportSource ? (
                        <small>研究报告写回后会继续回流到资料池，并可重新加入后续研究的 source scope。</small>
                      ) : (
                        <small>当前尚未形成写回资料；完成报告后可显式回流到工作台资料池，进入统一检索工作流。</small>
                      )}
                    </div>
                    <div className="wiki-citations">
                      <strong>Source-Backed Findings</strong>
                      <span>重点展示这份调研成果具体站在哪些来源之上，而不是先讲 verifier 过程。</span>
                      {currentEvidenceHighlights.length > 0 ? currentEvidenceHighlights.map((finding, index) => (
                        <div key={`research-verified-${index}`} className="link-card research-evidence-card">
                          <strong>{buildResearchSourceLabel(finding, sourceById, "未命名来源")}</strong>
                          <span>{summarizeText(finding.claim_text || "暂无 claim")}</span>
                          <span>evidence={finding.evidence_id || "-"} · support={finding.support_level || finding.row_status || "-"}</span>
                          <small>focus={finding.read_focus || "-"} · verifier={finding.verifier_note || "-"}</small>
                        </div>
                      )) : <span>当前还没有形成稳定的来源支撑结论。</span>}
                    </div>
                    <div className="wiki-citations">
                      <strong>Source Provenance</strong>
                      <span>{currentFinalAnswer.source_basis || "当前未返回稳定的来源基础。"}</span>
                      <small>
                        source_scope={currentResearchRun.source_scope.length} ·
                        verified_findings={currentVerifiedFindings.length} ·
                        ledger_rows={researchClosedLoopState?.ledger_row_count ?? currentFinalAnswer.ledger_row_count ?? 0}
                      </small>
                      {currentSavedReportSource ? (
                        <small>
                          当前成果已生成可回流来源：{currentSavedReportSource.title || currentSavedReportSource.source_id}
                        </small>
                      ) : (
                        <small>当前成果还没有形成独立 writeback source。</small>
                      )}
                      <small>{"检索过程、网页阅读轨迹和工具使用过程已收纳到“研究详情 -> 调研过程”中展示。"}</small>
                    </div>
                  </>
                ) : (
                  <div className="wiki-summary">
                    <strong>结构化报告尚未就绪</strong>
                    <span>当前 run 还没有 `report_structure`，可以先查看任务状态或等待回调完成。</span>
                  </div>
                )}
                <div className="wiki-citations">
                  <strong>Writeback Receipt</strong>
                  {currentSavedReportSource ? (
                    <>
                      <span>
                        source={currentSavedReportSource.source_id} · type={currentSavedReportSource.source_type} ·
                        status={currentSavedReportSource.status}
                      </span>
                      <small>
                        parse={currentSavedReportSource.parse_status} · index={currentSavedReportSource.index_status} ·
                        generated_by={currentSavedReportSource.generated_by || "-"}
                      </small>
                      <small>
                        generated_ref_id={currentSavedReportSource.generated_ref_id || "-"} ·
                        title={currentSavedReportSource.title || "未命名研究报告"}
                      </small>
                      <small>
                        {buildArtifactRecoveryNarrative(
                          currentResearchRunSummary?.recovery_mode || researchReportStructure?.recovery_status.active_recovery_strategy || researchReportStructure?.recovery_mode || "",
                          currentRunSummaryRecoveryTargets
                        ) || "产物出口纠偏说明：当前写回凭证未附带额外 recovery narrative。"}
                      </small>
                      {currentSavedReportSourceAsset ? (
                        <small>资料池映射：该报告已出现在当前工作台 source 列表中，可继续参与后续检索与研究。</small>
                      ) : (
                        <small>资料池映射：detail 已记录写回对象，但当前 sources 列表尚未刷新到对应条目。</small>
                      )}
                      <small>
                        {currentSavedReportSourceInScope
                          ? "source scope 状态：当前报告 source 已纳入显式研究输入。"
                          : "source scope 状态：当前报告 source 尚未纳入显式研究输入。"}
                      </small>
                      {currentSavedReportSourceAsset ? (
                        <div className="research-inline-actions">
                          {currentSavedReportSourceInScope ? (
                            <button
                              type="button"
                              className="secondary-button"
                              onClick={() => removeResearchSourceFromScope(currentSavedReportSource.source_id)}
                              disabled={isBusy}
                            >
                              移出当前 source scope
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="secondary-button"
                              onClick={() => addResearchSourceToScope(currentSavedReportSource.source_id)}
                              disabled={isBusy}
                            >
                              加入当前 source scope
                            </button>
                          )}
                          <button
                            type="button"
                            className="secondary-button"
                            onClick={() => setFocusedResearchSourceId(currentSavedReportSource.source_id)}
                            disabled={isBusy}
                          >
                            定位到 source scope
                          </button>
                        </div>
                      ) : null}
                    </>
                  ) : (
                    <>
                      <span>当前 final report 尚未写回资料池。</span>
                      <small>产物出口纠偏说明：只有在显式执行 `save-report-as-source` 后，研究报告才会形成稳定的资料池映射与可检索来源对象。</small>
                    </>
                  )}
                </div>
              </>
            ) : (
              <div className="wiki-summary">
                <div className="empty-panel research-empty-panel">
                  <strong>还没有选中的 Research Run</strong>
                  <p>在左侧填写问题并启动研究，或从历史列表选择一个 run 查看报告与证据。</p>
                </div>
                {researchTimelinePath.stageLabels.length > 0 ? (
                  <>
                    <small>当前路径阶段：{researchTimelinePath.currentStageLabel || "未形成稳定阶段"}</small>
                    <small>{researchTimelinePath.narrative || "当前已有历史路径，可从左侧选择 run 查看具体 continuity。"} </small>
                  </>
                ) : null}
              </div>
            )}
          </article>
  );
}
