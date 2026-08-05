import {
  buildArtifactJobInputs,
  readArtifactLanguage,
  readArtifactUrl,
  type ArtifactStudioSkill
} from "./artifactStudio";

export function buildArtifactPrompt(
  skill: ArtifactStudioSkill,
  formValues: Record<string, string>,
  customInstruction: string
): string {
  const artifactLanguage = readArtifactLanguage(formValues);
  const artifactUrl = readArtifactUrl(skill, formValues);
  const baseHeader = [
    `请基于当前工作台资料，${skill.promptFocus}。`,
    `输出语言：${artifactLanguage}。`,
    `额外要求：${skill.styleHint}`
  ];
  const custom = customInstruction.trim();
  if (custom) {
    baseHeader.push(`补充说明：${custom}`);
  }
  if (skill.key === "bilibili_course_note_pdf") {
    baseHeader.push(`B站链接：${artifactUrl || "请补充视频链接"}`);
  }
  return baseHeader.join("\n");
}

export function buildArtifactRequirement(
  skill: ArtifactStudioSkill,
  formValues: Record<string, string>,
  customInstruction: string
): string {
  const artifactLanguage = readArtifactLanguage(formValues);
  const artifactUrl = readArtifactUrl(skill, formValues);
  const custom = customInstruction.trim();
  const requirements = [
    `技能：${skill.title}`,
    `输出语言：${artifactLanguage}`,
    `风格：${skill.styleHint}`
  ];
  if (custom) {
    requirements.push(`补充说明：${custom}`);
  }
  if (skill.key === "bilibili_course_note_pdf") {
    requirements.push(`B站链接：${artifactUrl || "请补充视频链接"}`);
    requirements.push("需要按讲义章节、字幕获取策略与 PDF 产物要求组织异步任务");
  }
  return requirements.join("\n");
}

export function buildArtifactCreatePayload(
  skill: ArtifactStudioSkill,
  formValues: Record<string, string>,
  customInstruction: string
) {
  return {
    skill_key: skill.key,
    user_requirement: buildArtifactRequirement(skill, formValues, customInstruction),
    inputs: buildArtifactJobInputs(skill, formValues)
  };
}
