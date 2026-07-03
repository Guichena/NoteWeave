export const routes = [
  { key: "qa", label: "问答 RAG", description: "基于当前研究工作台资料回答，并返回引用。" },
  { key: "note", label: "Note", description: "后续接入 Marginalia 式结构化检索漏斗。" },
  { key: "wiki", label: "Wiki", description: "后续接入 Wiki 页面生成与关系展示。" }
] as const;

export type AnswerMode = (typeof routes)[number]["key"];
