import { useCallback, useEffect, useReducer, useRef } from "react";
import { knowledgeApi, type KnowledgeApi } from "./api";
import {
  type WikiGraphMode,
  type WikiGraphOptions,
  type WikiHome,
  type WikiIssueFilters,
  type WikiPage,
  type WikiSettings
} from "./model";
import {
  createKnowledgeServerState,
  LatestKnowledgeRequestGate,
  reduceKnowledgeServerState
} from "./state";

export type UseKnowledgeStateOptions = {
  workspaceId: string;
  search: string;
  issueType: string;
  issueSeverity: string;
  issueScope: "ALL" | "AUTO" | "MANUAL";
  issuePage: "ALL" | "CURRENT";
};

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
  }, [api, gate, homeWorkspaceId, options.search, options.workspaceId, visibleState.revision]);

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
    gate,
    homeWorkspaceId,
    options.workspaceId,
    visibleState.revision,
    visibleState.selectedWikiItemId
  ]);

  const refreshHome = useCallback(async () => {
    const workspaceId = options.workspaceId;
    if (!workspaceId) {
      throw new Error("请先创建工作台");
    }
    const lease = gate.begin("home-only", workspaceId);
    try {
      const home = await api.getHome(workspaceId, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "home", home });
      }
      return home;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const refreshWithHome = useCallback(async (
    home: WikiHome,
    preferredItemId: string,
    graphOptions: WikiGraphOptions = {}
  ) => {
    const lease = gate.begin("snapshot", home.workspace_id);
    try {
      const selected = preferredItemId
        ? home.pages.find((page) => page.item_id === preferredItemId) ?? null
        : null;
      const request = { signal: lease.signal };
      const [index, stats, issues, log, rebuildAdvice, graph, selectedDetail, selectedVersions] = await Promise.all([
        api.getIndex(home.workspace_id, request),
        api.getStats(home.workspace_id, request),
        api.getIssues(home.workspace_id, {}, request),
        api.getLog(home.workspace_id, undefined, request),
        api.getRebuildAdvice(home.workspace_id, request),
        api.getGraph(home.workspace_id, { ...graphOptions, selectedItemId: selected?.item_id }, request),
        selected ? api.getItem(selected.item_id, request) : Promise.resolve(null),
        selected ? api.getVersions(selected.item_id, request) : Promise.resolve([])
      ]);
      if (lease.isCurrent()) {
        dispatch({
          type: "home-snapshot",
          snapshot: {
            home,
            index,
            stats,
            issues,
            log,
            rebuildAdvice,
            graph,
            selectedItemId: selected?.item_id ?? "",
            selectedDetail,
            selectedVersions
          }
        });
      }
      return home;
    } finally {
      lease.complete();
    }
  }, [api, gate]);

  const refreshFromServer = useCallback(async (
    preferredItemId: string,
    graphOptions: WikiGraphOptions = {}
  ) => {
    const workspaceId = options.workspaceId;
    if (!workspaceId) {
      throw new Error("请先创建工作台");
    }
    const lease = gate.begin("home", workspaceId);
    try {
      const home = await api.getHome(workspaceId, { signal: lease.signal });
      if (!lease.isCurrent()) {
        return home;
      }
      return await refreshWithHome(home, preferredItemId, graphOptions);
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId, refreshWithHome]);

  const clearSelection = useCallback(async (graphOptions: WikiGraphOptions = {}) => {
    if (!options.workspaceId) {
      return;
    }
    const lease = gate.begin("selection", options.workspaceId);
    try {
      const graph = await api.getGraph(options.workspaceId, graphOptions, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "clear-selection", graph });
      }
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const selectPage = useCallback(async (
    page: WikiPage,
    graphOptions?: WikiGraphOptions
  ) => {
    const lease = gate.begin("selection", options.workspaceId);
    try {
      const request = { signal: lease.signal };
      const [detail, versions, graph] = await Promise.all([
        api.getItem(page.item_id, request),
        api.getVersions(page.item_id, request),
        options.workspaceId && graphOptions
          ? api.getGraph(options.workspaceId, { ...graphOptions, selectedItemId: page.item_id }, request)
          : Promise.resolve(undefined)
      ]);
      if (lease.isCurrent()) {
        dispatch({ type: "selection", snapshot: { itemId: page.item_id, detail, versions, graph } });
      }
      return detail;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const refreshGraph = useCallback(async (graphOptions: WikiGraphOptions) => {
    if (!options.workspaceId) {
      return;
    }
    const lease = gate.begin("graph", options.workspaceId);
    try {
      const graph = await api.getGraph(options.workspaceId, graphOptions, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "graph", graph });
      }
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const loadVersion = useCallback(async (itemId: string, versionNo: number) => {
    const lease = gate.begin("version", options.workspaceId);
    try {
      const version = await api.getVersion(itemId, versionNo, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "version", version });
      }
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const restoreLatestVersion = useCallback(() => {
    dispatch({ type: "restore-latest" });
  }, []);

  const findFirstIssue = useCallback(async (issueType: string) => {
    if (!options.workspaceId) {
      return null;
    }
    const lease = gate.begin("focus-issues", options.workspaceId);
    try {
      const issues = await api.getIssues(
        options.workspaceId,
        { issueType },
        { signal: lease.signal }
      );
      return lease.isCurrent() ? issues[0] ?? null : null;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const updateEnabled = useCallback(async (
    enabled: boolean,
    graphMode: WikiGraphMode,
    graphKinds: string[]
  ): Promise<WikiSettings> => {
    if (!options.workspaceId) {
      throw new Error("请先创建工作台");
    }
    const lease = gate.begin("settings-update", options.workspaceId);
    try {
      const settings = await api.updateSettings(options.workspaceId, enabled, { signal: lease.signal });
      if (!lease.isCurrent()) {
        return settings;
      }
      dispatch({ type: "enabled", enabled: settings.wiki_enabled });
      if (settings.wiki_enabled) {
        await refreshFromServer(visibleState.selectedWikiItemId, {
          mode: graphMode,
          graphKinds
        });
      } else {
        const advice = await api.getRebuildAdvice(options.workspaceId, { signal: lease.signal });
        if (lease.isCurrent()) {
          dispatch({ type: "advice", advice });
        }
      }
      return settings;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId, refreshFromServer, visibleState.selectedWikiItemId]);

  return {
    ...visibleState,
    refreshHome,
    refreshFromServer,
    clearSelection,
    selectPage,
    refreshGraph,
    loadVersion,
    restoreLatestVersion,
    findFirstIssue,
    updateEnabled
  };
}

function isAbortError(error: unknown) {
  return error instanceof DOMException
    ? error.name === "AbortError"
    : error instanceof Error && error.name === "AbortError";
}
