# 文件：03_异步任务OutboxKafka与一致性.md

## 0. 本篇定位

这篇负责统一异步任务、Outbox、Kafka 和最终一致性的深挖。它适合回答为什么要统一后台任务、为什么不用事务里直接发消息、重复消费怎么幂等、任务取消怎么设计，以及一致性和补偿链路怎么讲。

## 1. 本主题面试官想考什么

这个主题考察分布式系统基本功：长任务抽象、事务消息、最终一致性、幂等、重试、取消、失败恢复和可观测。NoteWeave 的统一异步骨架是很强的面试亮点，因为它把上传解析、Source 编译、Artifact 生成、Wiki 索引、Eval、清理任务收敛到一套模型。

## 2. 高频问题清单

### 基础问题

- 为什么需要统一的 `Task` 模型？
- `TaskAttempt` 和 `TaskEvent` 分别解决什么问题？
- `TaskOutbox` 是什么？为什么要有它？
- Kafka 在这里扮演什么角色？

### 进阶问题

- 为什么不在业务事务里直接发 Kafka？
- idempotencyKey 怎么设计？
- Worker 重复消费怎么办？
- 任务取消为什么是 `cancel_requested`，而不是强杀线程？
- outbox 发送失败后怎么补偿？

### 深挖追问

- 如果 DB 提交成功但 Kafka 发送失败，怎么恢复？
- 如果 Kafka 消息到了但 task 状态不是 PENDING，Worker 怎么处理？
- 如果任务已经 RUNNING，此时用户取消，如何保证副作用可控？
- Artifact 生成失败后如何回滚或 reconcile？

### 压力追问

- 如果任务量很大，Task 表和 Event 表会不会膨胀？
- 如果 Worker 部署多实例，如何避免同一个任务并发执行？
- 如果要支持优先级队列和延迟任务，当前模型怎么扩展？
- Redis Stream 能不能替代 Kafka？

## 3. 问答与讲解

### Q1：为什么要统一成 `Task + Attempt + Event + Outbox + Kafka + Worker`？

#### 面试官为什么问

这是系统设计核心题。面试官想看你能不能识别长任务共性，并通过统一抽象降低架构漂移。

#### 回答思路

先列出项目里的长任务，再说明如果每个模块各自实现会造成状态不一致、重试不一致、取消不一致、前端进度不可复用。最后讲统一模型带来的收益。

#### 结合我的项目怎么答

NoteWeave 里的文档解析、embedding backfill、Source import、Source compile、Artifact generate、Wiki index、RAG eval、cleanup 都不是适合 HTTP 同步等待的操作。项目用 `TaskService` 创建任务，用 `TaskOutboxService` 记录待投递事件，用 `TaskDispatcher` 投递 Kafka，用 Worker 执行业务，并用 `TaskAttempt` 和 `TaskEvent` 记录执行历史和状态迁移。

#### 技术原理 / 链路设计讲解

统一任务模型的本质是把长任务拆成三层：

- 业务状态：Task 当前是 PENDING、RUNNING、SUCCESS、FAILED、CANCELLED、TIMEOUT。
- 执行记录：Attempt 记录第几次执行、worker、错误、耗时。
- 事件流：Event 记录状态迁移，让前端和 Admin 能看到过程。

Outbox 不是真正业务队列，它是数据库事务里的“待发送消息表”。业务写入和 outbox 写入同事务提交，之后由 dispatcher 异步投递 Kafka。

#### 技术栈特点与选型理由

Kafka 适合多 worker 消费和削峰；MySQL 适合持久化任务状态和审计；Outbox 解决“业务 DB 成功但消息发送失败”的一致性问题。Redis 更适合临时 runtime，不适合作为当前项目的主长任务队列。

#### 可直接复述的面试回答

我做统一 Task 骨架，是因为项目里很多能力本质上都是长任务，比如文档解析索引、Source 编译、Artifact 生成、Wiki 入索引、评测和清理。如果每个模块各写一套后台线程，就会出现状态枚举不统一、重试和取消逻辑不统一、前端无法统一看进度、失败也不好排查。所以我把它们收敛成 `Task + TaskAttempt + TaskEvent + TaskOutbox + Kafka + Worker`。Task 表示当前状态，Attempt 记录每次执行，Event 记录状态迁移，Outbox 保证业务事务和消息投递最终一致，Kafka 负责把任务分发给 Worker。

#### 常见追问

- Outbox 和 MQ 事务消息有什么区别？
- TaskEvent 会不会太多？
- Worker 如何根据 taskId 回查状态？

#### 常见坑

不要把 outbox 说成“消息队列表”。它的职责是事务外盒和补偿投递，不负责业务消费语义。

---

### Q2：为什么不在业务事务里直接发 Kafka？

#### 面试官为什么问

这是经典一致性题。面试官想看你是否理解本地事务和外部消息系统之间没有天然原子性。

#### 回答思路

讲两个失败窗口：DB 成功 Kafka 失败，Kafka 成功 DB 回滚。然后说明 outbox 如何把“不可能强一致”的问题转成“可补偿的最终一致”。

#### 结合我的项目怎么答

比如文档 merge 时，会创建 Document、更新 FileObject refCount、创建 Task。如果此时直接发 Kafka，一旦 Kafka 发送成功但 DB 回滚，Worker 会收到一个不存在或不完整的任务；如果 DB 提交成功但 Kafka 发送失败，任务永远不执行。当前实现是在创建 Task 的同一事务里创建 TaskOutbox，之后 dispatcher 查找待发送 outbox，再发布 Kafka，失败则设置 retryCount 和 nextRetryAt。

#### 技术原理 / 链路设计讲解

Outbox Pattern 的关键点：

- 业务数据和 outbox 记录写入同一个 DB 事务。
- 外部发送由独立 dispatcher 完成。
- 发送成功后 outbox 标记 SENT。
- 发送失败后记录 FAILED、retryCount、nextRetryAt。
- Worker 不信任消息体，拿到 taskId 后回查 DB。

#### 技术栈特点与选型理由

这种方案不需要依赖 Kafka 事务和业务 DB 做跨资源事务，工程上更简单、可观测、可补偿。代价是消息可能重复发送，所以 Worker 必须幂等。

#### 可直接复述的面试回答

我没有在业务事务里直接发 Kafka，因为 DB 和 Kafka 不是一个事务资源。直接发会有两个窗口：Kafka 发成功但 DB 回滚，Worker 消费到脏任务；或者 DB 提交成功但 Kafka 发送失败，任务永远没人执行。Outbox 的做法是把业务写入和待发送消息放在同一个 MySQL 事务里，提交后再由 dispatcher 投递 Kafka。发送失败可以通过 outbox 状态和 nextRetryAt 补偿。这样不是强一致，而是把不可控失败变成可重试、可观测的最终一致。

#### 常见追问

- outbox 发送成功但标记 SENT 失败怎么办？
- Kafka 重复投递怎么办？
- 为什么 Worker 要回查 DB，而不是直接信消息体？

#### 常见坑

不要说“outbox 保证绝对不丢不重”。更准确是“保证 DB 已提交任务可被补偿投递，但消费侧必须按至少一次语义设计幂等”。

---

### Q3：任务取消为什么是 `cancel_requested`，而不是强杀？

#### 面试官为什么问

面试官想看你对副作用和一致性的敏感度。强杀线程听起来简单，但在真实业务中经常造成半写入状态。

#### 回答思路

说明 PENDING 和 RUNNING 的处理不同：PENDING 可以直接转 CANCELLED；RUNNING 只能设置取消请求，由 Worker 在安全点检查并停止。

#### 结合我的项目怎么答

`TaskService.cancelTask` 对 PENDING 任务会直接标记 CANCELLED；对 RUNNING 任务设置 `cancelRequested=true` 并写 TASK_CANCEL_REQUESTED 事件。Worker 或执行上下文在关键步骤调用 `ensureNotCancelled()`，例如 ArtifactPlanExecutor 每个 skill 前检查取消，避免在不可中断的写库操作中强制停止。

#### 技术原理 / 链路设计讲解

长任务通常有外部副作用：写 MinIO、写 ES、写 MySQL、调用 LLM、生成 ArtifactVersion。如果强杀线程，可能出现 object 已写入但 DB 未更新、ES 新索引写了一半、Artifact 状态卡在 GENERATING。协作式取消允许在“安全点”停止，并把状态 reconcile 到 CANCELLED 或 FAILED。

#### 技术栈特点与选型理由

Java 线程强中断无法保证业务资源回滚；Spring 事务只能保护当前 DB 事务，不能保护外部 MinIO/ES/LLM。协作式取消是更可靠的工程方案。

#### 可直接复述的面试回答

RUNNING 任务我不会直接强杀，而是设置 `cancel_requested`。原因是这些任务有很多外部副作用，比如写对象存储、写 ES、调用 LLM、保存 ArtifactVersion。强杀可能让系统停在半写入状态。我的设计是 PENDING 任务可以直接取消；RUNNING 任务写一个取消请求和事件，Worker 在每个安全点检查，比如每个 skill 执行前、每个阶段写入前，发现取消就停止并把状态收敛。这个方式牺牲了一点即时性，但换来状态一致和副作用可控。

#### 常见追问

- 如果 LLM 调用已经发出，能不能取消？
- 如果取消时已经写了一半 Artifact 怎么办？
- PENDING 任务取消后 outbox 还发出去了怎么办？

#### 常见坑

不要说“取消就是把线程 stop 掉”。Java 里强杀线程本身就不安全，业务上更不可控。

---

## 4. 本主题总结

这条主线要强调：Outbox 解决事务消息最终一致，Kafka 负责分发，Task 状态负责用户可见进度，Attempt/Event 负责审计，Worker 必须幂等，取消必须是协作式。

## 5. 面试前自查清单

- 我是否能画出 Task 创建到 Worker 执行的完整链路？
- 我是否能解释 outbox 不是业务队列？
- 我是否能回答 DB 成功 Kafka 失败的补偿方式？
- 我是否能讲清 Worker 重复消费和任务幂等？
- 我是否能解释 `cancel_requested` 的安全点设计？

## 6. 整条链路深讲：一个任务从创建到成功的全过程

可以用 `DOCUMENT_PROCESS` 举例讲完整链路。

第一步，业务服务在一个数据库事务里创建业务数据。例如文档 merge 成功后创建 Document、绑定 FileObject、增加 refCount。

第二步，同一个事务里调用 `TaskService.createTask` 创建 Task。Task 会保存 userId、spaceId、taskType、targetType、targetId、inputJson、idempotencyKey 和初始状态 PENDING。`idempotencyKey` 用来防止重复请求创建重复任务。

第三步，同一事务里追加 TaskEvent，例如 TASK_CREATED，同时通过 `TaskOutboxService.createTaskCreatedOutbox` 写入 outbox。注意这里还没有要求 Kafka 必须发送成功，业务事务只保证 Task 和 outbox 同时提交。

第四步，`TaskDispatcher` 扫描待投递 outbox，调用 `TaskOutboxRoutingPublisher` 按任务类型路由到 Kafka topic。发送成功后 outbox 标记 SENT；失败则标记 FAILED、增加 retryCount、设置 nextRetryAt，后续补偿。

第五步，Worker 消费 Kafka 消息后不直接相信消息体，而是用 taskId 回查 DB，并通过行锁或状态判断 claim 任务。只有 PENDING 任务能转 RUNNING，重复消费、过期消息或已成功任务会被跳过。

第六步，Worker 创建 TaskAttempt，记录 attemptNo、workerId、开始时间和 RUNNING 状态。执行业务逻辑时在安全点检查 cancelRequested。

第七步，业务执行成功后更新业务结果，例如 Document activeIndexVersion、parseStatus、indexStatus、chunkCount；再把 Task 标记 SUCCESS，写 resultRefType/resultRefId/outputJson，并追加 TASK_SUCCEEDED。

第八步，失败时写 TaskAttempt 的 errorCode/errorMessage，把 Task 标记 FAILED 或 TIMEOUT，业务对象也收敛到 FAILED 或保持旧可用版本。这样前端和 Admin 能看到失败原因，并决定是否 retry。

## 7. 原理与设计原因速查

- 为什么 outbox 能解决一致性：它把“业务数据”和“待发送消息”放进同一个本地事务，把外部消息发送变成可补偿动作。
- 为什么仍然会重复：dispatcher 可能发送成功但标记 SENT 前失败，Kafka 也可能至少一次投递，所以消费侧必须幂等。
- 为什么 Worker 回查 DB：消息体可能过期、重复或伪造，DB Task 状态才是权威。
- 为什么需要 Attempt：Task 只代表最终状态，Attempt 才能解释第几次失败、哪个 worker 失败、耗时多少。
- 为什么需要 Event：Event 是给前端进度、Admin 审计和排障看的状态流。
- 为什么取消是协作式：长任务有 MinIO、ES、MySQL、LLM 等外部副作用，强杀可能造成半写入。

## 8. 3 到 5 分钟深答模板

以文档解析任务为例，用户 merge 上传后，业务事务里会先创建 Document 和 FileObject 绑定，再创建 `DOCUMENT_PROCESS` Task，同时写 TaskEvent 和 TaskOutbox。这里不直接发 Kafka，因为 DB 和 Kafka 不是同一个事务资源，如果直接发会出现 DB 回滚但消息已发送，或者 DB 提交但消息发送失败。Outbox 提交后，dispatcher 异步投递 Kafka，失败就通过 outbox 状态和 nextRetryAt 补偿。Worker 收到消息后只把它当通知，会回查 Task 表并通过状态 claim 任务，只有 PENDING 才能转 RUNNING。执行过程中会创建 TaskAttempt，写 TASK_STARTED，真正解析、切片、索引时还会在安全点检查 cancelRequested。成功后更新业务对象和 Task SUCCESS；失败后写 attempt error、task error 和业务失败状态。如果同一消息重复消费，由于 task 已经不是 PENDING，就会跳过。这套模型保证了长任务的创建、投递、执行、重试、取消和审计都能统一处理。

## 9. 边界和不能说满的地方

- 可以坚定讲：统一任务模型、Outbox、补偿投递、消费侧幂等和安全点取消。
- 不要讲成：这是强一致事务消息；Outbox 能消灭重复；Kafka 成功即业务完成；当前已经有完整生产级死信治理和积压数据。
