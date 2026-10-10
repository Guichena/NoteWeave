import { type WikiIssue, type WikiPage } from "./model";

export function getWikiKindRank(kind: string) {
  const normalized = (kind || "TOPIC").toUpperCase();
  switch (normalized) {
    case "OVERVIEW":
      return 0;
    case "TOPIC":
      return 1;
    case "CONCEPT":
      return 2;
    case "COMPARISON":
      return 3;
    default:
      return 9;
  }
}

export function getSeverityRank(severity: string) {
  switch ((severity || "").toUpperCase()) {
    case "HIGH":
      return 0;
    case "MEDIUM":
      return 1;
    case "LOW":
      return 2;
    default:
      return 9;
  }
}

export function formatWikiRelationType(relationType: string) {
  switch ((relationType || "").toUpperCase()) {
    case "AUTO_LINK":
      return "自动互链";
    case "HYBRID_LINK":
      return "混合互链";
    case "WIKI_LINK":
      return "显式链接";
    default:
      return relationType || "页面关系";
  }
}

export function composeMissingWikiPageDraft(targetTitle: string, sourceTitle?: string) {
  const relationLine = sourceTitle
    ? `- 当前缺口来源页：[[${sourceTitle}]]`
    : "- 当前缺口来源页：待补充";
  return `# ${targetTitle}

## 页面定位

该页面用于补齐当前工作台 Wiki 网络中的缺失页面，避免知识关系在这里中断。

## 关联关系

${relationLine}
- 与其他相关页面的关系：待补充

## 待补充内容

- 核心定义或主题说明
- 关键事实与结论
- 需要补充的来源依据
`;
}

export function composeWikiAppendDraft(issueType: string, pageTitle: string) {
  if (issueType === "MISSING_SOURCE") {
    return `## 来源补充

- 待补充资料来源：
- 关键证据摘录：
- 引用定位：

## 页面修正说明

为《${pageTitle}》补齐来源依据，并让关键结论可以继续回溯到资料证据。`;
  }
  if (issueType === "ORPHAN_PAGE") {
    return `## 页面关系补充

- 建议补充的上游页面：[[ ]]
- 建议补充的下游页面：[[ ]]
- 本页在工作台中的定位：

## 页面修正说明

把《${pageTitle}》重新接回当前 Wiki 网络，避免它继续孤立在页面关系之外。`;
  }
  return `## 页面修正

请根据当前维护提醒补充《${pageTitle}》的正文、来源或页面关系。`;
}

export function buildAvailableWikiKinds(pages: WikiPage[]) {
  return Array.from(new Set(pages.map((page) => page.page_kind || "TOPIC")))
    .sort((left, right) => {
      const rankDiff = getWikiKindRank(left) - getWikiKindRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    });
}

export function buildWikiIssueTypes(issues: WikiIssue[]) {
  return Array.from(new Set(issues.map((issue) => issue.issue_type))).sort();
}

export function buildWikiIssueSeverities(issues: WikiIssue[]) {
  return Array.from(new Set(issues.map((issue) => issue.severity)))
    .sort((left, right) => {
      const rankDiff = getSeverityRank(left) - getSeverityRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    });
}

export function buildGroupedWikiPages(pages: WikiPage[]) {
  return Object.fromEntries(
    Object.entries(pages.reduce<Record<string, WikiPage[]>>((groups, page) => {
      const kind = page.page_kind || "TOPIC";
      groups[kind] = groups[kind] ?? [];
      groups[kind].push(page);
      return groups;
    }, {})).sort(([left], [right]) => {
      const rankDiff = getWikiKindRank(left) - getWikiKindRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    })
  );
}

export function buildGraphSearchHits(pages: WikiPage[], keywordRaw: string, limit = 6) {
  const keyword = keywordRaw.trim().toLowerCase();
  if (!keyword) {
    return [];
  }
  return pages
    .filter((page) => page.title.toLowerCase().includes(keyword)
      || page.summary.toLowerCase().includes(keyword)
      || (page.page_kind || "TOPIC").toLowerCase().includes(keyword))
    .slice(0, limit);
}

/** 页面类型直接展示后端原值（TOPIC / CONCEPT / SOURCE_SUMMARY…），只做空值兜底。 */
export function formatWikiKind(kind: string | null | undefined) {
  return (kind || "TOPIC").toUpperCase();
}

/** 页面类型对应的配色分组，用于图谱节点与类型标签。 */
export function wikiKindTone(kind: string | null | undefined) {
  switch ((kind || "TOPIC").toUpperCase()) {
    case "TOPIC":
    case "OVERVIEW":
      return "blue";
    case "CONCEPT":
      return "violet";
    case "SOURCE_SUMMARY":
      return "green";
    case "COMPARISON":
    case "ENTITY":
      return "gold";
    default:
      return "rose";
  }
}
