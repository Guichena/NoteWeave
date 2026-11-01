// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarkdownSurface } from "./MarkdownSurface";

afterEach(cleanup);

describe("MarkdownSurface", () => {
  it("renders common research markdown as semantic content", () => {
    render(<MarkdownSurface content={"# Report\n\n## Findings\n\n1. First point\n2. Second point\n\n> Verified\n\n**Strong** and `code`"} />);

    expect(screen.getByRole("heading", { name: "Report" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Findings" })).toBeTruthy();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByText("Verified").tagName).toBe("BLOCKQUOTE");
    expect(screen.getByText("Strong").tagName).toBe("STRONG");
    expect(screen.getByText("code").tagName).toBe("CODE");
  });

  it("does not interpret raw html as executable markup", () => {
    render(<MarkdownSurface content={'<img src=x onerror="alert(1)">'} />);
    expect(screen.getByText('<img src=x onerror="alert(1)">')).toBeTruthy();
    expect(document.querySelector("img")).toBeNull();
  });

  it("renders comparison tables as accessible semantic tables", () => {
    render(<MarkdownSurface compactCitations content={[
      "## 对比表",
      "",
      "| 维度 | PostgreSQL 17 | MySQL 8.4 |",
      "| --- | --- | --- |",
      "| JSON 数组索引 | GIN [evidence:pg] | 多值索引 [evidence:mysql] |",
      "| 查询方式 | 路径查询 | JSON_TABLE |",
    ].join("\n")} />);

    const table = screen.getByRole("table");
    expect(table).toBeTruthy();
    expect(screen.getAllByRole("columnheader")).toHaveLength(3);
    expect(screen.getAllByRole("rowheader")).toHaveLength(2);
    expect(screen.getAllByTitle(/evidence:/)).toHaveLength(2);
    expect(screen.getByRole("region", { name: "报告对比表" }).getAttribute("tabindex")).toBe("0");
  });
});

describe("MarkdownSurface 编号绑定角标", () => {
  const bindings = [
    { number: 1, evidenceId: "passage:c1" },
    { number: 2, evidenceId: "knowledge-version:v2" }
  ];

  it("按绑定把编号渲染成可点击角标，点击回传证据身份而不是编号", () => {
    const onEvidenceClick = vi.fn();
    render(<MarkdownSurface
      content="RRF 按名次融合 [1]，Wiki 页面说明了排序 [2]。"
      citationBindings={bindings}
      allowedEvidenceIds={["passage:c1", "knowledge-version:v2"]}
      evidenceOrder={["passage:c1", "knowledge-version:v2"]}
      onEvidenceClick={onEvidenceClick}
    />);

    fireEvent.click(screen.getByRole("button", { name: "查看来源 2" }));
    expect(onEvidenceClick).toHaveBeenCalledWith("knowledge-version:v2");
    expect(screen.getAllByRole("button")).toHaveLength(2);
  });

  it("未知编号与不在允许集合里的编号只渲染成普通文字", () => {
    render(<MarkdownSurface
      content="正文 [1]，越界编号 [7]，已撤销证据 [2]。"
      citationBindings={bindings}
      allowedEvidenceIds={["passage:c1"]}
      onEvidenceClick={() => undefined}
    />);

    expect(screen.getByRole("button", { name: "查看来源 1" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "查看来源 7" })).toBeNull();
    // 编号 2 有绑定，但证据不在本次允许集合内，同样不给按钮
    expect(screen.queryByRole("button", { name: "查看来源 2" })).toBeNull();
    expect(screen.getByText(/越界编号 \[7\]/)).toBeTruthy();
  });

  it("代码块、行内代码与转义字面量里的方括号不生成角标", () => {
    render(<MarkdownSurface
      content={[
        "正文引用 [1]。",
        "",
        "```",
        "const anchor = note[7];",
        "```",
        "",
        "行内 `pointer[3]` 与转义 \\[9] 都是字面量。"
      ].join("\n")}
      citationBindings={bindings}
      allowedEvidenceIds={["passage:c1"]}
      onEvidenceClick={() => undefined}
    />);

    expect(screen.getAllByRole("button", { name: /查看来源/ })).toHaveLength(1);
    expect(screen.getByText("const anchor = note[7];")).toBeTruthy();
    expect(screen.getByText("pointer[3]")).toBeTruthy();
    expect(screen.getByText(/转义 \[9\] 都是字面量/)).toBeTruthy();
  });

  it("没有绑定时沿用旧协议的编号按钮，行为不变", () => {
    const onCitationClick = vi.fn();
    render(<MarkdownSurface
      content="旧回答引用 [1]。"
      citations={["旧来源标题"]}
      onCitationClick={onCitationClick}
    />);

    fireEvent.click(screen.getByRole("button", { name: "来源 1：旧来源标题" }));
    expect(onCitationClick).toHaveBeenCalledWith(1);
  });
});