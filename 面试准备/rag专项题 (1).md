# NoteWeave RAG 专项题

## 0. 本篇定位

这篇用于准备 RAG 专项面试。重点不是背“RAG 是检索增强生成”，而是把 RAG 的通用原理落到 NoteWeave 的已验收链路：权限、文档治理、混合检索、证据后处理、Citation、Trace、Eval 和无证据兜底。

## 1. 这个主题在 NoteWeave 里解决什么问题

团队知识问答的难点从来不只是“能不能把文档搜出来”，而是下面这些条件要同时成立：

- 召回范围不能越权。
- 关键词和语义都要兼顾。
- 文档更新后旧索引不能污染结果。
- 给模型的证据要可控，不是 top-k 直接拼。
- 回答要能回到 source、chunk、version 和 trace。
- 坏回答出现后要能定位是数据、检索、证据还是生成出了问题。

## 2. 本主题面试官想考什么

RAG 专项通常会考六件事：

1. 你是否知道朴素 RAG 的问题。
2. 你是否理解 BM25、向量、Wiki recall、RRF、证据后处理各自职责。
3. 你是否能讲清权限和元数据过滤为什么必须前置。
4. 你是否能把 Citation、Trace、Eval 讲成工程闭环。
5. 你是否能说明无证据时为什么不能让模型自由发挥。
6. 你是否不会把 GraphRAG、MCP、Agent、生产指标说过头。

## 3. 核心链路

NoteWeave 团队 RAG 主链路可以按下面顺序讲：

```text
上传分片
-> merge 到对象存储
-> DOCUMENT_PROCESS 异步解析
-> parsed text / chunk / ES 索引 / activeIndexVersion
-> chat session scope 与权限校验
-> BM25 + 向量召回 + Wiki recall
-> Weighted RRF 融合
-> EvidencePostProcessor 证据治理
-> TeamRagPromptBuilder 构造 grounded prompt
-> LLM 回答
-> Citation / RetrievalTrace / LLMCallLog 落库
-> WebSocket runtime 与 Memory 写回策略
```

## 4. 问答与讲解

### Q1：NoteWeave 里的文档入库链路是什么？

#### 面试官为什么问

RAG 质量先取决于数据入检索层之前是否被治理。面试官想看你是否只知道 query 阶段，还是理解 ingestion 阶段。

#### 回答思路

按上传、解析、切分、索引、版本切换、检索可用讲。

#### 结合 NoteWeave 怎么答

团队文档不是“上传即入向量库”，而是经过分片上传、对象存储、异步解析、parsed text 保存、DocumentChunk、ES BM25/向量索引和 activeIndexVersion 切换。

#### 技术原理 / 链路设计讲解

先治理再入索引，可以避免重复文档、脏文本、错误元数据和半成品索引进入问答链路。active version 能避免重建失败破坏当前可用索引。

#### 实现兜底锚点

- `DocumentUploadService`
- `DocumentProcessingService`
- `ChunkService`
- `VectorIndexerService`
- `Phase2UploadFlowIntegrationTest`
- `Phase3DocumentProcessingIntegrationTest`

#### 可直接复述的面试回答

> NoteWeave 的文档入库不是上传后直接塞向量库，而是一条治理链路。前端先分片上传，后端合并到对象存储并创建 `FileObject` 和 `Document`；之后创建 `DOCUMENT_PROCESS` 任务，经 Outbox/Kafka 交给 Worker；Worker 解析文本、保存 parsed text、切 `DocumentChunk`，再写入 Elasticsearch 的 BM25 和向量索引。索引侧有版本概念，只有新版本完整构建成功后才切 active version，避免重建失败影响旧索引。这样做的核心是把 RAG 的数据质量问题前置处理，而不是等问答阶段再补救。

#### 常见坑

- 不要说所有文档类型和 OCR 都已经完整支持。
- 不要忽略权限和状态过滤。

### Q2：NoteWeave 的查询和对话链路是什么？

#### 面试官为什么问

这是 RAG 主链路题。面试官想听到权限、召回、融合、证据、生成、引用和观测。

#### 回答思路

按权限校验 -> session scope -> 混合召回 -> RRF -> EvidencePostProcessor -> PromptBuilder -> LLM -> Citation/Trace -> runtime 讲。

#### 结合 NoteWeave 怎么答

团队侧 HTTP 和 WebSocket 都复用 RAG 证据链；WebSocket 多了 runtime state、stop/resume 和流式事件。

#### 技术原理 / 链路设计讲解

RAG 的关键不是“把检索结果给模型”，而是控制哪些结果能进候选集、哪些证据能进上下文、答案如何回溯、问题如何排查。

#### 实现兜底锚点

- `TeamChatService`
- `HybridRetriever`
- `EvidencePostProcessor`
- `TeamRagPromptBuilder`
- `CitationService`
- `ChatRuntimeService`

#### 可直接复述的面试回答

> 用户提问后，NoteWeave 先做身份和空间权限校验，再根据 chat session scope 限定知识库范围。检索层走 BM25、向量召回和 Wiki recall，通过 Weighted RRF 融合排序，再经过 EvidencePostProcessor 做去重、相邻 chunk 合并、低分过滤和同文档限流。证据足够时，PromptBuilder 按 grounded-answer 规则构造 prompt；证据不足时明确兜底，不让模型编造。回答生成后保存 assistant message、Citation、RetrievalTrace 和 LLMCallLog。WebSocket 场景下还会通过 Redis runtime state 处理流式 delta、stop、resume 和 partial content。这个链路的价值是让回答可控、可追踪、可排障。

#### 常见坑

- 不要把 query rewrite、reranker、复杂 planner 说成当前主链路全部已做满。
- 不要说模型可以无证据自由发挥。

### Q3：为什么用 Elasticsearch 做混合检索，而不是只用纯向量库或 MySQL？

#### 面试官为什么问

这是检索选型题。面试官想看你是否理解企业知识场景里语义、关键词、过滤和权限的组合需求。

#### 回答思路

讲纯向量、MySQL、ES 的边界，再回到 NoteWeave 的检索需求。

#### 结合 NoteWeave 怎么答

NoteWeave 需要 `spaceId`、`knowledgeBaseId`、`status`、`activeIndexVersion` 等过滤，也需要 BM25、向量和 Wiki recall 融合。

#### 技术原理 / 链路设计讲解

向量适合语义，BM25 适合精确词项，ES 适合把全文检索、过滤条件、排序和向量能力放在一个检索中枢。MySQL 更适合业务状态，不适合承担复杂全文和向量召回。

#### 实现兜底锚点

- `HybridRetriever`
- `SearchDebugService`
- `Phase9HybridRetrievalIntegrationTest`

#### 可直接复述的面试回答

> 我选择 Elasticsearch 作为检索中枢，是因为 NoteWeave 的团队知识问答不是只有语义相似，还需要精确术语、文档状态、空间权限、知识库范围和索引版本过滤。纯向量库在语义召回上有优势，但面对专有名词、编号、强过滤和权限时不够自然；MySQL 适合业务状态和事务，不适合做复杂全文和向量检索。ES 能把 BM25、过滤条件、排序和向量能力放在同一条检索链路里，更适合这种“语义 + 关键词 + 权限 + 版本”的企业知识场景。

#### 常见坑

- 不要说 ES 是唯一选择。
- 不要把向量检索贬成没价值，它适合语义近似。

### Q4：为什么把 NoteWeave 定义成实用型增强 RAG，而不是重型 Agent？

#### 面试官为什么问

这是 Agent 风险词题。面试官想看你是否会过度包装。

#### 回答思路

先讲主目标是知识问答可信和可治理，再讲受控 workflow 和未来 agentic 扩展。

#### 结合 NoteWeave 怎么答

当前主线是 RAG、证据链、Artifact、Methodology、Memory 和 Admin/Ops；Skill 更像可控生成流水线，不是完整开放 Agent 平台。

#### 技术原理 / 链路设计讲解

知识库系统最重要的是可控上下文、证据和权限。如果一开始做开放 Agent，会增加不确定性，反而让检索和证据治理变弱。

#### 实现兜底锚点

- `ArtifactPlanExecutor`
- `SkillExecutionLog`
- `MethodologyMatcher`
- `PersonalGenerationService`

#### 可直接复述的面试回答

> 我更愿意把 NoteWeave 定义成实用型增强 RAG 和知识工作台，而不是重型 Agent。因为当前核心目标是稳定地把团队和个人知识检索出来、生成可引用回答、沉淀成长期知识，并能排查问题。项目里确实有 Methodology、Skill pipeline、Artifact plan 这些编排能力，但它更像受控生成流水线，不是完整开放的自主 Agent 平台。这样设计是为了优先保证权限、证据和可控性。后续如果要引入更强 agentic 能力，我会把它放在 source discovery、retrieval planning、output revision 这些边界清晰的位置，而不是让 Agent 直接绕过证据链自由行动。

#### 常见坑

- 不要说当前已经是完整多 Agent 系统。
- 不要把 MCP 夸大成完整开放平台主链路；更准确的说法是当前已经落了远程 B 站 tool service 这个样例。

### Q5：RAG 效果怎么评估？

#### 面试官为什么问

这是指标和诚实边界题。

#### 回答思路

先说当前不编造生产数字，再讲已有观测面和会评估的指标。

#### 结合 NoteWeave 怎么答

NoteWeave 有 RetrievalTrace、LLMCallLog、AnswerFeedback、RagEvalRun、PromptVersion 和 Admin Observability，可用于构建评测闭环。

#### 技术原理 / 链路设计讲解

RAG 评估要拆成检索层、生成层和端到端层。不能只看回答流畅度，也不能只看 citation coverage。

#### 实现兜底锚点

- `RetrievalTrace`
- `LLMCallLog`
- `RagEvalRun`
- `AdminObservabilityController`
- `Phase14ObservabilityEvaluationIntegrationTest`

#### 可直接复述的面试回答

> RAG 评估我会拆成三层。检索层看 recall@k、MRR、命中证据是否排在前面；生成层看答案是否使用了正确证据、是否幻觉、是否覆盖问题；端到端层看 citation coverage、无证据兜底率、延迟、错误率和用户反馈。NoteWeave 当前不会编造生产准确率，但已经有 RetrievalTrace、LLMCallLog、AnswerFeedback、RagEvalRun 和 PromptVersion 这些观测与评测基础。如果要给指标，我会先构造评测集和压测环境，再用这些 trace 和 eval run 计算，而不是凭感觉说一个数字。

#### 常见坑

- 不要编造“准确率 92%”。
- 不要只看用户主观评价。

## 5. 3 分钟总答版

如果面试官说“你把 RAG 这块完整讲一下”，可以用下面这段：

> NoteWeave 的团队 RAG 不是上传文档后做一次向量 top-k 就结束，而是一条带治理、权限和证据闭环的链路。文档先经过分片上传、合并、异步解析、chunk、ES BM25 和向量索引，以及 activeIndexVersion 切换，所以数据进入检索层之前已经被治理过。查询时系统先做身份和空间权限校验，再根据 session scope 限定知识库范围，之后走 BM25、向量召回和 Wiki recall，用 Weighted RRF 做融合，再由 EvidencePostProcessor 做去重、相邻 chunk 合并、低分过滤和同文档限流。真正给模型的不是粗糙的 top-k，而是治理后的 evidence pack。生成后还会保存 Citation、RetrievalTrace 和 LLMCallLog，方便从答案一路反查到检索、证据和模型调用。这个设计的核心不是让模型答得更像，而是让答案更可控、可追踪、可排障。当前可以坚定讲 Hybrid RAG、Citation、Trace 和 Eval 闭环；MCP 这块也不能再笼统讲成“完全没做”，因为已经有远程 B 站 tool service 落地，但不会把它夸大成完整开放平台或主检索链路能力。

## 6. 连续追问速查

- 为什么 BM25 现在仍然重要：
  因为术语、编号、专有名词和强精确匹配场景里，BM25 仍然是关键召回信号。
- 为什么权限过滤最好前置：
  因为后置过滤会污染候选集，小 k 场景下更容易把本该命中的结果挤掉。
- 为什么 Citation 和 RetrievalTrace 要分开：
  Citation 解决“回答引用了什么证据”，Trace 解决“系统为什么会得到这批候选和排序结果”。
- 无证据时为什么要拒答或兜底：
  因为知识系统的可信度比覆盖率更重要，不能为了表面回答率让模型自由编造。

## 7. 边界和不能说满的地方

- 可以坚定主讲：Hybrid RAG、权限过滤、Citation、RetrievalTrace、Eval、无证据兜底、activeIndexVersion。
- 可以作为优化方向讲：更复杂的 query rewrite、reranker、多阶段 planner、更丰富的评测集。
- 只能作为扩展方向讲：GraphRAG 主链路、完整开放式 MCP 平台主链路、完整开放 Agent、真实线上准确率和生产 QPS/P99。
