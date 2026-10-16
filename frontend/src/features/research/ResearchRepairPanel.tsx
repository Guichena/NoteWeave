import { researchApi, type ResearchApi } from "./api";
import type { ResearchCounterCheck, ResearchReplan } from "./model";
import { useRunResource } from "./useRunResource";

export const VERDICT_LABELS: Record<string, string> = {
  REFUTED: "被推翻",
  NOT_REFUTED: "未被推翻",
  NO_COUNTER_EVIDENCE_FOUND: "未找到反证",
  NOT_RUN: "未执行"
};

const STRATEGY_LABELS: Record<string, string> = {
  COUNTER_EVIDENCE: "检索反证",
  BROADEN: "放宽检索词",
  NARROW: "收窄检索词",
  SWITCH_LANGUAGE: "切换检索语言",
  SWITCH_SOURCE_TYPE: "更换来源类型"
};

const CAUSE_LABELS: Record<string, string> = {
  EVIDENCE_CONFLICT: "证据冲突",
  MARGINAL_GAIN_EXHAUSTED: "继续检索没有新收获",
  FETCH_FAILED: "页面读取失败",
  PROVIDER_FAILED: "检索服务失败"
};

type ResearchRepairPanelProps = {
  workspaceId: string;
  researchRunId: string;
  refreshKey: string;
  api?: Pick<ResearchApi, "listReplans" | "listCounterChecks">;
};

/** 修复过程：逐条结论的反证结果，以及每次按原因调整检索策略的记录。 */
export function ResearchRepairPanel({ workspaceId, researchRunId, refreshKey, api = researchApi }: ResearchRepairPanelProps) {
  const key = `${researchRunId}:${refreshKey}`;
  const checks = useRunResource(() => api.listCounterChecks(workspaceId, researchRunId), key);
  const replans = useRunResource(() => api.listReplans(workspaceId, researchRunId), key);
  const checkList = checks.data ?? [];
  const replanList = replans.data ?? [];
  if (checkList.length === 0 && replanList.length === 0) {
    const error = checks.error || replans.error;
    return error ? <div className="task-card"><small role="alert">{error}</small></div> : null;
  }

  return (
    <section className="task-card research-repair-panel" aria-label="反证与修复">
      <div className="research-plan-header">
        <strong>反证与修复</strong>
        <small>{summarizeVerdicts(checkList)}</small>
      </div>
      {checkList.length > 0 ? (
        <ul className="research-repair-list" aria-label="反证结果">
          {checkList.map((check) => <CounterCheckItem key={`${check.cell_key}-${check.claim_text}`} check={check} />)}
        </ul>
      ) : null}
      {replanList.length > 0 ? (
        <ol className="research-repair-list" aria-label="修复记录">
          {replanList.map((replan) => <ReplanItem key={`${replan.plan_revision_to}-${replan.created_at}`} replan={replan} />)}
        </ol>
      ) : null}
    </section>
  );
}

function CounterCheckItem({ check }: { check: ResearchCounterCheck }) {
  return (
    <li className="research-repair-item">
      <div className="research-repair-row">
        <span className={`research-verdict verdict-${check.verdict.toLowerCase()}`}>{VERDICT_LABELS[check.verdict] ?? check.verdict}</span>
        <span>{check.claim_text}</span>
      </div>
      <small>
        {check.entity_label} · {check.column_label} · {check.counter_query_count} 条反证查询
        {check.refuting_evidence_keys.length > 0 ? ` · ${check.refuting_evidence_keys.length} 条反驳证据` : ""}
        {check.verdict === "NOT_RUN" ? " · 模型不可用，这条结论不会进入正式结论" : ""}
      </small>
    </li>
  );
}

function ReplanItem({ replan }: { replan: ResearchReplan }) {
  return (
    <li className="research-repair-item">
      <div className="research-repair-row">
        <span className="research-strategy">{STRATEGY_LABELS[replan.strategy] ?? replan.strategy}</span>
        <span>
          第 {replan.plan_revision_from} 版到第 {replan.plan_revision_to} 版 · 原因：{CAUSE_LABELS[replan.repair_cause] ?? replan.repair_cause}
        </span>
      </div>
      {replan.observed_facts ? <small>{replan.observed_facts}</small> : null}
      <small>
        {replan.query_count} 条新查询
        {replan.affected_cells.length > 0 ? ` · 涉及 ${replan.affected_cells.join("、")}` : ""}
      </small>
    </li>
  );
}

export function summarizeVerdicts(checks: ResearchCounterCheck[]) {
  if (checks.length === 0) return "";
  const counts: Record<string, number> = {};
  for (const check of checks) counts[check.verdict] = (counts[check.verdict] ?? 0) + 1;
  return Object.entries(counts).map(([verdict, total]) => `${VERDICT_LABELS[verdict] ?? verdict} ${total}`).join(" · ");
}
