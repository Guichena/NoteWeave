export const routes = [
  { key: "qa", label: "问答 RAG", description: "基于当前研究工作台资料回答，并返回引用。" },
  { key: "note", label: "Note", description: "Marginalia 式结构化阅读漏斗：候选资料、原文窗口、摘录卡片、结构化笔记。" },
  { key: "wiki", label: "Wiki", description: "WeKnora 式 Wiki-first 链路：优先读取 Wiki 页面，并保留默认 Wiki 工作台入口。" }
] as const;

export type AnswerMode = (typeof routes)[number]["key"];
