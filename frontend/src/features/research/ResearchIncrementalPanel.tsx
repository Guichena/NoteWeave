import { useEffect, useState } from "react";
import { researchApi, type ResearchApi } from "./api";
import type { ResearchCell, ResearchIncrementalReceipt, ResearchIncrementalRequest } from "./model";

type ResearchIncrementalApi = Pick<ResearchApi, "listCells" | "reopenIncremental">;

type Props = {
  workspaceId: string;
  researchRunId: string;
  onStarted?: () => void;
  api?: ResearchIncrementalApi;
};

/**
 * 第七期：局部补查。保留旧表格，只勾选要重开的格，或追加一个行/列；
 * 输入变更时由后端保守地把已核实格标记 STALE 重新资格检查。
 */
export function ResearchIncrementalPanel({ workspaceId, researchRunId, onStarted, api = researchApi }: Props) {
  const [cells, setCells] = useState<ResearchCell[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [rowKey, setRowKey] = useState("");
  const [rowTitle, setRowTitle] = useState("");
  const [columnKey, setColumnKey] = useState("");
  const [columnLabel, setColumnLabel] = useState("");
  const [inputChanged, setInputChanged] = useState(false);
  const [changedSources, setChangedSources] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState<ResearchIncrementalReceipt | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.listCells(workspaceId, researchRunId)
      .then((loaded) => {
        if (!cancelled) setCells(loaded);
      })
      .catch(() => {
        if (!cancelled) setError("暂时读不到单元格");
      });
    return () => {
      cancelled = true;
    };
  }, [api, workspaceId, researchRunId]);

  function toggle(cellKey: string) {
    setSelected((current) => current.includes(cellKey)
      ? current.filter((key) => key !== cellKey)
      : [...current, cellKey]);
  }

  async function submit() {
    if (busy) return;
    const addRows = rowKey.trim() && rowTitle.trim()
      ? [{ row_key: rowKey.trim(), title: rowTitle.trim() }] : [];
    const addColumns = columnKey.trim() && columnLabel.trim()
      ? [{ column_key: columnKey.trim(), label: columnLabel.trim(), answer_format: "SHORT_TEXT" }] : [];
    const changedSourceIds = changedSources.split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean);
    if (selected.length === 0 && addRows.length === 0 && addColumns.length === 0 && changedSourceIds.length === 0) {
      setError("至少勾选一个格、追加行列或填写变更的资料");
      return;
    }
    if (inputChanged && changedSourceIds.length === 0) {
      setError("输入变更必须填写变更的资料身份");
      return;
    }
    setBusy(true);
    setError("");
    const request: ResearchIncrementalRequest = {
      cell_keys: selected,
      add_rows: addRows,
      add_columns: addColumns,
      input_changed: inputChanged || changedSourceIds.length > 0,
      changed_source_ids: changedSourceIds,
      reason: reason.trim()
    };
    try {
      const result = await api.reopenIncremental(workspaceId, researchRunId, request);
      setReceipt(result);
      setSelected([]);
      setRowKey("");
      setRowTitle("");
      setColumnKey("");
      setColumnLabel("");
      setInputChanged(false);
      setChangedSources("");
      onStarted?.();
    } catch {
      setError("局部补查提交失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  return (
    <details className="task-card research-incremental-panel">
      <summary>局部补查（保留旧表格，只重开指定格）</summary>
      <div className="research-incremental-body">
        <div className="research-incremental-cells" aria-label="可重开的单元格">
          {cells.slice(0, 40).map((cell) => (
            <label key={cell.cell_key}>
              <input
                type="checkbox"
                checked={selected.includes(cell.cell_key)}
                onChange={() => toggle(cell.cell_key)}
              />
              {cell.entity_label} · {cell.column_label}（{cell.cell_status}）
            </label>
          ))}
        </div>
        <div className="research-incremental-extra">
          <input aria-label="新增行键" placeholder="新增行键，例如 GCP" value={rowKey}
                 onChange={(event) => setRowKey(event.target.value)} />
          <input aria-label="新增行标题" placeholder="新增行标题" value={rowTitle}
                 onChange={(event) => setRowTitle(event.target.value)} />
          <input aria-label="新增列键" placeholder="新增列键，例如 cost" value={columnKey}
                 onChange={(event) => setColumnKey(event.target.value)} />
          <input aria-label="新增列标题" placeholder="新增列标题" value={columnLabel}
                 onChange={(event) => setColumnLabel(event.target.value)} />
        </div>
        <label>
          <input type="checkbox" checked={inputChanged}
                 onChange={(event) => setInputChanged(event.target.checked)} />
          输入资料已变更，只重查引用了这些资料的已核实格
        </label>
        <input aria-label="变更的资料" placeholder="变更的资料 ID，多个用逗号分隔" value={changedSources}
               onChange={(event) => setChangedSources(event.target.value)} />
        <input aria-label="补查原因" placeholder="补查原因（可选）" value={reason}
               onChange={(event) => setReason(event.target.value)} />
        {error ? <p role="alert">{error}</p> : null}
        {receipt ? (
          <p role="status">
            已重开 {receipt.reopened_cells} 格，新增 {receipt.added_rows} 行 / {receipt.added_columns} 列，
            重新核验 {receipt.stale_cells} 格，共 {receipt.total_cells} 格
          </p>
        ) : null}
        <button type="button" className="secondary-button" disabled={busy} onClick={() => void submit()}>
          开始局部补查
        </button>
      </div>
    </details>
  );
}
