import { useState } from "react";
import { ArrowRight, BadgeCheck, BrainCircuit, Inbox, LoaderCircle, RefreshCw, ScanSearch } from "lucide-react";
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
        <p className="section-label">01 · Candidate</p>
        <div className="memory-panel-heading">
          <h2>统一审核队列</h2>
          <span aria-label={`${queue.length} 条待审核`}>{queue.length}</span>
        </div>
        <p className="phase-note">
          仅显示当前运行时产生、需要人工判断的 revision；历史对象不会重复进入队列。
        </p>
        <button
          className="secondary-button"
          disabled={queueLoading || mutating}
          onClick={() => void refreshQueue()}
        >
          <RefreshCw className={queueLoading ? "is-spinning" : ""} size={15} aria-hidden="true" />
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
        <p className="section-label">02 · Review</p>
        <div className="memory-runtime-inline">
          <span className="memory-runtime-inline-index">03</span>
          <div>
            <strong>运行时生效</strong>
            <span>接受的 revision 成为当前版本；拒绝或撤销会同步退出编译、召回与回放。</span>
          </div>
        </div>
        {notice ? <p className="status-line" role="status">{notice}</p> : null}
        {error ? <p className="memory-error" role="alert">{error}</p> : null}
        {lastDecision ? (
          <p className="phase-note">
            最近决策：{lastDecision.status} · revision={lastDecision.revision_id}
          </p>
        ) : null}
        {queueLoading && !selectedItem ? (
          <div className="memory-review-loading" role="status" aria-live="polite">
            <span className="memory-review-loading-icon" aria-hidden="true">
              <LoaderCircle className="is-spinning" size={22} />
            </span>
            <div>
              <strong>正在读取审核队列</strong>
              <p>同步当前运行时的 Memory revision 与审核状态。</p>
            </div>
            <span className="memory-review-loading-line" aria-hidden="true" />
            <span className="memory-review-loading-line is-short" aria-hidden="true" />
          </div>
        ) : selectedItem ? (
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
          </>
        ) : (
          <div className="memory-empty-state" aria-label="Memory 审核流程">
            <div className="memory-empty-heading">
              <span className="empty-state-icon" aria-hidden="true"><BrainCircuit size={22} /></span>
              <div className="empty-state-copy">
                <span className="memory-empty-kicker">{queue.length} 条 revision 等待审核</span>
                <strong>{queue.length > 0 ? "选择一条 Memory revision" : "等待新的 Memory revision"}</strong>
                <p>
                  {queue.length > 0
                    ? "从左侧队列选择候选，随后核对来源、冲突状态和效用分数。"
                    : "当前运行时没有待审核候选，新 revision 产生后会进入这条审核流程。"}
                </p>
              </div>
            </div>
            <ol className="memory-review-flow" aria-label="候选到审核决策的阶段">
              <li className={queue.length > 0 ? "is-current" : "is-waiting"}>
                <span className="memory-review-flow-icon" aria-hidden="true"><Inbox size={17} /></span>
                <div>
                  <small>01 · Candidate</small>
                  <strong>进入审核队列</strong>
                  <span>{queue.length} 条待审核</span>
                </div>
              </li>
              <li>
                <span className="memory-review-flow-icon" aria-hidden="true"><ScanSearch size={17} /></span>
                <div>
                  <small>02 · Review</small>
                  <strong>核对来源与冲突</strong>
                  <span>人工决定接受或拒绝</span>
                </div>
              </li>
            </ol>
            <div className="memory-review-handoff" aria-label="审核后的运行时去向">
              <ArrowRight size={18} aria-hidden="true" />
              <div>
                <small>Next · Runtime</small>
                <strong>审核通过后进入运行时</strong>
                <span>右侧显示唯一生效版本与召回约束。</span>
              </div>
            </div>
          </div>
        )}
      </article>

      <aside className="memory-version-panel">
        <p className="section-label">03 · Runtime</p>
        <div className="memory-runtime-heading">
          <span aria-hidden="true"><BadgeCheck size={18} /></span>
          <h2>运行时生效</h2>
        </div>
        <p className="phase-note">
          接受后 revision 成为该 Memory item 的 current revision；拒绝或撤销会同步从编译、召回和回放中移除。
        </p>
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
