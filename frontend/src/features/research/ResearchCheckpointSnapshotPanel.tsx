type SnapshotCard = { title: string; lines: string[] };
type ComparisonOption = { checkpointNo: number; label: string };

type ResearchCheckpointSnapshotPanelProps = {
  replayLabel: string;
  comparisonValue: number | null;
  comparisonOptions: ComparisonOption[];
  snapshotCards: SnapshotCard[];
  isBusy: boolean;
  onChangeComparison: (value: string) => void;
};

export function ResearchCheckpointSnapshotPanel({
  replayLabel,
  comparisonValue,
  comparisonOptions,
  snapshotCards,
  isBusy,
  onChangeComparison
}: ResearchCheckpointSnapshotPanelProps) {
  return (
    <>
      <span>{replayLabel}</span>
      <label className="rail-field">
        <span>对比基线 checkpoint</span>
        <select value={comparisonValue ?? ""} onChange={(event) => onChangeComparison(event.target.value)} disabled={isBusy}>
          <option value="">不选择基线</option>
          {comparisonOptions.map((option) => <option key={`checkpoint-compare-${option.checkpointNo}`} value={option.checkpointNo}>{option.label}</option>)}
        </select>
      </label>
      <div className="checkpoint-structured-grid">
        {snapshotCards.map((card) => (
          <div key={card.title} className="link-card">
            <strong>{card.title}</strong>
            {card.lines.map((line, index) => index === 0 ? <span key={index}>{line}</span> : <small key={index}>{line}</small>)}
          </div>
        ))}
      </div>
    </>
  );
}
