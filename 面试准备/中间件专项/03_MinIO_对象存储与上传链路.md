# MinIO：对象存储、分片上传与证据快照

> 本文件为 2026-06-01 加深版。MinIO 在 NoteWeave 里承担大对象存储，不承担权限判断和业务状态判断。

## 0. 一句话定位

MinIO 保存文件类内容：上传分片、合并后的原始文件、解析文本、Source 原文、Citation snapshot、Artifact 导出等。MySQL 只保存 object_key、状态、hash、引用关系和清理记录。

## 1. 当前真实链路

### 上传和文件对象

相关表：
- `document_upload`：上传会话、file_md5、chunk_size、total_chunks、status、expires_at、task_id。
- `upload_chunk`：每个 chunk 的 object_key，`upload_id + chunk_index` 唯一。
- `file_object`：合并对象，`space_id + content_hash` 唯一，保存 object_key、size、content_type、ref_count、status。
- `document`：业务文档，引用 file_object 和 object_key。

面试怎么讲：
- 上传不是一次请求完成，而是 init、chunk 上传、bitmap 标记、merge、file_object/document 落库、DOCUMENT_PROCESS Task。
- `file_object` 按 space 隔离 content_hash，避免跨空间秒传泄露内容或存在性。
- `ref_count` 用来辅助清理，但不能只凭 ref_count 删除，要结合 document、citation、snapshot、cleanup 扫描。

### 解析文本和 Source

相关字段：
- `document.parsed_text_object_key`
- `source.raw_text_object_key`
- `source.parsed_text_object_key`

面试怎么讲：
- 原文件可能是 PDF、网页、文本，解析后的可读文本也可能比较大，所以不直接塞 MySQL。
- MySQL 保存解析状态和 object key；真正大文本放 MinIO，后续 chunk/index/证据回溯再读取。

### Citation snapshot

相关字段：
- `citation.snapshot_object_key`
- `citation.quote_text`
- `citation.quote_hash`
- `citation.source_version`

面试怎么讲：
- Citation 既要能指回当前资源，也要在资源变化时保留必要快照。
- snapshot 不是绕过权限的后门，读取 citation 时仍要做资源权限校验。

### cleanup

相关表：
- `ops_cleanup_job`
- `ops_cleanup_item`

面试怎么讲：
- cleanup 先 scan 再 execute，把疑似孤儿对象登记为 item，人工或任务执行清理。
- 清理结果可审计，避免直接定时任务扫 MinIO 后误删。

## 2. 高频深问与答法

### Q1: 为什么文件不直接放 MySQL？

答：文件、解析文本、snapshot 都是大对象，直接放 MySQL 会让事务表膨胀，影响备份、查询和索引。NoteWeave 的设计是 MySQL 管状态、归属、hash、引用和 object_key，MinIO 管字节内容。这样权限和任务仍回 MySQL 判断，大对象读写交给对象存储。

### Q2: object_key 泄露会不会越权？

答：不能把 object_key 当授权凭证。MinIO 只是存储层，业务层读取前要通过 SpacePermissionService/ResourceAccessService 判断用户是否能访问 Document、Source、Artifact 或 Citation。面试时要强调：object_key 不能直接暴露给无权限用户，下载和 snapshot 展示都要走后端授权。

### Q3: 合并文件成功但任务创建失败怎么办？

答：如果 MinIO 已有合并对象但 MySQL Task/Document 没创建成功，就会形成可能的孤儿对象。当前要通过事务边界尽量先保证业务事实和任务创建；残留由 Admin/Ops cleanup scan 找出，再登记 `ops_cleanup_item` 后执行清理。

### Q4: 删除文档后 MinIO 对象什么时候删？

答：业务删除先软删除 Document，让查询和 RAG 不再可见；物理对象清理要看 file_object ref_count、是否还有 citation snapshot、是否还有 Source/Artifact 引用，再由 cleanup 做。不能说删除接口立即删 MinIO。

### Q5: Bilibili MCP 或 URL Source 的外部文本要不要也进 MinIO？

答：可以作为原始文本、解析文本或工具上下文保存到对象存储或生成链路里，但不能自动变成长期知识。它们进入 Artifact、Wiki、Synthesis 或 Memory 前仍要经过权限、证据和确认边界。

## 3. 性能和风险怎么说

- 可观察指标：上传成功率、分片重传次数、merge 耗时、MinIO health、对象数量、cleanup 扫描数量。
- 风险点：对象残留、object_key 泄露、跨空间复用泄露、解析文本和原文件状态不一致。
- 扩展点：后续可以换成 OSS/S3，只要保留 FileStorageService、object_key、bucket/prefix 和 cleanup 抽象。

## 4. 不能说满

- 不要说 MinIO 做权限判断。
- 不要说上传成功就代表可检索。
- 不要说 file_object hash 可以跨所有空间安全复用。
- 不要说 cleanup 会无风险自动删除所有残留对象。
