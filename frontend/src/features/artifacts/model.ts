import { type ArtifactRuntimeTrace } from "./artifactRuntimeTrace";
import { type WaitContext } from "../executions/model";

export type ArtifactJobCreateResponse = {
  artifact_job_id: string;
  task_id: string;
  skill_key: string;
  status: string;
};

export type ArtifactJobSummary = {
  artifact_job_id: string;
  workspace_id: string;
  task_id: string;
  skill_key: string;
  status: string;
  task_status: string;
  progress_phase: string;
  progress_message: string;
  result_title: string;
  wait_context?: WaitContext | null;
  latest_version_no: number;
  created_at: string;
  updated_at: string;
};

export type ArtifactFileMetadata = {
  file_id: string;
  file_format: string;
  file_name: string;
  media_type: string;
  storage_backend: string;
  bucket_name: string;
  object_key: string;
  size_bytes: number;
  checksum_sha256: string;
  status: string;
  error_message: string;
  created_at: string;
};

export type ArtifactVersionDetail = {
  version_id: string;
  artifact_job_id: string;
  skill_key: string;
  version_no: number;
  title: string;
  content_markdown: string;
  trace_summary: string;
  citations: Array<Record<string, unknown>>;
  runtime_trace?: ArtifactRuntimeTrace | null;
  files: ArtifactFileMetadata[];
  created_at: string;
};

export type ArtifactVersionComparison = {
  from_version_no: number;
  to_version_no: number;
  title_changed: boolean;
  added_lines: number;
  removed_lines: number;
  unchanged_lines: number;
  summary: string;
};

export type ArtifactSavedSourceResponse = {
  source_id: string;
  artifact_job_id: string;
  artifact_version_id: string;
  status: string;
  parse_status: string;
  index_status: string;
};

export type CreateArtifactJobInput = {
  skill_key: string;
  user_requirement: string;
  inputs: Record<string, unknown>;
  source_scope_source_ids?: string[];
};

export type VideoLearningChoice = {
  skill_key: string;
  artifact_job_id: string | null;
  status: string;
  task_id: string | null;
  latest_version_no: number;
};

export type VideoLearningRequest = {
  request_id: string;
  material_state: string;
  material_task_id: string | null;
  material_bundle_id: string | null;
  knowledge_plan_id: string | null;
  cancellation_requested: boolean;
  video_url: string;
  part: number;
  choices: VideoLearningChoice[];
};

export type VideoLearningOverview = {
  enabled: boolean;
  available_skills?: string[];
  requests: VideoLearningRequest[];
};

export type CreateVideoLearningInput = {
  client_request_id: string;
  video_url: string;
  part: number;
  language: "zh-CN" | "en" | "zh-EN";
  frame_density: "LOW" | "STANDARD" | "HIGH";
  asr_fallback: "ALLOW" | "DENY";
  template_version: "original-v1";
  user_requirement: string;
  selected_skills: string[];
};

export type ArtifactKnowledgeWritebackInput = {
  item_type: "NOTE" | "WIKI";
  title: string;
};
