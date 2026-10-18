// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ArtifactVerificationPanel } from "./ArtifactVerificationPanel";

describe("ArtifactVerificationPanel", () => {
  afterEach(() => cleanup());

  it("展示契约状态、引用召回率与精确率、逐章节支撑和修复记录", () => {
    render(<ArtifactVerificationPanel verification={{
      contract_status: "PASS",
      repair_rounds: [{ round: 1, failed_checks: ["缺少引用"], repaired_sections: ["小结"] }],
      citation_recall: 0.92,
      citation_precision: 0.875,
      sections: [{ heading: "结论", support: "SUPPORTED" }, { heading: "小结", support: "UNSUPPORTED" }]
    }} />);

    expect(screen.getByText("输出契约通过")).toBeTruthy();
    expect(screen.getByText("引用召回率 92%")).toBeTruthy();
    expect(screen.getByText("引用精确率 88%")).toBeTruthy();
    expect(screen.getByText("修复 1 轮")).toBeTruthy();
    expect(screen.getByText("1 个章节缺少依据")).toBeTruthy();
    const sections = screen.getByRole("list", { name: "章节证据支撑" });
    expect(within(sections).getByText("缺少依据")).toBeTruthy();
    expect(screen.getByText("第 1 轮：未通过 缺少引用；重写 小结")).toBeTruthy();
  });

  it("修复后仍未通过校验的产物照常展示，并提醒核对缺少依据的章节", () => {
    render(<ArtifactVerificationPanel verification={{
      status: "FAIL",
      evidence_status: "FAIL",
      contract_status: "PASS",
      repair_rounds: [{ round: 1, failed_checks: ["缺少引用"], repaired_sections: ["小结"] }],
      sections: [{ heading: "小结", support: "UNSUPPORTED" }]
    }} />);

    expect(screen.getByRole("note").textContent).toContain("产物已照常生成");
  });

  it("模型不可用导致未校验的章节单独提示，缺少指标时显示占位", () => {
    render(<ArtifactVerificationPanel verification={{
      contract_status: "FAIL", repair_rounds: [], citation_recall: null, citation_precision: null,
      sections: [{ heading: "结论", support: "NOT_RUN" }]
    }} />);

    expect(screen.getByText("输出契约未通过")).toBeTruthy();
    expect(screen.getByText("引用召回率 -")).toBeTruthy();
    expect(screen.getByText("未经修复")).toBeTruthy();
    expect(screen.getByText(/1 个章节因模型不可用没有完成证据校验/)).toBeTruthy();
  });

  it("旧版本没有校验结果或只有旧的检查列表时不渲染", () => {
    const { container, rerender } = render(<ArtifactVerificationPanel verification={null} />);
    expect(container.innerHTML).toBe("");
    rerender(<ArtifactVerificationPanel verification={{ contract_status: null, sections: [], repair_rounds: [] }} />);
    expect(container.innerHTML).toBe("");
  });
});
