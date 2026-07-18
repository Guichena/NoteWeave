type SignalChip = { label: string; value: string; tone: string };

type CheckpointReplayCard = {
  checkpointNo: number;
  snapshotType: string;
  branchLoop: string;
  verifierSummary: string;
  evidenceSummary: string;
  intentSummary: string;
  requirementSummary: string;
  summarySource: string;
  recoveryTargets: string;
  recoveryNarrative: string;
  traceNarrative: string;
  continuityFocus: string;
  continuityNarrative: string;
  roundDeltaNarrative: string;
  signalChips: SignalChip[];
};

type ResearchCheckpointReplayPanelProps = {
  checkpoints: CheckpointReplayCard[];
  isBusy: boolean;
  onReplay: (checkpointNo: number) => void;
  onResume: (checkpointNo: number) => void;
};

export function ResearchCheckpointReplayPanel({ checkpoints, isBusy, onReplay, onResume }: ResearchCheckpointReplayPanelProps) {
  return (
    <div className="task-card">
      <strong>Checkpoint 回放</strong>
      {checkpoints.length > 0 ? (
        <div className="research-checkpoint-list">
          {checkpoints.map((checkpoint) => (
            <div key={`research-side-checkpoint-${checkpoint.checkpointNo}`} className="research-item">
              <span><strong>#{checkpoint.checkpointNo}</strong> · {checkpoint.snapshotType}</span>
              <small>{checkpoint.branchLoop}</small>
              <small>{checkpoint.verifierSummary}</small>
              <small>{checkpoint.evidenceSummary}</small>
              <small>{checkpoint.intentSummary}</small>
              <small>{checkpoint.requirementSummary}</small>
              {checkpoint.summarySource ? <small>{checkpoint.summarySource}</small> : null}
              {checkpoint.recoveryTargets ? <small>{checkpoint.recoveryTargets}</small> : null}
              {checkpoint.recoveryNarrative ? <small>{checkpoint.recoveryNarrative}</small> : null}
              <small>{checkpoint.traceNarrative}</small>
              {checkpoint.continuityFocus ? <small>{checkpoint.continuityFocus}</small> : null}
              {checkpoint.continuityNarrative ? <small>{checkpoint.continuityNarrative}</small> : null}
              {checkpoint.roundDeltaNarrative ? <small>{checkpoint.roundDeltaNarrative}</small> : null}
              <div className="signal-chip-row">
                {checkpoint.signalChips.map((chip, index) => <span key={`checkpoint-signal-chip-${checkpoint.checkpointNo}-${index}`} className={`signal-chip tone-${chip.tone}`}>{chip.label}: {chip.value}</span>)}
              </div>
              <div className="research-inline-actions">
                <button className="secondary-button" onClick={() => onReplay(checkpoint.checkpointNo)} disabled={isBusy}>回放</button>
                <button className="secondary-button" onClick={() => onResume(checkpoint.checkpointNo)} disabled={isBusy}>从此恢复</button>
              </div>
            </div>
          ))}
        </div>
      ) : <span>当前 run 还没有可回放的 checkpoint。</span>}
    </div>
  );
}
