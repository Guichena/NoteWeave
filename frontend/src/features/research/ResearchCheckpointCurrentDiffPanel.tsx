type DiffCard = { title: string; lines: string[] };
type Finding = { key: string; title: string; claim: string; meta: string };

type ResearchCheckpointCurrentDiffPanelProps = {
  summaryCards: DiffCard[];
  progressCards: DiffCard[];
  newFindings: Finding[];
  newFindingsSourceNarrative: string;
};

export function ResearchCheckpointCurrentDiffPanel({ summaryCards, progressCards, newFindings, newFindingsSourceNarrative }: ResearchCheckpointCurrentDiffPanelProps) {
  return (
    <div className="wiki-citations">
      <strong>Checkpoint To Current Diff</strong>
      <div className="checkpoint-structured-grid">
        {summaryCards.map((card) => <div key={card.title} className="link-card"><strong>{card.title}</strong>{card.lines.map((line, index) => index < 2 ? <span key={index}>{line}</span> : <small key={index}>{line}</small>)}</div>)}
      </div>
      {progressCards.map((card) => <div key={card.title} className="link-card"><strong>{card.title}</strong>{card.lines.map((line, index) => index < 2 ? <span key={index}>{line}</span> : <small key={index}>{line}</small>)}</div>)}
      {newFindings.length > 0 ? (
        <div className="wiki-citations">
          <strong>New Verified Findings Since Snapshot</strong>
          {newFindingsSourceNarrative ? <small>new finding sources={newFindingsSourceNarrative}</small> : null}
          {newFindings.map((finding) => <div key={finding.key} className="link-card"><strong>{finding.title}</strong><span>{finding.claim}</span><small>{finding.meta}</small></div>)}
        </div>
      ) : <span>当前 run 相比该 checkpoint 还没有新增 verifier-approved finding。</span>}
    </div>
  );
}
