import { useEffect, useState } from "react";
import { Check, ChevronDown, Copy } from "lucide-react";
import { type Message } from "./messageTypes";
import { MarkdownSurface } from "../../shared/ui/MarkdownSurface";

type MessageSection = {
  title: string;
  content: string;
};

type MessageCard = {
  title: string;
  preview: string;
  content: string;
};

type MessageSources = {
  title: string;
  items: string[];
  note: string;
};

type MessagePresentation = {
  leadTitle: string | null;
  body: string;
  cards: MessageCard[];
  sources: MessageSources | null;
};

const CITATION_SECTION_TITLES = new Set(["引用来源", "来源引用", "来源回链"]);

export function MessageBubble({ message }: { message: Message }) {
  if (message.role !== "assistant" || !message.answerMode) {
    return <div className="bubble-body">{message.content}</div>;
  }
  return <AssistantMessage message={message} />;
}

function AssistantMessage({ message }: { message: Message }) {
  const [activeCitation, setActiveCitation] = useState<number | null>(null);
  const [sourcesOpen, setSourcesOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const isGenerating = message.answerStatus === "GENERATING";
  const isFailed = message.answerStatus === "FAILED" || message.answerStatus === "CANCELLED";
  const presentation = buildAssistantPresentation(message);
  const body = isFailed
    ? "本次回答未能完成。"
    : presentation.body
      || (isGenerating
      ? "正在检索当前工作台资料并生成回答…"
      : "此回答暂未返回正文。");
  const sources = !isFailed ? presentation.sources : null;
  const sourceItems = sources?.items ?? [];

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1600);
    return () => window.clearTimeout(timer);
  }, [copied]);

  function focusCitation(index: number) {
    setActiveCitation((current) => (current === index ? null : index));
    setSourcesOpen(true);
  }

  async function copyAnswer() {
    try {
      await navigator.clipboard?.writeText(presentation.body || message.content);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <div className="assistant-message" aria-live={isGenerating ? "polite" : undefined}>
      {isGenerating ? (
        <div className="answer-run-status">
          <span className="answer-run-spinner" aria-hidden="true" />
          正在生成回答
        </div>
      ) : null}
      {isFailed ? (
        <div className="answer-run-error" role="alert">
          回答未完成：{message.answerError || "请稍后重试。"}
        </div>
      ) : null}
      {!isFailed && presentation.leadTitle ? <div className="bubble-label">{presentation.leadTitle}</div> : null}
      <MarkdownSurface
        content={body}
        className="bubble-body"
        citations={sourceItems.length > 0 ? sourceItems : undefined}
        activeCitation={activeCitation}
        onCitationClick={focusCitation}
      />
      {!isFailed && presentation.cards.length > 0 ? (
        <div className="bubble-cards">
          {presentation.cards.map((card, index) => (
            <details className="message-card" key={`${card.title}-${index}`}>
              <summary>
                <strong>{card.title}</strong>
                <span>{card.preview}</span>
                <ChevronDown className="message-card-chevron" size={15} aria-hidden="true" />
              </summary>
              <MarkdownSurface content={card.content} className="message-card-body" />
            </details>
          ))}
        </div>
      ) : null}
      {sources ? (
        <details
          className="message-sources"
          open={sourcesOpen}
          onToggle={(event) => setSourcesOpen((event.currentTarget as HTMLDetailsElement).open)}
        >
          <summary>
            <strong>{sources.title}</strong>
            <span>{sourceItems.length > 0 ? `${sourceItems.length} 条来源` : "说明"}</span>
            {sourceItems.length > 0 ? (
              <span className="message-sources-peek" aria-hidden="true">
                {sourceItems.slice(0, 3).map((item, index) => (
                  <span key={`${item}-${index}`}>{shortSourceLabel(item)}</span>
                ))}
              </span>
            ) : null}
            <ChevronDown className="message-card-chevron" size={15} aria-hidden="true" />
          </summary>
          {sourceItems.length > 0 ? (
            <ol className="message-source-list">
              {sourceItems.map((item, index) => (
                <li
                  key={`${item}-${index}`}
                  className={activeCitation === index + 1 ? "is-active" : undefined}
                >
                  <button type="button" className="citation-chip" onClick={() => focusCitation(index + 1)}>
                    {index + 1}
                  </button>
                  <span>{item}</span>
                </li>
              ))}
            </ol>
          ) : null}
          {sources.note ? <MarkdownSurface content={sources.note} className="message-sources-note" /> : null}
        </details>
      ) : null}
      {!isGenerating && !isFailed && presentation.body ? (
        <div className="message-actions">
          <button
            type="button"
            className="message-action"
            aria-label={copied ? "已复制回答" : "复制回答"}
            title={copied ? "已复制" : "复制"}
            onClick={() => void copyAnswer()}
          >
            {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
          </button>
        </div>
      ) : null}
    </div>
  );
}

function shortSourceLabel(value: string) {
  const cleaned = value.replace(/^\[\d+\]\s*/, "").split(/\s[·|]\s|\s\|\s/)[0] ?? value;
  return trimText(cleaned.trim(), 18);
}

function buildAssistantPresentation(message: Message): MessagePresentation {
  const parsedSections = splitMarkdownSections(message.content, message.answerMode === "wiki");
  const sections = message.answerMode === "wiki"
    ? normalizeWikiMessageSections(parsedSections)
    : parsedSections;
  const [leadSection, ...restSections] = sections;
  const citations = message.citations ?? [];
  const citationSection = restSections.find((section) => CITATION_SECTION_TITLES.has(section.title.trim()));
  const cards = message.answerMode === "note"
    ? buildNoteMessageCards(restSections)
    : message.answerMode === "wiki"
      ? buildWikiMessageCards(restSections)
      : buildDefaultMessageCards(restSections);
  const sources = buildMessageSources(citationSection, citations);
  if (!leadSection) {
    return {
      leadTitle: null,
      body: message.answerMode === "wiki" ? "" : message.content.trim(),
      cards,
      sources
    };
  }
  return {
    leadTitle: normalizeLeadTitle(leadSection.title),
    body: leadSection.content || leadSection.title,
    cards,
    sources
  };
}

function buildMessageSources(citationSection: MessageSection | undefined, citations: string[]): MessageSources | null {
  const sectionContent = citationSection?.content.trim() ?? "";
  if (!sectionContent && citations.length === 0) {
    return null;
  }
  const bulletItems = sectionContent
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => /^([-*+]|\d+[.)])\s+/.test(line))
    .map((line) => line.replace(/^([-*+]|\d+[.)])\s+/, "").replace(/^\[\d{1,2}\]\s*/, "").trim())
    .filter(Boolean);
  const items = citations.length > 0 ? citations : bulletItems;
  const note = bulletItems.length > 0 || !sectionContent ? "" : sectionContent;
  return {
    title: "来源引用",
    items,
    note
  };
}

function normalizeWikiMessageSections(sections: MessageSection[]) {
  return sections.flatMap((section) => {
    const title = section.title.trim();
    if (title === "页面关系") return [];
    if (title !== "默认 Wiki 工作台") return [section];

    const userFacingContent = section.content
      .split("\n")
      .filter((line) => !/^\s*\/workspaces(?:\/[^\s/]+)+\s*$/.test(line))
      .join("\n")
      .trim();
    return userFacingContent ? [{ title: "", content: userFacingContent }] : [];
  });
}

function buildWikiMessageCards(restSections: MessageSection[]) {
  const chatRedundantSections = new Set(["页面关系", "默认 Wiki 工作台"]);
  return buildDefaultMessageCards(restSections)
    .filter((card) => !chatRedundantSections.has(card.title));
}

function buildDefaultMessageCards(restSections: MessageSection[]) {
  return restSections
    .filter((section) => section.content)
    .filter((section) => !CITATION_SECTION_TITLES.has(section.title.trim()))
    .map((section) => buildMessageCard(section.title, section.content));
}

function buildNoteMessageCards(restSections: MessageSection[]) {
  const byTitle = new Map(restSections.map((section) => [section.title.trim(), section]));
  const directLocation = byTitle.get("资料定位");
  const directReading = byTitle.get("深读窗口");
  const directEvidence = byTitle.get("摘录证据");
  if (directLocation || directReading || directEvidence) {
    const cards: MessageCard[] = [];
    if (directLocation?.content) {
      cards.push(buildMessageCard("资料定位", directLocation.content));
    }
    if (directReading?.content) {
      cards.push(buildMessageCard("深读窗口", directReading.content));
    }
    if (directEvidence?.content) {
      cards.push(buildMessageCard("摘录证据", directEvidence.content));
    }
    return cards;
  }
  const cards: MessageCard[] = [];
  pushMergedNoteCard(cards, "资料定位", [
    byTitle.get("检索说明"),
    byTitle.get("Journal 信号"),
    byTitle.get("候选资料"),
    byTitle.get("关系扩展"),
    byTitle.get("验证批次")
  ]);
  pushMergedNoteCard(cards, "深读窗口", [
    byTitle.get("条目元数据"),
    byTitle.get("原文读取计划")
  ]);
  pushMergedNoteCard(cards, "摘录证据", [
    byTitle.get("关键观点"),
    byTitle.get("摘录证据")
  ]);
  return cards;
}

function pushMergedNoteCard(cards: MessageCard[], title: string, sections: Array<MessageSection | undefined>) {
  const content = mergeCardSections(sections);
  if (!content) {
    return;
  }
  cards.push(buildMessageCard(title, content));
}

function mergeCardSections(sections: Array<MessageSection | undefined>) {
  const filled = sections.filter((section): section is MessageSection => Boolean(section?.content?.trim()));
  if (filled.length === 0) {
    return "";
  }
  if (filled.length === 1) {
    return filled[0].content.trim();
  }
  return filled
    .map((section) => `【${normalizeCardTitle(section.title)}】\n${section.content.trim()}`)
    .join("\n\n");
}

function splitMarkdownSections(content: string, includeLevelThree = false): MessageSection[] {
  const normalized = content.replaceAll("\r\n", "\n").trim();
  const headingPattern = includeLevelThree ? /^#{2,3}\s+(.+)$/gm : /^##\s+(.+)$/gm;
  const matches = Array.from(normalized.matchAll(headingPattern));
  if (matches.length === 0) {
    return normalized ? [{ title: "", content: normalized }] : [];
  }
  const sections = matches.map((match, index) => {
    const title = (match[1] ?? "").trim();
    const start = (match.index ?? 0) + match[0].length;
    const end = index + 1 < matches.length ? (matches[index + 1].index ?? normalized.length) : normalized.length;
    return {
      title,
      content: normalized.slice(start, end).trim()
    };
  });
  const preamble = normalized.slice(0, matches[0].index ?? 0).trim();
  return preamble ? [{ title: "", content: preamble }, ...sections] : sections;
}

function normalizeLeadTitle(title: string) {
  const trimmed = title.trim();
  if (!trimmed || trimmed === "直接回答") {
    return null;
  }
  return trimmed;
}

function buildMessageCard(title: string, content: string, citationCount?: number): MessageCard {
  const normalizedTitle = normalizeCardTitle(title);
  return {
    title: normalizedTitle,
    preview: summarizeCard(normalizedTitle, content, citationCount),
    content
  };
}

function normalizeCardTitle(title: string) {
  switch (title.trim()) {
    case "关键关系梳理":
    case "关键关系":
      return "关键页面关系";
    case "引用来源":
    case "来源回链":
    case "来源引用":
      return "来源引用";
    case "原文读取计划":
      return "原文窗口";
    case "条目元数据":
      return "资料元信息";
    default:
      return title.trim();
  }
}

function summarizeCard(title: string, content: string, citationCount?: number) {
  const bulletCount = countBulletLines(content);
  if (title === "来源引用") {
    return `${citationCount ?? bulletCount ?? 0} 条来源`;
  }
  if (title === "资料定位") {
    const count = countSectionBullets(content, "候选资料") || bulletCount;
    return `${count} 份候选资料`;
  }
  if (title === "深读窗口") {
    const count = countSectionBullets(content, "原文窗口") || bulletCount;
    return `${count} 个阅读窗口`;
  }
  if (title === "候选资料") {
    return `${bulletCount} 份候选资料`;
  }
  if (title === "关系扩展") {
    return `${bulletCount} 条扩展关系`;
  }
  if (title === "验证批次") {
    return `${bulletCount} 项验证摘要`;
  }
  if (title === "资料元信息") {
    return `${bulletCount} 份资料元信息`;
  }
  if (title === "原文窗口") {
    return `${bulletCount} 个阅读窗口`;
  }
  if (title === "摘录证据" || title === "关键依据" || title === "关键观点") {
    return `${bulletCount} 条证据`;
  }
  if (title === "Journal 信号") {
    return `${bulletCount} 条历史整理信号`;
  }
  if (title === "检索说明") {
    return "检索方式与展示策略";
  }
  if (title === "关键页面关系" || title === "反向引用关系" || title === "页面关系") {
    return `${bulletCount} 条页面关系`;
  }
  const firstLine = content.split("\n").map((line) => line.trim()).find(Boolean);
  return firstLine ? trimText(firstLine, 32) : "点击展开";
}

function countBulletLines(content: string) {
  return content.split("\n").filter((line) => line.trim().startsWith("- ")).length;
}

function countSectionBullets(content: string, sectionTitle: string) {
  const escapedTitle = sectionTitle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(`【${escapedTitle}】([\\s\\S]*?)(?=\\n\\n【|$)`);
  const match = content.match(pattern);
  return match ? countBulletLines(match[1]) : 0;
}

function trimText(value: string, limit: number) {
  return value.length <= limit ? value : `${value.slice(0, Math.max(0, limit - 1))}…`;
}
