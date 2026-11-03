// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResearchIncrementalPanel } from "./ResearchIncrementalPanel";
import type { ResearchCell } from "./model";

afterEach(cleanup);

function cell(cellKey: string, entity: string, column: string): ResearchCell {
  return {
    cell_key: cellKey,
    entity_label: entity,
    column_label: column,
    cell_status: "VERIFIED",
    candidate_value: "值",
    equivalent_claims: [],
    independent_source_count: 1,
    confidence_ppm: 900000,
    conflicts: []
  };
}

function api() {
  return {
    listCells: vi.fn().mockResolvedValue([
      cell("AWS:deployment", "AWS", "部署方式"),
      cell("Azure:deployment", "Azure", "部署方式")
    ]),
    reopenIncremental: vi.fn().mockResolvedValue({
      research_run_id: "run-1",
      reopened_cells: 1,
      added_rows: 1,
      added_columns: 0,
      stale_cells: 0,
      total_cells: 3,
      report_version_relation: "NEW_REPORT_APPENDED_TO_SAME_RUN"
    })
  };
}

describe("ResearchIncrementalPanel", () => {
  it("勾选单元格并追加行后提交局部补查", async () => {
    const fake = api();
    render(<ResearchIncrementalPanel workspaceId="w" researchRunId="run-1" api={fake} />);

    const checkbox = await screen.findByRole("checkbox", { name: /AWS · 部署方式/ });
    fireEvent.click(checkbox);
    fireEvent.change(screen.getByLabelText("新增行键"), { target: { value: "GCP" } });
    fireEvent.change(screen.getByLabelText("新增行标题"), { target: { value: "GCP" } });
    fireEvent.click(screen.getByRole("button", { name: "开始局部补查" }));

    await waitFor(() => expect(fake.reopenIncremental).toHaveBeenCalledWith("w", "run-1", {
      cell_keys: ["AWS:deployment"],
      add_rows: [{ row_key: "GCP", title: "GCP" }],
      add_columns: [],
      input_changed: false,
      reason: ""
    }));
    const status = await screen.findByRole("status");
    expect(status.textContent).toContain("已重开 1 格");
  });

  it("输入变更可以单独提交", async () => {
    const fake = api();
    render(<ResearchIncrementalPanel workspaceId="w" researchRunId="run-1" api={fake} />);
    await screen.findByRole("checkbox", { name: /AWS · 部署方式/ });

    fireEvent.click(screen.getByRole("checkbox", { name: /输入资料已变更/ }));
    fireEvent.click(screen.getByRole("button", { name: "开始局部补查" }));

    await waitFor(() => expect(fake.reopenIncremental).toHaveBeenCalledWith("w", "run-1", {
      cell_keys: [],
      add_rows: [],
      add_columns: [],
      input_changed: true,
      reason: ""
    }));
  });
});
