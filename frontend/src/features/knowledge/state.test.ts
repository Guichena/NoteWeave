import { describe, expect, it } from "vitest";
import {
  createKnowledgeServerState,
  LatestKnowledgeRequestGate,
  reduceKnowledgeServerState,
  toLatestVersion
} from "./state";
import { type KnowledgeItemDetail } from "./model";

describe("Knowledge server state", () => {
  it("resets every server projection when the workspace changes", () => {
    const loaded = {
      ...createKnowledgeServerState("first"),
      wikiUrl: "/wiki/first",
      wikiEnabled: true,
      selectedWikiItemId: "item",
      wikiSearchResults: []
    };

    expect(reduceKnowledgeServerState(loaded, { type: "workspace", workspaceId: "second" }))
      .toEqual(createKnowledgeServerState("second"));
  });

  it("projects the immutable latest version from selected detail", () => {
    const detail = knowledgeDetail();

    expect(toLatestVersion(detail)).toEqual({
      version_id: "version-2",
      item_id: "item",
      version_no: 2,
      content: "正文",
      summary: "摘要",
      source_message_id: "message",
      citations: detail.citations,
      created_at: "2026-07-15T00:00:00Z"
    });
  });

  it("replaces selection and graph as one reducer transition", () => {
    const detail = knowledgeDetail();
    const graph = {
      nodes: [],
      edges: [],
      meta: {
        mode: "ego" as const,
        center_item_id: "item",
        depth: 1,
        total_nodes: 0,
        returned_nodes: 0,
        truncated: false
      }
    };
    const next = reduceKnowledgeServerState(createKnowledgeServerState("workspace"), {
      type: "selection",
      snapshot: { itemId: "item", detail, versions: [], graph }
    });

    expect(next.selectedWikiItemId).toBe("item");
    expect(next.selectedWikiDetail).toBe(detail);
    expect(next.selectedWikiVersionDetail?.version_id).toBe("version-2");
    expect(next.wikiGraph).toBe(graph);
    expect(next.revision).toBe(1);
  });

  it("resets local selection without discarding the loaded workspace graph", () => {
    const detail = knowledgeDetail();
    const graph = {
      nodes: [],
      edges: [],
      meta: {
        mode: "ego" as const,
        center_item_id: "item",
        depth: 1,
        total_nodes: 0,
        returned_nodes: 0,
        truncated: false
      }
    };
    const selected = reduceKnowledgeServerState(createKnowledgeServerState("workspace"), {
      type: "selection",
      snapshot: { itemId: "item", detail, versions: [], graph }
    });

    const reset = reduceKnowledgeServerState(selected, { type: "reset-selection" });

    expect(reset.selectedWikiItemId).toBe("");
    expect(reset.selectedWikiDetail).toBeNull();
    expect(reset.selectedWikiVersionDetail).toBeNull();
    expect(reset.wikiGraph).toBe(graph);
    expect(reset.revision).toBe(2);
  });
});

describe("LatestKnowledgeRequestGate", () => {
  it("aborts the previous request on the same channel", () => {
    const gate = new LatestKnowledgeRequestGate();
    gate.setWorkspace("workspace");
    const first = gate.begin("search");
    const second = gate.begin("search");

    expect(first.signal.aborted).toBe(true);
    expect(first.isCurrent()).toBe(false);
    expect(second.isCurrent()).toBe(true);
  });

  it("invalidates every in-flight request when workspace changes", () => {
    const gate = new LatestKnowledgeRequestGate();
    gate.setWorkspace("first");
    const search = gate.begin("search");
    const snapshot = gate.begin("snapshot");

    gate.setWorkspace("second");

    expect(search.signal.aborted).toBe(true);
    expect(snapshot.signal.aborted).toBe(true);
    expect(search.isCurrent()).toBe(false);
    expect(snapshot.isCurrent()).toBe(false);
  });

  it("synchronizes a newly rendered workspace before its first request", () => {
    const gate = new LatestKnowledgeRequestGate();
    gate.setWorkspace("previous");
    const previous = gate.begin("graph");

    const home = gate.begin("home", "current");
    gate.setWorkspace("current");

    expect(previous.signal.aborted).toBe(true);
    expect(home.signal.aborted).toBe(false);
    expect(home.isCurrent()).toBe(true);
  });
});

function knowledgeDetail(): KnowledgeItemDetail {
  return {
    item_id: "item",
    item_type: "WIKI",
    page_kind: "TOPIC",
    title: "标题",
    latest_version_id: "version-2",
    latest_version_no: 2,
    summary: "摘要",
    updated_at: "2026-07-15T00:00:00Z",
    outgoing_count: 0,
    backlink_count: 0,
    citation_count: 1,
    unresolved_count: 0,
    content: "正文",
    source_message_id: "message",
    citations: [{
      citation_id: "citation",
      source_id: "source",
      title: "来源",
      quote_text: "证据",
      page_no: 1,
      location_info: "p1",
      generated_by: "QA",
      generated_ref_id: "run"
    }],
    outgoing_links: [],
    backlinks: [],
    version_created_at: "2026-07-15T00:00:00Z"
  };
}
