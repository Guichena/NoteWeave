# Research Agent MA2：单进程受限并行执行方案

> 文档状态：CURRENT  
> 阶段：MA2  
> 前置：MA0、MA1 已完成；Research Worker 全量 `230 passed`（2026-07-15）  
> 上位设计：[受控多 Agent 并行架构与改造方案](ResearchAgent-受控多Agent并行架构与改造方案.md)  
> 本阶段边界：单个 Research Worker 进程内，对已冻结的 `CellTaskBundle` 做有限并发 proposal execution；canonical ledger 的 verifier 与 merge 仍由单一、稳定顺序的 Coordinator 收口。

## 1. 阶段目标

MA1 已将 “已有 evidence 如何写入 cell” 改造为 `Bundle → Candidate → Verifier → Merge Gate`。MA2 仅替换其中 bundle execution 的执行方式：

```text
frozen ledger + post-extraction evidence
  -> deterministic CellTaskBundle plan
  -> LocalParallelScheduler (bounded, isolated task snapshot)
  -> immutable AgentExecutionResult collection in task order
  -> existing CellVerifier
  -> deterministic single-writer Merge Gate
  -> row / requirement recomputation
```

新增 feature flag：

```text
SEQUENTIAL_V1       # 旧路径，默认
SEQUENTIAL_V2       # MA1 任务化路径，concurrency=1
LOCAL_PARALLEL      # MA2 本地 bundle proposal 并行
```

`LOCAL_PARALLEL` 的上限由既有 `NOTEWEAVE_RESEARCH_AGENT_MAX_CONCURRENCY` 控制；默认值仍为 1，只有显式切换 mode 才启用。本阶段建议灰度首值为 2，硬上限由 scheduler 限制为配置值且不超过 bundle 数。

## 2. 本阶段刻意不做的事

- 不让任何并发 Agent 直接更新 `ResearchStateCell`、row、report 或 checkpoint。
- 不并发运行 Cell Verifier、Merge Gate、Local/Global Verifier；它们保持 single writer / wave barrier 后执行。
- 不拆 Search/Fetch/Read/Extract 成独立的分布式 Agent 工具链。MA1 的 executor 目前只把已经抽取的 evidence 投影成 candidate，因此 MA2 证明的是**并行调度、隔离、乱序收集和确定性合并**，不是实际 provider 的延迟收益。
- 不接入 Backend append-only execution 表、lease、fencing reaper、Redis 全局配额或 Kafka task consumer；这些属于 MA3/MA4。
- 不因使用 `ThreadPoolExecutor` 宣称“真实搜索多 Agent 已上线”。真实工具并行只可在其每个 task 的 budget、取消、provider 限流和 durable result 都就绪后启用。

## 3. 设计与不变量

### 3.1 Scheduler 合约

新增 `app/local_parallel_scheduler.py`，提供：

```python
execute_local_parallel_bundles(
    bundles,
    cells_by_id,
    max_concurrency,
    execute_bundle=execute_sequential_bundle,
    cancellation_checker=None,
) -> LocalParallelExecutionSummary
```

它必须满足：

1. 每个输入 task 至多执行一次；返回结果与输入 bundle 顺序相同，而不是 future 完成顺序。
2. `1 <= actual_concurrency <= min(configured_concurrency, bundle_count)`；空 bundle 返回空结果，不创建线程池。
3. 每个 task 获得自己的 `CellTaskBundle` 深拷贝和**只包含目标 cell 的深拷贝 map**；一个 executor 对本地对象的误修改不得泄漏到 ledger 或其他 task。
4. executor 运行时异常必须转换为同 task、同 lease/fencing 的 `AgentExecutionResult(FAILED, EXECUTOR_ERROR:...)`，不能让一个 bundle 使整轮丢失其他已完成结果。
5. cancellation 在提交前或 worker 开始前被检测时生成可审计 `CANCELLED` result；未开始的 bundle 不执行。
6. scheduler 不拥有 LLM client、共享 memory、全局可变 call record 或 canonical state；未来真正的 Agent executor 必须通过 factory 在 worker 内创建独立实例。

### 3.2 Merge 与乱序规则

- Scheduler 可按任意顺序完成；Coordinator 将 result 按原 bundle 序、cell key 稳定地送入现有 verifier/merge 循环。
- 所有 bundle 都来自同一 frozen entity-set、plan revision 和 round snapshot；planner 已保证一个 cell 在一轮中不属于两个 bundle。
- Merge 仍检查 expected cell version、plan/entity-set、lease 和 fencing。MA2 不依赖 “线程没撞上” 来保证安全。
- 单进程失败 result 只影响该 task；其 cell 进入原有 `NOT_ENOUGH_INFO` / recovery 路径，而不是被误标 VERIFIED。

### 3.3 可观测性

`verify_cells` trace 新增：

```text
execution_mode
configured_max_concurrency
effective_max_concurrency
observed_max_in_flight
candidate_execution_count
candidate_count
execution_failure_count
execution_cancelled_count
merge_accepted_count / merge_rejected_count
```

这些值是 MA2 的内部调度证据；没有真实 Search/Fetch/LLM 的 A/B 数据时，它们不能用于承诺 wall-clock SLO。

## 4. TDD 执行顺序

### Red 1：受限并发与稳定收集

- 三个 bundle、配置 2 时观察到的同时 in-flight 不超过且可达到 2。
- future 完成顺序被故意打乱后，summary results 仍严格与 bundle 输入顺序一致。
- 配置 1 时不创建并行行为，结果与 `execute_sequential_bundle` 等价。

### Red 2：隔离与失败语义

- 注入 executor 改写自己的 cell snapshot 后，原 ledger 和其他 task snapshot 不变。
- 单个 executor 抛异常时，其结果为 `FAILED + EXECUTOR_ERROR`，同 wave 的其他 task 仍完成。
- 取消前/执行前的 bundle 变为 `CANCELLED`，没有 candidate，且不触发 executor。

### Red 3：ResearchToolbox 集成

- `LOCAL_PARALLEL` 走 MA1 的 proposal/verifier/merge 语义而不是 V1 分支。
- 两个不同 cell 的支持证据在任意 executor 完成顺序下，都获得相同 canonical cell、row 与 requirement 结果。
- trace 报告实际并发上限及无失败；`SEQUENTIAL_V2` trace 的 observed in-flight 为 1。
- V1 仍不产生 merge version/audit 写入。

## 5. 预计变更

新增：

- `workers/research-worker/app/local_parallel_scheduler.py`
- `workers/research-worker/tests/test_ma2_local_parallel_scheduler.py`
- `workers/research-worker/tests/test_ma2_local_parallel_integration.py`

修改：

- `workers/research-worker/app/research_tools.py`
- `workers/research-worker/app/config.py`（仅校验/说明 mode 与上限时）
- `workers/research-worker/tests/test_ma1_sequential_candidate_executor.py`（如需固定 shared trace 契约）
- `.env.example`、Compose（仅在新增参数时；默认不改变）

## 6. 验收与退出门槛

```powershell
python -m pytest workers/research-worker/tests/test_ma2_local_parallel_scheduler.py -q
python -m pytest workers/research-worker/tests/test_ma2_local_parallel_integration.py -q
python -m pytest workers/research-worker/tests/test_ma0_agent_contracts.py workers/research-worker/tests/test_ma0_merge_gate.py workers/research-worker/tests/test_ma1_cell_task_planner.py workers/research-worker/tests/test_ma1_sequential_candidate_executor.py -q
python -m pytest workers/research-worker/tests -q
python -m py_compile workers/research-worker/app/local_parallel_scheduler.py workers/research-worker/app/research_tools.py
docker compose --profile app config --quiet
git diff --check
```

MA2 只有在以下条件同时成立时才完成：

- 所有红灯转绿，Worker 全量回归通过；
- 没有共享 cell/ledger mutation、没有乱序导致的 canonical 结果不确定性；
- 默认仍是 `SEQUENTIAL_V1`，`LOCAL_PARALLEL` 必须显式启用；
- 文档和 trace 不夸大为分布式或真实 provider 并行；
- MA3 之前不将并发 result 写入现有 Backend delete/rebuild 路径。

## 7. 执行记录

### 完成情况（2026-07-15）

- 新增 `app/local_parallel_scheduler.py`。它使用受限 `ThreadPoolExecutor` 执行不同 bundle；`max_concurrency=1` 时走同步路径，避免给 `SEQUENTIAL_V2` 引入额外线程。
- 每个 task 在提交前获得独立的 bundle/cell 深拷贝，且只可见自身目标 cell；scheduler 不持有 canonical ledger、LLM client 或共享调用记账。
- future 可乱序完成，但结果严格按 planner 输入顺序返回给 Coordinator；CellVerifier 与 Merge Gate 仍在主线程稳定收口。
- executor 异常被转为相同 task/lease/fencing 的 `FAILED + EXECUTOR_ERROR:<type>`；布尔取消检查会产生 `CANCELLED + CANCELLATION_REQUESTED` 的审计结果。
- `ResearchToolbox.verify_cells` 为显式 `LOCAL_PARALLEL` 启用 scheduler，并把 configured/effective/observed concurrency、failure/cancel 计数写入 trace。`SEQUENTIAL_V1` 默认路径未改变。

### 验证

- MA2 scheduler + integration：`6 passed`。
- MA0/MA1/MA2 定向集：`26 passed`。
- Research Worker 全量：`236 passed`。
- `py_compile`、`docker compose --profile app config --quiet`、`git diff --check` 通过（最后一项仅输出工作区既有 CRLF 提示）。

### 仍然不成立的声明

MA2 没有并行 Search/Fetch/Read/Extract，也没有真实 LLM/provider A/B、分布式 lease、全局限流或 Backend 增量 CAS。因此本阶段只能称为“受控本地并行 candidate proposal”，不得称为真实 provider 并行或分布式多 Agent Research。
