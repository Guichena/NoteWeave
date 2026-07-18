# 阶段 2：Execution、Outbox 与并发基础设施

> 目标：统一主项目长任务的状态、尝试、可靠投递、回调幂等、并发隔离和观测。  
> 非目标：普通 QA 不强制创建 Execution；不设计 Research/Artifact Worker 内部编排。

## 1. Execution 控制面

### 1.1 数据模型

建议新增而非重命名旧 Task：

| 表 | 核心字段 |
| --- | --- |
| `execution` | id, workspace_id, kind, status, target_type/id, input_ref, result_ref, current_attempt, version, created_by, timestamps |
| `execution_attempt` | execution_id, attempt_no, status, fencing_token, worker_id, lease_until, heartbeat_at, error_code/detail, started/finished_at |
| `execution_event` | event_id, execution_id, attempt_no, event_type, phase, progress, payload_json, occurred_at |
| `outbox_message` | id, aggregate_type/id/version, event_type, payload, status, lease, attempts, next_attempt, published_at |
| `command_receipt` | consumer, command_id, scope, status, result_ref, timestamps；仅无自然幂等场景 |

旧 `task` 在兼容期成为 read model：新 Execution 状态通过适配器投影为旧 TaskResponse，禁止新旧两边都作为写入真相。

### 1.2 状态机

```text
PENDING -> DISPATCHABLE -> RUNNING
RUNNING -> WAITING | SUCCEEDED | FAILED | CANCELLING
WAITING -> DISPATCHABLE | RUNNING | FAILED | CANCELLING
CANCELLING -> CANCELLED | SUCCEEDED | FAILED
FAILED(retryable) -> DISPATCHABLE with new attempt
terminal -> no transition
```

所有状态迁移使用 `version` CAS。重试必须创建新 attempt 和 fencing token；旧 attempt 的 heartbeat/progress/result 一律拒绝，但可写安全审计事件。

## 2. 通用 Outbox

### 2.1 组件

```text
OutboxRepository
OutboxClaimPolicy
OutboxDispatcher
DeliveryAdapterRegistry
  - KafkaDeliveryAdapter
  - HttpDeliveryAdapter (确有外部 HTTP command 时)
DeadLetterService
OutboxAdminQuery
```

领域事务只调用 `OutboxPort.append(message)`。Dispatcher 通过 lease/CAS 成批 claim，事务提交后发送；成功条件更新为 PUBLISHED，失败计算 next_attempt。一个 outbox row 同一时刻只能被一个 owner claim。

### 2.2 消息契约

Envelope 至少包含：`event_id`、`event_type`、`schema_version`、`occurred_at`、`producer`、`correlation_id`、`workspace_id`、`aggregate_type/id/version`、`execution_id/attempt_no`（若适用）、`payload`。

Payload 字段使用稳定 snake_case 或 camelCase 中的一种，并由 JSON Schema/契约测试锁定；消费者忽略未知字段，小版本只能追加可选字段，破坏变更提升 schema_version/topic。

浏览器和运维端使用的 Execution event 也采用同一版本化 envelope，并增加单调 `sequence` 与恢复 `cursor`。前端 reducer 必须能处理重复、迟到、未知可选字段和终态后事件，不能依赖到达顺序猜测状态。

### 2.3 不使用全局 Inbox

- Source/ES：document id + snapshot/version upsert；
- Wiki：checkpoint 唯一约束；
- Worker：execution claim + fencing；
- Callback：callback receipt 唯一键；
- 邮件/支付类不可逆外部 command：独立 command receipt。

只有某个消费者的副作用确实没有自然幂等键时，才为该消费者建 receipt；不把所有事件复制到万能 Inbox。

## 3. Worker 边界

主项目向 Worker 投递 `ExecutionCommand`，Worker callback 必须携带 execution id、attempt no、fencing token、callback id 和进度/结果。主项目事务顺序：先 insert callback receipt；验证 attempt；应用状态；写 execution event；必要时写下一条 Outbox；统一提交。

重复 callback 返回原处理结果；旧 fencing token 返回 `STALE_ATTEMPT` 且不修改状态。Worker 内部 checkpoint 属于 Worker，本阶段只规定 result/checkpoint reference 的契约。

## 4. 线程池和背压

建立 `BusinessExecutorConfiguration`：

| Bean | 建议初始配置方法 | 拒绝处理 |
| --- | --- | --- |
| answerIoExecutor | 核心数按并发 LLM 上限，小有界队列 | API 返回 overload 或降级模型 |
| sourceIoExecutor | 受 MinIO 连接池/文件数约束 | Execution 延后重试 |
| projectionExecutor | 受 ES bulk 并发约束 | 不 ack Kafka |
| sseDispatchExecutor | 与回答执行完全隔离 | 断开慢客户端并保存 cursor |
| maintenanceExecutor | 1-2 线程，小队列 | 下轮 scheduler 重试 |

禁止直接在业务方法上随意加 `@Async`。Application Service 显式提交具名 executor，并传播 MDC/SecurityContext/correlation。持久任务提交被拒绝时必须保持为可重试状态；`CallerRunsPolicy` 只能用于能接受上游阻塞的内部维护任务。

容量计算记录：外部连接上限、平均/尾延迟、目标吞吐、队列等待预算。队列不是容量，排队时间超过任务 SLO 时应拒绝或延迟调度。

## 5. Kafka 配置

- Producer：`acks=all`、`enable.idempotence=true`、受控 retries、delivery timeout；
- Consumer：按业务语义分 group，手动/record ack，失败交由统一 error handler；
- DLT：原始 envelope、错误分类和 headers 完整保留；
- 同 aggregate 需要顺序时用 aggregate id 作为 key；
- consumer concurrency 不超过 topic partition 和下游容量；
- 监测 lag、rebalance、retry、DLT、处理延迟。

Kafka 只用于可靠命令/投影。Answer token、浏览器 SSE、进程内短编排不经过 Kafka。

## 6. 可观测性

每次 Execution 可查询时间线：created、dispatched、claimed、heartbeat、phase、waiting、retry、completed/failed/cancelled。事件 payload 大对象存引用，不无限写 JSON。

指标：

- execution 各状态数量、运行时长、retry/cancel/stale callback；
- outbox backlog、oldest age、claim conflict、publish latency、dead/redrive；
- executor active/queue/rejected/task latency；
- Kafka publish error、consumer lag、DLT；
- callback duplicate、invalid fencing、latency。

## 7. 迁移顺序

1. 新建 Execution/Attempt/Event 与新版 Outbox 迁移；
2. 抽出通用 Dispatcher，先影子读取旧 Outbox 但不发送；
3. 选择 Source parse 作为首个迁移领域；
4. 新写入只进新版 Outbox，旧 Task 由 Execution 投影；
5. 验证后迁移 Wiki/rebuild 和 Worker 边界；
6. 停旧 Dispatcher，确认 backlog 清零后删除写入口；
7. 接入五类线程池和指标；
8. 增加 admin query、DLT redrive、cancel/retry API。

### 7.1 Retry、Redrive 与 Replay 的边界

- retry：同一 Execution 因可重试错误创建新 attempt；
- redrive：将 DEAD/DLT 消息重新送回原消费者，仍受原幂等键约束；
- replay：从明确 checkpoint 创建一个可审计的新 Execution，用于版本升级、人工修复或重算。

Replay 必须记录 source execution、checkpoint、代码/策略版本、操作者和理由。包含邮件、外部发布、覆盖写等不可逆副作用时，Replay 默认停在 `WAITING_APPROVAL`，不能把“重新发送消息”包装成恢复能力。

Research/Artifact 只迁移主项目边界，不改 Worker 内部算法。

## 8. 测试

- 20 个 Dispatcher 并发实例 claim，同一 row 同时只有一个 lease owner；
- publish 成功但状态未落库时崩溃，重复投递由消费者幂等吸收；
- lease owner 崩溃后可被新实例接管；
- retry 产生新 attempt，旧 callback/heartbeat 不改变状态；
- callback 重复 100 次只应用一次；
- Kafka DLT 可按 event id redrive 且保留 correlation；
- executor 队列满时 API、持久任务、投影和 SSE 分别按设计降级；
- 关闭一个实例，多实例任务和事件不丢失。

## 9. 验收与回滚

完成标志：所有主项目长任务拥有 Execution/Attempt；Outbox 只有一个发送者；重复、崩溃、乱序和迟到 callback 均有自动化证明；线程池隔离和关键指标上线。

回滚时先关闭新入口和 claim，再让进行中 attempt 完成或租约过期；保留所有新表数据。兼容层可继续从 Execution 投影旧 Task API，但不得恢复双写真相。
