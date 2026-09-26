import type {
  TraceAuditTarget,
  TraceCard,
  TraceDetail,
  TraceGroup
} from "./researchDetailViewModel";

type ResearchProcessTraceBrowserProps = {
  groups: TraceGroup[];
  selectedDetail: TraceDetail | null;
  roundFocusMessage: string;
  searchTraceCount: number;
  readTraceCount: number;
  workspaceTraceCount: number;
  isBusy: boolean;
  hasWorkspace: boolean;
  hasCurrentRun: boolean;
  onSelectTrace: (key: string) => void;
  onOpenWorkbench: () => void;
  onSetSourceScope: (sourceId: string, inScope: boolean) => void;
  onFocusSource: (sourceId: string) => void;
  onOpenCheckpoint: (checkpointNo: number) => void;
  onViewAudit: (target: TraceAuditTarget) => void;
};

export function ResearchProcessTraceBrowser({
  groups,
  selectedDetail,
  roundFocusMessage,
  searchTraceCount,
  readTraceCount,
  workspaceTraceCount,
  isBusy,
  hasWorkspace,
  hasCurrentRun,
  onSelectTrace,
  onOpenWorkbench,
  onSetSourceScope,
  onFocusSource,
  onOpenCheckpoint,
  onViewAudit
}: ResearchProcessTraceBrowserProps) {
  return (
    <div className="wiki-citations">
      <div className="research-process-browser-header">
        <div>
          <small className="process-lane-badge">过程回放</small>
          <strong>网页与工具轨迹</strong>
          <span>这一层按“先搜、再读、最后回流到工作台”来展示当前可见的研究过程。</span>
          {roundFocusMessage ? <small>{roundFocusMessage}</small> : null}
        </div>
        <div className="research-process-browser-stats">
          <span>{searchTraceCount} 条搜索线索</span>
          <span>{readTraceCount} 条阅读轨迹</span>
          <span>{workspaceTraceCount} 条来源回流</span>
        </div>
      </div>
      <div className="research-process-browser-layout">
        <div className="checkpoint-structured-grid">
          {groups.map((group) => (
            <div key={group.title} className={`task-card research-process-group tone-${group.tone}`}>
              <div className="research-process-group-header">
                <div className="research-process-group-title">
                  <small className="process-lane-badge">{group.stageLabel}</small>
                  <strong>{group.title}</strong>
                  <span>{group.description}</span>
                  {group.roundMessage ? <small>{group.roundMessage}</small> : null}
                </div>
                <span className={`research-process-count tone-${group.tone}`}>{group.entries.length} 条</span>
              </div>
              {group.entries.length > 0 ? group.entries.map((entry) => (
                <div
                  id={entry.anchorId}
                  key={entry.key}
                  className={[
                    "link-card",
                    "research-process-trace-card",
                    `tone-${group.tone}`,
                    group.roundMessage.startsWith("当前展示") ? "process-round-linked-card" : "",
                    entry.focused ? "audit-focus-card" : "",
                    entry.selected ? "process-trace-selected-card" : ""
                  ].filter(Boolean).join(" ")}
                  role="button"
                  tabIndex={0}
                  onClick={() => onSelectTrace(entry.key)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      onSelectTrace(entry.key);
                    }
                  }}
                >
                  <strong>{entry.traceType}</strong>
                  <span>{entry.traceMessage}</span>
                  {entry.roundNo != null ? <small className="process-round-link-chip">命中 round {entry.roundNo}</small> : null}
                  <small>{entry.narrative}</small>
                  <small>记录时间：{entry.createdAt}</small>
                  <small>{entry.checkpointNarrative}</small>
                  {entry.focused ? <small className="audit-focus-chip">{entry.auditFocusNarrative}</small> : null}
                  <small>{entry.recoveryNarrative}</small>
                  {entry.sourceNarrative ? <small>{entry.sourceNarrative}</small> : null}
                  {entry.outcomeNarrative ? <small>{entry.outcomeNarrative}</small> : null}
                  {(entry.kind === "read" && entry.primaryUrl) || entry.kind === "workspace" ? (
                    <div className="research-inline-actions">
                      {entry.kind === "read" && entry.primaryUrl ? <button type="button" className="secondary-button" onClick={() => window.open(entry.primaryUrl, "_blank", "noopener,noreferrer")}>打开网页来源</button> : null}
                      {entry.kind === "workspace" ? <button type="button" className="secondary-button" onClick={onOpenWorkbench} disabled={isBusy || !hasWorkspace}>打开研究工作台</button> : null}
                      {entry.kind === "workspace" && entry.primarySourceId ? <button type="button" className="secondary-button" onClick={() => onFocusSource(entry.primarySourceId)} disabled={isBusy}>定位到 source scope</button> : null}
                      {entry.kind === "workspace" && entry.hasPrimarySourceAsset ? <button type="button" className="secondary-button" onClick={() => onSetSourceScope(entry.primarySourceId, entry.primarySourceInScope)} disabled={isBusy}>{entry.primarySourceInScope ? "移出当前 source scope" : "加入当前 source scope"}</button> : null}
                    </div>
                  ) : null}
                  {entry.checkpointNo != null ? <button type="button" className="secondary-button" onClick={() => onOpenCheckpoint(entry.checkpointNo!)} disabled={isBusy || !hasCurrentRun}>打开 checkpoint #{entry.checkpointNo}</button> : null}
                </div>
              )) : <span>{group.empty}</span>}
            </div>
          ))}
        </div>
        <div className={`task-card research-process-detail-panel tone-${selectedDetail?.tone || "neutral"}`}>
          {selectedDetail ? (
            <>
              <div className="research-process-detail-header">
                <div className="research-process-detail-title">
                  <small className="process-lane-badge">{selectedDetail.stageLabel}</small>
                  <strong>{selectedDetail.traceType}</strong>
                  <span>{selectedDetail.traceMessage}</span>
                </div>
                <div className="research-process-detail-meta">
                  <span className={`research-process-count tone-${selectedDetail.tone}`}>{selectedDetail.title}</span>
                  {selectedDetail.roundNo != null ? <span className="process-round-link-chip">round {selectedDetail.roundNo}</span> : null}
                </div>
              </div>
              <div className="research-process-detail-summary">
                <small>{selectedDetail.narrative}</small><small>记录时间：{selectedDetail.createdAt}</small><small>{selectedDetail.checkpointNarrative}</small><small>{selectedDetail.recoveryNarrative}</small>
              </div>
              <div className="research-process-detail-grid">
                <div className="research-process-detail-metric"><small>网页 / 来源</small><strong>{selectedDetail.primaryUrlLabel}</strong><span>{selectedDetail.sourceNarrative || "当前仍以 trace payload 为主。"}</span></div>
                <div className="research-process-detail-metric"><small>读取方式</small><strong>{selectedDetail.provider || selectedDetail.adapter || "未显式暴露"}</strong><span>{selectedDetail.provider ? `provider=${selectedDetail.provider}` : "provider=-"} · {selectedDetail.adapter ? `adapter=${selectedDetail.adapter}` : "adapter=-"}</span></div>
                <div className="research-process-detail-metric"><small>快照状态</small><strong>{selectedDetail.snapshotStatus || "未显式暴露"}</strong><span>{selectedDetail.checkpointNo != null ? `checkpoint #${selectedDetail.checkpointNo}` : "当前仍停留在运行态 trace。"}</span></div>
                <div className="research-process-detail-metric"><small>回流状态</small><strong>{selectedDetail.hasPrimarySourceAsset ? (selectedDetail.primarySourceInScope ? "已进入 source scope" : "已回流但未纳入") : "当前不是显式工作台来源"}</strong><span>{selectedDetail.primarySourceId ? `source=${selectedDetail.primarySourceId}` : "当前 trace 没有显式 source_id。"}</span></div>
              </div>
              {selectedDetail.querySamples.length || selectedDetail.searchAngles.length || selectedDetail.readFocuses.length ? <div className="research-process-detail-sections">
                {selectedDetail.querySamples.length ? <div className="research-process-detail-section"><small className="process-lane-badge">Query</small><span>{selectedDetail.querySamples.join(" / ")}</span></div> : null}
                {selectedDetail.searchAngles.length ? <div className="research-process-detail-section"><small className="process-lane-badge">Search Angle</small><span>{selectedDetail.searchAngles.join(" / ")}</span></div> : null}
                {selectedDetail.readFocuses.length ? <div className="research-process-detail-section"><small className="process-lane-badge">Read Focus</small><span>{selectedDetail.readFocuses.join(" / ")}</span></div> : null}
              </div> : null}
              {selectedDetail.sourceNarrative ? <div className="research-process-detail-section"><small className="process-lane-badge">来源焦点</small><span>{selectedDetail.sourceNarrative}</span></div> : null}
              {selectedDetail.outcomeNarrative ? <div className="research-process-detail-section"><small className="process-lane-badge">这一步的结果</small><span>{selectedDetail.outcomeNarrative}</span></div> : null}
              <div className="research-inline-actions research-process-detail-actions">
                {selectedDetail.primaryUrl ? <button type="button" className="secondary-button" onClick={() => window.open(selectedDetail.primaryUrl, "_blank", "noopener,noreferrer")}>打开网页来源</button> : null}
                {selectedDetail.kind === "workspace" ? <button type="button" className="secondary-button" onClick={onOpenWorkbench} disabled={isBusy || !hasWorkspace}>打开研究工作台</button> : null}
                {selectedDetail.primarySourceId ? <button type="button" className="secondary-button" onClick={() => onFocusSource(selectedDetail.primarySourceId)} disabled={isBusy}>定位到 source scope</button> : null}
                {selectedDetail.hasPrimarySourceAsset ? <button type="button" className="secondary-button" onClick={() => onSetSourceScope(selectedDetail.primarySourceId, selectedDetail.primarySourceInScope)} disabled={isBusy}>{selectedDetail.primarySourceInScope ? "移出当前 source scope" : "加入当前 source scope"}</button> : null}
                {selectedDetail.checkpointNo != null ? <button type="button" className="secondary-button" onClick={() => onOpenCheckpoint(selectedDetail.checkpointNo!)} disabled={isBusy || !hasCurrentRun}>打开 checkpoint #{selectedDetail.checkpointNo}</button> : null}
                <button type="button" className="secondary-button" onClick={() => onViewAudit(selectedDetail.auditTarget)}>查看这条审计</button>
              </div>
            </>
          ) : <><small className="process-lane-badge">当前动作详情</small><strong>还没有可展示的网页/工具动作</strong><span>当前过程层尚未命中可见 trace。后续当搜索、阅读或工作台回流事件到达后，这里会自动出现对应详情。</span></>}
        </div>
      </div>
    </div>
  );
}
