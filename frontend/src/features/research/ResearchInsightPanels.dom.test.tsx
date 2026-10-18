// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../shared/api/error";
import { researchInsights } from "../../dev/fixtures/research";
import { buildMatrix, ResearchCellsPanel } from "./ResearchCellsPanel";
import { ResearchDiscoveryPanel } from "./ResearchDiscoveryPanel";
import { ResearchRepairPanel, summarizeVerdicts } from "./ResearchRepairPanel";

const notFound = () => Promise.reject(new ApiError("not found", 404, "HTTP_404", "", ""));

describe("研究工作台附属面板", () => {
  afterEach(() => cleanup());

  it("单元格按研究对象和维度排成表格，点击后展示等价表述和冲突", async () => {
    const api = { listCells: vi.fn().mockResolvedValue(researchInsights.cells("done")) };
    render(<ResearchCellsPanel workspaceId="w" researchRunId="r" refreshKey="1" api={api} />);

    const cell = await screen.findByRole("button", { name: /生成列上的 B-tree/ });
    expect(screen.getByText(/已核实 1/)).toBeTruthy();
    expect(screen.getByText(/2 个独立来源 · 置信度 91%/)).toBeTruthy();

    fireEvent.click(cell);
    expect(screen.getByText("可以在 JSON 数组上建多值索引")).toBeTruthy();
    expect(screen.getByText(/语义矛盾：JSON 列不能建任何索引/)).toBeTruthy();
    expect(screen.getByText("待修复")).toBeTruthy();
  });

  it("没通过核验的单元格显示有出处的线索，点开后列出原文和来源", async () => {
    const api = { listCells: vi.fn().mockResolvedValue(researchInsights.cells("done")) };
    render(<ResearchCellsPanel workspaceId="w" researchRunId="r" refreshKey="1" api={api} />);

    const cell = await screen.findByRole("button", { name: /MEMBER OF/ });
    expect(cell.className).toContain("level-sourced");
    expect(screen.getByText(/有出处，待核实 1/)).toBeTruthy();

    fireEvent.click(cell);
    expect(screen.getByText(/没有通过自动核验/)).toBeTruthy();
    expect(screen.getByText(/Multi-valued indexes can be used/)).toBeTruthy();
    expect(screen.getByRole("link", { name: /第三方博客/ }).getAttribute("href")).toBe("https://example.com/mysql-multi-valued-index");
  });

  it("后端还没有提供接口时不显示面板", async () => {
    const api = { listCells: vi.fn().mockImplementation(notFound) };
    const { container } = render(<ResearchCellsPanel workspaceId="w" researchRunId="r" refreshKey="1" api={api} />);
    await vi.waitFor(() => expect(api.listCells).toHaveBeenCalled());
    expect(container.innerHTML).toBe("");
  });

  it("反证结果逐条展示判定，修复记录展示原因和策略", async () => {
    const api = {
      listCounterChecks: vi.fn().mockResolvedValue(researchInsights.counterChecks("done")),
      listReplans: vi.fn().mockResolvedValue(researchInsights.replans("done"))
    };
    render(<ResearchRepairPanel workspaceId="w" researchRunId="r" refreshKey="1" api={api} />);

    expect(await screen.findByText("JSON 列不能建任何索引")).toBeTruthy();
    expect(screen.getByText("被推翻")).toBeTruthy();
    expect(screen.getByText("未找到反证")).toBeTruthy();
    expect(screen.getByText("检索反证")).toBeTruthy();
    expect(screen.getByText(/原因：证据冲突/)).toBeTruthy();
    expect(screen.getByText("切换检索语言")).toBeTruthy();
  });

  it("没有执行反证的结论单独说明", () => {
    expect(summarizeVerdicts([
      { cell_key: "a", entity_label: "", column_label: "", claim_text: "x", verdict: "NOT_RUN", counter_query_count: 0, refuting_evidence_keys: [], checked_at: "" },
      { cell_key: "b", entity_label: "", column_label: "", claim_text: "y", verdict: "REFUTED", counter_query_count: 1, refuting_evidence_keys: [], checked_at: "" }
    ])).toBe("未执行 1 · 被推翻 1");
  });

  it("宽搜索默认只展示已加入的提案，可以展开查看全部", async () => {
    const api = { listDiscoveryProposals: vi.fn().mockResolvedValue(researchInsights.discoveryProposals("done")) };
    render(<ResearchDiscoveryPanel workspaceId="w" researchRunId="r" refreshKey="1" api={api} />);

    expect(await screen.findByText("研究维度：部分更新是否重写整行")).toBeTruthy();
    expect(screen.queryByText("研究对象：MariaDB 11")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "查看全部 3 条" }));
    expect(screen.getByText("研究对象：MariaDB 11")).toBeTruthy();
    expect(screen.getByText("仅记录")).toBeTruthy();
  });

  it("矩阵保持接口返回的行列顺序", () => {
    const matrix = buildMatrix(researchInsights.cells("done"));
    expect(matrix.entities).toEqual(["PostgreSQL 17", "MySQL 8.4"]);
    expect(matrix.columns).toEqual(["JSON 索引类型", "可加速的查询运算符"]);
  });
});
