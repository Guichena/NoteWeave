# Elasticsearch：Hybrid RAG 检索链路

## 0. 本篇定位

这篇用于回答 NoteWeave 为什么使用 Elasticsearch、ES 在文档索引、BM25、向量检索、Wiki recall、Hybrid RAG、权限过滤和索引版本中承担什么职责，以及面试里的倒排索引、BM25、向量、RRF、过滤、索引一致性和评估问题。

## 1. 本主题面试官想考什么

面试官问 Elasticsearch，通常想验证：

1. 你是否知道 ES 是检索索引，不是业务事实源。
2. 你是否能讲清 BM25、向量召回和 Wiki recall 的分工。
3. 你是否理解权限过滤、文档状态、activeIndexVersion 为什么必须进入检索层。
4. 你是否能说明 reindex、alias、降级和坏回答排查。
5. 你是否能把 RAG 八股讲成工程链路。

## 2. 高频问题清单

基础问题：

- NoteWeave 为什么用 Elasticsearch？
- BM25 是什么？为什么还重要？
- 为什么不用纯向量库或 MySQL？

进阶问题：

- Hybrid RAG 具体怎么做？
- ES 里存哪些字段？
- 权限过滤为什么要前置？

深挖追问：

- `activeIndexVersion` 解决什么问题？
- 向量召回失败怎么降级？
- Weighted RRF 为什么比直接加分更稳？

压力追问：

- ES 索引和 MySQL 数据不一致怎么办？
- ES 挂了问答还能不能用？
- 如何评估 RAG 效果？

## 3. 问答与讲解

### Q1：NoteWeave 为什么用 Elasticsearch？

#### 面试官为什么问

这是检索选型题。面试官想看你是否理解企业知识问答不是只靠向量相似。

#### 回答思路

先讲 NoteWeave 需要全文检索、关键词、过滤、权限、状态、向量和可调试性，再讲 ES 的适配点。

#### 结合 NoteWeave 怎么答

ES 索引 `DocumentChunk`，字段包括 `spaceId`、`knowledgeBaseId`、`documentId`、`documentStatus`、`indexVersion`、`activeIndexVersion`、`chunkId`、`content`、`embedding`、`lifecycleStatus` 等。

#### 技术原理 / 链路设计讲解

BM25 适合精确词项、术语、编号和标题内容检索；向量适合语义相似；过滤字段保障权限和状态；ES 可以把这些能力集中到一个检索中枢。

#### 实现兜底锚点

- `SearchIndexService`
- `EsDocumentChunk`
- `Bm25Retriever`
- `VectorRetriever`
- `HybridRetriever`
- `Phase9HybridRetrievalIntegrationTest`
- `SystemHealthService.checkElasticsearch()`

#### 可直接复述的面试回答

NoteWeave 用 Elasticsearch，是因为团队知识问答不只是语义相似，还需要关键词、专有名词、标题权重、空间权限、知识库范围、文档状态和索引版本过滤。纯向量库在语义召回上有优势，但面对精确术语和复杂过滤时不一定自然；MySQL 适合业务状态和事务，不适合承担全文检索和向量检索。ES 里既可以做 BM25，也可以承载 dense vector，还可以用 filter 把 `spaceId`、`knowledgeBaseId`、`documentStatus`、`lifecycleStatus` 和 `activeIndexVersion` 带进检索阶段，所以更适合作为 NoteWeave 的检索中枢。

#### 常见追问

- BM25 为什么没有过时？
- ES 和向量库怎么取舍？
- ES 里的数据和 MySQL 怎么分工？

#### 常见坑

- 不要说 ES 是业务事实源。
- 不要说纯向量检索一定比 BM25 更强。

### Q2：NoteWeave 的 Hybrid RAG 在 ES 上怎么走？

#### 面试官为什么问

这是 RAG 主链路题。

#### 回答思路

按 BM25 -> Vector -> Wiki recall -> Weighted RRF -> EvidencePostProcessor -> PromptBuilder -> Citation/Trace 讲。

#### 结合 NoteWeave 怎么答

`HybridRetriever` 先跑 BM25，再尝试 vector 和 wiki；如果 vector 失败，会 fallback 到 BM25；多路结果通过 `WeightedReciprocalRankFusion` 融合。

#### 技术原理 / 链路设计讲解

不同召回通道的分数尺度不同，直接加分不稳定。RRF 使用排名贡献，能把不同检索器的排序结果融合起来。NoteWeave 还给 BM25、Vector、Wiki 配了权重。

#### 实现兜底锚点

- `HybridRetriever`
- `WeightedReciprocalRankFusion`
- `RrfOptions`
- `RagProperties`
- `EvidencePostProcessor`

#### 可直接复述的面试回答

NoteWeave 的 Hybrid RAG 不是简单拼接 ES 查询结果。团队问答会先做权限和 session scope 约束，然后 BM25 从 ES 里召回关键词和全文相关 chunk；如果启用了向量索引，VectorRetriever 会用 embedding 做语义召回；如果问题需要 Wiki，也会通过 WikiRetriever 补充人工沉淀知识。多路结果进入 Weighted RRF，按排名和权重融合，而不是直接把不同通道分数相加。融合后还要经过 EvidencePostProcessor 做去重、相邻 chunk 合并、低分过滤和单文档限流，最后才进 prompt。这样做的重点是让召回、融合、证据治理和可追踪性成为一条工程链路。

#### 常见追问

- Weighted RRF 的公式是什么思路？
- 为什么要给 Wiki 更高权重？
- 向量检索失败怎么办？

#### 常见坑

- 不要说 Hybrid RAG 只是“ES + 向量一起查”。
- 不要说 RRF 是 reranker，它更像融合排序。

### Q3：权限过滤为什么必须进入 ES 检索阶段？

#### 面试官为什么问

这是安全和检索质量题。

#### 回答思路

讲候选集阶段就要限制范围，否则 top-k 可能被无权限或无效文档占据，后置过滤会降低召回质量。

#### 结合 NoteWeave 怎么答

ES 查询 filter 包含 `spaceId`、`knowledgeBaseId`、`lifecycleStatus=ACTIVE`、`documentStatus=INDEXED`，并用脚本确保 `indexVersion == activeIndexVersion`。

#### 技术原理 / 链路设计讲解

RAG 安全不能只在最终返回前过滤。检索前置过滤既防越权，也保证候选集质量。后续 MySQL 仍会复核文档生命周期和权限，形成双层保护。

#### 实现兜底锚点

- `SearchIndexService.searchChunkHits`
- `TeamChatService`
- `CitationService`
- `Phase4TeamRagIntegrationTest`

#### 可直接复述的面试回答

权限过滤必须进入 ES 检索阶段，因为 RAG 的 top-k 候选集如果先混入无权限或无效文档，后面再删结果会导致真正该命中的证据被挤掉，也有安全风险。NoteWeave 在 ES 查询里就带上 `spaceId`、`knowledgeBaseId`、`lifecycleStatus`、`documentStatus` 和 active version 过滤，确保召回范围是用户有权限且当前有效的文档。返回后还会做 MySQL 复核和 Citation 二次权限校验。这样既保证安全边界，也保证检索质量。

#### 常见追问

- 为什么还需要 MySQL 二次校验？
- 如果权限变更，ES 旧数据怎么办？
- 后置过滤会有什么问题？

#### 常见坑

- 不要只说 controller 鉴权就够了。
- 不要让 Citation 或预览绕过权限。

### Q4：`activeIndexVersion` 解决什么问题？

#### 面试官为什么问

这是索引一致性题，常用于判断你是否理解文档更新和 reindex 风险。

#### 回答思路

讲重建索引不能原地覆盖旧索引，新版本成功前旧版本必须继续可用。

#### 结合 NoteWeave 怎么答

`DocumentChunk` 和 ES 文档都带 `indexVersion`，文档有 `activeIndexVersion`。检索时只召回 indexVersion 等于 activeIndexVersion 的 chunk。

#### 技术原理 / 链路设计讲解

版本化索引让 reindex 成为“构建新版本 -> 成功后切换”的过程，而不是“删除旧索引 -> 尝试写新索引”。这避免中途失败导致线上不可检索。

#### 实现兜底锚点

- `DocumentProcessingService`
- `DocumentChunkService`
- `SearchIndexService.synchronizeDocumentChunkState`
- `Phase3DocumentProcessingIntegrationTest`

#### 可直接复述的面试回答

`activeIndexVersion` 解决的是文档重建索引的一致性问题。如果直接覆盖旧 chunk 或旧 ES 文档，新索引构建失败时，用户可能既查不到新内容，也失去旧的可用索引。NoteWeave 的做法是给 chunk 和索引文档带 `indexVersion`，文档记录当前 `activeIndexVersion`。重建时先生成新版本，成功后再切 active；检索时只召回 `indexVersion == activeIndexVersion` 的 chunk。这样旧版本在新版本成功前仍然可用，失败也不会破坏当前查询路径。

#### 常见追问

- 删除文档后 ES 怎么同步？
- 新旧版本会不会同时被召回？
- alias 和 active version 有什么区别？

#### 常见坑

- 不要说 reindex 时直接删除旧索引。
- 不要忽略 MySQL 和 ES 状态同步。

### Q5：ES 挂了或向量索引不可用怎么办？

#### 面试官为什么问

这是降级和故障边界题。

#### 回答思路

区分 BM25/Vector/Wiki 局部失败和 ES 整体不可用。向量失败可以 BM25 fallback；ES 整体挂了会影响团队检索问答。

#### 结合 NoteWeave 怎么答

`HybridRetriever` 捕获 vector retrieval 异常并设置 `fallbackUsed`。`SystemHealthService.checkElasticsearch()` 通过 cluster health 和 vector alias 检查状态。

#### 可直接复述的面试回答

如果只是向量召回失败，NoteWeave 可以退回 BM25 和 Wiki recall，`HybridRetriever` 会记录 fallbackUsed，不让某一路失败拖垮整个问答。但如果 ES 整体不可用，团队知识检索和 RAG 问答会受到明显影响，因为 BM25 和向量索引都依赖 ES。此时系统应该通过健康检查发现 ES 状态，通过 RetrievalTrace/LLMCallLog 记录失败，并返回明确错误或无证据兜底，而不是让模型脱离证据自由发挥。

#### 常见追问

- 为什么不能 ES 挂了还让模型直接答？
- 是否需要缓存热门问题答案？
- ES 集群状态 yellow/red 怎么理解？

#### 常见坑

- 不要说 ES 挂了 RAG 完全无影响。
- 不要用模型自由回答替代证据链。
