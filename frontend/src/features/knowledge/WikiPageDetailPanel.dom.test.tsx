// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WikiPageDetailPanel } from "./WikiPageDetailPanel";

afterEach(cleanup);

function buildProps(pageOverrides: Record<string, unknown> = {}) {
  return {
    isBusy: false,
    workspace: { workspace_id: "workspace-1", name: "工作台", status: "ACTIVE" },
    selection: {
      selectedWikiPage: {
        item_id: "page-1",
        title: "缓存一致性",
        page_kind: "SOURCE",
        latest_version_no: 2,
        summary: "摘要",
        updated_at: "2026-10-26T00:00:00Z",
        outgoing_count: 0,
        backlink_count: 0,
        citation_count: 0,
        unresolved_count: 0,
        ...pageOverrides
      },
      selectedWikiDetail: {
        item_id: "page-1",
        latest_version_no: 2,
        content: "正文内容",
        updated_at: "2026-10-26T00:00:00Z",
        citations: [],
        source_availability: [],
        outgoing_links: []
      },
      selectedWikiVersions: [],
      selectedWikiVersionDetail: null,
      selectedWikiIssues: [],
      selectedWikiLog: []
    },
    draft: {
      wikiAppendDraft: "",
      setWikiAppendDraft: vi.fn(),
      wikiRenameTitle: "",
      setWikiRenameTitle: vi.fn()
    },
    actions: {
      loadWikiVersion: vi.fn(),
      restoreLatestWikiVersion: vi.fn(),
      openWikiPageById: vi.fn(),
      autoFixWiki: vi.fn(),
      renameSelectedWikiPage: vi.fn(),
      deleteSelectedWikiPage: vi.fn(),
      appendWikiVersion: vi.fn()
    },
    helpers: {
      formatDateTime: () => "刚刚",
      buildKnowledgeCitationLabel: () => "",
      getWikiIssuePrimaryAction: () => null
    },
    pages: []
  } as any;
}

describe("WikiPageDetailPanel", () => {
  it("shows the pending-review notice only when the automatic update was skipped", () => {
    const { rerender } = render(
      <WikiPageDetailPanel {...buildProps({ wiki_page_state: "NEEDS_REVIEW" })} />
    );
    expect(screen.getByRole("status").textContent).toContain("来源已更新，待确认");

    rerender(<WikiPageDetailPanel {...buildProps({ wiki_page_state: "AUTO" })} />);
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("keeps the manual page free of the notice", () => {
    render(<WikiPageDetailPanel {...buildProps({ wiki_page_state: "MANUAL" })} />);

    expect(screen.queryByRole("status")).toBeNull();
  });
});
