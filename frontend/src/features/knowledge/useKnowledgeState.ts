import { useEffect, useReducer, useRef } from "react";
import { knowledgeApi, type KnowledgeApi } from "./api";
import { useKnowledgeActions } from "./useKnowledgeActions";
import { type KnowledgeQueryOptions, useKnowledgeQueries } from "./useKnowledgeQueries";
import {
  createKnowledgeServerState,
  LatestKnowledgeRequestGate,
  reduceKnowledgeServerState
} from "./state";

export type UseKnowledgeStateOptions = KnowledgeQueryOptions;

export function useKnowledgeState(
  options: UseKnowledgeStateOptions,
  api: KnowledgeApi = knowledgeApi
) {
  const [state, dispatch] = useReducer(
    reduceKnowledgeServerState,
    options.workspaceId,
    createKnowledgeServerState
  );
  const gateRef = useRef(new LatestKnowledgeRequestGate());
  const gate = gateRef.current;

  useEffect(() => {
    gate.setWorkspace(options.workspaceId);
    dispatch({ type: "workspace", workspaceId: options.workspaceId });
    if (!options.workspaceId) {
      return;
    }
    const lease = gate.begin("settings", options.workspaceId);
    void api.getSettings(options.workspaceId, { signal: lease.signal })
      .then((settings) => {
        if (lease.isCurrent()) {
          dispatch({ type: "enabled", enabled: settings.wiki_enabled });
        }
      })
      .catch(() => undefined)
      .finally(lease.complete);
  }, [api, gate, options.workspaceId]);

  useEffect(() => () => gate.cancelAll(), [gate]);

  const visibleState = state.workspaceId === options.workspaceId
    ? state
    : createKnowledgeServerState(options.workspaceId);

  useKnowledgeQueries({
    options,
    api,
    gate,
    dispatch,
    visibleState
  });

  const actions = useKnowledgeActions({
    workspaceId: options.workspaceId,
    api,
    gate,
    dispatch,
    selectedItemId: visibleState.selectedWikiItemId
  });

  return {
    ...visibleState,
    ...actions
  };
}
