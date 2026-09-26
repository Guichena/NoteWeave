import { useState } from "react";
import { BadgeCheck, BrainCircuit, Inbox, LoaderCircle, RefreshCw, ScanSearch } from "lucide-react";
import {
  type MemoryReviewDecision,
  type MemoryReviewItem
} from "./model";
import { useMemoryReview } from "./useMemoryReview";

type MemoryReviewWorkbenchProps = {
  workspaceId: string;
};

export function MemoryReviewWorkbench({ workspaceId }: MemoryReviewWorkbenchProps) {
  const [reason, setReason] = useState("");
  const [notice, setNotice] = useState("");
  const {
    queue,
    selectedRevisionId,
    selectedItem,
    queueLoading,
    mutating,
    error,
    lastDecision,
    refreshQueue,
    selectReview,
    decide
  } = useMemoryReview(workspaceId);

  async function submitDecision(item: MemoryReviewItem, decision: MemoryReviewDecision) {
    setNotice("");
    try {
      const result = await decide(item, decision, reason);
      setReason("");
      const revoked = result.revoked_memory_item_ids.length > 0
        ? `；撤销 ${result.revoked_memory_item_ids.length} 条冲突 Memory`
        : "";
      setNotice(`审核已提交：${decision} → ${result.status}${revoked}`);
    } catch {}
  }

  return (
    <section className="memory-workbench">
      <aside className="memory-queue-panel">
        <div className="memory-panel-heading">
          <h2>审核队列</h2>
          <span aria-label={`${queue.length} 条待审核`}>{queue.length}</span>
          <button
            type="button"
            className="icon-button memory-refresh"
            aria-label={queueLoading ? "刷新中" : "刷新审核队列"}
            title="刷新审核队列"
            disabled={queueLoading || mutating}
            onClick={() => void refreshQueue()}
          >
            <RefreshCw className={queueLoading ? "is-spinning" : ""} size={15} aria-hidden="true" />
          </button>
        </div>
        <p className="memory-queue-note">只显示当前运行时产生、需要人工判断的记忆候选。</p>
        <div className="memory-queue-list">
          {queue.map((item) => (
            <button
              key={item.revision_id}
              type="button"
              className={selectedRevisionId === item.revision_id ? "active memory-review-card" : "memory-review-card"}
              disabled={mutating}
              onClick={() => selectReview(item)}
            >
              <strong>{item.display_text}</strong>
              <small>{item.review_kind === "PROPOSAL" ? "新候选" : "复核"} · {item.review_status} · 效用 {formatScore(item.utility_score)}</small>
            </button>
          ))}
          {queueLoading ? <p className="phase-note">正在加载审核队列…</p> : null}
          {!queueLoading && queue.length === 0 ? <p className="memory-queue-empty">队列为空</p> : null}
        </div>
      </aside>

      <article className="memory-review-panel">
        {notice ? <p className="memory-notice" role="status">{notice}</p> : null}
        {error ? <p className="memory-error" role="alert">{error}</p> : null}
        {lastDecision ? (
          <p className="phase-note">最近决策：{lastDecision.status} · revision={lastDecision.revision_id}</p>
        ) : null}

        {queueLoading && !selectedItem ? (
          <div className="memory-review-loading" role="status" aria-live="polite">
            <LoaderCircle className="is-spinning" size={20} aria-hidden="true" />
            <div>
              <strong>正在读取审核队列</strong>
              <p>同步当前运行时的 Memory revision 与审核状态。</p>
            </div>
          </div>
        ) : selectedItem ? (
          <div className="memory-detail">
            <p className="memory-detail-kicker">
              {selectedItem.review_kind === "PROPOSAL" ? "新 Revision 审核" : "已生效 Memory 复核"}
            </p>
            <h2 className="memory-statement">{selectedItem.display_text}</h2>
            <dl className="memory-metric-grid">
              <Metric label="审核状态" value={selectedItem.review_status} />
              <Metric label="生命周期" value={selectedItem.lifecycle_status} />
              <Metric label="冲突" value={selectedItem.conflict_status} />
              <Metric label="效用" value={formatScore(selectedItem.utility_score)} />
            </dl>
            <p className="memory-provenance">
              revision={selectedItem.revision_id} · provenance={selectedItem.provenance_ref || "-"}
            </p>
            <label className="input-block">
              <span>审核理由</span>
              <textarea
                rows={3}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="说明接受、拒绝、替换或撤销的依据"
              />
            </label>
            <div className="memory-decision-actions">
              {decisionOptions(selectedItem).map((option) => (
                <button
                  key={option.decision}
                  type="button"
                  className={option.tone === "danger" ? "memory-danger-button" : option.tone === "secondary" ? "secondary-button" : "primary-action"}
                  disabled={mutating}
                  onClick={() => void submitDecision(selectedItem, option.decision)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div className="memory-empty-state" aria-label="Memory 审核流程">
            <span className="memory-empty-icon" aria-hidden="true"><BrainCircuit size={24} /></span>
            <h2>{queue.length > 0 ? "选择一条 Memory revision" : "等待新的 Memory revision"}</h2>
            <p>
              {queue.length > 0
                ? "从左侧队列选择候选，核对来源、冲突状态和效用分数后做出决定。"
                : "对话与研究中沉淀下来的长期记忆会先进入这里，经人工确认后才会被召回。"}
            </p>
            <ol className="memory-review-flow" aria-label="候选到审核决策的阶段">
              <li className={queue.length > 0 ? "is-current" : undefined}>
                <Inbox size={16} aria-hidden="true" /><span>进入审核队列</span>
              </li>
              <li><ScanSearch size={16} aria-hidden="true" /><span>核对来源与冲突</span></li>
              <li><BadgeCheck size={16} aria-hidden="true" /><span>审核通过后进入运行时</span></li>
            </ol>
          </div>
        )}

        <dl className="memory-runtime-policy" aria-label="运行时约束">
          <div>
            <dt>生效版本</dt>
            <dd>当前已接受 revision</dd>
          </div>
          <div>
            <dt>召回范围</dt>
            <dd>仅限已接受内容</dd>
          </div>
          <div>
            <dt>拒绝 / 撤销</dt>
            <dd>同步退出运行时</dd>
          </div>
        </dl>
      </article>
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="memory-metric-card">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

export function decisionOptions(item: MemoryReviewItem): Array<{
  decision: MemoryReviewDecision;
  label: string;
  tone: "primary" | "secondary" | "danger";
}> {
  if (item.review_kind === "ACTIVE") {
    return [
      { decision: "ACCEPT", label: "确认继续使用", tone: "primary" },
      { decision: "REVOKE", label: "撤销 Memory", tone: "danger" }
    ];
  }
  if (item.conflict_status === "CONFLICTING_ACTIVE_MEMORY") {
    return [
      { decision: "REPLACE_EXISTING", label: "替换冲突 Memory", tone: "primary" },
      { decision: "REJECT", label: "拒绝 Revision", tone: "danger" }
    ];
  }
  return [
    { decision: "ACCEPT", label: "接受 Revision", tone: "primary" },
    { decision: "REJECT", label: "拒绝 Revision", tone: "danger" }
  ];
}

function formatScore(value: number) {
  return Number.isFinite(value) ? value.toFixed(2) : "0.00";
}
