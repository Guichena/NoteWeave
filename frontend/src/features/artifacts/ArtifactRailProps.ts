import type { ReactNode } from "react";
import type { ArtifactRuntimeTrace } from "./artifactRuntimeTrace";
import type { ArtifactStudioField, ArtifactStudioSkill } from "./artifactStudio";
import type { ArtifactJobSummary, ArtifactVersionComparison, ArtifactVersionDetail } from "./model";
import type { Workspace } from "../workspace/model";

export type ArtifactRailProps = {
  artifactComposerOpen: boolean;
  onCloseArtifactRail?: () => void;
  isBusy: boolean;
  setArtifactComposerOpen: (open: boolean) => void;
  selectedArtifactSkill?: ArtifactStudioSkill;
  renderArtifactField: (field: ArtifactStudioField) => ReactNode;
  artifactCustomInstruction: string;
  setArtifactCustomInstruction: (value: string) => void;
  appendArtifactHint: (hint: string) => void;
  workspace: Workspace | null;
  prepareArtifactPrompt: (skill: ArtifactStudioSkill) => string;
  isArtifactFormReady: (skill: ArtifactStudioSkill, formValues: Record<string, string>) => boolean;
  artifactFormValues: Record<string, string>;
  launchArtifactPrompt: (skill: ArtifactStudioSkill) => void | Promise<void>;
  artifactComposerError: string;
  clearArtifactComposerError: () => void;
  artifactStudioSkills: ArtifactStudioSkill[];
  artifactSkillsLoading: boolean;
  artifactJobs: ArtifactJobSummary[];
  artifactJobsLoading: boolean;
  refreshArtifactJobs?: () => Promise<unknown>;
  sourceCount: number;
  setSelectedArtifactSkillKey: (key: string) => void;
  setArtifactFormValues: (
    value: Record<string, string> | ((current: Record<string, string>) => Record<string, string>)
  ) => void;
  selectedArtifactHistoryVersion: ArtifactVersionDetail | null;
  latestArtifactVersion: ArtifactVersionDetail | null;
  formatRelativeTime: (value: string) => string;
  saveArtifactVersionAsSource: (version: { artifact_job_id: string; version_no: number }) => void | Promise<void>;
  artifactSavedSourceByVersionId: Record<string, string>;
  artifactVersionSaveKey: (version: { artifact_job_id: string; version_no: number }) => string;
  writeArtifactVersionToKnowledge: (
    version: { artifact_job_id: string; version_no: number; title: string },
    target: "NOTE" | "WIKI"
  ) => void | Promise<void>;
  artifactWritebackByVersionId: Record<string, string[]>;
  regenerateArtifactVersion: (version: { artifact_job_id: string; version_no: number }) => void | Promise<void>;
  /** NR05/NR22：编辑保存入口——返回明确结果，阅读器只在成功后清理草稿 */
  editArtifactVersion: (
    version: { artifact_job_id: string; version_no: number },
    markdown: string,
    editReason?: string,
    requestId?: string
  ) => Promise<import("./useArtifactWorkspace").ArtifactEditOutcome>;
  compareArtifactWithPreviousVersion: (version: {
    artifact_job_id: string;
    version_no: number;
  }) => Promise<ArtifactVersionComparison | null | void> | void;
  rollbackArtifactVersion: (version: { artifact_job_id: string; version_no: number }) => void | Promise<void>;
  downloadArtifactVersionPdf: (version: {
    artifact_job_id: string;
    version_no: number;
    runtime_trace?: ArtifactRuntimeTrace | null;
    files?: Array<{ file_format: string; status: string }>;
  }) => void | Promise<void>;
  downloadArtifactVersionFile: (version: {
    artifact_job_id: string;
    version_no: number;
  }, file: import("./model").ArtifactFileMetadata) => void | Promise<void>;
  resolveArtifactSkillTitle: (skillKey: string, skills?: ArtifactStudioSkill[]) => string;
  openArtifactHistoryVersion: (item: import("./artifactHistory").ArtifactHistoryItem) => void | Promise<void>;
  artifactHistoryLoadingKey: string;
  /** FE-03：版本加载失败原因（绑定到具体版本键）；失败时阅读器显示错误与重试，不显示空态 */
  versionError?: import("./state").ArtifactVersionLoadError | null;
  onRetryVersion?: () => void | Promise<unknown>;
  /** FE-03：任务列表失败原因；列表保留最后一次成功数据 */
  jobsError?: string | null;
  onRetryJobs?: () => void | Promise<unknown>;
  sourceDraftTitle: string;
  setSourceDraftTitle: (value: string) => void;
  sourceDraftContent: string;
  setSourceDraftContent: (value: string) => void;
  sourceDraftRewriteMode: string;
  rewriteNoteSourceDraft: () => void | Promise<void>;
  saveNoteAnswerAsSource: () => void | Promise<void>;
  lastNoteAssistantMessageId: string;
};
