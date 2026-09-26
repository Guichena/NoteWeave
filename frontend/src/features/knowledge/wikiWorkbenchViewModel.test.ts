import { describe, expect, it } from "vitest";
import type { WikiIssue, WikiPage } from "./model";
import { buildWikiWorkbenchViewModel } from "./wikiWorkbenchViewModel";

function page(itemId: string, title: string, pageKind: string): WikiPage {
  return {
    item_id: itemId,
    item_type: "KNOWLEDGE_ITEM",
    page_kind: pageKind,
    title,
    latest_version_id: "version-1",
    latest_version_no: 1,
    summary: "",
    updated_at: "2026-08-08T00:00:00Z",
    outgoing_count: 0,
    backlink_count: 0,
    citation_count: 0,
    unresolved_count: 0
  };
}

function issue(itemId: string, issueType: string, severity: string, autoFixable: boolean): WikiIssue {
  return {
    item_id: itemId,
    issue_type: issueType,
    severity,
    title: issueType,
    message: "",
    suggested_action: "",
    auto_fixable: autoFixable,
    action_code: ""
  };
}

describe("buildWikiWorkbenchViewModel", () => {
  it("uses search results as the page source while retaining selected issue context", () => {
    const model = buildWikiWorkbenchViewModel({
      wikiHome: {
        workspace_id: "workspace-1",
        wiki_url: "/wiki",
        pages: [page("page-1", "Alpha", "TOPIC"), page("page-2", "Beta", "PERSON")],
        links: []
      },
      wikiIssues: [issue("page-1", "BROKEN_LINK", "HIGH", false)],
      wikiSearchResults: [page("page-2", "Beta", "PERSON")],
      selectedWikiItemId: "page-1",
      wikiKindFilter: "ALL",
      wikiGraphKindFilters: [],
      wikiGraphSearch: ""
    });

    expect(model.visibleWikiPages.map((page) => page.item_id)).toEqual(["page-2"]);
    expect(model.selectedWikiPage?.item_id).toBe("page-1");
    expect(model.selectedWikiIssues).toHaveLength(1);
    expect(model.graphFilterLabel).toBe("全部页面类型");
  });

  it("applies page-kind filtering and separates repair queues", () => {
    const model = buildWikiWorkbenchViewModel({
      wikiHome: {
        workspace_id: "workspace-1",
        wiki_url: "/wiki",
        pages: [page("page-1", "Alpha", "TOPIC"), page("page-2", "Beta", "PERSON")],
        links: []
      },
      wikiIssues: [
        issue("page-1", "BROKEN_LINK", "HIGH", true),
        issue("page-2", "ORPHAN_PAGE", "LOW", false)
      ],
      wikiSearchResults: null,
      selectedWikiItemId: "",
      wikiKindFilter: "PERSON",
      wikiGraphKindFilters: ["PERSON"],
      wikiGraphSearch: "be"
    });

    expect(model.visibleWikiPages.map((page) => page.item_id)).toEqual(["page-2"]);
    expect(model.autoFixableIssues).toHaveLength(1);
    expect(model.reviewRequiredIssues).toHaveLength(1);
    expect(model.graphFilterLabel).toBe("PERSON");
  });
});
