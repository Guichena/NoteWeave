import { type ResearchRetrievalMode } from "./model";

export const DEFAULT_RESEARCH_RETRIEVAL_MODE: ResearchRetrievalMode = "WEB_ONLY";

export function researchModeRequiresSeeds(mode: ResearchRetrievalMode): boolean {
  return mode !== "WEB_ONLY";
}

export function buildResearchSourceSelection(
  mode: ResearchRetrievalMode,
  selectedSourceIds: string[]
): { seed_source_ids: string[]; source_scope_source_ids: string[] } {
  return {
    seed_source_ids: mode === "WEB_ONLY" ? [] : [...selectedSourceIds],
    source_scope_source_ids: []
  };
}
