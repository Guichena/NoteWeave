# Task 调度、Quota 与运行时治理一体化面试手册

> 定位：这篇解释 Research、Artifact 等长任务怎样进入系统、怎样限制资源、怎样展示进度。面试推荐把持久 Admission Reservation、易失 Active Permit、Budget Reservation 和 Domain Execution Lease 分开。WAITING 保留接纳记录并释放 Active Permit，只有拥有业务提交权的 Domain Execution Lease 使用 Epoch 与 Fencing。

## 1. 60 秒主回答

学校资料服务最初的异步任务只需要显示“处理中”和“已完成”。加入 Research 与 Artifact 后，一次请求可能占用模型、Worker、对象存储和数据库数分钟，只靠线程池队列会出现三个问题：进程重启后任务身份丢失，一个 Workspace 可以挤占全部资源，用户只看到卡住却不知道是在排队、等待 Provider，还是已经失败。

我把治理拆成四种合同。通用 `Task` 保存用户可见状态、阶段、结果引用和持久事件；MySQL 中的 Admission Reservation 限制一个 Workspace 最多保留多少未终结长任务；Redis Active Permit 只统计真正占用 Worker 或 Provider 的任务；Domain Execution Lease 保存 Owner、Epoch、Fencing Token、执行快照和 Completion 仲裁。Budget Reservation 再限制单个 Run 的总消耗。WAITING 保留持久接纳记录，但释放执行槽。

## 2. 三种对象保护不同的不变量

```text
用户请求
  -> Admission Reservation：这次请求是否已被系统持久接纳
  -> Task：用户看到的状态、进度、事件和结果引用
  -> Active Permit：当前能否占用 Worker/Provider
  -> Domain Execution Lease：谁能执行、何时过期、旧 Worker 能否提交
  -> Provider Permit / Budget：这次外部调用能否发生、最多花多少
```

| 对象 | 当前落点 | 保护的不变量 | 不负责什么 |
| --- | --- | --- | --- |
| 通用 Task | `task`、`task_event`、`TaskService` | 一个用户任务只有合法状态迁移，终态可查询、事件可续播 | 不保存 Research Cell、Fencing 和完整执行快照 |
| Research Agent Task | `ResearchAgentTaskService` 及领域表 | 同一时刻只有有效 Owner 能执行和提交 | 不替代用户侧统一任务中心 |
| Admission Reservation | `[目标设计]` MySQL `admission_reservation` 与配额用量行 | Workspace 和 Workload 不会无界堆积长任务，已接纳身份不因 Redis TTL 消失 | 不等于真实执行槽，也不授予 Worker 提交权 |
| Active Permit | 推荐从并发租约中独立出的执行维度 | 当前占用 Worker/Provider 的任务数有界 | WAITING 时不继续占用 |
| Research Permit | `ResearchAgentPermitService`、`ResearchAgentRateLimitService` | Lease、快照、角色、工具和 Provider 配额同时有效 | 不保存最终业务结果 |
| Budget Reservation | `ResearchBudgetAndCheckpointService` | 预留、消费、释放和结算可审计 | 不等同于每分钟速率限制 |

通用 Task 是产品投影，领域 Task 是执行协议。若把两者硬合并，Artifact 和 Research 会被迫共享状态枚举，用户侧查询又要理解 Cell、Wave、Lease Epoch 等内部字段。当前实现通过 `targetType`、`targetId` 和 `resultRef` 关联两套状态机；推荐再用 `RunLifecycle` 模块收住映射、取消传播、终态仲裁和对账，Controller、前端与通用查询只看 Task Interface，Research 与 Artifact 通过各自 Adapter 提供领域完成合同。

## 3. 从线程池异步到运行时治理

| 阶段 | 做法 | 暴露的问题 | 当前选择 |
| --- | --- | --- | --- |
| V0 | Controller 提交线程池 | 重启丢状态，无法统一查询 | 持久 Task 与事件 |
| V1 | 单一 PENDING/RUNNING/COMPLETED | 等待外部条件被误判为卡死 | 增加 WAITING、Phase 和 Wait Context |
| V2 | 只限制 API QPS | 慢任务仍可长期占满资源 | 速率令牌桶加并发租约 |
| V3 | JVM 本地 Semaphore | 多实例各算各的，整体超卖 | Redis Lua 原子仲裁 |
| V4 | 通用 Task 直接充当 Worker Lease | 业务展示状态与执行权耦合 | 领域 Task 单独保存 Claim 和 Fencing |
| V5 | 失败统一 Retry | 等待、可重试失败和死信混在一起 | Waiting、Redrive、Lifecycle Recovery 分开 |

## 4. 通用 Task 状态机

当前主路径可以概括为：

```text
PENDING -> RUNNING <-> WAITING
   |          |          |
   +----------+----------+-> FAILED / CANCELLED
                         +-> COMPLETED

FAILED -> PENDING 或 RUNNING，仅允许显式 Redrive
```

`TaskStateRepository` 用带前置状态的条件更新仲裁竞争。`start` 只接受 PENDING；`complete` 只接受 RUNNING 或 WAITING；`cancel` 和 `fail` 接受尚未终结的状态；`redrive` 只接受 FAILED。更新条数为零时，`TaskService` 会读取当前状态，区分幂等终态和非法迁移。

Task 更新与 `task_event` 追加在同一个 MySQL 事务中。前端查询 Task Snapshot 获取权威状态，通过 SSE 或 Event History 获取增量过程。事件游标按 `(task_id, created_at, id)` 排序，`V095` 增加同序索引；SSE 每次最多读取有界批次，并在无事件时退避和发送心跳。

### WAITING 到底表示什么

WAITING 只表示当前业务步骤不能继续，不等于失败。`WaitContext` 可以说明缺少哪个能力、依赖是否健康、是否可恢复和建议动作。用户补齐配置或 Provider 恢复后，任务可以回到 RUNNING；无意义的自动重试不会持续烧 Token。

当前通用 Workload Quota 在 `recordWaiting` 时仍会续租同一个 Redis 并发名额，这是需要迁移的实现。推荐把它拆成持久 Admission Reservation 与 Active Permit：Waiting 保留前者以限制租户积压，释放后者以避免 Provider 故障占满执行槽；真正恢复执行时重新竞争 Active Permit。等待超过产品期限时，控制面先把 Task 转为 `EXPIRED`、`CANCELLED` 或需人工处理的终态，再在同一事务关闭 Reservation，不能靠 TTL 静默把已接纳任务移出配额。

## 5. 速率、并发和预算是三种控制

### 速率令牌桶

当前 Key 包含 Environment、Workspace、Actor 指纹和 Workload。桶容量控制突发，补充速率控制长期平均：

```text
tokens(t) = min(capacity, tokens(t0) + (t - t0) * refillRate)
allowed = tokens(t) >= 1
```

Lua 使用 Redis `TIME`，避免多个应用实例本地时钟不一致。默认配置容量 20、每分钟补充 20，只能解释当前保护意图，不能推出生产 QPS。

### Admission Reservation 与 Active Permit

Admission Reservation 是随 Task 一起提交的 MySQL 业务记录。入口先用 `(workspace_id, actor_id, workload, client_request_id)` 或等价 Admission Key 查找语义提交；命中相同 Request Digest 时返回原 Task，Digest 不同时拒绝冲突。只有新请求才锁定对应 Workspace/Workload 配额行，条件增加在途用量并插入以 `task_id` 唯一的 Reservation；重复请求不能再次占用额度。终态、取消或显式过期时在同一事务关闭 Reservation 并扣减用量。学校规模下这一行锁比引入独立调度平台更容易解释和对账，出现热点后再迁移到分桶计数或单写 Admission Scheduler。

Active Permit 是 Redis Sorted Set 中带 TTL 的执行资源 Token。申请时先清理过期成员，再判断 `ZCARD` 是否达到限制；同一 Token 重放只续期，不重复占槽。当前 Workspace 与 Workload 并发上限 4、租约 300 秒属于把接纳和执行合并后的旧保护参数，迁移后 Admission 上限按可接受积压配置，Active 上限按 Worker、Provider 和内存容量配置。

```text
effective_concurrency = ZCARD(after removing expired leases)
admit when token already exists or effective_concurrency < limit
```

### 预算账本

预算回答“这个 Run 最多能消费多少 Search、Read、Token 或费用”。Reserve 先冻结上限，Settle 记录实际消耗，Release 归还未使用部分。速率和并发即使都通过，也可能因预算不足被拒绝；反过来，有预算也不代表当前可以抢占 Provider。

### 四种合同不共享生命周期

Admission Reservation 使用稳定 `reservation_id`、`task_id`、`resource_scope`、状态和幂等关闭，生命周期跟随持久 Task；Active Permit 使用 `permit_id`、资源类别和 `expires_at`，允许 TTL 回收；Budget Reservation 使用预留、消费、结算和释放账本。只有 Research、Answer 或 Artifact 中拥有业务提交权的 Domain Execution Lease 需要 Owner、单调 `lease_epoch`、`fencing_token` 和 Heartbeat，并要求下游提交点校验 Token。给 Admission 或 Redis Permit 附加下游不校验的 Fencing Token 没有安全收益。

## 6. Redis Lua 为什么适合这层治理

令牌补充、扣减、过期清理和并发判断需要原子完成。把 `HGET`、计算、`HSET` 或 `ZREMRANGEBYSCORE`、`ZCARD`、`ZADD` 拆成多次网络请求，两个实例可能同时看到剩余名额并都放行。Lua 在 Redis 单线程执行上下文中完成一组状态变化，避免应用侧分布式锁。

代价包括脚本版本治理、热点 Key、Redis 故障依赖和 Cluster 下多 Key 同槽限制。`WorkloadQuotaService` 的一次脚本只操作一个 Key；Research Provider Rate Limit 若同时检查多个维度，需要保证脚本的 Key 布局和 Redis Cluster Hash Tag 可用，或改成分级本地队列与中心调度。

Redis 不是执行真源。并发租约丢失会影响接纳决策，不能让已经提交到 MySQL 的业务任务消失。默认 `local-fallback-enabled=false`，Redis 不可用时新增长任务返回 503。只有明确接受单实例局部限流和多实例超卖风险时，才开启本地回退。

## 7. 事务边界和四个失败窗口

### Quota 已申请，Task 插入失败

`createTask` 先申请配额，再注册事务完成回调。事务未提交时释放 Lease，避免异常请求长期占槽。速率 Token 不归还，因为请求已经消耗入口处理能力，且归还会让失败风暴绕过限流。

### Task 已终结，Redis Release 失败

终态提交后再释放 Lease，保证其他请求不会在业务终态尚未可见时抢到名额。Release 失败时不会回滚已提交的 Task，租约依靠 TTL 最终回收，并通过 Recovered Lease 指标观察泄漏。

### Redis Lease 过期，旧任务还在执行

Workload Quota 的 Lease 只控制接纳数量，不能充当业务提交权。旧任务能否写结果必须由领域 Task 的 Lease Epoch 和 Fencing Token 决定。只用 Redis 并发槽作为 Fencing，会在暂停、长 GC 或网络分区后允许旧 Worker 覆盖新结果。

### Task 终态已写，终态事件未发送到浏览器

Task Row 与 Event 在同一事务，SSE 断开后可以按游标重放。若客户端刚好遗漏终态事件，Controller 还会读取权威 Task 状态并发送 Terminal Snapshot，然后关闭连接。

## 8. Research 的调度治理比通用 Task 更深

Research Coordinator 不是简单地取一个 PENDING 任务执行。它要读取 Run Snapshot，恢复过期执行，判断当前 Wave、角色工作流、预算、Rollout Guard 和可运行任务，再决定是否推进、等待或终结。

Worker Claim 后得到 Lease Epoch、Fencing Token 和冻结 Task Snapshot。调用工具前，Trusted Permit 再检查 Run 未终结、Task 仍由当前 Worker 持有、Lease 未过期、快照 Schema 合法、角色允许该能力，并通过 Provider Rate Limit。Heartbeat 只能延长当前 Owner 的租约；旧 Owner 的 Epoch 或 Token 不匹配时，Permit 和 Completion 都会拒绝。

这条链路有两个控制回路：Coordinator 负责“下一步应该运行什么”，Lifecycle Recovery 负责“失联、过期和终态残留怎样恢复”。把两者塞进一个定时任务会让正常推进和故障修复互相影响，也很难区分指标。

## 9. 公平调度和背压

Admission Reservation 能避免单租户无限接纳，但它不是完整的全局公平调度器。任务大量积压时，按 Workspace 建虚拟队列，再使用加权轮询或 Deficit Round Robin 分配 Active Permit。权重来自套餐或课程优先级，不能直接由任务提交速度决定。学校早期规模可先使用每 Workspace 上限加独立资源池，只有观察到跨租户饥饿后再引入 DRR。

公平要同时约束三件事：等待时间不能无界，单租户不能长期占满，短任务不能被大量 Research 长任务阻塞。可以为交互 QA、Artifact 和 Research 设独立池和总上限，再保留少量共享突发容量。严格优先级容易饿死低优先级任务，单一 FIFO 又会出现队头阻塞。

背压应在入口尽早暴露。拒绝新任务时返回明确错误和建议重试时间；进入 Waiting 时展示原因；内部队列接近上限时降低 Coordinator 扫描和 Worker Claim；Provider 429 时缩小并发，而不是增加线程继续重试。

## 10. 学校场景演练

考试周同时有多个课程 Workspace 发起资料 Research 和讲义产物生成。固定演练可以这样讲：

1. 学生点击生成时，Actor 速率桶限制连点和网络重试造成的突发。
2. Admission Reservation 限制同一课程尚未终结的长任务，Active Permit 再限制真正运行的数量。
3. Task 立即返回 PENDING，前端展示进度，Worker 接管后转为 RUNNING。
4. Provider 配置缺失时任务进入 WAITING，Wait Context 显示缺少的能力，不盲目 Retry。
5. Research Worker 暂停超过 Lease 后由新 Worker 接管，旧 Worker 的 Completion 被 Fencing 拒绝。
6. 任务终结时，MySQL 在同一事务提交 Task、Event 并关闭 Admission Reservation；Active Permit 的释放异常由 TTL 回收。

这是一条故障演练故事，不能据此填写“支持多少学生同时使用”。容量数字需要任务到达率、平均服务时间、Provider 配额、连接池和 Worker 实测。

## 11. 指标和计算方式

| 指标 | 公式或采集点 | 代表什么 |
| --- | --- | --- |
| Admission Reject Rate | `限流或并发拒绝数 / 创建请求数` | 入口容量是否不足，需按原因分桶 |
| Queue Wait P95 | `RUNNING 时间 - PENDING 创建时间` 的 P95 | 用户在系统内排队多久 |
| Active Service Time | 终态时间减 RUNNING 时间，扣除可识别 Waiting | Worker 和 Provider 的实际服务时间 |
| Waiting Ratio | `WAITING 时长 / Task 总历时` | 依赖或人工条件造成的阻塞程度 |
| Active Permit Recovery Count | Lua 清理的过期 Permit 数 | 释放失败、进程退出或心跳问题 |
| Lease Lost Rate | `续租被拒绝数 / 续租请求数` | 任务是否经常失去执行资格 |
| Redrive Success Rate | `重驱后成功 Task / 被重驱 Task` | 死信修复是否有效 |
| Stale Completion Reject Rate | `旧 Owner 提交拒绝 / Completion 请求` | Fencing 实际保护了多少竞争窗口 |
| Provider Permit Wait | 允许调用时间减首次申请时间 | 外部 Provider 是否成为限制因素 |
| Budget Utilization | `实际消费 / 预留预算` | 预算估计是否长期过松或过紧 |
| Workspace Fairness | 各活跃 Workspace 获得 Active Permit 的分布 | 是否有租户长期饥饿 |

Little's Law 可用于交叉检查容量：稳定系统中 `L = lambda * W`。若每分钟平均到达 2 个 Research，平均在途时间 3 分钟，则平均在途任务约 6 个。它只是稳态估算，重尾任务、突发流量和 Provider 限制仍要通过分位数与压测验证。

## 12. 4 到 5 分钟标准主回答

这个功能最初只是学校资料服务里的异步进度条。解析任务放到线程池，前端轮询一个状态，数据量小时足够。后来加入 Research 和 Artifact，一次任务会持续几分钟，还会访问外部模型、搜索、对象存储和 Elasticsearch。原方案开始出现三个问题：应用重启后线程里的任务无法解释；某个 Workspace 连续提交会把 Worker 和 Provider 全占满；用户看到“处理中”，却分不清排队、等待能力和真正失败。

我没有做一张万能任务表，而是拆成用户任务、领域任务和资源治理。通用 Task 保存 PENDING、RUNNING、WAITING 和终态，附带 Phase、Message、ResultRef 与持久 TaskEvent。它负责统一查询和前端展示。Research Agent Task 另外保存执行快照、Owner、Lease Epoch 和 Fencing Token，因为 Worker 提交权属于 Research 的业务不变量，Artifact 不应该被迫共享这套字段。Quota 再独立回答当前请求能不能进入系统。

入口有速率和接纳两种控制。Redis 速率桶限制长期平均，MySQL Admission Reservation 限制每个 Workspace 的未终结长任务。真正开始执行前再申请 Active Permit；任务进入 WAITING 时释放 Active Permit，恢复时重新竞争。Redis Lua 用服务端时间原子清理和申请 Permit，同一 Permit 重放只续期。预算记录某个 Research Run 预留、消费和释放的 Search、Read 或 Token，不能用 QPS 或并发替代。

推荐方案把 Task、Admission Reservation 和配额用量放进同一个 MySQL 事务，因此接纳资格没有跨存储双写窗口。Redis 只处理速率 Token 与 Active Permit：速率 Token 已经消耗入口能力，不因后续事务失败而归还；Active Permit 在领域状态提交后幂等释放，释放失败由 TTL 回收并记录 Recovered Permit。当前实现仍是创建 Task 前先申请合并并发租约，再通过事务完成回调释放，这是迁移起点。无论新旧方案，Quota 或 Permit 都不能当作业务 Fencing；Research Worker 提交前仍要检查领域 Task 的 Owner、Epoch、Fencing Token、Lease 和冻结快照。

WAITING 和 FAILED 也分开。WAITING 表示外部条件暂时不满足，前端展示 Wait Context，条件恢复后继续；FAILED 才进入 Redrive 或人工处理。旧实现让 Waiting 续租合并并发名额，推荐方案拆为 Admission Reservation 与 Active Permit。Waiting 保留前者、释放后者，既控制租户积压，也不浪费实际执行槽。

用户侧通过 Task Snapshot 和持久事件观察运行。SSE 断线后按 Last Event ID 续播，遗漏终态事件时再用 Task Row 校正。运维侧看 Queue Wait、Waiting Ratio、Lease Lost、Recovered Lease、Provider Permit Wait、Budget Utilization 和各 Workspace 获得执行槽的分布。扩容时先区分入口过载、Worker 不足、Provider 429、数据库锁等待和 SSE 连接压力，再决定扩 Worker、调 Permit、拆资源池或改公平调度。

当前源码能证明状态条件更新、Redis Lua、事务回调、默认关闭本地回退和 Research Permit；持久 Admission Reservation 与 Active Permit 分离是面试推荐方案，仍需迁移实现。多租户加权公平队列只在真实出现跨 Workspace 饥饿时引入，不能把 DRR 当作学校规模的默认组件。

## 13. 重点知识点：速率、并发、预算和背压怎样配合

### 3 分钟回答

速率限制控制单位时间进入多少请求，并发限制控制某一时刻有多少在途工作，预算控制一次任务最多消耗多少总资源。三者的时间维度不同。只做速率限制时，每分钟只允许十个请求，但每个任务运行一小时，系统仍会积累大量并发；只做并发限制时，攻击者可以不断试探和重试，API 与 Redis 自身仍承受高请求速率；只做预算时，一个有足够预算的 Workspace 仍可能瞬间占满全部 Worker。

`[当前实现]` NoteWeave 在创建 Research 或 Artifact Task 时先扣 Actor 与 Workspace 维度的速率 Token，再申请 Workspace 与 Workload 合并并发 Lease。`[目标设计]` Task 与 Admission Reservation 在 MySQL 同一事务提交，Scheduler 开始执行前再申请 Active Permit。进入领域执行后，Research Permit 继续检查 Provider、Workspace、Run 和 Role 的外部调用速率，Budget Reservation 检查本次 Run 的总量。

`[目标设计]` 背压要从最靠近瓶颈的位置向上游传播。Provider 429 不应只在 Worker 内指数重试，因为上游仍会持续生产任务；Coordinator 应减少可运行工作，具备持久等待合同的 Task 才进入可解释 Waiting，入口在队列水位过高时拒绝或给出 Retry-After。当前 Research 429 仍是有限重试和失败分类，不能误写成已经统一持久等待。反过来，数据库只是短暂变慢时，也不能把任务标成 Provider Waiting，指标必须按控制点分桶。

参数不能单独拍。并发上限受 Worker 槽、Provider 并发、数据库连接和每任务峰值内存共同约束；Lease 至少覆盖正常心跳间隔加调度抖动和暂停时间；速率桶容量反映允许突发，补充速率不应超过下游长期吞吐；预算需要从 Gold Set 和真实调用分布估计，并为重试和 Verifier 留余量。调参后同时观察拒绝率、排队 P95、利用率、错误率和单位有效产出，单独追求满负载容易把尾延迟和失败率推高。

## 14. 二阶追问

### Redis 不可用时为什么默认 Fail Closed

多实例下的本地桶和本地 Semaphore 彼此看不到，四个实例各允许四个任务，整体可能变成十六个。Research 和 Artifact 会产生真实 Provider 成本，超卖的损失高于暂时拒绝新任务，所以默认返回 503。已有 MySQL Task 不被删除，恢复后仍可继续。只有本地开发、单实例演示或明确接受超卖时才开启本地回退，并使用更小的本地上限。

### 旧 Quota Lease 300 秒、Research Heartbeat 15 秒是怎么来的

当前合并 Quota Lease 和 Research 执行 Lease 不是一个参数。Quota 默认 300 秒，只是旧 Redis 在途名额的保护值；推荐 Admission Reservation 没有靠 TTL 自动失效的租期。Research Compose 的执行 Lease 是 60 秒、Heartbeat 15 秒，用于较快发现 Worker 失联。执行 Lease 可以从 `Tlease >= Theartbeat * missedHeartbeats + schedulingJitter + maxPause` 推导。若连续允许漏 2 次心跳，调度抖动 10 秒、最大暂停 10 秒，15 秒心跳对应至少 50 秒，60 秒留少量余量。实际参数需要用 Heartbeat Delay P99 和误回收率校准。

### Task 完成与取消同时发生，Reservation 会不会关闭两次

MySQL 条件状态更新决定只有一个终态迁移成功，关闭 Admission Reservation 使用 `ACTIVE -> RELEASED` 条件更新并与终态同事务提交，后到方读取现有终态并按幂等或冲突语义返回。Redis Active Permit 的 `ZREM` 可以重复，第二次返回零，不会释放其他 Task 的 Permit。终态提交成功但 Redis Release 前进程退出时，只等待 Active Permit TTL 回收，不影响持久接纳账本。

### WAITING 是否应该占并发

要先说明并发指什么。Admission Reservation 代表租户最多允许多少个未终结长任务，WAITING 应保留；Active Permit 代表当前有多少 Worker 或 Provider 调用正在运行，WAITING 必须释放。当前通用 Quota 把两种语义合并，是迁移前实现；推荐方案从模型上分开，避免用一个参数同时回答积压和执行容量。

### FIFO 为什么不够公平

某个 Workspace 瞬间提交一百个长 Research 后，FIFO 会让其他课程等待很久；严格短任务优先又可能让长任务饥饿。可以按 Workspace 建队列，用 Deficit Round Robin 发放 Active Permit，并为交互任务保留资源。衡量公平不能只看平均等待，应看每个 Workspace 的 P95 等待、最长饥饿时间和获得服务份额。

### 流量扩大十倍先改什么

先拆指标：入口 Reject 高但 Worker 空闲，可能是参数过紧；Queue Wait 高且 Provider 利用满，应扩 Provider 配额或降低单任务调用；数据库连接满，要减少轮询和缩短事务；SSE 线程或连接成为限制时，迁移到更适合长连接的异步 I/O；Workspace 分布不均时先做公平队列。只有 Task 表和 Event 表达到索引、归档或写吞吐边界后，才考虑分区或拆任务服务。

## 15. 项目八股映射

| 面试知识点 | 项目对象 | 继续下钻 |
| --- | --- | --- |
| Redis 数据结构 | Hash 令牌桶、Sorted Set 并发 Lease | 为什么 Score 用过期时间 |
| Redis Lua | 补充、扣减、清理和判断原子完成 | Cluster 多 Key 如何同槽 |
| Java 并发 | 本地桶同步、Concurrent Map、SSE Executor | 本地保护为何不能替代分布式配额 |
| MySQL 事务 | Task 状态与 Event 同事务、afterCommit 释放 | 外部 Redis 操作为何不放数据库事务中 |
| 乐观并发 | 带前置状态的条件 UPDATE | 更新数为零如何区分幂等和冲突 |
| 分布式系统 | Lease、Heartbeat、Fencing | 三者各自保护哪个失败窗口 |
| 排队论 | Little's Law、队头阻塞、公平调度 | 平均值为何不能替代 P95 |
| HTTP | 429、503、Retry-After、幂等提交 | 哪些错误可以自动重试 |
| 可观测性 | Queue Wait、Waiting、Lease Lost、Budget | 怎样从症状定位限制点 |

## 16. 源码与测试导航

| 主题 | 入口 |
| --- | --- |
| 通用状态机 | `TaskService`、`TaskStateRepository`、`TaskReadRepository` |
| Task SSE | `TaskController`、`TaskEventRepository`、`V095__add_task_event_stream_index.sql` |
| 入口配额 | `WorkloadQuotaService`、`application.yml` 的 `noteweave.quota` |
| Research 执行权 | `ResearchAgentTaskService`、`ResearchAgentPermitService` |
| Provider 限流 | `ResearchAgentRateLimitService` |
| Coordinator | `ResearchAgentCoordinatorTickService`、`ResearchAgentCoordinatorRecoveryService` |
| 预算与 Checkpoint | `ResearchBudgetAndCheckpointService` |
| 状态机测试 | `TaskServiceStateMachineTest`、`TaskQuotaLifecycleTest` |
| Redis 集成 | `WorkloadQuotaRedisIntegrationTest` |
| Research 竞争 | `ResearchAgentMySqlLockMatrixIT`、`ResearchAgentTrustedPermitServiceTest` |

## 17. 当前边界与面试口径

`[当前实现]` 可以讲通用 Task 条件迁移、持久事件、SSE 续播、Workspace/Actor 速率桶、Workspace/Workload 并发 Lease、事务回调释放、Redis 故障默认关闭新请求，以及 Research 领域 Lease/Fencing/Permit。

`[目标设计]` 可以讲 Admission Reservation 与 Active Permit 分离、按 Workspace 的加权公平队列、Provider 自适应并发、统一 Retry-After 和容量自动调节。

`[生产待验证]` 包括考试周峰值、真实 Provider 限额、长期 Lease 丢失率、跨实例 SSE 容量、各 Workspace 的公平性和成本 SLO。默认值和 Compose 行为不能写成生产容量。

## 18. 面试前自检

1. 能区分通用 Task、Research Agent Task、Quota Lease 和 Budget Reservation。
2. 能画出 Task 条件状态迁移，并解释取消与完成竞争。
3. 能解释令牌桶和 Sorted Set Lease 的 Lua 原子性。
4. 能讲清事务回滚、afterCommit 之前宕机和 Redis Release 失败。
5. 能回答 WAITING 是否占并发，并主动指出当前实现与目标设计的差异。
6. 能从 Heartbeat、抖动和暂停推导 Lease，而不是背默认值。
7. 能用 Queue Wait、Waiting Ratio、Permit Wait 和 Lease Lost 判断限制点。
8. 能说明公平队列是演进方案，不把当前 Workspace 配额包装成完整调度平台。

## 19. 重点知识点：多租户公平调度与过载保护

### 3 分钟回答

Workspace 并发上限只能防止单个租户无限占用，不能保证所有活跃租户等待时间接近，也不能解决长 Research 把短 Artifact 或交互 QA 堵住。公平调度需要先定义资源单位。一个 Task 不是一个等价单位，Research 可能占用十分钟和多次 Provider 调用，普通 QA 只持续几秒。若简单按任务数轮询，长任务 Workspace 仍会拿走更多资源。

目标设计先按 Workload 拆资源池，为交互 QA、Artifact 和 Research 设置保留容量与总上限，再按 Workspace 建逻辑队列。Deficit Round Robin 为每个 Workspace 按权重增加 Credit，任务只有在估算成本不超过 Credit 时才能获得 Active Permit；未用完的 Credit 可以有限累积，避免大任务永远进不去。权重来自明确的课程优先级或套餐，不从提交速度推导。任务成本估计错误时，以实际 Service Time、Provider Permit 和 Token 反馈校准，不能让用户通过伪报轻任务绕过治理。

`[目标设计]` 严格优先级容易让低优先级 Research 永久饥饿，所以要设置最大等待时间和 Aging。短任务专用池改善交互延迟，但还要有共享突发池，否则 QA 空闲时资源不能被后台任务使用。Provider 429、Hikari Pending 或 Worker 内存达到阈值时，调度器减少 Active Permit，并把 Retry-After 和 Waiting Reason 向入口传播；只有已有持久等待合同的能力产生 Waiting Reason。过载保护的目标是有界排队和可解释拒绝，不是让所有请求都进入系统后一起超时。

验证公平性不能只看全局平均等待。至少按 Workspace 和 Workload 报 Queue Wait P50/P95/P99、最长饥饿时间、获得 Active Permit 的份额、完成成本份额和 Deadline Miss。构造一个重租户持续提交长 Research、多个轻租户间歇提交短任务的开放模型，检查轻租户是否仍在截止时间内得到服务，重租户是否获得与权重相符的长期份额。当前实现只有 Workspace/Workload 并发 Lease，完整 DRR、成本反馈和 Aging 仍是 `[目标设计]`。

### 二阶追问：为什么不直接使用优先级队列

单个全局优先级队列只表达任务等级，不能保证同优先级租户之间公平，也容易让高优先级流量持续压制后台任务。还要处理队头任务暂时拿不到 Provider Permit 的情况，不能让它阻塞后续可运行任务。优先级适合作为 Workload 权重或截止时间因素，租户份额仍需独立调度规则。

### 二阶追问：如何防止成本估计不准导致不公平

初始成本用任务类型、资料量、计划 Cell、文件页数和历史分位数估计，执行后用实际 Service Time、Token、Provider 调用和重试修正。调度 Credit 使用上限截断，异常大任务拆成可抢占 Wave。估计误差本身要监控，若持续低估某类任务，就降低该类并发或提高 Debit，不能只在任务结束后记账。
