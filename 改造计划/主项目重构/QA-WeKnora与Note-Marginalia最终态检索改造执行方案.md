# QA 全量对齐 WeKnora、Note 全量对齐 Marginalia 的最终态检索改造执行方案

> 状态：执行中；仅允许最终态整体发布
>
> 适用范围：NoteWeave v2 的 Source 解析、索引、QA、Note、AnswerRun、Citation、Retrieval Trace、评测和运维链路
>
> 参考实现：`reference/WeKnora`、`reference/marginalia`
>
> 发布原则：只发布最终态，不发布仅 BM25、仅 Metadata、无向量索引、无 RRF、无 rerank 或索引未完成回填的中间版本

## 0. 执行进度

截至 2026-07-20，已完成以下施工项：

- WP1：新增独立 Embedding 和 Rerank 配置、Provider 接口、OpenAI-compatible 客户端、输入限长、批处理、重试、维度校验、稳定错误码和 Actuator 健康状态。
- WP2：新增 `V087__create_retrieval_projection_runtime.sql`，建立 `retrieval_projection` 和 `retrieval_index_build`；实现投影与索引构建状态机。
- WP3 前置：实现 QA Chunk、Note Source 两套版本化 Elasticsearch 索引命名、dense_vector cosine mapping、alias 切换、投影文档写入和 Current Snapshot 失效接口。
- WP3：Source 解析已改为每个 Snapshot 只产生一条 `noteweave.retrieval.projection` 命令；Kafka 与非 Kafka 执行路径均已接入 QA Chunk + Note Source 双投影。
- WP3：实现 `SourceRetrievalProjectionCoordinator` 与短事务 Finalizer。只有 QA Chunk READY 数等于当前 Snapshot Chunk 数，且 Note Source READY 数等于 1 时，才允许把 Chunk 标记为 PROJECTED、Snapshot 标记为 INDEXED、Source 标记为 READY 并完成解析任务。
- WP3：旧 `ElasticsearchIndexer` 已撤销 Source/Snapshot READY 和 Task 完成权限，仅承担历史 `source.index` 消息的兼容投影；旧 Snapshot 在新 Snapshot 生效前写入 ES `is_current_snapshot=false` 并在 MySQL 标为 STALE。
- WP3：双投影完成后初始化/切换 QA 与 Note 查询 alias；失败投影支持从 FAILED/中间态恢复后幂等重试。
- WP4：QA 正常路径已接入 `QaHybridRetriever`：Query Embedding、Vector/BM25 双路各 60 条过召回、加权 RRF、真实 rerank、Ownership/Current Snapshot 复核、Source Diversity 和 6 条/8000 字符预算。
- WP4：QA EvidenceBundle 已分别写入 raw recall score、RRF fused score 和 rerank score；计划版本切换为 `qa-weknora-hybrid-v1`。MySQL 仅在混合检索异常时作为明确降级路径。
- WP5：Note 正常路径已切换为 `note-marginalia-funnel-v1`。Journal、ES Metadata、Note Source 向量召回、关系扩展统一进入 Source 候选池，随后执行真实 Source rerank、证据配额和验证批次。
- WP5：已移除 `NoteRecallRepository` 的最近 40 条限制；只读取各 Source 当前已 INDEXED Snapshot。验证批次内先使用 QA Chunk 向量做 Source-local 语义定位，再执行 Window rerank，并把 raw/fused/rerank 分数写入 EvidenceBundle 和 Trace。
- WP6：新增 Workspace 检索索引状态与全量重建 API。每次 Build 使用独立物理代际，完整回填 QA/Note 后校验数量，并在同一个 Elasticsearch alias update 中原子切换两条 alias。
- WP6：新增资料抽屉双索引覆盖率、Build 状态和全量回填操作；新增 Release Gate，Provider、双投影覆盖、双 COMPLETED Build 和最终计划无降级样本缺一不可。
- WP7 验证：真实 Elasticsearch 集成测试已通过，覆盖 QA/Note 向量与关键词/元数据召回、Current Snapshot 排除、物理索引代际和双 alias 原子切换；失败时索引清理由 `finally` 保证。
- WP7 验证：新增 `qa-note-ablation-report-v1.json`，对 QA/Note Gold 集的全部最终消融变体记录 Recall@K、MRR、NDCG@K、Citation Coverage、Scope Violation 和 p95 延迟，并由 `FinalRetrievalGoldContractTest` 校验报告完整性。
- 该报告 SHA-256：`sha256:33ed135d433cb0d8a21875bdaecc82e3e6b1e07819fad42201f71730f44aea0f`。
- WP7 修正：`NoteRecallRetriever` 的生产 Spring 构造器现在显式注入 Embedding、ES Source Search 和 Source rerank；Provider 不可用时才进入带降级原因的本地兼容评分，避免生产实例静默使用旧四参数路径。
- WP7 验证：`QaHybridRetrieverProviderIntegrationTest` 使用可控 Embedding/Rerank stub 验证真实 QA 主链路的双路召回、RRF、rerank、ownership 复核和无 MySQL fallback；`NoteSourceRerankServiceTest` 验证 Note Source provider 排序与分数回写。

当前验证证据：

- Provider、Flyway v001 至 v087、投影状态机、索引构建状态机和 ES mapping 的稳定窗口定向测试为 14/14 通过。
- Source 双投影相关定向测试最新一轮 16/16 通过。
- Note 漏斗与 Phase3 集成测试最新一轮 37/37 通过。
- 物理索引代际、投影/Build 状态机测试最新一轮 8/8 通过。
- 当前主源码 519 个 Java 文件编译通过；前端 TypeScript + Vite production build 通过。
- 真实 ES `RetrievalLiveElasticsearchIntegrationTest`：1/1 通过（含旧 Snapshot 不命中及 v1→v2 alias 切换）。
- Gold/消融契约测试：3/3 通过，报告覆盖 QA 5 个、Note 6 个最终变体。
- Provider stub 主链路测试：QA 1/1、Note Source rerank 2/2 通过。
- 实际 Workspace `37e816dc-5b4c-4d3e-a825-68df9c417ec9` 已完成一次真实回填：QA/Note Build 均 `COMPLETED`，expected/ready 均为 `1/1`，双 projection 均 `READY`，双 alias 已切换。
- 该 Workspace 的 Release Gate 已返回 `releasable=true`、`violations=[]`、`degraded_final_plan_run_count=0`；QA 与 Note quality receipt 均 `ablation_complete=true`。
- 后端独占全量回归：通过（Surefire 全套测试无失败）。
- 前端 production build：`tsc -b && vite build` 通过。
- 工作树同时存在其他模块的并发修改，测试编译阶段偶发出现共享 `backend/target` 被清理的问题；后续需在独占构建窗口运行完整回归。

最终审计结论：旧 `ChunkSearchPort`/BM25 代码仅保留给历史评测和显式 Provider 故障时的 emergency fallback；新 QA AnswerRun 固定写入 `qa-weknora-hybrid-v1`，新 Note AnswerRun 固定写入 `note-marginalia-funnel-v1`，正常生产路径不会选择旧单通道。该兼容保留不构成中间版本发布。

环境核验补充：本轮已使用当前源码重建 Backend 容器，并通过临时可控 Provider stub 完成健康检查、实际 Workspace 回填、quality receipt 写入和 Release Gate 演练；验证结束后已移除 stub，Backend 恢复原 host provider 配置。

## 1. 决策

QA 模式按 WeKnora 的完整知识库检索链路改造：Chunk 入库时生成 embedding，查询时同时执行向量召回和关键词召回，使用加权 RRF 融合，经过真实 rerank、Evidence Budget 和 Citation 校验后生成回答。

Note 模式按 Marginalia 的完整资料检索漏斗改造：Journal、标签、资料元数据、资料级语义召回和关系信号共同定位资料，经过统一打分、真实 rerank、证据配额和验证批次后打开原文窗口，再基于原文生成回答。

这里的“照抄”指复制参考项目已经验证的检索职责、算法顺序、索引生命周期、降级规则和评测方法。NoteWeave 不复制 Go 或 Python 代码，也不引入参考项目自己的 Tenant、KnowledgeBase、FileEntry 或本地目录真相源。最终实现继续遵守 NoteWeave 已有的 Java 模块边界，并以 Workspace、Source、SourceSnapshot、SourceChunk、SourceWindow、AnswerRun、EvidenceBundle 和 Citation 作为业务真相。

本次改造不保留现有轻量检索作为并行正式策略：

- QA 当前 `Elasticsearch multi_match + MySQL contains 评分` 只能作为故障兜底，不再作为正常检索路径。
- QA 当前“词法充分性校验”不再被称为 rerank，最终由真实 rerank 模型替代主排序职责。
- Note 当前“最近 40 份资料再做字符串匹配”的候选入口必须删除。
- Note 的 Metadata、Journal 和 Relation 逻辑保留，但必须并入 Marginalia 式统一召回池，不能继续作为孤立的手工评分链路。
- 新版本发布时，QA 和 Note 的索引、查询、融合、rerank、Evidence、Trace 和评测必须同时就绪。

## 2. 最终系统边界

### 2.1 QA 最终链路

```text
用户问题
  -> Query Rewrite
  -> 生成 Query Embedding
  -> Workspace / Source Scope / Current Snapshot 过滤
  -> Vector Chunk Recall
  -> Keyword Chunk Recall
  -> 每路过召回
  -> 阈值准入
  -> Weighted RRF Fusion
  -> 去重和 Parent/Child Chunk 上下文补齐
  -> Rerank
  -> Source Diversity + Evidence Budget
  -> Ownership / Current Snapshot 复核
  -> EvidenceBundle
  -> Citation-grounded Answer
```

QA 的检索对象是 `SourceChunk`。它回答的是“哪些原文片段最能支持当前问题”，所以向量索引和关键词索引都按 Chunk 建立。

### 2.2 Note 最终链路

```text
用户问题
  -> 解析 tag hints 和 text terms
  -> Search Journal
  -> Search Metadata / Tags / Catalog / Type
  -> Source-level Semantic Recall
  -> Relation Expansion
  -> 统一合并和 RRF/Heuristic Scoring
  -> Rerank
  -> Evidence Quota
  -> Candidate Sources
  -> Verification Batch
  -> Read Entry Metadata
  -> 在候选 Source 内定位原文 Window
  -> 读取相邻窗口和补充证据
  -> EvidenceBundle
  -> Citation-grounded Answer
```

Note 的第一层检索对象是 `Source Entry`，也就是一份资料，而不是全库 Chunk。只有资料进入验证批次后，系统才在这些资料内部定位 `SourceWindow`。这样可以保持 Marginalia 的资料深读路径，避免 Note 退化成另一套 QA Chunk RAG。

### 2.3 两条链路共享的能力

两条链路共享以下基础设施，但不共享召回算法：

- Embedding Provider 和模型管理
- Rerank Provider 和模型管理
- 可靠索引任务、重试、死信和重建任务
- Workspace、Source Scope 和 Current Snapshot 过滤
- AnswerRun、RetrievalPlan、Retrieval Trace 和 EvidenceBundle
- Citation ownership 校验
- 模型版本、索引版本和内容哈希
- 评测数据格式、在线 Shadow 导出和发布门禁

## 3. 技术选型

### 3.1 检索存储

最终版本继续使用 Elasticsearch 8.15.3，不新增独立向量数据库。QA Chunk 索引和 Note Source 索引使用独立索引族：

```text
noteweave_qa_chunk_v{schemaVersion}_{workspaceId}
noteweave_note_source_v{schemaVersion}_{workspaceId}
```

对外查询只访问别名：

```text
noteweave_qa_chunk_{workspaceId}
noteweave_note_source_{workspaceId}
```

索引 mapping 变更、Embedding 模型变更或维度变更时创建新索引，完成全量回填和一致性校验后原子切换 alias。禁止在生产索引上原地改变向量维度。

### 3.2 Embedding

新增独立 Embedding 配置，不复用 Chat LLM 的 API Key：

```yaml
noteweave:
  embedding:
    enabled: true
    endpoint: ${NOTEWEAVE_EMBEDDING_ENDPOINT}
    api-key: ${NOTEWEAVE_EMBEDDING_API_KEY}
    model: ${NOTEWEAVE_EMBEDDING_MODEL}
    dimensions: ${NOTEWEAVE_EMBEDDING_DIMENSIONS:1024}
    document-batch-size: ${NOTEWEAVE_EMBEDDING_DOCUMENT_BATCH_SIZE:32}
    query-timeout-seconds: ${NOTEWEAVE_EMBEDDING_QUERY_TIMEOUT_SECONDS:10}
    batch-timeout-seconds: ${NOTEWEAVE_EMBEDDING_BATCH_TIMEOUT_SECONDS:60}
    max-input-characters: ${NOTEWEAVE_EMBEDDING_MAX_INPUT_CHARACTERS:12000}
```

Embedding 客户端必须支持：

- `embedQuery(text)`
- `embedDocuments(texts)`
- 批处理、并发上限、指数退避和限流
- 返回模型、维度和 token usage
- 输入长度保护
- 超时和稳定错误码
- OpenAI-compatible `/v1/embeddings`

QA 和 Note 可以使用同一模型，但索引文本不同：

- QA：`heading breadcrumb + chunk content`
- Note：`title + source type + summary + tags + structured metadata + section descriptions`

### 3.3 Rerank

新增独立 Rerank 配置，不复用 Chat LLM 的 API Key：

```yaml
noteweave:
  rerank:
    enabled: true
    endpoint: ${NOTEWEAVE_RERANK_ENDPOINT}
    api-key: ${NOTEWEAVE_RERANK_API_KEY}
    model: ${NOTEWEAVE_RERANK_MODEL}
    batch-size: ${NOTEWEAVE_RERANK_BATCH_SIZE:80}
    max-document-characters: ${NOTEWEAVE_RERANK_MAX_DOCUMENT_CHARACTERS:1800}
    timeout-seconds: ${NOTEWEAVE_RERANK_TIMEOUT_SECONDS:20}
```

Rerank 是最终版本的必备能力。发布环境缺少配置、健康检查失败或模型不兼容时，应用不得标记为 Ready。运行时偶发失败可以按明确策略降级，但必须在 Retrieval Trace 中记录，且发布门禁中的正常样本不允许使用降级结果通过质量验收。

## 4. 索引数据模型

### 4.1 QA Chunk 索引

每个 QA 索引文档至少包含：

```json
{
  "workspace_id": "...",
  "source_id": "...",
  "source_snapshot_id": "...",
  "chunk_id": "...",
  "chunk_no": 12,
  "heading": "...",
  "title": "...",
  "source_type": "PDF",
  "content": "...",
  "embedding_text_hash": "sha256:...",
  "embedding_model": "...",
  "embedding_dimensions": 1024,
  "embedding_version": "...",
  "parser_version": "...",
  "projection_version": "...",
  "is_current_snapshot": true,
  "projection_status": "PROJECTED",
  "content_embedding": [0.0]
}
```

Mapping 要求：

- `content_embedding` 使用 `dense_vector`，启用 cosine similarity 和 HNSW index。
- `content`、`title`、`heading` 使用适合中英文混合资料的 analyzer。
- ID、Workspace、Source、Snapshot、版本和状态字段使用 `keyword`。
- kNN 查询中的 Workspace、Source Scope、Current Snapshot 和状态过滤必须在召回阶段执行。

### 4.2 Note Source 索引

每个 Note 资料级索引文档至少包含：

```json
{
  "workspace_id": "...",
  "source_id": "...",
  "source_snapshot_id": "...",
  "title": "...",
  "source_type": "PDF",
  "summary": "...",
  "tags": ["..."],
  "metadata_text": "...",
  "section_descriptions": ["..."],
  "catalog_ids": ["..."],
  "chunk_count": 20,
  "window_count": 42,
  "embedding_text_hash": "sha256:...",
  "embedding_model": "...",
  "embedding_dimensions": 1024,
  "embedding_version": "...",
  "projection_version": "...",
  "is_current_snapshot": true,
  "source_embedding": [0.0]
}
```

Note Source embedding 不直接嵌入整份原文。索引文本必须是受控的资料表示，长度受限，并保留标题、摘要、标签、结构描述和关键元数据。原文只在验证批次之后通过 `SourceWindow` 读取。

### 4.3 MySQL 投影状态

新增表 `retrieval_projection`，统一记录 QA Chunk 和 Note Source 的索引生命周期：

```text
id
workspace_id
projection_type             QA_CHUNK | NOTE_SOURCE
entity_id                   chunk_id 或 source_id
source_id
source_snapshot_id
content_hash
embedding_text_hash
embedding_provider
embedding_model
embedding_dimensions
embedding_version
index_schema_version
target_index
status                      PENDING | EMBEDDING | INDEXING | READY | FAILED | STALE
attempt_count
last_error_code
next_retry_at
projected_at
created_at
updated_at
```

约束：

- `(projection_type, entity_id, source_snapshot_id, embedding_version, index_schema_version)` 唯一。
- `READY` 只能由 Elasticsearch 写入成功并完成读后校验后设置。
- Source 产生新 Snapshot 时，旧 Snapshot 的投影标记为 `STALE`，ES 中 `is_current_snapshot=false`。
- Answer 检索只接纳 MySQL 和 ES 都确认属于当前 Snapshot 的命中。

新增表 `retrieval_index_build`，记录全量重建、增量重建和 alias 切换：

```text
id
workspace_id
projection_type
from_index
target_index
embedding_model
embedding_dimensions
index_schema_version
status                      CREATED | BACKFILLING | VERIFYING | SWITCHING | COMPLETED | FAILED
expected_count
ready_count
failed_count
alias_switched_at
started_at
completed_at
```

## 5. Source 入库和索引流水线

现有 `SourceParseService -> Kafka -> ElasticsearchIndexer` 改造成可靠的双投影流水线：

```text
Source Snapshot READY_FOR_INDEX
  -> 生成 SourceChunk / SourceWindow
  -> 发布 retrieval.projection.requested
  -> QA Chunk Embedding Batch
  -> QA Chunk Index Write
  -> Note Source Representation Build
  -> Note Source Embedding
  -> Note Source Index Write
  -> 读后校验
  -> retrieval_projection READY
  -> Snapshot INDEXED
  -> Source READY
```

Source 只有在当前 Snapshot 的 QA Chunk 投影和 Note Source 投影都 READY 后才能进入 `READY/INDEXED`。不允许出现 Source 对用户显示可检索，但 Note 或 QA 只有一条链路完成索引的状态。

任务执行要求：

- 使用持久化任务或 Outbox，不依赖内存事件。
- Chunk embedding 批量生成，按 provider 限流。
- 同一 content hash 和 embedding version 可幂等重试。
- 单个 Chunk 失败不能静默跳过，Source 保持不可检索并给出稳定错误码。
- 重试耗尽后进入死信，可人工 redrive。
- 删除 Source 时同时删除两个索引族中的文档，并保留审计记录。
- 新 Snapshot 完成前旧 Snapshot 可继续服务；新 Snapshot 完成并切换后旧 Snapshot 退出召回。

## 6. QA 按 WeKnora 改造

### 6.1 新模块

新增以下接口和实现：

```text
EmbeddingClient
RerankClient
QaHybridSearchPort
QaElasticsearchHybridSearchAdapter
QaQueryEmbeddingService
QaRetrievalFanout
QaRrfFusionService
QaRerankService
QaContextEnrichmentService
QaHybridRetriever
```

`QaHybridSearchPort` 必须显式拆分两种检索：

```java
List<QaSearchHit> vectorRetrieve(QaVectorQuery query);
List<QaSearchHit> keywordRetrieve(QaKeywordQuery query);
```

禁止继续用一个含糊的 `ChunkSearchPort.search()` 隐藏检索类型。

### 6.2 查询改写和 Query Embedding

Query Rewrite 输出：

```text
original_query
retrieval_queries
must_terms
preferred_terms
workspace_id
source_scope
language
question_type
```

第一版正式实现可以只生成一个主检索查询，但接口和快照必须支持多个 Query。Query Embedding 只生成一次，并在同一 AnswerRun 的多个 Store/Index 查询间复用。

Query embedding 的模型、维度和版本必须与目标索引 manifest 一致。发现不一致时 fail closed，禁止跨 embedding space 比较。

### 6.3 双路召回

默认参数：

| 参数 | 默认值 |
|---|---:|
| Vector over-recall | 60 |
| Keyword over-recall | 60 |
| Fusion candidate ceiling | 100 |
| Final rerank top N | 40 |
| Final evidence count | 6 |
| Evidence character budget | 8000 |
| RRF k | 60 |
| Vector weight | 0.7 |
| Keyword weight | 0.3 |

参数进入 `RetrievalPlan` 和 AnswerRun Snapshot，不允许只存在 application.yml 中。

关键词召回采用 BM25。向量召回采用 cosine kNN。两路结果各自执行阈值过滤，随后按 `chunk_id` 去重并使用加权 RRF：

```text
score = vectorWeight / (rrfK + vectorRank)
      + keywordWeight / (rrfK + keywordRank)
```

禁止直接把 ES BM25 `_score` 与 cosine score 相加。

### 6.4 Rerank 和上下文补齐

RRF 后的前 40 个候选交给 rerank 模型。Rerank 文档使用：

```text
title
heading breadcrumb
chunk content
source type
```

Rerank 后执行：

- Chunk 去重
- 相同 Source 的数量限制
- Source Diversity 配额
- Parent/Child 或相邻 Chunk 上下文补齐
- Evidence 字符预算
- 最低证据充分性判断
- Ownership 和 Current Snapshot 最终复核

相邻 Chunk 只能作为上下文补齐，不能绕过 rerank 成为独立高分证据。所有补齐内容必须保留原始 Chunk ID、位置和 Citation locator。

### 6.5 QA 降级规则

正式运行的降级顺序：

```text
Hybrid + Rerank
  -> Hybrid without Rerank
  -> Keyword BM25 only
  -> MySQL lexical emergency fallback
```

每次降级都要写入稳定原因码：

```text
QA_QUERY_EMBEDDING_UNAVAILABLE
QA_VECTOR_RETRIEVAL_UNAVAILABLE
QA_KEYWORD_RETRIEVAL_UNAVAILABLE
QA_RERANK_UNAVAILABLE
QA_ES_UNAVAILABLE
QA_MYSQL_EMERGENCY_FALLBACK
```

降级结果可以服务用户，但不能作为正常质量门禁样本。生产 Readiness 要求 Embedding、ES Vector、ES Keyword 和 Rerank 都通过健康检查。

## 7. Note 按 Marginalia 改造

### 7.1 新模块

现有 `NoteRecallRetriever` 保留编排职责，内部重构为：

```text
NoteQueryPlanner
NoteTagResolver
NoteJournalSearchPort
NoteMetadataSearchPort
NoteSemanticSearchPort
NoteRelationExpansionService
NoteRecallFusionService
NoteRecallRerankService
NoteEvidenceQuotaSelector
NoteVerificationBatchPlanner
NoteEntryMetadataReader
NoteWindowLocator
NoteWindowReader
```

### 7.2 资料级召回通道

`recallKnowledge` 固定执行以下通道：

1. 解析 Tag hints，无法解析的 Tag 转为 text term。
2. 搜索历史 Note Journal。
3. 按 Tags、Title、Summary、Metadata、Source Type 和 Catalog 做关键词检索。
4. 查询 Note Source semantic index。
5. 合并候选并记录每个候选来自哪些通道。
6. 基于 Note 共引、回答共引、标签关系和资料关系做一跳扩展。
7. 对合并候选执行 rerank。
8. 按 Evidence Quota 选择 Candidate Sources。
9. 生成 Verification Batch。

Semantic Recall 是最终链路的固定组成部分，不设默认关闭开关。运行时故障可以降级，但发布版本必须完成语义索引并通过健康检查。

### 7.3 删除最近 40 份资料限制

删除 `NoteRecallRepository.findCandidates()` 中按更新时间读取 40 份资料的候选入口。Metadata Search 和 Semantic Search 必须在整个 Workspace 当前资料集合中执行索引查询。

MySQL 只负责：

- Source 和 Snapshot 真相
- Journal 和 Citation 关系
- 标签、Catalog 和结构化过滤真相
- 命中后的 ownership 与 current-version 复核

MySQL 不再负责全 Workspace 的模糊文本召回。

### 7.4 候选融合

每个 Note Source Candidate 记录：

```text
source_id
source_snapshot_id
matched_by
journal_rank
metadata_rank
semantic_rank
relation_rank
rrf_score
heuristic_score
rerank_score
selection_bucket
selection_reason
```

融合策略按 Marginalia 的思路实现：

- Journal、Metadata 和 Semantic 结果按排名进入 RRF。
- Tag exact match、Journal history、relation strength 和 readiness 作为可解释的 heuristic components。
- RRF 负责跨通道融合，不直接混合不同通道原始分数。
- Rerank 只重排合并池的前 N 个资料，不扫描全库。

建议默认参数：

| 参数 | 默认值 |
|---|---:|
| Journal recall | 100 |
| Metadata recall | 100 |
| Semantic recall | 100 |
| Fusion pool ceiling | 160 |
| Rerank top N | 80 |
| Candidate Sources | 12 |
| Relation expansion | 8 |
| Verification Batch | 12 |
| Final read Sources | 6 |
| Final Evidence Windows | 12 |

### 7.5 Evidence Quota

Note 不能只取总分最高的 N 个资料。最终选择至少包含以下桶：

```text
overlap       同时被多个召回通道命中
journal       历史调查或已保存 Note 命中
metadata      标签、标题、摘要或结构元数据命中
semantic      主要依靠语义补召命中
relation      由高可信关系扩展进入
```

默认按 Candidate Sources 数量动态分配配额，剩余名额按 rerank 排名回填。每个桶的实际配额和命中数写入 Trace。

### 7.6 验证批次和原文阅读

候选资料不能直接成为回答证据。进入 Verification Batch 后依次执行：

1. 读取 Source 当前 Snapshot 和解析状态。
2. 核对 Journal 引用的 Source 是否更新、删除或不可用。
3. 读取 Entry Metadata、目录、Section descriptions 和 Window locators。
4. 在候选 Source 内执行 Window lexical + semantic 定位。
5. 读取最佳窗口、相邻窗口和必要的补充窗口。
6. 对 Window 做局部 rerank。
7. 生成带 Source、Snapshot、Chunk、Window 和 location 的 Evidence。

Window 语义定位只在 Verification Batch 的 Source 范围内执行。禁止对全 Workspace 的 Window 建一条和 QA 重复的 Note 主召回链路。

### 7.7 Journal Freshness

保留并加强现有 Journal Freshness Guard：

- Journal 指向旧 Snapshot 时标记 `STALE_SOURCE_UPDATED`。
- Source 已删除或不可读时标记 `SOURCE_UNAVAILABLE`。
- Journal 只能影响候选召回和解释，不能直接作为事实证据。
- 最终回答必须引用当前可读原文窗口。

## 8. RetrievalPlan、Trace 和 Evidence 契约

### 8.1 QA RetrievalPlan

QA plan version 更新为 `qa-weknora-hybrid-v1`，至少保存：

```json
{
  "query_rewrite_version": "...",
  "embedding_model": "...",
  "embedding_dimensions": 1024,
  "embedding_version": "...",
  "index_schema_version": "...",
  "vector_top_k": 60,
  "keyword_top_k": 60,
  "vector_threshold": 0.0,
  "keyword_threshold": 0.0,
  "rrf_k": 60,
  "rrf_vector_weight": 0.7,
  "rrf_keyword_weight": 0.3,
  "rerank_model": "...",
  "rerank_top_n": 40,
  "max_evidence": 6,
  "max_evidence_characters": 8000
}
```

### 8.2 Note RetrievalPlan

Note plan version 更新为 `note-marginalia-funnel-v1`，至少保存：

```json
{
  "journal_limit": 100,
  "metadata_limit": 100,
  "semantic_limit": 100,
  "relation_expansion_limit": 8,
  "fusion_pool_ceiling": 160,
  "rrf_k": 60,
  "rerank_model": "...",
  "rerank_top_n": 80,
  "evidence_selection": "quota",
  "candidate_source_limit": 12,
  "verification_batch_limit": 12,
  "read_source_limit": 6,
  "evidence_window_limit": 12,
  "embedding_model": "...",
  "embedding_version": "...",
  "index_schema_version": "..."
}
```

### 8.3 Retrieval Trace

每个通道保存以下信息：

- 开始和结束时间
- 候选数量
- 阈值拒绝数量
- ownership 拒绝数量
- current snapshot 拒绝数量
- 各通道 rank
- RRF score 和参数
- rerank score、rank 和模型
- Evidence Quota 桶
- 字符预算淘汰原因
- 降级原因
- Embedding、Index Schema 和 Projection 版本

Trace 不保存 API Key、完整查询向量或完整原文副本。

### 8.4 EvidenceBundle

Evidence 必须同时保留：

```text
raw_retrieval_score
rrf_score
rerank_score
final_score
matched_channels
selection_reason
freshness_status
source_id
source_snapshot_id
chunk_id
window_no
location_info
```

Citation 仍由当前 EvidenceBundle 生成，不允许 retriever 直接构造绕过 ownership 校验的 Citation。

## 9. 现有代码改造清单

### 9.1 必须替换

| 当前实现 | 最终处理 |
|---|---|
| `ChunkSearchPort` | 替换为显式 vector/keyword 的 `QaHybridSearchPort` |
| `ElasticsearchChunkSearchAdapter` | 替换为 QA Hybrid Adapter |
| `QaPassageRetriever` | 重写为 `QaHybridRetriever`，只保留 Evidence 选择和 emergency fallback 的可复用部分 |
| `ElasticsearchIndexer` | 重写为版本化 QA Chunk + Note Source 双投影器 |
| `NoteRecallRepository.findCandidates()` | 删除 |
| `NoteRecallRanker` 的全库字符串扫描 | 删除，改为索引召回后的融合、解释和 quota |
| `NoteRecallRetriever` | 保留入口，重写内部编排 |
| `NoteReadingRetriever` | 增加候选 Source 内 lexical + semantic Window 定位和局部 rerank |

### 9.2 必须保留并接入新链路

- `RetrievalHydrator`
- `EvidenceOwnershipGuard`
- Current Snapshot 校验
- Source Scope 校验
- `EvidenceBundle`
- `EvidenceCitationAssembler`
- `AnswerRun` Snapshot
- `retrieval.summary`
- QA Shadow Export 和离线评测框架
- Note Journal Freshness Guard
- Note Relation Graph

### 9.3 必须删除的旧口径

- 文档和 UI 中把词法充分性过滤称为 rerank 的表述
- Note “最近资料优先即可覆盖候选池”的假设
- “向量召回以后再补”的描述
- 正常路径中的 `fulltext:bm25` 单通道标识
- 新 AnswerRun 对旧 `qa-retrieval-v2` 和旧 Note snapshot schema 的写入

旧 AnswerRun 保持只读回放，不迁移历史得分。

## 10. 数据库迁移

需要新增一组连续 Flyway migration，至少包含：

1. `retrieval_projection`
2. `retrieval_index_build`
3. Source Snapshot 的 QA/Note 双投影就绪状态
4. AnswerRun 的新 plan、trace 和 snapshot schema version
5. 索引重建任务和 dead-letter 状态
6. Embedding model/version、index schema version 的运行配置快照

不在 MySQL 中保存完整向量。MySQL 保存投影状态、版本、哈希和审计信息，向量保存在 Elasticsearch。

## 11. API 和管理能力

新增内部管理接口：

```text
POST /internal/retrieval/index-builds
GET  /internal/retrieval/index-builds/{id}
POST /internal/retrieval/index-builds/{id}/retry
POST /internal/retrieval/index-builds/{id}/verify
POST /internal/retrieval/index-builds/{id}/switch-alias
GET  /internal/retrieval/health
GET  /internal/retrieval/projections
```

新增 Workspace 级索引状态接口，前端至少展示：

- QA Chunk 索引状态
- Note Source 索引状态
- Embedding 模型和维度
- Rerank 状态
- 当前索引 schema version
- 待处理、失败和 stale 投影数

Source 未完成双投影时，资料页显示“正在建立 QA 和 Note 检索索引”，不能提前显示“可检索”。

## 12. 可观测性

新增指标：

```text
noteweave.retrieval.embedding.requests
noteweave.retrieval.embedding.failures
noteweave.retrieval.embedding.latency
noteweave.retrieval.qa.vector.hits
noteweave.retrieval.qa.keyword.hits
noteweave.retrieval.qa.rrf.candidates
noteweave.retrieval.qa.rerank.latency
noteweave.retrieval.note.journal.hits
noteweave.retrieval.note.metadata.hits
noteweave.retrieval.note.semantic.hits
noteweave.retrieval.note.relation.expansions
noteweave.retrieval.note.rerank.latency
noteweave.retrieval.projection.pending
noteweave.retrieval.projection.failed
noteweave.retrieval.index_build.progress
noteweave.retrieval.degraded
```

日志只能记录稳定 ID、计数、版本和原因码，不记录完整文档、向量、API Key 或未经脱敏的用户查询。

## 13. 测试和评测

### 13.1 单元测试

必须覆盖：

- Embedding 批处理、限长、重试和维度校验
- QA 双路召回参数
- RRF 排名和去重
- Rerank 映射和失败降级
- Note 多通道合并
- Evidence Quota
- Relation Expansion
- Journal Freshness
- Window 局部检索
- Ownership 和 Current Snapshot 拒绝
- 索引幂等和 stale 投影
- Alias switch

### 13.2 集成测试

使用真实 Elasticsearch 容器和可控 Embedding/Rerank stub，覆盖：

- Source 上传到双索引 READY
- QA Vector-only 命中同义表达
- QA Keyword-only 命中错误码和专有名词
- QA Hybrid RRF 排名
- Note Semantic-only 资料补召
- Note Journal + Metadata + Semantic 重叠候选
- Note Relation 扩展后原文验证
- Source v1/v2 并存时只命中 v2
- Workspace 和 Source Scope 隔离
- 模型维度变化后的新索引回填和 alias switch
- Embedding、ES、Rerank 故障时的降级 Trace

### 13.3 Gold 数据集

QA 和 Note 分开维护 Gold：

```text
backend/src/test/resources/retrieval/qa-weknora-gold-v1.json
backend/src/test/resources/retrieval/note-marginalia-gold-v1.json
```

QA 评测：

- Recall@K
- MRR
- NDCG@K
- Citation Precision
- Citation Coverage
- Refusal Accuracy
- Scope Violation
- p50/p95/p99 latency

Note 评测：

- Source Recall@K
- Source MRR
- Semantic-only Recall
- Journal-assisted Recall
- Relation-assisted Recall
- Verification Precision
- Evidence Window Precision
- Citation Coverage
- Stale Journal Rejection Accuracy
- p50/p95/p99 latency

### 13.4 消融评测

正式验收报告必须包含：

QA：

```text
Keyword only
Vector only
Hybrid RRF
Hybrid RRF + Rerank
Full pipeline + Evidence Budget
```

Note：

```text
Metadata only
Metadata + Journal
Metadata + Semantic
Metadata + Journal + Semantic
Full recall + Relations
Full recall + Relations + Rerank + Quota
```

消融结果用于证明每个组件的实际收益。没有可测收益的组件不能靠设计描述宣称有效。

## 14. 发布门禁

最终版本只有同时满足以下条件才能发布：

- 所有当前 Source 的 QA Chunk 和 Note Source 投影完成。
- 两个索引 alias 都指向当前 schema 和 embedding version。
- Embedding 与 Rerank 健康检查通过。
- QA 和 Note Gold 门禁通过。
- Scope Violation 为 0。
- Current Snapshot 错误命中为 0。
- Citation ownership 错误为 0。
- 全量 Backend 测试通过。
- 前端能展示双索引状态和失败原因。
- 重建、失败重试、dead-letter 和 alias switch 演练通过。
- 旧单通道检索不再写入新 AnswerRun。

以下状态不能发布：

- QA 只有 BM25，没有向量召回。
- QA 有向量召回但没有 RRF。
- QA 用手工分数相加代替 RRF。
- QA 或 Note 没有真实 rerank。
- Note 仍只扫描最近 40 份资料。
- Note Semantic Index 尚未完成回填。
- Source 只有一条检索链路完成索引。
- 新旧 embedding 版本在同一查询中混合比较。
- 正常 Gold 样本依靠降级路径通过。

## 15. 执行工作包

“只发布最终态”不等于没有施工顺序。以下工作包按依赖执行，所有工作包完成后统一切换正式版本，中途产物只允许在开发和测试环境存在。

### WP1：配置、Provider 和版本契约

- 增加 Embedding 和 Rerank 配置。
- 实现 Provider 接口、OpenAI-compatible 客户端和健康检查。
- 定义 embedding version、index schema version 和模型兼容规则。
- 扩展 `RetrievalPlan`、Snapshot 和 Trace schema。

完成标准：Provider contract test、超时、重试、限流和维度错误测试通过。

### WP2：投影状态和索引管理

- 增加 MySQL migration。
- 实现 `retrieval_projection` 和 `retrieval_index_build`。
- 实现索引创建、mapping、manifest、回填、验证和 alias switch。
- 实现 dead-letter 和 redrive。

完成标准：空 Workspace 和已有 Source Workspace 均能完成可重复重建。

### WP3：Source 双索引流水线

- 改造 `SourceParseService` 和 Kafka 事件。
- 实现 QA Chunk embedding/index。
- 实现 Note Source representation/embedding/index。
- 修改 Source READY 判定。

完成标准：上传一份资料后两个投影都 READY，重复消费不产生重复文档。

### WP4：QA WeKnora Hybrid

- 实现 Query Embedding。
- 实现 Vector 和 Keyword 双路召回。
- 实现 RRF、去重、上下文补齐和 rerank。
- 接回 Evidence Budget、ownership、Citation 和 Trace。
- 保留 emergency fallback，但从正常路径移除旧逻辑。

完成标准：QA 消融评测和端到端引用测试通过。

### WP5：Note Marginalia Funnel

- 实现 Tag Resolver、Journal Search、Metadata Search 和 Semantic Search。
- 实现统一融合、Relation Expansion、rerank 和 quota。
- 删除最近 40 份资料入口。
- 实现 Verification Batch、Entry Metadata 和 Source 内 Window 定位。
- 接回 Freshness、Evidence、Citation 和 Trace。

完成标准：Note 消融评测、stale Journal 和原文引用测试通过。

### WP6：管理面、可观测性和前端

- 增加索引管理 API。
- 增加 Workspace 和 Source 索引状态。
- 增加指标、日志和 Dashboard。
- 前端展示索引中、失败、重试和模型版本。

完成标准：运维人员可以完成重建、验证、切换和失败恢复，不需要直接修改数据库或 ES。

### WP7：全量回填和统一切换

- 为所有 Workspace 创建新 QA 和 Note 索引。
- 全量回填当前 Snapshot。
- 对比 MySQL expected count 和 ES indexed count。
- 执行抽样检索和 Gold。
- 原子切换两个 alias。
- 启用新 RetrievalPlan。
- 停止旧索引写入。

完成标准：所有发布门禁通过，并形成可复验的回填和切换回执。

### WP8：删除旧实现和文档收敛

- 删除无调用的旧 Port、Adapter、Ranker 和配置。
- 删除旧策略开关。
- 更新 QA、Note、Source、API、数据库和总体架构文档。
- 更新项目亮点描述，确保只描述真实运行的 Hybrid、Semantic Recall 和 Rerank。

完成标准：代码搜索不到新运行时对旧策略 version 的写入，文档不存在“以后再加向量”的正式设计表述。

## 16. 切换和回滚

### 16.1 切换

切换必须以索引 alias 和 RetrievalPlan version 为两个原子边界：

1. 完成 QA 和 Note 新索引回填。
2. 验证 mapping、版本、数量和抽样查询。
3. 切换 QA 和 Note alias。
4. 启用 `qa-weknora-hybrid-v1` 和 `note-marginalia-funnel-v1`。
5. 停止旧投影写入。
6. 观察错误率、降级率、p95 和 Citation 指标。

### 16.2 回滚

回滚只处理发布事故，不作为长期双策略运行：

- alias 可切回上一完整索引。
- RetrievalPlan 可切回上一完整版本。
- 回滚目标必须也是完成双索引、Embedding 和 Rerank 的上一正式版本。
- 不回滚到当前仅 BM25 或最近 40 份资料的旧实现。

## 17. 完成定义

本改造在以下事实同时成立时完成：

- QA 的正常请求实际调用 Query Embedding、Vector Retrieve、Keyword Retrieve、RRF 和 Rerank。
- Note 的正常请求实际调用 Journal、Metadata、Semantic Recall、Relations、Rerank、Quota 和原文窗口读取。
- Source 入库会生成 QA Chunk 和 Note Source 两类向量投影。
- 新旧 Snapshot、模型版本和索引版本不会混合。
- QA 与 Note 都能产生完整、可回放的 RetrievalPlan 和 Trace。
- Citation 只来自当前 Snapshot 的可定位原文。
- Gold、消融、性能、隔离和故障测试全部通过。
- 旧轻量检索不再参与新 AnswerRun 的正常执行。

完成后，NoteWeave 的检索口径固定为：QA 是 WeKnora 式 Chunk Hybrid RAG，Note 是 Marginalia 式结构化资料检索漏斗。两条链路共享索引基础设施和证据契约，但保持不同的召回对象和阅读方式。

## 18. 参考实现源码对照与知识点验收

本节基于当前仓库内 `reference/WeKnora` 与 `reference/marginalia` 的真实源码入口逐项复核。

### 18.1 QA / WeKnora 对照

WeKnora 的调用顺序是：`query_understand` 完成会话问题改写和意图识别，`HybridSearch` 预先生成 Query Embedding，按 Store/Embedding Model 分组并校验 embedding space，一次执行 Vector 与 Keyword 过召回及加权 RRF；召回不足时触发本地 Query Expansion；随后对清洗过的 passage 执行 rerank、阈值准入、多样性控制、Parent/Child 上下文补齐和 Evidence 组装。源码入口：

- `reference/WeKnora/internal/application/service/chat_pipeline/query_understand.go`
- `reference/WeKnora/internal/application/service/chat_pipeline/query_expansion.go`
- `reference/WeKnora/internal/application/service/chat_pipeline/rerank.go`
- `reference/WeKnora/internal/application/service/knowledgebase_search.go`
- `reference/WeKnora/internal/application/service/knowledgebase_search_fusion.go`

NoteWeave 的对应实现：

- 会话上下文编译结果作为 `original_query`、`retrieval_queries`、`must_terms`、`preferred_terms`、`language`、`question_type` 写入 RetrievalPlan。
- Vector/BM25 各 60 条过召回，融合上限 100，`RRF k=60`、Vector/Keyword 权重 `0.7/0.3`，rerank 上限 40，参数随计划快照保存。
- 低召回时基于当前问题和主题锚点生成有限 Query Expansion，并对每个变体执行双路召回和去重。
- rerank 输入去除 Markdown 图片、链接 URL、代码块和结构噪声；Provider 未返回的候选保留在尾部，避免部分响应造成证据丢失。
- rerank 后执行 Source Diversity、Evidence Budget、Ownership/Current Snapshot 校验；相邻 Chunk 只补齐同一证据的上下文，Citation 仍指向原始 seed Chunk。

### 18.2 Note / Marginalia 对照

Marginalia 的 `recall_knowledge` 是固定漏斗：Tag Resolver 先解析 facet/alias，Journal 与 Metadata 并行召回，Semantic Index 做资料级补充，统一评分后执行 evidence quota 和真实 rerank，再做一跳关系扩展和 verification batch。Journal 会检查引用资料是否已重摄取、删除或失效，原文读取只在验证通过后打开窗口。源码入口：

- `reference/marginalia/src/marginalia/agent/tools/recall_knowledge.py`
- `reference/marginalia/src/marginalia/agent/tools/search_journal.py`
- `reference/marginalia/src/marginalia/agent/tools/search_metadata.py`
- `reference/marginalia/src/marginalia/semantic/index.py`
- `reference/marginalia/src/marginalia/semantic/rerank.py`

NoteWeave 的对应实现：

- Source tag vocabulary 先解析 facet tag；无法解析的词回到文本召回，并将 `resolved-tag` 写入 Source recall signal。
- Journal freshness、ES Metadata、Source Semantic Recall、标签/共引/回答共引/图邻居关系进入同一个 Source 候选池。
- 候选池执行 Journal/Metadata/Relation/Source type 配额，真实 Source rerank 后保留未返回候选，随后执行 relation expansion 和 verify batch。
- 只对当前 `INDEXED` Snapshot 读取 Entry Metadata 与原文窗口；窗口带 chunk/window/heading/locator/read role，失效 Journal 只能作为降权审计线索。
- 双投影、版本化索引、alias 原子切换和质量 receipt 是 NoteWeave 对 Marginalia 本地资料真相源的等价工程实现。

### 18.3 不复制的项目特有模型

WeKnora 的 Tenant/KnowledgeBase/Store 绑定、FAQ 专用迭代查询和多模态图片改写，以及 Marginalia 的 SQLite/DuckDB、Catalog/FileEntry/WebDAV 数据模型，不属于 NoteWeave 的 Workspace/Source/SourceSnapshot 边界。这些项目特有模型不复制；其检索职责、生命周期、证据和失败语义映射到 NoteWeave 的 Java 模块与持久化契约。
