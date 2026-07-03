export const routes = [
  { key: "qa", label: "问答 RAG", description: "基于当前研究工作台资料回答，并返回引用。" },
  { key: "note", label: "Note", description: "Marginalia 式结构化检索漏斗：Journal 信号、候选资料、关系扩展、原文窗口和摘录卡片。" },
  { key: "wiki", label: "Wiki", description: "WebKonra / WeKnora 式全量 Wiki 检索：页面、索引、链接、图谱、反向链接和来源回链。" }
] as const;

export type AnswerMode = (typeof routes)[number]["key"];
