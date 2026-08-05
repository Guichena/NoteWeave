import type { SourceAsset } from "../sources/model";
import { type ResearchRetrievalMode } from "./model";

export const DEFAULT_RESEARCH_RETRIEVAL_MODE: ResearchRetrievalMode = "WEB_ONLY";

export function researchModeRequiresSeeds(mode: ResearchRetrievalMode): boolean {
  return mode !== "WEB_ONLY";
}

export function isResearchSourceReady(source: Pick<SourceAsset, "status">): boolean {
  return source.status.trim().toUpperCase() === "READY";
}

export function selectReadyResearchSources(
  sources: SourceAsset[],
  selectedSourceIds: string[]
): SourceAsset[] {
  const selected = new Set(selectedSourceIds);
  return sources.filter((source) => selected.has(source.source_id) && isResearchSourceReady(source));
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
