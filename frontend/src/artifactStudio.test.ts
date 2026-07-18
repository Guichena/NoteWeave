import { describe, expect, it } from "vitest";
import {
  buildArtifactJobInputs,
  buildArtifactStudioSkill,
  buildInitialArtifactFormValues,
  isArtifactFormReady,
  readArtifactLanguage,
  readArtifactUrl,
  type ArtifactSkillSummary
} from "./artifactStudio";

describe("artifactStudio skill form helpers", () => {
  it("should derive schema-driven fields and default hints from skill catalog metadata", () => {
    const summary: ArtifactSkillSummary = {
      skill_key: "bilibili_course_note_pdf",
      display_name: "B站讲义 PDF",
      description: "生成讲义 PDF 任务",
      status: "ACTIVE",
      input_schema: {
        type: "object",
        properties: {
          language: {
            type: "string",
            default: "zh-CN",
            oneOf: [
              { const: "zh-CN", title: "中文（简体）" },
              { const: "en", title: "English" },
              { const: "zh-EN", title: "中英双语" }
            ]
          },
          url: { type: "string" }
        },
        required: ["url"]
      },
      default_input_hints: ["填写 B 站视频链接", "输出讲义 PDF"]
    };

    const skill = buildArtifactStudioSkill(summary, {
      title: "B站讲义 PDF",
      tone: "rose"
    });

    expect(skill.supportsUrl).toBe(true);
    expect(skill.defaultInputHints).toEqual(["填写 B 站视频链接", "输出讲义 PDF"]);
    expect(skill.inputFields).toEqual([
      {
        key: "language",
        label: "选择语言",
        kind: "select",
        required: false,
        placeholder: "",
        options: [
          { value: "zh-CN", label: "中文（简体）" },
          { value: "en", label: "English" },
          { value: "zh-EN", label: "中英双语" }
        ]
      },
      {
        key: "url",
        label: "B站链接",
        kind: "url",
        required: true,
        placeholder: "https://www.bilibili.com/video/BV..."
      }
    ]);
  });

  it("should not fabricate url fields when schema does not declare them", () => {
    const summary: ArtifactSkillSummary = {
      skill_key: "video_summary",
      display_name: "视频总结",
      description: "总结视频内容",
      status: "ACTIVE"
    };

    const skill = buildArtifactStudioSkill(summary);

    expect(skill.supportsUrl).toBe(false);
    expect(skill.inputFields).toEqual([]);
  });

  it("should build filtered artifact job inputs from schema-driven form values", () => {
    const summary: ArtifactSkillSummary = {
      skill_key: "bilibili_course_note_pdf",
      display_name: "B站讲义 PDF",
      description: "生成讲义 PDF 任务",
      status: "ACTIVE",
      input_schema: {
        type: "object",
        properties: {
          language: {
            type: "string",
            default: "zh-CN",
            oneOf: [
              { const: "zh-CN", title: "中文（简体）" },
              { const: "en", title: "English" },
              { const: "zh-EN", title: "中英双语" }
            ]
          },
          url: { type: "string" }
        },
        required: ["url"]
      }
    };
    const skill = buildArtifactStudioSkill(summary);

    const inputs = buildArtifactJobInputs(skill, {
      language: "en",
      url: " https://www.bilibili.com/video/BV1NoteWeaveDemo ",
      bilibili_url: "https://legacy.example.com"
    });

    expect(inputs).toEqual({
      language: "en",
      url: "https://www.bilibili.com/video/BV1NoteWeaveDemo"
    });
    expect(readArtifactLanguage({ language: "en" })).toBe("en");
    expect(readArtifactUrl(skill, inputs)).toBe("https://www.bilibili.com/video/BV1NoteWeaveDemo");
  });

  it("should initialize defaults and validate required fields", () => {
    const summary: ArtifactSkillSummary = {
      skill_key: "bilibili_course_note_pdf",
      display_name: "B站讲义 PDF",
      description: "生成讲义 PDF 任务",
      status: "ACTIVE",
      input_schema: {
        type: "object",
        properties: {
          language: {
            type: "string",
            default: "zh-CN",
            oneOf: [
              { const: "zh-CN", title: "中文（简体）" },
              { const: "en", title: "English" },
              { const: "zh-EN", title: "中英双语" }
            ]
          },
          url: { type: "string" }
        },
        required: ["url"]
      }
    };
    const skill = buildArtifactStudioSkill(summary);

    const defaults = buildInitialArtifactFormValues(skill);

    expect(defaults).toEqual({
      language: "zh-CN",
      url: ""
    });
    expect(isArtifactFormReady(skill, defaults)).toBe(false);
    expect(isArtifactFormReady(skill, { ...defaults, url: "https://www.bilibili.com/video/BV1NoteWeaveDemo" })).toBe(true);
  });

  it("should derive select options and defaults from generic schema metadata", () => {
    const summary: ArtifactSkillSummary = {
      skill_key: "report_draft",
      display_name: "结构化报告",
      description: "生成结构化报告草稿",
      status: "ACTIVE",
      input_schema: {
        type: "object",
        properties: {
          output_mode: {
            type: "string",
            default: "concise",
            oneOf: [
              { const: "concise", title: "简洁" },
              { const: "detailed", title: "详细" }
            ]
          }
        }
      }
    };

    const skill = buildArtifactStudioSkill(summary);

    expect(skill.inputFields).toEqual([
      {
        key: "output_mode",
        label: "Output Mode",
        kind: "select",
        required: false,
        placeholder: "请输入",
        options: [
          { value: "concise", label: "简洁" },
          { value: "detailed", label: "详细" }
        ]
      }
    ]);
    expect(buildInitialArtifactFormValues(skill)).toEqual({
      output_mode: "concise"
    });
  });
});
