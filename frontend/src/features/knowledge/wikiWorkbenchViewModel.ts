import type { WikiHome, WikiIssue, WikiPage } from "./model";
import {
  buildAvailableWikiKinds,
  buildGraphSearchHits,
  buildGroupedWikiPages,
  buildWikiIssueSeverities,
  buildWikiIssueTypes
} from "./wikiUtils";

export type WikiWorkbenchViewModelInput = {
  wikiHome: WikiHome | null;
  wikiIssues: WikiIssue[];
  wikiSearchResults: WikiPage[] | null;
  selectedWikiItemId: string;
  wikiKindFilter: string;
  wikiGraphKindFilters: string[];
  wikiGraphSearch: string;
};

export function buildWikiWorkbenchViewModel({
  wikiHome,
  wikiIssues,
  wikiSearchResults,
  selectedWikiItemId,
  wikiKindFilter,
  wikiGraphKindFilters,
  wikiGraphSearch
}: WikiWorkbenchViewModelInput) {
  const pages = wikiHome?.pages ?? [];
  const selectedWikiPage = pages.find((page) => page.item_id === selectedWikiItemId) ?? null;
  const selectedWikiIssues = selectedWikiItemId
    ? wikiIssues.filter((issue) => issue.item_id === selectedWikiItemId)
    : [];
  const baseWikiPages = wikiSearchResults ?? pages;
  const visibleWikiPages = baseWikiPages.filter(
    (page) => wikiKindFilter === "ALL" || (page.page_kind || "TOPIC") === wikiKindFilter
  );
  const autoFixableIssues = wikiIssues.filter((issue) => issue.auto_fixable);
  const reviewRequiredIssues = wikiIssues.filter((issue) => !issue.auto_fixable);

  return {
    selectedWikiPage,
    selectedWikiIssues,
    availableWikiKinds: buildAvailableWikiKinds(pages),
    groupedWikiPages: buildGroupedWikiPages(visibleWikiPages),
    visibleWikiPages,
    autoFixableIssues,
    reviewRequiredIssues,
    graphFilterLabel: wikiGraphKindFilters.length === 0
      ? "全部页面类型"
      : wikiGraphKindFilters.join(" / "),
    graphSearchHits: buildGraphSearchHits(pages, wikiGraphSearch),
    wikiIssueTypes: buildWikiIssueTypes(wikiIssues),
    wikiIssueSeverities: buildWikiIssueSeverities(wikiIssues)
  };
}
