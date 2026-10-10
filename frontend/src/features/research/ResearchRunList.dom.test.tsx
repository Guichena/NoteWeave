// @vitest-environment jsdom

import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ResearchRunSummary } from "./model";
import { ResearchRunList } from "./ResearchRunList";

const run = (id: string, status: string) => ({
  research_run_id: id,
  question: `问题 ${id}`,
  final_report_title: "",
  status,
  updated_at: "2026-10-08T12:00:00Z"
}) as unknown as ResearchRunSummary;

describe("ResearchRunList", () => {
  it("区分研究中、失败、已取消和已完成", () => {
    const { container } = render(
      <ResearchRunList
        runs={[run("a", "RUNNING"), run("b", "FAILED"), run("c", "CANCELLED"), run("d", "COMPLETED")]}
        activeRunId=""
        composing={false}
        isBusy={false}
        onNewResearch={vi.fn()}
        onOpenRun={vi.fn()}
      />
    );

    const labels = [...container.querySelectorAll(".research-runs-item small")].map((item) => item.textContent ?? "");
    expect(labels.map((label) => label.split(" · ")[0])).toEqual(["研究中", "失败", "已取消", "已完成"]);
  });
});
