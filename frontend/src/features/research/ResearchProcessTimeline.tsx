import { Flag, GitBranch, RotateCcw } from "lucide-react";
import { formatRelativeTime } from "../../shared/util/datetime";
import type {
  ResearchCheckpointListItem,
  ResearchCounterfactualSummary,
  ResearchLoopRoundSummary
} from "./model";

const DECISION_LABEL: Record<string, string> = {
  CONTINUE: "继续检索",
  EXPAND: "扩展研究",
  REPLAN: "重新规划",
  COUNTERFACTUAL: "开启反证",
  MERGE: "合并分支",
  COMPLETE: "完成",
  STOP: "停止",
  HANDOFF: "需要人工介入"
};

const BRANCH_STATUS_LABEL: Record<string, string> = {
  MERGED: "已合并",
  ACTIVE: "进行中",
  ABANDONED: "已放弃",
  REJECTED: "已否决"
};

export type ResearchRound = ResearchLoopRoundSummary & { note: string };

type ResearchProcessTimelineProps = {
  rounds: ResearchRound[];
  counterfactual: ResearchCounterfactualSummary | null;
  checkpoints: ResearchCheckpointListItem[];
  running: boolean;
  canResume: boolean;
  onResume: (checkpointNo: number) => void;
};

function decisionLabel(value: string) {
  return DECISION_LABEL[value.toUpperCase()] ?? value;
}

function decisionTone(value: string) {
  const decision = value.toUpperCase();
  if (decision === "COMPLETE" || decision === "MERGE") return "done";
  if (decision === "REPLAN" || decision === "COUNTERFACTUAL") return "replan";
  return "neutral";
}

export function ResearchProcessTimeline({
  rounds,
  counterfactual,
  checkpoints,
  running,
  canResume,
  onResume
}: ResearchProcessTimelineProps) {
  const branches = counterfactual?.branches ?? [];

  return (
    <div className="research-process">
      <section aria-labelledby="research-rounds-title">
        <h3 id="research-rounds-title">研究轮次</h3>
        {rounds.length === 0 ? (
          <p className="research-process-empty">研究启动后，每一轮的规划、检索和核验结果会依次出现在这里。</p>
        ) : (
          <ol className="research-rounds">
            {rounds.map((round) => {
              const decision = round.global_decision || round.branch_decision;
              return (
                <li key={round.round_no} className={`research-round is-${decisionTone(decision)}`}>
                  <header>
                    <strong>第 {round.round_no} 轮</strong>
                    {decision ? <span className="research-round-decision">{decisionLabel(decision)}</span> : null}
                  </header>
                  {round.note ? <p>{round.note}</p> : null}
                  <dl className="research-round-metrics">
                    <div><dt>检索</dt><dd>{round.search_hit_count}</dd></div>
                    <div><dt>阅读</dt><dd>{round.read_window_count}</dd></div>
                    <div><dt>证据</dt><dd>{round.evidence_card_count}</dd></div>
                  </dl>
                  {round.search_queries.length > 0 ? (
                    <ul className="research-round-queries" aria-label="检索词">
                      {round.search_queries.slice(0, 4).map((query) => <li key={query}>{query}</li>)}
                      {round.search_queries.length > 4 ? <li>+{round.search_queries.length - 4}</li> : null}
                    </ul>
                  ) : null}
                </li>
              );
            })}
            {running ? (
              <li className="research-round is-running">
                <header><strong>第 {rounds.length + 1} 轮</strong><span className="research-round-decision">进行中</span></header>
              </li>
            ) : null}
          </ol>
        )}
      </section>

      {branches.length > 0 ? (
        <section aria-labelledby="research-branches-title">
          <h3 id="research-branches-title">反证复核</h3>
          {branches.map((branch) => (
            <article key={branch.branch_id} className="research-branch-card">
              <header>
                <GitBranch size={15} aria-hidden="true" />
                <strong>{branch.hypothesis_summary || "针对冲突证据开启的反证分支"}</strong>
              </header>
              <small>
                {BRANCH_STATUS_LABEL[branch.branch_status?.toUpperCase()] ?? branch.branch_status}
                {branch.target_evidence_ids.length > 0 ? ` · 复核 ${branch.target_evidence_ids.length} 条证据` : ""}
              </small>
            </article>
          ))}
        </section>
      ) : null}

      <section aria-labelledby="research-checkpoints-title">
        <h3 id="research-checkpoints-title">检查点</h3>
        {checkpoints.length === 0 ? (
          <p className="research-process-empty">每轮结束会保存一个检查点，任务中断后可以从这里恢复。</p>
        ) : (
          <ul className="research-checkpoints">
            {checkpoints.map((checkpoint) => (
              <li key={checkpoint.checkpoint_no}>
                <Flag size={14} aria-hidden="true" />
                <div>
                  <strong>检查点 {checkpoint.checkpoint_no}</strong>
                  <small>
                    {decisionLabel(checkpoint.final_loop_decision)}
                    {typeof checkpoint.verified_row_count === "number" ? ` · 已验证 ${checkpoint.verified_row_count}` : ""}
                    {checkpoint.created_at ? ` · ${formatRelativeTime(checkpoint.created_at)}` : ""}
                  </small>
                </div>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={!canResume}
                  onClick={() => onResume(checkpoint.checkpoint_no)}
                >
                  <RotateCcw size={14} aria-hidden="true" />从此恢复
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
