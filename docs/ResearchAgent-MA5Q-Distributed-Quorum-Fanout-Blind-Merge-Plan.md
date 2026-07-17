# Research Agent MA5Q：分布式双候选 Fan-out 与盲验汇合执行方案

> 状态：IMPLEMENTED / DETERMINISTIC EVIDENCE COMPLETE / REAL PROVIDER EVIDENCE PENDING  
> 范围：简历项目可真实演示的单集群实现；不引入多地域、leader election 或 sharding。  
> 前置：MA4A-J、MA5 离线角色/质量能力、MA6 运行治理已完成。  
> 启用条件：Research Run 的 `agent_execution_mode=INCREMENTAL_V1`；不保留旧 canary、manual dispatch 或双 allowlist。

## 1. 目标与非目标

MA5 Worker 已经具备 `candidate_quorum=2` 契约和本地盲验器，但旧的 Backend Coordinator 仍只创建一个物理 task，且该 task 完成后会立即 CAS canonical cell。这意味着“高风险 cell 双候选”目前只是离线能力，不是端到端能力。

本阶段把高风险 cell 变成两个真正独立的 durable execution slot：独立 task ID、lease/fencing token、execution、预算 reservation 和 command outbox；两个 slot 共享服务端生成的 quorum group。第一个候选只能进入 durable staging，第二个候选到达后才能进行身份盲化验证并至多 CAS 一次。

本阶段不做：动态 N 候选、跨地域仲裁、拜占庭共识、人工审核 UI、自动 Provider 选择、生产 SLO 证明。真实 Provider 质量/p95 证据仍需配置 `NOTEWEAVE_RESEARCH_LLM_*` 后执行四轮 A/B。

## 2. 关键不变量

1. `research_cell.high_risk` 是是否启用 quorum=2 的服务端权威来源；Worker 不能自行把普通 cell 升级为高风险。
2. 高风险 cell 必须单独成 bundle，且同一 group 固定 `candidate_quorum=2`、slot 为 1 和 2。
3. 两个 slot 是两个物理 task，拥有独立 lease、fencing、execution、预算和 outbox；共享 `quorum_group_key`、target cell 与 base cell version。
4. completion 中的 group/quorum/slot 不信任 Worker payload，Backend 必须从持锁的 authoritative task row 读取。
5. 首个完成的 slot 持久化 evidence、candidate、execution、completion 和 budget settlement，但不得改变 canonical cell；receipt 的 outcome 为 `QUORUM_PENDING`。
6. 第二个 slot 到达时，Backend 在同一事务中锁定 quorum group 和目标 cell，读取两个 durable candidate，进行身份盲化、独立来源域和内容一致性检查；通过后只 CAS 一次。
7. 同域伪 quorum、值冲突、缺少 provenance、slot/group 不一致必须 fail closed，生成确定性的 rejected merge/repair 信号，禁止任选一个候选。
8. slot 2 先到与 slot 1 先到得到相同 canonical 结果。重复 completion 返回首次保存的 byte-equivalent receipt；早期 pending receipt 不在后续动态改写。
9. 普通 `candidate_quorum=1` task 保持 MA4J 的现有原子 completion 行为。

## 3. 数据模型（V062）

只增加新 migration，不修改既有 migration。

### 3.1 `research_cell`

- `high_risk boolean not null default false`：由 plan/schema 编译阶段写入的服务端权威策略。

### 3.2 `research_agent_task`

- `quorum_group_key varchar(128) null`
- `candidate_quorum int not null default 1`
- `candidate_slot int not null default 1`
- `logical_task_key varchar(191) null`
- 唯一约束 `(research_run_id, quorum_group_key, candidate_slot)`。
- 检查约束：quorum 为 1..2，slot 为 1..quorum；quorum=2 时 group 不为空。

普通 task 也写入稳定 `logical_task_key`；quorum=1 可以不使用 group 锁，但 snapshot 仍显式携带 quorum/slot，避免默认值在不同语言中漂移。

### 3.3 `research_agent_candidate`

- `quorum_group_key varchar(128) null`
- `candidate_slot int not null default 1`
- `source_domains_json json null`
- `blind_digest varchar(71) null`
- 唯一约束 `(research_run_id, quorum_group_key, candidate_slot)`（group 非空时生效）。

候选来源域从被该 candidate 引用的服务端 evidence/source 关系派生，不接受 Worker 自报域名作为权威。

## 4. Snapshot 契约

新增 `research-agent-task-snapshot.v2`：

- 保留 v1 全部字段；
- 新增 `logical_task_key`、`quorum_group_key`、`candidate_quorum`、`candidate_slot`、`high_risk`；
- digest 覆盖以上字段；
- 高风险 quorum task 生成 v2；普通 task 与历史已落库任务继续支持严格 v1，completion 不因升级而拒绝合法旧任务；
- Worker parser 同时接受严格的 v1/v2，v2 字段不得出现 extra，且验证 `quorum=2 => high_risk=true && group!=null && slot in {1,2}`。

## 5. Coordinator taskization

### 5.1 普通 cell

按既有 entity bundle（最多 3 cell）生成一个 `DEEP_CELL` task，quorum=1、slot=1。

### 5.2 高风险 cell

高风险 cell 不与其他 cell 混合：

- 生成稳定 `quorum_group_key = sha256(run/wave/cell/base-version/plan/entity-set)`；
- slot 1 role=`DEEP_CELL`；
- slot 2 role=`COUNTERFACTUAL`；
- 两个任务使用确定性的 source partition。至少两条来源时按稳定 source id 排序交错分区；只有一个来源域时仍可执行两个 slot，但 completion 必须因无法形成独立域 quorum 而 fail closed；
- 每个 slot 分别 reserve 和 enqueue，整个 fan-out 位于 Coordinator 的单事务内，任一步失败全部回滚；
- replay 通过 task idempotency key 与 group-slot 唯一约束返回两个既有任务，不重复 reservation/outbox。

## 6. Completion 状态机与事务边界

```text
slot completion
  -> lock task + validate lease/fence/snapshot
  -> persist evidence/candidate/execution/completion
  -> settle this slot budget + terminalize this task
  -> quorum=1: existing verify/CAS path
  -> quorum=2:
       lock all group tasks/candidates in binary order
       candidates < 2: save immutable QUORUM_PENDING receipt, commit
       candidates = 2: blind verify
         pass: lock cell, one CAS, append accepted lineage, save QUORUM_MERGED receipt
         fail: no cell mutation, append deterministic rejection/repair signal,
               save QUORUM_REPAIR_REQUIRED receipt
```

锁顺序固定为 run -> quorum group/task id binary order -> cell key binary order，避免 slot 完成并发产生死锁。候选 staging、最终 CAS、receipt 和 task/budget terminalization都在同一 completion 事务中；故障注入点覆盖首候选落库后、第二候选盲验前、CAS 后 receipt 前。

## 7. 盲验规则

Backend 盲验输入不包含 task id、worker id、execution id 或 slot 的可读身份，只使用按 digest 排序的匿名结构：

- normalized candidate value digest；
- exact evidence key 集合；
- 从 evidence 反查得到的 source domain 集合；
- base cell version、plan revision、entity set version。

通过条件：两个独立 slot/execution；两个非空且互不重叠的来源域集合；相同 normalized value；目标 cell 和 base version 完全相同。任何缺失或冲突返回稳定 reason code，例如 `QUORUM_SOURCE_DOMAIN_NOT_INDEPENDENT`、`QUORUM_VALUE_CONFLICT`、`QUORUM_PROVENANCE_MISSING`。

## 8. 已确认的 TDD seam

只通过以下公共接口测试行为：

1. `ResearchAgentTaskCoordinatorService.planAndEnqueueForWave(...)`
   - 高风险 cell 创建同 group 的 slot 1/2 两个任务；task/reservation/outbox 均独立且 replay 不重复。
2. `ResearchAgentCompletionService.complete(...)`
   - 首 slot 返回 `QUORUM_PENDING`，cell version/value/status 不变；第二 slot 在独立域同值时返回 `QUORUM_MERGED`，canonical cell 只增加一个版本。
3. 同一个 `complete(...)` seam 覆盖 slot 2 先到、重复 completion、同域候选、值冲突和故障回滚；断言公开 receipt 与最终可读取状态，不测试 private helper。
4. Worker 的 `TaskSnapshotEnvelope` / `DeepCellExecutor.execute(...)`
   - v2 严格契约、slot 传播和来源 provenance；旧单候选 executor 遇 quorum=2 继续 fail closed。
5. `ResearchAgentCoordinatorTickService.tick(...)`
   - durable `QUORUM_REPAIR_REQUIRED` decision 必须进入 gap projection 和现有 checkpoint/repair/taskization 原子边界；不能停在 `TERMINAL_BARRIER_PENDING`。

每个 vertical slice 严格执行：一个失败测试 -> 最小实现 -> 定向测试通过，再进入下一 slice。

## 9. 分阶段执行

- MA5Q-A：完成。V062、服务端 high-risk 权威字段、Coordinator 双槽 fan-out、snapshot v2。
- MA5Q-B：完成。首候选 durable staging 与 `QUORUM_PENDING` immutable receipt。
- MA5Q-C：完成。双候选 Backend blind verifier、独立域检查与单次 CAS。
- MA5Q-D：完成。冲突/同域 repair signal、乱序/replay/crash rollback、quorum lifecycle group binding 回收。
- MA5Q-E：完成。Worker v2 snapshot/provenance、合法 COUNTERFACTUAL quorum slot 与 quorum=1 repair task、verifier decision 自动 repair taskization/成功后 RESOLVED、MySQL 8.4 与 Worker 全量回归。
- MA5Q-F：配置真实 Research LLM 后，每种 mode 至少四轮 A/B，归档质量、成本、429/5xx、p95；没有凭证时保持 PENDING，不伪造通过。

## 10. 完成判据

- 高风险 cell 在数据库中可观察到两个独立 durable task、reservation、outbox 和 execution；
- 首 slot 永不提前修改 canonical cell；
- 双 slot 独立域同值仅产生一次 accepted merge/CAS；
- 同域/冲突/乱序/重放/注入崩溃均确定性且幂等；
- 普通 quorum=1 路径无回归；
- Worker 全量测试与 Backend MySQL 8.4 定向/聚合测试通过；
- 文档区分 deterministic fixture 证据与真实 Provider 证据。

## 11. 2026-07-17 实施与验证记录

已落地：

- V062 为 `research_cell`、task、candidate 增加 high-risk/quorum/slot/blind digest 数据契约；
- Coordinator 对高风险 cell 创建两个独立物理 task，分别拥有 reservation、outbox、lease/fence 与 execution，并按稳定 source ID 分区；
- cell binding 对 quorum task 使用 group owner，两个 slot 可同时 claim；retry exhausted 时仅在最后一个非终态 sibling 消失后释放 group binding；
- completion 首候选只 durable stage，第二候选按服务端持久化 evidence 派生来源域，完成同值独立域 merge 或同域/值冲突 fail-closed；
- `QUORUM_VALUE_CONFLICT`、`QUORUM_SOURCE_DOMAIN_NOT_INDEPENDENT` 会写 rejected merge 与 open verifier decision；Coordinator snapshot/gap projection 会把该 decision 转为排除旧来源的 COUNTERFACTUAL repair task；
- repair task 使用 v2、`COUNTERFACTUAL`、quorum=1/slot=1、固定 `branch-counterfactual` 与 `counterfactual:` logical key；Backend atomic completion 与 Worker executor 都按该完整形状 fail-closed 授权；
- repair accepted merge 与 decision 状态更新位于同一 completion 事务；只有同 run/同 cell 的 OPEN `QUORUM_REPAIR_REQUIRED` 会转为 `RESOLVED`，无 accepted merge 不会误关闭；
- slot 乱序、pending receipt replay、CAS 后故障回滚和重试成功均有公开 seam 测试；
- Worker `DeepCellExecutor` 仅接受普通 `DEEP_CELL`、完整匹配的 v2/high-risk/quorum=2/slot=2 第二槽，或带非空 repair reason 与 excluded source IDs 的 v2/quorum=1 repair task。

本轮可复核证据：

- Backend MySQL 8.4 Research 聚合回归：`202 tests, 0 failures, 0 errors, 1 skipped`；唯一 skip 为需显式 `NOTEWEAVE_REDIS_INTEGRATION=true` 的 Redis integration；
- MySQL LockMatrix：MySQL `8.4.9`、READ-COMMITTED、`13/13` cases，`test_only_harness_removed=true`；
- Research Worker 全量：`361 passed`；
- Java task snapshot/completion canonical object key 已统一为 unsigned UTF-8 顺序，与 Python `sort_keys=True` 的 Unicode code-point 顺序对齐；包含非 BMP key 的跨运行时回归向量已通过；
- deterministic report 使用同一 unsigned UTF-8 cell 顺序，并转义 Markdown table 控制字符；Redis permit 对容量耗尽记录 `result=limited`；
- `git diff --check`：无 whitespace error；
- 旧 coordinator canary 的代码路径、主 compose、`.env.example` 与 MA4 fixture 死配置均已删除；唯一授权仍是 `INCREMENTAL_V1`。

仍为外部证明事项：

- 当前环境 `NOTEWEAVE_RESEARCH_LLM_API_KEY/BASE_URL/MODEL` 均为 `UNSET`；
- 因此没有执行真实 Provider 的每 mode 四轮 A/B，也不声称质量、成本或 p95 优于 TAS/MiroFlow/Marco/DeepWideSearch；
- 现有通过证据证明确定性契约、事务一致性、恢复和调度闭环，不等于生产 SLO 或真实外部检索质量证明。
