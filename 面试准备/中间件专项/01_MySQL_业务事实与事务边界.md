# MySQL：业务事实与事务边界

## 0. 本篇定位

这篇用于回答 NoteWeave 为什么用 MySQL、MySQL 在项目里存什么、它和 Kafka/Redis/ES/MinIO 如何分工，以及面试里常见的事务、索引、锁、状态机、幂等和数据一致性问题。

## 1. 本主题面试官想考什么

面试官问 MySQL，通常不是想听“关系型数据库支持事务”这么简单，而是想验证：

1. 你是否知道哪些数据必须是业务事实。
2. 你是否理解 MySQL 与 Redis、ES、MinIO、Kafka 的边界。
3. 你是否能讲清事务、唯一约束、行锁、状态机和 Outbox 的作用。
4. 你是否知道索引、分页、归档和未来分库分表该怎么回答。

## 2. 高频问题清单

基础问题：

- NoteWeave 里 MySQL 存了哪些数据？
- 为什么不用 ES 或 Redis 直接当主库？
- Flyway 在项目里解决什么问题？

进阶问题：

- Task/Outbox 为什么要和业务数据在同一个事务里写？
- Worker 重复消费时 MySQL 怎么帮忙保证幂等？
- Citation 为什么要关系化存储？

深挖追问：

- `findByIdForUpdate` 这类行锁解决什么问题？
- 如果文档 soft delete 了，MySQL 和 ES 怎么同步可见性？
- 数据量变大后哪些表最可能先膨胀？

压力追问：

- 当前有没有分库分表？
- 如何设计索引？
- 没有生产数据量怎么回答容量问题？

## 3. 问答与讲解

### Q1：NoteWeave 里 MySQL 承担什么职责？

#### 面试官为什么问

这是中间件边界题。面试官想看你是否知道 MySQL 是业务事实源，而不是只会说“存数据”。

#### 回答思路

先说 MySQL 保存长期业务事实，再按模块举例：用户、空间、权限、任务、文档元数据、Citation、Artifact、Memory、Eval、Admin/Ops。

#### 结合 NoteWeave 怎么答

NoteWeave 把正式状态都放在 MySQL：`users`、`space`、`task`、`task_outbox`、`document`、`document_chunk`、`citation`、`artifact`、`memory_item`、`rag_eval_run`、`system_health_snapshot` 等。Redis、ES、MinIO、Kafka 都不替代 MySQL 的业务事实位置。

#### 技术原理 / 链路设计讲解

MySQL 适合保存结构化状态和关系：权限关系、任务状态机、引用关系、版本关系、审计记录。它提供事务、唯一约束、行锁和可查询关系模型，适合承载“系统到底发生了什么”的事实。ES 是检索索引，Redis 是短期状态，MinIO 是对象内容，Kafka 是事件分发，都不是最终业务事实源。

#### 实现兜底锚点

- `src/main/resources/db/migration/V1__...` 到 `V18__...`
- `TaskRepository` / `TaskOutboxRepository`
- `DocumentRepository` / `DocumentChunkRepository`
- `CitationRepository`
- `ArtifactRepository` / `ArtifactVersionRepository`
- `MemoryItemRepository`
- `SystemHealthSnapshotRepository`
- `SystemHealthService.checkMysql()`

#### 可直接复述的面试回答

MySQL 在 NoteWeave 里是业务事实源。像用户、空间、成员权限、任务状态、Outbox、文档元数据、chunk 元数据、Citation、Artifact 版本、个人卡片、Memory、Eval 和 Admin/Ops 记录，都落在 MySQL。其他中间件各有职责：Redis 存短期运行态，MinIO 存文件对象，Elasticsearch 存检索索引，Kafka 做后台任务分发。但一旦要回答“某个资源是否存在、归谁所有、当前状态是什么、是否有权限访问、任务是否成功、证据指向哪里”，最终都要回到 MySQL 的业务表。

#### 常见追问

- 为什么 Citation 不直接存在 JSON 里？
- ES 里也有 chunk，为什么还要 MySQL 的 `document_chunk`？
- Redis 能不能存任务状态？

#### 常见坑

- 不要说 ES 或 Redis 是主业务事实源。
- 不要说 MinIO 对象本身代表用户有权限访问。

### Q2：Task/Outbox 为什么要写在 MySQL 里？

#### 面试官为什么问

这是事务一致性题。面试官会追问直接发 Kafka 会发生什么。

#### 回答思路

讲业务状态和待发送事件必须在同一个事务里形成事实，避免 DB 成功但消息丢失，或消息发出但事务回滚。

#### 结合 NoteWeave 怎么答

上传 merge 成功后，会创建 `Document`、`Task`、`TaskEvent`、`TaskOutbox`，后续由 dispatcher 投递 Kafka。Artifact、Source、Wiki、Eval、Cleanup 等长任务也复用同一套模式。

#### 技术原理 / 链路设计讲解

Outbox Pattern 的本质是把“需要发消息”也变成数据库事实。业务事务提交后，后台再投递 Kafka。投递失败可以重试，投递成功后修改 outbox 状态。Kafka 可能重复投递，所以 Worker 还要回查 MySQL 任务状态和幂等键。

#### 实现兜底锚点

- `TaskService`
- `TaskOutboxService.createTaskCreatedOutbox`
- `TaskOutboxDispatchScheduler`
- `TaskExecutionCoordinator.claimTask`
- `TaskServiceIntegrationTest`

#### 可直接复述的面试回答

NoteWeave 的 Task 和 Outbox 放在 MySQL，是为了把业务状态和待发送消息放进同一个事务里。比如文档 merge 后，系统不能只创建 Document 然后直接发 Kafka，因为可能出现 DB 成功但 Kafka 发送失败，也可能 Kafka 先发出但事务回滚。Outbox 的做法是：事务里先写 Task、TaskEvent 和 TaskOutbox，等事务提交后由 dispatcher 投递 Kafka。投递失败时 outbox 仍然可补偿；投递成功也不代表任务一定执行成功，Worker 还要回查 MySQL 状态并 claim 任务。这样链路是最终一致，而不是靠一次直接发消息赌成功。

#### 常见追问

- Outbox 会不会重复发？
- Kafka 消息发到了但 Worker 重复消费怎么办？
- 为什么还需要 `task_attempt` 和 `task_event`？

#### 常见坑

- 不要说 Outbox 保证绝对不重复。
- 不要说 Kafka 成功就等于业务成功。

### Q3：MySQL 在 Worker 幂等里起什么作用？

#### 面试官为什么问

这是重复消费和并发控制题。

#### 回答思路

讲 Worker 只把 Kafka 消息当触发信号，执行前回查 MySQL，只有 `PENDING` 能 claim 成 `RUNNING`。

#### 结合 NoteWeave 怎么答

`TaskExecutionCoordinator.claimTask` 通过 `findByIdForUpdate` 拿任务并检查状态。如果任务不再是 `PENDING`，重复消息会被跳过。

#### 技术原理 / 链路设计讲解

数据库行锁可以避免两个 Worker 同时把同一个任务 claim 成运行态。状态机本身也是幂等边界：`PENDING -> RUNNING -> SUCCESS/FAILED/CANCELLED/TIMEOUT`。真正业务副作用还要结合唯一约束、版本号或业务状态检查。

#### 实现兜底锚点

- `TaskExecutionCoordinator.claimTask`
- `TaskRepository.findByIdForUpdate`
- `TaskStatus`
- `TaskAttempt`

#### 可直接复述的面试回答

Kafka 消息在 NoteWeave 里只是触发信号，Worker 不直接相信消息体。消费时会先用 taskId 回查 MySQL，并通过行锁 claim 任务，只有 `PENDING` 状态才能转成 `RUNNING`，同时创建 `TaskAttempt`。如果同一条消息被重复消费，或者 outbox 重复投递，第二次进来发现任务已经不是 `PENDING`，就会直接跳过。也就是说，幂等不是只靠 MQ，而是靠 MySQL 状态机、行锁、幂等键和具体业务表约束一起完成。

#### 常见追问

- 如果 Worker 执行到一半宕机怎么办？
- 业务副作用已经写了一半怎么办？
- `cancel_requested` 如何配合 MySQL 状态？

#### 常见坑

- 不要说 Kafka exactly-once 就能解决业务幂等。
- 不要忽略业务侧唯一约束和状态检查。

### Q4：MySQL 八股怎么结合项目讲？

#### 面试官为什么问

面试官可能从项目题切到数据库基础，比如事务、索引、锁、MVCC。

#### 回答思路

每个八股点都要落回 NoteWeave 的真实场景。

#### 结合 NoteWeave 怎么答

- 事务：创建业务记录、Task、TaskOutbox 必须一起提交。
- 行锁：Worker claim task 时避免并发执行。
- 唯一约束：任务幂等键、关联关系去重。
- 索引：spaceId、userId、status、taskType、documentId、citationId 等查询维度。
- MVCC：普通读不阻塞写，但关键状态迁移要加锁。

#### 可直接复述的面试回答

如果面试官问 MySQL 八股，我会尽量结合 NoteWeave 场景讲。事务对应的是业务记录和 Task/Outbox 同时写入；行锁对应的是 Worker claim 任务，防止重复执行；唯一约束对应幂等键和关系去重；索引对应列表查询、权限过滤、任务状态筛选和 citation 回溯；MVCC 则解释为什么普通查询可以读快照，但状态迁移这种关键路径必须用 `for update` 或等价机制保证并发安全。这样回答比单独背概念更像真实项目经验。

#### 常见追问

- 为什么不是所有查询都加锁？
- 索引建太多有什么代价？
- 大表怎么治理？

#### 常见坑

- 不要泛泛背八股，必须挂到任务、权限、文档、Citation、Memory 这些表。

### Q5：当前没有分库分表怎么回答？

#### 面试官为什么问

这是压力题，考你是否会为了显得高级而乱吹。

#### 回答思路

明确当前没有分库分表，再说明为什么现在不需要，以及未来如何演进。

#### 结合 NoteWeave 怎么答

当前是本地验收的工程工作台，没有生产规模证据证明必须分库分表。未来最可能膨胀的是 `citation`、`retrieval_trace`、`llm_call_log`、`task_event`、`document_chunk` 这类表。

#### 可直接复述的面试回答

当前 NoteWeave 没有做分库分表，我不会把它说成已落地能力。原因是现阶段没有真实生产数据规模证明必须拆。现在更合理的是先做好空间隔离字段、索引、分页、归档、保留期和后台清理。如果未来 citation、retrieval trace、LLM log、task event 或 document chunk 这些表变大，我会先看查询模式和增长速度，优先做冷热归档、按时间或 space 维度分区、ES 检索分担查询压力，最后才考虑分库分表和分片键。

#### 常见追问

- 如果按 spaceId 分片会有什么问题？
- trace 表按时间分区是否更合适？
- 分库后跨空间 Admin 查询怎么办？

#### 常见坑

- 不要说当前已经做了分库分表。
- 不要为了回答高并发题硬套电商订单模型。
