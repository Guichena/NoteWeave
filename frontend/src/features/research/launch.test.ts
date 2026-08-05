import { describe, expect, it } from "vitest";
import type { SourceAsset } from "../sources/model";
import {
  buildResearchSourceSelection,
  isResearchSourceReady,
  selectReadyResearchSources
} from "./launch";

function source(sourceId: string, status: string): SourceAsset {
  return {
    source_id: sourceId,
    title: `${sourceId}.md`,
    source_type: "TEXT",
    status,
    parse_status: status === "READY" ? "PARSED" : "FAILED",
    index_status: status === "READY" ? "INDEXED" : "FAILED",
    generated_by: "upload",
    generated_ref_id: "",
    updated_at: "2026-07-28T00:00:00Z"
  };
}

describe("Research source selection", () => {
  it("only treats backend READY sources as eligible scope entries", () => {
    const ready = source("ready", "READY");
    const failed = source("failed", "FAILED");
    const processing = source("processing", "PROCESSING");

    expect(isResearchSourceReady(ready)).toBe(true);
    expect(isResearchSourceReady(failed)).toBe(false);
    expect(selectReadyResearchSources(
      [ready, failed, processing],
      [ready.source_id, failed.source_id, processing.source_id]
    )).toEqual([ready]);
  });

  it("never sends selected sources in WEB_ONLY mode", () => {
    expect(buildResearchSourceSelection("WEB_ONLY", ["ready"])).toEqual({
      seed_source_ids: [],
      source_scope_source_ids: []
    });
  });
});
