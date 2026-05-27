# Kafka：Task/Outbox 与异步任务

## 0. 本篇定位

这篇用于回答 NoteWeave 为什么使用 Kafka、Kafka 在 `Task + Outbox + Worker` 异步骨架中承担什么职责，以及面试里 MQ、Outbox、事务一致性、重复消费、幂等、重试、取消、消息积压和 Kafka 八股问题怎么讲。

## 1. 本主题面试官想考什么

面试官问 Kafka，通常想验证：

1. 你是否知道项目里哪些任务需要异步。
2. 你是否理解 Kafka 只是分发通道，不是业务事实源。
3. 你是否能讲清 Outbox 为什么必要。
4. 你是否能解释重复消费、幂等、任务状态机和失败重试。
5. 你是否能区分 Kafka 和 Redis 的职责。

## 2. 高频问题清单

基础问题：

- NoteWeave 为什么用 Kafka？
- Kafka 在项目里有哪些 topic？
- Kafka 和 Task/Outbox/Worker 是什么关系？

进阶问题：

- 为什么不直接在业务事务里发 Kafka？
- Outbox 投递失败怎么办？
- Worker 重复消费怎么保证幂等？

深挖追问：

- `DOCUMENT_PROCESS` 为什么路由到独立 topic？
- Kafka 消息已经发出但任务状态不是 PENDING 怎么办？
- RUNNING 任务取消为什么不是强杀？

压力追问：

- Kafka 积压怎么办？
- Kafka 是否保证消息不丢不重？
- Kafka 和 RabbitMQ/RocketMQ 怎么比较？

## 3. 问答与讲解

### Q1：NoteWeave 为什么用 Kafka？

#### 面试官为什么问

这是异步设计题。面试官想看你是否能从业务长任务讲到 MQ，而不是只背削峰解耦。

#### 回答思路

先列长任务，再讲同步 HTTP 不适合，最后讲 Kafka 负责分发，任务状态仍在 MySQL。

#### 结合 NoteWeave 怎么答

文档解析、Source 编译、Artifact 生成、Wiki 入索引、Embedding backfill、RAG Eval、Cleanup 等都走 Task/Worker。

#### 技术原理 / 链路设计讲解

Kafka 适合高吞吐、可分区、可消费组扩展的事件分发。NoteWeave 用它承接后台任务触发，但任务本身的状态、幂等和审计放在 MySQL。

#### 实现兜底锚点

- `TaskKafkaPublisher`
- `TaskKafkaConsumer`
- `DocumentProcessPublisher`
- `DocumentProcessConsumer`
- `TaskExecutionCoordinator`
- `TaskServiceIntegrationTest`
- `docker-compose.yml` 中 `noteweave.task`、`noteweave.document`、`noteweave.index`

#### 可直接复述的面试回答

NoteWeave 用 Kafka，是因为项目里有很多不适合在 HTTP 请求里同步完成的长任务，比如文档解析、切 chunk、建索引、个人 Source 编译、Artifact 生成、Wiki 入索引、RAG Eval 和资源清理。这些任务耗时长、依赖 MinIO/ES/LLM，失败概率也比普通数据库写入高。Kafka 在这里负责后台任务分发，让 Worker 可以异步消费执行；但任务状态、重试、取消、attempt、event 和审计都在 MySQL 的 Task 模型里。也就是说，Kafka 是分发通道，MySQL 才是任务事实源。

#### 常见追问

- 为什么不用线程池直接跑？
- 为什么不用 Redis Stream？
- Kafka 挂了任务会不会丢？

#### 常见坑

- 不要只回答“削峰、解耦、异步”。
- 不要说 Kafka 保存任务最终状态。

### Q2：为什么不直接在业务事务里发 Kafka？

#### 面试官为什么问

这是 Outbox 必问题。面试官想看你是否知道 DB 和 Kafka 不能本地原子提交。

#### 回答思路

讲两类不一致：DB 成功但消息没发；消息发了但 DB 回滚。Outbox 把“待发送消息”落成 DB 事实。

#### 结合 NoteWeave 怎么答

业务服务创建 Task 时，同一个事务里写 Task、TaskEvent 和 TaskOutbox；`TaskOutboxDispatchScheduler` 后续投递；`TaskOutboxRoutingPublisher` 根据 TaskType 路由到 task topic 或 document topic。

#### 技术原理 / 链路设计讲解

Outbox Pattern 是最终一致方案。它不保证不重复，因此消费者仍要幂等；但它让投递失败可见、可重试、可审计。

#### 实现兜底锚点

- `TaskOutboxService.createTaskCreatedOutbox`
- `TaskOutboxDispatchScheduler`
- `TaskOutboxRoutingPublisher`
- `TaskOutboxStatus`
- `TaskServiceIntegrationTest`

#### 可直接复述的面试回答

我不直接在业务事务里发 Kafka，是因为数据库事务和 Kafka 发送不是一个本地原子操作。最典型的问题是：数据库提交成功但 Kafka 发送失败，任务就没人执行；或者 Kafka 已经发出去了，但数据库事务回滚，Worker 消费到一个不存在或状态不完整的任务。NoteWeave 用 Outbox 解决这个问题：业务事务里先写 Task、TaskEvent 和 TaskOutbox，提交后由 dispatcher 投递 Kafka。投递失败时 outbox 还在，可以重试；投递成功后也只是表示消息进入 Kafka，任务是否执行成功仍由 Worker 回写 Task 状态。

#### 常见追问

- Outbox 重复投递怎么办？
- 如何保证消息顺序？
- Kafka 成功但 Worker 失败怎么办？

#### 常见坑

- 不要说 Outbox 是强一致方案。
- 不要说消息发送成功等于业务完成。

### Q3：Worker 重复消费怎么保证幂等？

#### 面试官为什么问

这是 MQ 核心八股。Kafka 默认就可能重复消费，面试官一定会追。

#### 回答思路

讲 Kafka 可能重复，NoteWeave 靠 MySQL task 状态机、行锁、幂等键和业务约束处理。

#### 结合 NoteWeave 怎么答

`TaskKafkaConsumer` 解析消息后调用 `TaskExecutionCoordinator.consume`。Coordinator 通过 `claimTask` 查询并锁定 task，只有 `PENDING` 才能变 `RUNNING`。

#### 技术原理 / 链路设计讲解

消费者幂等通常靠“消息唯一键 + 状态机 + 去重表/业务唯一约束”。NoteWeave 的 taskId 和 idempotencyKey 提供稳定标识，状态机挡住重复执行。

#### 实现兜底锚点

- `TaskKafkaConsumer.consume`
- `TaskExecutionCoordinator.claimTask`
- `TaskRepository.findByIdForUpdate`
- `TaskStatus`
- `TaskAttempt`

#### 可直接复述的面试回答

Kafka 消息可能重复，所以 NoteWeave 不把“消费到消息”当作可以直接执行业务副作用的依据。Worker 收到消息后会解析出 taskId，然后回查 MySQL，使用行锁 claim 任务。只有 `PENDING` 状态能转成 `RUNNING`，并创建 `TaskAttempt`；如果任务已经是 `RUNNING`、`SUCCESS`、`FAILED` 或 `CANCELLED`，重复消息会被跳过。创建任务时还有 idempotencyKey，具体业务落库也要靠唯一约束或状态检查。也就是说，幂等是在消费端和业务状态机里做的，不是单纯依赖 Kafka。

#### 常见追问

- Worker 执行成功但提交 offset 前崩了怎么办？
- 业务副作用已经写入，再次消费怎么办？
- exactly-once 能不能解决？

#### 常见坑

- 不要说 Kafka 保证不会重复。
- 不要只靠 offset 提交来解释业务幂等。

### Q4：`DOCUMENT_PROCESS` 为什么单独路由？

#### 面试官为什么问

这是 topic 设计和任务隔离题。

#### 回答思路

讲不同任务耗时、依赖和扩容方式不同，文档处理可以独立 topic 和 consumer group。

#### 结合 NoteWeave 怎么答

`TaskOutboxRoutingPublisher` 发现 `TaskType.DOCUMENT_PROCESS` 时，会转换成 `DocumentProcessTaskPayload` 并投递到 document topic。其他通用任务走 task topic。

#### 技术原理 / 链路设计讲解

独立 topic 能让文档解析、索引这类重任务和普通任务隔离，便于独立扩容、监控、限流和排障。分区数影响并行度，consumer group 影响消费扩展。

#### 实现兜底锚点

- `TaskOutboxRoutingPublisher`
- `DocumentProcessPublisher`
- `DocumentProcessConsumer`
- `DocumentProcessingService`
- `docker-compose.yml` 中 `noteweave.document`

#### 可直接复述的面试回答

`DOCUMENT_PROCESS` 单独路由，是因为文档处理和普通后台任务的特征不一样。文档解析、切 chunk、写 ES、补 embedding 依赖 MinIO、Tika、ES 和 embedding，耗时和失败模式都比较特殊。NoteWeave 通过 `TaskOutboxRoutingPublisher` 把 `DOCUMENT_PROCESS` 转成 `DocumentProcessTaskPayload`，投递到 document topic，其他任务走通用 task topic。这样未来可以给文档处理单独扩容 worker、单独监控 lag、单独限流，不会和 Artifact 生成、Eval、Cleanup 这类任务互相挤占。

#### 常见追问

- topic 越多越好吗？
- partition 数怎么定？
- document worker 失败怎么重试？

#### 常见坑

- 不要说每种任务都必须单独一个 topic。
- 不要忽略统一 Task 状态仍在 MySQL。

### Q5：Kafka 常见八股怎么结合项目讲？

#### 面试官为什么问

面试官可能从项目题切到 MQ 基础。

#### 回答思路

把 partition、consumer group、offset、ack、积压、顺序性、重试和死信落到 NoteWeave 场景。

#### 结合 NoteWeave 怎么答

- partition：决定同一 topic 的并行处理能力。
- consumer group：多个 Worker 实例共享消费。
- offset：消费进度，不等于业务成功。
- key：可用 taskId 保证同一任务路由稳定。
- 积压：看 Kafka lag、Task PENDING/RUNNING 数、Worker 健康。
- 重试：通过 Task retry / Outbox retry / Admin retry，不只靠 MQ 自动重试。

#### 可直接复述的面试回答

Kafka 八股我会结合 NoteWeave 的任务系统讲。partition 决定后台任务并行度，consumer group 让多个 Worker 实例分摊任务；offset 只是 Kafka 消费进度，不等于业务执行成功，所以 Worker 还要回写 Task 状态。消息 key 可以用 taskId 这类稳定标识，保证同一任务路由可控。出现积压时，我会同时看 Kafka lag、Task PENDING/RUNNING 数、TaskAttempt 错误、Worker 健康和下游 MinIO/ES/LLM 是否慢。重试也不是只靠 MQ，而是 Task retry、Outbox retry 和 Admin retry 共同完成。

#### 常见追问

- Kafka 如何保证顺序？
- 消息丢失怎么避免？
- 为什么不用 RabbitMQ？

#### 常见坑

- 不要把 offset 提交说成业务成功。
- 不要说 Kafka 天然 exactly-once 覆盖所有业务副作用。

### Q6：Kafka 挂了或积压怎么办？

#### 面试官为什么问

这是运维和降级题。

#### 回答思路

Kafka 挂了影响后台任务分发，但业务事务里的 Task/Outbox 仍在 MySQL，可等 Kafka 恢复后补偿投递。积压要看 Worker、下游依赖和任务类型。

#### 结合 NoteWeave 怎么答

`SystemHealthService.checkKafka()` 用 AdminClient 检查 topic 和 partition；Outbox 失败会保留状态；Admin/Ops 可查看任务、重试和健康。

#### 可直接复述的面试回答

Kafka 挂了会影响后台任务分发，比如文档解析、索引、Artifact 生成、Eval 和 Cleanup 不能及时被 Worker 消费。但 NoteWeave 的业务事务里已经写了 Task 和 TaskOutbox，所以消息没有成功发出时还有补偿依据，等 Kafka 恢复后可以继续投递。积压时不能只看 Kafka，要同时看 Task 状态分布、Worker 数量、失败率、下游 MinIO/ES/LLM 延迟和具体任务类型。如果是文档处理积压，可以独立扩容 document worker；如果是 LLM 慢，扩 Kafka 没用，要看 provider 超时、限流和任务并发。

#### 常见追问

- 积压时先扩 partition 还是 worker？
- 如何避免慢任务拖垮快任务？
- 是否需要死信队列？

#### 常见坑

- 不要说 Kafka 挂了任务事实就丢了。
- 不要把所有积压都归因于 Kafka 本身。

## 4. 边界和不能说满的地方

- 可以坚定讲：`Task + Outbox + Kafka + Worker` 是统一异步底座，目标是最终一致、可补偿和消费侧幂等。
- 不要讲成：Outbox 保证绝不重复；Kafka 成功就等于业务成功；Kafka 的 exactly-once 能覆盖全部业务副作用；Redis 是主任务队列。
