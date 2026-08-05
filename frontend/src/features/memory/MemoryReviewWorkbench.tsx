import { useState } from "react";
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
        <p className="section-label">Memory Review</p>
        <h2>统一审核队列</h2>
        <p className="phase-note">
          队列只读取当前运行时的 revision；旧 memory_object review 已停止写入。
        </p>
        <button
          className="secondary-button"
          disabled={queueLoading || mutating}
          onClick={() => void refreshQueue()}
        >
          {queueLoading ? "刷新中…" : "刷新审核队列"}
        </button>
        <div className="memory-queue-list">
          {queue.map((item) => (
            <button
              key={item.revision_id}
              className={selectedRevisionId === item.revision_id ? "active memory-review-card" : "memory-review-card"}
              disabled={mutating}
              onClick={() => selectReview(item)}
            >
              <span>{item.review_kind} · {item.status}</span>
              <strong>{item.display_text}</strong>
              <small>{item.review_status} · {item.lifecycle_status}</small>
              <small>utility {formatScore(item.utility_score)}</small>
            </button>
          ))}
          {queueLoading ? (
            <p className="phase-note">正在加载审核队列…</p>
          ) : null}
          {!queueLoading && queue.length === 0 ? (
            <div className="empty-panel">
              <strong>队列为空</strong>
              <p>当前没有待审核的 Memory revision。运行产生新候选后会出现在这里。</p>
            </div>
          ) : null}
        </div>
      </aside>

      <article className="memory-review-panel">
        <p className="section-label">Review Decision</p>
        {selectedItem ? (
          <>
            <h2>{selectedItem.review_kind === "PROPOSAL" ? "新 Revision 审核" : "已生效 Memory 复核"}</h2>
            <div className="memory-statement-card">
              <strong>{selectedItem.display_text}</strong>
              <span>revision={selectedItem.revision_id}</span>
              <small>provenance={selectedItem.provenance_ref || "-"}</small>
            </div>
            <div className="memory-metric-grid">
              <Metric label="Review" value={selectedItem.review_status} />
              <Metric label="Lifecycle" value={selectedItem.lifecycle_status} />
              <Metric label="Conflict" value={selectedItem.conflict_status} />
              <Metric label="Utility" value={formatScore(selectedItem.utility_score)} />
            </div>
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
                  className={option.tone === "danger" ? "memory-danger-button" : option.tone === "secondary" ? "secondary-button" : ""}
                  disabled={mutating}
                  onClick={() => void submitDecision(selectedItem, option.decision)}
                >
                  {option.label}
                </button>
              ))}
            </div>
            {notice ? <p className="status-line">{notice}</p> : null}
            {error ? <p className="memory-error">{error}</p> : null}
            {lastDecision ? (
              <p className="phase-note">
                最近决策：{lastDecision.status} · revision={lastDecision.revision_id}
              </p>
            ) : null}
          </>
        ) : (
          <p className="empty-state">从左侧选择一个 revision 进行审核。</p>
        )}
      </article>

      <aside className="memory-version-panel">
        <p className="section-label">Single Runtime</p>
        <h2>运行时说明</h2>
        <p className="phase-note">
          接受后 revision 成为该 Memory item 的 current revision；拒绝或撤销会同步从编译、召回和回放中移除。
        </p>
      </aside>
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="memory-metric-card">
      <small>{label}</small>
      <strong>{value}</strong>
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
