import type { ResearchClosedLoopState } from "./model";

/** 研究表单元格面向用户的展示状态，由 Worker 的多种 cell status 归并而来。 */
export type CellTone = "verified" | "filled" | "conflict" | "repair" | "missing" | "queued" | "pending";

export type ResearchEvidence = {
  evidenceId: string;
  sourceTitle: string;
  sourceUrl: string;
  quote: string;
  claim: string;
  relation: string;
  supportScore: number | null;
};

export type ResearchTableCell = {
  id: string;
  rowId: string;
  columnKey: string;
  value: string;
  status: string;
  tone: CellTone;
  confidence: number | null;
  verifierDecision: string;
  repairCount: number;
  branchId: string;
  evidenceIds: string[];
};

export type ResearchTableRow = { id: string; title: string; cells: Map<string, ResearchTableCell> };

export type ResearchTable = {
  columns: Array<{ key: string; label: string }>;
  rows: ResearchTableRow[];
  counts: Record<CellTone, number> & { total: number };
  evidenceById: Map<string, ResearchEvidence>;
};

const TONE_BY_STATUS: Record<string, CellTone> = {
  VERIFIED: "verified",
  FILLED: "filled",
  CANDIDATE: "filled",
  CONFLICTED: "conflict",
  NEEDS_REPAIR: "repair",
  MISSING: "missing",
  EMPTY: "queued",
  BLOCKED: "missing",
  PENDING: "pending"
};

export const CELL_TONE_LABEL: Record<CellTone, string> = {
  verified: "已验证",
  filled: "待核验",
  conflict: "有冲突",
  repair: "待修复",
  missing: "缺证据",
  queued: "待检索",
  pending: "进行中"
};

export const VERIFIER_DECISION_LABEL: Record<string, string> = {
  SUPPORTS: "证据支持",
  PARTIALLY_SUPPORTS: "部分支持",
  CONTRADICTS: "证据矛盾",
  NOT_ENOUGH_INFO: "证据不足"
};

export function cellTone(status: string): CellTone {
  return TONE_BY_STATUS[status.toUpperCase()] ?? "pending";
}

function text(value: unknown) {
  return typeof value === "string" ? value : value == null ? "" : String(value);
}

function num(value: unknown) {
  const parsed = typeof value === "number" ? value : typeof value === "string" && value ? Number(value) : NaN;
  return Number.isFinite(parsed) ? parsed : null;
}

// 列名优先用后端单元格上的 column_label；旧数据没有时按内置中文名或 snake_case key 兜底。
const BUILT_IN_LABELS: Record<string, string> = {
  subject: "研究对象",
  answer: "结论",
  key_evidence: "关键证据",
  limitations: "局限与风险",
  implications: "影响与启示"
};

function humanize(key: string) {
  return BUILT_IN_LABELS[key] ?? key.replace(/[_-]+/g, " ").trim().replace(/^\w/, (char) => char.toUpperCase());
}

function rowLabel(rowId: string, title: string) {
  if (!title || title.toLowerCase() === "subject") return BUILT_IN_LABELS[rowId] ?? (title || rowId);
  return title;
}

export function buildResearchTable(state: Pick<ResearchClosedLoopState, "cells" | "rows" | "source_evidence"> | null | undefined): ResearchTable {
  const counts = { verified: 0, filled: 0, conflict: 0, repair: 0, missing: 0, queued: 0, pending: 0, total: 0 };
  const evidenceById = new Map<string, ResearchEvidence>();
  for (const item of state?.source_evidence ?? []) {
    const id = text(item.evidence_id);
    if (!id) continue;
    evidenceById.set(id, {
      evidenceId: id,
      sourceTitle: text(item.source_title) || text(item.source_url) || id,
      sourceUrl: text(item.source_url),
      quote: text(item.quote_text),
      claim: text(item.claim_text),
      relation: text(item.relation_type),
      supportScore: num(item.support_score)
    });
  }

  const columnKeys: string[] = [];
  const columnLabels = new Map<string, string>();
  const rowsById = new Map<string, ResearchTableRow>();
  const rowTitle = new Map((state?.rows ?? []).map((row) => [text(row.row_id), text(row.source_title)] as const));

  for (const raw of state?.cells ?? []) {
    const rowId = text(raw.row_id);
    const columnKey = text(raw.column_key);
    if (!rowId || !columnKey) continue;
    if (!columnKeys.includes(columnKey)) columnKeys.push(columnKey);
    const columnLabel = text(raw.column_label);
    if (columnLabel && !columnLabels.has(columnKey)) columnLabels.set(columnKey, columnLabel);
    let row = rowsById.get(rowId);
    if (!row) {
      row = { id: rowId, title: rowLabel(rowId, rowTitle.get(rowId) ?? ""), cells: new Map() };
      rowsById.set(rowId, row);
    }
    const status = text(raw.status).toUpperCase();
    const tone = cellTone(status);
    counts[tone] += 1;
    counts.total += 1;
    row.cells.set(columnKey, {
      id: text(raw.cell_id) || `${rowId}:${columnKey}`,
      rowId,
      columnKey,
      value: text(raw.candidate_value),
      status,
      tone,
      confidence: num(raw.confidence),
      verifierDecision: text(raw.last_verifier_decision).toUpperCase(),
      repairCount: num(raw.repair_count) ?? 0,
      branchId: text(raw.branch_id),
      evidenceIds: Array.isArray(raw.evidence_refs) ? raw.evidence_refs.map(text).filter(Boolean) : []
    });
  }

  return {
    columns: columnKeys.map((key) => ({ key, label: columnLabels.get(key) ?? humanize(key) })),
    rows: [...rowsById.values()],
    counts,
    evidenceById
  };
}

/** 按报告中首次引用的顺序排列 evidence id，用于给引用角标编号 [1]、[2]。 */
export function orderReportEvidence(markdown: string) {
  const order: string[] = [];
  for (const match of markdown.matchAll(/\[evidence[:-]([^\]]+)\]/g)) {
    const id = match[1].trim();
    if (!order.includes(id)) order.push(id);
  }
  return order;
}
