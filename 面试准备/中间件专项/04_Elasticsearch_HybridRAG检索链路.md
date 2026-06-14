# Elasticsearch：Hybrid RAG 检索读模型

> 本文件为 2026-06-01 加深版。ES 在 NoteWeave 里是检索读模型，不是最终业务事实源，也不替代 Citation 和权限校验。

## 0. 一句话定位

Elasticsearch 保存可搜索的文档 chunk、向量索引和 Wiki 索引，用来做 BM25、向量召回、Wiki recall 和检索 debug。最终可见性、状态和版本仍以 MySQL 为准。

## 1. 当前真实设计

### 文档 chunk 索引

代码锚点：`SearchIndexService`

默认索引：
- `noteweave-dev-document-chunk`

主要字段：
- `spaceId`、`knowledgeBaseId`、`documentId`
- `documentStatus`
- `indexVersion`、`activeIndexVersion`
- `chunkId`、`chunkIndex`
- `title`、`content`
- `contentHash`、`sourceType`
- `lifecycleStatus`
- `createdAt`

面试怎么讲：
- ES 里冗余 space/document/status/version，是为了检索时先做过滤。
- 检索时还要过滤 `spaceId`、`knowledgeBaseId`、`lifecycleStatus=ACTIVE`、`documentStatus=INDEXED`，并保证 `indexVersion == activeIndexVersion`。
- 这不是最终权限判断，返回结果后仍要回 MySQL/Citation 做校验和追溯。

### 向量索引

代码锚点：`VectorIndexerService`

索引命名：
- alias：`{indexPrefix}document-chunk-vector`
- 版本索引：`{indexPrefix}document-chunk-vector-{model}-{dimension}`

面试怎么讲：
- 模型和维度会影响向量 index，所以用 model/dimension 生成新索引，再切 alias。
- 向量召回失败时，HybridRetriever 会 fallback 到 BM25/Wiki，而不是让整个问答崩掉。

### Wiki 索引

代码锚点：
- `WikiIndexService`
- `WikiIndexTaskWorker`
- `SearchIndexWikiSupport`

面试怎么讲：
- Wiki 发布事实在 MySQL 的 `wiki_page` 和 `wiki_page_version`。
- WIKI_INDEX 只是把已发布版本写入 ES，失败时 `wiki_page.index_status` 可标记失败，不破坏已发布页面。
- WikiRetriever 是 Hybrid RAG 的一路召回，但当前不能说成完整 GraphRAG。

## 2. 高频深问与答法

### Q1: 为什么不用 MySQL LIKE？

答：团队知识问答需要全文相关性、标题加权、chunk 级召回、向量检索和 Wiki 搜索。MySQL LIKE 可以做简单关键词，但无法承担语义向量、topK 排序、RAG evidence candidate 和检索 debug。MySQL 负责事实，ES 负责召回候选。

### Q2: ES 为什么不能做最终权限源？

答：ES 是异步读模型，可能落后于 MySQL，也可能保留旧 chunk。权限、软删除、成员变化、Wiki 发布状态都以 MySQL 为准。ES query 里会先过滤 space/status/version，但最终 Citation 展示和资源读取还要回服务层校验。

### Q3: BM25、向量、Wiki recall 怎么融合？

答：HybridRetriever 先拿 BM25 hits，再尝试 vector hits 和 Wiki hits；WeightedReciprocalRankFusion 按 BM25/vector/Wiki 权重和 RRF K 融合排序；EvidencePostProcessor 再做去重、相邻合并、限流和截断。ES 只提供候选，最终 evidence 包还要由应用层处理。

### Q4: ES 和 MySQL 不一致怎么排查？

答：先查 MySQL 的 `document.status`、`index_status`、`active_index_version` 或 `wiki_page.index_status`、`published_version_id`；再查 ES doc 的 documentStatus、lifecycleStatus、indexVersion；最后看 TaskEvent、WIKI_INDEX/DOCUMENT_PROCESS 任务和 cleanup 记录。修复通常是重建索引、同步状态或删除孤儿 ES doc。

### Q5: 向量模型升级怎么办？

答：不能直接覆盖旧向量。当前 VectorIndexerService 用 model/dimension 生成新向量索引，backfill 完后切 alias。这样新旧模型可以隔离，失败时不影响旧索引。面试不能说已经做了复杂在线灰度，只能说当前设计支持通过 alias 切换降低风险。

## 3. 性能和风险怎么说

- 可观察指标：ES health、查询 latency、召回数量、fallbackUsed、bm25/vector/wiki count、RAG Eval recallAtK/MRR。
- 风险点：索引滞后、旧版本 chunk、向量 alias 不存在、ES red/yellow、权限过滤遗漏。
- 扩展点：后续可以换 OpenSearch/Milvus，但要保留 SearchIndexService、Retriever、Trace 和 Citation 的边界。

## 4. 不能说满

- 不要说 ES 是业务事实源。
- 不要说 ES 里有数据就代表用户一定可见。
- 不要说当前 WikiGraph 等于完整 GraphRAG。
- 不要编造线上召回率或查询 P99。

## 5. grep / LIKE / BM25 怎么讲得像做过取舍
面试官问“为什么不用更简单的方案”，可以按阶段答：

- grep：适合本地排查和 baseline，比如快速验证某段文本是否存在。它不适合团队知识库主链路，因为没有权限过滤、索引版本、相关性排序和 Trace。
- MySQL LIKE：适合非常早期 MVP，少引入一个中间件。但长文本性能、分词、排序、字段权重和 topK 召回都弱，不适合作为 RAG 主检索。
- Elasticsearch BM25：适合关键词、术语、专有名词和技术文档，能在 query 阶段带 `spaceId`、`knowledgeBaseId`、status 和 version filter，因此是 NoteWeave 团队知识库的基础召回。

可以直接说：

> 我不是为了堆技术才上 ES。grep 和 LIKE 可以做 baseline，但一旦进入团队知识库和 RAG，就需要 chunk 级召回、相关性排序、权限过滤、索引版本和检索调试。ES 作为读模型更适合这个位置，MySQL 继续做事实源。

## 6. BM25 / Vector / Hybrid / Rerank 的升级路径
1. 先用 BM25 保证关键词和专有名词召回。
2. 再引入 Vector 处理用户表达和原文表达不一致的问题。
3. 再用 Weighted RRF 融合排名，避免不同 score 分布直接相加。
4. 后续如果要进一步提升高价值问题的排序质量，再引入 rerank。

当前项目可以讲到第 3 步。第 4 步要明确是“当前暂未实现的后续优化”。

## 7. ES 出问题时的排障口径
如果检索不到，先不要怪模型：

1. MySQL 里 Document/Wiki 是否是可检索状态。
2. Task 是否成功，是否有 failed attempt。
3. ES 文档是否写入正确索引或 alias。
4. 查询 filter 是否过严或漏掉正确 knowledgeBaseId。
5. Vector 维度和 alias 是否匹配。
6. HybridRetriever 是否 fallback 到 BM25。
7. RetrievalTrace 是否记录了各路召回数量。
