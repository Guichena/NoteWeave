type SavedReportSource = {
  sourceId: string;
  title: string;
  status: string;
  indexStatus: string;
  inScope: boolean;
  existsInWorkspace: boolean;
};

type ResearchProcessStageLanesProps = {
  searchSummary: string;
  searchScopeMessage: string;
  searchAngleMessage: string;
  runtimeSnapshot: string;
  readSummary: string;
  readSourceMessage: string;
  readScopeMessage: string;
  taskRuntimeFocusNarrative: string;
  workspaceScopeMessage: string;
  savedReportRoundNarrative: string;
  savedReportSource: SavedReportSource | null;
  isBusy: boolean;
  hasWorkspace: boolean;
  onOpenWorkbench: () => void;
  onSetSourceScope: (sourceId: string, inScope: boolean) => void;
  onFocusSource: (sourceId: string) => void;
};

export function ResearchProcessStageLanes({
  searchSummary,
  searchScopeMessage,
  searchAngleMessage,
  runtimeSnapshot,
  readSummary,
  readSourceMessage,
  readScopeMessage,
  taskRuntimeFocusNarrative,
  workspaceScopeMessage,
  savedReportRoundNarrative,
  savedReportSource,
  isBusy,
  hasWorkspace,
  onOpenWorkbench,
  onSetSourceScope,
  onFocusSource
}: ResearchProcessStageLanesProps) {
  return (
    <div className="checkpoint-structured-grid process-lane-grid">
      <div className="link-card process-lane-card tone-search">
        <small className="process-lane-badge">阶段 1 · 搜索入口</small>
        <strong>这次先搜了什么</strong>
        <span>{searchSummary}</span>
        <small>{searchScopeMessage}</small>
        <small>{searchAngleMessage}</small>
        {runtimeSnapshot ? <small>{runtimeSnapshot}</small> : null}
      </div>
      <div className="link-card process-lane-card tone-read">
        <small className="process-lane-badge">阶段 2 · 页面阅读</small>
        <strong>这次重点读了什么</strong>
        <span>{readSummary}</span>
        <small>{readSourceMessage}</small>
        {readScopeMessage ? <small>{readScopeMessage}</small> : null}
        {taskRuntimeFocusNarrative ? <small>{taskRuntimeFocusNarrative}</small> : null}
      </div>
      <div className="link-card process-lane-card tone-workspace">
        <small className="process-lane-badge">阶段 3 · 回流工作台</small>
        <strong>报告如何回流到工作台</strong>
        {workspaceScopeMessage ? <small>{workspaceScopeMessage}</small> : null}
        {savedReportSource ? (
          <>
            <span>已写回资料池：{savedReportSource.title || savedReportSource.sourceId}</span>
            <small>source={savedReportSource.sourceId} · status={savedReportSource.status} · index={savedReportSource.indexStatus}</small>
            {savedReportRoundNarrative ? <small>当前轮来源：{savedReportRoundNarrative}</small> : null}
            <small>{savedReportSource.inScope ? "当前报告已经回流到 source scope，可继续参与下一轮 Deep Research。" : "当前报告已写回资料池，但还没有加入当前 source scope。"}</small>
            <div className="research-inline-actions">
              <button type="button" className="secondary-button" onClick={onOpenWorkbench} disabled={isBusy || !hasWorkspace}>打开研究工作台</button>
              {savedReportSource.existsInWorkspace ? (
                <>
                  <button type="button" className="secondary-button" onClick={() => onSetSourceScope(savedReportSource.sourceId, savedReportSource.inScope)} disabled={isBusy}>
                    {savedReportSource.inScope ? "移出当前 source scope" : "加入当前 source scope"}
                  </button>
                  <button type="button" className="secondary-button" onClick={() => onFocusSource(savedReportSource.sourceId)} disabled={isBusy}>定位到 source scope</button>
                </>
              ) : null}
            </div>
          </>
        ) : (
          <>
            <span>当前报告还没有回流到资料池。</span>
            <small>当研究收敛后，这里会展示写回后的 source、索引状态，以及是否重新进入工作台资料范围。</small>
          </>
        )}
      </div>
    </div>
  );
}
