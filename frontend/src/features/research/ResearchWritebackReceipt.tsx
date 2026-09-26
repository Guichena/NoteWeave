import type { ResearchReportPanelProps } from "./ResearchReportPanel";

type ResearchWritebackReceiptProps = Pick<
  ResearchReportPanelProps,
  | "currentSavedReportSource"
  | "buildArtifactRecoveryNarrative"
  | "currentResearchRunSummary"
  | "researchReportStructure"
  | "currentRunSummaryRecoveryTargets"
  | "currentSavedReportSourceAsset"
  | "currentSavedReportSourceInScope"
  | "removeResearchSourceFromScope"
  | "addResearchSourceToScope"
  | "setFocusedResearchSourceId"
  | "isBusy"
>;

export function ResearchWritebackReceipt(props: ResearchWritebackReceiptProps) {
  const {
    currentSavedReportSource,
    buildArtifactRecoveryNarrative,
    currentResearchRunSummary,
    researchReportStructure,
    currentRunSummaryRecoveryTargets,
    currentSavedReportSourceAsset,
    currentSavedReportSourceInScope,
    removeResearchSourceFromScope,
    addResearchSourceToScope,
    setFocusedResearchSourceId,
    isBusy
  } = props;

  return (
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

  );
}
