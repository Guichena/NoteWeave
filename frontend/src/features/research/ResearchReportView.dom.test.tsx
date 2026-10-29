// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import {
  localizeResearchAudit, parseReportSources, ResearchReportView, splitReportSections, splitResearchReport
} from "./ResearchReportView";

afterEach(cleanup);

const report = `# Research report

Verified summary.

## Findings

Finding one [evidence-a]

## Limitations

- Limited sample

## Citation audit

### cell-a

- Evidence: \`evidence-a\`
- Snapshot: \`snapshot-a\``;

describe("ResearchReportView", () => {
  it("keeps audit material out of the reading body and behind disclosure", () => {
    render(<ResearchReportView markdown={report} status="COMPLETED" sourceCount={1} />);

    expect(screen.getByRole("heading", { name: "Research report" })).toBeTruthy();
    expect(screen.getByRole("navigation", { name: "报告目录" })).toBeTruthy();
    expect(screen.getByText("引用来源")).toBeTruthy();
    expect(document.querySelector(".research-reader-body")?.textContent).not.toContain("snapshot-a");
    expect(document.querySelector(".research-audit-markdown")?.textContent).toContain("snapshot-a");
    expect(document.querySelector(".research-audit-markdown")?.textContent).toContain("快照：");
    expect(document.querySelector(".research-audit-markdown")?.textContent).not.toContain("Snapshot:");
    expect(document.querySelector(".research-citation-ref")?.textContent).toBe("[1]");
  });

  it("localizes audit field names for display only", () => {
    expect(localizeResearchAudit("- Evidence: `e`\n- Exact quote: “q”\n  - Snapshot: `s`\n- URL: https://x"))
      .toBe("- 证据：`e`\n- 原文引句：“q”\n  - 快照：`s`\n- 链接：https://x");
  });

  it("preserves reports that do not contain an audit appendix", () => {
    expect(splitResearchReport("# Report\n\nBody")).toEqual({ body: "# Report\n\nBody", audit: "" });
  });

  it("moves legacy inline evidence blocks into the audit disclosure", () => {
    const legacy = `# Report

### Verified findings

### Latency is 50 ms

- Cell: \`latency\`
- Evidence: \`evidence-a\`
- Exact quote: “Latency is 50 ms”
  - Snapshot: \`snapshot-a\`

### Limitations

- One release`;

    const result = splitResearchReport(legacy);

    expect(result.body).toContain("Latency is 50 ms [evidence-a]");
    expect(result.body).not.toContain("snapshot-a");
    expect(result.audit).toContain("snapshot-a");
  });

  const documentReport = `# 结论报告

QUIC 的各条流相互独立 [evidence:aa]。

**核验情况：** 已核验 3 项，未找到足够证据 2 项。

## 直接回答

HTTP/2 未解决 TCP 队头阻塞 [evidence:aa]，据 RFC 9000 的说明，丢包只影响所在的流（待核实）[evidence:bb]。

## 补充说明（通用知识，未经本次检索核验）

以下内容依据公开的通用技术知识补充，仅供参考。

- TLS 1.3 的完整握手只需一个往返。

## 局限与未解决项

- 部分必答研究项未能核验

## Citation audit

### [1] RFC 9113: HTTP/2

- Evidence: \`evidence:aa\`
  - Exact quote: “TCP head-of-line blocking is not addressed by this protocol.”
  - URL: https://www.rfc-editor.org/rfc/rfc9113.html

### [2] RFC 9000

- Evidence: \`evidence:bb\`
  - Exact quote: “Streams are independent of each other.”
  - URL: https://www.rfc-editor.org/rfc/rfc9000.html
`;

  it("renders the report as a document: summary with verification, numbered sections, labelled notes and sources", () => {
    render(<ResearchReportView markdown={documentReport} onEvidenceClick={() => undefined} />);

    const summary = screen.getByRole("region", { name: "摘要" });
    expect(within(summary).getByText("已核验 3 项")).toBeTruthy();
    expect(within(summary).getByText("证据不足 2 项")).toBeTruthy();
    expect(document.querySelector(".research-doc-body")?.textContent).not.toContain("核验情况");

    expect(document.querySelector(".research-doc-section.is-background")?.textContent).toContain("一个往返");
    expect(document.querySelector(".research-doc-section.is-limits")).toBeTruthy();
    expect(screen.getByText("待核实").className).toBe("research-pending-badge");

    const sources = screen.getByRole("region", { name: "参考来源" });
    expect(within(sources).getByRole("link", { name: "RFC 9113: HTTP/2" }).getAttribute("href")).toBe("https://www.rfc-editor.org/rfc/rfc9113.html");
    expect(within(sources).getAllByText("rfc-editor.org")).toHaveLength(2);
    expect(within(sources).getByText("Streams are independent of each other.")).toBeTruthy();
  });

  it("groups citations of the same page into one source", () => {
    const audit = `### [1] Page

- Evidence: \`evidence:a\`
  - Exact quote: “one”
  - URL: https://x.example/p

### [2] Page

- Evidence: \`evidence:b\`
  - Exact quote: “two”
  - URL: https://x.example/p
`;
    const sources = parseReportSources(audit);
    expect(sources).toHaveLength(1);
    expect(sources[0].entries.map((entry) => [entry.number, entry.evidenceId, entry.quote])).toEqual([[1, "a", "one"], [2, "b", "two"]]);
    expect(splitReportSections(`## A

x

## 待核实的线索

y`).sections.map((section) => section.kind)).toEqual(["default", "pending"]);
  });
});
