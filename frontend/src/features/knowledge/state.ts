import {
  type KnowledgeItemDetail,
  type KnowledgeVersionDetail,
  type KnowledgeVersionSummary,
  type WikiGraph,
  type WikiHome,
  type WikiIndex,
  type WikiIssue,
  type WikiLogEntry,
  type WikiPage,
  type WikiRebuildAdvice,
  type WikiStats
} from "./model";

export type KnowledgeServerState = {
  workspaceId: string;
  revision: number;
  wikiUrl: string;
  wikiHome: WikiHome | null;
  wikiIndex: WikiIndex | null;
  wikiEnabled: boolean;
  wikiStats: WikiStats | null;
  wikiIssues: WikiIssue[];
  wikiLog: WikiLogEntry[];
  wikiGraph: WikiGraph | null;
  wikiRebuildAdvice: WikiRebuildAdvice | null;
  selectedWikiItemId: string;
  selectedWikiDetail: KnowledgeItemDetail | null;
  selectedWikiVersions: KnowledgeVersionSummary[];
  selectedWikiVersionDetail: KnowledgeVersionDetail | null;
  selectedWikiLog: WikiLogEntry[];
  wikiSearchResults: WikiPage[] | null;
  filteredWikiIssues: WikiIssue[];
};

export type KnowledgeHomeSnapshot = {
  home: WikiHome;
  index: WikiIndex;
  stats: WikiStats;
  issues: WikiIssue[];
  log: WikiLogEntry[];
  graph: WikiGraph;
  rebuildAdvice: WikiRebuildAdvice;
  selectedItemId: string;
  selectedDetail: KnowledgeItemDetail | null;
  selectedVersions: KnowledgeVersionSummary[];
};

export type KnowledgeSelectionSnapshot = {
  itemId: string;
  detail: KnowledgeItemDetail;
  versions: KnowledgeVersionSummary[];
  graph?: WikiGraph;
};

export type KnowledgeStateAction =
  | { type: "workspace"; workspaceId: string }
  | { type: "enabled"; enabled: boolean }
  | { type: "home"; home: WikiHome }
  | { type: "home-snapshot"; snapshot: KnowledgeHomeSnapshot }
  | { type: "selection"; snapshot: KnowledgeSelectionSnapshot }
  | { type: "clear-selection"; graph: WikiGraph }
  | { type: "graph"; graph: WikiGraph }
  | { type: "version"; version: KnowledgeVersionDetail }
  | { type: "restore-latest" }
  | { type: "advice"; advice: WikiRebuildAdvice }
  | { type: "search"; results: WikiPage[] | null }
  | { type: "filtered-issues"; issues: WikiIssue[] }
  | { type: "selected-log"; entries: WikiLogEntry[] };

export function createKnowledgeServerState(workspaceId = ""): KnowledgeServerState {
  return {
    workspaceId,
    revision: 0,
    wikiUrl: "",
    wikiHome: null,
    wikiIndex: null,
    wikiEnabled: false,
    wikiStats: null,
    wikiIssues: [],
    wikiLog: [],
    wikiGraph: null,
    wikiRebuildAdvice: null,
    selectedWikiItemId: "",
    selectedWikiDetail: null,
    selectedWikiVersions: [],
    selectedWikiVersionDetail: null,
    selectedWikiLog: [],
    wikiSearchResults: null,
    filteredWikiIssues: []
  };
}

export function reduceKnowledgeServerState(
  state: KnowledgeServerState,
  action: KnowledgeStateAction
): KnowledgeServerState {
  switch (action.type) {
    case "workspace":
      return action.workspaceId === state.workspaceId
        ? state
        : createKnowledgeServerState(action.workspaceId);
    case "enabled":
      return { ...state, wikiEnabled: action.enabled };
    case "home":
      return {
        ...state,
        revision: state.revision + 1,
        wikiUrl: action.home.wiki_url,
        wikiHome: action.home
      };
    case "home-snapshot": {
      const { snapshot } = action;
      return {
        ...state,
        revision: state.revision + 1,
        wikiUrl: snapshot.home.wiki_url,
        wikiHome: snapshot.home,
        wikiIndex: snapshot.index,
        wikiEnabled: snapshot.index.wiki_enabled,
        wikiStats: snapshot.stats,
        wikiIssues: snapshot.issues,
        wikiLog: snapshot.log,
        wikiGraph: snapshot.graph,
        wikiRebuildAdvice: snapshot.rebuildAdvice,
        selectedWikiItemId: snapshot.selectedItemId,
        selectedWikiDetail: snapshot.selectedDetail,
        selectedWikiVersions: snapshot.selectedVersions,
        selectedWikiVersionDetail: toLatestVersion(snapshot.selectedDetail),
        selectedWikiLog: [],
        filteredWikiIssues: snapshot.issues
      };
    }
    case "selection":
      return {
        ...state,
        revision: state.revision + 1,
        selectedWikiItemId: action.snapshot.itemId,
        selectedWikiDetail: action.snapshot.detail,
        selectedWikiVersions: action.snapshot.versions,
        selectedWikiVersionDetail: toLatestVersion(action.snapshot.detail),
        selectedWikiLog: [],
        wikiGraph: action.snapshot.graph ?? state.wikiGraph
      };
    case "clear-selection":
      return {
        ...state,
        revision: state.revision + 1,
        selectedWikiItemId: "",
        selectedWikiDetail: null,
        selectedWikiVersions: [],
        selectedWikiVersionDetail: null,
        selectedWikiLog: [],
        wikiGraph: action.graph
      };
    case "graph":
      return { ...state, wikiGraph: action.graph };
    case "version":
      return { ...state, selectedWikiVersionDetail: action.version };
    case "restore-latest":
      return { ...state, selectedWikiVersionDetail: toLatestVersion(state.selectedWikiDetail) };
    case "advice":
      return { ...state, wikiRebuildAdvice: action.advice };
    case "search":
      return { ...state, wikiSearchResults: action.results };
    case "filtered-issues":
      return { ...state, filteredWikiIssues: action.issues };
    case "selected-log":
      return { ...state, selectedWikiLog: action.entries };
  }
}

export function toLatestVersion(detail: KnowledgeItemDetail | null): KnowledgeVersionDetail | null {
  return detail
    ? {
        version_id: detail.latest_version_id,
        item_id: detail.item_id,
        version_no: detail.latest_version_no,
        content: detail.content,
        summary: detail.summary,
        source_message_id: detail.source_message_id,
        citations: detail.citations,
        created_at: detail.version_created_at
      }
    : null;
}

type RequestLease = {
  signal: AbortSignal;
  isCurrent: () => boolean;
  complete: () => void;
};

export class LatestKnowledgeRequestGate {
  private workspaceId = "";
  private readonly controllers = new Map<string, AbortController>();

  setWorkspace(workspaceId: string) {
    if (workspaceId === this.workspaceId) {
      return;
    }
    this.workspaceId = workspaceId;
    this.controllers.forEach((controller) => controller.abort());
    this.controllers.clear();
  }

  begin(channel: string, workspaceId = this.workspaceId): RequestLease {
    this.controllers.get(channel)?.abort();
    const controller = new AbortController();
    this.controllers.set(channel, controller);
    return {
      signal: controller.signal,
      isCurrent: () => (
        !controller.signal.aborted
        && workspaceId === this.workspaceId
        && this.controllers.get(channel) === controller
      ),
      complete: () => {
        if (this.controllers.get(channel) === controller) {
          this.controllers.delete(channel);
        }
      }
    };
  }

  cancel(channel: string) {
    this.controllers.get(channel)?.abort();
    this.controllers.delete(channel);
  }

  cancelAll() {
    this.controllers.forEach((controller) => controller.abort());
    this.controllers.clear();
  }
}
