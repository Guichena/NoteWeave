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
