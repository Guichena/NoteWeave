import type { ResearchRunDetail } from "./model";

export type TraceTone = "search" | "read" | "workspace";

export type TraceAuditTarget = {
  traceIndex: number;
  checkpointNo: number | null;
  branchId: string;
};

export type TraceCard = {
  key: string;
  anchorId: string;
  traceType: string;
  traceMessage: string;
  roundNo: number | null;
  narrative: string;
  createdAt: string;
  checkpointNarrative: string;
  auditFocusNarrative: string;
  recoveryNarrative: string;
  sourceNarrative: string;
  outcomeNarrative: string;
  primaryUrl: string;
  primarySourceId: string;
  primarySourceInScope: boolean;
  hasPrimarySourceAsset: boolean;
  checkpointNo: number | null;
  kind: TraceTone;
  focused: boolean;
  selected: boolean;
  auditTarget: TraceAuditTarget;
  payload: Record<string, unknown>;
};

export type TraceGroup = {
  title: string;
  stageLabel: string;
  description: string;
  empty: string;
  tone: TraceTone;
  roundMessage: string;
  entries: TraceCard[];
};

export type TraceDetail = TraceCard & {
  tone: TraceTone;
  title: string;
  stageLabel: string;
  primaryUrlLabel: string;
  provider: string;
  adapter: string;
  snapshotStatus: string;
  querySamples: string[];
  searchAngles: string[];
  readFocuses: string[];
};

export type EvidenceItem = { key: string; title: string; claim: string; meta: string };

type ResearchTimeline = NonNullable<
  NonNullable<ResearchRunDetail["research_process_summary"]>["search_read_timeline"]
>;

export function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

export function asRecords(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.map(asRecord).filter((item) => Object.keys(item).length > 0) : [];
}

export function text(value: unknown, fallback = "-") {
  const normalized = value == null ? "" : String(value).trim();
  return normalized || fallback;
}

export function count(value: unknown) {
  return typeof value === "number" ? value : Number(value) || 0;
}

export function stringList(value: unknown) {
  return Array.isArray(value) ? value.map((item) => text(item, "")).filter(Boolean) : [];
}

export function evidenceItems(value: unknown, prefix: string): EvidenceItem[] {
  return asRecords(value).map((item, index) => ({
    key: text(item["evidence_id"] ?? item["row_id"] ?? item["id"], `${prefix}-${index}`),
    title: text(item["source_title"] ?? item["title"] ?? item["row_id"], `记录 ${index + 1}`),
    claim: text(item["claim_text"] ?? item["claim"] ?? item["candidate_value"], "暂无摘要"),
    meta: [item["status"], item["relation_type"], item["source_id"]].map((item) => text(item, "")).filter(Boolean).join(" · ")
  }));
}

export function buildResearchRounds(
  timeline: ResearchTimeline | null,
  loopRounds: Array<Record<string, unknown>>
) {
  return (timeline?.rounds ?? []).map((round) => ({
    roundNo: round.round_no,
    branchId: text(loopRounds.find((item) => count(item["round_no"]) === round.round_no)?.["branch_id"], ""),
    decision: round.global_decision || round.branch_decision || "推进中",
    reason: text(loopRounds.find((item) => count(item["round_no"]) === round.round_no)?.["reason"], "未返回原因"),
    searchHitCount: String(round.search_hit_count),
    readWindowCount: String(round.read_window_count),
    evidenceCardCount: String(round.evidence_card_count),
    sourceNarrative: round.search_queries.join(" / "),
    deltaNarrative: `本轮新增 ${round.evidence_ids.length} 条证据引用。`,
    outcomeNarrative: round.global_decision ? `本轮结果：${round.global_decision}` : ""
  }));
}

export function buildTraceCards(
  traces: ResearchRunDetail["traces"],
  selectedTraceKey: string,
  sourceIds: ReadonlySet<string>
): TraceCard[] {
  return traces.map((trace, index) => {
    const payload = asRecord(trace.payload);
    const traceType = trace.trace_type.toUpperCase();
    const kind = traceType.includes("SEARCH")
      ? "search" as const
      : traceType.includes("READ") || traceType.includes("FETCH") || traceType.includes("EVIDENCE")
        ? "read" as const
        : "workspace" as const;
    const sourceId = text(payload["source_id"], "");
    const checkpointNo = count(payload["checkpoint_no"]) || null;
    const roundNo = count(payload["round_no"]) || null;
    const key = trace.trace_id || `trace-${index}`;
    return {
      key,
      anchorId: `research-trace-${key}`,
      traceType: trace.trace_type,
      traceMessage: trace.trace_message,
      roundNo,
      narrative: text(payload["narrative"] ?? payload["summary"], "该步骤已记录到 Research trace。"),
      createdAt: trace.created_at,
      checkpointNarrative: checkpointNo ? `关联 checkpoint #${checkpointNo}` : "未关联 checkpoint",
      auditFocusNarrative: "当前审计焦点",
      recoveryNarrative: text(payload["recovery_narrative"], ""),
      sourceNarrative: text(payload["source_title"] ?? payload["source_url"], ""),
      outcomeNarrative: text(payload["outcome"] ?? payload["decision"], ""),
      primaryUrl: text(payload["source_url"] ?? payload["url"], ""),
      primarySourceId: sourceId,
      primarySourceInScope: sourceIds.has(sourceId),
      hasPrimarySourceAsset: Boolean(sourceId),
      checkpointNo,
      kind,
      focused: false,
      selected: selectedTraceKey === key,
      auditTarget: { traceIndex: index, checkpointNo, branchId: text(payload["branch_id"], "") },
      payload
    };
  });
}

export function buildTraceGroups(traceCards: TraceCard[], effectiveRoundNo: number | null): TraceGroup[] {
  return (["search", "read", "workspace"] as const).map((kind) => ({
    title: kind === "search" ? "搜索线索" : kind === "read" ? "阅读与证据" : "闭环与工作台",
    stageLabel: kind === "search" ? "Search" : kind === "read" ? "Read" : "Workspace",
    description: kind === "search" ? "检索 query 与候选入口" : kind === "read" ? "页面读取、证据提取与快照" : "校验、checkpoint 与结果回流",
    empty: "当前阶段还没有可展示的轨迹。",
    tone: kind,
    roundMessage: effectiveRoundNo ? `当前查看第 ${effectiveRoundNo} 轮` : "整体运行轨迹",
    entries: traceCards.filter((trace) => trace.kind === kind)
  }));
}

export function buildTraceDetail(selectedTrace: TraceCard | null): TraceDetail | null {
  if (!selectedTrace) return null;
  return {
    ...selectedTrace,
    tone: selectedTrace.kind,
    title: selectedTrace.kind === "search" ? "搜索" : selectedTrace.kind === "read" ? "阅读" : "闭环",
    stageLabel: selectedTrace.kind.toUpperCase(),
    primaryUrlLabel: selectedTrace.primaryUrl || selectedTrace.primarySourceId || "未显式返回",
    provider: text(selectedTrace.payload["provider"], ""),
    adapter: text(selectedTrace.payload["adapter"], ""),
    snapshotStatus: text(selectedTrace.payload["snapshot_status"], ""),
    querySamples: stringList(selectedTrace.payload["search_queries"] ?? selectedTrace.payload["query_samples"]),
    searchAngles: stringList(selectedTrace.payload["search_angles"]),
    readFocuses: stringList(selectedTrace.payload["read_focuses"] ?? selectedTrace.payload["read_focus"])
  };
}
