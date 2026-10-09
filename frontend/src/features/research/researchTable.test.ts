import { describe, expect, it } from "vitest";
import { buildResearchTable, cellTone, orderReportEvidence } from "./researchTable";

describe("buildResearchTable", () => {
  const state = {
    rows: [{ row_id: "es", source_title: "Elasticsearch" }],
    cells: [
      { cell_id: "es:hybrid_search", row_id: "es", column_key: "hybrid_search", status: "VERIFIED", candidate_value: "原生 RRF", evidence_refs: ["ev-1"], confidence: "0.9", repair_count: 0 },
      { cell_id: "es:ops_cost", row_id: "es", column_key: "ops_cost", status: "needs_repair", candidate_value: "", evidence_refs: [] },
      { cell_id: "pg:hybrid_search", row_id: "pg", column_key: "hybrid_search", status: "CONFLICTED", candidate_value: "需自行组合", evidence_refs: ["ev-2"] }
    ],
    source_evidence: [
      { evidence_id: "ev-1", source_title: "ES 文档", source_url: "https://example.com/es", quote_text: "kNN + RRF", relation_type: "SUPPORTS", support_score: 0.9 },
      { evidence_id: "ev-2", source_url: "https://example.com/pg", quote_text: "tsvector", relation_type: "CONTRADICTS" }
    ]
  };

  it("按行和列组织单元格，并统计各状态数量", () => {
    const table = buildResearchTable(state);

    expect(table.columns).toEqual([
      { key: "hybrid_search", label: "Hybrid search" },
      { key: "ops_cost", label: "Ops cost" }
    ]);
    expect(table.rows.map((row) => row.title)).toEqual(["Elasticsearch", "pg"]);
    expect(table.rows[0].cells.get("ops_cost")?.tone).toBe("repair");
    expect(table.rows[1].cells.has("ops_cost")).toBe(false);
    expect(table.counts).toMatchObject({ verified: 1, repair: 1, conflict: 1, total: 3 });
    expect(table.rows[0].cells.get("hybrid_search")?.confidence).toBe(0.9);
  });

  it("列名优先使用后端 column_label，内置的行列键显示中文名", () => {
    const table = buildResearchTable({
      rows: [{ row_id: "subject", source_title: "Subject" }],
      cells: [
        { cell_id: "subject:answer", row_id: "subject", column_key: "answer", status: "VERIFIED" },
        { cell_id: "subject:key_evidence", row_id: "subject", column_key: "key_evidence", status: "VERIFIED", column_label: "核心证据" },
        { cell_id: "subject:finding-1", row_id: "subject", column_key: "finding-1", status: "FILLED", column_label: "必须区分冲突点" }
      ],
      source_evidence: []
    });

    expect(table.columns.map((column) => column.label)).toEqual(["结论", "核心证据", "必须区分冲突点"]);
    expect(table.rows[0].title).toBe("研究对象");
  });

  it("证据缺少标题时使用来源链接作为展示名称", () => {
    const table = buildResearchTable(state);

    expect(table.evidenceById.get("ev-1")?.sourceTitle).toBe("ES 文档");
    expect(table.evidenceById.get("ev-2")?.sourceTitle).toBe("https://example.com/pg");
    expect(table.evidenceById.get("ev-2")?.supportScore).toBeNull();
  });

  it("没有研究状态时返回空表", () => {
    const table = buildResearchTable(null);

    expect(table.rows).toHaveLength(0);
    expect(table.counts.total).toBe(0);
  });
});

describe("cellTone", () => {
  it("把 Worker 的单元格状态归并为展示状态，未知状态按进行中处理", () => {
    expect(cellTone("FILLED")).toBe("filled");
    expect(cellTone("EMPTY")).toBe("queued");
    expect(cellTone("BLOCKED")).toBe("missing");
    expect(cellTone("SOMETHING_NEW")).toBe("pending");
  });
});

describe("orderReportEvidence", () => {
  it("按首次出现顺序去重", () => {
    expect(orderReportEvidence("A [evidence:ev-2] B [evidence:ev-1] C [evidence-ev-2]")).toEqual(["ev-2", "ev-1"]);
  });
});
