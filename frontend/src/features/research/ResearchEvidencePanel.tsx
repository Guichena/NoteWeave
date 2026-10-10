import { ExternalLink, GitBranch, X } from "lucide-react";
import { ResearchCellStatus } from "./ResearchStateTable";
import {
  VERIFIER_DECISION_LABEL,
  type ResearchEvidence,
  type ResearchTable,
  type ResearchTableCell
} from "./researchTable";

/** 证据面板的两种入口：点击研究表单元格，或点击报告中的证据角标。 */
export type EvidenceSelection =
  | { kind: "cell"; cell: ResearchTableCell }
  | { kind: "evidence"; evidenceId: string };

const RELATION_LABEL: Record<string, string> = {
  SUPPORTS: "支持",
  PARTIALLY_SUPPORTS: "部分支持",
  CONTRADICTS: "反驳",
  NOT_ENOUGH_INFO: "信息不足"
};

type ResearchEvidencePanelProps = {
  table: ResearchTable;
  selection: EvidenceSelection;
  onClose: () => void;
  onSelectCell: (cell: ResearchTableCell) => void;
};

function EvidenceCard({ evidence }: { evidence: ResearchEvidence }) {
  const relation = evidence.relation.toUpperCase();
  return (
    <article className="research-evidence-item">
      <header>
        {evidence.sourceUrl ? (
          <a href={evidence.sourceUrl} target="_blank" rel="noreferrer">
            {evidence.sourceTitle}
            <ExternalLink size={12} aria-hidden="true" />
          </a>
        ) : (
          <strong>{evidence.sourceTitle}</strong>
        )}
        {relation ? (
          <span className={`research-relation is-${relation.toLowerCase()}`}>{RELATION_LABEL[relation] ?? relation}</span>
        ) : null}
      </header>
      {evidence.quote ? <blockquote>{evidence.quote}</blockquote> : null}
      {evidence.supportScore != null ? (
        <small>支持度 {Math.round(evidence.supportScore * 100)}%</small>
      ) : null}
    </article>
  );
}

function cellsCiting(table: ResearchTable, evidenceId: string) {
  return table.rows.flatMap((row) => [...row.cells.values()]
    .filter((cell) => cell.evidenceIds.includes(evidenceId))
    .map((cell) => ({ row, cell })));
}

export function ResearchEvidencePanel({ table, selection, onClose, onSelectCell }: ResearchEvidencePanelProps) {
  const columnLabel = (key: string) => table.columns.find((column) => column.key === key)?.label ?? key;
  const rowTitle = (id: string) => table.rows.find((row) => row.id === id)?.title ?? id;

  let body;
  if (selection.kind === "cell") {
    const { cell } = selection;
    const evidence = cell.evidenceIds.map((id) => table.evidenceById.get(id)).filter((item): item is ResearchEvidence => Boolean(item));
    const fromBranch = cell.branchId && cell.branchId !== "main";
    body = (
      <>
        <header className="research-evidence-head">
          <small>{rowTitle(cell.rowId)} · {columnLabel(cell.columnKey)}</small>
          <ResearchCellStatus tone={cell.tone} />
        </header>
        <p className="research-evidence-value">{cell.value || "这个字段还没有可采信的结论。"}</p>
        <dl className="research-evidence-facts">
          {cell.verifierDecision ? (
            <div><dt>核验结论</dt><dd>{VERIFIER_DECISION_LABEL[cell.verifierDecision] ?? cell.verifierDecision}</dd></div>
          ) : null}
          {cell.confidence != null ? (
            <div><dt>置信度</dt><dd>{Math.round(cell.confidence * 100)}%</dd></div>
          ) : null}
          {cell.repairCount > 0 ? (
            <div><dt>修复</dt><dd>重新检索 {cell.repairCount} 次</dd></div>
          ) : null}
        </dl>
        {fromBranch ? (
          <p className="research-evidence-branch">
            <GitBranch size={13} aria-hidden="true" />
            结论经过反证分支复核
          </p>
        ) : null}
        <h4>证据 {evidence.length}</h4>
        {evidence.length > 0
          ? evidence.map((item) => <EvidenceCard key={item.evidenceId} evidence={item} />)
          : <p className="research-evidence-empty">暂未找到可引用的证据，研究会在后续轮次继续补充。</p>}
      </>
    );
  } else {
    const evidence = table.evidenceById.get(selection.evidenceId);
    const citing = cellsCiting(table, selection.evidenceId);
    body = (
      <>
        <header className="research-evidence-head">
          <small>引用证据</small>
        </header>
        {evidence ? <EvidenceCard evidence={evidence} /> : <p className="research-evidence-empty">没有找到这条证据的原文记录。</p>}
        {citing.length > 0 ? (
          <>
            <h4>支撑的研究字段</h4>
            <ul className="research-evidence-citing">
              {citing.map(({ row, cell }) => (
                <li key={cell.id}>
                  <button type="button" className="research-citing-item" onClick={() => onSelectCell(cell)}>
                    <span>{row.title} · {columnLabel(cell.columnKey)}</span>
                    <ResearchCellStatus tone={cell.tone} />
                  </button>
                </li>
              ))}
            </ul>
          </>
        ) : null}
      </>
    );
  }

  return (
    <aside className="research-evidence-panel" aria-label="证据详情">
      <button type="button" className="research-evidence-close" aria-label="关闭证据详情" onClick={onClose}>
        <X size={16} aria-hidden="true" />
      </button>
      {body}
    </aside>
  );
}
