import { useMemo, useState } from "react";
import { researchApi, type ResearchApi } from "./api";
import type { ResearchCell, ResearchEvidenceLevel } from "./model";
import { formatConfidence, useRunResource } from "./useRunResource";

export const CELL_STATUS_LABELS: Record<string, string> = {
  GAP: "待补充",
  CANDIDATE_READY: "有候选值",
  VERIFIED: "已验证",
  CONFLICTED: "存在冲突"
};

/** 单元格按依据强弱分四级，没通过核验的单元格也能看到找到了什么 */
export const EVIDENCE_LEVEL_LABELS: Record<ResearchEvidenceLevel, string> = {
  VERIFIED: "已核实",
  SOURCED: "有出处，待核实",
  RELATED: "相关材料",
  NOT_FOUND: "未找到"
};

const CONFLICT_LABELS: Record<string, string> = {
  TYPED_FACT_CONTRADICTION: "数值或类型矛盾",
  SEMANTIC_CONTRADICTION: "语义矛盾"
};

type ResearchCellsPanelProps = {
  workspaceId: string;
  researchRunId: string;
  refreshKey: string;
  api?: Pick<ResearchApi, "listCells">;
};

/** 旧接口没有级别字段时按单元格状态推断 */
export function evidenceLevel(cell: ResearchCell): ResearchEvidenceLevel {
  if (cell.evidence_level) return cell.evidence_level;
  return cell.cell_status === "VERIFIED" ? "VERIFIED" : "NOT_FOUND";
}

/** 单元格标签：存在冲突、已有候选值仍在核验时按状态显示，其余按依据级别显示 */
export function cellLabel(cell: ResearchCell): string {
  const level = evidenceLevel(cell);
  if (level !== "VERIFIED" && level !== "SOURCED" && CELL_STATUS_LABELS[cell.cell_status] && cell.cell_status !== "GAP") {
    return CELL_STATUS_LABELS[cell.cell_status];
  }
  return EVIDENCE_LEVEL_LABELS[level];
}

/** 研究表格的单元格：按依据强弱显示结论或线索；点击单元格查看等价表述、线索原文与冲突详情。 */
export function ResearchCellsPanel({ workspaceId, researchRunId, refreshKey, api = researchApi }: ResearchCellsPanelProps) {
  const { data, unavailable, error } = useRunResource(
    () => api.listCells(workspaceId, researchRunId),
    `${researchRunId}:${refreshKey}`
  );
  const [selectedKey, setSelectedKey] = useState("");
  const matrix = useMemo(() => buildMatrix(data ?? []), [data]);

  if (unavailable || (!data && !error)) return null;
  if (!data) return <div className="task-card"><small role="alert">{error}</small></div>;
  if (data.length === 0) return null;

  const selected = data.find((cell) => cell.cell_key === selectedKey) ?? null;
  const counts = countLevels(data);

  return (
    <section className="task-card research-cells-panel" aria-label="单元格结论">
      <div className="research-plan-header">
        <strong>单元格结论</strong>
        <small>
          {(Object.keys(EVIDENCE_LEVEL_LABELS) as ResearchEvidenceLevel[])
            .filter((level) => counts[level])
            .map((level) => `${EVIDENCE_LEVEL_LABELS[level]} ${counts[level]}`)
            .join(" · ")}
        </small>
      </div>
      <div className="research-cells-scroll">
        <table className="research-cells-table">
          <thead>
            <tr>
              <th scope="col">研究对象</th>
              {matrix.columns.map((column) => <th key={column} scope="col">{column}</th>)}
            </tr>
          </thead>
          <tbody>
            {matrix.entities.map((entity) => (
              <tr key={entity}>
                <th scope="row">{entity}</th>
                {matrix.columns.map((column) => {
                  const cell = matrix.lookup.get(`${entity}\u0000${column}`);
                  if (!cell) return <td key={column} className="research-cell-empty">-</td>;
                  const level = evidenceLevel(cell);
                  const openConflicts = cell.conflicts.filter((conflict) => conflict.status === "OPEN").length;
                  const label = cellLabel(cell);
                  const value = level === "SOURCED" ? cell.lead_value : cell.candidate_value;
                  const leadCount = cell.lead_quotes?.length ?? 0;
                  return (
                    <td key={column}>
                      <button
                        type="button"
                        className={`research-cell status-${cell.cell_status.toLowerCase()} level-${level.toLowerCase()}${cell.cell_key === selectedKey ? " active" : ""}`}
                        aria-pressed={cell.cell_key === selectedKey}
                        onClick={() => setSelectedKey(cell.cell_key === selectedKey ? "" : cell.cell_key)}
                      >
                        <span className="research-cell-value">{value || label}</span>
                        <small>
                          {label}
                          {cell.candidate_value && cell.independent_source_count > 0 ? ` · ${cell.independent_source_count} 个独立来源` : ""}
                          {cell.candidate_value && cell.confidence_ppm !== null ? ` · 置信度 ${formatConfidence(cell.confidence_ppm)}` : ""}
                          {level === "RELATED" && leadCount > 0 ? ` · ${leadCount} 段原文` : ""}
                          {openConflicts > 0 ? ` · ${openConflicts} 处冲突` : ""}
                        </small>
                      </button>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {selected ? <CellDetail cell={selected} /> : <small>点击单元格查看支撑这一结论的原文、待核实的线索和冲突。</small>}
    </section>
  );
}

function CellDetail({ cell }: { cell: ResearchCell }) {
  const level = evidenceLevel(cell);
  const quotes = cell.lead_quotes ?? [];
  return (
    <div className="research-cell-detail" aria-label="单元格详情">
      <strong>{cell.entity_label} · {cell.column_label}</strong>
      {cell.equivalent_claims.length > 0 ? (
        <>
          <small>等价表述（按来源合并后计为 {cell.independent_source_count} 个独立来源）</small>
          <ul>
            {cell.equivalent_claims.map((claim) => (
              <li key={claim.evidence_key}>
                <span>{claim.claim_text}</span>
                <small>{claim.source_domain}</small>
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {level !== "VERIFIED" && quotes.length > 0 ? (
        <>
          <small>
            {level === "SOURCED"
              ? "找到了原文出处，但没有通过自动核验，例如只有第三方资料，或数字、版本与引用片段对不上"
              : "以下原文与研究对象有关，但没有直接回答这一项"}
          </small>
          {level === "SOURCED" && cell.lead_value ? <span className="research-cell-lead">{cell.lead_value}</span> : null}
          <ul>
            {quotes.map((quote) => (
              <li key={quote.evidence_key} className="research-cell-quote">
                <blockquote>{quote.quote_text}</blockquote>
                <small>
                  {quote.source_url
                    ? <a href={quote.source_url} target="_blank" rel="noreferrer">{quote.source_title || quote.source_url}</a>
                    : quote.source_title}
                </small>
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {cell.equivalent_claims.length === 0 && quotes.length === 0
        ? <small>读过的资料里没有找到与这一项相关的内容。</small>
        : null}
      {cell.conflicts.length > 0 ? (
        <>
          <small>冲突</small>
          <ul>
            {cell.conflicts.map((conflict, index) => (
              <li key={`${conflict.kind}-${index}`} className={`research-conflict status-${conflict.status.toLowerCase()}`}>
                <span>{CONFLICT_LABELS[conflict.kind] ?? conflict.kind}：{conflict.other_value}</span>
                <small>{conflict.status === "OPEN" ? "待修复" : "已解决"}</small>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

/** 按接口返回顺序收集行和列，保持与规划阶段一致的排列。 */
export function buildMatrix(cells: ResearchCell[]) {
  const entities: string[] = [];
  const columns: string[] = [];
  const lookup = new Map<string, ResearchCell>();
  for (const cell of cells) {
    if (!entities.includes(cell.entity_label)) entities.push(cell.entity_label);
    if (!columns.includes(cell.column_label)) columns.push(cell.column_label);
    lookup.set(`${cell.entity_label}\u0000${cell.column_label}`, cell);
  }
  return { entities, columns, lookup };
}

function countLevels(cells: ResearchCell[]) {
  const counts: Partial<Record<ResearchEvidenceLevel, number>> = {};
  for (const cell of cells) {
    const level = evidenceLevel(cell);
    counts[level] = (counts[level] ?? 0) + 1;
  }
  return counts;
}
