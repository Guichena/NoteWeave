# Phase 6: 个人 ResearchProject 与 Source 导入

本文档描述 Phase 6 的权威实现口径。Phase 6 的目标是建立个人研究资料入口，让用户在自己的 PERSONAL Space 下创建 ResearchProject，并把文件、URL、文本导入为可供后续编译的 Source。

---

## 1. 本阶段范围

Phase 6 完成以下能力：

- 个人 `ResearchProject` 的创建、列表、详情、更新、归档。
- 个人 `Source` 的 FILE / URL / TEXT 导入。
- `SOURCE_IMPORT` Task、失败重试、去重、重新导入。
- `raw_text_object_key` / `parsed_text_object_key` 持久化。
- owner-only 的 Source 列表、详情、删除、重新导入。

本阶段不做：

- ArticleCard / ConceptCard / MethodologyCard。
- 个人问答、个人生成、个人 Artifact。
- 外部资料自动发现。
- Quiz / 题库 / 评分。

---

## 2. 关键边界

- `ResearchProject` 只能属于当前用户的 ACTIVE `PERSONAL` Space。
- 个人资源不能只按 id 查询，必须在 `space_id + owner` 语义下访问。
- `SourceType` 在 Phase 6 固定为 `FILE | URL | TEXT`。
- FILE 复用 `DocumentParserService`，MVP 只支持 `.txt`、`.md`、`.markdown`、`.pdf`。
- `importStatus = READY` 的 Source 必须至少有一个可读取的 `raw_text_object_key` 或 `parsed_text_object_key`。
- 原始文件 `object_key` 不能单独让 Source 进入 READY。
- 父 `ResearchProject` 已归档或软删后，`GET /personal/sources/{id}`、`POST /personal/sources/{id}/import` 等接口都应视为不可操作；后台 import worker 也应直接跳过。

---

## 3. 数据模型

### ResearchProject

表：`research_project`

字段：

```text
id
spaceId
userId
title
description
researchGoal
compileStatus
status
deletedAt
deletedBy
createdAt
updatedAt
```

状态：

```text
compileStatus = PENDING / COMPILING / READY / FAILED
status = ACTIVE / ARCHIVED
```

### Source

表：`source`

字段：

```text
id
spaceId
researchProjectId
title
sourceType
url
objectKey
rawTextObjectKey
parsedTextObjectKey
contentHash
importStatus
compileStatus
tokenCount
errorMessage
createdBy
deletedAt
deletedBy
createdAt
updatedAt
```

状态：

```text
importStatus = PENDING / IMPORTING / READY / FAILED
compileStatus = PENDING / COMPILING / READY / FAILED
```

---

## 4. 导入与状态流转

### TEXT

- 创建时直接写入 `raw_text_object_key`。
- 当场计算 `content_hash`。
- 不创建 `SOURCE_IMPORT` task。
- 文本对象可读后直接标记 `READY`。

### FILE

- 上传请求直接把原始文件写入 MinIO，保留 `object_key`。
- 创建 `SOURCE_IMPORT` task。
- worker 复用 `DocumentParserService` 解析正文。
- 成功写出 `parsed_text_object_key` 且对象可读后，才允许标记 `READY`。

### URL

- 创建时先规范化 URL，并做同项目下同 URL 预去重。
- 创建 `SOURCE_IMPORT` task。
- worker 通过安全 URL 抓取层拉取内容，再交给 Tika/文本逻辑抽取正文。
- 非 2xx、空正文、抽取失败、超时、跳转越界、响应体超限都标记 `FAILED`，绝不标记 `READY`。

### SOURCE_IMPORT

- 复用现有通用 `task / task_attempt / task_event / task_outbox / Kafka` 链路。
- 不新增专用 topic，不改 `DOCUMENT_PROCESS` 现有专用链路。
- payload 至少包含：

```text
sourceId
researchProjectId
spaceId
sourceType
objectKey
url
title
```

- 重新导入语义是“发起一次新的业务导入尝试”：
  - 若已有 `PENDING / RUNNING` 的 `SOURCE_IMPORT`，直接返回已有 `taskId`。
  - 否则新建 task，`idempotencyKey = SOURCE_IMPORT:{sourceId}:attempt:{n}`。
- 对已经 `READY` 的 FILE / URL Source 执行重新导入时，必须创建 fresh attempt：
  - FILE 必须重新读取原始 `object_key` 并重新解析，不得把旧 `parsed_text_object_key` 当作新的业务导入结果。
  - URL 必须重新抓取正文并写入新的 `raw_text_object_key`，不得把旧正文缓存当作重新导入成功。
- 重新导入开始前必须把 `source.compileStatus` 与父 `research_project.compileStatus` 统一回退为 `PENDING`，等待后续 Phase 7 重新编译。
- 这与通用 `/tasks/{taskId}/retry` 不同；后者是同一 task 的 generic retry。

---

## 5. URL 安全抓取要求

Phase 6 的 URL 导入必须通过安全抓取层，而不是直接把用户 URL 丢给 `HttpClient`。

必须具备：

- 仅允许 `http` / `https`。
- 禁止 `localhost`、`127.0.0.1`、内网地址、链路本地地址、loopback、site-local、unique-local。
- 每次重定向都重新做 host / IP 校验。
- 显式限制最大重定向次数。
- 显式限制连接超时、请求/读取超时。
- 显式限制最大响应体大小。

---

## 6. 去重规则

- FILE / TEXT：在项目锁内按 `content_hash` 去重；若同项目已有未删除 Source，同内容直接返回已有 Source。
- URL：创建时先按规范化 URL 预去重。
- URL 内容级去重：在 worker 内、持有项目锁时按 `content_hash` canonicalize；若命中已有 canonical Source，则把当前临时 Source 软删，并把 task `resultRefType/Id` 指向 canonical Source。

---

## 7. 对象 key 规则

```text
{prefix}/source-files/{contentHash}/{fileName}
{prefix}/raw-text/source/{sourceId}/{attempt}.txt
{prefix}/parsed-text/source/{sourceId}/{attempt}.txt
```

其中：

- `{prefix}` 在本地开发为 `dev`
- `{prefix}` 在测试为 `test/{testRunId}`

---

## 8. API

### ResearchProject

```http
POST   /api/v1/personal/research-projects
GET    /api/v1/personal/research-projects
GET    /api/v1/personal/research-projects/{projectId}
PUT    /api/v1/personal/research-projects/{projectId}
DELETE /api/v1/personal/research-projects/{projectId}
```

### Source

```http
POST   /api/v1/personal/research-projects/{projectId}/sources/upload
POST   /api/v1/personal/research-projects/{projectId}/sources/url
POST   /api/v1/personal/research-projects/{projectId}/sources/text
GET    /api/v1/personal/research-projects/{projectId}/sources
GET    /api/v1/personal/sources/{sourceId}
POST   /api/v1/personal/sources/{sourceId}/import
DELETE /api/v1/personal/sources/{sourceId}
```

`SourceResponse` 需要包含最近活跃或最近一次 `SOURCE_IMPORT` 的 `taskId`，供前端继续查询 `/api/v1/tasks/{taskId}`。

---

## 9. 测试要求

- 本阶段必须先写失败测试，再实现最小代码。
- 集成测试继续使用 `ContainerizedIntegrationTest` 提供 MySQL / Redis / MinIO / Kafka / Elasticsearch Testcontainers。
- 测试 profile 下 dispatcher scheduler 是关闭的，因此异步 task 需要显式调用 `taskDispatcher.dispatchPendingMessages()` 再等待状态流转。
- URL 相关集成测试不依赖外网；建议通过 mock 抓取器或等价方式隔离真实网络。
- 需要覆盖 READY 状态下 URL / FILE 重新导入会创建新的导入 attempt，而不是静默复用旧正文对象。
- 需要覆盖重新导入会把 `source.compileStatus` 与 `research_project.compileStatus` 一并失效化回 `PENDING`。
- 测试临时路径必须使用：

```text
target/noteweave-test/phase6/
```
