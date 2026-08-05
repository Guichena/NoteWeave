import { buildInitialArtifactFormValues, type ArtifactStudioSkill } from "./artifactStudio";
import type { ShellRun } from "../shell/useShellBusy";
import type { AppView } from "../shell/viewRoute";
import type { AnswerMode } from "../../routes";
import { artifactsApi, type ArtifactsApi } from "./api";
import { buildArtifactCreatePayload, buildArtifactPrompt } from "./artifactComposer";

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
    if (!workspaceId) {
      setStatus("请先创建工作台");
      return;
    }
    const payload = buildArtifactCreatePayload(skill, catalog.formValues, catalog.customInstruction);
    await run(`创建 ${skill.title} 任务`, async () => {
      const created = await api.createJob(workspaceId, payload);
      await loadJobs();
      catalog.setComposerOpen(false);
      catalog.setCustomInstruction("");
      catalog.setFormValues(buildInitialArtifactFormValues(skill));
      appendSystemMessage(
        `已创建 ${skill.title} 任务：job=${created.artifact_job_id}，task=${created.task_id}，当前状态 ${created.status}。`
      );
      setStatus(`已创建“${skill.title}”任务，系统会通过独立 Artifact Worker 异步生成结果。`);
    }, "artifact");
  }

  return {
    updateFormValue,
    appendHint,
    preparePrompt,
    launchPrompt
  };
}
