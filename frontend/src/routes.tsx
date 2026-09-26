export const routes = [
  { key: "qa", label: "问答 RAG", description: "基于当前研究工作台资料回答，并返回引用。" },
  { key: "note", label: "Note", description: "沿笔记线索逐层检索：候选资料、关系扩展、原文窗口与摘录证据。" },
  { key: "wiki", label: "Wiki", description: "沿知识网络检索页面、索引、链接、图谱、反向链接与来源回链。" }
] as const;

export type AnswerMode = (typeof routes)[number]["key"];
