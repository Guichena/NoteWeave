# 05 Hybrid RAG 与 Citation 证据链

## 0. 本篇定位

这条链路回答：NoteWeave 如何把团队问答做成 evidence-first，而不是普通“检索几段文本问模型”的 demo。

核心链路：

```text
ChatSession scope
-> permission filter
-> BM25 / Vector / Wiki recall
-> Weighted RRF
-> EvidencePostProcessor
-> TeamRagPromptBuilder
-> LLM
-> ChatMessage
-> Citation / message_citation
-> RetrievalTrace / LLMCallLog / AnswerFeedback
```

## 面试先说版

这条链路我会从“RAG 怎么从 demo 变成可信问答系统”讲。普通 demo 往往只做向量召回，但团队知识库里很多问题是专有名词、版本号、接口名、错误码和术语匹配，单向量不稳定；只用 BM25 又缺少语义泛化。所以 NoteWeave 用 Hybrid RAG，把 BM25、向量召回和 Wiki recall 组合起来。

召回之后也不是直接把文本塞进 Prompt，而是先做融合和证据治理。不同检索器的分数不可比，所以用 RRF 这种基于排名的融合思路；再做去重、相邻 chunk 合并、同文档限流、低分过滤和上下文截断。回答后还会保存 Citation、RetrievalTrace 和 LLMCallLog。

这条链路能带出的八股是：BM25 vs 向量检索、Hybrid Search、RRF、chunk 粒度、token budget、grounding、citation、prompt injection 防护、RAG Eval。核心口径是 evidence-first，不是“模型觉得像就答”。

## Q1：团队侧 RAG 的完整处理流程是什么？

**答：**

用户在 TEAM_CHAT 会话里提问后，系统先保存 USER message，再根据 ChatSession 的 scope 解析可检索范围，比如某些 KnowledgeBase。然后 HybridRetriever 在权限过滤下进行召回，EvidencePostProcessor 做去重、相邻 chunk 合并、低分过滤、同文档限流和长度截断。

如果没有证据，系统返回明确的无证据兜底，不编造答案。如果有证据，TeamRagPromptBuilder 会把证据编号后放进 Prompt，并加入 grounded-answer 和 prompt injection 防护规则。LLM 返回后，系统保存 ASSISTANT message，再把引用写入 `citation` 和 `message_citation`，同时记录 RetrievalTrace 和 LLMCallLog。

## Q2：Hybrid RAG 具体 hybrid 在哪里？

**答：**

NoteWeave 的 hybrid 分成召回、融合和后处理三层。

召回层包括 BM25、向量召回和 Wiki recall。BM25 适合关键词和精确匹配，向量召回适合语义相似，Wiki recall 适合已经沉淀的团队长期知识。

融合层使用 Weighted RRF。它不直接比较不同召回器的原始分数，因为 BM25 分数和向量相似度分布不一样，而是根据各召回列表里的排名做 reciprocal rank 融合，再加权。

后处理层通过 EvidencePostProcessor 控制证据质量，比如去重、相邻 chunk 合并、同文档限流、低分过滤和上下文截断。

## Q3：为什么 Citation 不直接放在 message JSON 里？

**答：**

因为 Citation 是正式证据关系，不是展示字段。

如果只把 citationIds 或引用片段塞进 message JSON，会有几个问题。第一，权限不好二次校验。第二，引用无法跨 Message、Artifact、Card、Wiki 复用。第三，文档重处理后无法记录 quoteHash、sourceVersion、snapshotObjectKey 等防漂移信息。第四，后续做 Trace、Eval、Admin 查询时很难关联。

所以 NoteWeave 把 `citation` 作为独立表，Message、Artifact、ArticleCard、ConceptCard、SynthesisCard、WikiPage 都通过关联表绑定 Citation。

## Q4：如何防止模型幻觉？

**答：**

不是靠一句 Prompt，而是链路约束。

第一，检索范围受权限和 session scope 限制。第二，EvidencePostProcessor 提高证据质量。第三，PromptBuilder 明确要求基于证据回答，证据不足时说明无法确认。第四，回答后保存 Citation 和 RetrievalTrace。第五，RAG Eval 可以用 case 检查 recall@k、MRR、citationCoverage 和 latency。

## Q5：线上出现一条坏回答，怎么排查？

**答：**

先看 ChatMessage 和 AnswerFeedback，确认用户问题、回答内容和反馈原因。再看 RetrievalTrace，确认实际检索了哪些 chunk、分数、是否 selectedAsEvidence。然后看 Citation，确认回答引用是否真实存在、是否越权、是否引用旧版本。再看 LLMCallLog，确认 PromptVersion、模型、token、latency、是否调用成功。最后用 RagEvalCase 复现并跑 Eval。

## 实现兜底锚点

- `TeamChatService`
- `HybridRetriever`
- `Bm25Retriever`
- `VectorRetriever`
- `WikiRetriever`
- `WeightedReciprocalRankFusion`
- `EvidencePostProcessor`
- `TeamRagPromptBuilder`
- `CitationService`
- `RetrievalTraceService`
- `Phase4TeamRagIntegrationTest`
- `Phase9HybridRetrievalIntegrationTest`

## 3 到 5 分钟深答模板

> NoteWeave 的团队 RAG 是一条 evidence-first 链路，不是“检索几段文本然后问模型”这么简单。用户提问后，系统先做会话和空间权限校验，再根据 session scope 确定可检索范围。召回层同时走 BM25、向量召回和 Wiki recall，通过 weighted RRF 融合排序，而不是直接比较不同检索器的原始分数。融合后的候选结果还要经过证据后处理，做去重、相邻 chunk 合并、低分过滤和同文档限流，最后才把治理后的 evidence pack 交给 Prompt。回答完成后还会保存 Citation、RetrievalTrace 和 LLMCallLog，所以不仅能回答“引用了什么”，还能反查“为什么会召回这些证据”。这条链路真正解决的是权限、证据质量、可解释和排障一起成立的问题。

## 常见追问继续怎么接

- 如果继续追问“最容易出问题的是哪层”，可以答：通常不是模型本身，而是召回范围、证据治理和引用落库三层衔接出了偏差。
- 如果继续追问“怎么证明不是只靠 Prompt”，可以答：权限过滤、Hybrid recall、evidence post-process、Citation 和 Trace 都是链路约束，不是单点提示词技巧。
- 如果继续追问“坏回答先查哪里”，可以答：优先查 `RetrievalTrace` 和 `Citation`，因为先要确认证据有没有找对、引用有没有落对。

## 边界和不能说满的地方

- 可以坚定讲：Hybrid RAG、Citation、RetrievalTrace、无证据兜底、RAG Eval 闭环。
- 不要讲成：Citation 等于答案绝对正确；GraphRAG 已是主链路；当前已经把 query rewrite、复杂 planner、开放 Agent 都做满。
