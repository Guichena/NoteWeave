import { useCallback, type Dispatch } from "react";
import { knowledgeApi, type KnowledgeApi } from "./api";
import {
  type WikiGraphMode,
  type WikiGraphOptions,
  type WikiHome,
  type WikiPage,
  type WikiSettings
} from "./model";
import {
  type KnowledgeStateAction,
  LatestKnowledgeRequestGate
} from "./state";

type UseKnowledgeActionsInput = {
  workspaceId: string;
  api: KnowledgeApi;
  gate: LatestKnowledgeRequestGate;
  dispatch: Dispatch<KnowledgeStateAction>;
  selectedItemId: string;
};

export function useKnowledgeActions({
  workspaceId,
  api = knowledgeApi,
  gate,
  dispatch,
  selectedItemId
}: UseKnowledgeActionsInput) {
  const refreshHome = useCallback(async () => {
    if (!workspaceId) {
      throw new Error("Please create a workspace first");
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
  }, [api, dispatch, gate, workspaceId]);

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
  }, [api, dispatch, gate]);

  const refreshFromServer = useCallback(async (
    preferredItemId: string,
    graphOptions: WikiGraphOptions = {}
  ) => {
    if (!workspaceId) {
      throw new Error("Please create a workspace first");
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
  }, [api, gate, refreshWithHome, workspaceId]);

  const clearSelection = useCallback(async (graphOptions: WikiGraphOptions = {}) => {
    if (!workspaceId) {
      return;
    }
    const lease = gate.begin("selection", workspaceId);
    try {
      const graph = await api.getGraph(workspaceId, graphOptions, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "clear-selection", graph });
      }
    } finally {
      lease.complete();
    }
  }, [api, dispatch, gate, workspaceId]);

  const resetSelection = useCallback(() => {
    gate.cancel("selection");
    gate.cancel("selected-log");
    dispatch({ type: "reset-selection" });
  }, [dispatch, gate]);

  const selectPage = useCallback(async (
    page: WikiPage,
    graphOptions?: WikiGraphOptions
  ) => {
    const lease = gate.begin("selection", workspaceId);
    try {
      const request = { signal: lease.signal };
      const [detail, versions, graph] = await Promise.all([
        api.getItem(page.item_id, request),
        api.getVersions(page.item_id, request),
        workspaceId && graphOptions
          ? api.getGraph(workspaceId, { ...graphOptions, selectedItemId: page.item_id }, request)
          : Promise.resolve(undefined)
      ]);
      if (lease.isCurrent()) {
        dispatch({ type: "selection", snapshot: { itemId: page.item_id, detail, versions, graph } });
      }
      return detail;
    } finally {
      lease.complete();
    }
  }, [api, dispatch, gate, workspaceId]);

  const refreshGraph = useCallback(async (graphOptions: WikiGraphOptions) => {
    if (!workspaceId) {
      return;
    }
    const lease = gate.begin("graph", workspaceId);
    try {
      const graph = await api.getGraph(workspaceId, graphOptions, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "graph", graph });
      }
    } finally {
      lease.complete();
    }
  }, [api, dispatch, gate, workspaceId]);

  const loadVersion = useCallback(async (itemId: string, versionNo: number) => {
    const lease = gate.begin("version", workspaceId);
    try {
      const version = await api.getVersion(itemId, versionNo, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "version", version });
      }
    } finally {
      lease.complete();
    }
  }, [api, dispatch, gate, workspaceId]);

  /** 按版本 ID 加载指定版本：证据绑定的是版本身份，不按标题或最新版代替。 */
  const loadVersionById = useCallback(async (itemId: string, versionId: string) => {
    gate.cancel("version");
    const lease = gate.begin("selection", workspaceId);
    try {
      const request = { signal: lease.signal };
      const versions = await api.getVersions(itemId, request);
      const target = versions.find((entry) => entry.version_id === versionId);
      if (!target || !lease.isCurrent()) return null;
      const [version, detail] = await Promise.all([
        api.getVersion(itemId, target.version_no, request), api.getItem(itemId, request)
      ]);
      if (!lease.isCurrent()) return null;
      if (version.item_id !== itemId || version.version_id !== versionId || detail.item_id !== itemId) {
        throw new Error("引用版本身份不一致");
      }
      // 目标与版本列表、页面元信息一起提交，不先展示最新正文。
      dispatch({ type: "selection", snapshot: { itemId, detail, versions, version } });
      return version;
    } finally {
      lease.complete();
    }
  }, [api, dispatch, gate, workspaceId]);

  const restoreLatestVersion = useCallback(() => {
    dispatch({ type: "restore-latest" });
  }, [dispatch]);

  const findFirstIssue = useCallback(async (issueType: string) => {
    if (!workspaceId) {
      return null;
    }
    const lease = gate.begin("focus-issues", workspaceId);
    try {
      const issues = await api.getIssues(workspaceId, { issueType }, { signal: lease.signal });
      return lease.isCurrent() ? issues[0] ?? null : null;
    } finally {
      lease.complete();
    }
  }, [api, gate, workspaceId]);

  const updateEnabled = useCallback(async (
    enabled: boolean,
    graphMode: WikiGraphMode,
    graphKinds: string[]
  ): Promise<WikiSettings> => {
    if (!workspaceId) {
      throw new Error("Please create a workspace first");
    }
    const lease = gate.begin("settings-update", workspaceId);
    try {
      const settings = await api.updateSettings(workspaceId, enabled, { signal: lease.signal });
      if (!lease.isCurrent()) {
        return settings;
      }
      dispatch({ type: "enabled", enabled: settings.wiki_enabled });
      if (settings.wiki_enabled) {
        await refreshFromServer(selectedItemId, { mode: graphMode, graphKinds });
      } else {
        const advice = await api.getRebuildAdvice(workspaceId, { signal: lease.signal });
        if (lease.isCurrent()) {
          dispatch({ type: "advice", advice });
        }
      }
      return settings;
    } finally {
      lease.complete();
    }
  }, [api, dispatch, gate, refreshFromServer, selectedItemId, workspaceId]);

  return {
    refreshHome,
    refreshFromServer,
    resetSelection,
    clearSelection,
    selectPage,
    refreshGraph,
    loadVersion,
    loadVersionById,
    restoreLatestVersion,
    findFirstIssue,
    updateEnabled
  };
}

export type KnowledgeActions = ReturnType<typeof useKnowledgeActions>;
