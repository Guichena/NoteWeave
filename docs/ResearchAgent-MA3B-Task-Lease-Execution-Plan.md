# Research Agent MA3B：任务、Lease 与 Internal API 执行方案

> 文档状态：CURRENT  
> 阶段：MA3B（MA3 的第二子阶段）  
> 前置：MA3A 已完成服务端 cell CAS、candidate/merge append-only 审计。  
> 目标：将 `CellTaskBundle` 的生命周期持久化为可 claim、heartbeat、expire/reclaim 的服务端任务；尚不通过 Kafka 启动分布式 Agent。

## 1. 目标与边界

新增 `research_agent_task` 和 `research_agent_execution`，让 Coordinator 创建 task，执行者以服务端下发的 lease/fencing claim，并以同一身份 heartbeat/submit。所有状态转换由 Backend 条件更新控制。

本阶段不做 Budget reservation、checkpoint high-water mark、Worker 远程 client、Kafka command topic，也不将 `SEQUENTIAL_V1/LOCAL_PARALLEL` 改为新 API 调用。Internal API 只服务集成测试和未来执行面，默认生产链路不触发。

## 2. 数据与状态机

新增 migration `V039__add_research_agent_task_execution.sql`：

```text
research_agent_task
  id, research_run_id, task_key, idempotency_key,
  wave_no, role, entity_id, branch_id,
  plan_revision, entity_set_version, target_cells_json, budget_json,
  status, lease_epoch, fencing_token, worker_instance_id,
  lease_expires_at, attempt_count, created/updated/terminal timestamps

research_agent_execution
  id, research_agent_task_id, execution_key,
  lease_epoch, fencing_token, worker_instance_id,
  status, termination_reason, usage_json, trace_digest, submitted_at
```

唯一性：`(research_run_id, idempotency_key)`、`(research_run_id, task_key)`、`(research_agent_task_id, execution_key)`。task/execution 不删除；状态机为：

```text
PENDING -> CLAIMED -> RUNNING -> SUBMITTED
PENDING/CLAIMED/RUNNING -> CANCELLED/FAILED
CLAIMED/RUNNING -> EXPIRED -> CLAIMED
```

仅 `PENDING/RETRY_WAIT/EXPIRED` 可 claim。每次 claim 使 `lease_epoch` 与 `fencing_token` 单调递增；heartbeat/submit 同时匹配 task id、worker、epoch、token、未过期 lease。终态永不可重新 claim。

## 3. 服务与 API

`ResearchAgentTaskService` 提供 create、claim、heartbeat、expire 与 submit：

- create 校验 run 非终态、entity set 已 freeze、idempotency 去重；不赋予 claim 身份；
- claim 使用单条条件 update；成功返回 lease/fencing 与 task snapshot；
- heartbeat 只延长匹配活跃 lease；过期/错 token/错 worker 返回 `STALE_LEASE`；
- expire 只将过期 CLAIMED/RUNNING 转为 EXPIRED，不触碰 canonical cell；
- submit append execution 并转 task 为 SUBMITTED；重放按 execution key 返回已存结果。

`ResearchAgentTaskInternalController` 使用 `/internal/research-agent-tasks`。service 从 research run 查询 workspace scope，不信任请求中的 workspace。MA3B 暂不把 submit 的 candidate 接到 MA3A merge，避免绕过独立 verifier。

## 4. TDD 顺序

### Red 1：schema/create/idempotency

- Flyway V039 可迁移；同 run/idempotency/task key 重放只产生一条 task；
- 未 freeze entity set 或终态 run 不允许 create。

### Red 2：claim/heartbeat/expire/reclaim

- 两个 worker 竞争 claim，同一 task 只有一个成功；
- 正确 epoch/token 延期；错误 token/worker/过期 lease 必须拒绝；
- expire 后另一个 worker reclaim，epoch/fencing 严格增大；旧 worker heartbeat/submit 被拒。

### Red 3：execution submit / internal boundary

- submission append 一条 execution 并使 task SUBMITTED；重放不重复 append；
- terminal/cancelled task 不能 claim/submit；
- internal controller 只暴露必要 task identity，未写入 canonical cell。

## 5. 验收

```powershell
.\mvnw.cmd -q -f backend/pom.xml -Dtest=ResearchAgentTaskServiceTest test
.\mvnw.cmd -q -f backend/pom.xml '-Dtest=ResearchAgentTaskServiceTest,ResearchAgentCellMergeServiceTest,Phase6ResearchArtifactContractTest' test
git diff --check
```

完成后更新 MA3 总方案执行记录。MA3B 通过不代表 Kafka 多进程已启用；MA4 之前仅能说明“服务端有可验证的 task lease 生命周期”。

## 6. 执行记录

### 完成情况（2026-07-15）

- 新增 V039：`research_agent_task` 和 `research_agent_execution`，以 run-scoped task/idempotency key 和 task-scoped execution key 去重。
- 新增 `ResearchAgentTaskService`：create、claim、heartbeat、expire/reclaim、submit 都使用服务端条件更新；claim/reclaim 的 lease epoch 与 fencing token 单调递增。
- 新增 `/internal/research-agent-tasks` controller：create/claim/heartbeat/submit/expire。请求没有 workspace 写入字段，服务从 run 查询 scope；未连接到默认 Worker 链路。
- TDD 覆盖 create 重放、竞争 claim、失效后重新 claim、旧 lease heartbeat 拒绝、execution submit/replay、终态任务不可重新 claim。
- 验证：Task service 2/2、Cell CAS 3/3、Phase6 Research/Artifact contract 32/32 均通过；Flyway 可迁移至 V039。

### 仍未完成

entity-freeze 目前尚未有 Backend 增量 ledger 投影可强制校验；当前只验证 run 非终态和 task contract。Budget/checkpoint/durable candidate submit 链接、Worker client 与 Kafka 分发仍留在 MA3C/MA3D/MA4。
