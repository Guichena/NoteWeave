import { describe, expect, it } from "vitest";
import {
  buildResearchSourceSelection,
  DEFAULT_RESEARCH_RETRIEVAL_MODE,
  researchModeRequiresSeeds
} from "./features/research/launch";

describe("Research launch contract", () => {
  it("defaults to Web-only without requiring a Workspace source", () => {
    expect(DEFAULT_RESEARCH_RETRIEVAL_MODE).toBe("WEB_ONLY");
    expect(researchModeRequiresSeeds(DEFAULT_RESEARCH_RETRIEVAL_MODE)).toBe(false);
    expect(buildResearchSourceSelection("WEB_ONLY", [])).toEqual({
      seed_source_ids: [],
      source_scope_source_ids: []
    });
  });

  it("sends retrieval mode and treats selected Workspace sources only as explicit seeds", () => {
    expect(researchModeRequiresSeeds("WEB_PLUS_SEEDS")).toBe(true);
    expect(researchModeRequiresSeeds("SOURCES_ONLY")).toBe(true);
    expect(buildResearchSourceSelection("WEB_PLUS_SEEDS", ["source-1"])).toEqual({
      seed_source_ids: ["source-1"],
      source_scope_source_ids: []
    });
  });
});
