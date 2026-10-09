import { Plus } from "lucide-react";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { ResearchRunSummary } from "./model";

type ResearchRunListProps = {
  runs: ResearchRunSummary[];
  activeRunId: string;
  composing: boolean;
  isBusy: boolean;
  onNewResearch: () => void;
  onOpenRun: (run: ResearchRunSummary) => void;
};

function runTone(status: string) {
  const value = status.toUpperCase();
  if (value === "QUEUED" || value === "RUNNING") return "running";
  if (value === "FAILED") return "failed";
  if (value === "CANCELLED") return "cancelled";
  return "done";
}

const TONE_LABEL: Record<ReturnType<typeof runTone>, string> = {
  running: "研究中",
  failed: "失败",
  cancelled: "已取消",
  done: "已完成"
};

export function ResearchRunList({ runs, activeRunId, composing, isBusy, onNewResearch, onOpenRun }: ResearchRunListProps) {
  return (
    <aside className="research-runs" aria-label="研究记录">
      <button
        type="button"
        className={`research-new-button${composing ? " is-active" : ""}`}
        onClick={onNewResearch}
      >
        <Plus size={16} aria-hidden="true" />新研究
      </button>
      <p className="research-runs-title">研究记录</p>
      {runs.length === 0 ? (
        <p className="research-runs-empty">还没有研究记录</p>
      ) : (
        <ul className="research-runs-list">
          {runs.map((run) => {
            const active = !composing && run.research_run_id === activeRunId;
            const tone = runTone(run.status);
            return (
              <li key={run.research_run_id}>
                <button
                  type="button"
                  className={`research-runs-item${active ? " is-active" : ""}`}
                  aria-current={active ? "page" : undefined}
                  disabled={isBusy && !active}
                  onClick={() => onOpenRun(run)}
                  title={run.question}
                >
                  <span className="research-runs-item-title">{run.final_report_title || run.question}</span>
                  <small>
                    <i className={`research-runs-dot is-${tone}`} aria-hidden="true" />
                    {TONE_LABEL[tone]}
                    {" · "}
                    {formatRelativeTime(run.updated_at)}
                  </small>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </aside>
  );
}
