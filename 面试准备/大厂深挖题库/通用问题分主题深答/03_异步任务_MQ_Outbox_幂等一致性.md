# 文件：03_异步任务_MQ_Outbox_幂等一致性.md

## 0. 本篇定位

这篇负责异步任务、MQ、Outbox、幂等和最终一致性的主题深答。它适合回答为什么要异步、Kafka 在项目里如何使用、重复消费和重复请求怎么处理、任务取消和失败重试怎么设计。

## 1. 本主题覆盖的通用问题

- 为什么这里不直接同步处理，而要异步？
- 项目里用的是什么 MQ？有没有事务消息？
- 消费者怎么设计？
- 幂等怎么做？
- 重复消费、重复请求怎么办？
- 任务取消、失败重试怎么做？
- 如果换成下单场景还能这么设计吗？

## 2. 架构视角怎么切入

异步设计不是为了“显得高级”，而是因为 NoteWeave 有大量耗时、易失败、依赖外部系统的任务：

- 文档解析和索引。
- embedding backfill。
- Source 导入和编译。
- Artifact 生成。
- Wiki 入索引。
- RAG Eval。
- Resource cleanup。

这些任务不适合放在 HTTP 请求里同步完成。同步阶段只应该保证核心业务状态落库；耗时处理进入后台任务。

## 3. 完整链路深讲

以 `DOCUMENT_PROCESS` 为例：

```text
用户 merge 上传
-> 创建 Document(PENDING_PROCESS)
-> TaskService.createTask(DOCUMENT_PROCESS)
-> 写 TaskEvent(TASK_CREATED)
-> 写 TaskOutbox
-> 事务提交
-> TaskDispatcher 扫 outbox
-> publish Kafka
-> outbox 标记 SENT
-> Worker 消费 taskId
-> 回查 Task for update
-> PENDING -> RUNNING
-> 创建 TaskAttempt
-> 解析文件 / 切片 / 写 ES
-> 更新 Document activeIndexVersion
-> Task SUCCESS / FAILED
-> TaskEvent 记录结果
```

这里最关键的是：Kafka 消息只作为通知，DB Task 状态才是事实源。

## 4. 关键实现锚点

- `TaskService.createTask`
- `TaskOutboxService.createTaskCreatedOutbox`
- `TaskDispatcher.dispatchPendingMessages`
- `TaskOutboxRoutingPublisher`
- `TaskAttempt`
- `TaskEvent`
- `TaskStatus`: `PENDING / RUNNING / SUCCESS / FAILED / CANCELLED / TIMEOUT`
- `cancelRequested`
- `idempotencyKey`

## 5. 为什么这么设计

### 5.1 为什么不用同步

如果文档 merge 后同步解析和写 ES，会带来：

- HTTP 请求耗时不可控。
- LLM、ES、MinIO 任一组件慢都会拖垮用户请求。
- 失败难重试。
- 前端无法统一展示进度。
- 后续 Source 编译、Artifact 生成还要重复造轮子。

异步后，用户拿到 taskId，通过任务状态看进度；后台失败可以 retry、cancel、audit。

### 5.2 为什么不直接在事务里发 Kafka

DB 和 Kafka 没有天然本地事务。直接发 Kafka 有两个失败窗口：

- Kafka 发送成功，但 DB 回滚，Worker 消费到不存在或不完整任务。
- DB 提交成功，但 Kafka 发送失败，任务永远不执行。

Outbox Pattern 的做法是：业务数据和 outbox 在同一个 DB 事务提交，Kafka 发送由 dispatcher 补偿执行。

### 5.3 幂等怎么做

幂等分两层：

请求幂等：

- `idempotencyKey` 防止重复创建 Task。
- 上传 merge 如果已经有 documentId/taskId，重复调用直接返回已有结果。

消费幂等：

- Worker 消费消息后回查 Task。
- 只有 PENDING 能 claim 成 RUNNING。
- SUCCESS、FAILED、RUNNING 状态不会重复执行同一副作用。
- 具体业务侧再通过 activeIndexVersion、artifactVersionId、proposalId 等防止重复落库。

### 5.4 取消为什么是协作式

RUNNING 任务可能正在写 MinIO、ES、MySQL 或调用 LLM。强杀会造成半写入。NoteWeave 使用 `cancelRequested`，Worker 在安全点检查取消，安全退出并收敛状态。

## 6. 可直接复述的深答

NoteWeave 里异步任务是统一设计的，不是每个模块自己起线程。以文档解析为例，用户上传 merge 成功后，同步事务只负责创建 Document、Task、TaskEvent 和 TaskOutbox；Kafka 投递由 TaskDispatcher 后续补偿执行。这样避免业务 DB 和 Kafka 之间的不一致。Worker 收到消息后也不直接相信消息体，而是用 taskId 回查 DB，只有 PENDING 任务才能转 RUNNING，并创建 TaskAttempt。执行成功后更新业务状态和 Task SUCCESS；失败后记录 attempt error 和 TaskEvent。幂等上，创建任务靠 idempotencyKey，消费任务靠 Task 状态机。取消上，PENDING 可以直接 CANCELLED，RUNNING 只设置 cancelRequested，由 Worker 在安全点停止。这个设计让文档解析、Source 编译、Artifact 生成、Wiki 索引、Eval 和 cleanup 都复用同一套重试、取消、审计和进度模型。

## 7. 追问兜底

### 如果问“Outbox 发送成功但标记 SENT 失败”

会导致 outbox 可能再次发送，所以消费侧必须按至少一次语义设计幂等。Worker 回查 Task 状态就是为了处理这种重复。

### 如果问“换成订单场景能不能这么做”

订单创建、支付成功这类核心状态通常要同步确认；发券、积分、通知、物流同步可以异步。原则是区分核心事务和可最终一致的后置动作。

### 如果问“为什么不用 Redis Stream”

当前主任务队列用 Kafka。Redis 在项目中承担 runtime 和临时状态，不承担长任务队列，避免任务模型分裂。

## 8. 边界和不能说满的地方

- 可以坚定讲：长任务统一异步、Outbox 解决本地事务与消息发送的不一致、Worker 回查 DB 做幂等。
- 不要讲成：Outbox 没有重复；Kafka exactly-once 就足够；项目已经有真实生产吞吐和积压治理数据。

