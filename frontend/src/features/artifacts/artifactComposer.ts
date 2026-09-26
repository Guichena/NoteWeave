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
  if (skill.key === "mindmap_from_workspace") {
    baseHeader.push(`导图布局：${formValues.layout || "balanced"}。`);
    baseHeader.push(`内容层级：${formValues.depth || "3"} 层。`);
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
  if (skill.key === "mindmap_from_workspace") {
    requirements.push(`导图布局：${formValues.layout || "balanced"}`);
    requirements.push(`内容层级：${formValues.depth || "3"}`);
    requirements.push("输出单一根节点、4 到 7 条主分支和短语化节点，保留资料来源线索");
  }
  return requirements.join("\n");
}

export function buildArtifactCreatePayload(
  skill: ArtifactStudioSkill,
  formValues: Record<string, string>,
  customInstruction: string,
  sourceIds: string[] = []
) {
  return {
    skill_key: skill.key,
    user_requirement: buildArtifactRequirement(skill, formValues, customInstruction),
    inputs: buildArtifactJobInputs(skill, formValues),
    source_scope_source_ids: Array.from(new Set(sourceIds.filter(Boolean)))
  };
}
