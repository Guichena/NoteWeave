# 主 agent 核实：OTH-11、OTH-12 及服务器数据库证据

基线：70631bd015067b19f9d55aaec92d7429751dc936。代码核实时间：2026-10-23。禁止以历史通过数代替本轮验证。

## 数据库只读统计

由主 agent 在 `START TRANSACTION READ ONLY` 中查询；凭据仅使用 MySQL 容器已有环境，不输出正文、来源标识或凭据。

- Flyway 最大成功版本 V192，失败数 0。
- task_outbox 总行数 10；`(task_id, topic, message_key)` 在途 READY/PROCESSING 重复组 0，全历史重复组 0；task_id NULL 行数 0。
- task_outbox 仅 PRIMARY(id) 和普通调度/租约/状态索引，没有在途业务唯一约束。
- memory_application_outcome 总行数 0；仅 PRIMARY(id) 和三个普通索引，没有来源幂等唯一约束。不能从当前无数据推导并发正确。
- source_embedding_batch 具有唯一 `(source_snapshot_id,batch_version,batch_no)` 和普通 `(status,lease_until)` 索引；存在 READY 1 行且更新时间早于 5 分钟。仅证明滞留 READY 存在，是否有当前任务资格需实现前按任务/版本过滤，不能凭此重投。

## OTH-11 已确认需要修复

### 全部生产写入点：14 处 / 10 个文件

| 文件 | 行 | 入队身份/入口 |
|---|---|---|
| artifact/ArtifactJobWriteRepository.java | 305 | artifact job，task+artifact.job+jobId |
| artifact/VideoLearningRequestService.java | 74 | material task，artifact.job+parentRequestId，createOrReplay 已有业务幂等 |
| upload/UploadService.java | 335 | 上传完成→parse，task+parse+sourceId |
| source/SourceReprocessService.java | 135,177 | 新任务重建索引/重解析；新 task 必须允许新消息 |
| source/SourceParseService.java | 234,415 | parse/chunk→embed/projection、转写/解析→chunk |
| memory/MemoryConversationExtractionService.java | 194 | user message→memory.extraction |
| knowledge/WikiIngestService.java | 233,249 | ingest/retract，分别主题；新 task 合法 |
| conversation/ConversationTopicSummaryV2Service.java | 136 | topic summary revision |
| conversation/ConversationSegmentBuildService.java | 132 | segment summary revision |
| retrieval/projection/SourceEmbeddingBatchRepository.java | 178,315 | index 完成投递与每批 embed，message_key=snapshot 或 snapshot:batch |

### 调用链与证据

- V002:40-52 建表仅 id 主键，V022/V023 增加 attempt/lease 与普通索引，没有在途唯一索引。
- TaskOutboxDispatcherService.dispatchReadyMessages 115-132 → DurableOutboxDispatcher.findTaskCandidates 219-245 → claim 及每次随机 deliveryToken；当前防重针对单行，不能排除两个不同 id 的相同业务消息。
- TaskOutboxDispatcherService.redrive 184-207 允许 DEAD_LETTER→READY，必须在同业务键已有在途行时保留其状态并准确返回，不得全历史唯一导致 SENT/DEAD_LETTER 永远不能合法重驱。
- 向量旧/完成/有效租约失败：RetrievalProjectionDeadLetterFinalizer.finalizeFailure 55-94 返回 false 含不接受晚到失败；TaskOutboxDispatcherService 156-164 却继续 `taskService.failTask`，绕过批次拒绝资格。应区分已处理/过时拒绝/真正失败，不得将所有 false 直接失败任务。
- source ParseService:234 存在 taskId 可能 null 的旧同步调用边界；新入队服务须明确兼容现有表约束，不凭猜测默默扩大 key。

### 实施边界与验收

- 在途幂等键基于 task+topic+message_key，仅 READY/PROCESSING 约束；NULL 历史/同步路径单独核实。迁移前保守检查重复，不删除正常业务历史。
- 公共 enqueuer 与数据库唯一约束双重保证；并发重复入队同 payload 返回既有有效行，同 key 不同 payload 必须拒绝/明确说明，不静默吞更新。
- Kafka 发布/事务后丢确认仍属于至少一次投递，必须保留 callback fencing、失败重试和死信恢复。
- 先完成 memory/pipeline 所拥有文件，再由唯一 Outbox 实现者对这些文件串行迁移入队调用。不可同时修改。
- 验收：同键双线程、一键不同 payload、不同批次/版本/新任务、SENT 后重新入队、DEAD_LETTER 重驱与已有在途竞争、事务回滚、旧批/完成批/有效租约晚到失败不污染当前任务。

## OTH-12 已确认需要修复，但旧文档描述过粗

### 已有行为

- ChatService→RetrievalOrchestrator→QaPassageEvidenceRetriever 33-58→QaPassageRetriever 123-144：hybrid 异常时，mysqlFallbackEnabled=true 可走 MySQL，false 返回 503。MySQL 回退已有降级原因、workspace/source scope 和 generated source 资格检查。
- NoteRecallRetriever 84-120：源召回异常时，localMetadataFallbackEnabled=true 可走本地 BM25F，false 抛明确异常；已有本地结果追踪。
- 两开关默认 false，ProductionConfigurationGuard 129-133/229-230 明确禁止生产开启。这是既定能力边界，不能擅自去掉安全限制或改线上开关。

### 当前真实缺口

1. 无 ES 部署的资料 `SourceParseService:184-209` 被落为 source READY/PARSED、index DISABLED、chunk NOT_PROJECTED，任务 PARSED_LOCAL_ONLY。
2. QA 本地 SQL `QaPassageRetriever:263-279` 只取 INDEXED/PROJECTED；Note 数据库召回 `NoteRecallRepository:44-51,101-107,312,406-409` 同样只认 INDEXED。故即便显式开启允许的本地降级，无 ES 上传的真实资料仍不会被检索到。
3. `RetrievalHydrator` 的窗口/统计也限定 INDEXED，必须完整检查回填链，不能只放宽候选 SQL。
4. `QaHybridRetriever:74-85` 串行依赖 embedding→vector→keyword；一通道异常会阻断可用另一路。NoteRecallRetriever 103-106 在任一路异常时把已成功结果一起清空。需按当前任务语义核实保留可用部分和原因，不把空返回当成功的完整召回。

### 最小修复边界

- 仅在显式允许本地回退时读取当前最新 PARSED/DISABLED 的本地资料；与 INDEXED 数据分离查询/状态判定，不能放开 FAILED、PENDING、DELETED、撤权、旧快照、未合格 research 产物。
- 不修改 research 资格 gate 的实现；调用现有门控。默认关闭/生产 guard 行为保留。
- OTH-01 所属 ChatService 不并行修改，优先在 QaPassageRetriever、NoteRecallRepository/NoteRecallRetriever、RetrievalHydrator 与 tests 完成；若必须 ChatService 变动则队列化。
- 验收：ES disabled/local-only、有索引后故障、部分通道异常、回退关闭仍明确失败、空查询/无相关内容、workspace/source scope、最新版本、删除/撤销/生成来源门控、降级原因最终 evidence 可见；全部用隔离替身，不停止线上 ES。
