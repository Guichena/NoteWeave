import {
  BookOpen, CircleCheck, CircleDashed, ExternalLink, Info, Lightbulb, ListChecks, Sparkles, Table2, TriangleAlert
} from "lucide-react";
import { MarkdownSurface } from "../../shared/ui/MarkdownSurface";
import { orderReportEvidence } from "./researchTable";

type ResearchReportViewProps = {
  markdown: string;
  status?: string;
  sourceCount?: number;
  updatedAt?: string;
  /** 传入后正文中的证据引用可点击，用于打开证据详情。 */
  onEvidenceClick?: (evidenceId: string) => void;
  activeEvidence?: string | null;
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

const AUDIT_FIELD_LABELS: Array<[RegExp, string]> = [
  [/^(\s*-\s+)Evidence:\s*/gm, "$1证据："],
  [/^(\s*-\s+)Exact quote:\s*/gm, "$1原文引句："],
  [/^(\s*-\s+)Snapshot:\s*/gm, "$1快照："],
  [/^(\s*-\s+)URL:\s*/gm, "$1链接："]
];

/** 报告里的审计字段名是后端契约（拆分逻辑依赖它们），只在展示时换成中文。 */
export function localizeResearchAudit(audit: string) {
  return AUDIT_FIELD_LABELS.reduce((text, [pattern, label]) => text.replace(pattern, label), audit);
}

export function extractResearchHeadings(markdown: string) {
  return markdown
    .split(/\r?\n/)
    .flatMap((line) => {
      const heading = line.match(/^##\s+(.+)$/)?.[1]?.trim();
      return heading && heading.toLowerCase() !== "citation audit" ? [heading] : [];
    });
}

export type ReportSectionKind = "default" | "table" | "extra" | "pending" | "background" | "limits";

export type ReportSection = { heading: string; body: string; kind: ReportSectionKind };

export type ReportVerification = { verified: number | null; missing: number | null; text: string };

/** 按二级标题拆成文档的各节；第一个二级标题之前是摘要，其中的核验情况一行单独取出，显示成状态标签。 */
export function splitReportSections(body: string) {
  const lines = body.replaceAll("\r\n", "\n").split("\n");
  let title = "";
  const lead: string[] = [];
  const sections: ReportSection[] = [];
  for (const line of lines) {
    const heading = line.match(/^##\s+(.+)$/)?.[1]?.trim();
    if (heading) {
      sections.push({ heading, body: "", kind: sectionKind(heading) });
      continue;
    }
    if (!sections.length && !title && /^#\s+/.test(line)) {
      title = line.replace(/^#\s+/, "").trim();
      continue;
    }
    if (sections.length) sections[sections.length - 1].body += `${line}\n`;
    else lead.push(line);
  }
  let verification: ReportVerification | null = null;
  const summary = lead.filter((line) => {
    const match = line.match(/^\*\*核验情况：\*\*\s*(.+)$/);
    if (!match) return true;
    verification = {
      verified: Number(match[1].match(/已核验\s*(\d+)\s*项/)?.[1] ?? NaN) || null,
      missing: Number(match[1].match(/(?:未找到足够证据|证据不足)\s*(\d+)\s*项/)?.[1] ?? NaN) || null,
      text: match[1].replace(/[。.]\s*$/, "")
    };
    return false;
  }).join("\n").trim();
  return { title, summary, verification: verification as ReportVerification | null, sections };
}

function sectionKind(heading: string): ReportSectionKind {
  if (/^(对比表|Comparison)/i.test(heading)) return "table";
  if (/^(其他已核验要点|补充核验结果|Additional verified findings)/i.test(heading)) return "extra";
  if (/^(待核实的线索|未经核实的线索)/.test(heading)) return "pending";
  if (/^(补充说明（通用知识|Background notes)/i.test(heading)) return "background";
  if (/^(局限|Limitations)/i.test(heading)) return "limits";
  return "default";
}

const SECTION_ICON: Partial<Record<ReportSectionKind, typeof Info>> = {
  table: Table2,
  extra: ListChecks,
  pending: CircleDashed,
  background: Lightbulb,
  limits: TriangleAlert
};

export type ReportSource = {
  key: string;
  title: string;
  url: string;
  domain: string;
  entries: Array<{ number: number; evidenceId: string; quote: string }>;
};

/** 解析后端整理的引用核对（### [编号] 标题、Evidence、Exact quote、URL），同一网址的多条引用合成一个来源。 */
export function parseReportSources(audit: string): ReportSource[] {
  const sources = new Map<string, ReportSource>();
  const blocks = audit.replaceAll("\r\n", "\n").split(/^### /m).slice(1);
  for (const block of blocks) {
    const head = block.match(/^\[(\d+)\]\s+(.+)$/m);
    if (!head) continue;
    const evidence = block.match(/Evidence:\s*`([^`]+)`/)?.[1] ?? "";
    const quote = block.match(/Exact quote:\s*[“"]([\s\S]*?)[”"]\s*$/m)?.[1]?.trim() ?? "";
    const url = block.match(/URL:\s*(\S+)/)?.[1] ?? "";
    const title = head[2].trim();
    const key = url || title;
    const source = sources.get(key) ?? { key, title, url, domain: domainOf(url), entries: [] };
    source.entries.push({ number: Number(head[1]), evidenceId: evidence.replace(/^evidence[:-]/, ""), quote });
    sources.set(key, source);
  }
  return [...sources.values()];
}

function domainOf(url: string) {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return "";
  }
}

export function ResearchReportView({
  markdown,
  status,
  sourceCount,
  updatedAt,
  onEvidenceClick,
  activeEvidence = null
}: ResearchReportViewProps) {
  const { body, audit } = splitResearchReport(markdown);
  const { title, summary, verification, sections } = splitReportSections(body);
  const sources = parseReportSources(audit);
  const evidenceOrder = orderReportEvidence(body);
  const hasMeta = Boolean(status) || typeof sourceCount === "number" || Boolean(updatedAt);
  const quoteCount = sources.reduce((total, source) => total + source.entries.length, 0);

  const surface = (content: string, className: string) => (
    <MarkdownSurface
      content={content}
      className={className}
      compactCitations
      onEvidenceClick={onEvidenceClick}
      evidenceOrder={evidenceOrder}
      activeEvidence={activeEvidence}
    />
  );

  return (
    <article className="research-reader research-document" aria-label="研究报告正文">
      {hasMeta ? (
        <header className="research-reader-meta">
          <span className="research-reader-kicker">研究报告</span>
          <div className="research-reader-facts" aria-label="报告信息">
            {status ? <span>{status}</span> : null}
            {typeof sourceCount === "number" ? <span>{sourceCount} 个研究来源</span> : null}
            {updatedAt ? <span>更新于 {updatedAt}</span> : null}
          </div>
        </header>
      ) : null}

      {title ? <h1 className="research-doc-title">{title}</h1> : null}

      {summary || verification ? (
        <section className="research-doc-summary" aria-label="摘要">
          <span className="research-doc-label"><Sparkles size={14} aria-hidden="true" />摘要</span>
          {summary ? surface(summary, "research-doc-lead") : null}
          {verification || sources.length ? (
            <div className="research-doc-facts" aria-label="核验情况">
              {verification?.verified ? (
                <span className="is-verified"><CircleCheck size={13} aria-hidden="true" />已核验 {verification.verified} 项</span>
              ) : null}
              {verification?.missing ? (
                <span className="is-missing"><CircleDashed size={13} aria-hidden="true" />证据不足 {verification.missing} 项</span>
              ) : null}
              {verification && !verification.verified && !verification.missing ? <span>{verification.text}</span> : null}
              {sources.length ? (
                <span><BookOpen size={13} aria-hidden="true" />{sources.length} 个来源 · {quoteCount} 条引用</span>
              ) : null}
            </div>
          ) : null}
        </section>
      ) : null}

      {sections.length > 1 ? (
        <nav className="research-reader-toc" aria-label="报告目录">
          <span>目录</span>
          <ol>
            {sections.map((section) => (
              <li key={section.heading}><a href={`#${headingId(section.heading)}`}>{section.heading}</a></li>
            ))}
          </ol>
        </nav>
      ) : null}

      <div className="research-reader-body research-doc-body">
        {sections.map((section, index) => {
          const Icon = SECTION_ICON[section.kind];
          return (
            <section
              key={`${section.heading}-${index}`}
              id={headingId(section.heading)}
              className={`research-doc-section is-${section.kind}`}
              aria-labelledby={`${headingId(section.heading)}-title`}
            >
              <h2 id={`${headingId(section.heading)}-title`}>
                {Icon ? <Icon size={16} aria-hidden="true" /> : <span className="research-doc-no">{String(index + 1).padStart(2, "0")}</span>}
                {section.heading}
              </h2>
              {surface(section.body, "research-markdown-report")}
            </section>
          );
        })}
      </div>

      {sources.length ? (
        <section className="research-doc-sources" aria-label="参考来源">
          <h2><BookOpen size={16} aria-hidden="true" />参考来源<small>{sources.length} 个来源 · {quoteCount} 条引用</small></h2>
          <ol>
            {sources.map((source) => (
              <li key={source.key} className={source.entries.some((entry) => entry.evidenceId === activeEvidence) ? "is-active" : undefined}>
                <div className="research-source-numbers">
                  {source.entries.map((entry) => onEvidenceClick ? (
                    <button
                      type="button"
                      key={entry.number}
                      className={`citation-chip${entry.evidenceId === activeEvidence ? " is-active" : ""}`}
                      aria-label={`查看来源 ${entry.number}`}
                      onClick={() => onEvidenceClick(entry.evidenceId)}
                    >
                      {entry.number}
                    </button>
                  ) : <span key={entry.number} className="citation-chip">{entry.number}</span>)}
                </div>
                <div className="research-source-main">
                  {source.url ? (
                    <a className="research-source-title" href={source.url} target="_blank" rel="noreferrer">{source.title}</a>
                  ) : <span className="research-source-title">{source.title}</span>}
                  {source.domain ? (
                    <span className="research-source-domain">
                      {source.domain}{source.url ? <ExternalLink size={11} aria-hidden="true" /> : null}
                    </span>
                  ) : null}
                  {source.entries.some((entry) => entry.quote) ? (
                    <details className="research-source-quotes">
                      <summary>原文摘录{source.entries.length > 1 ? ` ${source.entries.length} 条` : ""}</summary>
                      <ul>
                        {source.entries.filter((entry) => entry.quote).map((entry) => (
                          <li key={entry.number}><b>[{entry.number}]</b>{entry.quote}</li>
                        ))}
                      </ul>
                    </details>
                  ) : null}
                </div>
              </li>
            ))}
          </ol>
        </section>
      ) : audit ? (
        <details className="research-audit-disclosure">
          <summary>
            <span>引用来源</span>
            <small>按正文角标编号列出每条引用的原文摘录和来源链接</small>
          </summary>
          <MarkdownSurface content={localizeResearchAudit(audit)} className="research-audit-markdown" />
        </details>
      ) : null}
    </article>
  );
}

function headingId(value: string) {
  return `report-${value.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "")}`;
}

export { headingId as researchHeadingId };
