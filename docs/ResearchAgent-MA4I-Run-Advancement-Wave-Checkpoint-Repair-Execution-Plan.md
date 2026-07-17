# Research Agent MA4I：Run 推进、Wave Barrier、Checkpoint 与 Repair 执行方案

> 状态：I1–I4 `IMPLEMENTED / VERIFIED`；I5 的 mode-handoff guard 与自动 coordinator 数据库切片 `IMPLEMENTED / VERIFIED`；coordinator process-kill / Kafka 隔离恢复矩阵仍 `IN PROGRESS`（2026-07-17）。  
> 前置：MA4G 提供 immutable atomic completion、CAS、budget settlement；MA4H 负责 execution lease、drain、trusted permit。MA4I **不**生成最终报告、不将 Run 收口为 `COMPLETED`，这些属于 MA4J。  
> 目标：让 `INCREMENTAL_V1` Run 在每一 wave 的 task 到达可解释终态后，由单一、可重放的 Coordinator 决定 checkpoint、下一 wave、repair/counterfactual 或可解释的 partial/failure 停止。

## 1. 当前基线与缺口

现有 `ResearchAgentTaskCoordinatorService` 可按稳定 fingerprint 创建/dispatch task；`ResearchAgentCompletionCommitter` 可原子写 execution、evidence、candidate、CAS、budget 和 task terminal；`ResearchBudgetAndCheckpointService` 已有 budget/checkpoint 基础表与单调 high-water 字段。

当前尚未闭合的能力是：completion 后没有受控的 Run advancement；没有 wave barrier/gap decision；checkpoint 不是 coordinator 的 authoritative progress cursor；repair/counterfactual 不能由已验证的缺口确定性派生；不同 execution mode 的接管边界未形成线性化协议。

## 2. 范围、非目标与不变量

范围：仅 `research_run.agent_execution_mode = INCREMENTAL_V1` 的 Coordinator 推进协议、task/wave 状态、checkpoint、repair taskization、budget/stop 条件与恢复。

非目标：SYNTHESIS/report、父 task 收口、真实 provider 质量/成本评测、生产灰度；不改变 MA4H 的 permit/lease 语义，也不重新实现 completion transaction。

必须成立：

- 同一 Run 同一时刻至多一个 advancement owner；worker completion 与 advancement 均按固定锁顺序 `run -> task/cell`，不得产生死锁或双推进。
- 一个 wave 的 barrier 只在全部任务处于 terminal/explainable 状态时穿越：`SUBMITTED`、显式 `FAILED`、`CANCELLED`、`EXPIRED` 且 retry/repair 决策已固化；`PENDING/CLAIMED/RUNNING/RETRY_WAIT` 一律阻断。
- checkpoint sequence 单调、内容不可变；其 high-water、ledger digest、decision digest 和前驱 checkpoint 必须可重算。重复调用返回同一 receipt，不能新建 checkpoint/预算 reservation/outbox。
- next-wave/repair task 的 `task_key + idempotency_key` 由 run、parent checkpoint、target binding、role、policy version、reason digest 决定，不能由 LLM 或 wall-clock 决定。
- 任何 budget/deadline/cancel/terminal guard 命中时，Coordinator 不再创建普通 task；只写可解释 checkpoint/stop decision。MA4J 才决定最终 report/Run terminal。
- legacy `SEQUENTIAL_V1/V2`、`LOCAL_PARALLEL` Run 不能进入该入口；已经有 incremental task、checkpoint 或 active lease 的 Run 不得切换回 legacy。模式切换是独立、审计化、CAS guarded 的操作。

## 3. 状态机与线性化点

```text
TASK completion/reaper
      |
      v
WAVE_OPEN --all terminal--> ADVANCING (claim coordinator lease)
      |                            |
      | active task                +--> write immutable checkpoint Cn
      v                            |
WAITING <--------------------------+---> STOPPED_PARTIAL (budget/deadline/cancel)
                                   |
                                   +---> TASKIZED_NEXT_WAVE (N+1 normal tasks)
                                   |
                                   +---> TASKIZED_REPAIR (same/next wave counterfactual tasks)
                                   |
                                   +---> READY_FOR_SYNTHESIS (MA4J-owned handoff only)
```

线性化点是：持有 Run advancement lease、`SELECT research_run ... FOR UPDATE` 后，对当前 wave terminal set、latest checkpoint sequence、budget checkpoint 与 cell high-water 建立同一事务快照；在该事务中写入 decision/checkpoint 及幂等 task/outbox rows。提交成功即为唯一推进；HTTP 响应丢失/调度器重复只重放同一 receipt。

建议新增 durable `research_agent_run_advancement`（或在既有 checkpoint 扩展）记录：`run_id`、`advance_key`、`owner`、`lease_epoch`、`lease_expires_at`、`input_checkpoint_seq`、`decision_kind`、`decision_digest`、`output_checkpoint_seq`、`created_at`。若复用既有表，仍须满足唯一键 `(run_id, advance_key)` 与 owner/fence audit。

## 4. Deterministic decision contract

输入只能来自锁定 snapshot：当前 wave task summary、accepted/rejected cell CAS、citation/global verifier projection、budget reservation/settlement、run deadline/cancel、latest checkpoint high-water。

输出优先级固定：

1. run cancelled/terminal 或 deadline 到达 → `STOPPED_CANCELLED` / `STOPPED_DEADLINE`；
2. remaining budget 小于最小 repair reservation → `STOPPED_BUDGET_EXHAUSTED`；
3. required cell 已 verified、citation audit passed、无 blocking conflict → `READY_FOR_SYNTHESIS`；
4. conflict/citation weakness/required gap 且 `repair_count < max_repair_per_cell` 且 budget 足够 → `COUNTERFACTUAL`/`EVIDENCE_AUDIT` repair tasks；
5. 可继续的信息缺口 → 下一 normal wave；
6. 无安全行动且仍有未满足 required cell → `STOPPED_PARTIAL`，列出 per-cell reason。

排序：按 required-first、依赖层级、risk bucket、cell key、reason digest 排序；task bundle 大小、最大 repair 数、最小 reservation、deadline safety margin 是 server-side policy，写入 checkpoint 的 `policy_version`。

## 5. TDD 切片与红灯顺序

### I1：read-only barrier 与 decision canonicalizer

先写红灯：active task 阻断、terminal mixed set 可解释、相同输入不同 map insertion order 给出同一 digest、budget/deadline/cancel 优先级。实现纯 `WaveBarrierEvaluator`/`AdvanceDecisionCanonicalizer`，无 DB 写。

### I2：Run lease、checkpoint 幂等与单调 high-water

先写红灯：并发两个 owner 仅一方获得 receipt；重复 advance 返回同一 checkpoint；旧 high-water/错误 predecessor 拒绝；run mode 非 incremental 拒绝。实现 run lock helper、advance key、fenced coordinator lease 与 transaction receipt。

### I3：normal next wave taskization

先写红灯：同一 decision replay 不增加 task/reservation/outbox；task key 对排序稳定；一个 insert 失败回滚 checkpoint/task/outbox。实现 server-side task command builder，复用 MA4E snapshot/task creation，不让 Worker 提供 scope。

### I4：repair/counterfactual 与 stop contract

先写红灯：conflict 生成独立 source exclusion 的 `COUNTERFACTUAL`；达到 repair limit/budget 下限不生成新 task 而写 partial；repair cannot target frozen/foreign cell。实现 gap projection、repair reason digest 与 per-cell cap。

### I5：crash/recovery 与 mode handoff

先写红灯：checkpoint commit 后 response loss、taskization 中间 crash、coordinator lease expiry、重复 scheduler tick、legacy/incremental race。实现 durable replay receipt、reaper/dispatcher integration 和 mode transition audit guard。

I5 执行夹具复用：优先基于 `scripts/ma4h/run-heartbeat-fault.ps1`（DB-clock expiry/reclaim/Kafka）、`scripts/ma4h/run-drain.ps1`（SIGTERM/drain/recovery/Kafka）和 MA4G G2 的数据库连接中断夹具扩展，不得新建没有 MySQL/Kafka cleanup manifest 的替代 harness。新增断言必须区分三层：worker task lease recovery 已由 MA4H 证明；advance key 的 response-loss replay 已由 I4d 证明；**coordinator 进程崩溃后的 scheduler/replay 恢复仍需单独证明**，不能以前两者替代。

I5 scheduler 前置契约：scheduler 不得接收 wave、cell scope、budget、decision digest 或 repair target；它只能提交 run id/coordinator identity，并在锁定的 Run snapshot 中派生最新 checkpoint、terminal wave、high-water、gap、policy 和 advance key。若没有形成这条 authoritative snapshot seam，禁止接入任何自动 advancement scheduler。

### I5-R：精炼版自动 Coordinator（本轮执行）

`INCREMENTAL_V1` 是唯一启用条件；不再引入 workspace/run allowlist、手动 dispatch 或第二个 feature flag。固定间隔的 scheduler 只扫描该模式下仍处于 `RUNNING` 的 Run，并逐个提交 `runId + coordinatorInstanceId`。一次 tick 的顺序固定为：DB-clock lease reaper → `research_run FOR UPDATE` authoritative snapshot → active task 则 no-op → 首 wave taskization → terminal barrier 的 normal/repair/stop 决策 → 事务 outbox。任何进程在提交前崩溃均由下一 tick 从 durable state 重放；提交后重放必须返回原 receipt，不能重复写 checkpoint、task、budget reservation 或 outbox。

本项目的边界是单数据库、无状态、可重启的 coordinator；不实现 leader election、多地域高可用、scheduler shard 或真实 provider 的成本 exactly-once。命令投递仍经既有 outbox，不能在锁事务里直接发送 Kafka；当 Kafka transport 被明确禁用时，仅关闭投递泵，不关闭 coordinator 的 durable 决策。TDD 顺序为：① run-lock snapshot 和首 wave exactly-once；② active/replay no-op；③ reaper 后恢复；④ terminal wave 的 server-derived normal/repair/stop；⑤ 两 coordinator 与 SIGTERM/restart 隔离夹具。

## 6. 数据库、接口与调度边界

- migration 必须 fresh/legacy MySQL 双验证；新增 uniqueness/index 的设计要支持 `run_id + checkpoint/advance key` 查询与 lease reaper。
- 新内部 route 只接收 run id + coordinator identity + immutable expected cursor；任何 wave/role/budget/cell scope 都从 lock snapshot 派生。外部用户 route 不直接驱动 advance。
- scheduler 由 `INCREMENTAL_V1` 自动启用，不使用 workspace/run allowlist 或手动 dispatch；使用固定 run interval，完成 task/reaper 后仅发布 coalesced advance signal，不能在 completion transaction 内嵌递归 advance。
- rate/budget：repair reservation 必须在 task creation transaction 中落库；无法 reservation 时 decision 改为 stop checkpoint，不能先创建无预算 task。

## 7. 隔离退出门

每轮使用新 `noteweave-ma4i-*` Compose project、真实 MySQL/Kafka、deterministic fake provider、零宿主端口和 project-scoped cleanup：

| Round | 注入 | 必须证明 |
| --- | --- | --- |
| I1 | normal wave tasks all submitted | exactly one checkpoint、deterministic next wave task/outbox、no duplicate on advance replay |
| I2 | one task failed/expired + repairable gap | one counterfactual repair with server-derived exclusion/scope；原 wave 不重写 |
| I3 | budget exhausted/deadline/cancel | no new task/reservation/outbox；one explainable partial/stop checkpoint |
| I4 | kill coordinator after checkpoint/taskization transaction before response | restart/replay returns same receipt; counts/digests unchanged |
| I5 | two coordinators + legacy mode mutation attempt | one fenced winner；legacy mutation rejected；Kafka lag/DLQ and cleanup evidence preserved |

每轮保存 compose config/image digest、MySQL version/isolation、task/checkpoint/decision digests、budget rows、Kafka lag/DLQ、failure marker 和 cleanup manifest。fake provider 只证明控制/一致性，不证明真实 provider 质量、取消或成本 exactly-once。

## 8. MA4I 完成声明边界

仅当 I1–I5、Backend/Worker 定向回归、migration 验证和全部隔离退出门通过，才可声明：`INCREMENTAL_V1` 的 wave/checkpoint/repair 推进在受控 fake-provider 夹具中可重放、可恢复、不会与 legacy 执行面双写。仍不得声明 MA4J report finalization、真实 provider 质量/性能或生产就绪。

## 9. 已验证证据账本

| 切片 | 验证结论 | 证据 |
| --- | --- | --- |
| I1：barrier / decision canonicalization | active task 阻断、未固化 expired recovery 阻断、可解释终态集打开 barrier；同一 decision 的 cell/fact 输入顺序不影响 canonical JSON/digest。 | `scripts/ma4i/evidence/noteweave-ma4i-i1-20260717t0900d-20260717T075025Z`：manifest `VERIFIED`，`ResearchAgentWaveBarrierTest` 4/4 green，cleanup 为空。该证据仅覆盖纯决策契约，不证明 database coordinator、checkpoint 或 provider 行为。 |
| I2：advancement receipt / checkpoint replay | Run lock 串行化 coordinator；相同 advance key/digest 重放同一 checkpoint receipt；stale predecessor 与 legacy mode 拒绝；两个 coordinator 从同一 predecessor 同时推进时恰一方提交。 | 真实 MySQL 8.4、Flyway v048 定向 Maven：`ResearchAgentRunAdvancementServiceTest` 3/3 green（project `noteweave-ma4i-i2-20260717t0920b`），测试后 container/volume/network 已清理。该轮覆盖 receipt/checkpoint 事务语义，不覆盖 I3 task/outbox 写入或 I4 repair。 |
| I3：normal next-wave taskization | server-side advancement 与 wave-2 task/reservation/outbox 同一事务提交；相同 decision replay 不重复创建；wave 号进入 task fingerprint；outbox 写入失败时 advancement row、agent checkpoint、task、reservation、outbox 全部回滚。 | 真实 MySQL 8.4、Flyway v048 定向 Maven：`ResearchAgentTaskCoordinatorServiceTest` 10/10 green（project `noteweave-ma4i-i3-20260717t0950c`），结束后 container/network 已清理。该轮证明数据库原子性与重放，不证明跨进程 publish 或 I4 repair/stop 决策。 |
| I4a：repair / stop policy | cancellation、deadline、repair-budget stop 优先于任何 repair；可 repair gap 产生按 cell key/reason digest 稳定排序的 `COUNTERFACTUAL` target 与 source exclusion；frozen、foreign、repair-capped cell 一律不能成为 target，且无安全 target 时写 `STOPPED_PARTIAL`。 | 真实 MySQL 8.4 隔离 Maven：`ResearchAgentRepairStopPolicyTest` 3/3 green（project `noteweave-ma4i-i4-policy-20260717t1000a`），结束后 container/network 已清理。该轮仅证明纯策略契约，不证明 gap projection 或恢复。 |
| I4b：counterfactual taskization | Coordinator 在 run lock 下重新读取 target cell 与 trusted source scope；target 必须属于该 Run、非冻结/verified、没有 active task；排除 source 后必须仍有独立 source。task/reservation/outbox 的 key 含 parent checkpoint、wave、cell version、reason digest、excluded source；首次成功创建才递增 per-cell `repair_count`，重放不重复写入/计数；outbox 故障整笔回滚。 | 真实 MySQL 8.4、Flyway v048 定向 Maven：`ResearchAgentTaskCoordinatorServiceTest` 13/13 + `ResearchAgentRepairStopPolicyTest` 3/3（合计 16/16，project `noteweave-ma4i-i4-20260717t1040e`），结束后 container/network 已清理。尚未把 policy 输出从 authoritative gap projection 自动生成并与 advancement/checkpoint 同事务绑定。 |
| I4c：authoritative gap projection | 只从同 Run、同 wave 的 `FAILED/EXPIRED` task 的 immutable `target_cells_json` 与 `execution_context_json` 派生 repair cell、失败 task、reason digest、被排除 source；不读取 Worker repair 入参。 | 真实 MySQL 8.4、Flyway v048 定向 Maven：`ResearchAgentTaskCoordinatorServiceTest` 14/14 green（project `noteweave-ma4i-i4-gap-20260717t1100a`），结束后 container/network 已清理。尚未与 policy/advance/taskization 组成一个单一事务。 |
| I4d：repair advancement binding | 从 server-derived failed-wave gap 生成稳定的 counterfactual decision digest；在单一事务中写 advancement/checkpoint、task/reservation/outbox。相同 advance key 重放同一 receipt，不新增 checkpoint、repair task 或 outbox。 | 真实 MySQL 8.4、Flyway v048 定向 Maven：`ResearchAgentTaskCoordinatorServiceTest` 15/15 green（project `noteweave-ma4i-i4-bind-20260717t1110a`），结束后 container/network 已清理。该证据覆盖 durable response-loss replay；不涵盖 coordinator process kill、lease reaper 或 Kafka 恢复，后者属于 I5。 |
| I5a：legacy / incremental handoff guard | `INCREMENTAL_V1` Run 一旦存在任何 agent task、checkpoint 或 advancement 历史，即不可切回 `SEQUENTIAL_V1/V2` 或 `LOCAL_PARALLEL`；活跃 task 继续返回原有更具体错误。 | 真实 MySQL 8.4、Flyway v048 定向 Maven：`ResearchAgentTaskServiceTest` 12/12 green（project `noteweave-ma4i-i5-20260717t1050a`），结束后 container/network 已清理。尚未覆盖 coordinator crash/replay、advance lease reaper 或 Kafka 端到端 recovery。 |
| I5b：自动 coordinator 数据库恢复 | scheduler 默认处理 `RUNNING + INCREMENTAL_V1` run：先 DB-clock reaper、再 run-lock snapshot；首 wave 只创建一次，active/retry 状态 no-op；当前 wave 的失败任务只从 server-derived snapshot 生成 repair advancement/checkpoint/counterfactual task/outbox。自动 outbox pump 默认随 Kafka transport 启用；Kafka 被显式禁用时只关闭投递，不关闭 durable coordinator。历史失败 wave 不会在较新 repair wave 已 terminal 后被重复推进。首 wave 的 task/reservation/outbox 已写入、事务提交前发生 deterministic fail-stop 时，整笔回滚，下一 tick 恢复为恰好一份任务。 | 真实 MySQL 8.4、Flyway v048 定向 Maven：`ResearchAgentTaskCoordinatorServiceTest` 20/20、`ResearchAgentCoordinatorSchedulerTest` 3/3、`ResearchAgentCommandDispatchSchedulerTest` 4/4，合计 27/27 green；evidence `scripts/ma4i/evidence/noteweave-ma4i-i5-crash-20260717t1825a-20260717T100440Z`，manifest `VERIFIED`，project-scoped container/volume/network 已清理。边界：deterministic pre-commit fail-stop，不含真实 coordinator process-kill、Kafka delivery/replay、worker crash 或真实 provider。 |
