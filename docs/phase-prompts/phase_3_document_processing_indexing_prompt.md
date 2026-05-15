# Phase 3 Prompt: Document Processing / Chunk / Indexing

你是 NoteWeave 项目的编码代理。请执行 Phase 3：文档解析、Chunk 切片与 Elasticsearch 索引。

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
- 先写 Document processing / Chunk / ES 索引相关失败测试。
- 覆盖重复消费、失败状态、权限过滤、删除/归档不可召回。

parser-agent:
- 负责 DocumentParser、parsed text 保存、文件类型支持边界。
- 不修改 ES 索引和 Worker 状态机。

chunk-agent:
- 负责 DocumentChunk、chunk metadata、indexVersion 数据模型和幂等约束。
- 不修改 LLM、Citation 或 RAG Chat。

index-agent:
- 负责 Elasticsearch BM25 index、activeIndexVersion 切换、Search Debug。
- 保证查询始终带 space / knowledgeBase / status filter。

worker-agent:
- 负责 DOCUMENT_PROCESS Worker 与 TaskAttempt / Document 状态回写。
- 只消费 taskId，执行前查 DB task 状态。

review-agent:
- 只做 review，不直接改代码。
- 重点检查幂等、权限、版本切换、失败回滚和测试缺口。
```
## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_2_file_upload_async_ingestion.md
docs/features/phase_3_document_processing_indexing.md
docs/features/file_upload_async_pipeline.md
```

## 目标

完成团队文档处理链路：

```text
DOCUMENT_PROCESS Worker
DocumentParser
parsed text 保存
DocumentChunk
indexVersion
activeIndexVersion
ES BM25 索引
重复消费幂等
失败写 TaskAttempt / Document 错误状态
```

## 严格边界

不要实现：

```text
RAG Chat
LLM 回答
Citation
Embedding 向量召回
个人 Source
Artifact
Quiz
```

## 必须遵守

- Worker 只消费 taskId，执行前查 DB task 状态。
- Chunk 唯一约束使用 `documentId + indexVersion + chunkIndex`。
- Reindex 必须先创建新版本，成功后切换 active，失败不破坏旧索引。
- 文档删除或 KB 归档后不能被检索召回。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
支持哪些文件类型
indexVersion 如何工作
重复消费如何幂等
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

