# Kafka：Task Outbox 与异步 Worker

> 本文件为 2026-06-01 加深版。Kafka 在 NoteWeave 里是任务触发和削峰通道，业务事实、幂等和最终状态仍由 MySQL Task 表和业务表承担。

## 0. 一句话定位

Kafka 负责把长任务从同步接口中解耦出来。Outbox 负责“DB 事实已提交但消息可重试投递”，Worker 负责“拿 taskId 回查事实并执行”，不是 Kafka 自己保证业务 exactly-once。

## 1. 当前真实设计

### Topic 和 Consumer Group

配置来源：
- `noteweave.kafka.topics.task` -> 默认 `noteweave.task`
- `noteweave.kafka.topics.document-process` -> 默认 `noteweave.document`
- `noteweave.kafka.consumer-groups.task` -> 默认 `noteweave-task-worker`
- `noteweave.kafka.consumer-groups.document-process` -> 默认 `noteweave-document-process-worker`

docker-compose：
- `noteweave.task`、`noteweave.document`、`noteweave.index` 创建 3 partitions、replication-factor 1。
- 当前代码主用 task 和 document 两个 topic；`noteweave.index` 是环境初始化里的预留 topic，不要夸成主链路已用。

### Outbox 调度

代码锚点：
- `TaskService`
- `TaskOutboxService`
- `TaskDispatcher`
- `TaskOutboxRoutingPublisher`

流程：
1. 业务事务写业务对象、`task`、`task_outbox`。
2. `TaskDispatcher` 扫描 PENDING 或到期 FAILED outbox。
3. 投递成功后 outbox 标记 SENT；失败后标记 FAILED、retry_count + 1、next_retry_at + 30 秒。
4. 每次投递和失败都写 `task_event`。

### 路由设计

代码锚点：`TaskOutboxRoutingPublisher`

- DOCUMENT_PROCESS 单独投到 `noteweave.document`，由 `DocumentProcessConsumer` 消费。
- 其他任务投到 `noteweave.task`，由 `TaskKafkaConsumer` 消费并交给 `TaskExecutionCoordinator`。

为什么这样讲：
- 文档解析可能更重，单独 topic/group 方便后续独立扩容和限流。
- 其他任务统一走 task topic，复用 worker registry 和状态机。

### Worker 执行

代码锚点：`TaskExecutionCoordinator`

- Consumer 只解析 message，真正执行前用 taskId `findByIdForUpdate` 抢占任务。
- 只有 PENDING 任务会切 RUNNING。
- 执行结果写回 SUCCESS/FAILED/TIMEOUT/CANCELLED，并更新 attempt、event、output、resultRef。
- Artifact 任务失败后会调用 ArtifactPersistenceService 做状态 reconcile。

## 2. 高频深问与答法

### Q1: 为什么不直接同步执行？

答：上传解析、Source 编译、Artifact 生成、Wiki 入索引、RAG Eval、cleanup 都可能耗时或依赖外部组件。同步执行会导致接口超时，也很难统一重试、取消和进度。Task/Outbox/Kafka/Worker 把用户请求变成可查询任务，让前端和 Admin 都能看到状态。

### Q2: Outbox 解决什么问题？

答：解决“业务状态提交”和“消息投递”之间的跨系统不一致。业务事务里写 Task 和 Outbox，事务提交后再投 Kafka。如果 Kafka 短暂不可用，Outbox 仍在 MySQL，可由调度器重试。它保证的是最终一致，不是分布式强一致。

### Q3: Kafka 重复消费怎么办？

答：Kafka 可能重复投递，所以 Worker 不能直接做副作用。TaskExecutionCoordinator 先锁 Task，只有 PENDING 才执行；attempt_no、task_status、业务唯一约束、indexVersion、artifactVersion、runId 等共同保证幂等。重复消息看到非 PENDING 状态就直接忽略。

### Q4: Kafka 消息丢了怎么办？

答：严格说不能只依赖 Kafka。NoteWeave 有 task_outbox 作为消息事实：如果投递没成功，outbox 不是 SENT，会被重新扫描；如果投递成功但 Worker 失败，Task/Attempt/Event 记录失败，可由 Admin 重试。排障时看 outbox status、TaskEvent、Consumer 日志和业务对象状态。

### Q5: 为什么 DOCUMENT_PROCESS 单独 topic？

答：文档解析和索引是高 IO/CPU 链路，和 Artifact、Wiki、RAG Eval 这类任务的资源模型不同。单独 topic/group 让后续可以独立扩容文档 worker，避免大文件解析把所有任务通道堵住。

## 3. 中间件替换怎么答

- 换 RabbitMQ：Task/Outbox/Worker 抽象基本能保留，发布和消费实现要换，重试/死信策略要重新设计。
- 换 Redis Stream：要重新解释可靠投递、消费者组、消息保留和 Admin 排障，不适合把 Redis 同时当 runtime 和主任务流。
- 拆微服务：先拆 Document Worker、RAG/LLM Worker、Artifact Worker、Eval/Ops Worker，而不是一开始按表拆服务。

## 4. 不能说满

- 不要说 Kafka 保证业务 exactly-once。
- 不要说 Outbox 是强一致分布式事务。
- 不要说 `noteweave.index` 当前已经是主链路。
- 不要说所有失败都能自动恢复；有些要 Admin 介入。
