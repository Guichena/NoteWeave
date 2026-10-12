// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ResearchPlan } from "./model";
import { parseRevision, ResearchPlanPanel } from "./ResearchPlanPanel";

const plan = (status: ResearchPlan["plan_status"]): ResearchPlan => ({
  plan_status: status,
  review_deadline: null,
  planner: "llm-matrix.v1",
  rows: [{ entity_key: "entity-a", label: "PostgreSQL" }, { entity_key: "entity-b", label: "MySQL" }],
  columns: [
    { column_key: "dim-a", label: "JSON 索引类型", description: "支持哪些索引" },
    { column_key: "dim-b", label: "限制", description: null }
  ],
  brief: { objective: "比较 JSON 索引能力" },
  effort_tier: "STANDARD",
  authoritative_domains: ["postgresql.org"],
  perspectives: []
});

function api(initial: ResearchPlan) {
  return {
    getPlan: vi.fn().mockResolvedValue(initial),
    approvePlan: vi.fn().mockResolvedValue({ ...initial, plan_status: "READY" }),
    revisePlan: vi.fn().mockImplementation((_w: string, _r: string, revision: { entities: Array<{ label: string }> }) =>
      Promise.resolve({ ...initial, rows: revision.entities.map((entity, index) => ({ entity_key: `e${index}`, label: entity.label })) }))
  };
}

describe("ResearchPlanPanel", () => {
  afterEach(() => cleanup());

  it("展示研究对象、维度和研究目标，已开始研究时不显示确认按钮", async () => {
    render(<ResearchPlanPanel workspaceId="w" researchRunId="r" api={api(plan("READY"))} />);

    expect(await screen.findByText("比较 JSON 索引能力")).toBeTruthy();
    expect(screen.getByText("PostgreSQL")).toBeTruthy();
    expect(screen.getByText("支持哪些索引")).toBeTruthy();
    expect(screen.getByText("已开始研究")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "开始研究" })).toBeNull();
  });

  it("确认阶段可以开始研究，并通知调用方刷新", async () => {
    const client = api(plan("REVIEW"));
    const onStarted = vi.fn();
    render(<ResearchPlanPanel workspaceId="w" researchRunId="r" api={client} onStarted={onStarted} />);

    fireEvent.click(await screen.findByRole("button", { name: "开始研究" }));

    await waitFor(() => expect(onStarted).toHaveBeenCalled());
    expect(client.approvePlan).toHaveBeenCalledWith("w", "r");
    expect(await screen.findByText("已开始研究")).toBeTruthy();
  });

  it("确认阶段修改研究对象后提交新的表格", async () => {
    const client = api(plan("REVIEW"));
    render(<ResearchPlanPanel workspaceId="w" researchRunId="r" api={client} />);

    fireEvent.click(await screen.findByRole("button", { name: "修改" }));
    fireEvent.change(screen.getByLabelText("研究对象（每行一个）"), { target: { value: "PostgreSQL\nSQLite\n" } });
    fireEvent.click(screen.getByRole("button", { name: "保存修改" }));

    await waitFor(() => expect(client.revisePlan).toHaveBeenCalled());
    const revision = client.revisePlan.mock.calls[0][2];
    expect(revision.entities).toEqual([{ label: "PostgreSQL" }, { label: "SQLite" }]);
    expect(revision.dimensions[0]).toEqual({ label: "JSON 索引类型", description: "支持哪些索引" });
    expect(await screen.findByText("SQLite")).toBeTruthy();
  });
});

describe("parseRevision", () => {
  it("忽略空行，维度行按第一个竖线拆分名称和说明", () => {
    expect(parseRevision("A\n\n B ", "维度一 | 说明 | 含竖线\n维度二\n维度三 |")).toEqual({
      entities: [{ label: "A" }, { label: "B" }],
      dimensions: [
        { label: "维度一", description: "说明 | 含竖线" },
        { label: "维度二" },
        { label: "维度三" }
      ]
    });
  });
});
