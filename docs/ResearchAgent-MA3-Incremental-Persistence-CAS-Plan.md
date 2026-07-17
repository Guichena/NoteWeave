# Research Agent MA3：增量持久化与 CAS 执行方案

> 文档状态：CURRENT  
> 阶段：MA3  
> 前置：MA0/MA1/MA2 已完成；MA2 只在单 Worker 内并行 candidate proposal，Backend 仍保留 legacy snapshot delete/rebuild 路径。  
> 上位设计：[受控多 Agent 并行架构与改造方案](ResearchAgent-受控多Agent并行架构与改造方案.md)  
> 核心目标：让 MySQL 成为 agent task/execution/candidate/merge/budget/wave 的增量真相源，并以 cell CAS + lease/fencing 拒绝迟到写入；不在本阶段派发跨进程 Agent。

## 1. 已核查的起点与问题

- `ResearchRunService.persistClosedLoopState` 当前先删除 `research_cell_evidence/source_evidence/research_cell/research_verifier_decision/research_row/research_branch`，再从 Worker 的完整 payload 重建。它只能服务一次性 `SEQUENTIAL_V1` completion，不能接受多个 Agent 的局部提交。
- `research_cell` 目前以 `(research_run_id, cell_key)` 唯一，但没有持久化 `version/plan_revision/entity_set_version/last_merge_id/lease/fencing`；Worker MA0 的字段尚未落到数据库。
- `research_execution_checkpoint` 以 `(research_run_id, checkpoint_no)` 唯一，不能由并行 Agent 自主选择编号。
- `worker_callback_receipt` 已提供 callback 幂等性，但不能替代 candidate/merge 级幂等与 CAS。

因此 MA3 的唯一写入权必须在 Backend 服务端，而不是复制 Worker 的纯 Python Merge Gate 作为最终真相。

## 2. 目标数据模型

新增 Flyway `V038__add_research_agent_execution_tables.sql`（编号须在实施前再次确认没有并发新增 migration）：

```text
research_cell
  + cell_version, plan_revision, entity_set_version
  + last_merge_id, active_task_id, lease_epoch, fencing_token

research_agent_task
  task_id(unique), research_run_id, wave_no, role, entity/branch scope,
  plan/entity-set snapshot, target_cells_json, budget_json,
  status, lease_epoch, fencing_token, lease_expires_at, idempotency_key(unique per run)

research_agent_execution
  execution_id(unique), task_id, lease/fencing snapshot, status,
  termination_reason, usage_json, trace_digest, submitted_at

research_agent_candidate
  candidate_id(unique), execution_id, task_id, cell_key, base_cell_version,
  value, evidence_ids_json, confidence, source_diversity, idempotency_key(unique)

research_cell_merge
  merge_id(unique), candidate_id(unique), research_run_id, cell_key,
  expected/result cell version, verdict, decision, reason_code,
  accepted_evidence_ids_json, merged_at

research_budget_reservation
  reservation_id(unique), research_run_id, task_id(unique), workspace/provider scope,
  reserved/consumed/released JSON, state, idempotency_key(unique)

research_wave_checkpoint
  research_run_id + checkpoint_seq(unique), wave/round/plan/entity-set,
  ledger hash, task/candidate/merge high-water marks, budget summary, payload summary
```

约束：所有 task/execution/candidate/merge 表 append-only；任何“更新”只更新 task lease/status 或 budget settlement，绝不覆盖 candidate/merge 历史。外键均以 `research_run` 为根；业务唯一键与索引必须支持幂等重投和 cell/lease 查询。

## 3. 服务端写入协议

### 3.1 任务创建与 claim

`ResearchAgentTaskService.createTasks` 只能由 Coordinator 调用：验证 run 非终态、entity set frozen、plan revision 匹配、target cell 唯一且状态非 FROZEN/VERIFIED；以 `(research_run_id, idempotency_key)` 去重。

`claimTask` 通过单条条件 update 实现：

```sql
update research_agent_task
set status = 'CLAIMED', lease_epoch = lease_epoch + 1,
    fencing_token = fencing_token + 1, lease_expires_at = ?
where id = ? and status in ('PENDING', 'RETRY_WAIT', 'EXPIRED')
  and plan_revision = ? and entity_set_version = ?
```

claim 成功后才产生可用的 lease/fencing；失败返回明确的 `STALE/ALREADY_CLAIMED/TERMINAL`，不静默重试。

### 3.2 Candidate submit、verify 与 merge

1. `submitExecution` 验证 task、lease epoch、fencing、target scope 和 execution idempotency，先 append execution，再 append candidates；同一个 key 重放返回已存记录。
2. `verifyCandidate` 只写 append-only verifier decision / candidate verification result；不写 canonical cell。
3. `mergeCandidate` 在一个事务内：锁定或条件更新一条 cell，校验 run/plan/entity-set、`cell_version=expected`、task lease/fencing、candidate/evidence binding、`SUPPORTS` verdict 和 `FROZEN` 状态；CAS 成功才将 cell version 加一并写 merge 记录与 cell evidence 关联。
4. 同一 `merge_id` 重放返回 `IDEMPOTENT_REPLAY`；不同 merge 对相同 version 竞争时只有一个成功，另一个 append `REJECTED:STALE_CELL_VERSION`。
5. `ResearchRunService.persistClosedLoopState` 在 `SEQUENTIAL_V1` 继续使用 legacy rebuild；新模式只能读取/project 增量表，禁止调用 delete/rebuild。

### 3.3 预算与 checkpoint

- 任务创建前 reserve；submit 结算 actual usage；未使用额度 release。每步均以 reservation id/idempotency key 幂等，且 `reserved = consumed + released + outstanding` 可审计。
- checkpoint 由 Coordinator 独占分配 `checkpoint_seq`，不允许 Agent 自行写 `checkpoint_no`。payload 保存 wave/round 与 high-water mark；resume 先重放 durable candidate 再重新派发未完成任务。

## 4. API 与授权边界

新增 internal-only controller，所有写请求均传 execution identity、idempotency key、lease epoch 与 fencing token：

```text
POST /internal/research-runs/{runId}/agent-tasks
POST /internal/research-agent-tasks/{taskId}/claim
POST /internal/research-agent-tasks/{taskId}/heartbeat
POST /internal/research-agent-tasks/{taskId}/submit
POST /internal/research-candidates/{candidateId}/verify
POST /internal/research-candidates/{candidateId}/merge
POST /internal/research-runs/{runId}/agent-checkpoints
```

客户端不可直接访问；Workspace/run ownership 从 task/run 服务端查询，不信任 request 中 workspace、cell 或 budget 字段。MA3 可先提供 Controller/Service/Repository 和集成测试，MA4 才让 Kafka executor 调用这些接口。

## 5. TDD 顺序

### Red 1：Flyway 与 append-only 基线

- 新 migration 在空库和现有 V037 schema 上可迁移；旧 Research/Artifact contract 不回归。
- 相同 task/candidate/execution/merge idempotency key 重放不产生第二行。
- legacy `SEQUENTIAL_V1` completion 仍走 snapshot projection；新 mode 的服务调用不执行任何 delete SQL。

### Red 2：cell CAS / fencing

- 正确 `expected_version + plan/entity-set + lease/fencing + SUPPORTS + precise evidence` 可接受并 `cell_version + 1`。
- 旧 version、旧 plan、旧 entity-set、旧 lease、旧 fencing、FROZEN、非 SUPPORTS、evidence 越界均拒绝且 append 决策。
- 两个不同 candidate 同时 merge 同一 cell，只有一个 accepted；重复 merge 返回幂等结果。

### Red 3：任务生命周期与预算

- create/claim/heartbeat/expire/reclaim 的 epoch/fencing 单调增长。
- task cancellation/terminal task 不允许 claim/merge。
- reservation/settle/release 守恒，重复 submit 不重复消费。
- checkpoint sequence 单调且 candidate/merge high-water mark 可恢复。

### Red 4：边界集成

- internal API 拒绝无认证、跨 workspace/run、伪造 target 或重复 key。
- Worker MA2 的 candidate JSON 可通过端到端 submit → verify → merge，但不会使 Worker 默认模式改变。
- 旧 `completeFromWorker` 的 `SEQUENTIAL_V1` fixture 与新模式投影都通过 detail/read API。

## 6. 拆分与实施顺序

1. **MA3A（schema + cell CAS）**：V038、cell 版本字段、`ResearchCellMergeService`、merge decision/candidate 最小表、纯服务与 JDBC 集成测试。
2. **MA3B（task/execution + lease）**：任务表、create/claim/heartbeat/expire、append execution/candidate、internal API。
3. **MA3C（budget + checkpoint projection）**：reservation/settle/release、checkpoint_seq、resume high-water mark。
4. **MA3D（dual read + legacy isolation）**：new-mode run 从增量表投影，V1 保持 rebuild；删除 legacy 路径只能在 MA4/灰度稳定后另立计划。

每个子阶段均先写执行记录、红灯测试和 migration review；不得把 MA3A 的 cell CAS 绿灯表述为整个分布式架构完成。

## 7. 验收与不变量

- 任何 canonical cell 更新均可定位到 task → execution → candidate → verifier → merge 审计链。
- 迟到/重复/乱序提交不覆盖较新 cell，且可解释地被拒绝或幂等返回。
- append-only 表没有 delete/rebuild；new-mode request 不触发 `persistClosedLoopState` 的删除路径。
- MySQL transaction、Flyway、Backend 全量测试、Worker contract 测试、Compose 配置、`git diff --check` 均通过。
- MA3 完成前不得启用 Kafka 多进程 Agent，也不得宣称 Redis/数据库全局 provider 限流已完成。

## 8. 执行记录

### MA3A：schema + cell CAS（完成，2026-07-15）

- 新增 `V038__add_research_agent_cell_cas_audit.sql`：为 `research_cell` 增加 `cell_version/plan_revision/entity_set_version/last_merge_id/active_task_id/lease_epoch/fencing_token`，并新增 append-only `research_agent_candidate` 与 `research_cell_merge`。
- 新增 `ResearchAgentCellMergeService`：candidate 以 `(research_run_id, idempotency_key)` 幂等追加；merge 对 cell 施加 version、plan/entity-set、task、lease 与 fencing 的条件更新；成功与拒绝均落审计。merge idempotency 限定为 `(research_run_id, merge_key)`，避免不同 run 的 key 相互污染。
- TDD 覆盖：成功 merge/version +1、candidate 重放、merge 重放、迟到 candidate、错误 fencing、非 SUPPORTS verdict；所有拒绝均不覆盖 canonical cell。
- 验证：`ResearchAgentCellMergeServiceTest` 3/3 通过；`Phase6ResearchArtifactContractTest` 32/32 通过；Flyway 在 H2 MySQL mode 完整迁移至 V038；`git diff --check` 通过（仅既有 CRLF 提示）。

### 下一步：MA3B

已完成。`research_agent_task`、execution、claim/heartbeat/expire/reclaim 与 internal API 已落地；Task service 2/2、Cell CAS 3/3、Phase6 32/32 通过。当前仍未让 Worker 使用新服务，亦未引入任务派发、全局预算、checkpoint high-water mark 或 dual-read 投影。

### 下一步：MA3C

已完成。Budget/Checkpoint service 与 internal API、V040 migration 已落地；Budget/Checkpoint 2/2、Task 2/2、CAS 3/3、Phase6 32/32 联合回归通过。之后 MA3D 连接增量投影与 legacy 隔离。MA3 全部完成前不得启动 Kafka 多进程 Agent。

### 下一步：MA3D

为新执行模式建立增量 state projection、将 task/execution/candidate/merge 读模型接入 research detail/checkpoint，并在新模式禁止 `persistClosedLoopState` delete/rebuild；默认 V1 继续兼容。此阶段完成后才可进入 MA4 分布式命令投递。
