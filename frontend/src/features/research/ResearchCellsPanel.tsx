import { useMemo, useState } from "react";
import { researchApi, type ResearchApi } from "./api";
import type { ResearchCell } from "./model";
import { formatConfidence, useRunResource } from "./useRunResource";

export const CELL_STATUS_LABELS: Record<string, string> = {
  GAP: "待补充",
  CANDIDATE_READY: "有候选值",
  VERIFIED: "已验证",
  CONFLICTED: "存在冲突"
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

/** 研究表格的单元格：候选值、独立来源数、置信度和冲突；点击单元格查看等价表述与冲突详情。 */
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
  const counts = countStatuses(data);

  return (
    <section className="task-card research-cells-panel" aria-label="单元格结论">
      <div className="research-plan-header">
        <strong>单元格结论</strong>
        <small>
          {Object.entries(counts).map(([status, total]) => `${CELL_STATUS_LABELS[status] ?? status} ${total}`).join(" · ")}
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
                  const openConflicts = cell.conflicts.filter((conflict) => conflict.status === "OPEN").length;
                  return (
                    <td key={column}>
                      <button
                        type="button"
                        className={`research-cell status-${cell.cell_status.toLowerCase()}${cell.cell_key === selectedKey ? " active" : ""}`}
                        aria-pressed={cell.cell_key === selectedKey}
                        onClick={() => setSelectedKey(cell.cell_key === selectedKey ? "" : cell.cell_key)}
                      >
                        <span className="research-cell-value">{cell.candidate_value || CELL_STATUS_LABELS[cell.cell_status] || cell.cell_status}</span>
                        <small>
                          {CELL_STATUS_LABELS[cell.cell_status] ?? cell.cell_status}
                          {cell.independent_source_count > 0 ? ` · ${cell.independent_source_count} 个独立来源` : ""}
                          {cell.confidence_ppm !== null ? ` · 置信度 ${formatConfidence(cell.confidence_ppm)}` : ""}
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
      {selected ? <CellDetail cell={selected} /> : <small>点击单元格查看支撑这一结论的等价表述和冲突。</small>}
    </section>
  );
}

function CellDetail({ cell }: { cell: ResearchCell }) {
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
      ) : <small>还没有支撑这一单元格的结论。</small>}
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

function countStatuses(cells: ResearchCell[]) {
  const counts: Record<string, number> = {};
  for (const cell of cells) counts[cell.cell_status] = (counts[cell.cell_status] ?? 0) + 1;
  return counts;
}
