import { useCallback, useEffect, useState } from "react";
import type { SourceAsset } from "../sources/model";
import { isResearchSourceReady } from "./launch";

type UseResearchSourceScopeInput = {
  sources: SourceAsset[];
};

export function useResearchSourceScope({ sources }: UseResearchSourceScopeInput) {
  const [selectedResearchSourceIds, setSelectedResearchSourceIds] = useState<string[]>([]);
  const [focusedResearchSourceId, setFocusedResearchSourceId] = useState("");

  useEffect(() => {
    setSelectedResearchSourceIds((current) =>
      current.filter((sourceId) => sources.some((source) =>
        source.source_id === sourceId && isResearchSourceReady(source)))
    );
  }, [sources]);

  useEffect(() => {
    if (!focusedResearchSourceId) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      const target = document.getElementById(`research-source-scope-${focusedResearchSourceId}`);
      if (target) {
        target.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [focusedResearchSourceId, sources.length]);

  const toggleResearchScope = useCallback((sourceId: string) => {
    if (!sources.some((source) => source.source_id === sourceId && isResearchSourceReady(source))) {
      return;
    }
    setSelectedResearchSourceIds((current) => (
      current.includes(sourceId)
        ? current.filter((entry) => entry !== sourceId)
        : [...current, sourceId]
    ));
  }, [sources]);

  const addResearchSourceToScope = useCallback((sourceId: string) => {
    if (!sources.some((source) => source.source_id === sourceId && isResearchSourceReady(source))) {
      return;
    }
    setSelectedResearchSourceIds((current) => (
      current.includes(sourceId) ? current : [...current, sourceId]
    ));
    setFocusedResearchSourceId(sourceId);
  }, [sources]);

  const removeResearchSourceFromScope = useCallback((sourceId: string) => {
    setSelectedResearchSourceIds((current) => current.filter((entry) => entry !== sourceId));
    setFocusedResearchSourceId(sourceId);
  }, []);

  return {
    selectedResearchSourceIds,
    setSelectedResearchSourceIds,
    focusedResearchSourceId,
    setFocusedResearchSourceId,
    toggleResearchScope,
    addResearchSourceToScope,
    removeResearchSourceFromScope
  };
}
