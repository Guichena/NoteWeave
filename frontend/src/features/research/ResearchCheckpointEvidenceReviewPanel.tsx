type EvidenceItem = { key: string; title: string; claim: string; meta: string };

type ResearchCheckpointEvidenceReviewPanelProps = {
  verifiedFindings: EvidenceItem[];
  conflictedRows: EvidenceItem[];
  branchDecisions: string[];
  recoveryLines: string[];
  nextActions: string[];
  evidenceCards: EvidenceItem[];
};

export function ResearchCheckpointEvidenceReviewPanel({
  verifiedFindings,
  conflictedRows,
  branchDecisions,
  recoveryLines,
  nextActions,
  evidenceCards
}: ResearchCheckpointEvidenceReviewPanelProps) {
  return (
    <>
      {verifiedFindings.length > 0 ? <div className="wiki-citations"><strong>Snapshot Verified Findings</strong>{verifiedFindings.map((finding) => <div key={finding.key} className="link-card"><strong>{finding.title}</strong><span>{finding.claim}</span><small>{finding.meta}</small></div>)}</div> : null}
      <div className="wiki-citations">
        <strong>Snapshot Conflict And Counterfactual Review</strong>
        {conflictedRows.length > 0 ? conflictedRows.map((row) => <div key={row.key} className="link-card"><strong>{row.title}</strong><span>{row.claim}</span><small>{row.meta}</small></div>) : <span>该 checkpoint 中没有显式冲突字段。</span>}
        {branchDecisions.map((decision, index) => <small key={`checkpoint-branch-${index}`}>分支决策：{decision}</small>)}
      </div>
      <div className="wiki-citations">
        <strong>Snapshot Recovery Status</strong>
        {recoveryLines.map((line, index) => index === 0 ? <span key={index}>{line}</span> : <small key={index}>{line}</small>)}
        {nextActions.length > 0 ? nextActions.map((action, index) => <small key={`checkpoint-next-${index}`}>Next：{action}</small>) : <span>该 checkpoint 没有额外 next actions。</span>}
      </div>
      <div className="wiki-citations">
        <strong>Snapshot Evidence Ledger</strong>
        {evidenceCards.length > 0 ? evidenceCards.map((card) => <div key={card.key} className="link-card"><strong>{card.title}</strong><span>{card.claim}</span><small>{card.meta}</small></div>) : <span>该 checkpoint 没有 evidence cards。</span>}
      </div>
    </>
  );
}
