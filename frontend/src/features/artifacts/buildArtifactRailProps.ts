import { createElement, type ReactNode } from "react";
import { formatRelativeTime } from "../../shared/util/datetime";
import { isArtifactFormReady, type ArtifactStudioField, type ArtifactStudioSkill } from "./artifactStudio";
import { artifactVersionSaveKey } from "./versionKey";
import { resolveArtifactSkillTitle } from "./skillCatalog";
import { ArtifactField } from "./ArtifactField";
import type { ArtifactRailProps } from "./ArtifactRailProps";
import type { ArtifactJobSummary, ArtifactVersionDetail } from "./model";
import type { Workspace } from "../workspace/model";

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
    artifactJobs: ArtifactJobSummary[];
    jobsLoading: boolean;
    latestArtifactVersion: ArtifactVersionDetail | null;
    selectedArtifactHistoryVersion: ArtifactVersionDetail | null;
    artifactSavedSourceByVersionId: Record<string, string>;
    artifactWritebackByVersionId: Record<string, string[]>;
    artifactHistoryLoadingKey: string;
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
  sourcesCount: number;
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
};

export function buildArtifactRailProps(input: BuildArtifactRailPropsInput): ArtifactRailProps {
  const { catalog, composer, workspace, artifactBusy, artifactWorkspace, sourcesCount, chat } = input;

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
    selectedArtifactSkill: catalog.selectedSkill,
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
    artifactJobs: artifactWorkspace.artifactJobs,
    artifactJobsLoading: artifactWorkspace.jobsLoading,
    refreshArtifactJobs: artifactWorkspace.loadJobs,
    sourceCount: sourcesCount,
    setSelectedArtifactSkillKey: catalog.setSelectedSkillKey,
    setArtifactFormValues: catalog.setFormValues,
    selectedArtifactHistoryVersion: artifactWorkspace.selectedArtifactHistoryVersion,
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
    openArtifactHistoryVersion: artifactWorkspace.openArtifactHistoryVersion,
    artifactHistoryLoadingKey: artifactWorkspace.artifactHistoryLoadingKey,
    sourceDraftTitle: chat.sourceDraftTitle,
    setSourceDraftTitle: chat.setSourceDraftTitle,
    sourceDraftContent: chat.sourceDraftContent,
    setSourceDraftContent: chat.setSourceDraftContent,
    sourceDraftRewriteMode: chat.sourceDraftRewriteMode,
    rewriteNoteSourceDraft: chat.rewriteNoteSourceDraft,
    saveNoteAnswerAsSource: chat.saveNoteAnswerAsSource,
    lastNoteAssistantMessageId: chat.lastNoteAssistantMessageId
  };
}
