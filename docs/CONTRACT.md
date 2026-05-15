# NoteWeave Contract

本文档是当前实现口径的最小契约。编码时若旧功能说明、阶段文档或示例与本文档冲突，以本文档、`docs/implementation_breakdown.md` 和 `docs/features/database_api_blueprint.md` 为准。

---

## 1. 开发流程

所有编程阶段默认采用测试驱动开发。

执行顺序固定为：

```text
1. 先根据当前 Phase 的验收标准写测试
2. 运行测试，确认关键测试失败且失败原因符合预期
3. 再写最小实现让测试通过
4. 重构和补齐边界处理
5. 最后运行当前 Phase 相关测试和必要回归测试
```

要求：

- 不允许先大段实现再补测试，除非是纯文档、纯配置或无法测试的脚手架改动。
- 每个 Phase 至少覆盖成功路径、权限失败路径、状态流转失败路径和关键幂等场景。
- 如果某个行为暂时无法自动化测试，必须在 `docs/PROJECT_STATUS.md` 的阶段记录中写清楚原因和手动验证方式。
- 阶段完成前必须完成当前阶段测试和必要回归测试；最终回复不需要逐项汇报测试过程，但不能跳过验证。

---

## 2. Docker 中间件

所有中间件必须通过 Docker 或 Testcontainers 提供。

本地开发使用：

```text
docker-compose.yml
```

测试使用：

```text
Testcontainers
```

必须容器化的中间件：

```text
MySQL
Redis
MinIO
Elasticsearch
Kafka
```

规则：

- 不允许要求开发者本机散装安装 MySQL、Redis、MinIO、Elasticsearch 或 Kafka。
- 如果某个 Phase 新增中间件依赖，必须同步更新 `docker-compose.yml`、`.env.example`、`docs/DOCKER_MIDDLEWARE.md` 和测试容器配置。
- 集成测试不得依赖 `docker compose up -d` 已经执行，必须自行通过 Testcontainers 启动所需服务。
- 测试用本地临时路径统一放在 `target/noteweave-test/{phase}/`。
- MinIO 测试对象必须写入 `noteweave-test` bucket，并使用 `test/` 对象前缀。
- Elasticsearch 测试索引必须使用 `noteweave-test-{testRunId}-` 前缀，测试结束后清理。
- Kafka 测试 topic 必须使用 `test.noteweave.*.{testRunId}` 命名，避免污染本地开发 topic。

详细规则见：

```text
docs/DOCKER_MIDDLEWARE.md
```

---

## 3. API

所有业务 API 使用统一前缀：

```text
/api/v1
```

旧的 `/api/...` 示例视为历史口径，不进入当前实现。

统一响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {}
}
```

统一分页响应：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0,
  "sort": "createdAt,desc"
}
```

---

## 4. 权限

`Space` 是最高业务容器：

```text
space.type = PERSONAL / TEAM
```

团队权限：

```text
SpaceMember.role = OWNER / EDITOR / VIEWER
```

系统权限：

```text
users.system_role = USER / ADMIN
```

`ADMIN` 只能表示系统后台权限，不能和团队 `OWNER` 混用。

所有资源读取都必须经过资源访问校验，不能只按 id 查询。尤其是：

```text
Document
DocumentChunk
Citation
Artifact
Source
ArticleCard
ConceptCard
SynthesisCard
WikiPage
```

---

## 5. Task

所有异步任务统一使用 `task`，不得新增 `generation_task`、`index_task` 等并行任务表。

Task 状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
TIMEOUT
```

Task 类型：

```text
DOCUMENT_PROCESS
DOCUMENT_REINDEX
SOURCE_IMPORT
SOURCE_COMPILE
ARTIFACT_GENERATE
EMBEDDING_BACKFILL
WIKI_INDEX
RAG_EVAL_RUN
CLEANUP_RESOURCE
```

Task 基础设施：

```text
task
task_attempt
task_event
task_outbox
```

异步投递边界：

```text
DB transaction -> task/task_outbox -> Kafka -> Worker -> task/task_attempt/task_event
```

规则：

- Kafka 是当前实现唯一的后台异步任务消息队列。
- `task_outbox` 是事务外盒和补偿投递表，不是业务执行队列。
- Worker/Consumer 收到 Kafka 消息后只信任 `taskId`，必须回查 DB task 状态和 payload。
- Redis Stream 只允许用于 Phase 5 Chat runtime 的流式状态、断线恢复和短期上下文，不承载上传、解析、索引、生成、评测等后台任务。
- RabbitMQ、Redis Stream 任务队列或内存队列都属于历史或备选口径，不进入当前实现，除非后续契约显式修订。

规则：

- `idempotency_key` 必须稳定且唯一。
- Worker 消费消息后先查 DB task 状态。
- 只有 `PENDING` 可以进入执行。
- `RUNNING` 取消只设置 `cancel_requested`，由 Worker 在安全点停止。
- `FAILED / TIMEOUT` 可以按 `max_retry_count` 重试。

---

## 6. Artifact 与 Wiki

Artifact 是生成产物层，Wiki 是长期知识层。

规则：

```text
Artifact 默认不进入 Wiki
```

团队侧：

```text
Artifact -> WikiDraft/WikiPage -> WikiPageVersion -> WIKI_INDEX Task -> Team RAG Index
```

个人侧：

```text
Artifact -> SynthesisCard -> Personal Wiki Index
```

个人侧增强阶段：

```text
Artifact -> Concept merge proposal -> 用户确认合并
Artifact -> Methodology proposal -> 用户确认写入
```

全局允许 Artifact 类型：

```text
REPORT
STUDY_GUIDE
READING_NOTES
BRIEFING
FAQ
COMPARISON
WIKI_DRAFT
ONBOARDING_GUIDE
TECHNICAL_SUMMARY
INCIDENT_REVIEW_DRAFT
PRESENTATION_OUTLINE
TIMELINE
WORK_PREP
MIND_MAP_OUTLINE
```

说明：

- 全局枚举不等于每个 Phase 都必须实现。
- Phase 8 MVP 只实现 `REPORT / STUDY_GUIDE / BRIEFING / FAQ / COMPARISON / WIKI_DRAFT`。
- 其他 Artifact 类型只有在对应 Phase 文档明确列入目标和验收时才实现。

Quiz、测验、答题、评分、题库、错题复习暂缓，不进入当前 DDL、API、前端路由和验收。

---

## 7. Citation / Evidence

Citation 必须可回溯：

```text
sourceType
sourceId
chunkId
pageNo
startOffset
endOffset
quoteHash
snapshotObjectKey
sourceVersion
```

Evidence 关联必须使用关系表：

```text
message_citation
artifact_citation
article_card_citation
concept_card_citation
synthesis_card_citation
wiki_page_citation
```

`evidence_quotes_json` 只能作为展示缓存，不能作为唯一证据来源。

---

## 8. 上传与 Source

文件对象复用不能穿透 Space 权限：

```text
file_object UNIQUE(space_id, content_hash)
```

秒传只能复用对象内容，不复用 Document 元数据和权限。

个人 Source 规则：

```text
import_status = READY
```

时必须满足：

```text
raw_text_object_key IS NOT NULL
OR parsed_text_object_key IS NOT NULL
```

---

## 9. 软删除

核心资源默认软删除，不做物理删除：

```text
deleted_at
deleted_by
```

当前至少覆盖：

```text
knowledge_base
document
research_project
source
artifact
wiki_page
```
