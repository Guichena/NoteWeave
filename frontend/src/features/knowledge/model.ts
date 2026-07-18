export type WikiPage = {
  item_id: string;
  item_type: string;
  page_kind: string;
  title: string;
  latest_version_id: string;
  latest_version_no: number;
  summary: string;
  updated_at: string;
  outgoing_count: number;
  backlink_count: number;
  citation_count: number;
  unresolved_count: number;
};

export type WikiLink = {
  source_item_id: string;
  target_item_id: string | null;
  target_title: string;
  relation_type: string;
  relation_status: string;
  mention_count: number;
};

export type WikiHome = {
  workspace_id: string;
  wiki_url: string;
  pages: WikiPage[];
  links: WikiLink[];
};

export type WikiSettings = {
  workspace_id: string;
  wiki_enabled: boolean;
};

export type WikiRebuild = {
  workspace_id: string;
  source_count: number;
  task_count: number;
  task_ids: string[];
};

export type WikiStats = {
  page_count: number;
  link_count: number;
  resolved_link_count: number;
  unresolved_link_count: number;
  citation_count: number;
  issue_count: number;
  auto_fixable_issue_count: number;
  manual_review_issue_count: number;
  pages_by_kind: Record<string, number>;
  recent_updates: WikiPage[];
  recent_tasks: WikiTaskSummary[];
  pending_task_count: number;
  wiki_enabled: boolean;
};

export type WikiIssue = {
  item_id: string;
  issue_type: string;
  severity: string;
  title: string;
  message: string;
  suggested_action: string;
  auto_fixable: boolean;
  action_code: string;
};

export type WikiLogEntry = {
  id: string;
  item_id: string | null;
  event_type: string;
  message: string;
  created_at: string;
};

export type WikiGraphMode = "overview" | "ego";

export type WikiGraph = {
  nodes: Array<{
    item_id: string;
    title: string;
    page_kind: string;
    version_no: number;
    degree: number;
    outgoing_count: number;
    backlink_count: number;
    citation_count: number;
    unresolved_count: number;
  }>;
  edges: Array<{
    source_item_id: string;
    source_title: string;
    target_item_id: string | null;
    target_title: string;
    relation_type: string;
    relation_status: string;
    mention_count: number;
  }>;
  meta: {
    mode: WikiGraphMode;
    center_item_id: string;
    depth: number;
    total_nodes: number;
    returned_nodes: number;
    truncated: boolean;
  };
};

export type WikiRebuildAdvice = {
  should_enable_wiki: boolean;
  ready_source_count: number;
  active_wiki_page_count: number;
  message: string;
  recommended_action: string;
  recommended_issue_type: string;
  focus_item_id: string;
  focus_title: string;
};

export type WikiTaskRelatedPage = {
  item_id: string;
  title: string;
  page_kind: string;
};

export type WikiIndexSource = {
  source_id: string;
  title: string;
  status: string;
  index_status: string;
  related_pages: WikiTaskRelatedPage[];
  recommended_action: string;
  focus_item_id: string;
  focus_title: string;
  updated_at: string;
};

export type WikiTaskSummary = {
  task_id: string;
  task_type: string;
  task_status: string;
  progress_phase: string;
  progress_message: string;
  target_type: string;
  target_id: string;
  target_title: string;
  related_pages: WikiTaskRelatedPage[];
  updated_at: string;
};

export type WikiIndex = {
  workspace_id: string;
  wiki_enabled: boolean;
  ready_source_count: number;
  page_count: number;
  source_backed_page_count: number;
  manual_page_count: number;
  link_count: number;
  resolved_link_count: number;
  unresolved_link_count: number;
  citation_count: number;
  issue_count: number;
  auto_fixable_issue_count: number;
  manual_review_issue_count: number;
  pending_task_count: number;
  pages_by_kind: Record<string, number>;
  recent_updates: WikiPage[];
  recent_tasks: WikiTaskSummary[];
  recent_sources: WikiIndexSource[];
  top_issues: WikiIssue[];
};

export type KnowledgeCitation = {
  citation_id: string;
  source_id: string;
  title: string;
  quote_text: string;
  page_no: number | null;
  location_info: string;
  generated_by: string;
  generated_ref_id: string;
};

export type KnowledgeItemDetail = WikiPage & {
  content: string;
  source_message_id: string | null;
  citations: KnowledgeCitation[];
  outgoing_links: WikiLink[];
  backlinks: WikiLink[];
  version_created_at: string;
};

export type KnowledgeVersionDetail = {
  version_id: string;
  item_id: string;
  version_no: number;
  content: string;
  summary: string;
  source_message_id: string | null;
  citations: KnowledgeCitation[];
  created_at: string;
};

export type KnowledgeVersionSummary = {
  version_id: string;
  version_no: number;
  summary: string;
  source_message_id: string | null;
  citation_count: number;
  created_at: string;
};

export type WikiIssueFilters = {
  issueType?: string;
  severity?: string;
  scope?: "ALL" | "AUTO" | "MANUAL";
  page?: "ALL" | "CURRENT";
  selectedItemId?: string;
};

export type WikiGraphOptions = {
  mode?: WikiGraphMode;
  selectedItemId?: string;
  graphKinds?: string[];
};

export type CreateKnowledgeItemInput = {
  item_type: "NOTE" | "WIKI";
  title: string;
  content: string;
  source_message_id: string | null;
};

export type AppendKnowledgeVersionInput = {
  content: string;
  source_message_id: string | null;
};

export type WikiAutoFixResult = {
  created_pages: number;
  remaining_issues: number;
};
