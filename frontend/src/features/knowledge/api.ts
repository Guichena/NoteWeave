import { apiClient, type ApiClient } from "../../shared/api";
import {
  type AppendKnowledgeVersionInput,
  type CreateKnowledgeItemInput,
  type KnowledgeItemDetail,
  type KnowledgeVersionDetail,
  type KnowledgeVersionSummary,
  type WikiAutoFixResult,
  type WikiGraph,
  type WikiGraphOptions,
  type WikiHome,
  type WikiIndex,
  type WikiIssue,
  type WikiIssueFilters,
  type WikiLogEntry,
  type WikiPage,
  type WikiRebuild,
  type WikiRebuildAdvice,
  type WikiSettings,
  type WikiStats
} from "./model";

export class KnowledgeApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  getSettings(workspaceId: string, init?: RequestInit) {
    return this.get<WikiSettings>(`/api/v2/workspaces/${workspaceId}/wiki-settings`, init);
  }

  updateSettings(workspaceId: string, wikiEnabled: boolean, init?: RequestInit) {
    const path = `/api/v2/workspaces/${workspaceId}/wiki-settings`;
    const body = { wiki_enabled: wikiEnabled };
    return init
      ? this.client.put<WikiSettings>(path, body, init)
      : this.client.put<WikiSettings>(path, body);
  }

  getHome(workspaceId: string, init?: RequestInit) {
    return this.get<WikiHome>(`/api/v2/workspaces/${workspaceId}/wiki-home`, init);
  }

  getIndex(workspaceId: string, init?: RequestInit) {
    return this.get<WikiIndex>(`/api/v2/workspaces/${workspaceId}/wiki-index`, init);
  }

  getStats(workspaceId: string, init?: RequestInit) {
    return this.get<WikiStats>(`/api/v2/workspaces/${workspaceId}/wiki-stats`, init);
  }

  search(workspaceId: string, keyword: string, init?: RequestInit) {
    const query = new URLSearchParams({ q: keyword });
    return this.get<WikiPage[]>(`/api/v2/workspaces/${workspaceId}/wiki-search?${query}`, init);
  }

  getIssues(workspaceId: string, filters: WikiIssueFilters = {}, init?: RequestInit) {
    const query = buildWikiIssueQuery(filters);
    const suffix = query.size > 0 ? `?${query}` : "";
    return this.get<WikiIssue[]>(`/api/v2/workspaces/${workspaceId}/wiki-issues${suffix}`, init);
  }

  getLog(workspaceId: string, itemId?: string, init?: RequestInit) {
    const query = new URLSearchParams();
    if (itemId) {
      query.set("item_id", itemId);
    }
    const suffix = query.size > 0 ? `?${query}` : "";
    return this.get<WikiLogEntry[]>(`/api/v2/workspaces/${workspaceId}/wiki-log${suffix}`, init);
  }

  getRebuildAdvice(workspaceId: string, init?: RequestInit) {
    return this.get<WikiRebuildAdvice>(`/api/v2/workspaces/${workspaceId}/wiki/rebuild-advice`, init);
  }

  getGraph(workspaceId: string, options: WikiGraphOptions = {}, init?: RequestInit) {
    const query = buildWikiGraphQuery(options);
    return this.get<WikiGraph>(`/api/v2/workspaces/${workspaceId}/wiki-graph?${query}`, init);
  }

  getItem(itemId: string, init?: RequestInit) {
    return this.get<KnowledgeItemDetail>(`/api/v2/knowledge-items/${itemId}`, init);
  }

  createItem(workspaceId: string, input: CreateKnowledgeItemInput) {
    return this.client.post<WikiPage>(`/api/v2/workspaces/${workspaceId}/knowledge-items`, input);
  }

  renameItem(itemId: string, title: string) {
    return this.client.patch<WikiPage>(`/api/v2/knowledge-items/${itemId}/title`, { title });
  }

  deleteItem(itemId: string) {
    return this.client.delete(`/api/v2/knowledge-items/${itemId}`);
  }

  getVersions(itemId: string, init?: RequestInit) {
    return this.get<KnowledgeVersionSummary[]>(`/api/v2/knowledge-items/${itemId}/versions`, init);
  }

  getVersion(itemId: string, versionNo: number, init?: RequestInit) {
    return this.get<KnowledgeVersionDetail>(`/api/v2/knowledge-items/${itemId}/versions/${versionNo}`, init);
  }

  appendVersion(itemId: string, input: AppendKnowledgeVersionInput) {
    return this.client.post<WikiPage>(`/api/v2/knowledge-items/${itemId}/versions`, input);
  }

  saveMessageAsNote(messageId: string, title: string) {
    return this.client.post<WikiPage>(`/api/v2/messages/${messageId}/save-as-note`, { title });
  }

  rebuildLinks(workspaceId: string) {
    return this.client.post<WikiStats>(`/api/v2/workspaces/${workspaceId}/wiki/rebuild-links`, {});
  }

  rebuild(workspaceId: string) {
    return this.client.post<WikiRebuild>(`/api/v2/workspaces/${workspaceId}/wiki/rebuild`, {});
  }

  autoFix(workspaceId: string) {
    return this.client.post<WikiAutoFixResult>(`/api/v2/workspaces/${workspaceId}/wiki/auto-fix`, {});
  }

  private get<T>(path: string, init?: RequestInit) {
    return init ? this.client.get<T>(path, init) : this.client.get<T>(path);
  }
}

export function buildWikiIssueQuery(filters: WikiIssueFilters) {
  const query = new URLSearchParams();
  if (filters.issueType && filters.issueType !== "ALL") {
    query.set("issue_type", filters.issueType);
  }
  if (filters.severity && filters.severity !== "ALL") {
    query.set("severity", filters.severity);
  }
  if (filters.scope === "AUTO") {
    query.set("auto_fixable", "true");
  }
  if (filters.scope === "MANUAL") {
    query.set("auto_fixable", "false");
  }
  if (filters.page === "CURRENT" && filters.selectedItemId) {
    query.set("item_id", filters.selectedItemId);
  }
  return query;
}

export function buildWikiGraphQuery(options: WikiGraphOptions) {
  const query = new URLSearchParams();
  if (options.mode === "ego" && options.selectedItemId) {
    query.set("mode", "ego");
    query.set("center", options.selectedItemId);
    query.set("depth", "1");
    query.set("limit", "12");
  } else {
    query.set("mode", "overview");
    query.set("limit", "24");
  }
  options.graphKinds?.forEach((kind) => query.append("kinds", kind));
  return query;
}

export const knowledgeApi = new KnowledgeApi();
