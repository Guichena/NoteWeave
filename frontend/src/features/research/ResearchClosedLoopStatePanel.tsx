type ClosedLoopStateView = {
  activeBranchId: string;
  finalLoopDecision: string;
  ledgerRowCount: number;
  branchCount: number;
  verifierDecisionCount: number;
  checkpointCount: number;
  sourceEvidenceCount: number;
  requirementSummary: string;
  requirementRowSummary: string;
  recoveryTargets: string;
  recoveryNarrative: string;
  targetQueries: string;
  missingRequirements: string;
};

type VerifierGatedRowView = {
  key: string;
  title: string;
  status: string;
  claim: string;
  focus: string;
  requirementSummary: string;
  missingColumns: string;
  verifierSummary: string;
  targetSummary: string;
};

type VerifierGatedRowsView = {
  summary: string;
  statusSummary: string;
  narrative: string;
  recoveryCoverage: string;
  missingRequirements: string;
  rows: VerifierGatedRowView[];
};

type ResearchClosedLoopStatePanelProps = {
  state: ClosedLoopStateView | null;
  verifierGatedRows: VerifierGatedRowsView | null;
};

export function ResearchClosedLoopStatePanel({ state, verifierGatedRows }: ResearchClosedLoopStatePanelProps) {
  return (
    <>
      {state ? (
        <div className="task-card">
          <strong>Closed-Loop State</strong>
          <span>Branch={state.activeBranchId || "-"} · Loop={state.finalLoopDecision || "-"}</span>
          <span>Rows {state.ledgerRowCount} · Branches {state.branchCount} · Decisions {state.verifierDecisionCount}</span>
          <span>Checkpoints {state.checkpointCount} · Source Evidence {state.sourceEvidenceCount}</span>
          <span>{state.requirementSummary}</span>
          <span>{state.requirementRowSummary}</span>
          {state.recoveryTargets ? <small>{state.recoveryTargets}</small> : null}
          {state.recoveryNarrative ? <small>{state.recoveryNarrative}</small> : null}
          {state.targetQueries ? <small>{state.targetQueries}</small> : null}
          {state.missingRequirements ? <small>{state.missingRequirements}</small> : null}
        </div>
      ) : null}
      {verifierGatedRows ? (
        <div className="task-card">
          <strong>Verifier-Gated Rows</strong>
          <span>{verifierGatedRows.summary}</span>
          <span>{verifierGatedRows.statusSummary}</span>
          <small>{verifierGatedRows.narrative}</small>
          {verifierGatedRows.recoveryCoverage ? <small>{verifierGatedRows.recoveryCoverage}</small> : null}
          {verifierGatedRows.missingRequirements ? <small>{verifierGatedRows.missingRequirements}</small> : null}
          {verifierGatedRows.rows.length > 0 ? (
            <div className="wiki-citations">
              {verifierGatedRows.rows.map((row) => (
                <div key={row.key} className="link-card">
                  <strong>{row.title}</strong>
                  <span>{row.status}</span>
                  <small>{row.claim}</small>
                  <small>{row.focus}</small>
                  <small>{row.requirementSummary}</small>
                  <small>{row.missingColumns}</small>
                  <small>{row.verifierSummary}</small>
                  <small>{row.targetSummary}</small>
                </div>
              ))}
            </div>
          ) : <small>当前 run 没有显式 verifier-gated row，闭环阻塞项已基本清空。</small>}
        </div>
      ) : null}
    </>
  );
}
