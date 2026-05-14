# Phase 3: 文档解析、Chunk 切片与 Elasticsearch 索引

本文档用于指导 NoteWeave 第三阶段编码实现。

范围：

```text
Phase 3: DocumentProcessConsumer / DocumentParserService / ChunkService / SearchIndexService / Elasticsearch BM25
```

第三阶段目标是把 Phase 2 上传并合并到 MinIO 的团队文档，异步处理为可检索的 `DocumentChunk`，并写入 Elasticsearch 文本索引。

本阶段不实现 RAG 问答、不调用 LLM、不做 WebSocket、不做个人 ResearchProject。

---

## 1. 参考文档

请严格参考：

```text
docs/features/phase_2_file_upload_async_ingestion.md
docs/features/file_upload_async_pipeline.md
docs/features/database_api_blueprint.md
docs/implementation_breakdown.md
```

可参考 PaiSmart-main：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\consumer\FileProcessingConsumer.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\ParseService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\VectorizationService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\ElasticsearchService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\EsConfig.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\EsIndexInitializer.java
```

注意：本阶段只借鉴流程和工程边界，不照搬旧模型命名。

---

## 2. 阶段目标

第三阶段完成后，系统应具备：

- Kafka Consumer 能真实处理 `DOCUMENT_PROCESS` 消息。
- 能从 MinIO 读取 `merged/{fileMd5}` 文件。
- 能解析 PDF / Markdown / TXT 基础文本。
- 能将文本切成稳定 Chunk。
- 能把 Chunk 保存到 MySQL `document_chunk`。
- 能把 Chunk 写入 Elasticsearch。
- ES 索引包含权限过滤字段。
- 处理成功后 Document 状态更新为 `INDEXED`。
- 处理失败后 Document 和 Task 有明确错误状态。
- 重复 Kafka 消息不会重复创建 Chunk。
- 删除中的文档不会继续处理或索引。

---

## 3. 本阶段不做的事

- 不做 RAG Chat。
- 不做 LLM 回答生成。
- 不做 Citation 生成。
- 不做向量召回。
- 不做 Weighted RRF。
- 不做 Team Wiki。
- 不做 WebSocket。
- 不做个人 Wiki Compiler。
- 不做 Artifact。

Embedding 可以只保留接口和字段，不要求真实调用向量模型。真实向量化可放到后续增强阶段。

---

## 4. 依赖 Phase 2 的能力

本阶段依赖：

```text
KnowledgeBase
Document
DocumentUpload
UploadChunk
Task
FileStorageService
DocumentProcessMessage
Kafka Consumer
```

Phase 2 中 Consumer 的占位逻辑需要在本阶段替换为真实处理逻辑。

---

## 5. 技术栈新增

在 Phase 2 基础上新增：

```text
Apache Tika
Elasticsearch Java Client
```

Maven 依赖建议：

```xml
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.1</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers-standard-package</artifactId>
    <version>2.9.1</version>
</dependency>
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
    <version>8.10.0</version>
</dependency>
```

---

## 6. 推荐包结构

在已有包结构上新增或补全：

```text
com.noteweave.team.document
  ├── parser
  ├── chunk
  └── service

com.noteweave.search
  ├── config
  ├── document
  ├── service
  └── init

com.noteweave.embedding
  └── service
```

建议类：

```text
team.document.parser.DocumentParserService
team.document.parser.ParseResult
team.document.chunk.ChunkService
team.document.chunk.ChunkCandidate
team.document.service.DocumentProcessingService
search.config.ElasticsearchConfig
search.init.EsIndexInitializer
search.document.EsDocumentChunk
search.service.SearchIndexService
embedding.service.EmbeddingService
```

`EmbeddingService` 本阶段可以是空实现或 stub，后续再接真实模型。

---

## 7. 配置文件

`application.yml` 新增：

```yaml
document:
  parsing:
    chunk-size: ${DOCUMENT_CHUNK_SIZE:800}
    chunk-overlap: ${DOCUMENT_CHUNK_OVERLAP:120}
    max-text-length: ${DOCUMENT_MAX_TEXT_LENGTH:2000000}
    supported-types:
      - application/pdf
      - text/plain
      - text/markdown
      - application/octet-stream

elasticsearch:
  host: ${ELASTICSEARCH_HOST:localhost}
  port: ${ELASTICSEARCH_PORT:9200}
  scheme: ${ELASTICSEARCH_SCHEME:http}
  username: ${ELASTICSEARCH_USERNAME:}
  password: ${ELASTICSEARCH_PASSWORD:}
  index:
    document-chunk: noteweave_document_chunk

embedding:
  enabled: ${EMBEDDING_ENABLED:false}
  dimension: ${EMBEDDING_DIMENSION:1536}
```

说明：

- 本阶段 `embedding.enabled` 默认 `false`。
- ES 可以先使用 BM25 文本检索。
- 如果本地 ES 是安全模式，配置 username/password。

---

## 8. 数据模型

### 8.1 Document

本阶段使用 Phase 2 已有 `document` 表。

处理状态：

```text
status:
  PENDING_PROCESS
  PROCESSING
  INDEXED
  FAILED
  DELETED

parse_status:
  PENDING
  PARSING
  SUCCESS
  FAILED

index_status:
  PENDING
  INDEXING
  SUCCESS
  FAILED
```

处理成功后：

```text
document.status = INDEXED
document.parse_status = SUCCESS
document.index_status = SUCCESS
document.chunk_count = 实际 Chunk 数量
document.token_count = 粗略 token 数
```

### 8.2 DocumentChunk

表：`document_chunk`

字段以 `database_api_blueprint.md` 为准。

本阶段必须写入：

```text
spaceId
knowledgeBaseId
documentId
chunkIndex
content
contentHash
tokenCount
sourceStart
sourceEnd
esDocId
createdAt
updatedAt
```

必须保证：

```text
UNIQUE(document_id, chunk_index)
```

### 8.3 Task

处理成功：

```text
task_status = SUCCESS
result_ref_type = DOCUMENT
result_ref_id = document_id
```

处理失败：

```text
task_status = FAILED
error_message = 错误摘要
```

---

## 9. Elasticsearch 索引设计

索引名：

```text
noteweave_document_chunk
```

文档 ID：

```text
chunk:{documentId}:{chunkIndex}
```

字段：

```json
{
  "spaceId": 1,
  "knowledgeBaseId": 10,
  "documentId": 100,
  "chunkId": 1000,
  "chunkIndex": 0,
  "title": "设计文档",
  "content": "chunk 内容",
  "contentHash": "sha256",
  "sourceType": "FILE",
  "createdBy": 1,
  "createdAt": "2026-05-14T10:00:00"
}
```

Mapping 建议：

```json
{
  "mappings": {
    "properties": {
      "spaceId": { "type": "long" },
      "knowledgeBaseId": { "type": "long" },
      "documentId": { "type": "long" },
      "chunkId": { "type": "long" },
      "chunkIndex": { "type": "integer" },
      "title": {
        "type": "text",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "content": { "type": "text" },
      "contentHash": { "type": "keyword" },
      "sourceType": { "type": "keyword" },
      "createdBy": { "type": "long" },
      "createdAt": { "type": "date" }
    }
  }
}
```

后续向量字段可以扩展：

```json
{
  "embedding": {
    "type": "dense_vector",
    "dims": 1536,
    "index": true,
    "similarity": "cosine"
  }
}
```

本阶段可以不添加 dense_vector。

---

## 10. Service 设计

### 10.1 DocumentParserService

职责：

- 根据文件类型解析文本。
- 保留基础元数据。
- 控制最大文本长度。

方法：

```java
ParseResult parse(InputStream inputStream, String fileName, String contentType);
```

ParseResult：

```java
public record ParseResult(
    String text,
    String detectedContentType,
    long characterCount,
    Map<String, Object> metadata
) {}
```

解析策略：

```text
PDF / Word / 其他 Office:
  Apache Tika

Markdown:
  直接按 UTF-8 文本读取

TXT:
  直接按 UTF-8 文本读取
```

失败处理：

- 解析失败时抛出业务异常。
- Document 标记 `parse_status = FAILED`。
- Task 标记 `FAILED`。

### 10.2 ChunkService

职责：

- 将纯文本切成稳定 Chunk。
- 保存 sourceStart / sourceEnd。
- 计算 contentHash。

方法：

```java
List<ChunkCandidate> split(String text, ChunkOptions options);
```

ChunkCandidate：

```java
public record ChunkCandidate(
    int chunkIndex,
    String content,
    String contentHash,
    int tokenCount,
    int sourceStart,
    int sourceEnd
) {}
```

MVP 切片策略：

```text
按段落优先
  ↓
段落过长则按固定字符数切分
  ↓
chunk-size 默认 800 字符
  ↓
chunk-overlap 默认 120 字符
```

注意：

- 不要按字节切。
- 空白文本不能生成 Chunk。
- Chunk 内容需要 trim。
- 连续空白需要归一化。

### 10.3 SearchIndexService

职责：

- 初始化 ES index。
- 写入 Chunk 文档。
- 删除指定 Document 的 ES 文档。
- 支持后续 BM25 检索。

方法：

```java
void ensureDocumentChunkIndex();
void indexChunk(EsDocumentChunk chunk);
void bulkIndexChunks(List<EsDocumentChunk> chunks);
void deleteByDocumentId(Long documentId);
```

本阶段建议使用 bulk 写入。

### 10.4 DocumentChunkService

职责：

- 保存 Chunk 到 MySQL。
- 删除旧 Chunk。
- 幂等重建 Chunk。

方法：

```java
List<DocumentChunk> replaceChunks(Document document, List<ChunkCandidate> candidates);
List<DocumentChunk> listByDocument(Long documentId);
```

幂等策略：

```text
处理开始前：
  删除 document_id 下旧 ES 文档
  删除 document_id 下旧 document_chunk

再重新写入新的 Chunk
```

### 10.5 DocumentProcessingService

职责：

- 编排完整处理流程。

方法：

```java
void process(DocumentProcessMessage message);
```

流程：

```text
读取 Task
  ↓
读取 Document
  ↓
检查 Document 是否 DELETED / INDEXED
  ↓
Task 标记 RUNNING
  ↓
Document 标记 PROCESSING
  ↓
从 MinIO 读取 objectKey
  ↓
DocumentParserService 解析
  ↓
ChunkService 切片
  ↓
删除旧 Chunk 和旧 ES 文档
  ↓
保存 DocumentChunk
  ↓
写入 Elasticsearch
  ↓
更新 Document = INDEXED
  ↓
Task 标记 SUCCESS
```

失败流程：

```text
捕获异常
  ↓
Document 标记 FAILED
  ↓
Task 标记 FAILED
  ↓
记录 errorMessage
  ↓
抛出异常交给 Kafka 重试 / DLT
```

### 10.6 DocumentProcessConsumer

职责：

- 消费 Kafka。
- 调用 `DocumentProcessingService`。

方法：

```java
@KafkaListener(...)
public void consume(DocumentProcessMessage message)
```

要求：

- Consumer 不直接写业务细节。
- 业务处理全部委托给 `DocumentProcessingService`。
- 重复消息必须幂等。

---

## 11. Controller 与 API

本阶段主要补充查询接口，不新增 RAG API。

### Document

```http
GET /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents
GET /api/v1/team/documents/{documentId}
GET /api/v1/team/documents/{documentId}/chunks
POST /api/v1/team/documents/{documentId}/reindex
```

### Search Debug

为了验证 ES 索引，建议提供一个开发期检索接口：

```http
GET /api/v1/team/knowledge-bases/{knowledgeBaseId}/search?keyword=部署
```

说明：

- 该接口只返回 Chunk 命中结果。
- 必须经过 `SpacePermissionService.requireViewSpace`。
- ES 查询必须带 `spaceId` 和 `knowledgeBaseId` filter。
- 这是 Phase 4 RAG 的前置验证接口。

SearchResponse：

```json
{
  "items": [
    {
      "chunkId": 1,
      "documentId": 10,
      "documentTitle": "部署手册",
      "chunkIndex": 3,
      "content": "...",
      "score": 12.3
    }
  ]
}
```

---

## 12. Repository 清单

### DocumentChunkRepository

```java
List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);
void deleteByDocumentId(Long documentId);
long countByDocumentId(Long documentId);
```

### DocumentRepository

Phase 2 已有，补充：

```java
Optional<Document> findByIdAndStatusNot(Long id, DocumentStatus status);
List<Document> findByKnowledgeBaseIdAndStatusNot(Long knowledgeBaseId, DocumentStatus status);
```

### TaskRepository

Phase 2 已有。

---

## 13. 权限要求

文档查询和搜索：

```text
OWNER / EDITOR / VIEWER 均可访问
```

重新索引：

```text
OWNER / EDITOR 可操作
VIEWER 不可操作
```

所有查询必须确保：

```text
Document.spaceId 属于用户可访问 Space
ES 查询 filter 包含 spaceId
ES 查询 filter 包含 knowledgeBaseId
```

---

## 14. 错误码补充

```text
DOCUMENT_PARSE_FAILED
DOCUMENT_EMPTY_TEXT
DOCUMENT_CHUNK_FAILED
DOCUMENT_INDEX_FAILED
ES_INDEX_NOT_AVAILABLE
ES_QUERY_FAILED
UNSUPPORTED_DOCUMENT_TYPE
```

---

## 15. 测试建议

### 15.1 单元测试

```text
DocumentParserServiceTest
ChunkServiceTest
DocumentProcessingServiceTest
SearchIndexServiceTest
```

重点覆盖：

- TXT 解析成功。
- Markdown 解析成功。
- 空文本解析后失败。
- 长文本可切成多个 Chunk。
- Chunk overlap 正常。
- Chunk contentHash 稳定。
- 重复处理不会重复创建 Chunk。
- Document 被删除时跳过处理。
- 处理异常时 Document / Task 标记 FAILED。

### 15.2 集成测试

如果本地有 MinIO / Kafka / ES：

```text
上传并 merge 文档
  ↓
Kafka Consumer 处理
  ↓
Document 状态变为 INDEXED
  ↓
document_chunk 有数据
  ↓
ES 中能搜到 Chunk
```

如果本地没有 ES，可先 mock `SearchIndexService`，但最终提交前应至少验证一次真实 ES。

---

## 16. 验收清单

Phase 3 验收：

- Kafka Consumer 能真实处理 `DOCUMENT_PROCESS`。
- MinIO 文件能被读取。
- TXT / Markdown / PDF 至少两类文件可以解析。
- 解析后的文本可以生成 Chunk。
- Chunk 写入 MySQL。
- Chunk 写入 Elasticsearch。
- Document 成功后状态为 `INDEXED`。
- Task 成功后状态为 `SUCCESS`。
- 处理失败时 Document 和 Task 有错误状态。
- 重复 Kafka 消息不会重复生成 Chunk。
- `GET /api/v1/team/documents/{documentId}/chunks` 可查看 Chunk。
- Search Debug 接口能按关键词搜到 Chunk。
- ES 查询包含 `spaceId` / `knowledgeBaseId` 权限过滤字段。

---

## 17. 实现顺序建议

```text
1. 添加 Tika 和 Elasticsearch 依赖
2. 添加 ElasticsearchConfig
3. 添加 EsIndexInitializer
4. 实现 EsDocumentChunk
5. 实现 SearchIndexService
6. 实现 DocumentParserService
7. 实现 ChunkService
8. 实现 DocumentChunk 实体和 Repository
9. 实现 DocumentChunkService
10. 实现 DocumentProcessingService
11. 替换 Phase 2 占位 DocumentProcessConsumer
12. 补 Document chunks 查询接口
13. 补 Search Debug 接口
14. 补单元测试
15. 运行 mvn test 或 mvn package
```

---

## 18. 给 AI 执行第三阶段的边界提醒

执行第三阶段时必须遵守：

- 不要实现 RAG Chat。
- 不要调用 LLM。
- 不要生成 Citation。
- 不要实现 WebSocket。
- 不要实现个人 ResearchProject。
- 不要实现 Artifact。
- 不要把 ES 搜索接口包装成 AI 问答。
- 所有 API 必须使用 `/api/v1`。
- ES 查询必须带权限过滤字段。
- Kafka Consumer 必须幂等。
- 失败必须回写 Document 和 Task 状态。

