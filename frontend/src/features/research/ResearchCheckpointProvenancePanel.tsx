type AuditFocusAction = {
  label: string;
  kind: "trace" | "loop-round" | "verifier";
  traceIndex?: number;
  roundNo?: number;
  verifierIndex?: number;
  checkpointNo: number | null;
  branchId: string;
};

type ProvenanceCard = {
  key: string;
  title: string;
  lines: string[];
  action?: AuditFocusAction;
};

type ResearchCheckpointProvenancePanelProps = {
  cards: ProvenanceCard[];
  onFocusAudit: (action: AuditFocusAction) => void;
};

export function ResearchCheckpointProvenancePanel({ cards, onFocusAudit }: ResearchCheckpointProvenancePanelProps) {
  return (
    <div className="wiki-citations">
      <strong>Checkpoint Provenance</strong>
      {cards.map((card) => (
        <div key={card.key} className="link-card">
          <strong>{card.title}</strong>
          {card.lines.map((line, index) => index === 0 ? <span key={index}>{line}</span> : <small key={index}>{line}</small>)}
          {card.action ? <div className="research-inline-actions"><button type="button" className="secondary-button" onClick={() => onFocusAudit(card.action!)}>{card.action.label}</button></div> : null}
        </div>
      ))}
    </div>
  );
}
