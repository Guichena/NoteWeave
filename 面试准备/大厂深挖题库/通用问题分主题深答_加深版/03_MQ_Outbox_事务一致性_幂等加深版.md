# MQ、Outbox、事务一致性、幂等加深版

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 通用问题如何转成项目深答
先把通用八股问题落到 NoteWeave 的真实模块，再回答场景、方案、收益、权衡、故障和指标。下面是本主题的项目化深答。

## 1. 本篇定位
上传、解析、索引、Source 编译、Artifact 生成、Eval、清理都是耗时任务，直接同步处理会超时，分散写后台任务又会失控。

## 2. 面试先说版
我会把这条链路讲成 NoteWeave 的工程地基。系统里很多动作都不是瞬时完成的，比如文档解析入索引、Source 编译、Artifact 生成、RAG Eval 和资源清理。我的设计是业务接口不直接把任务塞给某个临时线程，而是创建统一 Task，并在同一个事务里写 task_outbox。事务提交后由 outbox 调度器投递 Kafka，消费者拿到消息后只信 taskId，再回查数据库判断状态和幂等。Worker 执行时记录 TaskAttempt 和 TaskEvent，失败可以重试，运行中可以通过 cancel_requested 在安全点停止。这个方案牺牲了一点链路长度，但换来的是最终一致、可恢复和可观测。

## 3. 当前真实口径
NoteWeave 统一用 Task、TaskAttempt、TaskEvent、TaskOutbox、Kafka、Worker 来承载后台任务。

### 已实现
- TaskType 包含 DOCUMENT_PROCESS、DOCUMENT_REINDEX、SOURCE_IMPORT、SOURCE_COMPILE、ARTIFACT_GENERATE、EMBEDDING_BACKFILL、WIKI_INDEX、RAG_EVAL_RUN、CLEANUP_RESOURCE。
- TaskController 提供任务查询、事件查询、skill logs、cancel、retry。
- TaskOutboxService、TaskOutboxDispatchScheduler、TaskKafkaPublisher、TaskKafkaConsumer 形成 outbox 到 Kafka 的投递链路。
- TaskExecutionCoordinator 统一协调 Worker 执行状态。

### 设计目标
- 业务事务先写 Task 和 Outbox；Outbox 调度器投递 Kafka；Consumer 只信 taskId 并回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态和取消。
- 它把幂等、重试、取消、审计、前端进度和 Admin 运维统一了，避免每个模块发明一套后台执行逻辑。

### 后续可扩展
- 消息投递成功但 Worker 执行失败怎么办？
- 重试是复用原 Task 还是创建新 Task？
- Admin mark-failed 和业务失败有什么区别？

## 4. 代码和测试锚点
- src/main/java/com/noteweave/task/model/TaskType.java
- src/main/java/com/noteweave/task/service/TaskService.java
- src/main/java/com/noteweave/task/service/TaskOutboxService.java
- src/main/java/com/noteweave/task/service/TaskExecutionCoordinator.java
- src/test/java/com/noteweave/task/service/TaskServiceIntegrationTest.java

## 5. 必会问题与答题骨架

### Q1: 为什么不直接在业务事务里发 Kafka？

回答时按四步走：
1. 先说场景：上传、解析、索引、Source 编译、Artifact 生成、Eval、清理都是耗时任务，直接同步处理会超时，分散写后台任务又会失控。
2. 再说方案：业务事务先写 Task 和 Outbox；Outbox 调度器投递 Kafka；Consumer 只信 taskId 并回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态和取消。
3. 再说收益：它把幂等、重试、取消、审计、前端进度和 Admin 运维统一了，避免每个模块发明一套后台执行逻辑。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 我会把这条链路讲成 NoteWeave 的工程地基。系统里很多动作都不是瞬时完成的，比如文档解析入索引、Source 编译、Artifact 生成、RAG Eval 和资源清理。我的设计是业务接口不直接把任务塞给某个临时线程，而是创建统一 Task，并在同一个事务里写 task_outbox。事务提交后由 outbox 调度器投递 Kafka，消费者拿到消息后只信 taskId，再回查数据库判断状态和幂等。Worker 执行时记录 TaskAttempt 和 TaskEvent，失败可以重试，运行中可以通过 cancel_requested 在安全点停止。这个方案牺牲了一点链路长度，但换来的是最终一致、可恢复和可观测。

常见追问：
- 消息投递成功但 Worker 执行失败怎么办？
- 重试是复用原 Task 还是创建新 Task？
- Admin mark-failed 和业务失败有什么区别？

### Q2: Outbox 解决的核心一致性问题是什么？

回答时按四步走：
1. 先说场景：上传、解析、索引、Source 编译、Artifact 生成、Eval、清理都是耗时任务，直接同步处理会超时，分散写后台任务又会失控。
2. 再说方案：业务事务先写 Task 和 Outbox；Outbox 调度器投递 Kafka；Consumer 只信 taskId 并回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态和取消。
3. 再说收益：它把幂等、重试、取消、审计、前端进度和 Admin 运维统一了，避免每个模块发明一套后台执行逻辑。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 我会把这条链路讲成 NoteWeave 的工程地基。系统里很多动作都不是瞬时完成的，比如文档解析入索引、Source 编译、Artifact 生成、RAG Eval 和资源清理。我的设计是业务接口不直接把任务塞给某个临时线程，而是创建统一 Task，并在同一个事务里写 task_outbox。事务提交后由 outbox 调度器投递 Kafka，消费者拿到消息后只信 taskId，再回查数据库判断状态和幂等。Worker 执行时记录 TaskAttempt 和 TaskEvent，失败可以重试，运行中可以通过 cancel_requested 在安全点停止。这个方案牺牲了一点链路长度，但换来的是最终一致、可恢复和可观测。

常见追问：
- 消息投递成功但 Worker 执行失败怎么办？
- 重试是复用原 Task 还是创建新 Task？
- Admin mark-failed 和业务失败有什么区别？

### Q3: Worker 重复消费如何保证幂等？

回答时按四步走：
1. 先说场景：上传、解析、索引、Source 编译、Artifact 生成、Eval、清理都是耗时任务，直接同步处理会超时，分散写后台任务又会失控。
2. 再说方案：业务事务先写 Task 和 Outbox；Outbox 调度器投递 Kafka；Consumer 只信 taskId 并回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态和取消。
3. 再说收益：它把幂等、重试、取消、审计、前端进度和 Admin 运维统一了，避免每个模块发明一套后台执行逻辑。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 我会把这条链路讲成 NoteWeave 的工程地基。系统里很多动作都不是瞬时完成的，比如文档解析入索引、Source 编译、Artifact 生成、RAG Eval 和资源清理。我的设计是业务接口不直接把任务塞给某个临时线程，而是创建统一 Task，并在同一个事务里写 task_outbox。事务提交后由 outbox 调度器投递 Kafka，消费者拿到消息后只信 taskId，再回查数据库判断状态和幂等。Worker 执行时记录 TaskAttempt 和 TaskEvent，失败可以重试，运行中可以通过 cancel_requested 在安全点停止。这个方案牺牲了一点链路长度，但换来的是最终一致、可恢复和可观测。

常见追问：
- 消息投递成功但 Worker 执行失败怎么办？
- 重试是复用原 Task 还是创建新 Task？
- Admin mark-failed 和业务失败有什么区别？

### Q4: 任务取消为什么用 cancel_requested 而不是强杀？

回答时按四步走：
1. 先说场景：上传、解析、索引、Source 编译、Artifact 生成、Eval、清理都是耗时任务，直接同步处理会超时，分散写后台任务又会失控。
2. 再说方案：业务事务先写 Task 和 Outbox；Outbox 调度器投递 Kafka；Consumer 只信 taskId 并回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态和取消。
3. 再说收益：它把幂等、重试、取消、审计、前端进度和 Admin 运维统一了，避免每个模块发明一套后台执行逻辑。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 我会把这条链路讲成 NoteWeave 的工程地基。系统里很多动作都不是瞬时完成的，比如文档解析入索引、Source 编译、Artifact 生成、RAG Eval 和资源清理。我的设计是业务接口不直接把任务塞给某个临时线程，而是创建统一 Task，并在同一个事务里写 task_outbox。事务提交后由 outbox 调度器投递 Kafka，消费者拿到消息后只信 taskId，再回查数据库判断状态和幂等。Worker 执行时记录 TaskAttempt 和 TaskEvent，失败可以重试，运行中可以通过 cancel_requested 在安全点停止。这个方案牺牲了一点链路长度，但换来的是最终一致、可恢复和可观测。

常见追问：
- 消息投递成功但 Worker 执行失败怎么办？
- 重试是复用原 Task 还是创建新 Task？
- Admin mark-failed 和业务失败有什么区别？

### Q5: 如果 Kafka 短暂不可用，业务接口应该怎么表现？

回答时按四步走：
1. 先说场景：上传、解析、索引、Source 编译、Artifact 生成、Eval、清理都是耗时任务，直接同步处理会超时，分散写后台任务又会失控。
2. 再说方案：业务事务先写 Task 和 Outbox；Outbox 调度器投递 Kafka；Consumer 只信 taskId 并回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态和取消。
3. 再说收益：它把幂等、重试、取消、审计、前端进度和 Admin 运维统一了，避免每个模块发明一套后台执行逻辑。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 我会把这条链路讲成 NoteWeave 的工程地基。系统里很多动作都不是瞬时完成的，比如文档解析入索引、Source 编译、Artifact 生成、RAG Eval 和资源清理。我的设计是业务接口不直接把任务塞给某个临时线程，而是创建统一 Task，并在同一个事务里写 task_outbox。事务提交后由 outbox 调度器投递 Kafka，消费者拿到消息后只信 taskId，再回查数据库判断状态和幂等。Worker 执行时记录 TaskAttempt 和 TaskEvent，失败可以重试，运行中可以通过 cancel_requested 在安全点停止。这个方案牺牲了一点链路长度，但换来的是最终一致、可恢复和可观测。

常见追问：
- 消息投递成功但 Worker 执行失败怎么办？
- 重试是复用原 Task 还是创建新 Task？
- Admin mark-failed 和业务失败有什么区别？

## 6. 大厂深挖追问路径
1. 先问你做了什么。
2. 再问为什么这样设计，不用更简单方案。
3. 再问失败、重试、越权、删除、断线、重建索引时会发生什么。
4. 最后问如何量化效果和下一步演进。

把答案往下压一层：
- 业务层：上传、解析、索引、Source 编译、Artifact 生成、Eval、清理都是耗时任务，直接同步处理会超时，分散写后台任务又会失控。
- 架构层：业务事务先写 Task 和 Outbox；Outbox 调度器投递 Kafka；Consumer 只信 taskId 并回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态和取消。
- 数据层：引用 MySQL、Redis、MinIO、ES、Kafka 或 Citation/Trace 的真实职责。
- 测试层：能说出对应 IntegrationTest 或 ServiceTest。
- 边界层：明确哪些是后续扩展，不冒充已落地。

## 7. 不能说满的地方
- 不要说 Outbox 保证强一致，它保证的是 DB 事实和消息投递的最终一致。
- 不要把 Redis Stream 说成后台任务主队列。
- 不要说取消可以任意打断正在执行的外部 IO，只能在安全点停止。

## 8. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。
