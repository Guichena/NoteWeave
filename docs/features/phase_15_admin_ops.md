# Phase 15: 管理后台与运维能力

本文档用于指导 NoteWeave 第十五阶段编码实现。

范围：

```text
Phase 15: Admin Console / Task Retry / Resource Cleanup / Health Check / Audit Log / Ops Dashboard
```

第十五阶段目标是补齐系统上线后的基础运维能力：管理员能查看用户、空间、文档、任务和存储状态，能重试失败任务，能清理孤儿资源，能追踪关键操作，并通过健康检查确认 MySQL、Redis、MinIO、Kafka、Elasticsearch、LLM Provider 等依赖是否可用。

---

## 1. 参考文档

```text
docs/features/phase_0_1_bootstrap_auth_space.md
docs/features/phase_2_file_upload_async_ingestion.md
docs/features/phase_3_document_processing_indexing.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_14_evaluation_observability.md
docs/features/database_api_blueprint.md
```

---

## 2. 阶段目标

- 支持管理员查看用户、空间、知识库、文档、任务、Artifact 的基础状态。
- 管理员身份来自 `User.systemRole = ADMIN` 或等价系统角色，不能复用 Space OWNER。
- 支持失败任务重试、取消和标记终止。
- 支持清理上传中断、合并失败、索引失败后残留的 MinIO 对象和数据库记录。
- 支持 `ops_cleanup_item` 记录清理候选对象、原因、执行状态和错误。
- 支持检查 MySQL、Redis、MinIO、Kafka、Elasticsearch、LLM Provider 的健康状态。
- 支持关键管理操作写入 AuditLog。
- 支持基础系统统计：任务数量、失败率、存储占用、索引状态、LLM 调用量。
- 支持软删除资源的定期清理策略设计。

---

## 3. 本阶段不做的事

- 不做计费、套餐、额度售卖。
- 不做企业级复杂 RBAC 审批流。
- 不做多租户商业化管理后台。
- 不做复杂合规审计报表。
- 不做自动扩缩容平台。
- 不做外部研究资料发现。
- 不做 Quiz、测验、答题记录等暂缓功能。

---

## 4. 数据模型

### AuditLog

表：`audit_log`

核心字段：

```text
id
operatorId
spaceId
action
targetType
targetId
requestId
ipAddress
userAgent
beforeJson
afterJson
createdAt
```

action 建议值：

```text
USER_DISABLE
USER_ENABLE
SPACE_ARCHIVE
TASK_RETRY
TASK_CANCEL
DOCUMENT_DELETE
RESOURCE_CLEANUP
PROMPT_ACTIVATE
EVAL_RUN_START
```

### OpsCleanupJob

表：`ops_cleanup_job`

核心字段：

```text
id
jobType
status
targetType
targetId
scanCount
cleanupCount
errorMessage
startedBy
startedAt
finishedAt
createdAt
```

jobType：

```text
UPLOAD_EXPIRED
MINIO_ORPHAN_OBJECT
ES_ORPHAN_INDEX
SOFT_DELETE_PURGE
FAILED_MERGE_OBJECT
```

### OpsCleanupItem

表：`ops_cleanup_item`

核心字段：

```text
id
jobId
targetType
targetId
objectKey
reason
status
errorMessage
createdAt
updatedAt
```

清理必须先 scan 生成 item，再 execute 逐项处理并记录结果。

### SystemHealthSnapshot

表：`system_health_snapshot`

核心字段：

```text
id
component
status
latencyMs
detailJson
checkedAt
```

component：

```text
MYSQL
REDIS
MINIO
KAFKA
ELASTICSEARCH
LLM_PROVIDER
```

status：

```text
UP
DEGRADED
DOWN
UNKNOWN
```

---

## 5. Service 设计

### AdminUserService

```java
Page<AdminUserResponse> searchUsers(AdminUserQuery query);
AdminUserResponse disableUser(Long operatorId, Long userId);
AdminUserResponse enableUser(Long operatorId, Long userId);
```

规则：

- 禁用用户后不删除历史数据。
- 禁用用户不能登录，也不能发起新任务。
- 禁用用户的进行中任务是否取消由单独策略控制。

### AdminSpaceService

```java
Page<AdminSpaceResponse> searchSpaces(AdminSpaceQuery query);
AdminSpaceDetailResponse getSpace(Long operatorId, Long spaceId);
void archiveSpace(Long operatorId, Long spaceId);
```

### AdminTaskService

```java
Page<AdminTaskResponse> searchTasks(AdminTaskQuery query);
TaskResponse retry(Long operatorId, Long taskId);
TaskResponse cancel(Long operatorId, Long taskId);
TaskResponse markFailed(Long operatorId, Long taskId, String reason);
```

可重试任务类型：

```text
DOCUMENT_PARSE
DOCUMENT_INDEX
SOURCE_IMPORT
STUDIO_GENERATION
PERSONAL_GENERATION
RAG_EVAL_RUN
```

重试规则：

- 只允许 FAILED、CANCELLED、TIMEOUT 状态重试。
- 重试时创建新 attempt 或记录 retryCount。
- 不直接覆盖原始错误信息。
- 对于文件处理任务，必须先确认 MinIO 原始对象仍存在。

### ResourceCleanupService

```java
OpsCleanupJobResponse scan(Long operatorId, CleanupScanRequest request);
OpsCleanupJobResponse execute(Long operatorId, CleanupExecuteRequest request);
```

清理对象：

```text
过期未完成上传
合并失败残留分片
MinIO 中无数据库引用的对象
数据库中对象已不存在的 document_upload
Elasticsearch 中无数据库引用的 chunk 索引
软删除超过保留期的文档与 Artifact
```

要求：

- 默认先 scan，不直接删除。
- execute 必须写 AuditLog。
- 删除前要生成清理明细。
- 大规模清理使用异步 task。

### SystemHealthService

```java
SystemHealthResponse checkAll();
ComponentHealthResponse check(String component);
List<SystemHealthSnapshotResponse> recentSnapshots(String component);
```

检查项：

```text
MYSQL: 执行轻量 SELECT 1
REDIS: PING + 简单读写
MINIO: bucket 是否存在 + list 权限
KAFKA: topic metadata 可读
ELASTICSEARCH: cluster health + index alias 是否存在
LLM_PROVIDER: 可选轻量模型连通性检查
```

---

## 6. API 设计

### 用户与空间

```http
GET  /api/v1/admin/users
POST /api/v1/admin/users/{userId}/disable
POST /api/v1/admin/users/{userId}/enable
GET  /api/v1/admin/spaces
GET  /api/v1/admin/spaces/{spaceId}
POST /api/v1/admin/spaces/{spaceId}/archive
```

### 任务管理

```http
GET  /api/v1/admin/tasks
GET  /api/v1/admin/tasks/{taskId}
POST /api/v1/admin/tasks/{taskId}/retry
POST /api/v1/admin/tasks/{taskId}/cancel
POST /api/v1/admin/tasks/{taskId}/mark-failed
```

### 资源清理

```http
POST /api/v1/admin/cleanup/scan
POST /api/v1/admin/cleanup/execute
GET  /api/v1/admin/cleanup/jobs
GET  /api/v1/admin/cleanup/jobs/{jobId}
```

### 健康检查与统计

```http
GET /api/v1/admin/health
GET /api/v1/admin/health/{component}
GET /api/v1/admin/dashboard/summary
GET /api/v1/admin/audit-logs
```

---

## 7. 权限要求

- 所有 `/api/v1/admin/**` 接口必须要求 ADMIN 角色。
- Space owner 可以查看自己 Space 下的任务和文档状态，但不能使用全局清理接口。
- 普通用户不能访问 AuditLog、LLM 日志、系统健康详情。
- 任务重试必须再次校验操作者是否有目标资源权限。

---

## 8. 任务状态规范

通用状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
TIMEOUT
```

建议字段：

```text
retryCount
maxRetry
lastErrorCode
lastErrorMessage
startedAt
finishedAt
```

取消策略：

- PENDING 可以直接取消。
- RUNNING 只能设置 cancelRequested，由 Worker 在安全点停止。
- 已完成任务不能取消。

---

## 9. 清理策略

### 上传清理

```text
document_upload.status = UPLOADING
updatedAt 超过 24 小时
  ↓
检查 Redis Bitmap 和 MinIO 分片
  ↓
标记 EXPIRED
  ↓
清理分片对象
```

### 索引清理

```text
数据库 document_chunk 已删除
  ↓
Elasticsearch 仍存在 chunk document
  ↓
scan 记录候选
  ↓
execute 删除 ES 文档
```

### 软删除清理

```text
deletedAt 超过保留期
  ↓
清理关联 Citation / Artifact / Index / MinIO object
  ↓
物理删除或归档
```

---

## 10. 验收清单

- 管理员能查看用户、空间、任务列表。
- 管理员能重试失败任务。
- 管理员能取消 PENDING 或 RUNNING 任务。
- 系统能返回 MySQL、Redis、MinIO、Kafka、ES 健康状态。
- 清理功能支持先扫描后执行。
- 清理和重试操作会写入 AuditLog。
- 普通用户无法访问 `/api/v1/admin/**`。

---

## 11. 给 AI 执行第十五阶段的边界提醒

- 不要做计费和套餐系统。
- 不要做复杂企业审批流。
- 不要把清理接口设计成直接无确认删除。
- 不要绕过资源权限校验重试任务。
- 不要引入暂缓的 Quiz 或外部发现功能。
- 所有 API 必须使用 `/api/v1`。




