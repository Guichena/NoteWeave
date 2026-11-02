// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ResearchRunDetail } from "./model";
import { ResearchTurnMessage } from "./ResearchTurnMessage";

afterEach(cleanup);

const RUN = {
  research_run_id: "run-1",
  question: "如何部署？",
  status: "COMPLETED",
  final_report_title: "部署对比",
  final_report_markdown: "# 部署对比\n\n正文",
  closed_loop_state: { rows: [], cells: [], source_evidence: [], loop_rounds: [] },
  research_process_summary: { search_read_timeline: { rounds: [] } }
} as unknown as ResearchRunDetail;

describe("ResearchTurnMessage", () => {
  it("对话消息携带的后端投影直接驱动结论卡", async () => {
    render(
      <ResearchTurnMessage
        researchRunId="run-1"
        researchProjectionJson={JSON.stringify({
          schema_version: "research-projection-v1",
          research_run_id: "run-1",
          status: "COMPLETED",
          level: "P1",
          title: "投影标题",
          executive_summary: "后端结构化执行摘要",
          requirements: [{ code: "deployment", label: "部署方式", coverage: "COVERED" }]
        })}
        loadRun={vi.fn().mockResolvedValue(RUN)}
        onOpenReport={vi.fn()}
      />
    );

    const card = await screen.findByRole("article", { name: "研究结论卡：投影标题" });
    expect(within(card).getByText("后端结构化执行摘要")).toBeTruthy();
    expect(within(card).getByText("已覆盖")).toBeTruthy();
  });

  it("投影 JSON 损坏时退回运行详情渲染", async () => {
    render(
      <ResearchTurnMessage
        researchRunId="run-1"
        researchProjectionJson="{not-json"
        loadRun={vi.fn().mockResolvedValue(RUN)}
        onOpenReport={vi.fn()}
      />
    );

    expect(await screen.findByRole("article", { name: "研究报告：部署对比" })).toBeTruthy();
    expect(screen.queryByRole("article", { name: /研究结论卡/ })).toBeNull();
  });
});
