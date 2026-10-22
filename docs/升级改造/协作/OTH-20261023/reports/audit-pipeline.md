# OTH-08 / OTH-09 / OTH-10 向量流水线静态核实

## 0. 范围、证据口径与结论

- 基线按委派方提供：`70631bd015067b19f9d55aaec92d7429751dc936`；目标开发分支 `codex/oth-20261023`；服务器开发目录 `/data/workspace/noteweave-oth-20261023`。本报告只读取本机同提交快照，未自行运行 Git 核对提交、未连接数据库。
- 只使用 glob / grep / read 做源码核实；唯一写入是本报告。未实现、未测试、未调用模型、未部署、未创建嵌套 agent、未占用迁移编号。迁移由主 agent 分配，不能自行使用 V193。
- 已读 [AGENTS.md](<../../../../../AGENTS.md>)、[执行提示词](<../../../../../docs/升级改造/协作/AI执行提示词-其他改造线路-2026-10-22.txt>)、[其他线路接手说明](<../../../../../docs/升级改造/协作/AI接手说明-其他改造线路-2026-10-22.txt#L102-L128>)、[总账对应项](<../../../../../docs/升级改造/协作/任务总账-2026-10-22.json#L266-L313>)、[research 共享边界](<../../../../../docs/升级改造/协作/AI接手说明-Research改造线-2026-10-22.txt#L25-L35>)。

| 编号/子项 | 分类 | 核实结论 |
|---|---|---|
| OTH-08 运行期续租 | 已确认需要修复 | 未发现 source embedding 批次 heartbeat/renew；只有 claim 设置一次期限。不能用已存在的 artifact/research heartbeat 证明本链已修。 |
| OTH-08 旧持有者提交隔离 | 已修复并有证据 | 每次 claim 生成新 lease_token；完成/上下文调用校验 task、token、有效期、当前快照和版本；对象路径也含 token。必须保留，不应换成 taskId fencing。 |
| OTH-08 有效租约/P99 | 仅需验收 | 代码已有请求上界推导、溢出拒绝和租约下限；实际 P99、暂停/时钟偏差、多实例抢占需后续隔离验收，不能认定现有 7200 秒适合生产。 |
| OTH-09 持久请求预算 | 已确认需要修复 | embedding_request_budget 仅初始化和映射；无 consumed/used 列、无 CAS 扣减、无 embedding 请求许可。现有 context_model_calls 是另一套预算。 |
| OTH-09 计费与重试重置口径 | 缺少产品决定 | 普通自动重投应沿用冻结批次累计预算；人工重驱预算耗尽后是否可经显式授权追加预算、旧在途行如何初始化已用量需决定。不能默认每次 claim/new task 清零。 |
| OTH-10 READY 恢复 | 已确认需要修复 | 仅扫描过期 RUNNING；长期 READY 无周期重投。Outbox 单行重驱把同快照其他未完成批次也变 READY，可留下没有新消息的兄弟批次。 |
| OTH-10 当前身份/完成防重 | 已修复并有证据（部分） | claim 与完成有当前任务/最新快照/冻结版本及 COMPLETED 防重；恢复扫描本身缺 activeVersion，需修复，不能宣称整个恢复守卫完整。 |
| 历史原始审计/实际成本 | 缺少资料待核实 | 可读交接摘要，但原 REVIEW-d2/d4/d10 和 E-2/M3-M06 完整审计未发现；现有 schema 无 embedding 已用请求，不能从数据库还原真实历史成本。 |

主 agent 在本轮通过 send_message 转述的生产只读聚合结果（非本子 agent 独立查询）：Flyway 至 V192，失败 0；批次表现有唯一键 `(snapshot,version,no)` 和普通 `(status,lease_until)`；READY 且 updated_at 超 5 分钟有 1 行；task_outbox 共 10 行，在途及全历史三元组重复均为 0。该 1 行是否属于当前有效任务/版本尚需下面资格聚合 SQL；不能据行数直接宣称生产故障已证实。

## 1. 完整执行、恢复与失败链路

### 1.1 正常异步入口

1. 切片完成后写快照 `PARSED/INDEXING/EMBEDDING`，冻结 embedding_task_id，插入初始化消息： [SourceParseService.java:184–241](<../../../../../backend/src/main/java/com/noteweave/source/SourceParseService.java#L184-L241>)。
2. [TaskOutboxDispatcherService.java:115–132](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java#L115-L132>) 定时发布；其 CompletionMode 为 SENT，Kafka 发送确认不代表批次完成。 [DurableOutboxDispatcher.java:219–244](<../../../../../backend/src/main/java/com/noteweave/infra/outbox/DurableOutboxDispatcher.java#L219-L244>) 只重试 READY 或租约过期 PROCESSING，不重新发布 SENT。
3. [KafkaTaskConsumer.java:160–186](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java#L160-L186>) 区分初始化消息和含 batchNo/batchVersion 的批次消息；整数/版本校验后调用 coordinator。异常经 [KafkaTaskConsumer.java:339–354](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java#L339-L354>) 抛出，非静默吞掉。
4. 初始化 [SourceRetrievalProjectionCoordinator.java:76–85](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionCoordinator.java#L76-L85>) → repository.initialize：资料/快照/任务锁、片段/元数据摘要、版本哈希、每批 ≤64、持久预算、按批入 Outbox；同任务重复初始化不再投递，换任务保留完成结果与 context 账本，只投未完成批次：[SourceEmbeddingBatchRepository.java:58–107](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L58-L107>)。
5. 批次 [SourceRetrievalProjectionCoordinator.java:89–109](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionCoordinator.java#L89-L109>) → claim → 核对实际配置版本 → computeEmbeddingBatch → saveBatch → complete；RuntimeException 时按旧 token release，再抛给 Kafka 重试。模型调用没有外层数据库长事务。
6. [SourceRetrievalProjectionService.java:151–241](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionService.java#L151-L241>)：读取本批 → 上下文增强/持久化 → QA 缓存查找与本批相同输入合并 → misses embedDocuments → 校验并写缓存 → 末批独立 Note 向量调用。
7. [SnapshotEmbeddingStore.java:62–96](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SnapshotEmbeddingStore.java#L62-L96>) 按 token 独立对象路径写文件；repository.complete 短事务内校验所有权、写 COMPLETED；所有批次完成后同资料锁内生成 manifest、阶段 CAS 至 INDEXING、唯一投递索引：[SourceEmbeddingBatchRepository.java:143–182](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L143-L182>)。注意 saveManifest 对象存储回调仍发生在数据库事务中，这是已有边界，不属于此次模型移出事务的改造。
8. [KafkaTaskConsumer.java:190–205](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java#L190-L205>) → [SourceRetrievalProjectionCoordinator.java:122–158](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionCoordinator.java#L122-L158>) 校验 manifest、当前 task/version、COMPLETED/对象摘要，逐批加载；索引前后重验当前任务。 [SourceRetrievalProjectionService.java:308–369](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionService.java#L308-L369>) 校验输入完整性与 Note 末批契约；[SourceRetrievalProjectionFinalizer.java:63–124](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionFinalizer.java#L63-L124>) 双 READY 门控收尾，保留失败补偿。

### 1.2 失败与恢复

- Kafka 配置每次 poll 1 条、RECORD ACK、错误重试后 DLT 并业务收尾：[KafkaConfig.java:164–175](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaConfig.java#L164-L175>)、[KafkaConfig.java:196–244](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaConfig.java#L196-L244>)。Kafka 自身心跳不更新批次数据库租约。
- 批次恢复调度器位于 repository 自身，无另一个隐藏 scheduler：[SourceEmbeddingBatchRepository.java:192–209](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L192-L209>)，每 60 秒扫描至多 100 条过期 RUNNING，事务内 lockCurrent、按 id/token/期限 CAS 为 READY 并同事务入队；不扫描 READY、不查 activeVersion。
- 现有 lockCurrent 检查 workspace/source 归属、source PROCESSING/INDEXING、snapshot PARSED/INDEXING/指定阶段、最大 version_no、embedding_task_id（允许空时绑定）、任务类型/目标/非终态，以及没有 created_at 严格更新的同目标 SOURCE_PARSE 任务：[SourceEmbeddingBatchRepository.java:259–286](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L259-L286>)。created_at 相同的并行任务不由 newer 检查区分，主要依靠 embedding_task_id；后续恢复不要自行发明不兼容的排序规则。
- 全局扫描先 limit 再校验：无效旧快照/终态任务的最老过期 RUNNING 会一直占候选位置，达到 100 条可饿死后面的有效任务；应把当前资格纳入候选，或安全分页/隔离，不删除业务行。RUNNING 且 lease_until 为 null 也未覆盖，应统计后决定作为异常隔离还是修复候选。
- [KafkaDeadLetterTaskRecovery.java:49–61](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaDeadLetterTaskRecovery.java#L49-L61>) → [RetrievalProjectionDeadLetterFinalizer.java:84–113](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/RetrievalProjectionDeadLetterFinalizer.java#L84-L113>) → repository.acceptsFailure。已完成、仍有有效持有者、旧任务/版本不得失败收尾；[SourceEmbeddingBatchRepository.java:212–220](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L212-L220>) 已实现这一点，但守卫与 finalizeFailed 分两次事务存在竞态窗口，需在统一恢复设计里复核原子化。
- 索引失败后最多 3 次自动退避（1/2/4 分钟）：[SourceRetrievalProjectionFinalizer.java:127–165](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionFinalizer.java#L127-L165>) → [SourceIndexRetryScheduler.java:36–60](<../../../../../backend/src/main/java/com/noteweave/source/SourceIndexRetryScheduler.java#L36-L60>) → [SourceReprocessService.java:97–144](<../../../../../backend/src/main/java/com/noteweave/source/SourceReprocessService.java#L97-L144>) 新任务、清租约但保留完成批次、初始化消息。这条 scheduler 只认 FAILED，不会救仍在 PROCESSING 的 READY 批次。其候选也未显式限制最新快照，不能替代 OTH-10 的当前身份核验。
- 人工 Outbox 重驱：[TaskOutboxDispatcherService.java:184–206](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java#L184-L206>) 只复位选中消息，再 [TaskOutboxDispatcherService.java:264–288](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java#L264-L288>) 把该快照所有非 COMPLETED 批次置 READY，可能留下兄弟批次无新投递；这里也没有最新快照/当前任务/批次版本核对，应由 OTH-11 与本组统一收紧合法重驱契约。
- 已提交主 agent 的 OTH-11 交叉问题：[TaskOutboxDispatcherService.java:156–164](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java#L156-L164>) 将 finalizeFailure 的 false（可能表示应忽略的完成批次/有效租约/旧版本）全部降为 failTask，绕开批次守卫。主 agent 已确认纳入 OTH-11。不要通过加大 READY 重投频率增加该风险。

## 2. OTH-08：租约、心跳与 fencing

- 默认 7200 秒来自 [SourceEmbeddingBatchRepository.java:32–54](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L32-L54>)；[SourcePipelineProcessingBudget.java:9–43](<../../../../../backend/src/main/java/com/noteweave/source/SourcePipelineProcessingBudget.java#L9-L43>) 将 poll 下限设为两次上下文调用 + provider 子批最大 attempts × (timeout+5s) +120s，再把 lease 抬到 ceil(poll/1000)+60s。1860 秒只是默认 poll=1800000 时的最低值，不是固定上限，配置可把有效 lease 抬得高于 7200。
- claim 的 token 是 Ids.newId，每次重占都变：[SourceEmbeddingBatchRepository.java:110–127](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L110-L127>)。requireOwned 使用数据库当前行 token 和 lease_until，而非 claimed 对象冻结的期限：[SourceEmbeddingBatchRepository.java:242–251](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L242-L251>)。因此可以在不换 token 的情况下安全续租。
- 没有 renew 方法或调度句柄；coordinator 从 claim 到 complete 完全同步。上下文调用前有 requireOwned，embedding QA/Note 的发送前没有，故慢上下文后租约失效或任务取消，仍可能继续产生 embedding 费用，最后提交才拒绝。预算许可可同时补发送前资格检查。
- Outbox 有独立 renewTaskMessage：[DurableOutboxDispatcher.java:302–316](<../../../../../backend/src/main/java/com/noteweave/infra/outbox/DurableOutboxDispatcher.java#L302-L316>)；它更新 task_outbox，不更新 source_embedding_batch，且 source Kafka 发布模式已是 SENT，不应借用它冒充批次 heartbeat。
- 建议：claim 后独立受控调度续租（模型/存储阻塞期间照常），短事务统一当前身份 + token + RUNNING + lease 尚有效 CAS；采用 DB 时间统一 claim/renew/recovery/complete 判定，过期不得复活旧 token。续租失败设置 lost/cancel 标志，后续 provider permit 和 complete 均拒绝；finally 取消句柄，release 始终带 token，防止撤销新持有者。异常/线程中断/应用关闭都要清理。不要只在模型返回后续租。
- 最小安全阶段先保留现有 lease/poll 下限，补 heartbeat；若产品要求缩短故障恢复时间，再拆分消费 poll 执行上界和可续租的故障接管窗口，依据 P99 单独调参。不能仅把配置从 7200 改为 180。

## 3. OTH-09：请求的真正计数边界

### 3.1 当前事实

- 预算公式为 `(ceil(chunkCount / clamp(documentBatchSize,1,64)) + (末批?1:0)) × maxAttempts`：[SourceRetrievalProjectionService.java:140–147](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionService.java#L140-L147>)。仓储初始化写入 84–89，map 333 读回，除此无读写使用。全仓搜索 embedding_request_budget / embeddingRequestBudget 得到的生产代码与迁移均与此相符。
- context_model_calls 真实扣减在 [SourceEmbeddingBatchRepository.java:130–140](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L130-L140>)，上限 2；[SourceChunkContextEnhancer.java:195–237](<../../../../../backend/src/main/java/com/noteweave/source/SourceChunkContextEnhancer.java#L195-L237>) 只在 llm.streamChat 前回调，缓存或关闭时不调用。上下文预算耗尽降为原文，身份/租约错误传播；这些行为必须保留，不能误把它当 embedding budget 已实现。
- 客户端 [OpenAiCompatibleEmbeddingClient.java:57–64](<../../../../../backend/src/main/java/com/noteweave/retrieval/provider/OpenAiCompatibleEmbeddingClient.java#L57-L64>) 设置 SDK maxRetries=maxAttempts-1；[OpenAiCompatibleEmbeddingClient.java:79–113](<../../../../../backend/src/main/java/com/noteweave/retrieval/provider/OpenAiCompatibleEmbeddingClient.java#L79-L113>) 先按 documentBatchSize 子批，再每次 create 内部隐式重试。当前接口只返 vectors/model/dimensions/tokens，无 attempt hook：[EmbeddingClient.java:5–30](<../../../../../backend/src/main/java/com/noteweave/retrieval/provider/EmbeddingClient.java#L5-L30>)。一次 embedDocuments 可能是多个 HTTP request，每个还有多次 retry，外层计 1 必错。
- QA 缓存校验 workspace、配置版本、content/full/effective hash、model/dimensions：[SourceEmbeddingCacheRepository.java:24–55](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingCacheRepository.java#L24-L55>)。命中不发送，应不扣预算；同一批重复 miss 合并后按真实子批发送计数。Note 不查此缓存，每次末批重算都发一次。部分 provider 子批成功后后续失败，客户端未返回聚合结果，service 不会写入此前子批的缓存，重试会再请求此前成功部分，必须再次计数，不能因输入相同免扣。
- context 检查点独立 REQUIRES_NEW 提交：[SourceChunkContextEnhancer.java:60–61](<../../../../../backend/src/main/java/com/noteweave/source/SourceChunkContextEnhancer.java#L60-L61>)、[SourceChunkContextEnhancer.java:108–135](<../../../../../backend/src/main/java/com/noteweave/source/SourceChunkContextEnhancer.java#L108-L135>)；恢复不重复生成。最终批次完成对象与上下文缓存已持久化、版本摘要严格核对，均保留。

### 3.2 所有 EmbeddingClient 直接调用者及兼容边界

所有生产直接调用点已经 grep 并读取周边；下面其他业务不应被 source batch 预算或新的重试语义影响：

| 调用者 | 方法/用途 |
|---|---|
| [SourceRetrievalProjectionService.java:215–234](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionService.java#L215-L234>)、[548–551](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionService.java#L548-L551>) | 批次 QA/末批 Note；旧构造器兼容 QA/Note |
| [TopicSegmentationVectors.java:16–38](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicSegmentationVectors.java#L16-L38>) | 用户原话批量 embedding，失败词项降级 |
| [MemoryTextEmbeddingStore.java:43–64](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryTextEmbeddingStore.java#L43-L64>) | 记忆 displayText 变更才 embedDocuments |
| [MemoryConflictService.java:59–83](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConflictService.java#L59-L83>) | 冲突语义检索 embedQuery |
| [MemoryRelevanceScorer.java:19–59](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryRelevanceScorer.java#L19-L59>) | 记忆排序查询向量 |
| [MemoryIndexOutbox.java:108–124](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryIndexOutbox.java#L108-L124>) | 记忆索引 upsert |
| [KnowledgeWikiSearchEngine.java:236–254](<../../../../../backend/src/main/java/com/noteweave/knowledge/KnowledgeWikiSearchEngine.java#L236-L254>) | Wiki 检索，失败显式降级 |
| [WikiIndexOutbox.java:115–140](<../../../../../backend/src/main/java/com/noteweave/knowledge/WikiIndexOutbox.java#L115-L140>) | Wiki 索引 upsert |
| [NoteReadingRetriever.java:181–198](<../../../../../backend/src/main/java/com/noteweave/chat/NoteReadingRetriever.java#L181-L198>) | Note 阅读片段语义评分 |
| [NoteRecallRetriever.java:77–113](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRecallRetriever.java#L77-L113>) | Note 资料召回 |
| [QaHybridRetriever.java:66–108](<../../../../../backend/src/main/java/com/noteweave/chat/QaHybridRetriever.java#L66-L108>) | QA 主查询与扩展查询分别发送 |
| [RetrievalProviderHealthIndicator.java:79–98](<../../../../../backend/src/main/java/com/noteweave/retrieval/provider/RetrievalProviderHealthIndicator.java#L79-L98>) | 缓存式健康探测，不能扣某个资料批次预算 |

另外三条资料计算入口绕开 Kafka 批次账本，必须显式选择策略，不能漏接：
- [SynchronousRetrievalProjectionListener.java:31–47](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SynchronousRetrievalProjectionListener.java#L31-L47>) / [KafkaTaskConsumer.java:209–224](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java#L209-L224>) → projectAndFinalize → projectSnapshot → computingBatches。
- [RetrievalBackfillService.java:46–76](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/RetrievalBackfillService.java#L46-L76>) → projectSnapshotToIndexes → computingBatches。
- [SourceRetrievalProjectionService.java:487–538](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionService.java#L487-L538>) computingBatches / computeEmbeddings 只有内存 context 上限，每次重新生成，没有 Kafka lease。建议本轮保留普通同步/重建调用语义，若要求跨同步重试也持久限额，另定义独立 budget scope，不能伪造 SOURCE_PARSE task 或复用后台 batch owner。

### 3.3 最小方案：仅 source batch 路径可观测每次 attempt

1. 保留原 embedQuery/embedDocuments 接口语义和普通 SDK client。增加明确的预算路径（带 AttemptPermit 的重载或专用 SourceBatchEmbeddingExecutor），只由持久批次调用；接口不能用默认 no-op 回调绕过预算。
2. 预算路径使用第二个同配置、`maxRetries=0` 的 SDK client。已有 [OpenAiSdkClients.java:22–35](<../../../../../backend/src/main/java/com/noteweave/config/OpenAiSdkClients.java#L22-L35>) 支持参数，无需改共享工厂；不要全局关闭 SDK 重试。
3. 预算路径显式实现有限 attempt loop，每个 provider 子批和每次可重试失败之后再次发送前，执行 repository 短事务：当前任务/最新快照/版本/有效 token 校验 + `used < budget` 条件更新。提交后才发 HTTP，超额返回专用拒绝码，不降为未发生的成功。保留原瞬态 408/429/5xx/连接错误重试和不可重试 4xx 行为，Retry-After/退避有上限并计入 ProcessingBudget；不在网络调用期间持锁。
4. 成功、失败、超时但可能已被 provider 接受的发送均计一次；QA 缓存命中与无输入不计；Note 每次发送计；不同批次额度独立，同批 claim 重试、新 task 复用同冻结版本均不清账。预算拒绝/lease lost 不应再自动发 HTTP。
5. 如要求同一次许可重复提交幂等和逐 attempt 审计，新增 `attempt_id` 唯一账本，记录 batchId、claimToken、序号、用途、状态与受限原因码，不存正文/密钥；许可登记和 used CAS 同事务。仅对重复记账幂等，不允许用相同 attempt_id 重发 HTTP而免扣。
6. 数据库许可提交与外部 provider 请求无法原子提交。许可已提交后进程崩溃但尚未发送，保守记 RESERVED/UNKNOWN，不能声称精确等于 provider 实际收费；未获得 provider 幂等协议前不能安全退还不确定请求。产品若坚持绝对精确计费，需要 provider 回执/幂等支持，属于单独决定。
7. 预算耗尽的错误码应贯穿 Kafka DLT、finalizer、任务事件；当前 [KafkaDeadLetterTaskRecovery.java:106–109](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaDeadLetterTaskRecovery.java#L106-L109>) 只提取 RetrievalProviderException，若新异常用 BusinessException 会丢专用码。还需避免预算耗尽进入 3 次无意义自动新任务重试；人工追加预算必须可审计。

## 4. OTH-10 最小统一恢复设计

- 候选覆盖：有效当前版本的过期 RUNNING，以及超过可配置重投间隔的 READY。先过滤当前 source/snapshot/task/version/非终态和批次非 COMPLETED，再限量分页，避免旧行占满 100 槽位。
- 每候选事务内再次锁同资料/快照/任务并重验版本，锁定批次当前状态；READY 更新独立 last_enqueued_at/next_recovery_at 或 CAS 时间戳，RUNNING 仅在仍过期时清 token；入 Outbox 与状态/恢复时间同事务，失败整体回滚。
- 与 OTH-11 的语义键至少含 task、snapshot、batchVersion、batchNo、阶段。现有 message_key=`snapshot:batchNo` 不含 version：[SourceEmbeddingBatchRepository.java:311–317](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java#L311-L317>)，同任务换版本时若只套 `(task,topic,message_key)` 在途唯一会错误吞新版本。Kafka 分区键可继续稳定，但去重语义键必须分开。
- 已有 READY/PROCESSING Outbox 应复用或抑制新建；历史 SENT 不能永久抑制恢复，SENT 只证明 Kafka 接收。恢复节流不能依赖 updated_at 同时承担 heartbeat 和发送时间。
- COMPLETED 不再发送 embedding；全部完成后依旧同资料行锁 + 阶段 CAS 唯一投索引，保留 manifest/指针/摘要校验。完成批次对象不因重驱清掉，缓存与上下文账本不清零。
- 非法/过时消息应 ACK 或记录安全 reason，不能复位旧快照或 fail 当前任务。人工重驱、自动索引重试、DLT 与恢复共享当前身份验证契约，最终收尾应与守卫保持事务原子性。

## 5. 数据库约束、索引与历史

### 5.1 已有结构

- [V145 批次迁移:1–37](<../../../../../backend/src/main/resources/db/migration/V145__source_embedding_batches.sql#L1-L37>)：snapshot embedding_task_id/version；batch PK(id)、FK workspace/source/snapshot/task、UK(snapshot,version,batch_no)、普通 index(status,lease_until)。无 used/attempt ledger、无 READY 时间索引、无预算非负/上限 CHECK。
- [V146 上下文迁移:1–15](<../../../../../backend/src/main/resources/db/migration/V146__source_chunk_context.sql#L1-L15>)：片段独立 context 状态与计数，cache PK(workspace,prompt_version,input_hash)。
- [V147 向量缓存迁移:1–18](<../../../../../backend/src/main/resources/db/migration/V147__source_embedding_cache.sql#L1-L18>)：UK(workspace,embedding_version,chunker_version,context_version,content_hash)，正文摘要额外读回校验，不能缩窄作用域。
- [V002 task/outbox:40–52](<../../../../../backend/src/main/resources/db/migration/V002__create_task_and_outbox_tables.sql#L40-L52>)：仅 id PK，无语义在途唯一；[V022:1–6](<../../../../../backend/src/main/resources/db/migration/V022__harden_artifact_outbox_dispatch.sql#L1-L6>) 和 [V023:60–64](<../../../../../backend/src/main/resources/db/migration/V023__productionize_artifact_files_and_outbox.sql#L60-L64>) 增加重试、租约索引。既有 Outbox deliveryToken fencing 不可删除。
- [V129:4–10](<../../../../../backend/src/main/resources/db/migration/V129__source_pipeline_stages.sql#L4-L10>) 有 FAILED 索引重试索引，不服务 batch READY 扫描。
- 最小新增建议：batch embedding_requests_used（或保留 budget 为总额度、另记 reserved/used）、恢复发送时间索引 `(status,next_recovery_at,id)`；如逐请求审计则 attempt_id 唯一 + `(batch_id,created_at)`；统一新迁移，不改已应用迁移，主 agent 编号。上线前历史在途 budget 使用量未知，不能伪造已用 0 为真实计费；需明确迁移截点计数/隔离后人工重开方案。

### 5.2 历史证据与不能沿用的结论

- [HANDOFF.md:390–416](<../../../../../HANDOFF.md#L390-L416>) 明确 7200 未改、需 heartbeat、budget 只写、READY 不恢复；与本次源码核实一致。
- [HANDOFF.md:483–485](<../../../../../HANDOFF.md#L483-L485>) 记录批次 embed/index topic 写死已在 `359f1aa` 修复；当前仓储用 SourceTopicNames 并有自定义主题测试。此项不能重做。
- [HANDOFF.md:507](<../../../../../HANDOFF.md#L507>) 将 `8e975a6` 批次改造列为交叉审查未完成；这不是本轮三个缺陷已修的证据。
- [HANDOFF.md:195](<../../../../../HANDOFF.md#L195>) 的 `6b8aaff` heartbeat 属 artifact，不是 source embedding。不得移除，也不能混淆范围。
- 原始 REVIEW 文件没有在快照 glob 发现；未读取其外部历史路径，不臆造细节；历史测试通过数不当成本轮运行结果。

## 6. 现有测试证据与缺口

以下仅静态读取/索引，不代表本轮执行通过。

- [SourceEmbeddingBatchRepositoryTest.java:25–41](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepositoryTest.java#L25-L41>)：64/65/129 分批、初始化防重、只末次完成投索引；[45–93](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepositoryTest.java#L45-L93>)：两次 context 上限、旧 token 不能调用/提交、新 task 保留完成及账本；[96–140](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepositoryTest.java#L96-L140>)：workspace/latest/version/元数据/片段/取消任务守卫；[143–181](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepositoryTest.java#L143-L181>)：真实双线程完成防重、自定义 topic。未测 heartbeat、预算实际 attempt、长期 READY、无效旧行扫描饥饿。
- [SourceEmbeddingBatchProjectionTest.java:103–120](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchProjectionTest.java#L103-L120>) 只断预算公式 9，不断持久扣减；[175–186](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchProjectionTest.java#L175-L186>) 证明 QA 缓存后仍 Note 请求；[267–308](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchProjectionTest.java#L267-L308>) 覆盖同步/重建恢复 context，保留。
- [OpenAiCompatibleEmbeddingClientTest.java:60–113](<../../../../../backend/src/test/java/com/noteweave/retrieval/provider/OpenAiCompatibleEmbeddingClientTest.java#L60-L113>) 真本地 HTTP stub 断 503→成功=2 requests、401=1；[119–161](<../../../../../backend/src/test/java/com/noteweave/retrieval/provider/OpenAiCompatibleEmbeddingClientTest.java#L119-L161>) 分批截断。新增预算路径应在同层断 provider 接收计数与 DB 许可计数，而非 mock 业务方法调用数。
- [KafkaPipelineSettingsTest.java:62–75](<../../../../../backend/src/test/java/com/noteweave/infra/KafkaPipelineSettingsTest.java#L62-L75>) 已证明有效 lease 可为 12975 秒及溢出拒绝。
- [SnapshotEmbeddingBatchStoreTest.java:40–57](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SnapshotEmbeddingBatchStoreTest.java#L40-L57>) manifest 校验、不同 leaseToken 对象路径隔离；[SourceRetrievalProjectionCoordinatorTest.java](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionCoordinatorTest.java>) 主要覆盖 finalize 补偿，无实际 heartbeat。
- [TaskOutboxDispatcherServiceTest.java:123–170](<../../../../../backend/src/test/java/com/noteweave/infra/TaskOutboxDispatcherServiceTest.java#L123-L170>) 投递失败最终收尾且现有测试期待 fallback failTask，需要新增区分 ignored-stale 与真正失败测试，不能直接删除已有失败收尾保障；[287–335](<../../../../../backend/src/test/java/com/noteweave/infra/TaskOutboxDispatcherServiceTest.java#L287-L335>) 重驱保留完成批次，但只一个 batch fixture，没有多兄弟恢复断言。
- 关联测试文件还包括 [KafkaRetrievalProjectionFailureRecoveryTest.java](<../../../../../backend/src/test/java/com/noteweave/infra/KafkaRetrievalProjectionFailureRecoveryTest.java>)、[SourceChunkContextEnhancerTest.java](<../../../../../backend/src/test/java/com/noteweave/source/SourceChunkContextEnhancerTest.java>)、[SynchronousSourceEmbeddingContractTest.java](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SynchronousSourceEmbeddingContractTest.java>)、[SourceEmbeddingCacheRepositoryTest.java](<../../../../../backend/src/test/java/com/noteweave/retrieval/projection/SourceEmbeddingCacheRepositoryTest.java>)；本轮对这些以方法清单 grep 辅助定位，不冒称完整执行或逐行读取全部测试。

## 7. 文件责任与实施顺序

1. 本组三项同一负责人： [SourceEmbeddingBatchRepository.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java>)、[SourceRetrievalProjectionCoordinator.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionCoordinator.java>)、[SourceRetrievalProjectionService.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceRetrievalProjectionService.java>)、[SourcePipelineProcessingBudget.java](<../../../../../backend/src/main/java/com/noteweave/source/SourcePipelineProcessingBudget.java>) 及对应测试；可能新增批次 heartbeat/AttemptPermit 类。
2. 公共客户端由主 agent 登记单一负责人：[EmbeddingClient.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/provider/EmbeddingClient.java>)、[OpenAiCompatibleEmbeddingClient.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/provider/OpenAiCompatibleEmbeddingClient.java>)。不必改 [OpenAiSdkClients.java](<../../../../../backend/src/main/java/com/noteweave/config/OpenAiSdkClients.java>) 或其他普通业务调用者；如增加接口签名需兼容全部测试替身。
3. 与 OTH-11 硬冲突：[SourceEmbeddingBatchRepository.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SourceEmbeddingBatchRepository.java>)（两处 Outbox insert）、[TaskOutboxDispatcherService.java](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java>)（重驱/终态）、[SourceReprocessService.java](<../../../../../backend/src/main/java/com/noteweave/source/SourceReprocessService.java>)、[SourceParseService.java](<../../../../../backend/src/main/java/com/noteweave/source/SourceParseService.java>)、[SynchronousRetrievalProjectionListener.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/SynchronousRetrievalProjectionListener.java>)（入队/状态）、[RetrievalProjectionDeadLetterFinalizer.java](<../../../../../backend/src/main/java/com/noteweave/retrieval/projection/RetrievalProjectionDeadLetterFinalizer.java>)、[KafkaDeadLetterTaskRecovery.java](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaDeadLetterTaskRecovery.java>)（原因码和原子失败资格）。主 agent 已安排仓储先本组、后 Outbox，顺序合并，不并改。
4. 配置涉及 [application.yml](<../../../../../backend/src/main/resources/application.yml>) 和部署模板时另登记；此阶段无必要改 Compose。迁移新增文件名/编号一律由主 agent 给定。

## 8. 验收场景（实现后集中进行，当前未执行）

- 慢上下文、慢 embedding、慢对象存储跨越多个 heartbeat 仍由原 token 完成；停止 heartbeat 后租约到期新 token 接管，旧 token 的 renew/permit/complete/release 均不能影响新持有者；进程 shutdown/异常不遗留调度线程。
- 同批双线程抢占、心跳与恢复同时发生、取消/新 task/新 snapshot/新 version 与 provider 返回并发；使用实际数据库事务代理/多连接，不以同线程顺序模拟替代。
- 每个 HTTP 子批/503重试/超时/401的 DB 许可数量；缓存全命中、部分命中、同输入合并、Note 重算；预算临界时两条线程只一条获许可，拒绝前无 HTTP；外层 Kafka 重试、新 claim/new task 不能清计数；许可成功后进程终止按 UNKNOWN 保守处理。
- 原 embedQuery/embedDocuments 普通调用仍沿 SDK 重试、原 timeout/model/dimension/截断规则；source 专用路径不会使健康探测、QA、Note、记忆、Wiki或话题切分扣到资料 batch。
- READY 无消息/仅历史 SENT/已有在途消息/已有 DEAD_LETTER/两个恢复器并行；重投节流、事务失败回滚；多个失效旧 RUNNING 不饿死有效候选；旧任务、旧快照、旧版本、COMPLETED 全部不投；所有完成只发一个索引消息。
- 人工重驱一个批次时其 READY 兄弟恢复；晚到 completed/旧版本 Outbox 耗尽不 fail 当前任务；普通真正的投递耗尽仍最终 FAILED 并可合法重试。
- manifest 损坏不隐式全量重算，旧 NWE1 路径兼容；上下文降级保留原文、独立 context 检查点与 workspace 缓存隔离不变；完整双索引 READY gate 与失败补偿不变。

## 9. 提供主 agent 的只读聚合 SQL

针对当前 MySQL；仅聚合/元数据，无业务正文、payload 输出、任务/资料 ID 输出。`:embed_topic` 绑定实际 SourceTopicNames.embed 配置，不硬编码默认 topic。以下尚未由本子 agent 执行。先短事务只读；大表先 EXPLAIN，必要时离峰或只读副本。

```sql
START TRANSACTION READ ONLY;
-- 迁移与物理索引，确认实际库未偏离源码。
SELECT MAX(CASE WHEN success = 1 THEN CAST(version AS UNSIGNED) END) AS latest_version,
       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed_migrations
FROM flyway_schema_history;
SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('source_embedding_batch','task_outbox','source_snapshot','task')
GROUP BY table_name,index_name,non_unique;

-- 状态、老化、租约异常；5分钟为诊断阈值，不等于拟定恢复产品参数。
SELECT status, COUNT(*) AS rows_n,
       SUM(updated_at < CURRENT_TIMESTAMP - INTERVAL 5 MINUTE) AS older_5m,
       SUM(updated_at < CURRENT_TIMESTAMP - INTERVAL 2 HOUR) AS older_2h,
       SUM(status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP) AS expired_n,
       SUM(status='RUNNING' AND (lease_until IS NULL OR lease_token IS NULL)) AS malformed_lease_n,
       MIN(embedding_request_budget) AS min_budget, MAX(embedding_request_budget) AS max_budget,
       MAX(context_model_calls) AS max_context_calls
FROM source_embedding_batch GROUP BY status;

-- 当前有效身份统计。逻辑对齐 lockCurrent，额外核对 activeVersion；不输出标识。
WITH classified AS (
 SELECT b.status,
   b.updated_at < CURRENT_TIMESTAMP - INTERVAL 5 MINUTE AS old_ready_age,
   CASE
    WHEN s.id IS NULL OR ss.id IS NULL OR t.id IS NULL THEN 'MISSING_IDENTITY'
    WHEN s.workspace_id <> b.workspace_id OR t.workspace_id <> b.workspace_id
      OR t.task_type <> 'SOURCE_PARSE' OR t.target_type <> 'SOURCE'
      OR t.target_id <> b.source_id THEN 'IDENTITY_MISMATCH'
    WHEN s.status <> 'PROCESSING' OR s.index_status <> 'INDEXING'
      OR ss.parse_status <> 'PARSED' OR ss.index_status <> 'INDEXING'
      OR COALESCE(ss.processing_stage,'') <> 'EMBEDDING' THEN 'NOT_EMBEDDING'
    WHEN ss.version_no <> (SELECT MAX(n.version_no) FROM source_snapshot n WHERE n.source_id=s.id)
      THEN 'OLD_SNAPSHOT'
    WHEN ss.embedding_task_id IS NOT NULL AND ss.embedding_task_id <> b.task_id THEN 'OLD_TASK'
    WHEN t.task_status IN ('COMPLETED','FAILED','CANCELLED') THEN 'TERMINAL_TASK'
    WHEN EXISTS (SELECT 1 FROM task n WHERE n.workspace_id=b.workspace_id
      AND n.task_type='SOURCE_PARSE' AND n.target_type='SOURCE' AND n.target_id=b.source_id
      AND n.id<>t.id AND n.created_at>t.created_at) THEN 'NEWER_TASK_EXISTS'
    WHEN ss.embedding_batch_version IS NULL OR ss.embedding_batch_version<>b.batch_version
      THEN 'OLD_BATCH_VERSION'
    ELSE 'CURRENT_ELIGIBLE' END AS reason
 FROM source_embedding_batch b
 LEFT JOIN source s ON s.id=b.source_id
 LEFT JOIN source_snapshot ss ON ss.id=b.source_snapshot_id AND ss.source_id=b.source_id
 LEFT JOIN task t ON t.id=b.task_id
)
SELECT status,reason,COUNT(*) AS batches_n,SUM(old_ready_age) AS older_5m
FROM classified GROUP BY status,reason;

-- 重复键仅返回数量，历史重复不能直接删除；三元组不足以最终代表版本语义键。
SELECT 'ALL_HISTORY' AS scope, COUNT(*) AS duplicate_groups, COALESCE(SUM(n-1),0) AS extra_rows
FROM (SELECT COUNT(*) n FROM task_outbox GROUP BY task_id,topic,message_key HAVING COUNT(*)>1) d
UNION ALL
SELECT 'IN_FLIGHT',COUNT(*),COALESCE(SUM(n-1),0)
FROM (SELECT COUNT(*) n FROM task_outbox WHERE status IN ('READY','PROCESSING')
      GROUP BY task_id,topic,message_key HAVING COUNT(*)>1) d;
SELECT status,COUNT(*) AS rows_n,MIN(attempt_count) AS min_attempts,MAX(attempt_count) AS max_attempts
FROM task_outbox WHERE topic=:embed_topic GROUP BY status;

-- 旧版本前缀键潜在冲突：只解析身份版本字段，不返回任何载荷或正文。
SELECT COUNT(*) AS multi_version_key_groups
FROM (
 SELECT task_id,topic,message_key
 FROM task_outbox
 WHERE topic=:embed_topic AND status IN ('READY','PROCESSING') AND JSON_VALID(payload_json)
 GROUP BY task_id,topic,message_key
 HAVING COUNT(DISTINCT JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.batchVersion')))>1
) d;
COMMIT;
```

若需要判断那 1 条老 READY 是否无可用 Outbox，可在上面 CURRENT_ELIGIBLE 子查询中仅聚合 EXISTS：比较 task_id、配置 embed topic、JSON 身份字段 sourceSnapshotId/batchVersion/batchNo 和状态 READY/PROCESSING/SENT/DEAD_LETTER，不输出 payload。不能仅用 snapshot:batchNo 关联，否则旧版本会被误判为在途。当前 schema 没有 provider 实际请求计数，SQL 无法核实历史实际 embedding 消耗；只能以今后 attempt 账本或脱敏 provider 聚合计数补足。
