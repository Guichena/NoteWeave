// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WikiRelationsPanel } from "./WikiRelationsPanel";

vi.mock("./WikiGraphPanel", () => ({
  WikiGraphPanel: () => <section aria-label="知识关系图">关系图</section>
}));

afterEach(cleanup);

describe("WikiRelationsPanel", () => {
  it("places the visual graph before direct relation cards", () => {
    render(<WikiRelationsPanel {...({
      selectedWikiPage: { item_id: "center", title: "中心页面" },
      selectedWikiDetail: {
        outgoing_links: [{
          source_item_id: "center",
          target_item_id: "related",
          target_title: "关联页面",
          relation_type: "WIKI_LINK",
          relation_status: "RESOLVED",
          mention_count: 1
        }],
        backlinks: []
      },
      wikiIssues: [],
      wikiIssueTypes: [],
      wikiIssueSeverities: [],
      reviewRequiredIssues: [],
      autoFixableIssues: [],
      selectedWikiIssues: [],
      filteredWikiIssues: [],
      wikiLog: [],
      formatWikiRelationType: () => "Wiki 链接",
      openWikiPageById: vi.fn(),
      onCloseRelations: vi.fn()
    } as any)} />);

    const graph = screen.getByRole("region", { name: "知识关系图" });
    const directRelations = screen.getByRole("region", { name: "直接关系" });
    expect(graph.compareDocumentPosition(directRelations) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText("关联页面")).toBeTruthy();
  });
});
