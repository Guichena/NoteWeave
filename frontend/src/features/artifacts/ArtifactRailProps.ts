import type { ReactNode } from "react";
import type { ArtifactSidebarState } from "./artifactSidebar";
import type { ArtifactStudioField, ArtifactStudioSkill } from "./artifactStudio";
import type { ArtifactVersionDetail } from "./model";
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
  artifactJobsLoading: boolean;
  refreshArtifactJobs?: () => Promise<unknown>;
  sourceCount: number;
  setSelectedArtifactSkillKey: (key: string) => void;
  setArtifactFormValues: (
    value: Record<string, string> | ((current: Record<string, string>) => Record<string, string>)
  ) => void;
  artifactSidebarState: ArtifactSidebarState;
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
  compareArtifactWithPreviousVersion: (version: {
    artifact_job_id: string;
    version_no: number;
  }) => void | Promise<void>;
  rollbackArtifactVersion: (version: { artifact_job_id: string; version_no: number }) => void | Promise<void>;
  downloadArtifactVersionPdf: (version: {
    artifact_job_id: string;
    version_no: number;
    runtime_trace?: unknown;
    files?: Array<{ file_format: string; status: string }>;
  }) => void | Promise<void>;
  downloadArtifactVersionFile: (version: {
    artifact_job_id: string;
    version_no: number;
  }, file: import("./model").ArtifactFileMetadata) => void | Promise<void>;
  resolveArtifactSkillTitle: (skillKey: string, skills?: ArtifactStudioSkill[]) => string;
  summarizeRunStatus: (status: string) => string;
  openArtifactHistoryVersion: (item: import("./artifactHistory").ArtifactHistoryItem) => void | Promise<void>;
  artifactHistoryLoadingKey: string;
  openWikiHome: () => void | Promise<void>;
  openResearchWorkbench: () => void | Promise<void>;
  openMemoryWorkbench: () => void;
  toggleWikiEnabled: () => void | Promise<void>;
  wikiEnabled: boolean;
  sourceDraftTitle: string;
  setSourceDraftTitle: (value: string) => void;
  sourceDraftContent: string;
  setSourceDraftContent: (value: string) => void;
  sourceDraftRewriteMode: string;
  rewriteNoteSourceDraft: () => void | Promise<void>;
  saveNoteAnswerAsSource: () => void | Promise<void>;
  lastNoteAssistantMessageId: string;
  wikiRebuildAdvice: { message?: string } | null | undefined;
};
