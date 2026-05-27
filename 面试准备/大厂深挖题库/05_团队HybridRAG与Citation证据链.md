# 文件：05_团队HybridRAG与Citation证据链.md

## 0. 本篇定位

这篇负责团队侧 Hybrid RAG、Citation 和证据链的深挖。它适合回答 BM25、向量召回、Wiki recall、RRF、EvidencePostProcessor、无证据兜底、Citation 关系化建模，以及坏回答如何排查等问题。

## 1. 本主题面试官想考什么

这个主题是 NoteWeave 最容易被深挖的 AI 工程主线。面试官会重点看你是否理解 RAG 的召回、融合、证据处理、prompt 构造、无证据兜底、Citation 持久化、Trace 排障，以及为什么这不是简单“拼几个 retriever”。

## 2. 高频问题清单

### 基础问题

- 团队 RAG 问答链路怎么走？
- BM25、Vector、Wiki recall 分别解决什么问题？
- 什么是 RRF？为什么要做 weighted RRF？
- Citation 保存了哪些信息？

### 进阶问题

- 为什么用 Elasticsearch，而不是只用 MySQL 或只用向量库？
- `EvidencePostProcessor` 为什么必要？
- 如果向量召回失败，系统怎么降级？
- 无证据时为什么不能让模型自由发挥？
- RetrievalTrace 和 Citation 分别解决什么问题？

### 深挖追问

- RRF 为什么比直接归一化分数更稳？
- 相邻 chunk 合并有什么收益和风险？
- 同文档限流为什么必要？
- Citation 为什么要关系表，而不是 message JSON？
- Prompt injection 怎么防？

### 压力追问

- 如果用户质疑 citation 不准，你怎么排查？
- 如果召回全是噪声，怎么优化？
- 如果文档量上升，检索瓶颈在哪里？
- 如果多语言、同义词、长问题召回差，怎么改？

## 3. 问答与讲解

### Q1：团队 RAG 问答完整链路是什么？

#### 面试官为什么问

面试官想确认你是否真正理解端到端，而不是只知道“检索后问 LLM”。

#### 回答思路

按 `TeamChatService` 的链路讲：权限校验、保存用户消息、上下文读取计划、混合检索、证据后处理、trace、无证据兜底或构造 prompt、LLM 调用、保存助手消息、保存 Citation、Memory writeback。

#### 结合我的项目怎么答

`TeamChatService.ask` 会要求 FORMAL 团队会话，并调用 `requireAskQuestion` 校验权限；根据 session scope 解析可见知识库；用 `HybridRetriever` 检索；用 `EvidencePostProcessor` 去重、过滤、合并相邻 chunk、限制每文档证据数和上下文长度；保存 RetrievalTrace；如果没有 evidence 返回明确兜底；否则用 `TeamRagPromptBuilder` 构造 grounded prompt，经 `ObservedLlmGateway` 调用模型，再保存 assistant message 和 citations。

#### 技术原理 / 链路设计讲解

RAG 的质量不只取决于向量召回。链路上每层都有作用：

- 权限层：控制用户能问哪些空间和知识库。
- 召回层：BM25、Vector、Wiki 多路召回。
- 融合层：Weighted RRF 合并不同召回排名。
- 后处理层：去重、相邻合并、同文档限流、上下文裁剪。
- 生成层：把证据编号和规则注入 prompt。
- 审计层：Citation、RetrievalTrace、LLMCallLog。

#### 技术栈特点与选型理由

Elasticsearch 既可以做 BM25，也可以支持向量字段和结构化过滤，适合作为检索中枢。MySQL 做权限与状态复核。LLM 调用通过 `ObservedLlmGateway` 统一记录日志和 prompt version。

#### 可直接复述的面试回答

团队 RAG 链路从权限开始，不是直接调用模型。用户提问时先校验 session 和 space 的提问权限，然后根据 session scope 解析可见知识库。检索层用 `HybridRetriever` 做 BM25、向量和 Wiki recall，再用 weighted RRF 融合。融合后的结果进入 `EvidencePostProcessor`，做 chunk 去重、相邻 chunk 合并、同文档限流、低分过滤和上下文长度控制。之后保存 RetrievalTrace。如果没有证据，就返回明确的无证据兜底；如果有证据，就用 `TeamRagPromptBuilder` 构造 grounded prompt 调 LLM。最后保存 assistant message、Citation 和 LLM 日志，FORMAL 会话再触发长期记忆写回。

#### 常见追问

- session scope 怎么影响检索？
- 为什么先保存 user message？
- Memory 会不会污染 RAG 证据？

#### 常见坑

不要把 RAG 简化成“向量检索 + prompt”。真实工程里权限、融合、后处理、trace、citation 和兜底同样重要。

---

### Q2：BM25、Vector、Wiki recall 和 Weighted RRF 分别解决什么问题？

#### 面试官为什么问

面试官想判断你是否理解混合检索的必要性和融合方法，而不是把“Hybrid RAG”当简历关键词。

#### 回答思路

说明 BM25 擅长关键词精确匹配，向量擅长语义近似，Wiki recall 擅长已沉淀知识，RRF 用排名融合降低不同分数尺度不可比的问题。

#### 结合我的项目怎么答

`HybridRetriever` 总是先跑 BM25。若模式不是纯 BM25，再尝试 `VectorRetriever` 和 `WikiRetriever`；向量失败时标记 fallbackUsed，并退回 BM25；最后通过 `WeightedReciprocalRankFusion` 按 BM25、Vector、Wiki 权重融合。

#### 技术原理 / 链路设计讲解

BM25 基于词项频率、逆文档频率和文档长度归一，适合术语、编号、人名、错误码。向量召回基于 embedding 相似度，适合语义改写和模糊问题。Wiki recall 代表人工确认后的高质量知识。不同 retriever 的分数不可直接相加，RRF 用排名位置而不是原始分数做融合，更稳定；weighted RRF 可以表达不同召回源的可信度。

#### 技术栈特点与选型理由

Elasticsearch 的优势是可以把关键词、向量和 filter 放在同一搜索基础设施里，避免维护过多检索系统。代价是要处理 embedding 维度版本、alias、回填任务和降级策略。

#### 可直接复述的面试回答

BM25、向量和 Wiki recall 解决的问题不一样。BM25 对关键词、术语、编号、错误码很稳；向量对语义相似和问法变化更友好；Wiki recall 更偏向已经人工沉淀的稳定知识。难点是三路召回的分数尺度不一样，不能简单相加，所以我用了 weighted RRF，用排名位置融合，并给 BM25、Vector、Wiki 配不同权重。这样即使向量服务失败，也可以 fallback 到 BM25，不会让问答链路整体不可用。

#### 常见追问

- RRF 的 K 值怎么调？
- 权重怎么确定？
- 向量失败为什么不能直接失败？

#### 常见坑

不要声称当前已经有生产验证的 recall@k 数字，除非你有真实评测数据。可以说项目有 Eval 和 Trace 能力，后续会用 recall@k、MRR、citation coverage 验证。

---

### Q3：为什么 Citation 要持久化成关系，而不是放在 message JSON？

#### 面试官为什么问

这是证据审计和数据建模题。面试官想看你是否理解 AI 结果的可追踪性。

#### 回答思路

讲 message JSON 的问题：难查询、难权限复核、难关联 Artifact/Card/Wiki、难做版本和快照。关系表的收益是统一证据模型。

#### 结合我的项目怎么答

项目中回答的 Citation 不是只存在 ChatMessage content 里，而是通过 `citation`、`message_citation` 等关系持久化。Artifact、Card、Synthesis、Wiki 也有各自的 citation/relations，这让证据能跨 Message、Artifact、Card、Wiki 复用和审计。

#### 技术原理 / 链路设计讲解

Citation 至少要记录 sourceType、sourceId、chunkId、pageNo、startOffset、endOffset、quoteHash、snapshotObjectKey、sourceVersion 等信息。这样可以回答“这句话来自哪里、当时引用的是哪个版本、证据片段是什么、现在用户还有没有权限看”。

#### 技术栈特点与选型理由

MySQL 关系表适合做 evidence 的权威记录；MinIO 存 snapshot；ES 只负责召回，不负责证据权威。JSON 可以做缓存展示，但不能作为唯一证据来源。

#### 可直接复述的面试回答

Citation 我没有放在 message JSON 里，因为它不是一个展示字段，而是证据关系。JSON 方案很难做权限二次校验，也很难追踪 source version、snapshot、chunk、page offset，更难复用到 Artifact、Card、Wiki。关系表方案可以保存 citation 本身，再用 message_citation、artifact_citation、card_citation 这类关系绑定不同产物。这样当用户查看引用、排查坏回答、做评测或权限变化时，都能基于正式关系去查，而不是解析一段历史 JSON。

#### 常见追问

- Citation 快照和原文不一致怎么办？
- 如果文档更新了，旧 citation 还有效吗？
- Citation 是否会暴露敏感原文？

#### 常见坑

不要说“JSON 查起来也可以”。面试官会继续追问权限、版本、审计和跨产物复用，JSON 很快撑不住。

---

## 4. 本主题总结

RAG 主题要突出 evidence-first：权限约束下召回，多路融合，证据后处理，prompt grounding，无证据兜底，Citation 关系持久化，Trace 和 LLM 日志可排障。

## 5. 面试前自查清单

- 我是否能讲出 TeamChatService 的端到端链路？
- 我是否能解释 BM25、Vector、Wiki recall 的差异？
- 我是否能说明 RRF 为什么比直接分数相加更稳？
- 我是否能解释 EvidencePostProcessor 的每一步作用？
- 我是否能回答 Citation 和 RetrievalTrace 的区别？

## 6. 整条链路深讲：一次团队 RAG 问答如何生成可信回答

第一步是会话和权限。`TeamChatService.ask` 先确认 session 是 TEAM_CHAT 且是 FORMAL，再校验用户对 session.spaceId 有提问权限。DRAFT 会话不走 HTTP 问答，而走 WebSocket Runtime。

第二步是保存用户消息。系统先保存 USER ChatMessage，这样后续 RetrievalTrace、LLMCallLog、Citation 都能挂到明确的 messageId 上。

第三步是解析检索范围。session 可以是整个 Space，也可以限定 KnowledgeBase scope。服务端会从 DB 中取 ACTIVE KnowledgeBase，避免前端传入越权或已归档范围。

第四步是混合召回。`HybridRetriever` 先跑 BM25；如果配置为 hybrid，再尝试 VectorRetriever 和 WikiRetriever。向量召回失败不会让整条链路失败，而是 fallback 到 BM25，并记录 fallbackUsed。

第五步是 RRF 融合。不同 retriever 的分数尺度不一致，BM25 分数、向量相似度、Wiki 得分不能直接相加。Weighted RRF 基于排名融合，用权重表达召回源可信度，用 rrfK 控制排名衰减。

第六步是证据后处理。`EvidencePostProcessor` 对 chunk 去重、低分过滤、按 score 排序、同文档限流、相邻 chunk 合并、上下文长度裁剪，最终输出带 citationIndex 的 EvidenceItem。

第七步是保存 RetrievalTrace。Trace 记录 query、retrieverType、topK、latency、bm25Count、vectorCount、fusionCount、fallbackUsed、traceJson 和 trace items。它回答的是“当时怎么召回的”。

第八步是生成回答。如果 evidence 为空，系统返回明确无证据兜底，不让模型自由发挥；如果 evidence 不为空，`TeamRagPromptBuilder` 把证据编号、引用规则和防 prompt injection 规则注入 prompt，再通过 `ObservedLlmGateway` 调用 LLM。

第九步是保存 Citation。生成完成后保存 ASSISTANT message，并通过 `CitationService.saveForAssistantMessage` 把 evidence 持久化为 citation 和 message_citation。Citation 回答的是“这句话引用了什么证据”。

第十步是记忆写回。FORMAL 会话会触发 MemoryWriteback，但 DRAFT 不写。记忆是辅助上下文，不替代 evidence。

## 7. 原理与设计原因速查

- 为什么先保存 user message：后续 trace、LLM log、citation、feedback 都需要稳定关联点。
- 为什么多路召回：BM25 擅长精确词，Vector 擅长语义，Wiki 擅长已确认知识。
- 为什么 RRF 而不是分数相加：不同检索器分数不可比，排名融合更稳。
- 为什么后处理要同文档限流：避免一个长文档占满上下文，提升证据多样性。
- 为什么相邻 chunk 合并：单个 chunk 可能语义不完整，合并邻接片段能改善上下文连贯性。
- 为什么无证据兜底：没有 evidence 就不应该让模型编造 citation。
- 为什么 Citation 和 Trace 都要有：Trace 解释召回过程，Citation 解释最终答案证据。

## 8. 3 到 5 分钟深答模板

团队 RAG 我会按 evidence-first 链路讲。用户提问后，系统先校验 TEAM_CHAT FORMAL session 和提问权限，然后保存用户消息，解析 session scope 得到可见知识库。检索层使用 HybridRetriever，BM25 是基础召回，hybrid 模式下再跑向量召回和 Wiki recall；向量失败会 fallback，不让生成链路整体失败。多路召回后用 weighted RRF 做排名融合，因为不同检索器的原始分数不可比。融合结果进入 EvidencePostProcessor，做 chunk 去重、低分过滤、相邻 chunk 合并、同文档限流和上下文长度控制。然后保存 RetrievalTrace，便于之后排查召回质量。如果 evidence 为空，直接返回无证据兜底；如果有 evidence，再用 TeamRagPromptBuilder 构造带证据编号和引用规则的 grounded prompt 调 LLM。最终 assistant message 和 Citation 会持久化，Citation 不是 JSON 展示字段，而是正式关系，查询时还要二次权限校验。这样一条回答既有生成结果，也能反查到召回过程和具体证据。

## 9. 边界和不能说满的地方

- 可以坚定讲：Hybrid RAG、EvidencePostProcessor、Citation、RetrievalTrace、无证据兜底和 RAG Eval。
- 不要讲成：Citation 等于答案绝对正确；GraphRAG 已是当前主链路；query rewrite、复杂 rerank 和开放 Agent 都已经在主流程落地。
