# Phase 1.5: Task / Outbox / Worker 基础设施

本文档用于指导 NoteWeave Phase 1.5 编码实现。

范围：

```text
Phase 1.5: Task / TaskAttempt / TaskEvent / TaskOutbox / Worker / Dispatcher
```

本阶段目标是在上传、解析、索引、Source 编译、Artifact 生成、Wiki 入索引等长任务接入前，先建立统一异步任务底座。后续阶段只能复用这套 Task/Worker 模型，不再各自创建 `generation_task`、`index_task` 等并行概念。

---

## 1. 参考文档

请严格参考：

```text
docs/features/database_api_blueprint.md
docs/implementation_breakdown.md
docs/features/phase_0_1_bootstrap_auth_space.md
```

后续阶段依赖本文档：

```text
docs/features/phase_2_file_upload_async_ingestion.md
docs/features/phase_3_document_processing_indexing.md
docs/features/phase_6_personal_research_source.md
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_10_team_wiki_publish_index.md
```

---

## 2. 阶段目标

Phase 1.5 完成后，系统应具备：

- 使用统一 `task` 表承载所有异步任务。
- 使用 `task_attempt` 记录每次执行尝试。
- 使用 `task_event` 记录状态迁移和可观察事件。
- 使用 `task_outbox` 保证数据库提交与队列投递最终一致。
- 支持任务创建幂等。
- 支持任务查询、取消、失败重试。
- Worker 执行前后都按 DB 状态做幂等检查。
- RUNNING 任务支持 `cancel_requested`，Worker 在安全点停止。
- Dispatcher 可以补偿投递未发送的 Outbox 消息。
- Admin / 前端可以查看任务状态、事件和错误原因。

---

## 3. 本阶段不做的事

- 不做真实文档解析。
- 不做真实 Embedding。
- 不做真实 LLM 生成。
- 不做 Wiki 入索引。
- 不做复杂工作流编排。
- 不做分布式锁的复杂优化。
- 不做任务优先级队列。

本阶段只实现通用任务地基和一个可测试的示例 Worker。

---

## 4. 依赖 Phase 0/1 的能力

本阶段依赖：

```text
User
Space
SpaceMember
JWT
CurrentUserProvider
SpacePermissionService
统一 ApiResponse
统一 PageResponse
统一错误码
```

权限要求：

- 查询任务时，用户必须能访问 `task.spaceId`。
- 个人 ResearchProject 任务只能由 owner 查询。
- 取消任务需要满足任务所属资源的编辑权限。
- Admin 可以查询全部任务，但敏感 `inputJson / outputJson / errorMessage` 要脱敏。

---

## 5. 数据模型

以 `database_api_blueprint.md` 为准。

核心表：

```text
task
task_attempt
task_event
task_outbox
```

### 5.1 Task

`task` 是所有异步任务的主表。

关键字段：

```text
id
userId
spaceId
researchProjectId
taskType
targetType
targetId
taskStatus
idempotencyKey
inputJson
outputJson
errorMessage
cancelRequested
retryCount
maxRetryCount
resultRefType
resultRefId
startedAt
finishedAt
createdAt
updatedAt
```

### 5.2 TaskAttempt

`task_attempt` 记录每次 Worker 执行尝试。

每次 RUNNING 都必须创建 attempt：

```text
taskId
attemptNo
workerId
status
startedAt
finishedAt
errorCode
errorMessage
```

### 5.3 TaskEvent

`task_event` 记录状态变化和关键过程事件。

事件示例：

```text
TASK_CREATED
TASK_DISPATCHED
TASK_STARTED
TASK_PROGRESS
TASK_CANCEL_REQUESTED
TASK_CANCELLED
TASK_SUCCEEDED
TASK_FAILED
TASK_RETRY_CREATED
OUTBOX_SENT
OUTBOX_SEND_FAILED
```

### 5.4 TaskOutbox

`task_outbox` 用于保证 Task 创建和消息投递最终一致。

Outbox 只记录待投递事实，不承载业务执行结果。业务执行结果必须回写 `task / task_attempt / task_event`。

---

## 6. 状态机

Task 状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
TIMEOUT
```

允许流转：

```text
PENDING -> RUNNING
PENDING -> CANCELLED
RUNNING -> SUCCESS
RUNNING -> FAILED
RUNNING -> CANCELLED
RUNNING -> TIMEOUT
FAILED -> PENDING    // retry 创建新 attempt 前回到 PENDING
TIMEOUT -> PENDING   // retry 创建新 attempt 前回到 PENDING
```

禁止流转：

```text
SUCCESS -> RUNNING
SUCCESS -> FAILED
SUCCESS -> CANCELLED
CANCELLED -> SUCCESS
```

取消规则：

- `PENDING` 任务取消时直接进入 `CANCELLED`。
- `RUNNING` 任务取消时只设置 `cancelRequested = true`。
- Worker 必须在安全点检查 `cancelRequested`。
- Worker 检查到取消后，写入 `TASK_CANCELLED` 事件并将任务置为 `CANCELLED`。

重试规则：

- 默认只允许 `FAILED / TIMEOUT` 任务重试。
- `retryCount >= maxRetryCount` 时禁止重试。
- 重试不复用旧 attempt，必须创建新的 `task_attempt`。
- 重试必须写入 `TASK_RETRY_CREATED` 事件。

超时规则：

- Worker 或调度器发现任务超过允许执行时间时，将任务置为 `TIMEOUT`。
- `TIMEOUT` 需要记录 `TASK_TIMED_OUT` 事件和当前 attempt 的错误信息。
- `TIMEOUT` 不等同于业务失败，后续排障和重试策略必须能单独筛选。

---

## 7. Task 类型

Phase 1.5 只要求支持通用 Task 框架，但需要预留以下类型：

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

本阶段可实现一个示例类型：

```text
NOOP_TEST
```

用于验证状态流转、attempt、event、outbox、取消和重试。

---

## 8. 幂等规则

创建 Task 时必须生成稳定 `idempotencyKey`。

推荐格式：

```text
{taskType}:{spaceId}:{targetType}:{targetId}:{businessHash}
```

示例：

```text
DOCUMENT_PROCESS:team-1:DOCUMENT:1001:v1
SOURCE_COMPILE:personal-2:SOURCE:2001:compile-v1
WIKI_INDEX:team-1:WIKI_PAGE_VERSION:3001:v1
```

规则：

- 相同 `idempotencyKey` 的请求不得重复创建 Task。
- 如果已存在 `PENDING / RUNNING` 任务，直接返回已有任务。
- 如果已存在 `SUCCESS` 任务，默认返回已有结果。
- 如果已存在 `FAILED / TIMEOUT` 任务，由调用方决定是否调用 retry。

---

## 9. 后端服务设计

推荐服务：

```text
TaskService
TaskAttemptService
TaskEventService
TaskOutboxService
TaskDispatcher
TaskWorkerRegistry
TaskWorker
TaskCancellationChecker
```

### TaskService

职责：

- 创建幂等任务。
- 查询任务详情。
- 查询任务列表。
- 取消任务。
- 重试任务。
- 更新任务状态。

### TaskDispatcher

职责：

- 扫描 `task_outbox.status = PENDING`。
- 投递 Kafka / queue 消息。
- 投递成功后标记 `SENT`。
- 投递失败后增加 `retryCount` 和 `nextRetryAt`。
- 使用 `idempotencyKey` 防止重复投递造成重复执行。

### TaskWorker

接口示例：

```java
public interface TaskWorker {
    String taskType();
    TaskResult execute(TaskExecutionContext context);
}
```

执行规则：

- Worker 开始前必须确认 Task 仍是 `PENDING`。
- 获取执行权时将 Task 从 `PENDING` 原子更新为 `RUNNING`。
- Worker 开始后创建 `task_attempt`。
- 执行成功写 `SUCCESS` 和 `TASK_SUCCEEDED`。
- 执行失败写 `FAILED` 和 `TASK_FAILED`。
- 可取消任务必须周期性调用 `TaskCancellationChecker`.

---

## 10. API 设计

通用 Task API：

```http
GET  /api/v1/tasks
GET  /api/v1/tasks/{taskId}
GET  /api/v1/tasks/{taskId}/events
POST /api/v1/tasks/{taskId}/cancel
POST /api/v1/tasks/{taskId}/retry
```

查询参数：

```text
spaceId
researchProjectId
taskType
taskStatus
targetType
targetId
page
size
sort
```

响应重点字段：

```json
{
  "id": 1001,
  "taskType": "DOCUMENT_PROCESS",
  "targetType": "DOCUMENT",
  "targetId": 2001,
  "taskStatus": "RUNNING",
  "cancelRequested": false,
  "retryCount": 0,
  "maxRetryCount": 3,
  "startedAt": "2026-05-14T10:00:00",
  "finishedAt": null
}
```

---

## 11. Kafka / Queue 消息

推荐消息结构：

```json
{
  "taskId": 1001,
  "taskType": "DOCUMENT_PROCESS",
  "targetType": "DOCUMENT",
  "targetId": 2001,
  "idempotencyKey": "DOCUMENT_PROCESS:1:DOCUMENT:2001:v1",
  "createdAt": "2026-05-14T10:00:00"
}
```

消息 key：

```text
taskId
```

Consumer 规则：

- 只信任 `taskId`。
- 消费后先查 DB task。
- 如果 Task 不存在，记录错误并 ack。
- 如果 Task 已是 `SUCCESS / CANCELLED`，直接 ack。
- 如果 Task 是 `RUNNING`，按幂等规则跳过或记录重复消费。
- 只有 `PENDING` 可以进入执行。

---

## 12. 测试要求

必须覆盖：

- 相同 idempotencyKey 不重复创建 Task。
- `PENDING -> RUNNING -> SUCCESS`。
- `PENDING -> RUNNING -> FAILED`。
- `PENDING -> RUNNING -> TIMEOUT`。
- `PENDING` 任务取消。
- `RUNNING` 任务 cancelRequested 被 Worker 识别。
- FAILED / TIMEOUT 任务 retry 创建新 attempt。
- 超过 maxRetryCount 禁止 retry。
- Outbox 发送成功标记 `SENT`。
- Outbox 发送失败后记录 `nextRetryAt`。
- 重复消费同一个 taskId 不重复执行业务。
- 非成员不能查询或取消团队任务。

---

## 13. 编码顺序

建议顺序：

1. Flyway 创建 `task / task_attempt / task_event / task_outbox`。
2. 定义 Task 枚举、状态枚举、事件枚举。
3. 实现 TaskService 创建、查询、取消、重试。
4. 实现 TaskEventService。
5. 实现 TaskOutboxService。
6. 实现 TaskDispatcher。
7. 实现 TaskWorkerRegistry。
8. 实现 `NOOP_TEST` Worker。
9. 实现通用 Task API。
10. 补测试。

---

## 14. 验收标准

- 可以创建一个 `NOOP_TEST` Task。
- 重复创建同一幂等任务只返回同一个 Task。
- Worker 执行后能看到 task、attempt、event 全链路记录。
- 可以取消 `PENDING` 任务。
- 可以请求取消 `RUNNING` 任务，并由 Worker 在安全点停止。
- FAILED / TIMEOUT 任务可以重试，且 attemptNo 递增。
- Outbox 投递失败可以补偿重试。
- 后续 Phase 2 可以直接复用 TaskOutbox 创建 `DOCUMENT_PROCESS` 任务。
