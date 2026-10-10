// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ResearchRunDetail } from "./model";
import { ResearchRunView } from "./ResearchRunView";

afterEach(cleanup);

function buildRun(overrides: Partial<ResearchRunDetail> = {}) {
  return {
    research_run_id: "run-1",
    question: "如何选择向量检索方案？",
    status: "COMPLETED",
    completion_terminal_state: "COMPLETED",
    final_report_title: "向量检索方案选型",
    final_report_markdown: "# 报告\n\n## 结论\n\nES 支持混合检索 [evidence:ev-1]，pgvector 需要自行组合 [evidence:ev-2]。",
    updated_at: "2026-09-20T10:00:00Z",
    saved_report_source: null,
    counterfactual_summary: null,
    research_process_summary: {
      search_read_timeline: {
        rounds: [
          { round_no: 1, search_hit_count: 12, read_window_count: 5, evidence_card_count: 3, search_queries: ["hybrid search"], evidence_ids: [], branch_decision: "EXPAND", global_decision: "COMPLETE" }
        ]
      }
    },
    closed_loop_state: {
      rows: [{ row_id: "es", source_title: "Elasticsearch" }, { row_id: "pg", source_title: "pgvector" }],
      cells: [
        { cell_id: "es:hybrid", row_id: "es", column_key: "hybrid", status: "VERIFIED", candidate_value: "原生 RRF", evidence_refs: ["ev-1"], last_verifier_decision: "SUPPORTS" },
        { cell_id: "pg:hybrid", row_id: "pg", column_key: "hybrid", status: "CONFLICTED", candidate_value: "需自行组合", evidence_refs: ["ev-2"], last_verifier_decision: "CONTRADICTS", repair_count: 1 }
      ],
      source_evidence: [
        { evidence_id: "ev-1", source_title: "ES 官方文档", quote_text: "kNN with RRF", relation_type: "SUPPORTS" },
        { evidence_id: "ev-2", source_title: "pgvector README", quote_text: "combine with full-text search", relation_type: "PARTIALLY_SUPPORTS" }
      ],
      loop_rounds: [{ round_no: 1, plan_note: "建立 2 × 1 研究表" }],
      checkpoints: [{ checkpoint_no: 1, final_loop_decision: "COMPLETE", verified_row_count: 1, created_at: "2026-09-20T09:50:00Z" }]
    },
    ...overrides
  } as unknown as ResearchRunDetail;
}

function renderRun(run: ResearchRunDetail, extra: Partial<Parameters<typeof ResearchRunView>[0]> = {}) {
  const props = {
    run,
    progressMessage: "",
    isBusy: false,
    onExport: vi.fn(),
    onSaveAsSource: vi.fn(),
    onRefresh: vi.fn(),
    onOpenAudit: vi.fn(),
    onResume: vi.fn(),
    ...extra
  };
  render(<ResearchRunView {...props} />);
  return props;
}

describe("ResearchRunView", () => {
  it("已完成的研究默认展示报告，点击证据角标可以追溯原文和对应字段", () => {
    renderRun(buildRun());

    expect(screen.getByRole("heading", { name: "向量检索方案选型" })).toBeTruthy();
    expect(screen.getByText("已完成")).toBeTruthy();
    expect(screen.getByRole("tab", { name: "报告" }).getAttribute("aria-selected")).toBe("true");

    fireEvent.click(screen.getByRole("button", { name: "查看证据 2" }));
    const panel = screen.getByRole("complementary", { name: "证据详情" });
    expect(within(panel).getByText("pgvector README")).toBeTruthy();
    expect(within(panel).getByText("combine with full-text search")).toBeTruthy();

    fireEvent.click(within(panel).getByRole("button", { name: /pgvector · Hybrid/ }));
    expect(within(panel).getByText("证据矛盾")).toBeTruthy();
    expect(within(panel).getByText("重新检索 1 次")).toBeTruthy();
  });

  it("研究表按对象和字段展示核验状态", () => {
    renderRun(buildRun());

    fireEvent.click(screen.getByRole("tab", { name: /研究表/ }));
    const cell = screen.getByRole("button", { name: "Elasticsearch · Hybrid：已验证" });
    fireEvent.click(cell);

    expect(cell.getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("complementary", { name: "证据详情" }).textContent).toContain("ES 官方文档");
  });

  it("研究进行中时报告不可用，并展示任务进度", () => {
    renderRun(buildRun({ status: "RUNNING", final_report_markdown: "", final_report_title: "" }), {
      progressMessage: "第 2 轮：核验 Evidence"
    });

    expect(screen.getByText("研究中")).toBeTruthy();
    expect((screen.getByRole("tab", { name: "报告" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByRole("tab", { name: /研究表/ }).getAttribute("aria-selected")).toBe("true");
    expect(screen.getByText("第 2 轮：核验 Evidence")).toBeTruthy();
  });

  it("证据不足的研究不作为可靠结论，也不能存入资料库", () => {
    renderRun(buildRun({ completion_terminal_state: "INSUFFICIENT_EVIDENCE" }));

    expect(screen.getByText("证据不足")).toBeTruthy();
    expect(screen.queryByText("已完成")).toBeNull();
    expect((screen.getByRole("button", { name: "存入资料库" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("失败的研究给出恢复提示", () => {
    renderRun(buildRun({ status: "FAILED" }));

    expect(screen.getByText("运行失败")).toBeTruthy();
    expect(screen.getByText(/可以从最近的检查点恢复/)).toBeTruthy();
  });

  it("过程页列出每轮规划与检查点，并可以从检查点恢复", () => {
    const props = renderRun(buildRun());

    fireEvent.click(screen.getByRole("tab", { name: "过程" }));
    expect(screen.getByText("建立 2 × 1 研究表")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /从此恢复/ }));

    expect(props.onResume).toHaveBeenCalledWith(1);
  });
});
