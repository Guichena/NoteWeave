# Research Agent MA3C：预算账本与 Checkpoint 高水位执行方案

> 文档状态：CURRENT  
> 阶段：MA3C（MA3 第三子阶段）  
> 前置：MA3A cell CAS、MA3B task/lease/execution 已完成。  
> 目标：让任务预算以服务端 reservation → settlement/release 守恒，并让 checkpoint 以单调 sequence 与 task/candidate/merge 高水位可恢复。

## 1. 边界

MA3C 增加持久化真相，但不接入 Redis provider 限流、Kafka command consumer、真实 Worker submit client 或全量 ledger dual-read。预算先按 task/run 记账，跨 workspace/provider 的分布式限流属于 MA4。

## 2. 数据模型

新增 V040：

```text
research_budget_reservation
  id, research_run_id, research_agent_task_id(unique), idempotency_key(unique per run),
  reserved_json, consumed_json, released_json, state, created/settled timestamps

research_agent_checkpoint
  id, research_run_id, checkpoint_seq(unique per run), wave_no, round_no,
  plan_revision, entity_set_version, ledger_hash,
  task_high_water_mark, candidate_high_water_mark, merge_high_water_mark,
  budget_summary_json, summary_json, created_at
```

每个数量字段是非负的维度 map，且逐维满足：`reserved = consumed + released + outstanding`。reservation 只能从 `RESERVED` 转 `SETTLED` 或 `RELEASED`；重复请求按 idempotency key 返回原记录，不重复扣账。

checkpoint sequence 不使用 legacy `checkpoint_no`；通过 run-level lock/transaction 分配，保存高水位与 payload 摘要。MA3D resume 使用该记录重放 durable candidate，而不是重新搜证。

## 3. 服务

`ResearchBudgetService`：reserve、settle、release、read summary。settle 验证 usage 不超过 reserved；release 仅释放尚未 consumed 的额度；终态 task 不得 reserve。

`ResearchAgentCheckpointService`：append checkpoint、list latest。append 验证 run 存在和版本非负；服务端生成下一 sequence，不信任调用方 sequence。高水位单调不回退。

两者使用 internal-only API 供未来 Coordinator 使用，当前 Worker 默认链路不调用。

## 4. TDD

- Red 1：同 task/idempotency reserve 重放只一行；settle/release 后逐维守恒，超预算拒绝。
- Red 2：两次 checkpoint sequence 严格递增；同 run 高水位不能倒退；不同 run sequence 独立。
- Red 3：terminal run/task 的 reserve/checkpoint 拒绝；旧 Phase6 与 MA3A/B 测试不回归。

## 5. 验收

```powershell
.\mvnw.cmd -q -f backend/pom.xml -Dtest=ResearchBudgetAndCheckpointServiceTest test
.\mvnw.cmd -q -f backend/pom.xml '-Dtest=ResearchBudgetAndCheckpointServiceTest,ResearchAgentTaskServiceTest,ResearchAgentCellMergeServiceTest,Phase6ResearchArtifactContractTest' test
git diff --check
```

MA3C 完成不代表 resume 已切换到新 checkpoint，也不代表真实 provider 限流已跨进程生效；这些仍由 MA3D/MA4 完成。

## 6. 执行记录

### 完成情况（2026-07-15）

- 新增 V040：`research_budget_reservation` 与 `research_agent_checkpoint`，reservation 以 run/task/idempotency 唯一，checkpoint 以 `(run, sequence)` 唯一。
- 新增 `ResearchBudgetAndCheckpointService` 与 `/internal/research-agent` Budget/Checkpoint controller：reserve、settle、release 按维度检查非负与上限，release 生成未消耗差额；checkpoint 在锁定 research run 后由服务端生成 sequence，并拒绝任一 high-water mark 回退。
- TDD 覆盖预算重放、settle/release 守恒、超预算拒绝、checkpoint 单调 sequence 与高水位回退拒绝。
- 验证：Budget/Checkpoint service 2/2 通过；Flyway 可迁移至 V040。

### 仍未完成

尚未将新 checkpoint 接入 resume 或让 Worker 调用 reservation；这些会随 MA3D 的增量投影和 API 收口一起完成。跨进程 provider 限流仍属于 MA4。
