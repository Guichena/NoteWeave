import type { AnswerEvidence } from "../../features/answers/evidence";
import type { ConversationMessage } from "../../features/conversations/model";

// 对话页设计数据：工作台资料、一段覆盖问答 / 精读 / Wiki 三种模式的历史对话，以及每条回答的证据清单。

const now = Date.now();
const minutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString();

// 资料列表与资料库页面共用，定义见 sources.ts。
export { sources } from "./sources";

type Turn = {
  mode: "QA" | "NOTE" | "WIKI";
  question: string;
  answer: string;
  runId: string;
  evidence: Array<Omit<AnswerEvidence, "rank" | "character_cost">>;
};

const turns: Turn[] = [
  {
    mode: "QA",
    question: "RRF 融合排序为什么不需要调参？",
    runId: "answer-qa",
    answer: [
      "RRF 只使用每路检索结果中的**名次**，而不是原始分数：每条结果的得分是各路 1 / (k + 名次) 之和 [1]。",
      "",
      "BM25 与向量相似度的分数量纲不同，直接加权需要反复调权重；RRF 不依赖分数分布，因此两路结果可以直接融合，常数 k 取 60 在大多数场景下都稳定 [2]。",
      "",
      "在本工作台的链路里，RRF 融合后的前 80 条候选还会交给 Rerank 模型重新打分，再截取最相关的证据进入回答 [1][3]。"
    ].join("\n"),
    evidence: [
      { evidence_id: "passage:rag-12", kind: "PASSAGE", source_id: "src-rag", passage_id: "rag-12", knowledge_item_id: null, title: "RAG 检索链路设计.md", excerpt: "混合检索分两路召回：BM25 取前 50，向量取前 50；融合使用 RRF，score = Σ 1 / (60 + rank)。", location: "第 3 节 · 融合排序" },
      { evidence_id: "passage:bench-4", kind: "PASSAGE", source_id: "src-bench", passage_id: "bench-4", knowledge_item_id: null, title: "向量数据库压测记录.pdf", excerpt: "对比加权求和与 RRF：加权方案在三个数据集上需要分别调参，RRF 使用固定 k = 60，NDCG@10 差异小于 1%。", location: "第 7 页" },
      { evidence_id: "passage:rag-15", kind: "PASSAGE", source_id: "src-rag", passage_id: "rag-15", knowledge_item_id: null, title: "RAG 检索链路设计.md", excerpt: "融合后的前 80 条候选交给 Rerank 重新打分，最终按字符预算截取证据。", location: "第 4 节 · 重排与截断" }
    ]
  },
  {
    mode: "NOTE",
    question: "精读《RAG 检索链路设计》，它是怎么处理长文档上下文的？",
    runId: "answer-note",
    answer: [
      "这份资料的做法是**先定位、再连续阅读**：先在资料级召回里找到相关章节，再按原文顺序读取相邻窗口，避免只拿到零散片段 [1]。",
      "",
      "## 资料定位",
      "- RAG 检索链路设计.md：第 5 节「长文档处理」与问题最相关",
      "- 向量数据库压测记录.pdf：只提到窗口大小对召回率的影响，作为补充",
      "",
      "## 深读窗口",
      "- 窗口 12 → 14：长文档切片与窗口拼接规则",
      "- 窗口 15：窗口预算与截断策略",
      "",
      "## 摘录证据",
      "- 每个窗口约 800 字，读取时向前后各扩展一个窗口，保证论证完整 [1]",
      "- 超出预算时优先保留锚点窗口，其次保留后续窗口 [2]"
    ].join("\n"),
    evidence: [
      { evidence_id: "note-window:rag-20:12", kind: "PASSAGE", source_id: "src-rag", passage_id: "rag-20", knowledge_item_id: null, title: "RAG 检索链路设计.md", excerpt: "长文档按约 800 字切成连续窗口；命中锚点窗口后，向前后各扩展一个窗口，一起交给模型阅读。", location: "第 5 节 · 窗口 12-14" },
      { evidence_id: "note-window:rag-21:15", kind: "PASSAGE", source_id: "src-rag", passage_id: "rag-21", knowledge_item_id: null, title: "RAG 检索链路设计.md", excerpt: "当窗口总长度超过预算，保留锚点窗口，其次保留后续窗口，前序窗口最先截断。", location: "第 5 节 · 窗口 15" }
    ]
  },
  {
    mode: "WIKI",
    question: "知识库里关于混合检索的页面有哪些？它们之间是什么关系？",
    runId: "answer-wiki",
    answer: [
      "知识库中与混合检索直接相关的页面有三个，以 [[混合检索]] 为中心 [1]：",
      "",
      "- [[混合检索]] 定义了 BM25 与向量两路召回，并链接到融合方法 [1]",
      "- [[RRF 融合排序]] 说明按名次融合的公式与参数选择，被 [[混合检索]] 引用 [2]",
      "- [[Rerank 重排]] 描述融合后的精排步骤，与 [[RRF 融合排序]] 相互链接 [3]",
      "",
      "三个页面的来源都回链到《RAG 检索链路设计》，其中 [[RRF 融合排序]] 还引用了压测记录中的对比数据。"
    ].join("\n"),
    evidence: [
      { evidence_id: "knowledge-version:hybrid-v3", kind: "KNOWLEDGE_VERSION", source_id: null, passage_id: null, knowledge_item_id: "hybrid", title: "混合检索", excerpt: "混合检索同时使用 BM25 关键词召回与向量语义召回，两路结果经 RRF 融合后进入重排。", location: "knowledge-version:hybrid-v3" },
      { evidence_id: "knowledge-version:rrf-v2", kind: "KNOWLEDGE_VERSION", source_id: null, passage_id: null, knowledge_item_id: "rrf", title: "RRF 融合排序", excerpt: "RRF 按名次融合多路结果：score = Σ 1 / (k + rank)，k 通常取 60。", location: "knowledge-version:rrf-v2" },
      { evidence_id: "knowledge-version:rerank-v1", kind: "KNOWLEDGE_VERSION", source_id: null, passage_id: null, knowledge_item_id: "rerank", title: "Rerank 重排", excerpt: "Rerank 使用交叉编码模型对候选重新打分，只处理融合后的前 80 条。", location: "knowledge-version:rerank-v1" }
    ]
  }
];

export const messages: ConversationMessage[] = turns.flatMap((turn, index) => {
  const seq = index * 2 + 1;
  const askedAt = minutesAgo(30 - index * 8);
  return [
    {
      message_id: `msg-${seq}`, message_seq: seq, role: "USER", requested_turn_mode: turn.mode,
      content: turn.question, reply_to_message_id: null, context_status: "CURRENT", content_hash: null, created_at: askedAt
    },
    {
      message_id: `msg-${seq + 1}`, message_seq: seq + 1, role: "ASSISTANT", requested_turn_mode: turn.mode,
      content: turn.answer, reply_to_message_id: `msg-${seq}`, context_status: "CURRENT", content_hash: null,
      answer_status: "SUCCEEDED", created_at: askedAt, answer_run_id: turn.runId,
      citations: turn.evidence.map((item) => `${item.title} | ${item.excerpt}`)
    }
  ];
});

export const evidenceByRun: Record<string, AnswerEvidence[]> = Object.fromEntries(
  turns.map((turn) => [turn.runId, turn.evidence.map((item, index) => ({
    ...item,
    rank: index + 1,
    character_cost: item.excerpt.length
  }))])
);
