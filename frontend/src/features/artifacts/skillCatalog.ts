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
    skill_key: "mindmap_from_workspace",
    display_name: "思维导图",
    description: "把当前工作台资料整理为可缩放、可折叠的交互式思维导图。",
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
        layout: {
          type: "string",
          default: "balanced",
          oneOf: [
            { const: "balanced", title: "均衡分支" },
            { const: "compact", title: "紧凑概览" }
          ]
        },
        depth: {
          type: "string",
          default: "3",
          oneOf: [
            { const: "2", title: "2 层，快速浏览" },
            { const: "3", title: "3 层，推荐" },
            { const: "4", title: "4 层，详细" }
          ]
        }
      }
    },
    default_input_hints: ["4 到 7 条主分支", "节点使用短语", "保留来源线索"]
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
    key: "mindmap_from_workspace",
    title: "思维导图",
    summary: "把资料中的主题、概念和证据组织成可缩放、可折叠的知识分支。",
    artifactType: "Mind Map",
    sourceHint: "当前工作台资料 / Wiki",
    runtimeHint: "交互式预览",
    badges: ["可视化", "推荐"],
    styleHint: "保持单一根节点，主分支控制在 4 到 7 条，节点使用短语并保留来源线索。",
    promptFocus: "输出适合交互式思维导图的层级化 Markdown",
    tone: "green"
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
  },
  {
    key: "report_draft",
    title: "结构化报告",
    summary: "把多份资料综合成问题、证据、判断和行动建议清晰的报告。",
    artifactType: "Report",
    sourceHint: "当前工作台资料 / 研究结果",
    runtimeHint: "异步生成",
    badges: ["综合", "可追溯"],
    styleHint: "先给结论，再展开证据、分歧和行动建议。",
    promptFocus: "输出可审阅、可继续修订的结构化报告",
    tone: "blue"
  },
  {
    key: "faq_draft",
    title: "FAQ 草稿",
    summary: "把资料重组为常见问题、直接答案和使用说明。",
    artifactType: "FAQ",
    sourceHint: "当前工作台资料",
    runtimeHint: "快速生成",
    badges: ["帮助中心", "问答"],
    styleHint: "问题具体，答案直接，补充边界和下一步。",
    promptFocus: "输出面向读者问题的 FAQ 草稿",
    tone: "green"
  },
  {
    key: "structured_note",
    title: "结构化笔记",
    summary: "把散乱资料沉淀为主题、关键摘录、判断和后续问题。",
    artifactType: "Note",
    sourceHint: "当前工作台资料 / 最新回答",
    runtimeHint: "快速生成",
    badges: ["知识对象", "可写回"],
    styleHint: "结构轻量，保留关键摘录和未解决问题。",
    promptFocus: "输出可继续沉淀的结构化笔记",
    tone: "violet"
  },
  {
    key: "video_summary",
    title: "视频总结",
    summary: "从视频链接提炼主题、时间线、关键观点和行动项。",
    artifactType: "Video Brief",
    sourceHint: "视频链接",
    runtimeHint: "异步生成",
    badges: ["媒体", "时间线"],
    styleHint: "按时间线组织，区分原话、摘要和判断。",
    promptFocus: "输出带时间线的可追溯视频总结",
    tone: "rose"
  },
  {
    key: "audio_minutes",
    title: "音频纪要",
    summary: "整理会议结论、行动项、负责人和待确认问题。",
    artifactType: "Minutes",
    sourceHint: "当前工作台音频资料",
    runtimeHint: "异步生成",
    badges: ["会议", "行动项"],
    styleHint: "先结论后过程，行动项必须具体可跟进。",
    promptFocus: "输出可执行的音频会议纪要",
    tone: "gold"
  },
  {
    key: "course_notes",
    title: "课程笔记",
    summary: "按章节整理知识点、重点难点、例子和复习题。",
    artifactType: "Course Notes",
    sourceHint: "课程资料 / 可选视频链接",
    runtimeHint: "异步生成",
    badges: ["学习", "章节"],
    styleHint: "按课程章节组织，加入复习提示和自测问题。",
    promptFocus: "输出适合复习的课程笔记",
    tone: "gold"
  }
] satisfies Array<{ key: string } & Partial<ArtifactStudioSkill>>;

export const ARTIFACT_STUDIO_PRESENTATION: Record<string, Partial<ArtifactStudioSkill>> = Object.fromEntries(
  ARTIFACT_STUDIO_PRESENTATION_ENTRIES.map((skill) => [skill.key, skill])
);

export const ARTIFACT_STUDIO_SKILL_ORDER = [
  "mindmap_from_workspace",
  "report_draft",
  "wiki_page",
  "study_guide",
  "structured_note",
  "quiz_pack",
  "resume_highlight",
  "faq_draft",
  "course_notes",
  "video_summary",
  "audio_minutes",
  "bilibili_course_note_pdf"
];

export function resolveArtifactSkillTitle(skillKey: string, skills: ArtifactStudioSkill[]): string {
  return skills.find((skill) => skill.key === skillKey)?.title || skillKey;
}
