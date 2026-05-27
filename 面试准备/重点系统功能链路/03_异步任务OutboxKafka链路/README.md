# 03 异步任务 Outbox Kafka 链路

## 0. 本篇定位

这条链路回答：NoteWeave 如何承接上传解析、Source 编译、Artifact 生成、Wiki 入索引、Eval、Cleanup 这类长任务。

核心口径：

```text
长任务统一进入 Task。
业务事务内写 Task + TaskOutbox。
事务提交后投递 Kafka。
Worker 消费后回查 DB 状态。
执行过程写 TaskAttempt / TaskEvent。
Admin 可以统一查询、取消、重试和 mark failed。
```

## 面试先说版

这条链路我会从“AI 系统里的长任务怎么治理”讲，而不是一上来讲实现名称。NoteWeave 里文档解析、索引、生成、评测、清理都可能耗时，也依赖 MinIO、ES、LLM 这些外部组件，所以不能让用户请求同步等到最后。

我的设计是：MySQL 先记录任务事实和业务状态，Outbox 保证“业务数据落库”和“待投递消息”在本地事务内一起完成，Kafka 负责异步推进和削峰，Worker 消费后先回查 DB 状态再执行。它不是强事务模型，而是最终一致模型，核心靠状态机、幂等、重试、attempt/event 和 Admin 操作把任务收敛。

这里能自然带出的八股是：本地消息表、MQ 最终一致、at-least-once、消费幂等、失败重试、补偿、Kafka 和 Redis 队列的取舍。面试官如果问“为什么不用本地线程池”，我会说本地线程池实现简单，但进程重启、多实例扩容、失败恢复和可观测都弱，不适合这类后台任务流。

## Q1：为什么要统一成 `Task + Outbox + Kafka + Worker`？

**答：**

因为 NoteWeave 里很多核心操作都是长任务或易失败任务。如果每个模块各写一套后台执行逻辑，后续重试、取消、进度、错误排查和 Admin 管理都会碎掉。

统一任务模型后，文档解析、Source 导入、Source 编译、Artifact 生成、Wiki 入索引、Embedding backfill、RAG Eval、资源清理都可以用同一套生命周期：

```text
创建 Task
-> 写 TaskOutbox
-> Kafka 投递
-> Worker 回查 Task 状态
-> 写 TaskAttempt / TaskEvent
-> 成功、失败、取消或重试
```

这样做的收益是：用户请求可以快速返回 taskId；后台任务有统一状态；失败有统一错误记录；Admin 可以统一查看、取消、重试和 mark failed；后续新增长任务也不用重新设计执行框架。

## Q2：为什么不在业务事务里直接发 Kafka？

**答：**

直接发 Kafka 最大的问题是业务 DB 写入和消息投递很难放在一个强事务里。

比如上传 merge 成功后，需要创建 Document、绑定 FileObject、创建 Task，然后让 Worker 去解析。如果 DB 已提交但 Kafka 发送失败，任务就没人执行；如果 Kafka 发出去了但 DB 回滚，Worker 拿到 taskId 又查不到任务。

Outbox 的思路是：业务事务内只写本地数据库，包括业务状态和 `task_outbox`。事务提交后再由 dispatcher 把 outbox 消息投递到 Kafka。投递失败不会让业务数据回滚，而是保留 outbox 为 PENDING 或 FAILED，后续可以补偿投递。

## Q3：后台任务长时间没有结果怎么办？

**答：**

先根据 `task_status`、`task_attempt` 和 `task_event` 定位它卡在哪里。

如果任务还在 PENDING，要看 outbox 是否已投递、Kafka 是否正常、dispatcher 是否工作。如果任务 RUNNING 很久，要看 Worker 是否超时、是否支持 cancel safe point、外部依赖是否卡住。如果任务 FAILED，要看错误类型，是临时依赖故障、参数错误、对象不存在，还是代码逻辑问题。

处理方式上，不能简单“看到没成功就重跑”。要先查任务当前状态，再按任务类型决定是否 retry、cancel、mark failed 或人工修复。

## Q4：任务取消和 WebSocket stop 是一回事吗？

**答：**

不是。

Task cancel 是后台任务生命周期控制，比如文档解析、Artifact 生成、Eval、Cleanup 这种任务可以通过 `cancel_requested` 通知 Worker 在安全点停止。

WebSocket stop 是会话运行态控制，用户在流式输出中要求停止当前回答。它通过 `ActiveExecutionRegistry` 标记当前执行停止，停止继续推送 delta，并保留 partialContent 和 runtime state。

## 和 Austin 的类比

Austin 里消息任务进入 MQ 后，通过状态机、重试、死信和补偿让发送链路收敛。NoteWeave 这里不是通知发送，而是知识处理任务。相似点是都接受异步中间态，并用状态、幂等、事件和补偿让任务最终收敛。

## 实现兜底锚点

- `TaskService`
- `TaskOutboxService`
- `TaskDispatcher`
- `TaskKafkaPublisher`
- `TaskKafkaConsumer`
- `TaskWorkerRegistry`
- `TaskServiceIntegrationTest`
- `TaskAdminVisibilityIntegrationTest`

## 3 到 5 分钟深答模板

> NoteWeave 里很多核心操作都不是适合放在 HTTP 请求里同步做完的，比如文档解析索引、Source 编译、Artifact 生成、Wiki 入索引、RAG Eval 和 Cleanup。所以我把它们统一放到任务体系里治理。业务入口先在 MySQL 里创建任务和 outbox，把“业务事实”和“待投递事实”放进同一个本地事务；事务提交后再投递 Kafka。Worker 消费时不会只相信消息体，而是回查 DB 状态并 claim 任务，再记录 attempt 和 event。这样重复消息、投递失败、执行超时、取消请求和 Admin 重试都能收敛到同一套状态机，而不是每个模块各自实现一套后台逻辑。

## 常见追问继续怎么接

- 如果继续追问“为什么不用定时扫表替代 Kafka”，可以答：扫表适合兜底，不适合承担主通知链路；Kafka 让任务分发更及时，扫表更像补偿机制。
- 如果继续追问“幂等是怎么做的”，可以答：不是只靠 MQ 去重，而是消费侧回查任务状态、claim 所有权、记录 attempt 和 event。
- 如果继续追问“失败后怎么收敛”，可以答：看任务状态机和重试策略，必要时 cancel、retry、mark failed 或走 Admin 人工补偿。

## 边界和不能说满的地方

- 可以坚定讲：这是最终一致、可补偿、消费侧幂等的设计。
- 不要讲成：Outbox 保证绝不重复；Kafka 成功就等于业务成功；Redis 是主任务队列。
