# Research Checkpoint Schema 设计（DR-401）

> 状态：`[目标设计]` 设计基线，非已实现
> 更新时间：2026-09-18
> 对应任务：`docs/DeepResearch-简历能力落地执行计划.md` DR-401（M4，依赖 M1）
> 退出条件：Schema 能表达恢复所需最小状态；证据产物：迁移与契约
> 权威架构：[DeepResearch-ResearchAgent架构文档.md](../DeepResearch-ResearchAgent架构文档.md) 「Checkpoint Hydration 与避免重复外部调用」
> 上游接口：执行计划 §7.4 `CheckpointRecovery.save / hydrate / acceptCallback`

本文只定义 Schema 与契约，不修改代码。所有结论都标注了对应文件 / 类 / 迁移版本。`[当前实现]` 表示仓库中已存在并可验证；`[目标设计]` 表示尚未实现、不得当作已完成。

---

## 0. 结论速览

- 当前 Checkpoint 的 payload 能表达「Canonical Ledger 的不可变语义快照」，但**不能表达 Run 身份稳定下的新 Attempt、外部调用收据、预算连续性和过期写入拒绝令牌**。
- 旧恢复口径「创建 Descendant Run + 复制语义状态」在 `research_run` 上换了业务身份，且显式不复制 Receipt / Reservation / Execution，因此无法作为 DR-404/DR-405 的验收底座（架构文档 L124–L128 已把它标为「不能作为推荐口径」）。
- 目标 Schema 采用 **同一 ResearchRun 身份 + 新 ExecutionAttempt**，新增三类一等表（Attempt / 外部调用收据 / Run 级预算账本），并把 `research_agent_checkpoint` 扩展为携带 barrier、watermark 与 fence 的「恢复最小状态」索引行。
- 若 DR-101（`ExtractionResult` / 拒绝原因枚举）、DR-201（`CellRecoveryPolicy` 决策枚举）尚未冻结，则**抽取类收据的 reason code** 与**「未完成 Cell」的判定集合**属于外部依赖，见 §8。

---

## 1. 现状：当前 Checkpoint 能表达什么、不能表达什么

当前存在三套与 Checkpoint 相关的存储，职责不同，不能混为一谈：

| 存储 | 迁移 | 承载内容 | 恢复中的角色 |
| --- | --- | --- | --- |
| `research_execution_checkpoint` | `V015` | `snapshot_type`、`object_key`（对象存储指针）、`payload_sha256`、`content_size`、`active_branch_key`、`final_loop_decision`、`summary_json` | `[当前实现]` 旧口径 Checkpoint 索引，payload 在 `noteweave-derived` 桶 |
| `research_agent_checkpoint` | `V040` | `checkpoint_seq / wave_no / round_no / plan_revision / entity_set_version / ledger_hash / task_high_water_mark / candidate_high_water_mark / merge_high_water_mark / budget_summary_json / summary_json` | `[当前实现]` Coordinator 拥有的水位线与预算摘要 |
| `research_checkpoint_hydration_snapshot` | `V100` | `schema_version`、`payload_json`（Canonical JSON）、`content_size`、`payload_sha256`、`canonical_digest`、`ledger_digest` | `[当前实现]` Hydration 的真正数据源 |

### 1.1 能表达的语义状态

`research_checkpoint_hydration_snapshot.payload_json` 由 `ResearchAgentCheckpointSnapshotCompiler.compile()`（`ResearchAgentCheckpointSnapshotCompiler.java:37-81`）编译，字段集合见 `:44-67`：

| payload 字段 | 来源表 / 列 | 说明 |
| --- | --- | --- |
| `matrix_plan` | `research_matrix_plan.plan_json`（最新一条） | 计划与 digest 下限 |
| `branches` | `research_branch` | 分支不变量 |
| `rows` | `research_row` | 研究对象与来源绑定 |
| `cells` | `research_cell`：`cell_status / candidate_value / evidence_refs_json / last_verifier_decision / repair_count / cell_version / plan_revision / entity_set_version / last_merge_id / high_risk` | 逐 Cell 状态与版本 |
| `accepted_evidence` | `source_evidence` ⨝ `research_cell_evidence` ⨝ `research_cell(status='VERIFIED')` | 已确认证据（`Compiler.java:144-170`） |
| `open_decisions` | `research_verifier_decision(decision_status='OPEN')` | 未决验证 / 修复 |
| `stages` | `research_run_stage`：`stage / stage_revision / status / barrier_digest / expected_task_count / settled_task_count / blocker_count / stage_version / barrier_json` | 阶段屏障（`Compiler.java:187-199`） |
| `budget_summary` | 来自 `CheckpointCommand.budgetSummary()`（调用方传入） | 预算摘要 |
| `high_water_marks` | `CheckpointCommand` 的 `task / candidate / merge` | 执行面水位线 |

完整性可校验：`Compiler` 落库 `payload_sha256`（size+sha256）与 `canonical_digest`（domain-separated）；`Hydrator.verify()`（`ResearchAgentCheckpointHydrator.java:132-149`）在恢复时逐项复算，`research_checkpoint_hydration_snapshot` 有 `uq_..._checkpoint` 与 `uq_..._seq` 唯一约束（`V100:19-23`）。

### 1.2 不能表达的语义状态

| 缺口 | 依据 | 后果 |
| --- | --- | --- |
| **Run 身份稳定的新 Attempt** | `resumeFromCheckpoint` 直接 `insert into research_run(... resumed_from_research_run_id, resumed_from_checkpoint_no, resume_mode ...)` 造新 Run（`ResearchRunCommandService.java:146-163`） | 基础设施故障被计成新的业务 Run，恢复指标/成本口径被污染 |
| **Run 级 Attempt fencing** | `V071__add_research_run_callback_fencing.sql` 给 `research_run` 加了 `run_version / attempt_no / fencing_token`；**已核实无任何读写**，复核命令：`rg -U 'research_run[\s\S]{0,400}?(run_version\|attempt_no)' backend/src/main`（唯一命中是迁移文件本身），`rg 'run_version\|attempt_no\|fencing_token' backend/src/main/java/com/noteweave/research` 的命中全部是 Task 级 `research_agent_task.fencing_token` 或 completion 回执字段 | 没有 Run 级旧 Attempt 拒绝能力，只能靠 Task 级 lease/fence |
| **外部调用收据（Operation-Key 粒度）** | `research_external_snapshot`（`V079`）只归档 Read/Fetch 的**内容**；Search/Model 只有聚合计数，见 Python `_RESERVATION_KEYS`（`agent_task_client.py:418-422`）与 completion receipt（`V045`） | 恢复时无法判断某次 Serper/GLM 调用是否已计费，可能重复调用或重复计费 |
| **收据状态机 `CONFIRMED/IN_FLIGHT/UNKNOWN`** | 无对应表 / 列 | Provider 超时后的「结果未知」无法沉淀，只能靠人工 |
| **Run 级预算账本连续性** | Hydrator 写入 `budget_summary_json = {"restored_source": <旧摘要>}`（`ResearchAgentCheckpointHydrator.java:124`） | 预算只被「记录」，没有被继续记账；DR-405 的守恒断言无对象 |
| **Cell 完成水位线** | payload 有 `cells[].cell_version`，但无「哪些 Cell 已封存」的紧凑水位线；Hydrator 重建 Cell 时把 `active_task_id=null, lease_epoch=0, fencing_token=0`（`ResearchAgentCheckpointHydrator.java:214-229`） | DR-403「只调度未完成 Cell」需要重新扫描全部 Cell，且缺少可断言的封存水位 |
| **恢复所需令牌** | `research_agent_checkpoint` 无 `attempt_id / run_fence_token`；回调校验只依赖 Task 的 `lease_epoch/fencing_token`（`ResearchAgentCompletionCommitter.java:192-200`、`:779-786`） | 无法在 Checkpoint 上表达「该快照属于哪一代 Attempt、写入时 Run fence 是多少」 |
| **多阶段屏障** | `ResearchAgentRunnableWorkService` 里 `STAGE = "CELL_RESEARCH"` 是唯一常量（`ResearchAgentRunnableWorkService.java:15-16`），`research_run_stage` 仅写这一阶段 | DISCOVERY / VERIFYING / SYNTHESIZING 无屏障行，恢复无法判断「阶段是否已 settled」 |

### 1.3 历史口径评述：为什么「旧恢复入口创建 Descendant Run」不能作为推荐口径

旧恢复入口即 `resumeFromCheckpoint`（`ResearchRunCommandService.java:118-207`）：
- `AUTO` 在 `checkpointHydrator.available()` 为真时走 `HYDRATED_LEDGER_RESUME`，否则回落 `CONTEXT_RESTART`；
- 无论哪种模式都**新建 `research_run`**，并记录 `resumed_from_research_run_id / resumed_from_checkpoint_no / resume_mode`；
- Hydration 只复制语义平面（branch/row/cell/evidence/decision/stage/matrix），**不复制** Active Lease、Task、Outbox、Execution、Reservation、Receipt（架构文档 L124 明确列出）。

不能作为推荐口径的原因：

1. **业务身份被重置**。架构文档目标态要求「ResearchRun 保持用户意图身份稳定……只从 Checkpoint 创建新的 ExecutionAttempt，不自动重编译 Plan，也不重置 Run 总预算」（架构文档 L126）。Descendant Run 恰恰相反。
2. **无法自证「不重复计费」**。收据在被恢复时被丢弃，DR-404 要断言的「已确认外部调用不重复」在 Descendant Run 上只能证明**新 Run 内**不重复，不能证明**原 Run** 的计费不重复。
3. **预算不可守恒**。新 Run 没有继承旧 Run 的 reservation，`V040` 的 `research_budget_reservation` 唯一键是 `(research_run_id, idempotency_key)`，跨 Run 无从合并，DR-405 无法断言。
4. **指标口径被破坏**。`Cost per Successful Research`、`Recovery Success Rate`、`Duplicate External Call Rate` 等都以 ResearchRun 为分母（架构文档 L189–L192），Descendant 会把一次故障拆成两个 Run。
5. **可见性回溯链断裂**。`ResearchBriefCompiler` 需 join `resumed_from_research_run_id`（`ResearchBriefCompiler.java:150-156`）才能还原，说明恢复语义已退化为「外部拼接」而非「同一 Run 的账本演进」。

**结论**：`CONTEXT_RESTART` 保留为兼容 / 只读路径，但不得成为恢复验收与 DR-402/403/404 的实现底座。

---

## 2. 缺口：以 DR-402/403/404/405 退出条件为反向需求

| 反向需求（来自 M4 退出条件） | 缺失字段 / 语义 | 影响任务 |
| --- | --- | --- |
| 「任一阶段中断均有最近安全点」 | 需要「外部调用确认后」这一触发点落库：`operation_key + receipt_status + commit checkpoint_seq` | DR-402 |
| 「同 Run 可跨 Worker 继续」 | 需要同一 Run 下的 `attempt_no / fence_token` 与 `parent_attempt_id`，而非新 Run | DR-403 |
| 「只调度未完成 Cell」 | 需要稳定的「已完成 Cell 集合 + 版本」水位线，且其判定依赖 Cell 语义状态是否冻结 | DR-403 |
| 「已确认外部调用不重复」 | 需要 `operation_key` 全局唯一 + `CONFIRMED` 复用语义 + `IN_FLIGHT` 可查询语义 | DR-404 |
| 「旧 Attempt 不可写入」 | 需要回调携带 `attempt_id + fence_token`，Hydration/写入前做单调性比较 | DR-404 |
| 「重启不重置或重复扣费」 | 需要 Run 级预算账本（reserved/consumed/released 三维度），且随 Checkpoint 记录 digest | DR-405 |
| 「阶段屏障」 | 需要非 `CELL_RESEARCH` 阶段的 barrier 行与 `settled_at_checkpoint_seq` | DR-402/403 |
| 「恢复所需最小状态」 | 需要判定 Hydration 是否「自足」的契约：缺 attempt / receipt / ledger 时 fail-closed | DR-401 |

---

## 3. 目标 Schema（字段级）

设计原则：**回答「最小恢复所需状态」而非「把所有东西都存下来」**。因此：
- 不可变语义快照继续由 `research_checkpoint_hydration_snapshot.payload_json` 承载（已存在，不重造）；
- 新增表只承载**恢复决策与去重**所需的可变状态：Attempt、外部调用收据、Run 级预算账本；
- `research_agent_checkpoint` 扩展为**一行 = 一个安全点的索引**，携带 barrier / watermark / fence / digest。

### 3.1 新表：`research_execution_attempt`

同一 ResearchRun 下的执行代（generation）。`[目标设计]`

| 列 | 类型 | 可空 | 约束 / 语义 |
| --- | --- | --- | --- |
| `id` | varchar(36) | 否 | PK |
| `research_run_id` | varchar(36) | 否 | FK → `research_run(id)` |
| `attempt_no` | int | 否 | Run 内单调递增，从 1 开始 |
| `fence_token` | bigint | 否 | Run 内单调递增，且 ≥ 前任；过期写入的拒绝依据 |
| `parent_attempt_id` | varchar(36) | 是 | 恢复来源 Attempt（首个为 null） |
| `recovery_reason` | varchar(64) | 是 | `WORKER_CRASH / LEASE_EXPIRED / PROVIDER_FAILURE / MANUAL` |
| `source_checkpoint_id` | varchar(36) | 是 | FK → `research_agent_checkpoint(id)` |
| `source_checkpoint_seq` | int | 是 | 恢复起点 |
| `worker_instance_id` | varchar(128) | 是 | 本代执行者 |
| `status` | varchar(32) | 否 | `ACTIVE / SUPERSEDED / COMPLETED / FAILED` |
| `execution_config_digest` | varchar(71) | 是 | 本代 Provider / Bundle / Flag 的 canonical digest |
| `started_at` | timestamp | 否 | default `current_timestamp` |
| `superseded_at` | timestamp | 是 | 被接管时间 |

- 唯一约束：`uq_research_attempt_no(research_run_id, attempt_no)`；`uq_research_attempt_fence(research_run_id, fence_token)`。
- 索引：`idx_research_attempt_status(research_run_id, status)`。
- 与 `V071` 的关系：`research_run.attempt_no / fencing_token / run_version` 作为「当前代」缓存列，由本表派生；本表是其历史账本。**当前这两个列未被使用（§1.2），本设计首次赋予其语义。**

### 3.2 新表：`research_external_operation_receipt`

Search / Read / Model / Extract 的外部调用收据。`[目标设计]`

| 列 | 类型 | 可空 | 约束 / 语义 |
| --- | --- | --- | --- |
| `id` | varchar(36) | 否 | PK |
| `research_run_id` | varchar(36) | 否 | FK → `research_run(id)` |
| `attempt_id` | varchar(36) | 否 | FK → `research_execution_attempt(id)`，产生该收据的代 |
| `research_agent_task_id` | varchar(36) | 是 | FK → `research_agent_task(id)` |
| `cell_key` | varchar(191) | 是 | 关联 Cell（可为空，如 Discover 阶段） |
| `operation_key` | varchar(200) | 否 | **幂等键 / 操作身份**，Run 内全局唯一 |
| `operation_kind` | varchar(32) | 否 | `SEARCH / READ / EXTRACT / MODEL / ARCHIVE` |
| `provider` | varchar(64) | 是 | 如 `serper` / `jina` / `glm` |
| `adapter` | varchar(64) | 是 | Worker 侧适配器标识 |
| `tool_identity` | varchar(120) | 是 | 与 Permit 的 `tool_identity` 对齐 |
| `request_digest` | varchar(71) | 否 | canonical request digest，用于识别「同键不同内容」 |
| `receipt_status` | varchar(32) | 否 | `CONFIRMED / IN_FLIGHT / UNKNOWN / FAILED` |
| `receipt_digest` | varchar(71) | 是 | 归一化后收据 digest；`CONFIRMED` 必须有值 |
| `result_ref_kind` | varchar(32) | 是 | `EXTERNAL_SNAPSHOT / EVIDENCE / INLINE` |
| `result_ref_id` | varchar(64) | 是 | 指向 `research_external_snapshot.id` 或 `source_evidence.id` |
| `provider_request_id` | varchar(160) | 是 | `IN_FLIGHT` 时用于向 Provider 查询 |
| `billable` | boolean | 否 | default `true`；`false` 表示缓存命中 |
| `consumed_json` | json | 是 | 该操作对预算账本的增量（键同 `_RESERVATION_KEYS`） |
| `created_at` | timestamp | 否 | default `current_timestamp` |
| `settled_at` | timestamp | 是 | 由 `IN_FLIGHT` 转 `CONFIRMED/UNKNOWN` 的时间 |

- 唯一约束：`uq_research_operation_key(research_run_id, operation_key)`（**去重的核心**）。
- 索引：`idx_research_operation_status(research_run_id, receipt_status)`；`idx_research_operation_task(research_agent_task_id, operation_key)`；`idx_research_operation_attempt(attempt_id)`。
- 复用关系：Read/Fetch 的**内容**继续存 `research_external_snapshot`（`V079`），本表只存收据与指针；Model/Extract 的原子提交继续走 `research_agent_completion`（`V045`），本表提供 Operation-Key 粒度。
- **与 DR-102 的分工（避免重复存储）**：DR-102 已在 `research_agent_execution.extraction_diagnostics_json`（`V105`）持久化**任务级**抽取诊断（`termination_reason` / `rejection_counts` / `provider_receipt` digest），它属于 completion 原子事务；本表只承载**跨 Attempt 去重与复用**所需的 Operation-Key 状态（`receipt_status` / `provider_request_id` / `result_ref_id`）。DR-402 **不得**把同一份 `rejection_counts` 或模型响应摘要再写进本表，只在 `receipt_digest` 中引用同一 canonical digest。
- 与 Python 契约的对应：`agent_task_client.py` 的 `X-NoteWeave-Idempotency-Key` 已为 claim/heartbeat/complete/archive 生成幂等键（`:107 / :129 / :150-152 / :282-285`），但未持久化为可查询收据；本表即其落点。

### 3.3 新表：`research_run_budget_ledger`

Run 级预算账本（守恒对象）。`[目标设计]`

| 列 | 类型 | 可空 | 约束 / 语义 |
| --- | --- | --- | --- |
| `id` | varchar(36) | 否 | PK |
| `research_run_id` | varchar(36) | 否 | FK → `research_run(id)` |
| `ledger_key` | varchar(64) | 否 | 与 `_RESERVATION_KEYS` 同名：`llm_calls / search_calls / fetch_calls / read_calls / extract_calls / evidence_cards / evidence_appended / candidates_submitted / candidate_merges_accepted / candidate_merges_rejected` |
| `reserved_total` | bigint | 否 | default 0，只增 |
| `consumed_total` | bigint | 否 | default 0，只增 |
| `released_total` | bigint | 否 | default 0，只增 |
| `version` | bigint | 否 | default 0，乐观并发 |
| `last_checkpoint_seq` | int | 是 | 最近一次随 Checkpoint 固化时的 seq |
| `updated_at` | timestamp | 否 | default `current_timestamp` |

- 唯一约束：`uq_research_budget_ledger_key(research_run_id, ledger_key)`。
- 与 `research_budget_reservation`（`V040`）的关系：后者是**逐 Task 的预留/结算明细**，前者是**Run 级聚合**。两者由结算事务同时更新；`reserved_total/consumed_total/released_total` 必须等于明细的聚合。
- 键集合来源：`agent_task_client.py:418-422` `_RESERVATION_KEYS`（当前实现已存在），以及 `ResearchAgentCompletionReceipt.BudgetReceipt`（`ResearchAgentCompletionReceipt.java:54-65`）。

### 3.4 扩展表：`research_agent_checkpoint`（复用 + 新增列）

复用 `V040` 已有列：`checkpoint_seq / wave_no / round_no / plan_revision / entity_set_version / ledger_hash / task_high_water_mark / candidate_high_water_mark / merge_high_water_mark / budget_summary_json / summary_json`（`V040:22-38`）。

新增列：

| 列 | 类型 | 可空 | 说明 |
| --- | --- | --- | --- |
| `checkpoint_schema_version` | varchar(64) | 否 | default `research-agent-checkpoint.v1`；v2 为新格式 |
| `attempt_id` | varchar(36) | 是 | FK → `research_execution_attempt(id)`，该安全点所属代 |
| `run_fence_token` | bigint | 是 | 写入时 Run fence，用于过期回调比较 |
| `stage_watermark_json` | longtext | 是 | 已 settled 阶段的紧凑快照（见下） |
| `cell_watermark_json` | longtext | 是 | Cell 完成水位线（见下） |
| `receipt_ledger_digest` | varchar(71) | 是 | 该安全点收据账本的 canonical digest |
| `budget_ledger_digest` | varchar(71) | 是 | Run 级预算账本的 canonical digest |

`stage_watermark_json` 结构（目标态）：
```json
{
  "settled": [
    {"stage": "CELL_RESEARCH", "stage_revision": 3, "barrier_digest": "sha256:...",
     "settled_at_checkpoint_seq": 12, "blocker_count": 0}
  ],
  "active": {"stage": "VERIFYING", "stage_revision": 1, "status": "ACTIVE"}
}
```
> 依据：`research_run_stage` 现有列（`V099:1-20`）已能表达单阶段屏障；本字段只把「哪些阶段已 settled、在哪个 seq settled」压成一行，供 Hydration 免扫描判定。

`cell_watermark_json` 结构（目标态）：
```json
{
  "sealed": [{"cell_key": "...", "cell_version": 7, "cell_status": "VERIFIED"}],
  "max_sealed_cell_version": 7,
  "plan_revision": 3,
  "entity_set_version": 2
}
```
> 任一 `cell_key` 的 `cell_version` 跨 Checkpoint 必须单调不减；`sealed` 集合即 FR-403「不重新调度」的判定输入。

### 3.5 Schema 能回答的「最小恢复所需状态」清单

一次 Hydration 只需读：① `research_checkpoint_hydration_snapshot`（不可变语义）；② 本安全点的 `research_agent_checkpoint` 行（barrier/watermark/fence/digest）；③ `research_execution_attempt` 的当前 ACTIVE 行；④ `research_external_operation_receipt`（按 `receipt_status` 过滤）；⑤ `research_run_budget_ledger` 聚合行。不再依赖 Descendant Run，也不再把 Execution/Task/Outbox 当作恢复输入。

---

## 4. 不变量（可断言语句）

以下不变量必须能被单测 / 集成测试直接断言，供 DR-403/404/405 复用。

- **INV-1 收据复用（不重复计费）**：对任一 `operation_key`，若存在 `receipt_status='CONFIRMED'` 的收据，则恢复后不得新增该键的 `billable=true` 收据，也不得再次发起 Provider 调用。断言：`count(billable=true and operation_key=K) == 1`。
- **INV-2 同键不同内容拒绝**：同一 `operation_key` 配不同 `request_digest` 必须报错，不得覆盖旧收据。对应现有 idempotency 冲突语义（`ResearchBudgetAndCheckpointService.java:48-50` 的 `RESEARCH_BUDGET_IDEMPOTENCY_CONFLICT`）。
- **INV-3 Attempt fence 单调递增**：`research_execution_attempt` 中同一 Run 的 `attempt_no` 与 `fence_token` 严格递增；任一 Attempt 的 `fence_token` 小于当前 ACTIVE 代时，其 callback 不得改变 canonical state。
- **INV-4 过期回调拒绝**：回调携带 `(runId, attemptId, fenceToken)`，若 `attemptId != current_attempt` 或 `fenceToken < current_fence`，则对 `research_cell.cell_version / cell_status / source_evidence / research_agent_candidate` 的写入影响数 = 0。断言：`affected_rows == 0`（对应现有 CAS 语义 `ResearchAgentCompletionCommitter.java:779-786`）。
- **INV-5 预算守恒**：对每个 `ledger_key`，任意时刻 `consumed_total + released_total <= reserved_total`；且 `reserved_total/consumed_total/released_total` 跨 checkpoint_seq 单调不减。结算事务后，Run 级账本必须等于 `research_budget_reservation` 明细的聚合。重启前后差值：`Δreserved = Δconsumed + Δreleased`（无 in-flight 时取等号）。
- **INV-6 Cell 完成水位单调**：同一 `cell_key` 的 `cell_version` 在 `checkpoint_seq + 1` 上 ≥ 在 `checkpoint_seq` 上。已进入 `sealed` 的 Cell，在 `plan_revision / entity_set_version` 未变时不得被重新调度。
- **INV-7 阶段屏障不可回退**：`status='SETTLED'` 且 `barrier_digest=D` 的阶段，在同一 `stage_revision` 下不得回退为 `ACTIVE`；`barrier_digest` 必须能由持久化 canonical state 重算得到。
- **INV-8 Hydration 幂等**：`hydrate(runId, checkpointSeq, attemptId)` 重复执行必须产生相同 `canonical_digest`；若目标 Run 已存在 ACTIVE Attempt，必须失败而不是覆盖。
- **INV-9 Schema fail-closed**：`checkpoint_schema_version` 不在白名单、或缺少 `attempt_id / receipt_ledger_digest / budget_ledger_digest` 时，必须抛 `RESEARCH_CHECKPOINT_HYDRATION_REQUIRED` / `RESEARCH_CHECKPOINT_SCHEMA_UNSUPPORTED`，**禁止静默降级为 `CONTEXT_RESTART`**（对照 `ResearchAgentCheckpointHydrator.java:85-88` 的现有 fail 行为）。
- **INV-10 收据状态机合法**：`receipt_status='CONFIRMED'` 必须满足 `receipt_digest != null`；`IN_FLIGHT` 必须有 `provider_request_id`；`UNKNOWN` 必须进入人工对账，不得自动重试为 `billable` 调用。

---

## 5. 迁移方案

当前最大迁移版本为 `V104`（`V104__add_research_agent_completion_replay_observation.sql`）。
**编号已被 M1/M2/M3 占用**：

- `V105__add_research_agent_extraction_diagnostics.sql`（DR-102，`research_agent_execution.extraction_diagnostics_json`）；
- `V106`（DR-203，Replan 审计记录）；
- `V107` 预留给 DR-305（业务终态 / `RunCompletionGate` 的持久化，若需要）。

因此 M4 的迁移自 `V108` 起。**注意：以下编号是指示性的，必须在每个 M4 子任务派发时重新确认**——
M2/M3 的任务会持续占用编号，写死编号会导致反复撞号。

| 文件 | 内容 | 目的 |
| --- | --- | --- |
| `V108__create_research_execution_attempt.sql` | 新表 §3.1 + 为 `research_run` 的三个 V071 列补语义注释型 check（不改列） | DR-403/404 |
| `V109__create_research_external_operation_receipt.sql` | 新表 §3.2 | DR-402/404 |
| `V110__create_research_run_budget_ledger.sql` | 新表 §3.3 + 初始迁移脚本（从既有 `research_budget_reservation` 聚合回填） | DR-405 |
| `V111__extend_research_agent_checkpoint_recovery_state.sql` | `research_agent_checkpoint` 新增 §3.4 列，`checkpoint_schema_version` default `'research-agent-checkpoint.v1'` | DR-401 |

设计要点：
- **expand-only**：所有新列可空或带 default，不删除、不重命名旧列；与 `V045`「Historical rows remain nullable/LEGACY_UNBOUND; no provenance is guessed」的原则一致。
- **回填策略**：`V107` 从 `research_budget_reservation` 按 `research_run_id` 聚合出 `reserved/consumed/released`，仅回填非终态 Run；终态 Run 不回填，避免猜测。
- **旧格式必须失败而不是静默降级**：`checkpoint_schema_version='research-agent-checkpoint.v1'` 的历史行继续可读（read-model），但 Hydration 必须拒绝——现有 `available()` 已按 `schema_version` 精确匹配（`ResearchAgentCheckpointHydrator.java:52-60`），本设计要求新增 `research-agent-checkpoint.v2` 白名单，并把 `AUTO` 的回落路径改为显式失败，而非 `CONTEXT_RESTART`。
- **`CONTEXT_RESTART` 降级为显式、非默认**：由 `requestedResumeMode` 显式请求（`ResearchRunCommandService.java:127-137` 现有枚举已支持 `HYDRATE_REQUIRED` / `CONTEXT_RESTART`），`AUTO` 不再自动回落到它。
- **唯一键注意**：`uq_research_operation_key(research_run_id, operation_key)` 是去重根基；`operation_key` 生成必须确定性（Worker 现有幂等键格式 `agent-archive:{task}:{lease}:{fence}:{window}:{source}:{digest}` `agent_task_client.py:282-285` 需去掉 `lease_epoch/fencing_token` 以跨 Attempt 稳定，改为绑定 Run + 逻辑操作身份）。

---

## 6. 等价性测试清单（供 DR-403/404 直接实现）

| ID | 输入 | 断言 | 预期失败模式 |
| --- | --- | --- | --- |
| EQ-01 | 在搜索确认后写 Checkpoint，kill Worker，同 Run 新 Attempt hydrate | `operation_key` 的 `billable=true` 收据数 = 1；Search 调用计数不增 | 若重复调用 → 报 `DUPLICATE_EXTERNAL_CALL` |
| EQ-02 | 抓取确认后写 Checkpoint，新 Attempt hydrate | 复用的 `research_external_snapshot.content_sha256` 与新 Attempt 读取一致 | 缺失收据 → `RESEARCH_CHECKPOINT_HYDRATION_REQUIRED` |
| EQ-03 | 抽取确认但 `receipt_status='IN_FLIGHT'` 时中断 | hydrate 先按 `provider_request_id` 查询，不直接重放 | 无法查询且非幂等 → 置 `UNKNOWN` + 人工对账 |
| EQ-04 | 同一 Checkpoint 对同一 `attemptId` hydrate 两次 | 两次 `canonical_digest` 相同；第二次不产生新 cell/evidence 行 | 目标已有 ACTIVE Attempt → `RESEARCH_ATTEMPT_ALREADY_ACTIVE` |
| EQ-05 | 旧 Attempt 在新 Attempt 开始后回调 | canonical state 影响行数 = 0 | `fence_token` 不达 → `RESEARCH_AGENT_TASK_STALE_LEASE`（沿用现有错误码） |
| EQ-06 | 旧 Attempt 使用同 `operation_key` 但不同 `request_digest` | 拒绝，不覆盖 | `RESEARCH_OPERATION_KEY_CONFLICT` |
| EQ-07 | 恢复前后读取 Run 级预算账本 | `Δreserved = Δconsumed + Δreleased`；无重置、无重复扣费 | 账本不守恒 → `RESEARCH_BUDGET_LEDGER_DRIFT` |
| EQ-08 | Checkpoint 序列 N、N+1 的 Cell 水位比较 | `cell_version` 单调不减；`sealed` 集合只增 | 水位回退 → `RESEARCH_CHECKPOINT_HIGH_WATER_REGRESSION`（对照 `ResearchBudgetAndCheckpointService.java:194-198`） |
| EQ-09 | 阶段 settled 后中断 | hydrate 后该阶段不回到 ACTIVE；`barrier_digest` 可重算 | digest 不一致 → `RESEARCH_STAGE_BARRIER_CORRUPTED` |
| EQ-10 | 注入 v1 schema 的 Checkpoint 走 hydrate | 明确失败 | 静默降级 → 断言 `RESEARCH_CHECKPOINT_SCHEMA_UNSUPPORTED` |
| EQ-11 | `payload_json` 被篡改 1 字节 | 恢复拒绝 | `size/sha256/canonical_digest` 任一不符 → `RESEARCH_CHECKPOINT_CORRUPTED`（沿用现有 `Hydrator.verify()`） |
| EQ-12 | 恢复后继续调度 | 只对 `sealed` 之外且 `GAP/STALE` 的 Cell 建 Task | 已 sealed Cell 被重新 Taskize → 断言失败 |

> 说明：EQ-03 / EQ-05 / EQ-11 的失败语义在当前代码中已有可对照实现（`agent_task_client.py:145-179` 的 complete 重放、`ResearchAgentCompletionCommitter.java:192-200` 的 lease/fence CAS、`Hydrator.verify()` 的完整性校验），DR-403/404 只需把断言对象从「新 Run」换成「同 Run 新 Attempt」。

---

## 7. 回滚策略

当 Schema 不完整 / 迁移异常 / 不变量被破坏时，按下列顺序回退，**目标是停止自动恢复但保留人工重跑入口**：

1. **关闭恢复开关**：`checkpoint_hydration_v2`（`ResearchAgentFeatureFlagService.java:19`、`Hydration` 构造参数 `noteweave.research.checkpoint-hydration-v2`（`ResearchAgentCheckpointHydrator.java:39`）现已存在）置 false → `available()` 直接 false，停止自动 Hydration。
2. **停止创建新 Attempt**：所有进行中 Attempt 置 `SUPERSEDED`，Run 进入人工复核态；不再自动 `hydrate`。
3. **保留人工重跑入口**：沿用现有显式 `CONTEXT_RESTART` / 新建 Run 的 API（`ResearchRunController` 的 `resume-from-checkpoint`，`ResearchRunController.java:88-96`），但必须作为**显式人工动作**，不自动触发。
4. **只读不回写**：历史 Completion / Evidence / Digest / Checkpoint / Artifact 不回写（与架构文档 L220 一致）。
5. **预算账本冻结**：`research_run_budget_ledger` 只读，禁止释放/结算，避免回滚期间产生新的守恒缺口；由 DR-405 的账本断言给出漂移报告后再人工修正。
6. **可观测**：回滚必须产生结构化记录（新增 `RESEARCH_CHECKPOINT_RECOVERY_DISABLED` 之类的 reason code），不得静默。

---

## 8. 外部依赖与卡点（明确判断）

### 8.1 依赖 M1/M2/M3 尚未稳定的状态定义

| 依赖 | 影响的 Schema 部分 | 卡点性质 |
| --- | --- | --- |
| **DR-101 `ExtractionResult` 与拒绝原因枚举**（M1，`执行计划` §7.1 / M1 表） | `research_external_operation_receipt` 中 `EXTRACT` 类收据的 `receipt_status` 与 reason code、`result_ref_kind` 的取值 | 字段可先预留为 varchar + schema_version，但**枚举白名单必须等 DR-101 冻结**，否则退出条件「Schema 能表达恢复所需最小状态」无法证明 |
| **DR-201 `CellRecoveryPolicy` 决策枚举**（M2，§7.2：`RETRY_EXTRACTION / REREAD_WINDOW / TARGETED_SEARCH / COUNTEREVIDENCE_SEARCH / FREEZE_UNRESOLVED / STOP_BUDGET_EXHAUSTED`） | `cell_watermark_json.sealed` 的判定规则（哪些 `cell_status` 属于终态、哪些可重调度） | 「只调度未完成 Cell」的语义取决于此；**Cell 状态集合不稳定前，DR-403 只能半实现** |
| **DR-301/DR-305 Verifier 与 `RunCompletionGate` 诚实终态**（M3，§7.3） | `stage_watermark_json` 中 `VERIFYING / SYNTHESIZING` 阶段的 barrier，以及终态与「未解决 Cell」的关系 | 目标态 `COMPLETED_VERIFIED / COMPLETED_WITH_LIMITATIONS / INSUFFICIENT_EVIDENCE` 未落地前，阶段屏障只覆盖 `CELL_RESEARCH` |
| **外部调用归一化契约**（Provider Adapter，§7.4「Provider Adapter 只负责外部协议和收据标准化」） | `receipt_digest` 的归一化规则、`provider_request_id` 可得性 | 决定 `IN_FLIGHT` 是否可查询；不可查询的 Provider 只能走 `UNKNOWN` |

### 8.2 「哪些能让 DR-402 立刻开工，哪些必须先等 M1/M2」

**可以立即开工（不阻断）**：
- DR-402 的**安全点触发**：在搜索 / 抓取 / 抽取确认后写 `research_agent_checkpoint` + `research_external_operation_receipt`（`operation_kind` 先按 `SEARCH/READ/EXTRACT` 落库，reason code 字段版本化预留）。
- DR-404 的**fencing 与 stale callback 拒绝**：Task 级 `lease_epoch/fencing_token`（`V039`，claim 时 `+1`，见 `ResearchAgentTaskService.java:100-108`）与 completion 的 CAS 校验（`ResearchAgentCompletionCommitter.java:192-200`、`:779-786`）已具备基础；补 Run 级 Attempt 即可，且可与 M1 并行。
- `V105` / `V106` 迁移本身（新表不依赖 M1/M2 的业务枚举，只依赖字段占位）。

**必须先等 M1/M2/M3**：
- DR-403 的**「只调度未完成 Cell」**最终语义：需 DR-201 冻结 Cell 决策枚举，否则 `cell_watermark_json` 的 `sealed` 判定会在 M2 返工。
- `Extract` 类收据的**结构化 reason code**：需 DR-101 冻结 `ExtractionResult`，否则契约测试无稳定断言对象。
- 阶段屏障从 `CELL_RESEARCH` 扩展到 `VERIFYING/SYNTHESIZING`：需 DR-301/DR-305。
- DR-405 的**账本守恒最终断言**：`V107` 可先建表并回填，但「不重复扣费」的端到端断言依赖 DR-404 + 稳定的预算键集合。

### 8.3 Schema 设计对「稳定 Cell 状态」的前置假设

本设计的 `cell_watermark_json` 与 INV-6/INV-7 假定：Cell 语义状态在 M2/M3 期间会**单调收敛**（GAP → 候选 → 验证通过 / 冲突 / 需修复 / 冻结 / Stale），且冻结 Cell 不因无关 Cell 重试而回退（验收矩阵「Table-as-State」行，`执行计划` §9）。若 DR-201/DR-301 引入新的可回退状态，则 `sealed` 判定与 INV-6 必须同步修订，并升 `checkpoint_schema_version`。

---

## 9. 与现有 V040 / V100 的差异点（汇总）

| 维度 | V040 `research_agent_checkpoint` | V100 `research_checkpoint_hydration_snapshot` | 目标 Schema |
| --- | --- | --- | --- |
| 恢复身份 | 无 Attempt 概念 | payload 记录 `source_research_run_id` | 新增 `research_execution_attempt`，同一 Run 换代 |
| 外部收据 | 无 | 无（只有 budget_summary） | 新增 `research_external_operation_receipt`（Operation-Key 去重 + 状态机） |
| 预算 | `budget_summary_json`（Map，仅记录） | 从 Command 透传 | 新增 `research_run_budget_ledger`（守恒聚合） |
| 阶段屏障 | `stages[]` 全量拷贝进 payload | 全量拷贝 | 增加 `stage_watermark_json` 紧凑水位 + 非 CELL_RESEARCH 阶段 |
| Cell 水位 | 无 | `cells[].cell_version`（全量） | 增加 `cell_watermark_json`（sealed 集合 + max version） |
| Fence | 无 | 无 | `attempt_id` + `run_fence_token` 落到 checkpoint 行 |
| 失败模式 | HWM 回退拒绝（`:194-198`） | size/sha256/canonical_digest 校验 | 增加 schema_version 白名单 fail-closed |
| 旧口径 | — | `resumeFromCheckpoint` 造 Descendant Run | 同 Run + 新 Attempt；`CONTEXT_RESTART` 仅显式人工 |

---

## 10. 附：关键依据索引

- 架构口径：`docs/DeepResearch-ResearchAgent架构文档.md` L122–L132（Hydration 与外部 Receipt）、L151–L157（幂等 / fencing）、L189–L192（恢复类指标）、L220（回滚不回写）。
- 执行计划：`docs/DeepResearch-简历能力落地执行计划.md` §3（简历→证据映射）、§7.4（`CheckpointRecovery` 接口）、M4 表（DR-401–405）、§9（验收矩阵 Checkpoint/Fencing 行）、§10（故障注入矩阵）、§14（风险与回滚）。
- Java：`ResearchAgentCheckpointSnapshotCompiler.java`、`ResearchAgentCheckpointHydrator.java`、`ResearchCheckpointStore.java`、`ResearchCheckpointIntegrity.java`、`ResearchBudgetAndCheckpointService.java`、`ResearchRunCommandService.java`、`ResearchAgentRunnableWorkService.java`、`ResearchAgentTaskService.java`、`ResearchAgentCompletionCommitter.java`、`ResearchAgentCompletionReceipt.java`、`ResearchAgentFeatureFlagService.java`、`ResearchAgentRunAdvancementService.java`。
- 迁移：`V015`、`V038`、`V039`、`V040`、`V044`、`V045`、`V048`、`V062`、`V071`、`V074`、`V079`、`V097`、`V099`、`V100`、`V101`（`V105` 由 DR-102、`V106` 由 DR-203 占用，M4 新增自 `V107` 起）。
- Python：`workers/research-worker/app/agent_task_client.py`（claim/heartbeat/complete/archive 与 `_RESERVATION_KEYS`）、`app/execution_control.py`（`LeaseKeeper` / `ExecutionControl`）。
