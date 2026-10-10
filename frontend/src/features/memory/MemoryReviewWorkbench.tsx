import { useState, type FormEvent } from "react";
import { BookOpen, Check, CircleAlert, LoaderCircle, MessageSquareQuote, Plus, RefreshCw } from "lucide-react";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { MemoryApi } from "./api";
import type { MemoryItem, MemoryReviewDecision } from "./model";
import {
  MEMORY_NEIGHBORHOOD_OPTIONS,
  buildGateChecks,
  describeKind,
  describeNeighborhoods,
  describeSource,
  formatScore,
  groupMemories,
  memoryActions
} from "./memoryItems";
import { errorMessage, useMemoryItems, type AddPreferenceInput } from "./useMemoryItems";

type MemoryReviewWorkbenchProps = {
  workspaceId: string;
  api?: MemoryApi;
};

type AddResult = { tone: "ready" | "review"; text: string; item: MemoryItem | null };

/** 记忆页：说明记忆与资料的分工，添加偏好，确认候选记忆，管理生效中的记忆。 */
export function MemoryReviewWorkbench({ workspaceId, api }: MemoryReviewWorkbenchProps) {
  const { items, loading, error, pendingRevisionId, refresh, addPreference, decide } = useMemoryItems(workspaceId, api);
  const [actionError, setActionError] = useState("");
  const groups = groupMemories(items);
  const pending = [...groups.proposal, ...groups.recheck];

  async function submitDecision(item: MemoryItem, decision: MemoryReviewDecision) {
    setActionError("");
    try {
      await decide(item, decision);
    } catch (failure) {
      setActionError(errorMessage(failure));
    }
  }

  return (
    <section className="memory-page memory-workbench workbench-page" aria-labelledby="memory-title">
      <header className="memory-hero">
        <div>
          <h2 id="memory-title">记忆</h2>
          <p>回答时会参照这些偏好调整语气、结构和用词。记忆只影响表达方式，事实和引用始终来自资料库。</p>
        </div>
        <button
          type="button"
          className="icon-button"
          aria-label="刷新记忆"
          title="刷新"
          disabled={loading || !workspaceId}
          onClick={() => void refresh()}
        >
          <RefreshCw className={loading ? "is-spinning" : ""} size={16} aria-hidden="true" />
        </button>
      </header>

      <dl className="memory-layers" aria-label="记忆与资料的分工">
        <div>
          <dt><MessageSquareQuote size={15} aria-hidden="true" />记忆 · 表达偏好</dt>
          <dd>编入回答的表达控制，决定怎么说：语气、结构、术语和需要避免的写法。你在对话里提出的长期要求会自动整理成候选。</dd>
        </div>
        <div>
          <dt><BookOpen size={15} aria-hidden="true" />资料 · 事实证据</dt>
          <dd>来自资料库和知识库的原文，决定说什么，每个结论都附引用。</dd>
        </div>
      </dl>

      <MemoryComposer disabled={!workspaceId} onAdd={addPreference} />

      {error ? <p className="memory-error" role="alert">{error}</p> : null}
      {actionError ? <p className="memory-error" role="alert">{actionError}</p> : null}

      {pending.length > 0 ? (
        <section className="memory-section" aria-labelledby="memory-pending-heading">
          <div className="memory-section-heading">
            <h3 id="memory-pending-heading">待确认</h3>
            <span>{pending.length}</span>
            <small>未通过自动检查的候选，以及使用效果变差的记忆</small>
          </div>
          <ul className="memory-card-list">
            {pending.map((item) => (
              <li key={item.revision_id}>
                <MemoryPendingCard item={item} busy={pendingRevisionId === item.revision_id} onDecide={submitDecision} />
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section className="memory-section" aria-labelledby="memory-active-heading">
        <div className="memory-section-heading">
          <h3 id="memory-active-heading">生效中</h3>
          {groups.active.length > 0 ? <span>{groups.active.length}</span> : null}
        </div>
        {groups.active.length > 0 ? (
          <ul className="memory-active-list">
            {groups.active.map((item) => (
              <li key={item.revision_id}>
                <MemoryActiveRow item={item} busy={pendingRevisionId === item.revision_id} onDecide={submitDecision} />
              </li>
            ))}
          </ul>
        ) : loading ? (
          <p className="memory-quiet" role="status"><LoaderCircle className="is-spinning" size={14} aria-hidden="true" />正在读取记忆</p>
        ) : (
          <p className="memory-quiet">还没有生效的记忆。在对话里提出长期要求（例如以后先给结论），或在上方添加一条偏好。</p>
        )}
      </section>
    </section>
  );
}

function MemoryComposer({ disabled, onAdd }: {
  disabled: boolean;
  onAdd: (input: AddPreferenceInput) => Promise<MemoryItem | null>;
}) {
  const [text, setText] = useState("");
  const [kind, setKind] = useState<AddPreferenceInput["kind"]>("PREFERENCE");
  const [neighborhood, setNeighborhood] = useState("COMMON");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<AddResult | null>(null);
  const [failure, setFailure] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!text.trim() || submitting) return;
    setSubmitting(true);
    setFailure("");
    setResult(null);
    try {
      const item = await onAdd({ text, kind, neighborhood });
      setText("");
      const failed = item?.gate ? buildGateChecks(item.gate).filter((check) => !check.passed) : [];
      setResult(item?.revision_status === "ACTIVE"
        ? { tone: "ready", text: "已生效：通过了来源、效用、风险和冲突四项检查。", item }
        : { tone: "review", text: `需要确认：${failed.map((check) => check.hint).join("；") || "未通过自动检查"}。`, item });
    } catch (error) {
      setFailure(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="memory-composer" onSubmit={(event) => void submit(event)} aria-label="添加偏好">
      <label className="memory-composer-input">
        <span className="sr-only">偏好内容</span>
        <textarea
          rows={2}
          maxLength={500}
          value={text}
          disabled={disabled}
          onChange={(event) => setText(event.target.value)}
          placeholder={kind === "NEGATIVE" ? "例如：不要使用营销式的夸张措辞" : "例如：回答先给结论，再展开证据"}
        />
      </label>
      <div className="memory-composer-row">
        <div className="memory-kind-switch" role="radiogroup" aria-label="类型">
          {([["PREFERENCE", "偏好"], ["NEGATIVE", "避免"]] as const).map(([value, label]) => (
            <button key={value} type="button" role="radio" aria-checked={kind === value}
              className={kind === value ? "is-active" : ""} onClick={() => setKind(value)}>
              {label}
            </button>
          ))}
        </div>
        <label className="memory-scope-select">
          <span>适用于</span>
          <select value={neighborhood} onChange={(event) => setNeighborhood(event.target.value)}>
            {MEMORY_NEIGHBORHOOD_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </label>
        <button type="submit" className="primary-action" disabled={disabled || submitting || !text.trim()}>
          {submitting ? <LoaderCircle className="is-spinning" size={15} aria-hidden="true" /> : <Plus size={15} aria-hidden="true" />}
          添加
        </button>
      </div>
      {result ? (
        <p className={`memory-add-result is-${result.tone}`} role="status">
          {result.tone === "ready" ? <Check size={14} aria-hidden="true" /> : <CircleAlert size={14} aria-hidden="true" />}
          {result.text}
        </p>
      ) : null}
      {failure ? <p className="memory-error" role="alert">{failure}</p> : null}
    </form>
  );
}

function MemoryPendingCard({ item, busy, onDecide }: {
  item: MemoryItem;
  busy: boolean;
  onDecide: (item: MemoryItem, decision: MemoryReviewDecision) => void;
}) {
  const recheck = item.revision_status !== "PROPOSED";
  const checks = !recheck && item.gate ? buildGateChecks(item.gate) : [];
  return (
    <article className={`memory-card${recheck ? " is-recheck" : ""}`}>
      <p className="memory-card-text">{item.display_text}</p>
      <p className="memory-card-meta">
        <span className={`memory-kind-tag is-${item.candidate_type === "NEGATIVE" ? "negative" : "preference"}`}>{describeKind(item)}</span>
        适用于{describeNeighborhoods(item.task_neighborhoods)} · 来自{describeSource(item)} · {formatRelativeTime(item.created_at)}
      </p>
      {recheck ? (
        <p className="memory-card-note">
          最近几次使用后的反馈不佳，效用降到 {formatScore(item.utility_score)}，已暂停在回答中使用。确认继续使用后恢复，也可以直接停用。
        </p>
      ) : checks.length > 0 ? (
        <ul className="memory-gate-checks" aria-label="门控检查">
          {checks.map((check) => (
            <li key={check.key} className={check.passed ? "is-passed" : "is-failed"} title={check.passed ? "通过" : check.hint}>
              {check.passed ? <Check size={12} strokeWidth={3} aria-hidden="true" /> : <CircleAlert size={12} aria-hidden="true" />}
              <span>{check.label}</span>
              <strong>{check.value}</strong>
            </li>
          ))}
        </ul>
      ) : null}
      {!recheck && checks.some((check) => !check.passed) ? (
        <p className="memory-card-note">{checks.filter((check) => !check.passed).map((check) => check.hint).join("；")}。</p>
      ) : null}
      <div className="memory-card-actions">
        {memoryActions(item).map((action) => (
          <button key={action.decision} type="button" disabled={busy}
            className={action.tone === "primary" ? "primary-action" : action.tone === "danger" ? "memory-danger-button" : "secondary-button"}
            onClick={() => onDecide(item, action.decision)}>
            {action.label}
          </button>
        ))}
      </div>
    </article>
  );
}

function MemoryActiveRow({ item, busy, onDecide }: {
  item: MemoryItem;
  busy: boolean;
  onDecide: (item: MemoryItem, decision: MemoryReviewDecision) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  return (
    <div className="memory-active-row">
      <span className={`memory-kind-tag is-${item.candidate_type === "NEGATIVE" ? "negative" : "preference"}`}>{describeKind(item)}</span>
      <div className="memory-active-copy">
        <strong>{item.display_text}</strong>
        <small>
          适用于{describeNeighborhoods(item.task_neighborhoods)} · 来自{describeSource(item)}
          {item.application_count > 0 ? ` · 已用于 ${item.application_count} 次回答` : " · 尚未使用"}
          {item.memory_scope === "USER" ? " · 仅自己可见" : ""}
        </small>
      </div>
      {confirming ? (
        <span className="memory-active-confirm">
          <button type="button" className="memory-danger-button" disabled={busy} onClick={() => onDecide(item, "REVOKE")}>确认停用</button>
          <button type="button" className="secondary-button" onClick={() => setConfirming(false)}>取消</button>
        </span>
      ) : (
        <button type="button" className="secondary-button memory-active-revoke" disabled={busy}
          aria-label={`停用记忆：${item.display_text}`} onClick={() => setConfirming(true)}>
          停用
        </button>
      )}
    </div>
  );
}
