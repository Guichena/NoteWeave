# Research Agent MA1：顺序任务化执行方案

> 文档状态：CURRENT  
> 阶段：MA1  
> 前置：MA0 已完成，Research Worker `221 passed`  
> 上位设计：`docs/ResearchAgent-受控多Agent并行架构与改造方案.md`  
> 本阶段仍保持单 Worker、并发度 1；目标是将 cell 验证路径变成真实的 bundle/candidate/verifier/merge 语义。

## 1. 阶段目标

当前 `ResearchToolbox.verify_cells` 直接把 ledger cell 交给 `CellVerifier`，并由方法内部改写 status。MA1 将把这一段重构为：

```text
post-extraction ledger
  -> bind current plan/entity-set versions
  -> deterministic CellTaskBundle planner
  -> sequential candidate proposal executor
  -> existing CellVerifier
  -> MA0 MergeGate
  -> row/requirement recomputation
```

本阶段的 candidate proposal 复用同一轮已收集的 Evidence Card，不重新发起 Search/Fetch/Read；真正按 bundle 执行工具链属于后续并发调度阶段。这样先验证状态所有权和合并语义，避免把“并发执行”和“语义重构”混在同一个风险面。

## 2. 非目标

- 不改变 Search/Fetch/Read/Extract 的阶段顺序。
- 不创建线程池或 Kafka task worker。
- 不做 Backend candidate 持久化、lease、fencing reaper。
- 不让当前 Report/Global Verifier 读取新外部表。

## 3. 设计决策

### 3.1 Bundle 生成规则

- 输入为当前 frozen ledger 的 cell；
- 按 `(entity_id, branch_id)` 分组；
- 同组按 required 优先、column key 稳定排序；
- 每个 bundle 最多 `research_agent_bundle_max_cells`（默认 3）；
- FROZEN cell 不生成 task；
- 空值/无 evidence cell 仍可生成 task，但只会产出无候选 result，保留现有 `NOT_ENOUGH_INFO` 路径；
- 同一轮中每个 cell 最多属于一个 bundle。

### 3.2 Candidate proposal 规则

对于有 `candidate_value + evidence_refs` 的 cell，生成一个 `CellCandidate`；候选值、证据、置信度均来自当前 ledger，不引入新 LLM 结论。

对于无值或无证据 cell，生成一个 `AgentExecutionResult(SUBMITTED, NO_CANDIDATE_VALUE_OR_EVIDENCE, candidates=[])`，使“没有候选”也成为可审计的执行结果。

### 3.3 Verifier 与 Merge 规则

- `CellVerifier` 仍是生成候选后的唯一语义判定器；
- SUPPORTS：调用 Merge Gate，成功后 canonical cell version +1；
- PARTIALLY_SUPPORTS / CONTRADICTS / NOT_ENOUGH_INFO：不调用 Merge Gate 写 VERIFIED，沿用现有 status 映射；
- Merge Gate 拒绝：保持原 cell 值，并记录 `MERGE_GATE:<reason>`；不得把 rejected candidate 当作 VERIFIED；
- 所有 cell 的 `plan_revision/entity_set_version` 在生成 bundle 前绑定为当前状态。

## 4. TDD 顺序

### Red 1：Bundle planner

- 同 entity 的四个 cell 按 3+1 分包；
- 不跨 entity/branch；
- FROZEN 不入包；
- required cell 排在 optional 之前；
- 每个 target 的 expected version 与 cell version 一致。

### Red 2：Sequential proposal executor

- 有完整值和 evidence 的 cell 生成 candidate；
- 空值/无证据不伪造 candidate；
- execution result 与 task 的 lease/fencing/task ID 一致；
- 使用稳定 ID，重复运行语义相同。

### Red 3：ResearchToolbox 集成语义

- SUPPORTS 通过 Merge Gate 后 version 增加；
- 非 SUPPORTS 不增加 version；
- Merge Gate 拒绝后不覆盖 cell；
- 已 VERIFIED cell 在无冲突证据时保持版本和值；
- 现有 loop/requirement/citation 回归保持通过。

## 5. 预期文件

新增：

- `app/cell_task_planner.py`
- `app/sequential_candidate_executor.py`
- `tests/test_ma1_cell_task_planner.py`
- `tests/test_ma1_sequential_candidate_executor.py`

修改：

- `app/research_tools.py`
- `app/models.py`（仅在 trace/结果需要时）
- `app/agent_contracts.py`

## 6. 验收标准

```powershell
python -m pytest workers/research-worker/tests/test_ma1_cell_task_planner.py -q
python -m pytest workers/research-worker/tests/test_ma1_sequential_candidate_executor.py -q
python -m pytest workers/research-worker/tests/test_llm_extract_verify.py -q
python -m pytest workers/research-worker/tests/test_loop_runtime.py -q
python -m pytest workers/research-worker/tests -q
```

退出门槛：

- 新路径仍由 `SEQUENTIAL_V1` 默认关闭；需要显式 `SEQUENTIAL_V2` 才启用。
- `SEQUENTIAL_V2` 与既有 fixture 的研究结果在 verdict、row readiness、requirement progress 和报告 gate 上等价。
- 不出现直接 Agent → VERIFIED 写入。
- MA1 完成不代表并行已启用。

## 7. 执行记录

### 完成情况（2026-07-15）

- 新增 `app/cell_task_planner.py`：在 frozen ledger 上按 `(entity_id, branch_id)` 生成稳定、required 优先且每包最多 3 cell 的 `CellTaskBundle`；跳过 `FROZEN/VERIFIED` cell。
- 新增 `app/sequential_candidate_executor.py`：仅将已有的 `candidate_value + evidence_refs` 投影为不可变 candidate；值或证据缺失时产生可审计的空 candidate result，不伪造证据。
- `ResearchToolbox.verify_cells` 增加显式 `SEQUENTIAL_V2` 分支。它先绑定当前 plan/entity-set 版本，再走 planner → sequential proposal → CellVerifier → MA0 Merge Gate → row/requirement projection；默认 `SEQUENTIAL_V1` 完全保持原路径。
- 增加并通过 V1/V2 行为测试：V2 支持证据合并后 version 加一且写入 `last_merge_id`；V1 不创建 merge 审计也不改变 version；跨轮重建保留 version、plan/entity-set binding 与 merge id。
- 验证：MA0/MA1 定向 `20 passed`；Research Worker 全量 `230 passed`；`py_compile`、`docker compose --profile app config --quiet`、`git diff --check` 通过（后者仅报告工作区既有 CRLF 提示）。

### 保持的边界

MA1 不执行并发，也没有将 Search/Fetch/Read/Extract 拆成独立 Agent 工具链；`SEQUENTIAL_V2` 仍是单 Worker、并发度 1 的语义桥接。Backend 仍是旧的 snapshot 持久化路径，因此 MA2 只能先做进程内、隔离 executor 的受限并发，不能提前宣称分布式多写者能力。
