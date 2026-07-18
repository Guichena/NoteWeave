type ResearchRoundTimelineItem = {
  roundNo: number;
  branchId: string;
  decision: string;
  reason: string;
  searchHitCount: string;
  readWindowCount: string;
  evidenceCardCount: string;
  sourceNarrative: string;
  deltaNarrative: string;
  outcomeNarrative: string;
};

type ResearchRoundTimelineProps = {
  rounds: ResearchRoundTimelineItem[];
  activeRoundNo: number | null;
  narrativeMode: "global" | "round";
  onSelectRound: (roundNo: number) => void;
  onSetNarrativeMode: (mode: "global" | "round") => void;
  onViewAudit: (roundNo: number, branchId: string) => void;
};

export function ResearchRoundTimeline({
  rounds,
  activeRoundNo,
  narrativeMode,
  onSelectRound,
  onSetNarrativeMode,
  onViewAudit
}: ResearchRoundTimelineProps) {
  const activeRound = rounds.find((round) => round.roundNo === activeRoundNo) ?? null;

  return (
    <div className="task-card research-round-timeline">
      <div className="research-round-timeline-header">
        <div className="research-process-group-title">
          <small className="process-lane-badge">轮次回放</small>
          <strong>最近几轮研究推进</strong>
          <span>按轮次回看这次 Deep Research 是如何逐步搜索、阅读、纠偏并收敛的。</span>
        </div>
        <div className="research-round-timeline-toolbar">
          <span className="research-process-count tone-neutral">{rounds.length} 轮</span>
          <div className="research-round-view-switch">
            <button type="button" className={narrativeMode === "global" ? "active" : ""} onClick={() => onSetNarrativeMode("global")}>整体视角</button>
            <button type="button" className={narrativeMode === "round" ? "active" : ""} onClick={() => onSetNarrativeMode("round")} disabled={!activeRound}>当前轮视角</button>
          </div>
        </div>
      </div>
      {rounds.length > 0 ? (
        <>
          <div className="research-round-pill-row">
            {rounds.map((round) => (
              <button
                key={`process-round-pill-${round.roundNo}`}
                type="button"
                className={["research-round-pill", activeRound?.roundNo === round.roundNo ? "active" : ""].filter(Boolean).join(" ")}
                onClick={() => onSelectRound(round.roundNo)}
              >
                第 {round.roundNo} 轮
              </button>
            ))}
          </div>
          {activeRound ? (
            <div className="research-round-preview">
              <div className="research-round-preview-header">
                <div className="research-process-group-title">
                  <strong>第 {activeRound.roundNo} 轮重点</strong>
                  <span>本轮判断={activeRound.decision} · 原因={activeRound.reason}</span>
                </div>
                <div className="research-inline-actions">
                  <button type="button" className="secondary-button" onClick={() => onViewAudit(activeRound.roundNo, activeRound.branchId)}>查看这轮审计</button>
                </div>
              </div>
              <div className="research-round-preview-metrics">
                <div className="research-round-preview-metric"><small>搜索命中</small><strong>{activeRound.searchHitCount}</strong></div>
                <div className="research-round-preview-metric"><small>阅读窗口</small><strong>{activeRound.readWindowCount}</strong></div>
                <div className="research-round-preview-metric"><small>证据卡</small><strong>{activeRound.evidenceCardCount}</strong></div>
              </div>
              {activeRound.sourceNarrative ? <small>本轮来源焦点：{activeRound.sourceNarrative}</small> : null}
              <small>{activeRound.deltaNarrative}</small>
              {activeRound.outcomeNarrative ? <small>{activeRound.outcomeNarrative.replace("step outcome", "本轮结果")}</small> : null}
            </div>
          ) : null}
        </>
      ) : <span>当前 run 还没有形成可展示的研究轮次，后续会随着闭环推进逐步沉淀成可回看的时间线。</span>}
    </div>
  );
}
