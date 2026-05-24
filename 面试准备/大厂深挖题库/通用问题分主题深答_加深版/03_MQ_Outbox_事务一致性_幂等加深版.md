# 文件：03_MQ_Outbox_事务一致性_幂等加深版.md

## 1. 这个主题要回答什么

这个主题对应后端面试高频八股：

- 为什么要异步？
- Kafka 在项目里怎么用？
- 什么是事务消息？
- 什么是 Outbox Pattern？
- 消费者重复消费怎么办？
- 幂等怎么做？
- 任务取消怎么做？
- 最终一致性怎么保证？

## 2. 面向初学者：MQ 是什么

MQ 是 Message Queue，消息队列。它的核心作用是把“发送方”和“消费方”解耦。

不用 MQ：

```text
上传接口 -> 直接解析文档 -> 写 ES -> 返回结果
```

用了 MQ：

```text
上传接口 -> 创建任务 -> 发消息 -> 立即返回 taskId
Worker -> 消费消息 -> 慢慢解析文档
```

MQ 常见作用：

- `异步`：耗时操作放后台。
- `削峰`：高峰请求先进入队列，Worker 慢慢处理。
- `解耦`：业务服务不关心具体哪个 Worker 执行。
- `重试`：消费失败可以重新消费。

但 MQ 也带来问题：

- 消息可能重复。
- 消息可能延迟。
- 消息发送和 DB 事务不一致。
- 消费失败要处理。

## 3. NoteWeave 为什么需要 MQ

NoteWeave 有很多长任务：

- 文档解析。
- 文档切片。
- ES 索引。
- Embedding backfill。
- Source 导入。
- Source 编译。
- Artifact 生成。
- Wiki 入索引。
- RAG Eval。
- 资源清理。

这些任务共同特点：

- 执行时间长。
- 依赖外部系统，如 MinIO、ES、LLM。
- 可能失败，需要重试。
- 用户不应该一直等 HTTP 请求。

所以 NoteWeave 抽象出统一任务模型：

```text
Task
TaskAttempt
TaskEvent
TaskOutbox
Kafka
Worker
```

## 4. 完整链路深讲

以文档解析为例：

```text
1. 用户完成 merge
2. 创建 Document(PENDING_PROCESS)
3. TaskService 创建 DOCUMENT_PROCESS Task
4. TaskEvent 写 TASK_CREATED
5. TaskOutbox 写待发送消息
6. DB 事务提交
7. TaskDispatcher 扫描 outbox
8. 发布 Kafka 消息
9. outbox 标记 SENT
10. Worker 消费消息
11. Worker 回查 Task
12. PENDING -> RUNNING
13. 创建 TaskAttempt
14. 解析文档、切片、写 ES
15. 更新 Document activeIndexVersion
16. Task -> SUCCESS
17. TaskEvent 写 TASK_SUCCEEDED
```

这里最关键的是：Kafka 消息不是事实源，MySQL 里的 Task 状态才是事实源。

## 5. 八股知识点：事务消息是什么

### 5.1 问题背景

业务系统经常需要同时做两件事：

```text
写数据库
发 MQ 消息
```

但数据库和 MQ 是两个不同系统，不在同一个本地事务里。

会出现两个问题：

场景一：

```text
DB 写成功
MQ 发送失败
```

结果：业务数据存在，但没人处理后续任务。

场景二：

```text
MQ 发送成功
DB 回滚
```

结果：消费者收到消息，但查不到业务数据。

### 5.2 常见解决方案

1. 本地事务 + MQ 事务消息。
2. Outbox Pattern。
3. 可靠事件表 + 定时扫描。
4. TCC / Saga。
5. 最终一致补偿。

NoteWeave 使用的是 Outbox Pattern。

## 6. Outbox Pattern 是什么

Outbox Pattern 的核心思想：

```text
业务数据和待发送消息，先写到同一个数据库事务里。
事务提交后，再由后台程序把 outbox 消息发送到 MQ。
```

在 NoteWeave 中：

```text
Task 表：业务任务状态
TaskOutbox 表：待发送的任务事件
TaskDispatcher：扫描 outbox 并发送 Kafka
Kafka：分发给 Worker
```

优点：

- DB 事务内能保证 Task 和 Outbox 一起提交。
- Kafka 失败可以补偿发送。
- 发送状态可观察。

缺点：

- 消息可能重复发送。
- 需要 dispatcher。
- 消费者必须幂等。

## 7. 八股知识点：幂等是什么

幂等指同一个操作执行一次和执行多次，结果一致。

例如：

```text
set status = SUCCESS
```

天然接近幂等。

但：

```text
余额 = 余额 - 100
refCount = refCount + 1
新增一条记录
```

重复执行就会出问题。

NoteWeave 里的幂等分三层：

### 7.1 请求幂等

用 `idempotencyKey` 防止重复创建 Task。

例如：

```text
DOCUMENT_PROCESS:documentId:version
SOURCE_COMPILE:sourceId:attempt
ARTIFACT_GENERATE:artifactId:attempt
```

如果重复请求进来，TaskService 会先查 idempotencyKey，已有就返回旧 Task。

### 7.2 消息幂等

Worker 消费消息后回查 Task：

```text
if task.status != PENDING:
    skip
else:
    PENDING -> RUNNING
```

这样 Kafka 重复投递不会重复执行业务。

### 7.3 业务幂等

业务内部也要防重复。

例子：

- 文档索引用 activeIndexVersion。
- Artifact 生成用 ArtifactVersion。
- Distillation 用 proposalId 和 artifactVersionId。
- upload merge 已生成 documentId/taskId 时直接返回。

## 8. 八股知识点：消费者如何设计

一个合格消费者要考虑：

- 反序列化失败。
- 消息重复。
- 消息乱序。
- 业务数据不存在。
- 业务状态不允许执行。
- 执行失败重试。
- 死信或人工处理。

NoteWeave 的消费者设计原则：

```text
只信 taskId
回查 DB
状态机 claim
记录 attempt
失败写 event
必要时 retry
```

这样比直接信 Kafka payload 更安全。

## 9. 八股知识点：最终一致性

强一致是指操作完成后，所有系统立刻一致。

最终一致是指短时间内可能不一致，但经过重试和补偿后会收敛。

NoteWeave 的任务链路就是最终一致：

```text
Document 已创建
Task 已创建
Kafka 可能还没发送
Worker 可能还没处理
ES 可能还没索引
```

用户看到的是任务状态，而不是假装立即完成。

## 10. 为什么取消不能强杀

RUNNING 任务可能正在：

- 读 MinIO。
- 写 parsed text。
- 写 DocumentChunk。
- bulk index ES。
- 调 LLM。
- 写 ArtifactVersion。

强杀可能导致半写入。

所以 NoteWeave 使用：

```text
cancelRequested = true
Worker 在安全点检查
```

安全点包括：

- skill 执行前。
- 批处理阶段之间。
- 外部副作用前。
- 写最终状态前。

## 11. 底层原理补充：Kafka 和消息可靠性

### 11.1 Kafka 的基本结构

Kafka 里几个核心概念：

- Topic：消息主题，比如 `noteweave.document`。
- Partition：topic 的分区，用于并行和扩展。
- Producer：生产者，发送消息。
- Consumer：消费者，读取消息。
- Consumer Group：消费者组，同一组内多个消费者分摊 partition。
- Offset：消费者读到 partition 的哪个位置。

Kafka 的并行能力主要来自 partition。一个 partition 同一时间只能被同一个 consumer group 内的一个 consumer 消费，所以想提升并发，通常要增加 partition 和 consumer 数。

### 11.2 至少一次、至多一次、恰好一次

消息语义常见三种：

- 至多一次：可能丢，但不重复。
- 至少一次：不丢，但可能重复。
- 恰好一次：看起来不丢不重，但实现成本高，通常需要严格条件。

业务系统里最常用的是“至少一次 + 幂等消费”。NoteWeave 也是这个思路：允许 Kafka 或 outbox 重复通知，但 Worker 回查 Task 状态保证不重复执行。

### 11.3 Offset 和重复消费

消费者处理消息后提交 offset。如果处理成功但提交 offset 前挂了，消息会被再次消费。所以消费者必须能处理重复消息。

NoteWeave 的处理方式：

```text
Kafka message -> taskId -> DB Task status -> 是否执行
```

这比依赖 Kafka “不重复”更可靠。

### 11.4 顺序性问题

Kafka 只保证同一个 partition 内有序，不保证全局有序。NoteWeave 不依赖消息全局顺序，而是通过 DB 任务状态决定是否能执行。

## 12. 可直接复述的深答

NoteWeave 里 MQ 不是为了炫技，而是因为文档解析、Source 编译、Artifact 生成、Wiki 入索引、Eval 和 cleanup 都是长任务。同步 HTTP 做这些会导致请求耗时不可控，也不利于失败重试和进度展示。所以我把这些任务统一抽象成 Task。业务服务创建 Task 时，同一个 MySQL 事务里会写 Task、TaskEvent 和 TaskOutbox；Kafka 发送由 TaskDispatcher 后续补偿执行。这就是 Outbox Pattern，用来解决 DB 提交和 MQ 发送之间的不一致。Worker 消费 Kafka 后只把消息当通知，会用 taskId 回查 DB，只有 PENDING 任务才能转 RUNNING，并创建 TaskAttempt。重复消费时，因为 Task 已经不是 PENDING，就会跳过。幂等上，创建任务靠 idempotencyKey，消费任务靠状态机，业务落库靠版本号或唯一约束。取消上，RUNNING 任务不会强杀，而是设置 cancelRequested，让 Worker 在安全点停止，避免 MinIO、ES、MySQL、LLM 出现半写入。

## 13. 面试官可能继续追问

### 13.1 Outbox 会不会重复发消息

会。比如 Kafka 发成功但 outbox 标记 SENT 失败，dispatcher 可能再次发送。所以消费者必须幂等。Outbox 解决的是可靠投递，不解决“绝对不重复”。

### 13.2 Kafka 为什么适合这个项目

Kafka 适合任务分发、消费组、水平扩展和高吞吐。NoteWeave 任务类型多，后续可以按 topic 或 consumer group 扩展 Worker。

### 13.3 Redis Stream 能不能替代 Kafka

技术上可以做部分队列，但项目里 Redis 已经定位为 runtime 和短期状态。长任务统一用 Kafka，避免任务模型分裂。

### 13.4 如果 Worker 执行一半失败怎么办

TaskAttempt 记录失败，Task 标记 FAILED，业务对象也收敛到 FAILED 或保持旧版本可用。后续可 retry。
