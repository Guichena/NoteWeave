# 阶段2：资料上传与问答RAG

## 1. 阶段目标

阶段 2 要交付 v2 的第一条真实用户闭环：

`工作台 -> 上传资料 -> 完成解析索引 -> 发起问答 -> 返回带引用答案`

## 2. 与原始 NoteWeave Phase 的关系

当前阶段 2 主要对应原始 `Phase 1：问答模式`，同时把原始设计里没有单独拆开的上传、解析、切片和引用底座一起补齐。

## 3. 子阶段树

```text
2.1 上传与建档
2.2 解析、切片、索引
2.3 问答与引用闭环
```

## 4. 子阶段 2.1 上传与建档

### 目标

打通上传事务、分片、合并、建档。

### 优先迁移

1. 旧版文件上传与分片合并逻辑
2. 旧版文件去重与对象存储映射逻辑

### 推荐函数与类

Controller：

1. `UploadController.uploadChunk(uploadId, chunkIndex, contentMd5, filePart)`
2. `UploadController.completeUpload(uploadId)`

Application：

1. `UploadApplicationService.acceptChunk(cmd)`
2. `UploadApplicationService.completeUpload(uploadId)`

Domain / Infra：

1. `FileMergeService.mergeUploadChunks(uploadId)`
2. `FileObjectService.getOrCreateFileObject(workspaceId, mergedFile)`
3. `SourceDomainService.createSourceFromUpload(cmd)`
4. `SourceSnapshotService.createInitialSnapshot(sourceId, fileObjectId, objectKey, hash)`
5. `SourceTaskPublisher.publishParseTask(sourceId, snapshotId)`

### 中间件接入

MySQL：

1. `document_upload`
2. `upload_chunk`
3. `file_object`
4. `source`
5. `source_snapshot`

MinIO：

1. 临时分片路径：
   `workspace/{workspaceId}/upload_tmp/{uploadId}/{chunkIndex}`
2. 原始文件路径：
   `workspace/{workspaceId}/source/{sourceId}/snapshot/{versionNo}/original/{fileName}`
3. 去重规范化文件路径：
   `workspace/{workspaceId}/file_object/{sha256}-{fileName}`

### TDD 要求

先写：

1. 创建上传事务测试
2. 上传分片测试
3. 完成上传集成测试

再写：

1. `document_upload`
2. `upload_chunk`
3. `file_object / source / source_snapshot`

### 完成定义

1. 用户能完成一次上传
2. 系统能创建 `source` 及快照
3. 同一工作台内重复上传相同内容时复用同一个 `file_object`
4. `file_object.object_key` 指向的规范化对象必须真实可读
5. 如果前端传入 `Content-MD5`，后端必须校验分片内容，校验失败时拒绝写入

## 5. 子阶段 2.2 解析、切片、索引

### 目标

把上传后的资料转成可检索 `source_chunk`。

### 优先迁移

1. 旧版解析器
2. 旧版 chunker
3. 旧版 ES 索引写入逻辑

### 推荐函数与类

Java：

1. `OutboxPublishScheduler.publishPendingEvents()`
2. `SourceTaskConsumer.handleParseTask(event)`
3. `SourceParseService.parseSource(sourceId, snapshotId)`
4. `ChunkBuildService.buildChunks(parsedDocument)`
5. `SourceChunkService.persistChunks(snapshotId, chunks)`
6. `SourceIndexService.indexChunks(snapshotId, chunks)`
7. `SourceStatusService.markParsed(sourceId, snapshotId)`
8. `SourceStatusService.markIndexed(sourceId, snapshotId)`

Parser 子函数：

1. `ParserRouter.selectParser(mimeType)`
2. `MarkdownParser.parse(file)`
3. `PdfParser.parse(file)`
4. `TextStructureExtractor.extractSections(text)`

### 中间件接入

Kafka：

1. topic：`noteweave.source.parse`
2. topic：`noteweave.source.index`
3. consumer group：`noteweave-source-worker`

MinIO：

1. 解析正文：
   `workspace/{workspaceId}/source/{sourceId}/snapshot/{versionNo}/parsed/content.md`
2. 结构元数据：
   `workspace/{workspaceId}/source/{sourceId}/snapshot/{versionNo}/parsed/structure.json`

Elasticsearch：

1. index alias：`nw-source-chunk`
2. 查询必须带 `workspace_id`

### TDD 要求

先写：

1. 解析任务测试
2. chunk 生成测试
3. ES 索引写入测试

再写：

1. parser
2. chunker
3. indexer
4. outbox + consumer

### 完成定义

1. `parse_status / index_status` 能正确变化
2. chunk 能按工作台维度检索
3. 资料解析任务会写入 `task_event`，并可通过 `/api/v2/tasks/{taskId}/events` 以 SSE 读取
4. 前端上传解析后展示任务状态和任务事件流

## 6. 子阶段 2.3 问答与引用闭环

### 目标

打通 `answer_mode = QA` 的统一聊天链路。

### 优先迁移

1. 旧版 RAG 检索逻辑
2. 旧版 citation 回源逻辑
3. 旧版流式输出逻辑

### 推荐函数与类

Controller：

1. `ChatController.sendMessage(conversationId, req)`
2. `ChatStreamController.stream(requestId)`

Application / Orchestrator：

1. `ChatApplicationService.sendMessage(cmd)`
2. `ChatOrchestrator.answerQa(messageId, context)`
3. `ChatContextService.buildChatContext(conversationId, answerMode)`

Retrieval：

1. `RetrievalDomainService.retrieveForQa(workspaceId, query)`
2. `QueryRewriteService.rewriteQaQuery(query)`
3. `ChunkRetriever.search(workspaceId, rewrittenQuery)`
4. `EvidenceSelectionService.selectEvidence(candidates)`

Citation：

1. `CitationService.createCitations(messageId, selectedEvidence)`
2. `CitationBackfillService.buildCitationView(citationIds)`

Stream：

1. `ChatStreamService.open(requestId)`
2. `ChatStreamService.publishDelta(requestId, delta)`
3. `ChatStreamService.publishCitation(requestId, citationItem)`
4. `ChatStreamService.complete(requestId, messageId)`

### 中间件接入

MySQL：

1. `conversation`
2. `conversation_message`
3. `citation`
4. `message_citation`

Redis：

1. `idempotency:chat:{requestId}`
2. `stream:chat:{assistantRequestId}`

SSE 事件：

1. `chat.delta`
2. `chat.citation`
3. `chat.completed`
4. `chat.failed`

Elasticsearch：

1. 只查当前工作台 alias `nw-source-chunk`
2. 第一批默认 `BM25 + metadata filter`

### TDD 要求

先写：

1. 会话创建测试
2. 发送消息测试
3. citation 落库测试
4. SSE 输出测试

再写：

1. retrieval service
2. citation service
3. chat orchestrator
4. SSE endpoint

### 完成定义

1. 用户能在 QA 模式提问
2. 系统返回答案与 citation
3. `conversation_message` 与 `message_citation` 正常落库

## 7. 本阶段禁止项

1. 不扩展到 Note / Wiki
2. 不接入 Artifact
3. 不接入 Deep Research
