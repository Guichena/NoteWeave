import { useEffect, useState } from "react";
import {
  type AppendMemoryVersionInput,
  type MemoryReviewDecision,
  type MemoryReviewItem,
  type MemoryReviewKind
} from "./model";
import { useMemoryReview } from "./useMemoryReview";

type MemoryReviewWorkbenchProps = {
  workspaceId: string;
};

export function MemoryReviewWorkbench({ workspaceId }: MemoryReviewWorkbenchProps) {
  const [kind, setKind] = useState<MemoryReviewKind>("ALL");
  const [reason, setReason] = useState("");
  const [statement, setStatement] = useState("");
  const [neighborhoods, setNeighborhoods] = useState("");
  const [styleConstraints, setStyleConstraints] = useState("");
  const [structureConstraints, setStructureConstraints] = useState("");
  const [terminologyPolicy, setTerminologyPolicy] = useState("");
  const [forbiddenPatterns, setForbiddenPatterns] = useState("");
  const [interactionPolicy, setInteractionPolicy] = useState("");
  const [reviewChecklist, setReviewChecklist] = useState("");
  const [notice, setNotice] = useState("");
  const {
    queue,
    selectedReviewId,
    versions,
    selectedItem,
    selectedVersion,
    queueLoading,
    versionsLoading,
    mutating,
    error,
    lastDecision,
    refreshQueue,
    selectReview,
    decide,
    appendVersion,
    selectVersion
  } = useMemoryReview(workspaceId, kind);

  useEffect(() => {
    if (!selectedVersion) {
      setStatement(selectedItem?.statement ?? "");
      setNeighborhoods(selectedItem?.task_neighborhoods.join("\n") ?? "");
      setStyleConstraints("");
      setStructureConstraints("");
      setTerminologyPolicy("");
      setForbiddenPatterns("");
      setInteractionPolicy("");
      setReviewChecklist("");
      return;
    }
    setStatement(selectedVersion.canonical_statement);
    setNeighborhoods(selectedVersion.task_neighborhoods.join("\n"));
    setStyleConstraints(selectedVersion.compile_hints.style_constraints.join("\n"));
    setStructureConstraints(selectedVersion.compile_hints.structure_constraints.join("\n"));
    setTerminologyPolicy(selectedVersion.compile_hints.terminology_policy.join("\n"));
    setForbiddenPatterns(selectedVersion.compile_hints.forbidden_patterns.join("\n"));
    setInteractionPolicy(selectedVersion.compile_hints.interaction_policy.join("\n"));
    setReviewChecklist(selectedVersion.compile_hints.review_checklist.join("\n"));
  }, [selectedItem?.review_id, selectedVersion?.memory_version_id]);

  async function submitDecision(item: MemoryReviewItem, decision: MemoryReviewDecision) {
    setNotice("");
    try {
      const result = await decide(item, decision, reason);
      setReason("");
      setNotice(buildDecisionNotice(result.decision, result.review_status, result.revoked_memory_object_ids));
    } catch {}
  }

  async function submitVersion() {
    if (!selectedItem || selectedItem.review_kind !== "OBJECT") {
      return;
    }
    const input: AppendMemoryVersionInput = {
      canonical_statement: statement.trim(),
      task_neighborhoods: parseLines(neighborhoods),
      style_constraints: parseLines(styleConstraints),
      structure_constraints: parseLines(structureConstraints),
      terminology_policy: parseLines(terminologyPolicy),
      forbidden_patterns: parseLines(forbiddenPatterns),
      interaction_policy: parseLines(interactionPolicy),
      review_checklist: parseLines(reviewChecklist)
    };
    if (!input.canonical_statement || input.task_neighborhoods.length === 0) {
      setNotice("正文和至少一个 task neighborhood 为必填项。");
      return;
    }
    setNotice("");
    try {
      const version = await appendVersion(selectedItem.review_id, input);
      setNotice(`已追加 Memory v${version.version_no}，旧版本保持不可变。`);
    } catch {}
  }

  return (
    <section className="memory-workbench">
      <aside className="memory-queue-panel">
        <p className="section-label">Memory Review</p>
        <h2>人工审核队列</h2>
        <p className="phase-note">
          Memory 只保存偏好、约束与可复用检查规则，不替代 Source/Knowledge 事实证据。
        </p>
        <div className="memory-filter-row">
          {(["ALL", "CANDIDATE", "OBJECT"] as MemoryReviewKind[]).map((entry) => (
            <button
              key={entry}
              className={kind === entry ? "active filter-pill" : "filter-pill"}
              disabled={queueLoading || mutating}
              onClick={() => setKind(entry)}
            >
              {entry}
            </button>
          ))}
        </div>
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
              key={`${item.review_kind}-${item.review_id}`}
              className={selectedReviewId === item.review_id ? "active memory-review-card" : "memory-review-card"}
              disabled={mutating}
              onClick={() => void selectReview(item)}
            >
              <span>{item.review_kind} · priority {item.priority}</span>
              <strong>{item.statement}</strong>
              <small>{item.review_status} · {item.lifecycle_status || "-"}</small>
              <small>risk {formatScore(item.risk_score)} · utility {formatScore(item.utility_score)}</small>
            </button>
          ))}
          {!queueLoading && queue.length === 0 ? (
            <p className="empty-state">当前筛选下没有待审核 Memory。</p>
          ) : null}
        </div>
      </aside>

      <article className="memory-review-panel">
        <p className="section-label">Review Decision</p>
        {selectedItem ? (
          <>
            <h2>{selectedItem.review_kind === "CANDIDATE" ? "候选规则审核" : "Memory 对象复核"}</h2>
            <div className="memory-statement-card">
              <strong>{selectedItem.statement}</strong>
              <span>{selectedItem.task_neighborhoods.join(" / ") || "未声明 task neighborhood"}</span>
              <small>policy={selectedItem.policy_version || "-"}</small>
            </div>
            <div className="memory-metric-grid">
              <Metric label="Review" value={selectedItem.review_status} />
              <Metric label="Lifecycle" value={selectedItem.lifecycle_status || "-"} />
              <Metric label="Evidence Gate" value={selectedItem.evidence_gate_status || "-"} />
              <Metric label="Conflict" value={selectedItem.conflict_status || "-"} />
              <Metric label="Risk" value={formatScore(selectedItem.risk_score)} />
              <Metric label="Utility" value={formatScore(selectedItem.utility_score)} />
            </div>
            <label className="input-block">
              <span>审核理由（写入审计）</span>
              <textarea
                rows={3}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="说明批准、拒绝、替换或撤销的依据"
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
            {selectedItem.review_kind === "CANDIDATE"
              && selectedItem.conflict_status === "CONFLICTING_ACTIVE_MEMORY" ? (
                <p className="memory-warning">
                  冲突 Candidate 不能普通批准；必须明确选择“替换现有 Memory”或拒绝。
                </p>
              ) : null}
            {notice ? <p className="status-line">{notice}</p> : null}
            {error ? <p className="memory-error">{error}</p> : null}
            {lastDecision ? (
              <p className="phase-note">
                最近决策：{lastDecision.decision} · {lastDecision.review_status} · audit={lastDecision.review_decision_id}
              </p>
            ) : null}
          </>
        ) : (
          <p className="empty-state">从左侧选择一个 Candidate 或 Object 进行审核。</p>
        )}
      </article>

      <aside className="memory-version-panel">
        <p className="section-label">Immutable Versions</p>
        <h2>版本与修正规则</h2>
        {selectedItem?.review_kind === "OBJECT" ? (
          <>
            <div className="memory-version-list">
              {versionsLoading ? <p className="phase-note">版本加载中…</p> : null}
              {versions.map((version) => (
                <button
                  key={version.memory_version_id}
                  className={selectedVersion?.memory_version_id === version.memory_version_id ? "active memory-version-card" : "memory-version-card"}
                  disabled={mutating}
                  onClick={() => selectVersion(version.memory_version_id)}
                >
                  <strong>v{version.version_no} · {version.status}</strong>
                  <small>{version.policy_version}</small>
                  <small>{formatDateTime(version.valid_from)}</small>
                </button>
              ))}
            </div>
            <details className="memory-version-editor" open>
              <summary>追加修正版</summary>
              <label className="rail-field">
                <span>Canonical Statement</span>
                <textarea rows={4} value={statement} onChange={(event) => setStatement(event.target.value)} />
              </label>
              <MemoryListField label="Task Neighborhoods" value={neighborhoods} onChange={setNeighborhoods} required />
              <MemoryListField label="Style Constraints" value={styleConstraints} onChange={setStyleConstraints} />
              <MemoryListField label="Structure Constraints" value={structureConstraints} onChange={setStructureConstraints} />
              <MemoryListField label="Terminology Policy" value={terminologyPolicy} onChange={setTerminologyPolicy} />
              <MemoryListField label="Forbidden Patterns" value={forbiddenPatterns} onChange={setForbiddenPatterns} />
              <MemoryListField label="Interaction Policy" value={interactionPolicy} onChange={setInteractionPolicy} />
              <MemoryListField label="Review Checklist" value={reviewChecklist} onChange={setReviewChecklist} />
              <button disabled={mutating || !statement.trim()} onClick={() => void submitVersion()}>
                {mutating ? "提交中…" : "追加 immutable version"}
              </button>
            </details>
          </>
        ) : (
          <p className="empty-state">选择 Object review 后可查看版本链并追加修正版。</p>
        )}
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

function MemoryListField({
  label,
  value,
  onChange,
  required = false
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
}) {
  return (
    <label className="rail-field">
      <span>{label}{required ? " *" : ""}</span>
      <textarea
        rows={2}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder="每行一项"
      />
    </label>
  );
}

export function decisionOptions(item: MemoryReviewItem): Array<{
  decision: MemoryReviewDecision;
  label: string;
  tone: "primary" | "secondary" | "danger";
}> {
  if (item.review_kind === "OBJECT") {
    return [
      { decision: "APPROVE", label: "批准恢复 ACTIVE", tone: "primary" },
      { decision: "REVOKE", label: "撤销 Memory", tone: "danger" }
    ];
  }
  if (item.conflict_status === "CONFLICTING_ACTIVE_MEMORY") {
    return [
      { decision: "REPLACE_EXISTING", label: "替换现有 Memory", tone: "primary" },
      { decision: "REJECT", label: "拒绝 Candidate", tone: "danger" }
    ];
  }
  return [
    { decision: "APPROVE", label: "批准并提升", tone: "primary" },
    { decision: "REJECT", label: "拒绝 Candidate", tone: "danger" }
  ];
}

function parseLines(value: string) {
  return Array.from(new Set(value
    .split(/\r?\n|,/)
    .map((entry) => entry.trim())
    .filter(Boolean)));
}

function formatScore(value: number) {
  return Number.isFinite(value) ? value.toFixed(2) : "0.00";
}

function formatDateTime(value: string) {
  return value ? new Date(value).toLocaleString("zh-CN") : "时间未知";
}

function buildDecisionNotice(decision: string, status: string, revokedIds: string[]) {
  const revoked = revokedIds.length > 0 ? `；撤销 ${revokedIds.length} 个旧 Memory` : "";
  return `审核已提交：${decision} → ${status}${revoked}`;
}
