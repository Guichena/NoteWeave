// 三种回答模式对应三条不同的检索链路：strategy 是回答上展示的简短说明，description 用于模式切换提示。
export const routes = [
  {
    key: "qa",
    label: "问答",
    strategy: "混合检索",
    description: "关键词与语义混合检索，融合排序并重排后回答，适合快速查找事实。"
  },
  {
    key: "note",
    label: "精读",
    strategy: "原文窗口",
    description: "先定位相关资料，再读取连续的原文段落，适合深入理解一份资料。"
  },
  {
    key: "wiki",
    label: "Wiki",
    strategy: "知识网络",
    description: "沿知识库页面与关联关系回答，并回链到原始资料。"
  }
] as const;

export type AnswerMode = (typeof routes)[number]["key"];
