# Research Agent MA4B：Lease Reaper、取消与 DLQ 执行方案

> 状态：IMPLEMENTED / VERIFIED（2026-07-17）  
> 前置：MA4A transport 已完成；默认 agent consumer 仍关闭，尚无真实 role executor  
> 目标：让分布式 task 在 worker crash、重复投递和用户取消时可解释、可恢复、不可错误合并

## 1. 当前差距

`ResearchAgentTaskService.expireLeases` 只能由 internal endpoint 手动调用：它把过期 lease 标为 `EXPIRED`，但没有调度器、重试时间/上限、run 取消传播、DLQ 可查询投影或 Worker drain 协议。`research_agent_task` 只有 `attempt_count`，缺少 `next_attempt_at`、终态 reason 和取消来源；agent command DLQ 当前只是 Kafka topic，Backend 无法把失败与 task/run 审计关联。

## 2. 状态机与线性化规则

```text
PENDING/RETRY_WAIT/EXPIRED --claim--> CLAIMED/RUNNING --submit--> SUBMITTED
CLAIMED/RUNNING --lease expired--> RETRY_WAIT | FAILED
PENDING/RETRY_WAIT/EXPIRED --run cancel--> CANCELLED
CLAIMED/RUNNING --run cancel--> CANCELLED (旧 lease 立即失效，submit 被拒绝)
任何非终态 --retry exhausted--> FAILED
```

* `cancel run` 是线性化点：同一事务把所有非终态 agent task 标为 `CANCELLED`，释放未使用 reservation；之后 claim、heartbeat、submit 一律拒绝。
* reaper 只处理 `CLAIMED/RUNNING` 且 lease 已过期的 task。若 `attempt_count < max_attempts`，转 `RETRY_WAIT` 并写确定性 backoff；否则 `FAILED`。不可把它直接重置为 `PENDING`，否则时间语义、审计和退避丢失。
* 每次 retry 必须创建新的 command outbox delivery（不能受现有 task 唯一 outbox 约束阻断）；因此须将 `research_agent_outbox` 的唯一性从 task 级改为 `task + delivery_no`，或新建 delivery 表。迁移要先验证既有 V042 数据兼容。
* Kafka DLQ 只表示 transport failure；新增 append-only `research_agent_delivery_failure` 投影，保存 task/run/command/delivery、reason code、sanitized digest、attempt、时间和 redrive 状态，不保存 secret/正文。

## 3. 数据迁移与服务边界

新 Flyway（暂定 V043）应增加：

| 对象 | 字段/约束 |
| --- | --- |
| `research_agent_task` | `next_attempt_at`、`max_attempts`、`terminal_reason`、`cancelled_at`；ready claim 查询索引 |
| `research_agent_outbox` | 支持每 task 多 delivery 的唯一键与 `delivery_no`；旧 delivery 默认 1 |
| `research_agent_delivery_failure` | append-only failure/redrive 审计，FK 到 run/task/outbox |

新增 Backend `ResearchAgentLifecycleService`：`reapExpiredLeases(now)`、`cancelRun(runId, reason)`、`recordDeliveryFailure(...)`、`redriveDelivery(...)`。Scheduler 只调用该 service；Worker 从 MA4D 起在 heartbeat/claim 响应中读取 cancellation，而非自己修改 task 状态。

## 4. TDD 红灯清单

1. 两个过期 task：一个剩余 retry 转 `RETRY_WAIT` 且延迟递增，一个超过 max attempt 转 `FAILED`；旧 fence 永远不可 submit。
2. run cancel 与 concurrent submit：事务后 task 必为 `CANCELLED`，不存在 execution/merge 成功；重复 cancel 幂等。
3. cancel 前已 SENT 的 command 重投，claim 返回正常不可领取，consumer 不创建 DLQ 噪声。
4. retry task 可创建第二条 delivery；旧 delivery 不可重复发送，新 delivery 包含递增 delivery attempt。
5. poison/重试耗尽 DLQ 由 Worker 上报或 Backend 记录时，投影不含 API key、prompt、网页正文；相同 failure key 重放幂等。
6. scheduler 在 feature flag 关闭时不运行；手动 `now` 注入保证测试无 sleep。

## 5. 放行与回滚

MA4B 的 DoD：Flyway、task/outbox/failure 审计、状态机服务、scheduler 配置、cancel internal API、故障注入与 projection 测试全绿；Compose 至少验证 reaper 不影响 `SEQUENTIAL_V1`。任一异常则停止 scheduler/agent consumer，保留审计；不删除 candidate、execution、merge 或预算记录。

MA4B 仍不启用真实 provider/多 Worker。MA4C 才处理跨进程 Redis 限流和预算结算，MA4D 才提供 executor、heartbeat/drain 以及真实工具链。

## 6. 实施记录（进行中，2026-07-15）

已完成并验证：

* V043 添加 retry/cancel 状态、`delivery_no` 与 delivery-failure 审计表；迁移已在 H2/Flyway 成功执行。
* `ResearchAgentLifecycleService` 将过期 lease 按 attempt/max-attempt 转为 `RETRY_WAIT`（指数退避）或 `FAILED`；后者释放任务 reservation。retry 使用既有 Outbox 行递增 `delivery_no` 并重置 READY，避免破坏旧外键依赖的 task 唯一性。
* cancel run 在单事务内取消非终态 agent task、失效 READY command、释放 reservation；旧 lease 随后不能 submit。
* `ResearchAgentLifecycleScheduler` 默认关闭，只在显式 `noteweave.research.agent.lifecycle-reaper-enabled=true` 时运行。
* 新增内部 lifecycle API，包含 cancel、delivery-failure append 与 redrive；failure 仅接收稳定 reason/digest/attempt，不接收未脱敏异常正文。failure key 按 task 幂等，redrive 通过 Outbox `delivery_no` 重投。
* Worker agent consumer 在**成功写 Kafka DLQ 之后、提交 offset 之前**调用 failure projection；projection 失败同样停止消费且不 commit。原始 schema poison 因无可信 task identity 只进 Kafka DLQ，不创建伪归属 failure。
* 验证：`ResearchAgentLifecycleServiceTest` 4/4、`ResearchAgentTaskServiceTest` 3/3、`ResearchAgentCommandOutboxServiceTest` 2/2 通过（均关闭 Kafka listener）。
  Worker contract/client/agent-consumer/legacy-consumer 定向回归 `33 passed`。

完成证据：Worker durable DLQ failure 上报、`research_agent_delivery_failure` 投影/redrive API、显式 cancellation internal controller、lease reaper 和 cancel/submit/reaper 并发故障测试均已落地。默认 Coordinator scheduler 会在每轮 tick 前执行过期 lease 回收；独立 lifecycle reaper 仅作为 Coordinator 关闭时的可选运维入口，避免默认启用两个重复扫描器。
