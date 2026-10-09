// @vitest-environment jsdom

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { localizeResearchAudit, ResearchReportView, splitResearchReport } from "./ResearchReportView";

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
    expect(screen.getByText("引用与研究审计")).toBeTruthy();
    expect(document.querySelector(".research-reader-body")?.textContent).not.toContain("snapshot-a");
    expect(document.querySelector(".research-audit-markdown")?.textContent).toContain("snapshot-a");
    expect(document.querySelector(".research-audit-markdown")?.textContent).toContain("快照：");
    expect(document.querySelector(".research-audit-markdown")?.textContent).not.toContain("Snapshot:");
    expect(document.querySelector(".research-citation-ref")?.textContent).toBe("[证据]");
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
});
