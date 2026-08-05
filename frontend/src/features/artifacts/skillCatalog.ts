import {
  type ArtifactSkillSummary,
  type ArtifactStudioSkill
} from "./artifactStudio";

export const DEFAULT_ARTIFACT_STUDIO_SKILL_SUMMARIES: ArtifactSkillSummary[] = [
  {
    skill_key: "resume_highlight",
    display_name: "简历亮点描述",
    description: "把当前工作台资料整理成适合写进简历的项目亮点和影响表述。",
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
        }
      }
    },
    default_input_hints: ["强调架构设计", "强调工程复杂度", "适合校招简历"]
  },
  {
    skill_key: "study_guide",
    display_name: "学习指南",
    description: "按知识点、关键概念和练习建议生成结构化学习材料。",
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
        }
      }
    },
    default_input_hints: ["突出关键概念", "加入练习路径", "适合新人上手"]
  },
  {
    skill_key: "quiz_pack",
    display_name: "测验题集",
    description: "围绕当前资料生成题目、答案解析和评分要点。",
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
        }
      }
    },
    default_input_hints: ["区分题型难度", "附标准答案", "保留评分要点"]
  },
  {
    skill_key: "wiki_page",
    display_name: "Wiki 页面",
    description: "沉淀成定义、机制、引用和相关页面齐全的知识页草稿。",
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
        }
      }
    },
    default_input_hints: ["定义先行", "补充关键机制", "保留相关页面建议"]
  },
  {
    skill_key: "bilibili_course_note_pdf",
    display_name: "B站讲义 PDF",
    description: "面向 B 站视频链接生成图文讲义与 PDF 编译请求。",
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
    default_input_hints: ["填写 B 站视频链接", "保留章节结构", "输出讲义 PDF"]
  }
];

export const ARTIFACT_STUDIO_PRESENTATION_ENTRIES = [
  {
    key: "resume_highlight",
    title: "简历亮点描述",
    summary: "把当前工作台资料整理成适合写进简历的项目亮点和影响表述。",
    artifactType: "Resume Highlights",
    sourceHint: "当前工作台资料 / 最新回答",
    runtimeHint: "快速生成",
    badges: ["推荐", "聊天生成"],
    styleHint: "结果导向、动词开头、突出指标与复杂度。",
    promptFocus: "输出适合简历使用的项目亮点描述",
    tone: "blue"
  },
  {
    key: "study_guide",
    title: "学习指南",
    summary: "按知识点、关键概念和练习建议生成结构化学习材料。",
    artifactType: "Study Guide",
    sourceHint: "当前工作台资料",
    runtimeHint: "可扩展为异步",
    badges: ["常用", "结构化"],
    styleHint: "教学口吻、层次清晰、包含复习路径。",
    promptFocus: "输出带章节结构的学习指南",
    tone: "gold"
  },
  {
    key: "quiz_pack",
    title: "测验题集",
    summary: "围绕当前资料生成题目、答案解析和评分要点。",
    artifactType: "Quiz",
    sourceHint: "当前工作台资料 / 选中资料",
    runtimeHint: "可扩展为异步",
    badges: ["练习", "结构化"],
    styleHint: "区分难度，附带标准答案和解析。",
    promptFocus: "输出可直接使用的测验题集",
    tone: "green"
  },
  {
    key: "wiki_page",
    title: "Wiki 页面",
    summary: "沉淀成定义、机制、引用和相关页面齐全的知识页草稿。",
    artifactType: "Wiki Page",
    sourceHint: "当前工作台资料 / 已保存产物",
    runtimeHint: "建议校验后入库",
    badges: ["知识沉淀", "需校验"],
    styleHint: "定义明确、结构稳定、保留相关页面建议。",
    promptFocus: "输出适合 Wiki 的知识页草稿",
    tone: "violet"
  },
  {
    key: "bilibili_course_note_pdf",
    title: "B站讲义 PDF",
    summary: "面向 B 站视频链接生成图文讲义与 PDF 编译请求。",
    artifactType: "Course Note PDF",
    sourceHint: "B站链接 / 内置能力",
    runtimeHint: "异步 + System MCP",
    badges: ["System MCP", "异步"],
    styleHint: "专业讲义体，保留章节、图示和总结。",
    promptFocus: "输出 B 站讲义 PDF 生成请求",
    tone: "rose"
  }
] satisfies Array<{ key: string } & Partial<ArtifactStudioSkill>>;

export const ARTIFACT_STUDIO_PRESENTATION: Record<string, Partial<ArtifactStudioSkill>> = Object.fromEntries(
  ARTIFACT_STUDIO_PRESENTATION_ENTRIES.map((skill) => [skill.key, skill])
);

export const ARTIFACT_STUDIO_SKILL_ORDER = DEFAULT_ARTIFACT_STUDIO_SKILL_SUMMARIES.map(
  (skill) => skill.skill_key
);

export function resolveArtifactSkillTitle(skillKey: string, skills: ArtifactStudioSkill[]): string {
  return skills.find((skill) => skill.key === skillKey)?.title || skillKey;
}
