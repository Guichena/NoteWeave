import { describe, expect, it } from "vitest";
import { deriveResearchEvidenceMetrics } from "./derived";

describe("deriveResearchEvidenceMetrics", () => {
  it("falls back to the authoritative ledger when the legacy process summary contains zero placeholders", () => {
    const run = {
      research_process_summary: {
        search_read_timeline: {
          loop_round_count: 0,
          total_search_hit_count: 0,
          total_read_window_count: 0,
          total_evidence_card_count: 0,
          all_search_queries: [],
          final_loop_decision: "",
          final_loop_reason: "",
          terminal_disposition: "",
          handoff_required: false,
          abandon_reason: "",
          rounds: []
        },
        source_evidence_summary: {
          source_basis: "",
          primary_quality: "",
          quality_mix_label: "",
          read_strategy_mix_label: "",
          fetch_foundation_label: "",
          orchestration_foundation_label: "",
          verified_finding_count: 0,
          citation_count: 0
        }
      },
      source_scope: [{ source_id: "source-1" }],
      final_report_markdown: "Citations: [evidence:a] and [evidence:b]",
      closed_loop_state: {
        rows: [{ row_status: "VERIFIED" }],
        source_evidence: [
          { evidence_id: "evidence:a", source_id: "source-1", window_id: "window-1", search_query: "query" },
          { evidence_id: "evidence:b", source_id: "source-1", window_id: "window-1", search_query: "query" }
        ]
      }
    } as never;

    expect(deriveResearchEvidenceMetrics(run)).toMatchObject({
      searchHitCount: 1,
      readWindowCount: 1,
      evidenceCardCount: 2,
      verifiedFindingCount: 1,
      citationCount: 2,
      searchQueries: ["query"],
      sourceBasis: "WORKSPACE_SOURCE",
      primaryQuality: "VERIFIED"
    });
  });
});
