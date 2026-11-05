// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ResearchRunDetail } from "./model";
import { formatElapsed, ResearchTurnMessage } from "./ResearchTurnMessage";

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
  it("进行中的研究显示已用时间，可以取消，取消后立即刷新状态", async () => {
    const running = { ...RUN, status: "RUNNING", created_at: new Date(Date.now() - 75_000).toISOString() };
    const cancelled = { ...RUN, status: "CANCELLED" };
    const loadRun = vi.fn()
      .mockResolvedValueOnce(running as unknown as ResearchRunDetail)
      .mockResolvedValue(cancelled as unknown as ResearchRunDetail);
    const cancelRun = vi.fn().mockResolvedValue(undefined);
    render(<ResearchTurnMessage researchRunId="run-1" loadRun={loadRun} cancelRun={cancelRun} onOpenReport={vi.fn()} />);

    const button = await screen.findByRole("button", { name: "取消研究" });
    expect(screen.getByText(/已用时 1 分/)).toBeTruthy();
    fireEvent.click(button);
    await waitFor(() => expect(cancelRun).toHaveBeenCalledWith("run-1"));
    await waitFor(() => expect(screen.queryByRole("button", { name: "取消研究" })).toBeNull());
    expect(loadRun).toHaveBeenCalledTimes(2);
  });

  it("已用时间格式", () => {
    const now = Date.parse("2026-11-04T10:02:05Z");
    expect(formatElapsed("2026-11-04T10:01:50Z", now)).toBe("15 秒");
    expect(formatElapsed("2026-11-04T10:00:00Z", now)).toBe("2 分 5 秒");
    expect(formatElapsed("not-a-date", now)).toBe("");
  });
});
