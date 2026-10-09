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
  /** 解析完成后才有；旧版后端不返回这些字段。 */
  chunk_count?: number | null;
  page_count?: number | null;
  /** 最近一次资料解析任务。 */
  task_id?: string | null;
  /** 最新快照所处（或失败时停在）的处理阶段：EXTRACTING / CHUNKING / EMBEDDING / INDEXING / READY。 */
  processing_stage?: string | null;
  /** 检索索引已自动重试的次数与下次自动重试的时间。 */
  index_attempt_count?: number | null;
  next_index_retry_at?: string | null;
};

/** 重新处理的结果：从解析阶段（PARSE）或向量化阶段（INDEX）重新开始。 */
export type SourceReprocessResult = {
  source_id: string;
  task_id: string;
  restart_from: "PARSE" | "INDEX";
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

// 后端全局使用 SNAKE_CASE 序列化，这里的字段名与响应保持一致。
export type RetrievalCoverage = {
  source_count: number;
  ready_source_count: number;
  qa_ready_source_count: number;
  note_ready_source_count: number;
};

export type RetrievalIndexBuild = {
  id: string;
  projection_type: "QA_CHUNK" | "NOTE_SOURCE";
  target_index: string;
  status: string;
  expected_count: number;
  ready_count: number;
  failed_count: number;
  last_error_code?: string | null;
};

export type RetrievalIndexStatus = {
  workspace_id: string;
  coverage: RetrievalCoverage;
  builds: RetrievalIndexBuild[];
  projections: Array<{ projection_type: string; status: string; count: number }>;
};

export type RetrievalBackfillResult = {
  workspace_id: string;
  qa_build: RetrievalIndexBuild;
  note_build: RetrievalIndexBuild;
  failed_source_ids: string[];
};
