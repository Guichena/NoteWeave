# Phase 2: KnowledgeBase、文件存储、分片上传与异步任务投递

本文档用于指导 NoteWeave 第二阶段编码实现。

范围：

```text
Phase 2: MinIO FileStorage / TaskOutbox Dispatch / KnowledgeBase / FileObject / DocumentUpload / UploadChunk / Document
```

第二阶段目标是让团队文档能通过分片上传进入系统，完成断点续传、MinIO 合并、FileObject 引用、Document 元数据创建和 TaskOutbox 异步处理任务投递。

本阶段不要求完成文档解析、Chunk 切片、Embedding、Elasticsearch 索引或 RAG 问答。本阶段只负责创建 Task 和 TaskOutbox，并由 dispatcher 投递消息；真实 Consumer 处理放到 Phase 3。

---

## 1. 参考文档

请严格参考：

```text
docs/features/phase_0_1_bootstrap_auth_space.md
docs/features/phase_1_5_task_outbox_worker.md
docs/features/file_upload_async_pipeline.md
docs/features/database_api_blueprint.md
docs/implementation_breakdown.md
```

可参考 PaiSmart-main：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\controller\UploadController.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\UploadService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\consumer\FileProcessingConsumer.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\KafkaConfig.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\MinioConfig.java
```

注意：参考 PaiSmart 的实现思路，不照搬包名、实体名和旧接口。

---

## 2. 阶段目标

第二阶段完成后，系统应具备：

- OWNER / EDITOR 可以创建团队 KnowledgeBase。
- OWNER / EDITOR 可以初始化文档上传。
- 文件按分片上传到 MinIO。
- Redis Bitmap 记录分片上传状态。
- 上传状态可查询。
- 已上传分片可跳过，实现断点续传。
- 所有分片上传完成后可以合并为 `FileObject` 管理的对象，例如带 dev/test 前缀的 `objects/{contentHash}`。
- 合并成功后创建 Document 元数据。
- 合并成功后创建 Task 和 TaskOutbox。
- Outbox dispatcher 投递 Kafka `DOCUMENT_PROCESS` 消息，发送失败可补偿。
- VIEWER 不能上传文档。
- 非成员不能访问团队 KnowledgeBase 和上传链路。

---

## 3. 本阶段不做的事

- 不做 PDF / Word / Markdown 真实解析。
- 不做 DocumentChunk 切片。
- 不做 Embedding。
- 不做 Elasticsearch。
- 不做 RAG 问答。
- 不做 WebSocket。
- 不做个人 ResearchProject。
- 不做 Artifact。
- 不做复杂文档级权限。

本阶段只验证 TaskOutbox dispatcher 能把 `DOCUMENT_PROCESS` 消息投递到 Kafka；不实现业务 Consumer，不把 Task 标记为成功。真实 Consumer、解析、Chunk 和索引放到 Phase 3。

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
```

必须使用：

```java
SpacePermissionService.requireViewSpace(...)
SpacePermissionService.requireUploadDocument(...)
```

权限规则：

| 操作 | OWNER | EDITOR | VIEWER |
|---|---:|---:|---:|
| 创建 KnowledgeBase | Y | Y | N |
| 查看 KnowledgeBase | Y | Y | Y |
| 初始化上传 | Y | Y | N |
| 上传分片 | Y | Y | N |
| 合并文件 | Y | Y | N |
| 查看文档列表 | Y | Y | Y |

---

## 5. 技术栈新增

在 Phase 0/1 基础上新增：

```text
Spring Kafka
MinIO SDK
Apache Commons Codec
Apache Commons IO
```

Maven 依赖建议：

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.12</version>
</dependency>
<dependency>
    <groupId>commons-codec</groupId>
    <artifactId>commons-codec</artifactId>
    <version>1.16.1</version>
</dependency>
<dependency>
    <groupId>commons-io</groupId>
    <artifactId>commons-io</artifactId>
    <version>2.14.0</version>
</dependency>
```

---

## 6. 推荐包结构

在已有包结构上新增：

```text
com.noteweave.team.kb
  ├── controller
  ├── dto
  ├── model
  ├── repository
  └── service

com.noteweave.team.document
  ├── controller
  ├── dto
  ├── model
  ├── repository
  └── service

com.noteweave.storage
  ├── config
  ├── model
  └── service

com.noteweave.task
  ├── dto
  ├── model
  ├── repository
  └── service

com.noteweave.kafka
  ├── config
  ├── message
  └── producer
```

---

## 7. 配置文件

`application.yml` 新增：

```yaml
spring:
  servlet:
    multipart:
      enabled: true
      max-file-size: 20MB
      max-request-size: 25MB
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
noteweave:
  storage:
    minio:
      endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
      access-key: ${MINIO_ACCESS_KEY:noteweave}
      secret-key: ${MINIO_SECRET_KEY:noteweave-minio-secret}
      bucket: ${MINIO_BUCKET:noteweave-dev}
      test-bucket: ${MINIO_TEST_BUCKET:noteweave-test}
    paths:
      local-test-root: ${NOTEWEAVE_TEST_ROOT:target/noteweave-test}
      dev-object-prefix: ${NOTEWEAVE_DEV_OBJECT_PREFIX:dev}
      test-object-prefix: ${NOTEWEAVE_TEST_OBJECT_PREFIX:test}
  upload:
    chunk-size: ${NOTEWEAVE_UPLOAD_CHUNK_SIZE:5242880}
    bitmap-ttl-hours: ${NOTEWEAVE_UPLOAD_BITMAP_TTL_HOURS:24}
  kafka:
    topics:
      document-process: ${NOTEWEAVE_KAFKA_TOPIC_DOCUMENT:noteweave.document}
```

说明：

- 前端可以自行决定分片大小，但后端需要校验 `chunkSize` 合理。
- MVP 可固定推荐 5MB 分片。
- 本地开发连接根目录 `docker-compose.yml` 中的 MinIO / Kafka。
- 集成测试必须使用 Testcontainers 启动 MinIO / Kafka，并使用 `noteweave-test` bucket、`test/` 对象前缀和测试 topic。

---

## 8. 数据模型

### 8.1 KnowledgeBase

表：`knowledge_base`

字段见：

```text
docs/features/database_api_blueprint.md
```

本阶段实体字段：

```text
id
spaceId
name
description
status
createdBy
createdAt
updatedAt
```

状态：

```text
ACTIVE
ARCHIVED
```

### 8.2 DocumentUpload

表：`document_upload`

字段：

```text
id
spaceId
knowledgeBaseId
documentId
userId
fileMd5
fileName
contentType
totalSize
chunkSize
totalChunks
objectKey
status
errorMessage
expiresAt
cancelledAt
mergedAt
createdAt
updatedAt
```

状态：

```text
INIT
UPLOADING
MERGED
PROCESSING
INDEXED
FAILED
CANCELLED
EXPIRED
DELETED
```

### 8.3 UploadChunk

表：`upload_chunk`

字段：

```text
id
uploadId
fileMd5
chunkIndex
chunkMd5
size
objectKey
createdAt
updatedAt
```

唯一约束：

```text
upload_id + chunk_index
```

### 8.4 FileObject

表：`file_object`

字段：

```text
id
spaceId
contentHash
objectKey
size
contentType
refCount
status
createdAt
updatedAt
```

约束：

```text
UNIQUE(space_id, content_hash)
```

说明：

- `FileObject` 按 Space 分区复用，避免不同 Space 因相同 content hash 共享对象导致权限边界混淆。
- `refCount` 只统计同一 Space 内 Document 引用数。
- Document 创建成功时 `refCount + 1`，Document 物理清理完成后 `refCount - 1`。

### 8.5 Document

表：`document`

字段：

```text
id
spaceId
knowledgeBaseId
fileObjectId
title
sourceType
objectKey
originalFilename
contentHash
parseStatus
indexStatus
status
tokenCount
chunkCount
errorMessage
createdBy
createdAt
updatedAt
```

本阶段状态：

```text
PENDING_PROCESS
PROCESSING
FAILED
```

`INDEXED` 留给 Phase 3。

### 8.6 Task

表：`task`

本阶段任务类型：

```text
DOCUMENT_PROCESS
```

字段：

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

本阶段状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
```

### 8.7 TaskAttempt

表：`task_attempt`

字段：

```text
id
taskId
attemptNo
workerId
status
startedAt
finishedAt
errorCode
errorMessage
createdAt
updatedAt
```

用途：

- 记录每次 Worker 实际执行，便于排查重复执行和重试原因。
- 同一个 `taskId` 的 attemptNo 单调递增。

### 8.8 TaskOutbox

表：`task_outbox`

字段：

```text
id
taskId
eventType
aggregateType
aggregateId
idempotencyKey
payloadJson
status
retryCount
nextRetryAt
sentAt
createdAt
updatedAt
```

要求：

- Task 与 TaskOutbox 必须在同一个 DB 事务中创建。
- 事务提交后由 dispatcher 投递 Kafka，Kafka key 固定为 `taskId`。
- `idempotencyKey` 唯一，避免重复投递造成重复业务任务。

---

## 9. Repository 清单

### KnowledgeBaseRepository

```java
List<KnowledgeBase> findBySpaceIdAndStatus(Long spaceId, KnowledgeBaseStatus status);
Optional<KnowledgeBase> findByIdAndStatus(Long id, KnowledgeBaseStatus status);
```

### DocumentUploadRepository

```java
Optional<DocumentUpload> findByIdAndStatusNot(Long id, DocumentUploadStatus status);
Optional<DocumentUpload> findByIdAndUserId(Long id, Long userId);
Optional<DocumentUpload> findFirstByUserIdAndFileMd5AndStatusIn(Long userId, String fileMd5, Collection<DocumentUploadStatus> statuses);
List<DocumentUpload> findExpiredUploads(Instant now, Collection<DocumentUploadStatus> statuses);
```

### UploadChunkRepository

```java
Optional<UploadChunk> findByUploadIdAndChunkIndex(Long uploadId, Integer chunkIndex);
List<UploadChunk> findByUploadIdOrderByChunkIndexAsc(Long uploadId);
long countByUploadId(Long uploadId);
```

### FileObjectRepository

```java
Optional<FileObject> findBySpaceIdAndContentHash(Long spaceId, String contentHash);
void incrementRefCount(Long fileObjectId);
void decrementRefCount(Long fileObjectId);
```

### DocumentRepository

```java
List<Document> findByKnowledgeBaseIdAndStatusNot(Long knowledgeBaseId, DocumentStatus status);
Optional<Document> findByIdAndStatusNot(Long id, DocumentStatus status);
```

### TaskRepository

```java
Optional<Task> findByIdempotencyKey(String idempotencyKey);
Optional<Task> findByIdAndUserId(Long id, Long userId);
List<Task> findBySpaceIdAndTaskStatus(Long spaceId, TaskStatus status);
```

---

## 10. Service 设计

### 10.1 KnowledgeBaseService

职责：

- 创建知识库。
- 查询知识库列表。
- 查询知识库详情。
- 更新知识库。
- 删除知识库。

方法：

```java
KnowledgeBaseResponse create(Long userId, Long spaceId, CreateKnowledgeBaseRequest request);
List<KnowledgeBaseResponse> list(Long userId, Long spaceId);
KnowledgeBaseResponse get(Long userId, Long knowledgeBaseId);
KnowledgeBaseResponse update(Long userId, Long knowledgeBaseId, UpdateKnowledgeBaseRequest request);
void archive(Long userId, Long knowledgeBaseId);
```

权限：

- 创建、更新、删除需要 `canUploadDocument` 或 `canManageSpace`。
- 查看需要 `canViewSpace`。

### 10.2 FileStorageService

职责：

- 确保 bucket 存在。
- 上传分片。
- 检查对象是否存在。
- 合并分片。
- 删除分片。

方法：

```java
void putObject(String objectKey, InputStream inputStream, long size, String contentType);
boolean objectExists(String objectKey);
String composeObject(String targetObjectKey, List<String> sourceObjectKeys);
void removeObject(String objectKey);
String getPresignedUrl(String objectKey);
```

MinIO object key：

```text
uploads/{uploadId}/chunks/{chunkIndex}
objects/{contentHash}
```

说明：上面是业务 key 后缀；实际 MinIO key 必须按 `docs/DOCKER_MIDDLEWARE.md` 增加 `dev/` 或 `test/` 前缀，测试对象还要带 `testRunId`。

### 10.3 UploadBitmapService

职责：

- 使用 Redis Bitmap 记录分片状态。

key：

```text
upload:{uploadId}
```

方法：

```java
boolean isChunkUploaded(Long uploadId, int chunkIndex);
void markChunkUploaded(Long uploadId, int chunkIndex);
List<Integer> getUploadedChunks(Long uploadId, int totalChunks);
void clear(Long uploadId);
```

### 10.4 DocumentUploadService

职责：

- 初始化上传。
- 上传分片。
- 查询状态。
- 合并文件。
- 创建 Document、Task。
- 投递 Kafka。

方法：

```java
InitUploadResponse initUpload(Long userId, Long knowledgeBaseId, InitUploadRequest request);
UploadChunkResponse uploadChunk(Long userId, Long uploadId, Integer chunkIndex, MultipartFile file);
UploadStatusResponse getStatus(Long userId, Long uploadId);
MergeUploadResponse merge(Long userId, Long uploadId);
void cancelUpload(Long userId, Long uploadId);
```

初始化流程：

```text
读取 KnowledgeBase
  ↓
校验 canUploadDocument
  ↓
校验 fileMd5 / totalSize / totalChunks
  ↓
查找已有 upload 或可复用对象
  ↓
创建 DocumentUpload
  ↓
返回 uploadId 和已上传分片
```

分片上传流程：

```text
读取 DocumentUpload
  ↓
校验 upload 归属和权限
  ↓
检查 Redis Bitmap
  ↓
已上传则直接返回
  ↓
计算 chunkMd5
  ↓
上传带 dev/test 前缀的 uploads/{uploadId}/chunks/{chunkIndex} 到 MinIO
  ↓
保存 UploadChunk
  ↓
SETBIT
  ↓
返回进度
```

合并流程：

```text
读取 DocumentUpload
  ↓
校验 upload 归属和权限
  ↓
检查 Redis Bitmap 和 UploadChunk 数量
  ↓
检查 MinIO 分片存在
  ↓
ComposeObject 合并到带 dev/test 前缀的 objects/{contentHash} 并写入 FileObject
  ↓
创建 Document
  ↓
更新 DocumentUpload.documentId / objectKey / status，并创建或复用同 Space 的 FileObject 引用
  ↓
FileObject.refCount + 1
  ↓
清理分片对象
  ↓
清理 Redis Bitmap
  ↓
创建 Task(DOCUMENT_PROCESS) 和 TaskOutbox
  ↓
由 Outbox dispatcher 投递 Kafka，可失败补偿
```

幂等要求：

- 重复上传同一分片不应重复写 MinIO。
- 重复 merge 不应重复创建 Document。
- Task 使用 `idempotencyKey = DOCUMENT_PROCESS:{documentId}:{fileMd5}`。
- 重复 cancel 只返回当前取消状态，不重复删除对象。

取消流程：

```text
读取 DocumentUpload
  ↓
校验归属和权限
  ↓
仅 INIT / UPLOADING / FAILED 可取消
  ↓
标记 CANCELLED，写 cancelledAt
  ↓
删除当前 dev/test 前缀下 uploads/{uploadId}/chunks/ 的分片对象
  ↓
清理 Redis Bitmap
```

### 10.5 TaskService

职责：

- 创建任务。
- 查询任务。
- 更新任务状态。
- 幂等创建任务。

方法：

```java
Task createIfAbsent(CreateTaskCommand command);
TaskResponse getTask(Long userId, Long taskId);
void markRunning(Long taskId);
void markSuccess(Long taskId, String resultRefType, Long resultRefId);
void requestCancel(Long userId, Long taskId);
void markFailed(Long taskId, String errorMessage);
void cancel(Long userId, Long taskId);
```

要求：

- `requestCancel` 只设置 `cancelRequested = true`，RUNNING 任务由 Worker 在安全点停止。
- 每次 Worker 执行必须创建 `TaskAttempt`，失败和取消都要记录 attempt 结果。
- 重复创建任务时先按 `idempotencyKey` 查询既有任务。

### 10.6 UploadCleanupService

职责：

- 定期扫描过期或取消的分片上传。
- 清理 MinIO 分片对象、Redis Bitmap 和 DB 残留状态。
- 生成清理日志，供 Phase 15 Admin / Ops 查询。

方法：

```java
int cleanupExpiredUploads(Instant now);
void cleanupUpload(Long uploadId);
```

规则：

- `DocumentUpload.expiresAt < now` 且状态为 `INIT / UPLOADING / FAILED` 时标记 `CANCELLED` 或 `EXPIRED`。
- 清理只删除分片临时对象，不删除已经合并并被 `FileObject` 引用的正式对象。
- 清理失败要记录 errorMessage，等待下次任务重试。

### 10.7 DocumentProcessProducer

职责：

- 投递 Kafka `DocumentProcessMessage`。

方法：

```java
void send(DocumentProcessMessage message);
```

### 10.8 DocumentProcessDispatcher

本阶段只做 TaskOutbox 投递，不做真实解析，也不把业务 Task 标记为 `SUCCESS`。

流程：

```text
读取 TaskOutbox PENDING 记录
  ↓
事务外发送 DocumentProcessMessage，Kafka key = taskId
  ↓
Outbox 标记 SENT
  ↓
Document 保持 PENDING_PROCESS 或 DISPATCHED
  ↓
Task 保持 PENDING，等待 Phase 3 Worker 真实处理
```

如需写 `outputJson`，只能写明：

```json
{
  "phase": "PHASE_2_DISPATCHED",
  "message": "Document process task dispatched; parsing will be implemented in Phase 3."
}
```

投递要求：

- Dispatcher 只处理已提交事务中的 PENDING Outbox 记录。
- Kafka 发送失败不回滚业务数据，只更新 Outbox retryCount / nextRetryAt。
- 同一个 Outbox 多次投递时，后续 Phase 3 Consumer 必须依赖 `taskId` 和业务幂等键去重。

---

## 11. Kafka 消息

### DocumentProcessMessage

```java
public record DocumentProcessMessage(
    Long taskId,
    Long documentId,
    Long spaceId,
    Long knowledgeBaseId,
    String fileMd5,
    String objectKey,
    String fileName,
    String contentType,
    Long uploadedBy,
    Instant createdAt
) {}
```

Topic：

```text
noteweave.document
```

---

## 12. API 设计

接口统一前缀：

```text
/api/v1
```

### 12.1 KnowledgeBase

```http
POST   /api/v1/team/spaces/{spaceId}/knowledge-bases
GET    /api/v1/team/spaces/{spaceId}/knowledge-bases
GET    /api/v1/team/knowledge-bases/{kbId}
PUT    /api/v1/team/knowledge-bases/{kbId}
DELETE /api/v1/team/knowledge-bases/{kbId}
```

CreateKnowledgeBaseRequest：

```json
{
  "name": "项目文档库",
  "description": "需求、设计、复盘文档"
}
```

### 12.2 Document Upload

```http
POST /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init
POST /api/v1/team/document-uploads/{uploadId}/chunks
GET  /api/v1/team/document-uploads/{uploadId}/status
POST /api/v1/team/document-uploads/{uploadId}/merge
POST /api/v1/team/document-uploads/{uploadId}/cancel
```

InitUploadRequest：

```json
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileName": "design.pdf",
  "contentType": "application/pdf",
  "totalSize": 123456,
  "chunkSize": 5242880,
  "totalChunks": 1
}
```

InitUploadResponse：

```json
{
  "uploadId": 1,
  "documentId": null,
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "uploadedChunks": [],
  "totalChunks": 1,
  "instantUpload": false,
  "status": "UPLOADING"
}
```

UploadChunk：

```http
POST /api/v1/team/document-uploads/{uploadId}/chunks?chunkIndex=0
Content-Type: multipart/form-data
```

表单：

```text
file: chunk binary
```

UploadChunkResponse：

```json
{
  "uploadId": 1,
  "chunkIndex": 0,
  "uploaded": true,
  "uploadedChunks": [0],
  "totalChunks": 1,
  "progress": 100.0
}
```

UploadStatusResponse：

```json
{
  "uploadId": 1,
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "status": "UPLOADING",
  "uploadedChunks": [0],
  "totalChunks": 1,
  "progress": 100.0,
  "documentId": null,
  "taskId": null
}
```

MergeUploadResponse：

```json
{
  "uploadId": 1,
  "documentId": 10,
  "taskId": 100,
  "status": "PROCESSING",
  "objectKey": "objects/sha256-content-hash"
}
```

### 12.3 Document Query

```http
GET    /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents
GET    /api/v1/team/documents/{documentId}
DELETE /api/v1/team/documents/{documentId}
POST   /api/v1/team/documents/{documentId}/reindex
```

本阶段：

- `GET documents` 返回 Document 元数据和处理状态。
- `DELETE document` 可先做软删除。
- `reindex` 创建新的 `DOCUMENT_PROCESS` Task；实际重建必须由 Phase 3 的 indexVersion 机制处理，不能覆盖旧索引。

### 12.4 Task

```http
GET  /api/v1/tasks/{taskId}
POST /api/v1/tasks/{taskId}/cancel
```

---

## 13. Controller 清单

```text
team.kb.controller.KnowledgeBaseController
team.document.controller.DocumentUploadController
team.document.controller.DocumentController
task.controller.TaskController
```

---

## 14. 错误码补充

在 Phase 0/1 错误码基础上新增：

```text
KNOWLEDGE_BASE_NOT_FOUND
DOCUMENT_NOT_FOUND
UPLOAD_NOT_FOUND
UPLOAD_ACCESS_DENIED
UPLOAD_INVALID_CHUNK
UPLOAD_CHUNK_INCOMPLETE
UPLOAD_MERGE_FAILED
STORAGE_OBJECT_NOT_FOUND
STORAGE_OPERATION_FAILED
TASK_NOT_FOUND
KAFKA_DISPATCH_FAILED
```

---

## 15. 测试建议

### 15.1 单元测试

```text
KnowledgeBaseServiceTest
DocumentUploadServiceTest
UploadBitmapServiceTest
TaskServiceTest
```

重点覆盖：

- OWNER 可以创建 KnowledgeBase。
- EDITOR 可以创建 KnowledgeBase。
- VIEWER 不能创建 KnowledgeBase。
- 上传初始化校验权限。
- 重复分片上传幂等。
- Redis Bitmap 能正确记录分片。
- 分片未完整时不能 merge。
- merge 成功后创建 Document 和 Task。
- Task 幂等创建。

### 15.2 集成测试

集成测试必须使用 Testcontainers 启动 MinIO / Kafka，不能依赖本机已启动服务，也不能把本阶段需要的容器化能力留到后续补。

最小接口测试：

```text
创建用户 A
创建团队 Space
创建 KnowledgeBase
初始化上传
上传 chunk 0
查询上传状态
merge
查询 Document
查询 Task
```

---

## 16. 验收清单

Phase 2 验收：

- 应用可以启动。
- MinIO 配置可加载。
- Kafka 配置可加载。
- 可以创建 KnowledgeBase。
- VIEWER 创建 KnowledgeBase 返回 403。
- 可以初始化上传。
- 可以上传分片。
- Redis Bitmap 可以查询已上传分片。
- 可以查询上传进度。
- 分片不完整时 merge 失败。
- 分片完整时 merge 成功。
- merge 成功后 MinIO 中存在 FileObject 指向的带 dev/test 前缀 `objects/{contentHash}`。
- merge 成功后创建 Document。
- merge 成功后创建 Task。
- merge 成功后创建 TaskOutbox。
- TaskOutbox dispatcher 能发送 Kafka `noteweave.document` 消息并将 Outbox 标记为 `SENT`。
- 业务 Task 保持 `PENDING`，等待 Phase 3 Worker 真实处理。
- 非成员访问上传链路返回 403。

---

## 17. 实现顺序建议

```text
1. 添加 Maven 依赖
2. 添加 MinIO 配置和 MinioClient Bean
3. 添加 Kafka 配置和 topic 常量
4. 复用 Phase 1.5 的 Task / TaskOutbox / Task API，不重复实现任务底座
5. 实现 KnowledgeBase 实体、Repository、Service、Controller
6. 实现 Document / DocumentUpload / UploadChunk 实体和 Repository
7. 实现 FileStorageService
8. 实现 UploadBitmapService
9. 实现 DocumentProcessMessage 和 Producer
10. 实现 DocumentUploadService
11. 实现 DocumentUploadController
12. 实现 DocumentController
13. 实现 TaskOutbox dispatcher，不实现占位成功 Consumer
14. 补测试
15. 运行 mvn test 或 mvn package
```

---

## 18. 给 AI 执行第二阶段的边界提醒

执行第二阶段时必须遵守：

- 不要实现 Phase 3 的文档解析和 Chunk 切片。
- 不要引入 Elasticsearch。
- 不要引入 RAG Chat。
- 不要引入 WebSocket。
- 不要改变 Phase 0/1 已有 API 行为。
- 所有 API 必须使用 `/api/v1`。
- 所有权限必须经过 `SpacePermissionService`。
- 不要使用 `generation_task`，必须使用通用 `task`。
- 不要使用普通 multipart 一步上传替代分片上传。




