# 阶段5：DeepResearch与Memory

## 1. 阶段目标

阶段 5 是亮点收口阶段，目标是：

1. 打通 Deep Research
2. 打通研究报告回写
3. 接入 Memory Control Pack

## 2. 与原始 NoteWeave Phase 的关系

当前阶段 5 不直接对应原始三模式 Phase，而是承接原始设计里单独存在的：

1. `Deep Research`
2. `Graduated Memory`

也就是说，它是三模式之外的重型执行层和长期沉淀层。

## 3. 子阶段树

```text
5.1 Research Run 骨架
5.2 验证驱动研究闭环
5.3 Memory Control Pack
```

## 4. 子阶段 5.1 Research Run 骨架

### 目标

建立 `research_run`、Research Worker 和任务状态闭环。

### 优先迁移

1. 旧版任务重试、超时与失败恢复基础设施
2. 旧版搜索 / 读取工具封装

### 推荐函数与类

Java：

1. `ResearchRunController.createResearchRun(req)`
2. `ResearchRunService.createRun(cmd)`
3. `ResearchTaskPublisher.publishResearchTask(taskId, runId)`
4. `ResearchWorkerInputController.getResearchTaskInput(taskId)`
5. `ResearchWorkerCallbackController.reportProgress(taskId, req)`

Python：

1. `load_research_task_input(task_id)`
2. `run_research_task(task_input)`
3. `report_research_progress(task_id, payload)`

### 中间件接入

MySQL：

1. `research_run`
2. `task`
3. `task_outbox`

Kafka：

1. topic：`noteweave.research.run`
2. consumer group：`noteweave-research-worker`

### TDD 要求

先写：

1. `research_run` 创建测试
2. Worker 输入接口测试
3. 进度回传测试

再写：

1. research controller
2. research task service
3. worker input API

### 完成定义

1. 用户能启动 Research
2. 前端能看到任务进度

## 5. 子阶段 5.2 验证驱动研究闭环

### 目标

第一批只实现最小 Research Harness：

1. Search
2. Read
3. Extract
4. Verify
5. Write Report

### 推荐函数与类

Python：

1. `build_research_plan(task_input)`
2. `load_table_state(run_id)`
3. `search_web(query_set)`
4. `read_source(goal, source_ref)`
5. `extract_cell_candidates(row, column, content)`
6. `verify_cell_value(candidate, evidence)`
7. `update_research_cell(run_id, rowId, columnKey, verifiedValue)`
8. `write_research_report(run_id, table_state)`
9. `submit_research_result(task_id, result)`

Java：

1. `ResearchTraceService.appendTrace(runId, traceType, payload)`
2. `ResearchCellService.upsertCell(runId, rowId, columnKey, payload)`
3. `ResearchReportSaveService.saveReportAsSource(runId)`

### 中间件接入

Kafka：

1. 研究任务输入走 `noteweave.research.run`

MinIO：

1. research 快照：
   `workspace/{workspaceId}/research/{researchRunId}/snapshot/{checkpointId}.json`

MySQL：

1. `research_row`
2. `research_cell`
3. `research_trace`

Elasticsearch：

1. 研究读取工作台资料时继续用 `nw-source-chunk`

### TDD 要求

先写：

1. table state 更新测试
2. verifier 结果测试
3. 报告输出 schema 测试
4. 失败恢复测试

再写：

1. planner
2. reader
3. verifier
4. report writer

### 完成定义

1. Research Worker 能输出研究报告
2. `research_row / research_cell / research_trace` 能稳定落地

## 6. 子阶段 5.3 Memory Control Pack

### 目标

把 Memory 接到运行时控制层，而不是接到事实检索层。

### 核心要求

1. 有 `memory_signal`
2. 有 `memory_candidate`
3. 有 `memory_object`
4. 生成 `Chat / Artifact / Research Control Pack`

### 推荐函数与类

Java：

1. `MemorySignalService.extractFromArtifactFeedback(feedback)`
2. `MemorySignalService.extractFromConversationFeedback(feedback)`
3. `MemoryCandidateService.buildCandidates(signalIds)`
4. `MemoryPromotionService.promoteCandidate(candidateId)`
5. `MemoryCompilerService.compileChatControlPack(workspaceId, answerMode)`
6. `MemoryCompilerService.compileArtifactControlPack(workspaceId, actionKey)`
7. `MemoryCompilerService.compileResearchControlPack(workspaceId, profile)`

Python / Runtime：

1. `apply_chat_control_pack(prompt, pack)`
2. `apply_artifact_control_pack(task_input, pack)`
3. `apply_research_control_pack(task_input, pack)`

### 中间件接入

MySQL：

1. `memory_signal`
2. `memory_candidate`
3. `memory_object`
4. `memory_usage_log`

Kafka：

1. 如需异步晋升，topic：`noteweave.memory.promote`

Elasticsearch：

1. `nw-memory` 只作为记忆对象检索，不参与事实召回排序

### TDD 要求

先写：

1. memory 对象读写测试
2. candidate 晋升测试
3. control pack 编译测试
4. control pack 注入测试

再写：

1. memory signal extractor
2. promotion service
3. compiler service
4. runtime injector

### 完成定义

1. Memory 能影响风格、结构、禁用路径和交互策略
2. Memory 不进入 citation，不参与 chunk 召回排序

## 7. 本阶段禁止项

1. 不做通用 Agent 平台
2. 不做复杂分支工作台 UI
3. 不做 Memory 事实检索化
