import { useEffect, type Dispatch } from "react";
import { knowledgeApi, type KnowledgeApi } from "./api";
import {
  type WikiIssueFilters
} from "./model";
import {
  type KnowledgeServerState,
  type KnowledgeStateAction,
  LatestKnowledgeRequestGate
} from "./state";

export type KnowledgeQueryOptions = {
  workspaceId: string;
  search: string;
  issueType: string;
  issueSeverity: string;
  issueScope: "ALL" | "AUTO" | "MANUAL";
  issuePage: "ALL" | "CURRENT";
};

type UseKnowledgeQueriesInput = {
  options: KnowledgeQueryOptions;
  api: KnowledgeApi;
  gate: LatestKnowledgeRequestGate;
  dispatch: Dispatch<KnowledgeStateAction>;
  visibleState: KnowledgeServerState;
};

export function useKnowledgeQueries({
  options,
  api = knowledgeApi,
  gate,
  dispatch,
  visibleState
}: UseKnowledgeQueriesInput) {
  const homeWorkspaceId = visibleState.wikiHome?.workspace_id ?? "";

  useEffect(() => {
    if (!options.workspaceId || !homeWorkspaceId) {
      dispatch({ type: "search", results: null });
      return;
    }
    const keyword = options.search.trim();
    if (!keyword) {
      gate.cancel("search");
      dispatch({ type: "search", results: null });
      return;
    }
    const timeoutId = window.setTimeout(() => {
      const lease = gate.begin("search", options.workspaceId);
      void api.search(options.workspaceId, keyword, { signal: lease.signal })
        .then((results) => {
          if (lease.isCurrent()) {
            dispatch({ type: "search", results });
          }
        })
        .catch((error) => {
          if (lease.isCurrent() && !isAbortError(error)) {
            dispatch({ type: "search", results: [] });
          }
        })
        .finally(lease.complete);
    }, 200);
    return () => window.clearTimeout(timeoutId);
  }, [api, dispatch, gate, homeWorkspaceId, options.search, options.workspaceId, visibleState.revision]);

  useEffect(() => {
    if (!options.workspaceId || !homeWorkspaceId) {
      dispatch({ type: "filtered-issues", issues: [] });
      return;
    }
    const timeoutId = window.setTimeout(() => {
      const lease = gate.begin("issues", options.workspaceId);
      const filters: WikiIssueFilters = {
        issueType: options.issueType,
        severity: options.issueSeverity,
        scope: options.issueScope,
        page: options.issuePage,
        selectedItemId: visibleState.selectedWikiItemId
      };
      void api.getIssues(options.workspaceId, filters, { signal: lease.signal })
        .then((issues) => {
          if (lease.isCurrent()) {
            dispatch({ type: "filtered-issues", issues });
          }
        })
        .catch((error) => {
          if (lease.isCurrent() && !isAbortError(error)) {
            dispatch({ type: "filtered-issues", issues: [] });
          }
        })
        .finally(lease.complete);
    }, 120);
    return () => window.clearTimeout(timeoutId);
  }, [
    api,
    dispatch,
    gate,
    homeWorkspaceId,
    options.issuePage,
    options.issueScope,
    options.issueSeverity,
    options.issueType,
    options.workspaceId,
    visibleState.revision,
    visibleState.selectedWikiItemId
  ]);

  useEffect(() => {
    if (!options.workspaceId || !homeWorkspaceId || !visibleState.selectedWikiItemId) {
      dispatch({ type: "selected-log", entries: [] });
      return;
    }
    const timeoutId = window.setTimeout(() => {
      const lease = gate.begin("selected-log", options.workspaceId);
      void api.getLog(options.workspaceId, visibleState.selectedWikiItemId, { signal: lease.signal })
        .then((entries) => {
          if (lease.isCurrent()) {
            dispatch({ type: "selected-log", entries });
          }
        })
        .catch((error) => {
          if (lease.isCurrent() && !isAbortError(error)) {
            dispatch({ type: "selected-log", entries: [] });
          }
        })
        .finally(lease.complete);
    }, 120);
    return () => window.clearTimeout(timeoutId);
  }, [
    api,
    dispatch,
    gate,
    homeWorkspaceId,
    options.workspaceId,
    visibleState.revision,
    visibleState.selectedWikiItemId
  ]);
}

function isAbortError(error: unknown) {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}
