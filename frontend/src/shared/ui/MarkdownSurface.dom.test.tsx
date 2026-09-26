// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
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
