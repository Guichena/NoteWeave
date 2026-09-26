import { Fragment, type ReactNode } from "react";

type MarkdownSurfaceProps = {
  content: string;
  className?: string;
  compactCitations?: boolean;
  /** 传入后，正文里的 [1]、[2] 会渲染成可点击的来源角标。 */
  citations?: string[];
  activeCitation?: number | null;
  onCitationClick?: (index: number) => void;
  /** 传入后，[[页面标题]] 会渲染成可点击的 Wiki 链接；返回 false 表示页面不存在。 */
  onWikiLinkClick?: (title: string) => boolean | void;
  resolveWikiLink?: (title: string) => boolean;
};

type InlineContext = {
  compactCitations: boolean;
  citations?: string[];
  activeCitation?: number | null;
  onCitationClick?: (index: number) => void;
  onWikiLinkClick?: (title: string) => boolean | void;
  resolveWikiLink?: (title: string) => boolean;
};

function headingId(value: string) {
  return `report-${value.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-|-$/g, "")}`;
}

const headingPattern = /^(#{1,4})\s+(.+)$/;
const orderedItemPattern = /^\d+[.)]\s+(.+)$/;
const unorderedItemPattern = /^[-*+]\s+(.+)$/;
const tableDividerCellPattern = /^:?-{3,}:?$/;

export function MarkdownSurface({
  content,
  className = "",
  compactCitations = false,
  citations,
  activeCitation = null,
  onCitationClick,
  onWikiLinkClick,
  resolveWikiLink
}: MarkdownSurfaceProps) {
  const inline: InlineContext = { compactCitations, citations, activeCitation, onCitationClick, onWikiLinkClick, resolveWikiLink };
  const lines = content.replaceAll("\r\n", "\n").split("\n");
  const blocks: ReactNode[] = [];
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) {
      index += 1;
      continue;
    }

    if (line.trim().startsWith("```")) {
      const language = line.trim().slice(3).trim();
      const codeLines: string[] = [];
      index += 1;
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        codeLines.push(lines[index]);
        index += 1;
      }
      index += index < lines.length ? 1 : 0;
      blocks.push(
        <pre className="markdown-code-block" key={`code-${blocks.length}`}>
          {language ? <span className="markdown-code-language">{language}</span> : null}
          <code>{codeLines.join("\n")}</code>
        </pre>
      );
      continue;
    }

    const heading = line.match(headingPattern);
    if (heading) {
      const level = heading[1].length;
      const text = renderInlineMarkdown(heading[2], inline);
      const key = `heading-${blocks.length}`;
      blocks.push(level === 1
        ? <h2 id={headingId(heading[2])} key={key}>{text}</h2>
        : level === 2
          ? <h3 id={headingId(heading[2])} key={key}>{text}</h3>
          : <h4 id={headingId(heading[2])} key={key}>{text}</h4>);
      index += 1;
      continue;
    }

    const tableHeader = splitMarkdownTableRow(line);
    const tableDivider = index + 1 < lines.length ? splitMarkdownTableRow(lines[index + 1]) : null;
    if (tableHeader && tableDivider && isTableDivider(tableDivider, tableHeader.length)) {
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length) {
        const row = splitMarkdownTableRow(lines[index]);
        if (!row) break;
        rows.push(normalizeTableRow(row, tableHeader.length));
        index += 1;
      }
      blocks.push(
        <div className="markdown-table-scroll" role="region" aria-label="报告对比表" tabIndex={0} key={`table-${blocks.length}`}>
          <table>
            <thead>
              <tr>{tableHeader.map((cell, cellIndex) => <th scope="col" key={`head-${cellIndex}`}>{renderInlineMarkdown(cell, inline)}</th>)}</tr>
            </thead>
            <tbody>
              {rows.map((row, rowIndex) => (
                <tr key={`row-${rowIndex}`}>
                  {row.map((cell, cellIndex) => cellIndex === 0
                    ? <th scope="row" key={`cell-${cellIndex}`}>{renderInlineMarkdown(cell, inline)}</th>
                    : <td key={`cell-${cellIndex}`}>{renderInlineMarkdown(cell, inline)}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
      continue;
    }

    if (line.trimStart().startsWith(">")) {
      const quoteLines: string[] = [];
      while (index < lines.length && lines[index].trimStart().startsWith(">")) {
        quoteLines.push(lines[index].trimStart().replace(/^>\s?/, ""));
        index += 1;
      }
      blocks.push(
        <blockquote key={`quote-${blocks.length}`}>{renderInlineMarkdown(quoteLines.join(" "), inline)}</blockquote>
      );
      continue;
    }

    const ordered = line.trim().match(orderedItemPattern);
    const unordered = line.trim().match(unorderedItemPattern);
    if (ordered || unordered) {
      const items: ReactNode[] = [];
      const pattern = ordered ? orderedItemPattern : unorderedItemPattern;
      while (index < lines.length) {
        const item = lines[index].trim().match(pattern);
        if (!item) break;
        items.push(<li key={`item-${index}`}>{renderInlineMarkdown(item[1], inline)}</li>);
        index += 1;
      }
      blocks.push(ordered
        ? <ol key={`list-${blocks.length}`}>{items}</ol>
        : <ul key={`list-${blocks.length}`}>{items}</ul>);
      continue;
    }

    const paragraphLines = [line.trim()];
    index += 1;
    while (index < lines.length && lines[index].trim() && !isBlockStart(lines[index])) {
      paragraphLines.push(lines[index].trim());
      index += 1;
    }
    blocks.push(
      <p key={`paragraph-${blocks.length}`}>{renderInlineMarkdown(paragraphLines.join(" "), inline)}</p>
    );
  }

  return <div className={`markdown-surface${className ? ` ${className}` : ""}`}>{blocks}</div>;
}

function isBlockStart(line: string) {
  const trimmed = line.trim();
  return trimmed.startsWith("```")
    || headingPattern.test(trimmed)
    || trimmed.startsWith(">")
    || orderedItemPattern.test(trimmed)
    || unorderedItemPattern.test(trimmed)
    || Boolean(splitMarkdownTableRow(trimmed));
}

function splitMarkdownTableRow(line: string) {
  const trimmed = line.trim();
  if (!trimmed.includes("|") || trimmed.startsWith("```")) return null;
  const body = trimmed.replace(/^\|/, "").replace(/\|$/, "");
  const cells = body.split(/(?<!\\)\|/).map((cell) => cell.trim().replaceAll("\\|", "|"));
  return cells.length >= 2 ? cells : null;
}

function isTableDivider(cells: string[], columnCount: number) {
  return cells.length === columnCount && cells.every((cell) => tableDividerCellPattern.test(cell.replaceAll(" ", "")));
}

function normalizeTableRow(cells: string[], columnCount: number) {
  return Array.from({ length: columnCount }, (_, index) => cells[index] ?? "");
}

function renderInlineMarkdown(value: string, context: InlineContext) {
  const { compactCitations, citations, activeCitation, onCitationClick, onWikiLinkClick, resolveWikiLink } = context;
  const numberedCitations = Boolean(citations && citations.length > 0);
  const parts = [
    "`[^`]+`",
    "\\[\\[[^\\]]+\\]\\]",
    "\\*\\*[^*]+\\*\\*",
    "\\[[^\\]]+\\]\\(https?:\\/\\/[^\\s)]+\\)",
    ...(compactCitations ? ["\\[evidence(?::|-)[^\\]]+\\]"] : []),
    ...(numberedCitations ? ["\\[\\d{1,2}\\](?!\\()"] : [])
  ];
  const pattern = new RegExp(`(${parts.join("|")})`, "g");
  const tokens = value.split(pattern);
  return tokens.map((token, index) => {
    if (token.startsWith("`") && token.endsWith("`") && token.length > 1) {
      return <code key={index}>{token.slice(1, -1)}</code>;
    }
    if (/^\[\[[^\]]+\]\]$/.test(token)) {
      const title = token.slice(2, -2).split("|")[0].trim();
      const label = token.slice(2, -2).split("|").pop()?.trim() || title;
      if (!onWikiLinkClick) {
        return <span key={index} className="wikilink">{label}</span>;
      }
      const resolved = resolveWikiLink ? resolveWikiLink(title) : true;
      return (
        <button
          type="button"
          key={index}
          className={resolved ? "wikilink" : "wikilink is-unresolved"}
          title={resolved ? `打开《${title}》` : `《${title}》尚未创建`}
          onClick={() => onWikiLinkClick(title)}
        >
          {label}
        </button>
      );
    }
    if (token.startsWith("**") && token.endsWith("**") && token.length > 4) {
      return <strong key={index}>{token.slice(2, -2)}</strong>;
    }
    if (compactCitations && /^\[evidence(?::|-)[^\]]+\]$/.test(token)) {
      return <sup className="research-citation-ref" title={token.slice(1, -1)} key={index}>[证据]</sup>;
    }
    if (numberedCitations && /^\[\d{1,2}\]$/.test(token)) {
      const citationNumber = Number(token.slice(1, -1));
      const label = citations?.[citationNumber - 1] ?? `来源 ${citationNumber}`;
      return (
        <button
          type="button"
          key={index}
          className={`citation-chip${activeCitation === citationNumber ? " is-active" : ""}`}
          title={label}
          aria-label={`来源 ${citationNumber}：${label}`}
          onClick={() => onCitationClick?.(citationNumber)}
        >
          {citationNumber}
        </button>
      );
    }
    const link = token.match(/^\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/);
    if (link) {
      return <a key={index} href={link[2]} target="_blank" rel="noreferrer">{link[1]}</a>;
    }
    return <Fragment key={index}>{token}</Fragment>;
  });
}
