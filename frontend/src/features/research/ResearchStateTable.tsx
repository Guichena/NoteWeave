import { CircleCheck, CircleDashed, LoaderCircle, TriangleAlert, Wrench, type LucideIcon } from "lucide-react";
import { CELL_TONE_LABEL, type CellTone, type ResearchTable, type ResearchTableCell } from "./researchTable";

const TONE_ICON: Record<CellTone, LucideIcon> = {
  verified: CircleCheck,
  filled: CircleDashed,
  conflict: TriangleAlert,
  repair: Wrench,
  missing: CircleDashed,
  queued: CircleDashed,
  pending: LoaderCircle
};

// 图例只列出当前表格里实际出现的状态，按用户关心的顺序排列。
const LEGEND_ORDER: CellTone[] = ["verified", "conflict", "repair", "filled", "missing", "pending", "queued"];

type ResearchStateTableProps = {
  table: ResearchTable;
  selectedCellId: string | null;
  onSelectCell: (cell: ResearchTableCell) => void;
};

export function ResearchCellStatus({ tone }: { tone: CellTone }) {
  const Icon = TONE_ICON[tone];
  return (
    <span className={`research-cell-status is-${tone}`}>
      <Icon size={13} aria-hidden="true" className={tone === "pending" ? "is-spinning" : undefined} />
      {CELL_TONE_LABEL[tone]}
    </span>
  );
}

export function ResearchStateTable({ table, selectedCellId, onSelectCell }: ResearchStateTableProps) {
  if (table.rows.length === 0) {
    return (
      <div className="research-table-empty">
        <strong>研究表尚未生成</strong>
        <p>第一轮规划完成后，研究对象和待验证字段会出现在这里。</p>
      </div>
    );
  }

  const legend = LEGEND_ORDER.filter((tone) => table.counts[tone] > 0);

  return (
    <div className="research-table">
      <div className="research-table-legend" aria-label="字段状态统计">
        {legend.map((tone) => (
          <span key={tone} className={`research-legend-item is-${tone}`}>
            <i aria-hidden="true" />
            {CELL_TONE_LABEL[tone]} {table.counts[tone]}
          </span>
        ))}
      </div>
      <div className="research-table-scroll">
        <table className="research-state-table">
          <thead>
            <tr>
              <th scope="col">研究对象</th>
              {table.columns.map((column) => <th scope="col" key={column.key}>{column.label}</th>)}
            </tr>
          </thead>
          <tbody>
            {table.rows.map((row) => {
              const verified = [...row.cells.values()].filter((cell) => cell.tone === "verified").length;
              return (
                <tr key={row.id}>
                  <th scope="row">
                    <strong>{row.title}</strong>
                    <small>{verified} / {row.cells.size} 已验证</small>
                  </th>
                  {table.columns.map((column) => {
                    const cell = row.cells.get(column.key);
                    if (!cell) return <td key={column.key} className="research-cell-absent" />;
                    return (
                      <td key={column.key}>
                        <button
                          type="button"
                          className={`research-cell is-${cell.tone}${selectedCellId === cell.id ? " is-selected" : ""}`}
                          aria-label={`${row.title} · ${column.label}：${CELL_TONE_LABEL[cell.tone]}`}
                          aria-pressed={selectedCellId === cell.id}
                          onClick={() => onSelectCell(cell)}
                        >
                          <ResearchCellStatus tone={cell.tone} />
                          <span className="research-cell-value">{cell.value || "暂无结论"}</span>
                        </button>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
