# 04 团队知识摄取与索引链路

## 0. 本篇定位

这条链路回答：团队文档如何从上传进入系统，并变成可检索、可引用的 DocumentChunk。

核心链路：

```text
KnowledgeBase
-> DocumentUpload init
-> chunk upload
-> merge
-> FileObject / Document
-> DOCUMENT_PROCESS Task
-> parse
-> chunk
-> indexVersion
-> Elasticsearch index
-> activeIndexVersion
```

## 面试先说版

这条链路我会从“团队资料怎么稳定进入 RAG 系统”讲。上传文件只是入口，真正的系统问题是：大文件怎么分片和断点续传，原始文件放哪里，解析和索引失败怎么恢复，相同文件能不能复用，重建索引时怎么不影响线上检索，以及检索时怎么避免读到旧版本或越权内容。

NoteWeave 的设计是：MySQL 存上传、文档和索引版本这些业务事实；MinIO 存原始文件、分片和解析后的大文本；Redis 用 bitmap 记录分片上传进度；Kafka/Worker 异步做解析、切片和索引；Elasticsearch 承接 BM25、向量检索和过滤。索引重建时不直接覆盖旧索引，而是写新 indexVersion，成功后再切 activeIndexVersion。

这里能自然带出的八股是：分片上传、对象存储、Redis bitmap、异步任务、ES 与 MySQL 最终一致、索引版本切换、软删除和延迟清理。

## Q1：团队文档从上传到可检索的完整链路是什么？

**答：**

用户先在某个团队空间的知识库下初始化上传，系统记录文件 md5、文件名、大小、分片大小、总分片数等元信息。分片上传时，分片对象写入 MinIO，并通过 Redis bitmap 记录哪些分片已经上传。

上传完成后系统合并分片，生成最终对象，再按 `spaceId + contentHash` 复用或创建文件对象，并创建文档和后台处理任务。

后台 Worker 消费任务后解析 PDF、Markdown、TXT 等文本，保存解析结果，再切成 chunk。最后把 chunk 写入 Elasticsearch，并在成功后切换文档的 activeIndexVersion。

## Q2：为什么上传要异步解析？

**答：**

解析、切片、写索引都可能耗时，也可能依赖 MinIO、ES、Tika 等外部组件。如果放在用户请求里同步做，大文件会导致接口超时，失败也难以恢复。

异步任务可以返回 taskId，让用户查看处理状态，失败后也能重试或排查。

## Q3：为什么 `FileObject` 复用要按 Space 隔离？

**答：**

文件内容可以相同，但权限不能共享。

如果两个团队上传了相同 hash 的文件，底层对象内容理论上可以复用，但业务元数据必须分开。A 团队能看到这个文件，不代表 B 团队也能看到 A 的 Document、Chunk、Citation 或检索结果。

所以 `FileObject` 按 `spaceId + contentHash` 建唯一约束。这样既能在同一空间内复用对象，又不会因为 hash 相同导致跨空间权限污染。

## Q4：解析和索引怎么保证一致性？

**答：**

核心是 indexVersion 和 activeIndexVersion。

文档处理时不会先把旧索引删掉再写新索引，而是创建新的 indexVersion。新版本解析、切片、写 ES 成功后，再把 Document 的 activeIndexVersion 切到新版本。如果新版本处理失败，旧版本仍然可用，检索链路不会被破坏。

检索时也不是只相信 ES。ES 召回后还会回查 MySQL，确认文档没有删除、KnowledgeBase 没有归档、chunk 的 indexVersion 等于文档 activeIndexVersion。

## 常见追问

**追问：重复消费 DOCUMENT_PROCESS 会怎样？**

当前链路按幂等目标处理。Worker 执行前会回查 task 和 Document 状态，chunk 侧也有版本和唯一约束，用来避免重复创建 active chunk 或重复切换错误版本。

**追问：删除 Document 时为什么不立刻物理删除？**

删除主要是软删除。物理清理更适合放到 Admin/Ops 的 cleanup scan/execute 中，先扫描可清理资源，再确认执行，避免误删仍被引用的对象。

## 实现兜底锚点

- `DocumentUploadService`
- `UploadBitmapService`
- `DocumentProcessingService`
- `DocumentParserService`
- `ChunkService`
- `VectorIndexerService`
- `Phase2UploadFlowIntegrationTest`
- `Phase3DocumentProcessingIntegrationTest`

## 3 到 5 分钟深答模板

> 团队文档从上传到可检索，我一般按六步讲。第一步是初始化上传，在团队空间的知识库下记录上传元信息；第二步是分片上传，把分片写入对象存储，并用 Redis bitmap 记录上传进度；第三步是 merge，合并成最终对象，按空间和内容 hash 做对象复用，但不跨空间共享权限；第四步是创建后台处理任务，通过统一任务底座异步解析；第五步是 Worker 解析文本、保存解析结果、切 chunk、写 Elasticsearch 索引；第六步是通过 activeIndexVersion 切换可检索版本。这个链路的关键不只是“文档进 ES”，而是上传态和处理态分离、对象复用不越权、索引重建失败不破坏旧版本、检索时还要回查 MySQL 状态。

## 边界和不能说满的地方

- 可以坚定讲：分片上传、断点续传、异步解析、indexVersion 和 activeIndexVersion。
- 不要讲成：跨空间按 hash 直接共享权限；reindex 时直接覆盖旧索引；所有 OCR 或复杂文档类型都已完整支持。
