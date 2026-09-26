import { describe, expect, it } from "vitest";
import { buildArtifactCreatePayload, buildArtifactPrompt, buildArtifactRequirement } from "./artifactComposer";
import type { ArtifactStudioSkill } from "./artifactStudio";

const skill = {
  key: "resume_highlight",
  title: "简历亮点描述",
  promptFocus: "输出适合简历使用的项目亮点描述",
  styleHint: "结果导向",
  summary: "",
  artifactType: "",
  sourceHint: "",
  runtimeHint: "",
  badges: [],
  tone: "blue",
  fields: [],
  inputFields: []
} as unknown as ArtifactStudioSkill;

describe("artifactComposer", () => {
  it("builds prompt with language and custom instruction", () => {
    const prompt = buildArtifactPrompt(skill, { language: "zh-CN" }, "强调架构");
    expect(prompt).toContain("输出语言：zh-CN");
    expect(prompt).toContain("补充说明：强调架构");
  });

  it("builds requirement payload lines", () => {
    const req = buildArtifactRequirement(skill, { language: "en" }, "");
    expect(req).toContain("技能：简历亮点描述");
    expect(req).toContain("输出语言：en");
  });

  it("freezes the displayed workspace sources into the artifact job request", () => {
    const payload = buildArtifactCreatePayload(
      skill,
      { language: "zh-CN" },
      "",
      ["source-1", "source-2", "source-1"]
    );

    expect(payload.source_scope_source_ids).toEqual(["source-1", "source-2"]);
  });
});
