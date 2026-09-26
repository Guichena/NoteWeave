import { MarkdownSurface } from "../../shared/ui/MarkdownSurface";

type ResearchReportViewProps = {
  markdown: string;
  status?: string;
  sourceCount?: number;
  updatedAt?: string;
};

export function splitResearchReport(markdown: string) {
  const marker = /^##\s+Citation audit\s*$/im;
  const match = marker.exec(markdown);
  if (!match) return splitLegacyResearchReport(markdown);
  return {
    body: markdown.slice(0, match.index).trim(),
    audit: markdown.slice(match.index + match[0].length).trim(),
  };
}

function splitLegacyResearchReport(markdown: string) {
  const lines = markdown.replaceAll("\r\n", "\n").split("\n");
  const body: string[] = [];
  const audit: string[] = [];
  let index = 0;
  while (index < lines.length) {
    if (!/^#{3,4}\s+/.test(lines[index])) {
      body.push(lines[index]);
      index += 1;
      continue;
    }
    const block = [lines[index]];
    index += 1;
    while (index < lines.length && !/^#{1,4}\s+/.test(lines[index])) {
      block.push(lines[index]);
      index += 1;
    }
    const evidence = block.join("\n").match(/^-\s+Evidence:\s+`?([^`\s]+)`?/m)?.[1];
    const isAuditBlock = Boolean(evidence) && block.some((line) => /^\s*-\s+Snapshot:/i.test(line));
    if (!isAuditBlock) {
      body.push(...block);
      continue;
    }
    const finding = block[0].replace(/^#{3,4}\s+/, "").trim();
    body.push(`${finding}${evidence ? ` [${evidence}]` : ""}`, "");
    audit.push(`### ${finding}`, "", ...block.slice(1), "");
  }
  return {
    body: body.join("\n").trim(),
    audit: audit.join("\n").trim(),
  };
}

export function extractResearchHeadings(markdown: string) {
  return markdown
    .split(/\r?\n/)
    .flatMap((line) => {
      const heading = line.match(/^##\s+(.+)$/)?.[1]?.trim();
      return heading && heading.toLowerCase() !== "citation audit" ? [heading] : [];
    });
}

export function ResearchReportView({ markdown, status, sourceCount, updatedAt }: ResearchReportViewProps) {
  const { body, audit } = splitResearchReport(markdown);
  const headings = extractResearchHeadings(body);

  return (
    <section className="research-reader" aria-label="研究报告正文">
      <header className="research-reader-meta">
        <span className="research-reader-kicker">Deep research report</span>
        <div className="research-reader-facts" aria-label="报告信息">
          {status ? <span>{status}</span> : null}
          {typeof sourceCount === "number" ? <span>{sourceCount} 个研究来源</span> : null}
          {updatedAt ? <span>更新于 {updatedAt}</span> : null}
        </div>
      </header>

      {headings.length > 1 ? (
        <nav className="research-reader-toc" aria-label="报告目录">
          <span>目录</span>
          <ol>
            {headings.map((heading) => (
              <li key={heading}><a href={`#${headingId(heading)}`}>{heading}</a></li>
            ))}
          </ol>
        </nav>
      ) : null}

      <MarkdownSurface
        content={body}
        className="research-markdown-report research-reader-body"
        compactCitations
      />

      {audit ? (
        <details className="research-audit-disclosure">
          <summary>
            <span>引用与研究审计</span>
            <small>查看原始证据、网页快照和引用链</small>
          </summary>
          <MarkdownSurface content={audit} className="research-audit-markdown" />
        </details>
      ) : null}
    </section>
  );
}

function headingId(value: string) {
  return `report-${value.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "")}`;
}

export { headingId as researchHeadingId };
