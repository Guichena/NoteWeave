import { describe, expect, it } from "vitest";
import {
  buildResearchRounds,
  buildTraceCards,
  buildTraceDetail,
  buildTraceGroups,
  evidenceItems
} from "./researchDetailViewModel";

describe("researchDetailViewModel", () => {
  it("normalizes rounds and trace groups without losing source scope", () => {
    const rounds = buildResearchRounds({
      rounds: [{
        round_no: 2,
        search_hit_count: 3,
        read_window_count: 2,
        evidence_card_count: 1,
        search_queries: ["query"],
        evidence_ids: ["e-1"],
        branch_decision: "继续",
        global_decision: "收敛"
      }]
    } as any, [{ round_no: 2, branch_id: "branch-2", reason: "证据足够" }]);
    const cards = buildTraceCards([{
      trace_id: "trace-1",
      trace_type: "SEARCH_RESULT",
      trace_message: "search",
      created_at: "2026-08-07T00:00:00Z",
      payload: {
        source_id: "source-1",
        source_title: "来源一",
        source_url: "https://example.com",
        round_no: 2,
        search_queries: ["query"],
        provider: "web"
      }
    }], "trace-1", new Set(["source-1"]));
    const groups = buildTraceGroups(cards, 2);
    const detail = buildTraceDetail(cards[0]);

    expect(rounds[0]).toMatchObject({ roundNo: 2, branchId: "branch-2", reason: "证据足够" });
    expect(cards[0]).toMatchObject({ kind: "search", selected: true, primarySourceInScope: true, roundNo: 2 });
    expect(groups.find((group) => group.tone === "search")?.entries).toHaveLength(1);
    expect(detail).toMatchObject({ title: "搜索", provider: "web", querySamples: ["query"] });
  });

  it("keeps evidence item fallbacks stable for untyped payloads", () => {
    expect(evidenceItems([{ id: "row-1", claim: "结论", status: "VERIFIED" }], "evidence")).toEqual([
      { key: "row-1", title: "记录 1", claim: "结论", meta: "VERIFIED" }
    ]);
  });
});
