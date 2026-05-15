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

## Subagent 分工模板

本节描述 AI 编码代理执行本 Phase 时允许使用的 subagent 协作方式。`subagent` 只表示编码执行分工，不是 NoteWeave 产品运行态 Agent/Skill 设计。

通用规则：

- 主代理是本 Phase 的 owner，负责最终集成、测试、文档更新和交付结论。
- subagent 必须先按本文档的“必读文档”顺序读取上下文，再开始自己的子任务。
- 每个 subagent 必须有明确 ownership，限定可修改的模块、文件或测试范围。
- 不允许多个 subagent 同时修改同一文件或同一模块；需要交叉修改时由主代理统一合并。
- 不允许 subagent 扩大当前 Phase 范围，遇到范围外问题只记录为遗留风险。
- 所有实现仍必须遵守 TDD：先写失败测试，再写最小实现，再重构和回归。
- subagent 产出必须由主代理 review 后合入，主代理不能直接信任未验证结果。

推荐分工：

```text
lead-agent:
- 读取全部必读文档，拆分任务，维护 Phase 边界。
- 负责最终集成、运行回归测试、更新 PROJECT_STATUS。

test-agent:
- 先写 Embedding backfill、VectorRetriever、Weighted RRF、降级路径测试。
- 覆盖权限 filter 始终存在、向量失败降级 BM25。

embedding-agent:
- 负责 EmbeddingClient、模型配置、EMBEDDING_BACKFILL Task。
- 不修改团队 Wiki 发布逻辑。

vector-index-agent:
- 负责 ES vector mapping、alias、维度版本绑定、VectorIndexerService。
- 保证历史 Chunk 可 backfill。

retriever-agent:
- 负责 BM25Retriever、VectorRetriever、WikiRetriever 抽象统一。
- 所有检索实现必须保留权限过滤。

rrf-agent:
- 负责 Weighted RRF、EvidencePostProcessor 增强、同文档限流。
- 不改变 Phase 4 的 Citation 契约。

trace-agent:
- 负责 RRF trace 写入 RetrievalTrace 的最小集成。
- 不实现 Phase 14 完整评测平台。

review-agent:
- 只做 review，不直接改代码。
- 重点检查降级路径、权限 filter、向量维度迁移和 trace 可解释性。
```
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

