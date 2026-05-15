# Phase 9 Prompt: Retrieval Enhancement / Vector / Weighted RRF

你是 NoteWeave 项目的编码代理。请执行 Phase 9：增强检索、向量召回与 Weighted RRF。

## 测试驱动执行规则

本阶段必须采用测试驱动开发：

```text
1. 先根据本阶段目标和验收标准写测试
2. 运行测试，确认关键测试失败且失败原因符合预期
3. 再写最小实现让测试通过
4. 重构和补齐边界处理
5. 最后运行当前阶段相关测试和必要回归测试
```

## Docker 中间件执行规则

本阶段涉及的所有中间件必须通过 Docker Compose 或 Testcontainers 提供：

```text
MySQL
Redis
MinIO
Elasticsearch
Kafka
```

要求：

- 不允许依赖本机散装安装的中间件。
- 本地开发中间件统一维护在根目录 `docker-compose.yml`。
- 集成测试中间件统一使用 Testcontainers。
- 当前 Phase 新增中间件、bucket、topic、index 或测试路径时，必须同步更新 `docs/DOCKER_MIDDLEWARE.md`。
- 测试临时路径统一使用 `target/noteweave-test/{phase}/`，不能写用户机器绝对路径。

## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_3_document_processing_indexing.md
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_9_retrieval_enhancement_rrf.md
```

## 目标

实现增强检索：

```text
EmbeddingClient
EMBEDDING_BACKFILL Task
Vector index / alias
BM25Retriever
VectorRetriever
Weighted RRF
EvidencePostProcessor
RetrievalTrace
```

## 严格边界

不要实现：

```text
团队 Wiki 发布
个人 Wiki 生成
Artifact 生成
评测平台完整 UI
Quiz
```

## 必须遵守

- RRF 权重使用文档定稿值。
- Embedding 维度变化必须创建新索引版本或 alias，不能破坏旧索引。
- 向量失败时可降级 BM25。
- EvidencePostProcessor 要做相邻 Chunk 合并、低分过滤、同文档限流。
- 检索 trace 必须能解释每路召回和融合结果。

## 交付要求

实现代码和测试。并确保以下内容已落实：

```text
RRF 权重
向量索引版本策略
降级策略
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

