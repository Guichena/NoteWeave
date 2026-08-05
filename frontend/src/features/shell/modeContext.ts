import type { AnswerMode } from "../../routes";

const MODE_CONTEXT: Record<AnswerMode, string> = {
  qa: "基于工作台资料回答，展示引用与证据；回答不入库。",
  note: "结构化检索漏斗与摘录证据；回答后可整理为资料入库。",
  wiki: "工作台级 Wiki 网络检索；治理与图谱请进入 Wiki 工作台。"
};

const MODE_EXAMPLES: Record<AnswerMode, string[]> = {
  qa: [
    "这份资料的核心结论是什么？",
    "有哪些相互矛盾的观点？",
    "用三条要点概括当前工作台内容。"
  ],
  note: [
    "按 Journal 信号梳理关键摘录。",
    "哪些原文片段支撑主要主张？",
    "整理成可入库的中性笔记草稿。"
  ],
  wiki: [
    "当前 Wiki 网络里与本主题相关的页面有哪些？",
    "有哪些断链或待补全的页面？",
    "总结知识图谱中的关键实体关系。"
  ]
};

const MODE_PLACEHOLDERS: Record<AnswerMode, string> = {
  qa: "基于资料提问，例如：核心结论是什么？",
  note: "提出精读问题，例如：请整理关键摘录与证据。",
  wiki: "查询 Wiki 网络，例如：相关页面与断链有哪些？"
};

export function modeContextLine(mode: AnswerMode): string {
  return MODE_CONTEXT[mode] ?? MODE_CONTEXT.qa;
}

export function modeExamplePrompts(mode: AnswerMode): string[] {
  return MODE_EXAMPLES[mode] ?? MODE_EXAMPLES.qa;
}

export function modeQuestionPlaceholder(mode: AnswerMode): string {
  return MODE_PLACEHOLDERS[mode] ?? MODE_PLACEHOLDERS.qa;
}
