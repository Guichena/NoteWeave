# Research Agent MA3D：增量投影与 Legacy 隔离执行方案

> 文档状态：CURRENT  
> 阶段：MA3D（MA3 第四子阶段）  
> 前置：MA3A-C 已提供 cell CAS、task/lease、budget/checkpoint 真相表。  
> 目标：让增量执行模式从 append-only 表投影研究状态，并确保它不会触发 legacy `persistClosedLoopState` 的 delete/rebuild。

## 1. 双路径规则

新增 `research_run.agent_execution_mode`，默认 `SEQUENTIAL_V1`：

| Mode | Completion 状态持久化 | Research detail 读取 |
| --- | --- | --- |
| `SEQUENTIAL_V1` | 保留 `persistClosedLoopState` delete/rebuild，保证旧 Worker 兼容 | 现有 closed-loop snapshot |
| `SEQUENTIAL_V2` / `LOCAL_PARALLEL` | 仍由当前 Worker 完整 payload 兼容写入，尚未宣称 Backend 增量执行 | 现有 snapshot + agent projection（如有） |
| `INCREMENTAL_V1` | 禁止 delete/rebuild；report/trace 可完成，canonical state 只从 MA3 表追加/投影 | append-only task/candidate/merge/budget/checkpoint projection |

只有 internal Coordinator 可将 run 切到 `INCREMENTAL_V1`。对外 API、普通 Worker completion 不可借 payload 任意改变 mode。

## 2. 实现

- V041 为 `research_run` 增加 mode；`ResearchRunService` 仅在 mode 为 `SEQUENTIAL_V1/SEQUENTIAL_V2/LOCAL_PARALLEL` 时调用 legacy rebuild。
- `ResearchAgentProjectionService` 读取 MA3 表，输出 task status、execution、candidate、merge、budget、agent checkpoint 摘要；它是只读投影，不反写 canonical cell。
- `ResearchRunDetailResponse` 增加 `agent_execution_projection`，让前端/审计可分辨 legacy snapshot 与增量执行状态。
- Internal mode endpoint 必须由 Backend 读取 run，而不是接收 workspace 信任字段；终态 run 禁止切换。

## 3. TDD

- Red 1：legacy run completion 仍删除并重建旧 state；`INCREMENTAL_V1` completion 保留预存 cell/evidence，绝不执行 delete。
- Red 2：增量 task/candidate/merge/budget/checkpoint 通过 detail projection 可见，字段来自 append-only 表。
- Red 3：仅允许已知 mode；终态 run 不可切换；Phase6 和 MA3A-C 全部回归。

## 4. 验收

```powershell
.\mvnw.cmd -q -f backend/pom.xml -Dtest=ResearchIncrementalProjectionTest test
.\mvnw.cmd -q -f backend/pom.xml '-Dtest=ResearchIncrementalProjectionTest,ResearchBudgetAndCheckpointServiceTest,ResearchAgentTaskServiceTest,ResearchAgentCellMergeServiceTest,Phase6ResearchArtifactContractTest' test
git diff --check
```

MA3D 完成后，Backend 才具备受控多 Agent 的持久化读/写边界；Kafka 命令投递、跨进程 reaper、Redis 限流和 Worker client 仍是 MA4。

## 5. 执行记录

### 完成情况（2026-07-15）

- 新增 V041 与 `agent_execution_mode`：默认 `SEQUENTIAL_V1`；新增 `INCREMENTAL_V1` completion 显式跳过 `persistClosedLoopState` delete/rebuild，并写入 trace。
- 新增 `ResearchAgentProjectionService`：从 append-only task/candidate/merge/checkpoint 表构建只读审计投影；`ResearchRunDetailResponse` 暴露 `agent_execution_projection`。
- 新增 internal mode controller；run 终态或未知 mode 被拒绝，mode 不能由普通 Worker payload 自行改变。
- TDD 验证：`INCREMENTAL_V1` completion 保留预存 canonical cell，同时 projection 显示 agent task 与 mode；Flyway 可迁移至 V041。

### 仍未完成

MA3D 尚未把 agent task 的 candidate submit 自动串到 verifier/merge，也未将新 checkpoint 取代 legacy resume；这两项与跨进程 command/topic、reaper、Redis provider 限流一起进入 MA4。当前可称为“增量持久化模式的写入隔离与审计投影”，不可称为已完成分布式执行。
