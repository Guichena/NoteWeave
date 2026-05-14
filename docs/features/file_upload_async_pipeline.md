# 文件上传与异步处理链路

本文档描述 NoteWeave 的文件上传、断点续传、任务投递和异步处理链路。

目标是让上传阶段快速、可靠、可恢复，解析、向量化、索引构建等长耗时流程全部异步执行。

---

## 1. 参考实现

可参考 `PaiSmart-main` 中以下文件：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\controller\UploadController.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\UploadService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\consumer\FileProcessingConsumer.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\KafkaConfig.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\MinioConfig.java
```

参考点：

- Redis Bitmap 记录分片上传状态，key 以 uploadId 为准。
- MinIO 存储分片和 FileObject 管理的合并文件。
- MD5 作为内容级标识。
- 合并成功后投递 Kafka。
- Consumer 负责解析和向量化。
- Consumer 处理前检查文件生命周期。

---

## 2. 设计目标

```text
上传阶段：
  分片落库
  分片存储
  状态记录
  合并校验
  任务投递

异步阶段：
  文本解析
  Chunk 切片
  Embedding 向量化
  Elasticsearch 索引构建
  处理状态回写
```

上传接口不能同步执行解析、Embedding 或索引构建。

---

## 3. 领域模型

### 3.1 DocumentUpload

表示一次上传会话。

```text
id
space_id
knowledge_base_id
document_id
user_id
file_md5
file_name
content_type
total_size
chunk_size
total_chunks
object_key
status
expires_at
cancelled_at
created_at
updated_at
merged_at
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

### 3.2 UploadChunk

记录已上传分片的元数据。

```text
id
upload_id
file_md5
chunk_index
chunk_md5
size
object_key
created_at
```

### 3.3 FileObject

表示可复用的对象存储文件。

```text
id
space_id
content_hash
object_key
size
content_type
ref_count
status
created_at
updated_at
```

约束：

```text
UNIQUE(space_id, content_hash)
```

`ref_count` 只在同一 Space 内统计引用，禁止跨 Space 共享 FileObject 权限边界。

### 3.4 Document

团队知识库中的正式文档。

```text
id
space_id
knowledge_base_id
uploaded_by
file_object_id
file_md5
file_name
content_type
object_key
status
parse_error
created_at
updated_at
```

### 3.5 Task

异步处理任务。

```text
id
task_type
target_type
target_id
space_id
created_by
status
idempotency_key
retry_count
max_retry_count
error_message
result_ref_type
result_ref_id
created_at
updated_at
```

文件处理任务：

```text
task_type = DOCUMENT_PROCESS
target_type = DOCUMENT
target_id = document_id
```

---

## 4. 存储约定

### 4.1 Redis Bitmap

key：

```text
upload:{uploadId}
```

操作：

```text
SETBIT upload:{uploadId} {chunkIndex} 1
GETBIT upload:{uploadId} {chunkIndex}
GET upload:{uploadId}
DEL upload:{uploadId}
```

说明：

- `chunkIndex` 从 0 开始。
- 查询进度时优先一次性读取 bitmap，再在应用层解析。
- 合并成功后删除 bitmap。

### 4.2 MinIO Object Key

分片：

```text
chunks/{uploadId}/{chunkIndex}
```

合并文件：

```text
objects/{contentHash}
```

说明：

- 使用内容 hash 作为 FileObject 身份；对象复用必须通过 FileObject 引用计数管理，不能作为权限依据。
- 原始文件永远作为 Raw Source 保留。

---

## 5. Kafka 设计

Topic：

```text
document-process-topic
```

DLT：

```text
document-process-dlt
```

Consumer Group：

```text
document-processing-group
```

消息体：

```text
task_id
document_id
space_id
knowledge_base_id
file_md5
object_key
file_name
content_type
uploaded_by
created_at
```

生产要求：

- 合并成功后创建 Task 和 TaskOutbox。
- 使用 `idempotency_key = DOCUMENT_PROCESS:{documentId}:{fileMd5}` 防重复。
- TaskOutbox 发送成功后标记为 `SENT`；业务 Task 等 Phase 3 Worker 真实处理后再进入 `RUNNING/SUCCESS/FAILED`。
- Kafka 发送失败时，Outbox 保留 `PENDING` 或进入可重试失败状态，等待补偿投递。
- Task 与 TaskOutbox 必须在同一个 DB 事务内创建，事务提交后再发送 Kafka。
- Kafka key 固定为 `task_id`，Consumer 使用 `task_id + document_id + index_version` 做幂等。

消费要求：

- Consumer 先检查 Document 是否存在。
- Document 已删除则跳过。
- Document 已 `INDEXED` 且 Chunk 存在时，重复消息直接幂等返回。
- Worker 在解析、切片、索引等安全点检查 `cancelRequested`。
- 解析、切片、索引任一步失败，都要回写 Task 和 Document 错误状态。

---

## 6. 主流程

### 6.1 上传初始化

```text
前端计算 fileMd5
  ↓
POST uploads/init
  ↓
校验用户权限 canUploadDocument
  ↓
检查同 MD5 文件是否已存在
  ↓
创建 DocumentUpload
  ↓
返回 uploadId、已上传分片、是否可秒传
```

### 6.2 分片上传

```text
POST uploads/{uploadId}/chunks
  ↓
校验 upload 归属
  ↓
检查 Redis Bitmap
  ↓
已上传则跳过
  ↓
计算 chunkMd5
  ↓
上传 chunks/{uploadId}/{chunkIndex} 到 MinIO
  ↓
写 UploadChunk
  ↓
SETBIT
  ↓
返回进度
```

### 6.3 合并文件

```text
POST uploads/{uploadId}/merge
  ↓
校验所有分片已上传
  ↓
校验 UploadChunk 数量
  ↓
检查 MinIO 分片存在
  ↓
ComposeObject 合并为 objects/{contentHash}
  ↓
创建或复用同 Space 的 FileObject，并 ref_count + 1
  ↓
更新 Document / DocumentUpload 状态
  ↓
删除分片对象
  ↓
删除 Redis Bitmap
  ↓
创建 Task
  ↓
写入 TaskOutbox，由 dispatcher 发送 Kafka
```

### 6.3.1 取消与过期清理

```text
POST uploads/{uploadId}/cancel
  ↓
校验 upload 归属与权限
  ↓
标记 CANCELLED / 写 cancelled_at
  ↓
删除临时分片对象
  ↓
清理 Redis Bitmap
```

`UploadCleanupService` 定期扫描 `expires_at < now` 且仍处于 `INIT / UPLOADING / FAILED` 的上传，按同样流程清理。已经合并为 FileObject 的正式对象不由上传清理删除，必须等 Document 引用清理后再递减 `ref_count`。

### 6.4 异步处理

```text
Phase 3 Worker 接收 DocumentProcessMessage
  ↓
检查 Document 生命周期
  ↓
从 FileObject.objectKey 读取原始对象
  ↓
DocumentParserService 解析文本
  ↓
ChunkService 切片
  ↓
保存 DocumentChunk
  ↓
EmbeddingService 生成向量
  ↓
SearchIndexService 写 ES
  ↓
更新 Document = INDEXED
  ↓
真实处理完成后更新 Task = SUCCESS
```

MVP 可以先跳过 Embedding，只写 BM25 文本索引，但接口要保留。

---

## 7. 接口设计

```http
POST /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init
POST /api/v1/team/document-uploads/{uploadId}/chunks
GET  /api/v1/team/document-uploads/{uploadId}/status
POST /api/v1/team/document-uploads/{uploadId}/merge
POST /api/v1/team/document-uploads/{uploadId}/cancel
GET  /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents
GET  /api/v1/team/documents/{documentId}
GET  /api/v1/team/documents/{documentId}/chunks
```

### 7.1 Init Request

```json
{
  "fileMd5": "string",
  "fileName": "string",
  "contentType": "application/pdf",
  "totalSize": 123456,
  "chunkSize": 5242880,
  "totalChunks": 12
}
```

### 7.2 Init Response

```json
{
  "uploadId": 1,
  "documentId": 10,
  "fileMd5": "string",
  "uploadedChunks": [0, 1, 2],
  "totalChunks": 12,
  "instantUpload": false,
  "status": "UPLOADING"
}
```

---

## 8. 权限与安全

- 初始化上传必须校验 `canUploadDocument`。
- 分片上传必须校验 upload 属于当前用户或当前 Space。
- 合并时必须再次校验权限。
- ES 文档必须写入 `space_id`、`knowledge_base_id`、`document_id`。
- 检索时必须在 ES 查询阶段做权限过滤。
- 文件 MD5 不能作为权限依据，只能作为内容去重依据。
- 不同 Space 复用相同 MD5 对象时，仍然要创建独立 Document 元数据。

---

## 9. 验收标准

- 大文件可以分片上传。
- 上传中断后可以断点续传。
- 已上传分片不会重复上传。
- 同内容文件可以秒传或复用已有 MinIO 对象。
- 合并成功后可以投递 Kafka。
- Consumer 可以解析文档并写入 Chunk。
- Chunk 可以写入 Elasticsearch。
- 失败时能看到 Task 和 Document 的错误状态。
- 重复 Kafka 消息不会重复创建 Chunk。
- 删除中的文档不会继续索引。


