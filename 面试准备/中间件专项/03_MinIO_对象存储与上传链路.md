# MinIO：对象存储与上传链路

## 0. 本篇定位

这篇用于回答 NoteWeave 为什么使用 MinIO、MinIO 在文件上传、解析文本、Citation 快照、Artifact 导出和资源清理中承担什么职责，以及面试中对象存储、分片上传、对象 key、元数据绑定、幂等和清理问题怎么讲。

## 1. 本主题面试官想考什么

面试官问 MinIO，通常想验证：

1. 你是否知道对象存储和关系数据库的分工。
2. 你是否理解文件上传为什么不能直接放 MySQL。
3. 你是否能讲清分片上传、合并、对象 key、refCount、soft delete 和清理。
4. 你是否知道对象存储只保存内容，不负责业务权限。
5. 你是否能回答“对象存在但 DB 失败”“DB 存在但对象丢失”这类一致性问题。

## 2. 高频问题清单

基础问题：

- NoteWeave 为什么用 MinIO？
- MinIO 里存了哪些对象？
- 文件为什么不直接存 MySQL？

进阶问题：

- 分片上传和 merge 链路怎么走？
- MinIO 对象和 MySQL 元数据如何绑定？
- `FileObject` 和 `Document` 为什么要分开？

深挖追问：

- merge 失败、对象合并失败、DB 写入失败分别怎么办？
- 为什么文档删除先 soft delete？
- orphan object 怎么清理？

压力追问：

- MinIO 挂了会影响哪些链路？
- 对象 key 怎么设计才不容易冲突？
- 如果未来换成 S3，改动边界在哪里？

## 3. 问答与讲解

### Q1：NoteWeave 为什么用 MinIO？

#### 面试官为什么问

这是存储选型题。面试官想看你是否知道二进制内容、解析文本、快照和导出文件不适合都塞进 MySQL。

#### 回答思路

先讲 MinIO 是对象存储，适合大文件和对象内容；再讲 MySQL 保存元数据、权限、状态和引用关系。

#### 结合 NoteWeave 怎么答

MinIO 保存上传分片、合并后的原始文件、parsed text、Citation snapshot、Artifact 导出对象等。MySQL 保存 `FileObject`、`Document`、`DocumentChunk`、`Citation`、`ArtifactVersion` 等元数据。

#### 技术原理 / 链路设计讲解

对象存储适合大对象、流式读写、按 key 存取和对象生命周期管理。关系数据库适合结构化查询、事务和权限关系。把文件内容放 MinIO，业务事实放 MySQL，能避免数据库膨胀，也让文件处理和业务查询职责更清楚。

#### 实现兜底锚点

- `FileStorageService`
- `StorageConfiguration`
- `StorageProperties`
- `DocumentUploadService`
- `SourceStorageSupport`
- `ArtifactStorageSupport`
- `AdminStorageSupport`
- `SystemHealthService.checkMinio()`
- `Phase2UploadFlowIntegrationTest`

#### 可直接复述的面试回答

NoteWeave 用 MinIO 是为了把文件对象和业务元数据分开。用户上传的原始文件、分片对象、解析后的文本、Citation snapshot、Artifact 导出这些内容都适合放对象存储，因为它们体积可能较大，也不需要像关系表一样频繁做复杂查询。MySQL 里只保存 `FileObject`、`Document`、`Citation`、`ArtifactVersion` 这类元数据和关系。这样一来，MinIO 负责“内容在哪里”，MySQL 负责“这个内容属于谁、当前状态是什么、谁有权限访问、被哪些证据引用”。这个边界很重要，因为对象存储本身不等于业务权限。

#### 常见追问

- 为什么不用 MySQL BLOB？
- MinIO 对象 key 如何设计？
- 对象存在是否代表用户有权限访问？

#### 常见坑

- 不要说 MinIO 负责权限判断。
- 不要把对象 key 暴露成用户可直接访问的下载地址。

### Q2：团队文档上传和 MinIO 的完整链路怎么走？

#### 面试官为什么问

这是端到端流程题。面试官想听分片、合并、校验、任务投递和对象绑定。

#### 回答思路

按 init upload -> upload chunk -> Redis bitmap/MySQL chunk record -> MinIO temp object -> merge compose -> FileObject/Document -> DOCUMENT_PROCESS 讲。

#### 结合 NoteWeave 怎么答

`FileStorageService.putObject` 写分片，`composeObject` 合并对象，`statObject/getObject/removeObject` 做校验、读取和清理。merge 后再创建 Document 和后续任务。

#### 技术原理 / 链路设计讲解

分片上传避免大文件一次性上传失败后重来；对象 key 用 dev/test 前缀和 uploadId 隔离临时对象；merge 时需要同时校验 Redis 进度、MySQL 分片记录和 MinIO 对象是否存在。

#### 实现兜底锚点

- `DocumentUploadService`
- `UploadBitmapService`
- `UploadChunkRepository`
- `FileStorageService.putObject`
- `FileStorageService.composeObject`
- `FileStorageService.statObject`
- `Phase2UploadFlowIntegrationTest`

#### 可直接复述的面试回答

团队文档上传里，MinIO 参与的是文件内容存储。用户先 init upload，服务端创建上传会话；每个 chunk 上传成功后，后端会把分片对象写入 MinIO，同时记录 MySQL 分片信息，并用 Redis bitmap 加速进度查询。merge 时系统不会只信 Redis，而是校验分片完整性和 MinIO 对象是否存在，然后用 MinIO compose 把分片合成最终对象。合并完成后，MySQL 创建 `FileObject` 和 `Document`，再创建 `DOCUMENT_PROCESS` 任务交给 Kafka/Worker 做解析、切 chunk 和索引。这样上传链路把“大对象内容”交给 MinIO，把“状态、权限、任务和元数据”交给 MySQL。

#### 常见追问

- 如果某个 chunk 对象丢了怎么办？
- merge 成功但后续 DB 写失败怎么办？
- 为什么分片对象要做过期清理？

#### 常见坑

- 不要说 Redis bitmap 是唯一完整性依据。
- 不要把 MinIO merge 成功等同于文档可检索。

### Q3：MinIO 对象和 MySQL 元数据怎么保持一致？

#### 面试官为什么问

这是对象存储最常见的一致性追问。

#### 回答思路

说明当前是业务事务 + 对象操作 + 补偿清理的最终一致思路，不是跨 MySQL/MinIO 的强事务。

#### 结合 NoteWeave 怎么答

MinIO 保存对象，MySQL 通过 `FileObject.objectKey`、`Document.fileObjectId`、`parsedTextObjectKey`、`snapshotObjectKey` 等字段引用对象。删除文档先 soft delete，资源清理扫描 orphan object 后再执行。

#### 技术原理 / 链路设计讲解

对象存储和 MySQL 没有天然本地事务。设计上要避免在用户路径里直接物理删除对象，而是通过状态标记、引用计数、清理扫描和可重试任务处理不一致。

#### 实现兜底锚点

- `FileObject`
- `Document`
- `Citation.snapshotObjectKey`
- `ResourceCleanupService`
- `CleanupResourceTaskWorker`
- `Phase15AdminOpsIntegrationTest`

#### 可直接复述的面试回答

MySQL 和 MinIO 之间不是一个本地事务，所以我不会说它们强一致。NoteWeave 的做法是把对象 key 存成 MySQL 元数据，比如 `FileObject.objectKey`、文档的 parsed text key、Citation snapshot key、Artifact 导出 key。用户删除文档时先 soft delete，把业务可见性拿掉，而不是立刻物理删除对象。后续 Admin/Ops 的 cleanup 会扫描 orphan object、过期上传分片或不再引用的对象，再执行清理。这样做的好处是不会因为一次删除或失败把证据链和排障线索直接抹掉，也给补偿和人工确认留空间。

#### 常见追问

- 对象存储失败要不要回滚数据库？
- DB 成功但对象没写上怎么办？
- 如何避免误删仍被引用的对象？

#### 常见坑

- 不要说 MySQL 和 MinIO 有天然分布式事务。
- 不要直接物理删除仍可能被 Citation 或 Artifact 引用的对象。

### Q4：对象存储八股怎么结合项目讲？

#### 面试官为什么问

面试官可能从 MinIO 追到 S3、对象 key、预签名 URL、分片上传、生命周期管理。

#### 回答思路

每个概念都落到 NoteWeave 的上传和证据链。

#### 结合 NoteWeave 怎么答

- 对象 key：按 dev/test 前缀、业务类型、uploadId、citationId、artifactId 等组织。
- 分片上传：避免大文件上传失败重传。
- Compose：合并 chunk。
- Bucket：区分 dev/test bucket。
- 生命周期管理：过期上传和 orphan object 清理。
- 预签名 URL：当前不作为主讲能力，下载仍应走鉴权 API。

#### 可直接复述的面试回答

对象存储八股我会结合 NoteWeave 讲。对象 key 不能随便用文件名，因为会冲突，也容易泄露业务信息，所以要按环境前缀和业务类型组织；分片上传解决大文件网络不稳定和断点续传问题；compose 负责把临时 chunk 合成最终对象；bucket 可以区分 dev/test；生命周期管理和 Admin cleanup 用来处理过期上传和 orphan object。至于预签名 URL，当前我不会把它当主链路能力来讲，因为业务下载和 Citation 查看仍然应该走统一鉴权，不能把对象地址直接暴露给用户。

#### 常见追问

- 文件名相同怎么办？
- 为什么不用本地磁盘？
- MinIO 和 S3 切换成本在哪里？

#### 常见坑

- 不要用原始文件名当唯一 key。
- 不要让对象存储绕过业务权限。

### Q5：MinIO 挂了怎么办？

#### 面试官为什么问

这是故障边界题。

#### 回答思路

区分读写文件链路受影响，已有 MySQL 业务状态仍可查；解析、上传、导出、Citation snapshot 可能失败或降级。

#### 结合 NoteWeave 怎么答

`SystemHealthService.checkMinio()` 会检查 bucket、prefix、objectCount。上传和解析任务失败会记录 TaskAttempt/TaskEvent，后续可重试。

#### 可直接复述的面试回答

MinIO 挂了主要影响对象读写链路，比如上传 chunk、merge 文件、读取原始文件解析、保存 parsed text、生成 Citation snapshot 或导出 Artifact。MySQL 里的业务元数据还在，所以系统仍然知道文档、任务和引用关系的状态，但涉及对象内容的操作会失败。NoteWeave 通过健康检查发现 MinIO 状态，通过 TaskAttempt 和 TaskEvent 记录失败，后续可以重试或由 Admin/Ops 处理。这个边界说明 MinIO 是内容存储，不是业务状态机本身。

#### 常见追问

- 已经上传一半的文件怎么办？
- 解析任务失败如何重试？
- 是否需要多副本或对象存储高可用？

#### 常见坑

- 不要说 MinIO 挂了不影响任何功能。
- 不要说重试能自动解决所有对象不一致。
