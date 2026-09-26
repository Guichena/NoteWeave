export type SourceAsset = {
  source_id: string;
  title: string;
  source_type: string;
  status: string;
  parse_status: string;
  index_status: string;
  generated_by: string;
  generated_ref_id: string;
  updated_at: string;
};

export function isSourceReady(source: Pick<SourceAsset, "status">): boolean {
  return source.status.trim().toUpperCase() === "READY";
}

export function isSourceSearchable(source: Pick<SourceAsset, "status" | "index_status">): boolean {
  return isSourceReady(source)
    && source.index_status.trim().toUpperCase() === "INDEXED";
}

export function isSourceReadableOnly(source: Pick<SourceAsset, "status" | "index_status">): boolean {
  return isSourceReady(source)
    && source.index_status.trim().toUpperCase() === "DISABLED";
}

export function formatSourceProcessingStatus(status: string): string {
  switch (status.trim().toUpperCase()) {
    case "READY":
      return "已解析";
    case "PENDING":
    case "PROCESSING":
    case "PARSING":
      return "处理中";
    case "FAILED":
    case "ERROR":
      return "处理失败";
    case "DELETED":
      return "已删除";
    default:
      return status.trim() || "状态未知";
  }
}

export function formatSourceIndexStatus(status: string): string {
  switch (status.trim().toUpperCase()) {
    case "INDEXED":
      return "已建立检索索引";
    case "DISABLED":
      return "未建立检索索引";
    case "PENDING":
    case "PROCESSING":
    case "BUILDING":
      return "索引处理中";
    case "FAILED":
    case "ERROR":
      return "索引失败";
    default:
      return status.trim() || "索引状态未知";
  }
}

export type CompletedSourceUpload = {
  source_id: string;
  task_id: string;
  parse_status: string;
  index_status: string;
};

export type DeletedSource = {
  source_id: string;
  status: string;
  wiki_retract_task_id: string;
};

export type SaveAnswerAsSourceInput = {
  title: string;
  content?: string;
};

export type NoteSourceDraftInput = {
  title?: string;
};

export type NoteSourceDraft = {
  message_id: string;
  title: string;
  content: string;
  rewrite_mode: string;
  source_content: string;
};

export type SavedAnswerSource = {
  source_id: string;
  message_id: string;
  title: string;
  status: string;
  parse_status: string;
  index_status: string;
  generated_by: string;
  generated_ref_id: string;
};

export type RetrievalCoverage = {
  sourceCount: number;
  readySourceCount: number;
  qaReadySourceCount: number;
  noteReadySourceCount: number;
};

export type RetrievalIndexBuild = {
  id: string;
  projectionType: "QA_CHUNK" | "NOTE_SOURCE";
  targetIndex: string;
  status: string;
  expectedCount: number;
  readyCount: number;
  failedCount: number;
  lastErrorCode?: string;
};

export type RetrievalIndexStatus = {
  workspaceId: string;
  coverage: RetrievalCoverage;
  builds: RetrievalIndexBuild[];
  projections: Array<{ projectionType: string; status: string; count: number }>;
};

export type RetrievalBackfillResult = {
  workspaceId: string;
  qaBuild: RetrievalIndexBuild;
  noteBuild: RetrievalIndexBuild;
  failedSourceIds: string[];
};
