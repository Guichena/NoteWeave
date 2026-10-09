import type { CreateMemorySignalInput, MemoryGate, MemoryItem } from "../../features/memory/model";

// 记忆页设计数据：生效中的偏好、未通过门控的候选（模型推断、与生效记忆冲突），以及使用效果变差需要复核的记忆。
// 这份数据是可变的：添加偏好、确认和停用都会改变后续的列表，便于演示完整流程。

const now = Date.now();
const minutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString();

const GATE_BASE: MemoryGate = {
  result: "READY", source_type: "USER_FEEDBACK",
  evidence_score: 0.98, evidence_threshold: 0.7,
  utility_score: 0.93, utility_threshold: 0.6,
  risk_score: 0.15, risk_threshold: 0.7,
  scope_status: "VALID", conflict_status: "NO_CONFLICT", policy_version: "memory-candidate-policy-v1"
};

function item(id: string, text: string, overrides: Partial<MemoryItem> = {}): MemoryItem {
  return {
    memory_item_id: id,
    revision_id: id,
    memory_scope: "WORKSPACE",
    item_status: "ACTIVE",
    review_status: "APPROVED",
    revision_status: "ACTIVE",
    version_no: 1,
    display_text: text,
    candidate_type: "PREFERENCE",
    task_neighborhoods: ["COMMON"],
    provenance_type: "MEMORY_CANDIDATE",
    utility_score: 0.9,
    application_count: 0,
    conflict_status: "NO_CONFLICT",
    gate: GATE_BASE,
    last_confirmed_at: minutesAgo(60),
    created_at: minutesAgo(60),
    ...overrides
  };
}

let items: MemoryItem[] = [
  item("mem-inferred", "用户可能偏好更长、更细的解释", {
    item_status: "EMPTY", review_status: "REVIEW_REQUIRED", revision_status: "PROPOSED",
    task_neighborhoods: ["CHAT_NOTE"], created_at: minutesAgo(12),
    gate: { ...GATE_BASE, result: "NEEDS_REVIEW", source_type: "MODEL_INFERENCE", evidence_score: 0.35, utility_score: 0.4, risk_score: 0.65 }
  }),
  item("mem-conflict", "不要先给结论，按推导顺序展开", {
    item_status: "EMPTY", review_status: "REVIEW_REQUIRED", revision_status: "PROPOSED", candidate_type: "NEGATIVE",
    conflict_status: "CONFLICTING_ACTIVE_MEMORY", created_at: minutesAgo(30),
    gate: { ...GATE_BASE, result: "NEEDS_REVIEW", risk_score: 0.85, conflict_status: "CONFLICTING_ACTIVE_MEMORY" }
  }),
  item("mem-recheck", "引用统一放在段落末尾，不插在句中", {
    review_status: "REVIEW_REQUIRED", utility_score: 0.38, application_count: 6, created_at: minutesAgo(4_300)
  }),
  item("mem-conclusion", "回答先给结论，再展开证据", { application_count: 14, created_at: minutesAgo(8_600) }),
  item("mem-terms", "技术术语保留英文原文，不做翻译", { application_count: 22, created_at: minutesAgo(9_900) }),
  item("mem-marketing", "不要使用营销式的夸张措辞", { candidate_type: "NEGATIVE", application_count: 9, created_at: minutesAgo(7_200) }),
  item("mem-note-page", "精读回答保留原文所在的页码", {
    task_neighborhoods: ["CHAT_NOTE"], application_count: 5, created_at: minutesAgo(2_900)
  }),
  item("mem-artifact", "产物默认使用中文，代码与命令保持英文", {
    task_neighborhoods: ["ARTIFACT"], application_count: 3, created_at: minutesAgo(1_500)
  }),
  item("mem-personal", "称呼我为你，不用您", {
    memory_scope: "USER", provenance_type: "USER_FEEDBACK", gate: null,
    task_neighborhoods: ["CHAT_QA"], application_count: 2, created_at: minutesAgo(700)
  })
];

const signals = new Map<string, CreateMemorySignalInput>();
let sequence = 0;

export function listItems() {
  return items;
}

export function createSignal(input: CreateMemorySignalInput) {
  const signalId = `signal-${++sequence}`;
  signals.set(signalId, input);
  return { signal_id: signalId };
}

/** 用户反馈通过来源、效用和风险检查；与"先给结论"意思相反的规则按冲突处理。 */
export function promoteSignals(signalIds: string[]) {
  const candidates = signalIds.flatMap((signalId) => {
    const signal = signals.get(signalId);
    if (!signal) return [];
    const id = `mem-new-${signalId}`;
    const conflicting = signal.signal_type === "NEGATIVE" && /结论/.test(signal.signal_text)
      && items.some((entry) => entry.memory_item_id === "mem-conclusion");
    const next = item(id, signal.signal_text, {
      candidate_type: signal.signal_type,
      task_neighborhoods: [signal.task_neighborhood],
      created_at: new Date().toISOString(),
      ...(conflicting ? {
        item_status: "EMPTY", review_status: "REVIEW_REQUIRED", revision_status: "PROPOSED",
        conflict_status: "CONFLICTING_ACTIVE_MEMORY",
        gate: { ...GATE_BASE, result: "NEEDS_REVIEW", risk_score: 0.85, conflict_status: "CONFLICTING_ACTIVE_MEMORY" }
      } : {})
    });
    items = [next, ...items];
    return [{ candidate_id: id, review_status: conflicting ? "NEEDS_REVIEW" : "READY", conflict_status: next.conflict_status }];
  });
  return { candidates, memory_objects: [] };
}

export function decide(revisionId: string, decision: string) {
  const target = items.find((entry) => entry.revision_id === revisionId);
  if (!target) return undefined;
  const revoked: string[] = [];
  if (decision === "REJECT" || decision === "REVOKE") {
    items = items.filter((entry) => entry !== target);
    if (decision === "REVOKE") revoked.push(target.memory_item_id);
  } else {
    if (decision === "REPLACE_EXISTING") {
      revoked.push("mem-conclusion");
      items = items.filter((entry) => entry.memory_item_id !== "mem-conclusion");
    }
    items = items.map((entry) => entry === target ? {
      ...entry, item_status: "ACTIVE", review_status: "APPROVED", revision_status: "ACTIVE",
      conflict_status: "NO_CONFLICT", last_confirmed_at: new Date().toISOString()
    } : entry);
  }
  const status = decision === "REJECT" ? "REJECTED" : decision === "REVOKE" ? "REVOKED" : "ACTIVE";
  return { revision_id: revisionId, memory_item_id: target.memory_item_id, status, revoked_memory_item_ids: revoked };
}
