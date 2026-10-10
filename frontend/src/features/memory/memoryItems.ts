import type { MemoryGate, MemoryItem, MemoryReviewDecision } from "./model";

export type MemoryGroup = "proposal" | "recheck" | "active";

/** 待确认的新版本、需要复核的生效记忆、正常生效的记忆。 */
export function classifyMemory(item: MemoryItem): MemoryGroup {
  if (item.revision_status === "PROPOSED") return "proposal";
  if (item.review_status === "REVIEW_REQUIRED" || item.item_status === "STALE") return "recheck";
  return "active";
}

export function groupMemories(items: MemoryItem[]) {
  const groups: Record<MemoryGroup, MemoryItem[]> = { proposal: [], recheck: [], active: [] };
  items.forEach((item) => groups[classifyMemory(item)].push(item));
  return groups;
}

// 任务邻域决定记忆在哪些回答中被编入表达控制，取值见 MemoryCompilerService。
const NEIGHBORHOOD_LABELS: Record<string, string> = {
  COMMON: "全部回答",
  CHAT: "对话",
  CHAT_QA: "问答",
  CHAT_NOTE: "精读",
  CHAT_WIKI: "Wiki 问答",
  ARTIFACT: "产物",
  RESEARCH: "深度研究"
};

export const MEMORY_NEIGHBORHOOD_OPTIONS = ["COMMON", "CHAT_QA", "CHAT_NOTE", "CHAT_WIKI", "ARTIFACT", "RESEARCH"]
  .map((value) => ({ value, label: NEIGHBORHOOD_LABELS[value] }));

export function describeNeighborhoods(neighborhoods: string[]) {
  if (neighborhoods.length === 0) return NEIGHBORHOOD_LABELS.COMMON;
  return neighborhoods.map((value) => {
    const key = value.toUpperCase();
    if (NEIGHBORHOOD_LABELS[key]) return NEIGHBORHOOD_LABELS[key];
    if (key.startsWith("ARTIFACT_")) return "产物";
    if (key.startsWith("RESEARCH_")) return "深度研究";
    if (key.startsWith("CHAT_")) return "对话";
    return value;
  }).filter((label, index, labels) => labels.indexOf(label) === index).join("、");
}

const SOURCE_LABELS: Record<string, string> = {
  USER_FEEDBACK: "你的反馈",
  PROJECT_DECISION: "项目决策",
  MODEL_INFERENCE: "模型推断",
  ARTIFACT_FEEDBACK: "产物反馈",
  CONVERSATION_FEEDBACK: "对话反馈",
  LEGACY_MEMORY_VERSION: "早期记忆"
};

/** 记忆的来源：候选记忆以原始信号为准，直接提交的观察记录以版本来源为准。 */
export function describeSource(item: MemoryItem) {
  const source = item.gate?.source_type || item.provenance_type;
  // 从对话中自动提取的候选：用户明确要求长期遵守的按对话反馈计分，其余为模型推断
  if (item.gate?.source_ref?.startsWith("conversation-message:")) {
    return source === "MODEL_INFERENCE" ? "对话推断" : "对话中的明确要求";
  }
  return SOURCE_LABELS[source] ?? "系统记录";
}

export function describeKind(item: Pick<MemoryItem, "candidate_type">) {
  if (item.candidate_type === "NEGATIVE") return "避免";
  if (item.candidate_type === "DECISION") return "约定";
  return "偏好";
}

export type MemoryGateCheck = {
  key: "evidence" | "utility" | "risk" | "conflict" | "scope";
  label: string;
  value: string;
  passed: boolean;
  /** 未通过时的说明。 */
  hint: string;
};

export function buildGateChecks(gate: MemoryGate): MemoryGateCheck[] {
  const conflict = gate.conflict_status === "CONFLICTING_ACTIVE_MEMORY";
  const checks: MemoryGateCheck[] = [
    {
      key: "evidence",
      label: "来源可信度",
      value: formatScore(gate.evidence_score),
      passed: gate.evidence_score >= gate.evidence_threshold,
      hint: `来源可信度低于 ${formatScore(gate.evidence_threshold)}，${SOURCE_LABELS[gate.source_type] ?? "该来源"}需要你确认`
    },
    {
      key: "utility",
      label: "预期效用",
      value: formatScore(gate.utility_score),
      passed: gate.utility_score >= gate.utility_threshold,
      hint: `预期效用低于 ${formatScore(gate.utility_threshold)}，对后续回答的帮助有限`
    },
    {
      key: "risk",
      label: "风险",
      value: formatScore(gate.risk_score),
      passed: gate.risk_score < gate.risk_threshold,
      hint: `风险达到 ${formatScore(gate.risk_threshold)} 以上`
    },
    {
      key: "conflict",
      label: "冲突",
      value: conflict ? "有" : "无",
      passed: !conflict,
      hint: "与一条生效中的记忆意思相反"
    }
  ];
  if (gate.scope_status && gate.scope_status !== "VALID") {
    checks.push({ key: "scope", label: "适用范围", value: "无效", passed: false, hint: "缺少适用范围" });
  }
  return checks;
}

export type MemoryAction = {
  decision: MemoryReviewDecision;
  label: string;
  tone: "primary" | "secondary" | "danger";
};

export function memoryActions(item: MemoryItem): MemoryAction[] {
  const group = classifyMemory(item);
  if (group === "active") {
    return [{ decision: "REVOKE", label: "停用", tone: "secondary" }];
  }
  if (group === "recheck") {
    return [
      { decision: "ACCEPT", label: "继续使用", tone: "primary" },
      { decision: "REVOKE", label: "停用", tone: "danger" }
    ];
  }
  if (item.conflict_status === "CONFLICTING_ACTIVE_MEMORY") {
    return [
      { decision: "REPLACE_EXISTING", label: "替换原有记忆", tone: "primary" },
      { decision: "REJECT", label: "保留原有记忆", tone: "secondary" }
    ];
  }
  return [
    { decision: "ACCEPT", label: "生效", tone: "primary" },
    { decision: "REJECT", label: "忽略", tone: "secondary" }
  ];
}

export function formatScore(value: number) {
  return Number.isFinite(value) ? value.toFixed(2) : "0.00";
}
