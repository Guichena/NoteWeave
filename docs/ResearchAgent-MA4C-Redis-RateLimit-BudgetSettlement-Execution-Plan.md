# Research Agent MA4C：Redis 限流与预算结算执行方案

> 状态：IMPLEMENTED / VERIFIED（受控范围，2026-07-17）  
> 前置：MA3 reservation/checkpoint、MA4A transport、MA4B lease/cancel/DLQ  
> 目标：多 Worker 下以 Redis 原子限流和 Backend budget ledger 约束真实工具调用；任何重投不得双扣额度

## 1. 核查结论

现有 `WorkloadQuotaService` 提供 workspace+actor+workload token bucket 和并发 lease，但 Redis 故障会退化为本地内存 bucket。它服务普通任务，既没有 provider/run 维度，也没有 Research task reservation/actual usage 的幂等结算语义。因此 MA4C 不能直接调用它并声称跨进程 Research 限流已完成。

## 2. 目标模型

每个已 claim 的 Agent task 在调用 Search、Fetch、Read 或 LLM 前，需原子通过：

```text
provider bucket  AND  workspace bucket  AND  run+role bucket
```

键必须由 Backend 签发的 task snapshot 生成，不接受 Worker 自报的 workspace/provider。Redis 不可用时的默认策略为 **fail-closed**：拒绝新外部工具调用、保留 lease 让 coordinator/reaper 恢复；仅显式开发/单进程模式可允许降级，且必须可观测。

预算守恒：

```text
reservation.reserved = consumed + released + still_reserved
same (task_id, execution_key) settlement consumes once
cancel / retry-exhausted releases once
usage > reserved is rejected before canonical merge
```

## 3. 改造范围

| 层 | 改动 |
| --- | --- |
| Backend | `ResearchAgentRateLimitService` 使用 Redis Lua 一次检查/扣减三 bucket；限制 provider/workspace/run/role；不复用本地 fallback |
| Backend | `ResearchAgentBudgetSettlementService` 以 `(task, execution_key)` 做幂等 settle/release，绑定 task 终态与 reservation |
| Contract | claim snapshot 增加受信 `workspace_id`、provider profile、rate-limit policy/version；不得由 command 携带 |
| Worker | 工具 adapter 在每次外部调用前请求 permit；429/5xx 按 retry policy，不把失败调用伪报为成功 usage |
| Redis | Lua 返回 GRANTED / LIMITED / UNAVAILABLE，TTL 防遗留 key；指标记录所有维度但不暴露 secret |
| Tests | 两 worker 竞争、Redis down fail-closed、重复 settlement、cancel/retry-exhausted、429 budget 不双扣 |

## 4. TDD 红灯清单

1. 相同 provider/run 的两个 worker 并发请求，超过容量时恰好一个被拒绝；workspace 限制独立生效。
2. Redis exception 默认抛 `RESEARCH_AGENT_RATE_LIMIT_UNAVAILABLE`，无本地 fallback；显式开发配置才允许 fallback 且记录指标。
3. 不同 role 使用不同并发上限；释放/lease 过期可恢复 capacity。
4. 一个 execution 重放 settlement 两次：只产生一次消费；usage 超 reservation 被拒绝，reservation 不进入错误终态。
5. cancel 与 retry-exhausted 各释放一次；先 settle 再 release 的 released 等于 reserved-consumed。
6. Worker 在 permit denied 时不调用真实 adapter，并将可重试/不可重试原因传入终态 usage。

## 5. 放行条件

Redis 集成测试使用真实 Redis；至少两个 Worker 实例/两个独立 client 竞争同一 bucket。必须证明 Redis down 默认 fail-closed、budget 守恒、无重复扣费和不影响 `SEQUENTIAL_V1`。通过前不得开启 MA4D 真实 provider executor 或多进程 consumer。

## 6. 实施记录（进行中，2026-07-15）

已完成：独立 `ResearchAgentRateLimitService` 使用一个 Redis Lua 脚本原子检查并扣减 provider、workspace、run+role 三个 bucket；Redis 不可用默认抛 `RESEARCH_AGENT_RATE_LIMIT_UNAVAILABLE`，不复用普通任务的本地 fallback。单元 fail-closed 测试与真实 Redis 两个独立 service 实例竞争同一 capacity=1 bucket 的集成测试均通过。

完成范围：task snapshot 的受信 provider/workspace policy、Worker 工具 adapter permit 接线、`(task, execution_key)` 预算结算幂等键、cancel/retry-exhausted 释放、Redis unavailable fail-closed，以及 `granted/limited/unavailable/local_development` 低基数指标均已落地。简历项目不再额外引入 acquire/release 型 role concurrency lease；并发上限由 durable taskization、active cell binding 和 `run+role` Redis bucket 共同约束，避免新增一套容易泄漏的第二 lease 状态机。
