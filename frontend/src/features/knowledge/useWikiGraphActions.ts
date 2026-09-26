import { useCallback } from "react";
import type { Workspace } from "../workspace/model";
import { type WikiGraphMode, type WikiGraphOptions } from "./model";
import { getWikiKindRank } from "./wikiUtils";

type UseWikiGraphActionsInput = {
  workspace: Workspace | null;
  wikiGraphMode: WikiGraphMode;
  wikiGraphKindFilters: string[];
  selectedWikiItemId: string;
  setWikiGraphMode: (mode: WikiGraphMode) => void;
  setWikiGraphKindFilters: (filters: string[]) => void;
  refreshWikiGraph: (options: WikiGraphOptions) => Promise<void>;
};

export function useWikiGraphActions({
  workspace,
  wikiGraphMode,
  wikiGraphKindFilters,
  selectedWikiItemId,
  setWikiGraphMode,
  setWikiGraphKindFilters,
  refreshWikiGraph
}: UseWikiGraphActionsInput) {
  const switchWikiGraphMode = useCallback(async (nextMode: WikiGraphMode) => {
    if (!workspace) {
      return;
    }
    setWikiGraphMode(nextMode);
    await refreshWikiGraph({
      mode: nextMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: wikiGraphKindFilters
    });
  }, [workspace, setWikiGraphMode, refreshWikiGraph, selectedWikiItemId, wikiGraphKindFilters]);

  const toggleWikiGraphKind = useCallback(async (kind: string) => {
    if (!workspace) {
      return;
    }
    const nextKinds = wikiGraphKindFilters.includes(kind)
      ? wikiGraphKindFilters.filter((entry) => entry !== kind)
      : [...wikiGraphKindFilters, kind].sort((left, right) => getWikiKindRank(left) - getWikiKindRank(right));
    setWikiGraphKindFilters(nextKinds);
    await refreshWikiGraph({
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: nextKinds
    });
  }, [workspace, wikiGraphKindFilters, setWikiGraphKindFilters, refreshWikiGraph, wikiGraphMode, selectedWikiItemId]);

  const resetWikiGraphKinds = useCallback(async () => {
    if (!workspace) {
      return;
    }
    setWikiGraphKindFilters([]);
    await refreshWikiGraph({
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: []
    });
  }, [workspace, setWikiGraphKindFilters, refreshWikiGraph, wikiGraphMode, selectedWikiItemId]);

  return {
    switchWikiGraphMode,
    toggleWikiGraphKind,
    resetWikiGraphKinds
  };
}
