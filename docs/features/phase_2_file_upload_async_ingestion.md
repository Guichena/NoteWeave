# Phase 2: KnowledgeBase、文件存储、分片上传与异步任务投递

本文档用于指导 NoteWeave 第二阶段编码实现。

范围：

```text
Phase 2: MinIO FileStorage / Kafka Task Dispatch / KnowledgeBase / DocumentUpload / UploadChunk / Document
```

第二阶段目标是让团队文档能通过分片上传进入系统，完成断点续传、MinIO 合并、Document 元数据创建和 Kafka 异步处理任务投递。

本阶段不要求完成文档解析、Chunk 切片、Embedding、Elasticsearch 索引或 RAG 问答。Kafka Consumer 可以先做最小占位处理。

---

## 1. 参考文档

请严格参考：

```text
docs/features/phase_0_1_bootstrap_auth_space.md
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
- 所有分片上传完成后可以合并为 `merged/{fileMd5}`。
- 合并成功后创建 Document 元数据。
- 合并成功后创建 Task。
- 合并成功后投递 Kafka `DOCUMENT_PROCESS` 消息。
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

Kafka Consumer 本阶段只需要能接收消息，并把 Task / Document 状态更新为可观察状态。真实解析放到 Phase 3。

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
  ├── producer
  └── consumer
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
    consumer:
      group-id: noteweave-document-processing-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.noteweave.kafka.message"

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  bucket-name: ${MINIO_BUCKET_NAME:noteweave}
  public-url: ${MINIO_PUBLIC_URL:http://localhost:9000}

noteweave:
  upload:
    chunk-size: ${NOTEWEAVE_UPLOAD_CHUNK_SIZE:5242880}
    bitmap-ttl-hours: ${NOTEWEAVE_UPLOAD_BITMAP_TTL_HOURS:24}
  kafka:
    topics:
      document-process: document-process-topic
      document-process-dlt: document-process-dlt
```

说明：

- 前端可以自行决定分片大小，但后端需要校验 `chunkSize` 合理。
- MVP 可固定推荐 5MB 分片。

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

### 8.4 Document

表：`document`

字段：

```text
id
spaceId
knowledgeBaseId
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

### 8.5 Task

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
```

### UploadChunkRepository

```java
Optional<UploadChunk> findByUploadIdAndChunkIndex(Long uploadId, Integer chunkIndex);
List<UploadChunk> findByUploadIdOrderByChunkIndexAsc(Long uploadId);
long countByUploadId(Long uploadId);
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
chunks/{fileMd5}/{chunkIndex}
merged/{fileMd5}
```

### 10.3 UploadBitmapService

职责：

- 使用 Redis Bitmap 记录分片状态。

key：

```text
upload:{userId}:{fileMd5}
```

方法：

```java
boolean isChunkUploaded(Long userId, String fileMd5, int chunkIndex);
void markChunkUploaded(Long userId, String fileMd5, int chunkIndex);
List<Integer> getUploadedChunks(Long userId, String fileMd5, int totalChunks);
void clear(Long userId, String fileMd5);
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
上传 chunks/{fileMd5}/{chunkIndex} 到 MinIO
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
ComposeObject 合并到 merged/{fileMd5}
  ↓
创建 Document
  ↓
更新 DocumentUpload.documentId / objectKey / status
  ↓
清理分片对象
  ↓
清理 Redis Bitmap
  ↓
创建 Task(DOCUMENT_PROCESS)
  ↓
投递 Kafka
```

幂等要求：

- 重复上传同一分片不应重复写 MinIO。
- 重复 merge 不应重复创建 Document。
- Task 使用 `idempotencyKey = DOCUMENT_PROCESS:{documentId}:{fileMd5}`。

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
void markFailed(Long taskId, String errorMessage);
void cancel(Long userId, Long taskId);
```

### 10.6 DocumentProcessProducer

职责：

- 投递 Kafka `DocumentProcessMessage`。

方法：

```java
void send(DocumentProcessMessage message);
```

### 10.7 DocumentProcessConsumer

本阶段只做占位消费，不做真实解析。

流程：

```text
接收 DocumentProcessMessage
  ↓
Task 标记 RUNNING
  ↓
Document 标记 PROCESSING
  ↓
记录日志
  ↓
不创建 Chunk
  ↓
Task 标记 SUCCESS 或保留 RUNNING 由 Phase 3 接管
```

建议本阶段将 Task 标记为 `SUCCESS`，Document 保持 `PROCESSING`，并在 `outputJson` 中写明：

```json
{
  "phase": "PHASE_2_PLACEHOLDER",
  "message": "Document process message consumed; parsing will be implemented in Phase 3."
}
```

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
document-process-topic
```

DLT：

```text
document-process-dlt
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
  "objectKey": "merged/d41d8cd98f00b204e9800998ecf8427e"
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
- `reindex` 可先创建新的 `DOCUMENT_PROCESS` Task。

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

如果本地没有 MinIO / Kafka，可先使用 mock 或 Testcontainers 后续补。

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
- merge 成功后 MinIO 中存在 `merged/{fileMd5}`。
- merge 成功后创建 Document。
- merge 成功后创建 Task。
- merge 成功后发送 Kafka 消息。
- Kafka Consumer 能接收到消息并更新 Task。
- 非成员访问上传链路返回 403。

---

## 17. 实现顺序建议

```text
1. 添加 Maven 依赖
2. 添加 MinIO 配置和 MinioClient Bean
3. 添加 Kafka 配置和 topic 常量
4. 实现 Task 实体、Repository、Service、Controller
5. 实现 KnowledgeBase 实体、Repository、Service、Controller
6. 实现 Document / DocumentUpload / UploadChunk 实体和 Repository
7. 实现 FileStorageService
8. 实现 UploadBitmapService
9. 实现 DocumentProcessMessage 和 Producer
10. 实现 DocumentUploadService
11. 实现 DocumentUploadController
12. 实现 DocumentController
13. 实现占位 DocumentProcessConsumer
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

