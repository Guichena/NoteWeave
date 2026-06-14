# RAG、ES、向量检索、Prompt 幻觉治理加深版

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 通用问题如何转成项目深答
先把通用八股问题落到 NoteWeave 的真实模块，再回答场景、方案、收益、权衡、故障和指标。下面是本主题的项目化深答。

## 1. 本篇定位
大厂面试会重点追问 RAG 是否只是简单调用模型。NoteWeave 的重点是权限、召回、融合、证据后处理、Prompt、防幻觉、Citation 和 Trace。

## 2. 面试先说版
RAG 这块我不会只说“接了向量库”。NoteWeave 的目标是 evidence-first。一次团队问答先经过 Space 权限和 session scope 判断，只在可见知识库内检索。检索层有 BM25、向量和 Wiki recall，并用 Weighted RRF 融合排序，避免单一召回方式漏掉关键词或语义相关内容。之后 EvidencePostProcessor 会对 chunk 做去重、相邻合并、每文档限流和上下文截断，避免 prompt 被重复证据撑爆。TeamRagPromptBuilder 再把证据以受控格式注入，明确文档内容不是系统指令，无证据时返回兜底。最后 CitationService 把回答和证据关系落到 citation/message_citation 等关系里，并保留 page、offset、quoteHash、snapshotObjectKey 这类字段，方便后续审计和二次权限校验。

## 3. 当前真实口径
NoteWeave 的团队问答是 evidence-first：先检索可见证据，再让模型在证据约束下回答，并把引用持久化。

### 已实现
- HybridRetriever、Bm25Retriever、VectorRetriever、WikiRetriever、WeightedReciprocalRankFusion 已在代码中出现。
- EvidencePostProcessor 和 TeamRagPromptBuilder 有单测。
- TeamChatService 负责保存用户消息、检索、构造 prompt、调用 LLM、保存 assistant message 和 citation。
- RetrievalTraceService、LlmCallLogService、AnswerFeedbackService 支持追踪和反馈。

### 设计目标
- HybridRetriever 组合 BM25、向量和 Wiki 召回，WeightedReciprocalRankFusion 做融合，EvidencePostProcessor 做去重、相邻合并、限流和截断，TeamRagPromptBuilder 约束模型只基于证据回答，CitationService 保存可回溯引用。
- 回答质量、可解释性、权限安全和后续 Eval/排障都更强。

### 后续可扩展
- 如果向量召回失败，系统怎么降级？
- 如果 Citation 被用户质疑不准确，先查哪几层？
- Prompt injection 文档内容怎么处理？

## 4. 代码和测试锚点
- src/main/java/com/noteweave/team/rag/retriever/HybridRetriever.java
- src/main/java/com/noteweave/team/rag/retriever/WeightedReciprocalRankFusion.java
- src/main/java/com/noteweave/team/rag/evidence/EvidencePostProcessor.java
- src/main/java/com/noteweave/team/rag/prompt/TeamRagPromptBuilder.java
- src/main/java/com/noteweave/citation/service/CitationService.java
- src/test/java/com/noteweave/chat/Phase9HybridRetrievalIntegrationTest.java

## 5. 必会问题与答题骨架

### Q1: 为什么要做 Hybrid RAG？

回答时按四步走：
1. 先说场景：大厂面试会重点追问 RAG 是否只是简单调用模型。NoteWeave 的重点是权限、召回、融合、证据后处理、Prompt、防幻觉、Citation 和 Trace。
2. 再说方案：HybridRetriever 组合 BM25、向量和 Wiki 召回，WeightedReciprocalRankFusion 做融合，EvidencePostProcessor 做去重、相邻合并、限流和截断，TeamRagPromptBuilder 约束模型只基于证据回答，CitationService 保存可回溯引用。
3. 再说收益：回答质量、可解释性、权限安全和后续 Eval/排障都更强。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> RAG 这块我不会只说“接了向量库”。NoteWeave 的目标是 evidence-first。一次团队问答先经过 Space 权限和 session scope 判断，只在可见知识库内检索。检索层有 BM25、向量和 Wiki recall，并用 Weighted RRF 融合排序，避免单一召回方式漏掉关键词或语义相关内容。之后 EvidencePostProcessor 会对 chunk 做去重、相邻合并、每文档限流和上下文截断，避免 prompt 被重复证据撑爆。TeamRagPromptBuilder 再把证据以受控格式注入，明确文档内容不是系统指令，无证据时返回兜底。最后 CitationService 把回答和证据关系落到 citation/message_citation 等关系里，并保留 page、offset、quoteHash、snapshotObjectKey 这类字段，方便后续审计和二次权限校验。

常见追问：
- 如果向量召回失败，系统怎么降级？
- 如果 Citation 被用户质疑不准确，先查哪几层？
- Prompt injection 文档内容怎么处理？

### Q2: BM25、向量和 Wiki recall 各解决什么问题？

回答时按四步走：
1. 先说场景：大厂面试会重点追问 RAG 是否只是简单调用模型。NoteWeave 的重点是权限、召回、融合、证据后处理、Prompt、防幻觉、Citation 和 Trace。
2. 再说方案：HybridRetriever 组合 BM25、向量和 Wiki 召回，WeightedReciprocalRankFusion 做融合，EvidencePostProcessor 做去重、相邻合并、限流和截断，TeamRagPromptBuilder 约束模型只基于证据回答，CitationService 保存可回溯引用。
3. 再说收益：回答质量、可解释性、权限安全和后续 Eval/排障都更强。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> RAG 这块我不会只说“接了向量库”。NoteWeave 的目标是 evidence-first。一次团队问答先经过 Space 权限和 session scope 判断，只在可见知识库内检索。检索层有 BM25、向量和 Wiki recall，并用 Weighted RRF 融合排序，避免单一召回方式漏掉关键词或语义相关内容。之后 EvidencePostProcessor 会对 chunk 做去重、相邻合并、每文档限流和上下文截断，避免 prompt 被重复证据撑爆。TeamRagPromptBuilder 再把证据以受控格式注入，明确文档内容不是系统指令，无证据时返回兜底。最后 CitationService 把回答和证据关系落到 citation/message_citation 等关系里，并保留 page、offset、quoteHash、snapshotObjectKey 这类字段，方便后续审计和二次权限校验。

常见追问：
- 如果向量召回失败，系统怎么降级？
- 如果 Citation 被用户质疑不准确，先查哪几层？
- Prompt injection 文档内容怎么处理？

### Q3: Weighted RRF 为什么比简单拼接更稳？

回答时按四步走：
1. 先说场景：大厂面试会重点追问 RAG 是否只是简单调用模型。NoteWeave 的重点是权限、召回、融合、证据后处理、Prompt、防幻觉、Citation 和 Trace。
2. 再说方案：HybridRetriever 组合 BM25、向量和 Wiki 召回，WeightedReciprocalRankFusion 做融合，EvidencePostProcessor 做去重、相邻合并、限流和截断，TeamRagPromptBuilder 约束模型只基于证据回答，CitationService 保存可回溯引用。
3. 再说收益：回答质量、可解释性、权限安全和后续 Eval/排障都更强。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> RAG 这块我不会只说“接了向量库”。NoteWeave 的目标是 evidence-first。一次团队问答先经过 Space 权限和 session scope 判断，只在可见知识库内检索。检索层有 BM25、向量和 Wiki recall，并用 Weighted RRF 融合排序，避免单一召回方式漏掉关键词或语义相关内容。之后 EvidencePostProcessor 会对 chunk 做去重、相邻合并、每文档限流和上下文截断，避免 prompt 被重复证据撑爆。TeamRagPromptBuilder 再把证据以受控格式注入，明确文档内容不是系统指令，无证据时返回兜底。最后 CitationService 把回答和证据关系落到 citation/message_citation 等关系里，并保留 page、offset、quoteHash、snapshotObjectKey 这类字段，方便后续审计和二次权限校验。

常见追问：
- 如果向量召回失败，系统怎么降级？
- 如果 Citation 被用户质疑不准确，先查哪几层？
- Prompt injection 文档内容怎么处理？

### Q4: Citation 为什么不直接存在 message JSON？

回答时按四步走：
1. 先说场景：大厂面试会重点追问 RAG 是否只是简单调用模型。NoteWeave 的重点是权限、召回、融合、证据后处理、Prompt、防幻觉、Citation 和 Trace。
2. 再说方案：HybridRetriever 组合 BM25、向量和 Wiki 召回，WeightedReciprocalRankFusion 做融合，EvidencePostProcessor 做去重、相邻合并、限流和截断，TeamRagPromptBuilder 约束模型只基于证据回答，CitationService 保存可回溯引用。
3. 再说收益：回答质量、可解释性、权限安全和后续 Eval/排障都更强。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> RAG 这块我不会只说“接了向量库”。NoteWeave 的目标是 evidence-first。一次团队问答先经过 Space 权限和 session scope 判断，只在可见知识库内检索。检索层有 BM25、向量和 Wiki recall，并用 Weighted RRF 融合排序，避免单一召回方式漏掉关键词或语义相关内容。之后 EvidencePostProcessor 会对 chunk 做去重、相邻合并、每文档限流和上下文截断，避免 prompt 被重复证据撑爆。TeamRagPromptBuilder 再把证据以受控格式注入，明确文档内容不是系统指令，无证据时返回兜底。最后 CitationService 把回答和证据关系落到 citation/message_citation 等关系里，并保留 page、offset、quoteHash、snapshotObjectKey 这类字段，方便后续审计和二次权限校验。

常见追问：
- 如果向量召回失败，系统怎么降级？
- 如果 Citation 被用户质疑不准确，先查哪几层？
- Prompt injection 文档内容怎么处理？

### Q5: 没有证据时为什么要明确兜底？

回答时按四步走：
1. 先说场景：大厂面试会重点追问 RAG 是否只是简单调用模型。NoteWeave 的重点是权限、召回、融合、证据后处理、Prompt、防幻觉、Citation 和 Trace。
2. 再说方案：HybridRetriever 组合 BM25、向量和 Wiki 召回，WeightedReciprocalRankFusion 做融合，EvidencePostProcessor 做去重、相邻合并、限流和截断，TeamRagPromptBuilder 约束模型只基于证据回答，CitationService 保存可回溯引用。
3. 再说收益：回答质量、可解释性、权限安全和后续 Eval/排障都更强。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> RAG 这块我不会只说“接了向量库”。NoteWeave 的目标是 evidence-first。一次团队问答先经过 Space 权限和 session scope 判断，只在可见知识库内检索。检索层有 BM25、向量和 Wiki recall，并用 Weighted RRF 融合排序，避免单一召回方式漏掉关键词或语义相关内容。之后 EvidencePostProcessor 会对 chunk 做去重、相邻合并、每文档限流和上下文截断，避免 prompt 被重复证据撑爆。TeamRagPromptBuilder 再把证据以受控格式注入，明确文档内容不是系统指令，无证据时返回兜底。最后 CitationService 把回答和证据关系落到 citation/message_citation 等关系里，并保留 page、offset、quoteHash、snapshotObjectKey 这类字段，方便后续审计和二次权限校验。

常见追问：
- 如果向量召回失败，系统怎么降级？
- 如果 Citation 被用户质疑不准确，先查哪几层？
- Prompt injection 文档内容怎么处理？

## 6. 大厂深挖追问路径
1. 先问你做了什么。
2. 再问为什么这样设计，不用更简单方案。
3. 再问失败、重试、越权、删除、断线、重建索引时会发生什么。
4. 最后问如何量化效果和下一步演进。

把答案往下压一层：
- 业务层：大厂面试会重点追问 RAG 是否只是简单调用模型。NoteWeave 的重点是权限、召回、融合、证据后处理、Prompt、防幻觉、Citation 和 Trace。
- 架构层：HybridRetriever 组合 BM25、向量和 Wiki 召回，WeightedReciprocalRankFusion 做融合，EvidencePostProcessor 做去重、相邻合并、限流和截断，TeamRagPromptBuilder 约束模型只基于证据回答，CitationService 保存可回溯引用。
- 数据层：引用 MySQL、Redis、MinIO、ES、Kafka 或 Citation/Trace 的真实职责。
- 测试层：能说出对应 IntegrationTest 或 ServiceTest。
- 边界层：明确哪些是后续扩展，不冒充已落地。

## 7. 不能说满的地方
- 不要把 Hybrid RAG 说成 GraphRAG 主链路。
- 不要编造 recall@k 或准确率。
- 不要说 Citation 只靠模型输出的引用编号。

## 8. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。
