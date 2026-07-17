# Research Agent MA4E：Coordinator Taskization 与 Canary 注入执行方案

> 状态：IMPLEMENTED / 定向验证通过（2026-07-15）  
> 前置：MA0–MA4D-2B 已提供 task/lease/snapshot/evidence/candidate/CAS 基础；MA4D-3 fake worker fixture 已就绪。  
> 目标：将 `INCREMENTAL_V1` ResearchRun 的 server-side plan/cell 变成可审计、可派发的 versioned DEEP_CELL task，消除只能通过内部 controller/测试手工造 task 的缺口。
> 后续状态（2026-07-17）：本文的 canary/双 allowlist 是 MA4E 当时的历史激活方式；MA4I 自动 Coordinator 已接管，MA6 已删除旧 canary service/controller/config。当前唯一授权条件是 `INCREMENTAL_V1`。

## 1. 现状与禁止事项

开工时 `ResearchRunService` 的正常 `createRun` 只创建 legacy `task_outbox`；MA4E 已补入默认关闭、workspace/run 双 allowlist 的 server-side coordinator canary，但尚未接入默认生产 scheduler，因此不能把 canary 入口表述为生产自动调度。

MA4E 不得：

- 在 `SEQUENTIAL_V1/V2` 或 `LOCAL_PARALLEL` 自动派发 agent task；
- 根据 Worker/LLM 输出猜测 source scope、cell version、provider 或预算；
- 在没有冻结 plan/entity-set/cell 的 Run 上创建 snapshot-ready task；
- 通过 controller 调用把 canary test data 写入用户 Run。

## 2. Coordinator 输入与输出

Coordinator 只接受 Backend 持久化数据：`research_run`、`research_cell`、`research_row`、已冻结 plan/entity-set、run source scope、control pack 和 server-side provider profile。每个 target cell 生成：

```text
CreateTaskCommand(
  task_key = stable(run, wave, role, sorted cell/version),
  idempotency_key = stable(run, plan, entity-set, cell/version),
  role = DEEP_CELL,
  target_bindings = [(cell_key, cell_version)],
  execution_context = provider_key + source_scope + query policy,
  budget = coordinator reservation policy
)
→ task row → reserve budget → agent outbox command
```

source scope 必须从 Run 保存的 source ids 展开成 server-side `SourceScopeItem`，含允许 Workspace sample；external policy 默认 false。query 必须来自 Run question/intent，不得由 Worker 传回。每个 task 最多三个 cell，且只允许同 entity/branch、相同 plan/entity-set 的未冻结 cell。

## 3. 事务与失败语义

每次 coordinator 调用在单一 Backend transaction 内执行：检查 `INCREMENTAL_V1` → 加载 server-persisted scope → create idempotent task → reserve idempotent budget → enqueue idempotent command。任何一步失败回滚；重复 coordinator 运行只返回已有 task/reservation/outbox，不制造第二条 command。outbox dispatch 继续异步，Kafka 不参与该事务。

未满足可派发条件时返回可审计的 planning receipt（skipped count/reason），不抛出假的成功，也不改变 canonical cell。

## 4. TDD 红灯清单

1. 非 `INCREMENTAL_V1`、terminal run、无 cell/冻结 cell、混合 entity 或 stale plan/entity-set 均不创建 task/outbox。
2. 同一 Run/cell/version 的 coordinator 重放得到同 task/reservation/outbox；不同 version 才能创建新 task。
3. snapshot 内 workspace/source/query/provider/budget 均来自 Backend，且 target binding 与 `research_cell` 精确一致。
4. reservation/outbox 任一写失败时 task 不留下半成品；task/retry 不可超预算。
5. canary allowlist 未开启时 coordinator 不会为 Run enqueue；开启后仅允许配置中的 workspace/run、fake provider、单 wave 小预算。

## 5. MA4D-3 的衔接

实现后，Compose canary 由受控 internal coordinator endpoint 或 scheduler 触发，而不是手工数据库写入。运行时必须先重建隔离 Backend 镜像、设置 `INCREMENTAL_V1 + fake provider + allowlist`，再让两个 canary worker 消费 command。只有这条真实 Run→task→outbox→Kafka→claim→evidence/candidate/CAS→execution 链路连续通过四轮，才能设计真实 provider canary。

## 6. 已实现代码

- `ResearchAgentTaskCoordinatorService` 仅接收非终态 `INCREMENTAL_V1` Run，从 Backend 持久化 source scope 展开 READY workspace source/sample，并按 entity、plan revision、entity-set version 将未终态 cell 组成最多 3 个 cell 的 `DEEP_CELL` task。
- 稳定 task fingerprint 包含 run、entity、`plan_revision`、`entity_set_version` 及排序后的 cell/version；计划或实体集版本变化不会错误复用旧 task。
- `VERIFIED` 与 `FROZEN` cell 不进入 taskization；已绑定 `active_task_id` 的 cell 也不重复调度。
- task create、budget reserve、agent outbox enqueue 处于同一事务；预算或 outbox 故障时不留半成品。
- coordinator canary 默认关闭，必须同时命中 workspace/run allowlist 才能 dispatch；external search/fetch 在 snapshot 中默认 false。

## 7. TDD 与验证记录

`ResearchAgentTaskCoordinatorServiceTest` 当前 7 项通过，覆盖首次创建与幂等重放、模式拒绝、canary 双 allowlist、reservation/outbox 故障回滚、终态 cell 排除、plan revision 变更生成新 task。

可复核结果：`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`。测试运行时间约 75 秒，桌面前台命令可能先触发 64 秒工具超时，最终结果以 Surefire 报告为准。

## 8. 未完成边界与下一阶段

MA4E 只闭合了 **Run → durable task/reservation/outbox**，尚未证明 outbox 被当前 Backend 自动发布，也未完成新镜像下的双 Worker Compose 链路。下一阶段必须先建立独立 MA4F 方案，补齐默认关闭的 outbox dispatch 激活面和隔离 E2E fixture，再执行四轮 fake provider crash/retry 对照。完成前不得宣称真实多进程 Research 已上线或获得质量/延迟收益。
