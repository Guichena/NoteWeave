import { useState } from "react";
import { researchApi, type ResearchApi } from "./api";
import type { ResearchDiscoveryProposal } from "./model";
import { formatConfidence, useRunResource } from "./useRunResource";

const TYPE_LABELS: Record<string, string> = {
  ENTITY: "研究对象",
  DIMENSION: "研究维度",
  SEARCH_QUERY: "检索方向"
};

const STATUS_LABELS: Record<string, string> = {
  ACCEPTED: "已加入",
  SHADOW: "仅记录",
  REJECTED: "未采纳"
};

type ResearchDiscoveryPanelProps = {
  workspaceId: string;
  researchRunId: string;
  refreshKey: string;
  api?: Pick<ResearchApi, "listDiscoveryProposals">;
};

/** 宽搜索提出的新研究对象、维度和检索方向，默认只展示已加入表格的项。 */
export function ResearchDiscoveryPanel({ workspaceId, researchRunId, refreshKey, api = researchApi }: ResearchDiscoveryPanelProps) {
  const { data, error } = useRunResource(
    () => api.listDiscoveryProposals(workspaceId, researchRunId),
    `${researchRunId}:${refreshKey}`
  );
  const [showAll, setShowAll] = useState(false);
  const proposals = data ?? [];
  if (proposals.length === 0) {
    return error ? <div className="task-card"><small role="alert">{error}</small></div> : null;
  }

  const accepted = proposals.filter((proposal) => proposal.status === "ACCEPTED");
  const visible = showAll ? proposals : accepted;

  return (
    <section className="task-card research-discovery-panel" aria-label="宽搜索发现">
      <div className="research-plan-header">
        <strong>宽搜索发现</strong>
        <button type="button" className="secondary-button" onClick={() => setShowAll((value) => !value)}>
          {showAll ? "只看已加入" : `查看全部 ${proposals.length} 条`}
        </button>
      </div>
      <small>{accepted.length} 条已加入研究表格，{proposals.length - accepted.length} 条仅记录或未采纳。</small>
      {visible.length > 0 ? (
        <ul className="research-repair-list">
          {visible.map((proposal) => <ProposalItem key={`${proposal.proposal_type}-${proposal.label}`} proposal={proposal} />)}
        </ul>
      ) : null}
    </section>
  );
}

function ProposalItem({ proposal }: { proposal: ResearchDiscoveryProposal }) {
  return (
    <li className="research-repair-item">
      <div className="research-repair-row">
        <span className={`research-proposal-status status-${proposal.status.toLowerCase()}`}>
          {STATUS_LABELS[proposal.status] ?? proposal.status}
        </span>
        <span>{TYPE_LABELS[proposal.proposal_type] ?? proposal.proposal_type}：{proposal.label}</span>
      </div>
      {proposal.rationale ? <small>{proposal.rationale}</small> : null}
      <small>置信度 {formatConfidence(proposal.confidence_ppm)} · {proposal.supporting_domain_count} 个独立站点支持</small>
    </li>
  );
}
