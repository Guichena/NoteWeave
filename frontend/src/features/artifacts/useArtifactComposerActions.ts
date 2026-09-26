import { useState } from "react";
import { buildInitialArtifactFormValues, type ArtifactStudioSkill } from "./artifactStudio";
import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import type { AnswerMode } from "../../routes";
import { artifactsApi, type ArtifactsApi } from "./api";
import { buildArtifactCreatePayload, buildArtifactPrompt } from "./artifactComposer";
import { summarizeRunStatus } from "../../runStatus";

type CatalogLike = {
  formValues: Record<string, string>;
  customInstruction: string;
  setComposerOpen: (open: boolean) => void;
  setCustomInstruction: (value: string) => void;
  setFormValues: (
    value: Record<string, string> | ((current: Record<string, string>) => Record<string, string>)
  ) => void;
};

type UseArtifactComposerActionsInput = {
  workspaceId: string;
  sourceIds: string[];
  run: ShellRun;
  setStatus: (status: string) => void;
  setView: (view: AppView) => void;
  setChatMode: (mode: AnswerMode) => void;
  setQuestion: (value: string) => void;
  appendSystemMessage: (content: string) => void;
  catalog: CatalogLike;
  loadJobs: () => Promise<unknown>;
  api?: ArtifactsApi;
};

export function useArtifactComposerActions({
  workspaceId,
  sourceIds,
  run,
  setStatus,
  setView,
  setChatMode,
  setQuestion,
  appendSystemMessage,
  catalog,
  loadJobs,
  api = artifactsApi
}: UseArtifactComposerActionsInput) {
  const [composerError, setComposerError] = useState("");

  function updateFormValue(fieldKey: string, value: string) {
    catalog.setFormValues((current) => ({ ...current, [fieldKey]: value }));
  }

  function appendHint(hint: string) {
    const current = catalog.customInstruction;
    const trimmedCurrent = current.trim();
    if (!trimmedCurrent) {
      catalog.setCustomInstruction(hint);
      return;
    }
    if (trimmedCurrent.includes(hint)) {
      return;
    }
    catalog.setCustomInstruction(`${trimmedCurrent}\n- ${hint}`);
  }

  function preparePrompt(skill: ArtifactStudioSkill) {
    const prompt = buildArtifactPrompt(skill, catalog.formValues, catalog.customInstruction);
    setView("chat");
    setChatMode("qa");
    setQuestion(prompt);
    catalog.setComposerOpen(false);
    setStatus(`已将“${skill.title}”的生成请求填入聊天输入框。`);
    return prompt;
  }

  async function launchPrompt(skill: ArtifactStudioSkill) {
    setComposerError("");
    if (!workspaceId) {
      const message = "请先创建工作台";
      setComposerError(message);
      setStatus(message);
      return;
    }
    const payload = buildArtifactCreatePayload(
      skill,
      catalog.formValues,
      catalog.customInstruction,
      sourceIds
    );
    await run(`创建 ${skill.title} 任务`, async () => {
      try {
        const created = await api.createJob(workspaceId, payload);
        await loadJobs();
        catalog.setComposerOpen(false);
        catalog.setCustomInstruction("");
        catalog.setFormValues(buildInitialArtifactFormValues(skill));
        appendSystemMessage(
          `${skill.title} 已加入产物工作台，当前状态：${summarizeRunStatus(created.status)}。`
        );
        setStatus(`已创建“${skill.title}”任务，系统会通过独立 Artifact Worker 异步生成结果。`);
      } catch (error) {
        setComposerError(error instanceof Error ? error.message : "产物任务创建失败，请稍后重试");
        throw error;
      }
    }, "artifact");
  }

  return {
    updateFormValue,
    appendHint,
    preparePrompt,
    launchPrompt,
    composerError,
    clearComposerError: () => setComposerError("")
  };
}
