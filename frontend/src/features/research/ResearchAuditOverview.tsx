type FormalAuditSummary = {
  localVerifierStatus: string;
  globalVerifierDecision: string;
  finalLoopDecision: string;
  checkpointCount: number;
  counterfactualBranchCount: number;
  recoveryTargetCount: number;
  blockedRowCount: number;
  conflictedRowCount: number;
  guardrailedRowCount: number;
  hasCounterfactualRecheck: boolean;
};

type ResearchAuditOverviewProps = {
  verifierGateLead: string;
  verifierGateDetail: string;
  verifierGateMetrics: string;
  counterfactualLead: string;
  counterfactualDetail: string;
  counterfactualMetrics: string;
  checkpointResumeLead: string;
  checkpointResumeDetail: string;
  checkpointResumeMetrics: string;
  harnessControlLead: string;
  harnessControlDetail: string;
  harnessControlMetrics: string;
  formalAuditSummary: FormalAuditSummary | null;
};

export function ResearchAuditOverview({
  verifierGateLead,
  verifierGateDetail,
  verifierGateMetrics,
  counterfactualLead,
  counterfactualDetail,
  counterfactualMetrics,
  checkpointResumeLead,
  checkpointResumeDetail,
  checkpointResumeMetrics,
  harnessControlLead,
  harnessControlDetail,
  harnessControlMetrics,
  formalAuditSummary
}: ResearchAuditOverviewProps) {
  return (
    <>
      <p className="section-label">闭环审计</p>
      <div className="checkpoint-structured-grid process-lane-grid">
        <div className="link-card process-lane-card tone-neutral"><small className="process-lane-badge">Verifier Gate / Final Loop Decision</small><strong>系统为什么允许继续、恢复或写报告</strong><span>{verifierGateLead}</span><small>{verifierGateDetail}</small><small>{verifierGateMetrics}</small></div>
        <div className="link-card process-lane-card tone-workspace"><small className="process-lane-badge">Counterfactual Branch Summary</small><strong>有没有走反证分支</strong><span>{counterfactualLead}</span><small>{counterfactualDetail}</small><small>{counterfactualMetrics}</small></div>
        <div className="link-card process-lane-card tone-read"><small className="process-lane-badge">Checkpoint / Resume Summary</small><strong>当前 run 与 checkpoint / resume 的关系</strong><span>{checkpointResumeLead}</span><small>{checkpointResumeDetail}</small><small>{checkpointResumeMetrics}</small></div>
        <div className="link-card process-lane-card tone-search"><small className="process-lane-badge">Harness Control State</small><strong>当前闭环控制状态</strong><span>{harnessControlLead}</span><small>{harnessControlDetail}</small><small>{harnessControlMetrics}</small></div>
      </div>
      {formalAuditSummary ? (
        <div className="task-card">
          <strong>Formal Audit Summary</strong>
          <span>local={formalAuditSummary.localVerifierStatus || "-"} · global={formalAuditSummary.globalVerifierDecision || "-"} · final={formalAuditSummary.finalLoopDecision || "-"}</span>
          <span>checkpoints={formalAuditSummary.checkpointCount} · cf-branches={formalAuditSummary.counterfactualBranchCount} · recovery targets={formalAuditSummary.recoveryTargetCount}</span>
          <small>blocked={formalAuditSummary.blockedRowCount} · conflicted={formalAuditSummary.conflictedRowCount} · guardrailed={formalAuditSummary.guardrailedRowCount}</small>
          <small>{formalAuditSummary.hasCounterfactualRecheck ? "当前审计摘要显示这次 run 已进入 counterfactual recheck 路径。" : "当前审计摘要显示这次 run 沿 verified path 收敛，没有显式 counterfactual recheck。"}</small>
        </div>
      ) : null}
    </>
  );
}
