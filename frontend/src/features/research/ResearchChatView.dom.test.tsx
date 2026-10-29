// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ResearchRunDetail } from "./model";
import { ResearchChatView } from "./ResearchChatView";

vi.mock("./ResearchComposer", () => ({
  RESEARCH_EXAMPLES: ["示例问题一"],
  ResearchComposer: () => <div>研究输入框</div>
}));

afterEach(cleanup);

const REPORT = [
  "# 向量检索方案选型",
  "",
  "ES 原生支持混合检索 [evidence:ev-1]。",
  "",
  "## 直接回答",
  "",
  "pgvector 需要自行组合全文检索 [evidence:ev-2]。",
  "",
  "## 对比表",
  "",
  "| 方案 | 混合检索 |",
  "| --- | --- |",
  "| ES | 原生 [evidence:ev-1] |"
].join("\n");

function buildRun(overrides: Partial<ResearchRunDetail> = {}) {
  return {
    research_run_id: "run-1",
    question: "如何选择向量检索方案？",
    status: "COMPLETED",
    completion_terminal_state: "COMPLETED",
    final_report_title: "向量检索方案选型",
    final_report_markdown: REPORT,
    saved_report_source: null,
    research_process_summary: {
      search_read_timeline: {
        rounds: [{ round_no: 1, search_hit_count: 12, read_window_count: 5, evidence_card_count: 3, search_queries: ["hybrid search"], evidence_ids: [], branch_decision: "EXPAND", global_decision: "COMPLETE" }]
      }
    },
    closed_loop_state: {
      rows: [{ row_id: "es", source_title: "Elasticsearch" }, { row_id: "pg", source_title: "pgvector" }],
      cells: [
        { cell_id: "es:hybrid", row_id: "es", column_key: "hybrid", status: "VERIFIED", candidate_value: "原生 RRF", evidence_refs: ["ev-1"] },
        { cell_id: "pg:hybrid", row_id: "pg", column_key: "hybrid", status: "MISSING", candidate_value: "", evidence_refs: [] }
      ],
      source_evidence: [],
      loop_rounds: [{ round_no: 1, plan_note: "建立 2 × 1 研究表" }]
    },
    ...overrides
  } as unknown as ResearchRunDetail;
}

function renderChat(run: ResearchRunDetail | null, extra: Record<string, unknown> = {}) {
  const props = {
    run,
    progressMessage: "",
    isBusy: false,
    composer: { researchQuestion: "", setResearchQuestion: vi.fn() } as any,
    onStart: vi.fn(),
    onOpenReport: vi.fn(),
    onExport: vi.fn(),
    onSaveAsSource: vi.fn(),
    ...extra
  };
  render(<ResearchChatView {...props} />);
  return props;
}

describe("ResearchChatView", () => {
  it("新研究显示欢迎页和示例问题，点示例填入输入框", () => {
    const props = renderChat(null);

    expect(screen.getByRole("heading", { name: "想深入研究什么问题？" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "示例问题一" }));
    expect(props.composer.setResearchQuestion).toHaveBeenCalledWith("示例问题一");
    expect(screen.getByText("研究输入框")).toBeTruthy();
  });

  it("完成的研究按对话展示问题、计划、过程和报告消息，可以跳到完整报告", () => {
    const props = renderChat(buildRun());

    expect(screen.getByText("如何选择向量检索方案？")).toBeTruthy();
    expect(screen.getByText(/围绕 2 个研究对象/)).toBeTruthy();
    expect(screen.getByText(/研究过程 · 1 轮检索/)).toBeTruthy();

    const report = screen.getByRole("article", { name: "研究报告：向量检索方案选型" });
    expect(within(report).getByText("直接回答")).toBeTruthy();
    expect(within(report).getByText(/已核验 1\/2 项/)).toBeTruthy();
    // 对话里只放摘要和直接回答，对比表留在完整报告页
    expect(within(report).queryByRole("table")).toBeNull();

    fireEvent.click(within(report).getByRole("button", { name: /查看完整报告/ }));
    fireEvent.click(within(report).getAllByRole("button", { name: "查看证据 1" })[0]);
    expect(props.onOpenReport).toHaveBeenCalledTimes(2);
  });

  it("研究进行中显示进度，没有报告消息", () => {
    renderChat(buildRun({ status: "RUNNING", final_report_markdown: "" }), { progressMessage: "第 2 轮：核验证据" });

    expect(screen.getByText("研究中")).toBeTruthy();
    expect(screen.getByText("第 2 轮：核验证据")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /查看完整报告/ })).toBeNull();
  });
});
