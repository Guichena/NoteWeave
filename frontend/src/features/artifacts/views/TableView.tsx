import type { TableIr } from "./ir";

export function TableView({ ir }: { ir: TableIr }) {
  return (
    <div className="artifact-kind-view artifact-table-view" role="region" aria-label="数据表" tabIndex={0}>
      <table>
        <caption>{ir.title}</caption>
        <thead><tr>{ir.columns.map((column) => <th key={column.id} scope="col">{column.label}</th>)}</tr></thead>
        <tbody>{ir.rows.map((row) => (
          <tr key={row.id}>{row.cells.map((cell, index) => <td key={ir.columns[index].id}>{cell.text}</td>)}</tr>
        ))}</tbody>
      </table>
    </div>
  );
}
