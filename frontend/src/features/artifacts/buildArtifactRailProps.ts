import { createElement, type ReactNode } from "react";
import { formatRelativeTime } from "../../shared/util/datetime";
import { isArtifactFormReady, type ArtifactStudioField, type ArtifactStudioSkill } from "./artifactStudio";
import { buildArtifactSidebarState } from "./artifactSidebar";
import { summarizeRunStatus } from "../../runStatus";
import { artifactVersionSaveKey } from "./versionKey";
import { resolveArtifactSkillTitle } from "./skillCatalog";
import { ArtifactField } from "./ArtifactField";
import type { ArtifactRailProps } from "./ArtifactRailProps";
import type { Workspace } from "../workspace/model";
import type { ArtifactHistoryJobSummary } from "./artifactHistory";
import type { ArtifactHistoryViewerVersion } from "./artifactHistoryViewer";
import type {
  ArtifactSidebarResearchRunSummary,
  ArtifactSidebarWorkspaceTask
} from "./artifactSidebar";

type BuildArtifactRailPropsInput = {
  catalog: {
    composerOpen: boolean;
    setComposerOpen: (open: boolean) => void;
    customInstruction: string;
    setCustomInstruction: (value: string) => void;
    formValues: Record<string, string>;
    setFormValues: (
      value: Record<string, string> | ((current: Record<string, string>) => Record<string, string>)
    ) => void;
    skills: ArtifactStudioSkill[];
    loading: boolean;
    selectedSkill: ArtifactStudioSkill | undefined;
    setSelectedSkillKey: (key: string) => void;
  };
  composer: {
    updateFormValue: (fieldKey: string, value: string) => void;
    appendHint: (hint: string) => void;
    preparePrompt: (skill: ArtifactStudioSkill) => string;
    launchPrompt: (skill: ArtifactStudioSkill) => Promise<void>;
    composerError: string;
    clearComposerError: () => void;
  };
  workspace: Workspace | null;
  artifactBusy: boolean;
  artifactWorkspace: {
    artifactJobs: ArtifactHistoryJobSummary[];
    jobsLoading: boolean;
    latestArtifactVersion: ArtifactRailProps["latestArtifactVersion"];
    selectedArtifactHistoryVersion: ArtifactHistoryViewerVersion | null;
    selectedArtifactHistoryKey: string;
    artifactSavedSourceByVersionId: Record<string, string>;
    artifactWritebackByVersionId: Record<string, string[]>;
    artifactHistoryLoadingKey: string;
    clear: () => void;
    loadJobs: () => Promise<unknown>;
    openArtifactHistoryVersion: ArtifactRailProps["openArtifactHistoryVersion"];
    saveArtifactVersionAsSource: ArtifactRailProps["saveArtifactVersionAsSource"];
    writeArtifactVersionToKnowledge: ArtifactRailProps["writeArtifactVersionToKnowledge"];
    regenerateArtifactVersion: ArtifactRailProps["regenerateArtifactVersion"];
    compareArtifactWithPreviousVersion: ArtifactRailProps["compareArtifactWithPreviousVersion"];
    rollbackArtifactVersion: ArtifactRailProps["rollbackArtifactVersion"];
    downloadArtifactVersionPdf: ArtifactRailProps["downloadArtifactVersionPdf"];
    downloadArtifactVersionFile: ArtifactRailProps["downloadArtifactVersionFile"];
  };
  researchSummary: ArtifactSidebarResearchRunSummary | null | undefined;
  latestTask: ArtifactSidebarWorkspaceTask | null | undefined;
  sourcesCount: number;
  wiki: {
    wikiEnabled: boolean;
    wikiIndex: { page_count?: number; pending_task_count?: number } | null;
    wikiHome: { pages: Array<{ updated_at?: string }> } | null;
    wikiRebuildAdvice: { message?: string } | null | undefined;
    openWikiHome: () => void | Promise<void>;
    toggleWikiEnabled: () => void | Promise<void>;
  };
  chat: {
    sourceDraftTitle: string;
    setSourceDraftTitle: (value: string) => void;
    sourceDraftContent: string;
    setSourceDraftContent: (value: string) => void;
    sourceDraftRewriteMode: string;
    rewriteNoteSourceDraft: () => void | Promise<void>;
    saveNoteAnswerAsSource: () => void | Promise<void>;
    lastNoteAssistantMessageId: string;
  };
  openResearchWorkbench: () => void | Promise<void>;
  openMemoryWorkbench: () => void;
};

export function buildArtifactRailProps(input: BuildArtifactRailPropsInput): ArtifactRailProps {
  const {
    catalog,
    composer,
    workspace,
    artifactBusy,
    artifactWorkspace,
    researchSummary,
    latestTask,
    sourcesCount,
    wiki,
    chat,
    openResearchWorkbench,
    openMemoryWorkbench
  } = input;

  const selectedArtifactSkill = catalog.selectedSkill;
  const artifactSidebarState = buildArtifactSidebarState({
    artifactJobs: artifactWorkspace.artifactJobs,
    latestArtifactVersion: artifactWorkspace.latestArtifactVersion,
    selectedArtifactHistoryVersion: artifactWorkspace.selectedArtifactHistoryVersion,
    selectedArtifactHistoryKey: artifactWorkspace.selectedArtifactHistoryKey,
    currentResearchRunSummary: researchSummary,
    latestTask,
    workspaceReady: Boolean(workspace),
    wikiState: workspace
      ? {
          enabled: wiki.wikiEnabled,
          pageCount: wiki.wikiIndex?.page_count ?? wiki.wikiHome?.pages.length ?? 0,
          pendingTaskCount: wiki.wikiIndex?.pending_task_count ?? 0,
          updatedAt: wiki.wikiHome?.pages[0]?.updated_at ?? ""
        }
      : null,
    sourcesCount,
    resolveArtifactSkillTitle: (skillKey) => resolveArtifactSkillTitle(skillKey, catalog.skills),
    formatRelativeTime
  });

  function renderArtifactField(field: ArtifactStudioField): ReactNode {
    return createElement(ArtifactField, {
      field,
      value: catalog.formValues[field.key] || "",
      onChange: composer.updateFormValue
    });
  }

  return {
    artifactComposerOpen: catalog.composerOpen,
    isBusy: artifactBusy,
    setArtifactComposerOpen: catalog.setComposerOpen,
    selectedArtifactSkill,
    renderArtifactField,
    artifactCustomInstruction: catalog.customInstruction,
    setArtifactCustomInstruction: catalog.setCustomInstruction,
    appendArtifactHint: composer.appendHint,
    workspace,
    prepareArtifactPrompt: composer.preparePrompt,
    isArtifactFormReady,
    artifactFormValues: catalog.formValues,
    launchArtifactPrompt: composer.launchPrompt,
    artifactComposerError: composer.composerError,
    clearArtifactComposerError: composer.clearComposerError,
    artifactStudioSkills: catalog.skills,
    artifactSkillsLoading: catalog.loading,
    artifactJobsLoading: artifactWorkspace.jobsLoading,
    refreshArtifactJobs: artifactWorkspace.loadJobs,
    sourceCount: sourcesCount,
    setSelectedArtifactSkillKey: catalog.setSelectedSkillKey,
    setArtifactFormValues: catalog.setFormValues,
    artifactSidebarState,
    latestArtifactVersion: artifactWorkspace.latestArtifactVersion,
    formatRelativeTime,
    saveArtifactVersionAsSource: artifactWorkspace.saveArtifactVersionAsSource,
    artifactSavedSourceByVersionId: artifactWorkspace.artifactSavedSourceByVersionId,
    artifactVersionSaveKey,
    writeArtifactVersionToKnowledge: artifactWorkspace.writeArtifactVersionToKnowledge,
    artifactWritebackByVersionId: artifactWorkspace.artifactWritebackByVersionId,
    regenerateArtifactVersion: artifactWorkspace.regenerateArtifactVersion,
    compareArtifactWithPreviousVersion: artifactWorkspace.compareArtifactWithPreviousVersion,
    rollbackArtifactVersion: artifactWorkspace.rollbackArtifactVersion,
    downloadArtifactVersionPdf: artifactWorkspace.downloadArtifactVersionPdf,
    downloadArtifactVersionFile: artifactWorkspace.downloadArtifactVersionFile,
    resolveArtifactSkillTitle: (skillKey: string) => resolveArtifactSkillTitle(skillKey, catalog.skills),
    summarizeRunStatus,
    openArtifactHistoryVersion: artifactWorkspace.openArtifactHistoryVersion,
    artifactHistoryLoadingKey: artifactWorkspace.artifactHistoryLoadingKey,
    openWikiHome: wiki.openWikiHome,
    openResearchWorkbench,
    openMemoryWorkbench,
    toggleWikiEnabled: wiki.toggleWikiEnabled,
    wikiEnabled: wiki.wikiEnabled,
    sourceDraftTitle: chat.sourceDraftTitle,
    setSourceDraftTitle: chat.setSourceDraftTitle,
    sourceDraftContent: chat.sourceDraftContent,
    setSourceDraftContent: chat.setSourceDraftContent,
    sourceDraftRewriteMode: chat.sourceDraftRewriteMode,
    rewriteNoteSourceDraft: chat.rewriteNoteSourceDraft,
    saveNoteAnswerAsSource: chat.saveNoteAnswerAsSource,
    lastNoteAssistantMessageId: chat.lastNoteAssistantMessageId,
    wikiRebuildAdvice: wiki.wikiRebuildAdvice
  };
}
