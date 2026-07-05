# 阶段5：Deep Research 与 Memory

## 1. 阶段目标

这一阶段的目标有两条：

1. 把 `Deep Research` 做成工作台里的独立长任务能力。
2. 把 `Memory` 做成运行时控制层，而不是事实检索层。

当前已经按“先稳闭环、再上重量级能力”的顺序推进：

1. 先完成 Memory 主对象、晋升、编译和聊天接入。
2. 再完成 Research Run / Worker / 结果回传骨架。
3. 最后再逐步接真实搜索、真实读取和真实研究循环。

## 2. 当前已经完成的部分

### 2.1 Memory 当前状态

Memory 第一阶段已经打通最小闭环：

```text
memory_signal
  -> memory_candidate
  -> memory_object
  -> control pack
  -> ChatService
```

已经落地：

1. `memory_signal`
2. `memory_candidate`
3. `memory_object`
4. `memory_usage_log`
5. `MemoryCompilerService`
6. `Chat Control Pack` 注入 QA / Note / Wiki

当前边界仍然严格成立：

1. Memory 不进入事实检索链路。
2. Memory 不参与 citation 证据排序。
3. Memory 只负责风格、结构、禁用路径、交互策略。

### 2.2 Research Run 当前状态

已经具备：

1. `research_run`
2. `research_trace`
3. `POST /api/v2/workspaces/{workspaceId}/research-runs`
4. `GET /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}`
5. `GET /internal/worker/research-tasks/{taskId}/input`
6. `POST /internal/worker/tasks/{taskId}/progress`
7. `POST /internal/worker/tasks/{taskId}/complete`
8. `POST /internal/worker/tasks/{taskId}/fail`
9. Research Worker `POST /tasks/{taskId}/run`
10. `POST /internal/worker/research-outbox/dispatch`

这里的 Java 侧当前仍然是 Research / Artifact 的任务编排、输入装配、状态落库和 worker 回调骨架，不是完整 Research 执行引擎。真正的研究内部循环先放在 Python Research Worker 中推进。

Research Run 的资料范围按创建任务时的 `source_scope_json` 固化为 source id 快照。Worker 拉取输入时按这个快照加载资料标题、摘要和首个 `source_window` 文本，不再重新读取当前工作台最新资料列表，避免长任务执行过程中新增文件污染既有 Research Run。

Research Run 详情接口会返回最终报告、source scope 快照、Research Control Pack 和 `research_trace` 列表。Worker 完成回调时，`FINAL_REPORT` trace 会保存 `result_payload`，因此 `search_hits`、`read_windows`、`evidence_cards`、`branch_decisions`、`state_ledger`、verifier 结果可以通过详情接口回看。

Research Worker 现在提供 `callback.py`、`POST /tasks/{taskId}/run` 和 Kafka consumer 执行入口。HTTP 入口用于本地调试和复用执行逻辑；正式异步路径由 worker 消费 `noteweave.research.run` Kafka 消息，按 `task_id` 拉取 Java worker input，运行研究 loop，逐阶段回调 progress，最终回调 complete；异常时回调 fail。

Java 侧已经提供最小 Kafka publisher：扫描 `noteweave.research.run` 的 READY 消息，发布到 Kafka，成功后把消息标记为 `SENT`。当前闭环是 `Research Run -> task_outbox -> Kafka -> Python Research Worker -> Java callback -> research_trace`。

### 2.3 Research Worker 当前内部设计

当前 Research Worker 采用的是轻量研究闭环，不是空壳：

```text
planner
  -> query bundle
  -> workspace search hits
  -> bounded read windows
  -> evidence cards
  -> table-as-state ledger
  -> branch recovery
  -> local verifier
  -> global verifier
  -> report writer
  -> Java callback
```

当前已经具备这些工程语义：

1. 先编译研究计划、查询集合和 Stop Contract。
2. 查询集合包含直接查询、资料定向查询、覆盖缺口检查和反证证据检查四类 `search_angles`。
3. 用 `workspace search hits` 表示 Research Run 快照资料范围内的检索命中，并记录 `matched_fields`、`coverage_score` 和 `retrieval_reason`。
4. 用 `bounded read windows` 控制工具结果保留预算，避免无限塞上下文。
5. 用 `evidence cards` 把读取窗口转换成可验证证据单元。
6. 用 `Table-as-State` 保存来源、阅读目标、证据卡、支持度、冲突度和验证注记。
7. 用 `branch recovery` 在无命中、无窗口、无证据或冲突证据时生成受控恢复计划。
8. 用 `Local Verifier` 校验问题、query bundle、search hit、read window、evidence card、ledger 和 evidence policy。
9. 用 `Global Verifier` 决定是直接写报告，还是带 guardrails 写报告。
10. 最终报告输出包含状态账本、证据卡、分支决策和验证结果。
11. 用 Java callback adapter 将阶段进度、最终结果和失败状态回传主系统。

## 3. 当前关键文件

### 3.1 Memory

1. [backend/src/main/java/com/noteweave/memory/MemoryController.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/memory/MemoryController.java)
2. [backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java)
3. [backend/src/main/java/com/noteweave/chat/ChatService.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/chat/ChatService.java)

### 3.2 Research

1. [workers/research-worker/app/models.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/models.py)
2. [workers/research-worker/app/planner.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/planner.py)
3. [workers/research-worker/app/search.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/search.py)
4. [workers/research-worker/app/reader.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/reader.py)
5. [workers/research-worker/app/extractor.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/extractor.py)
6. [workers/research-worker/app/branch.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/branch.py)
7. [workers/research-worker/app/state.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/state.py)
8. [workers/research-worker/app/verifier.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/verifier.py)
9. [workers/research-worker/app/reporter.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/reporter.py)
10. [workers/research-worker/app/runner.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/runner.py)
11. [workers/research-worker/app/callback.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/callback.py)

## 4. TDD 要求

### 4.1 已落地测试

Java：

1. [backend/src/test/java/com/noteweave/Phase5MemoryContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase5MemoryContractTest.java)
2. [backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java)

Python：

1. [workers/research-worker/tests/test_runner.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_runner.py)
2. [workers/research-worker/tests/test_callback.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_callback.py)
3. [workers/research-worker/tests/test_imports.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_imports.py)

### 4.2 当前测试覆盖点

1. Memory 信号、晋升、控制包编译可用。
2. Chat Control Pack 已接入 QA / Note / Wiki。
3. Research Run 能创建任务、暴露 worker 输入、落最终结果。
4. Research Worker 能输出 `search_hits`、`read_windows`、`evidence_cards`、`branch_decisions`、`state_ledger`、`local_verifier`、`global_verifier`。
5. Research Worker Input 能按创建时 source scope 快照返回资料，并携带 `sample_text` 作为读取窗口输入。
6. Research Detail API 能返回最终报告、资料快照、控制包和研究过程 trace。
7. Research 完成回调能把 worker `result_payload` 写入 `FINAL_REPORT` trace。
8. Research Worker 能通过 callback adapter 拉取 Java input、发送 progress、complete 和 fail。
9. Research Kafka publisher 能把 READY research 消息发布到 Kafka，并把消息标记为 `SENT`。
10. Research 失败回调能同步 `research_run` 与 `task` 状态，并写入失败 trace。

## 5. 下一步施工重点

下一轮建议按这个顺序继续：

1. 把 Research 的 `workspace search hits` 从当前快照 source scope adapter 升级为真实召回或搜索 adapter。
2. 把当前手动触发的 Research Kafka publisher 升级为定时任务或后台 publisher。
3. 把 Python Kafka consumer 接入本地/部署启动脚本和进程守护。
4. 把 `Table-as-State` 从 worker payload / FINAL_REPORT trace 继续升级为更细粒度的研究过程快照。
5. 把最终研究报告变成可选回写资料。
6. 把 Research Control Pack 接入真实 prompt / tool 参数注入点。

Memory 这边下一轮建议：

1. 把 Artifact / Research Control Pack 接到真实 worker prompt 注入点。
2. 进一步收敛术语、结构和风格策略的编译规则。
3. 在不进入事实检索的前提下，增强工作台级行为偏好控制。

## 6. 当前完成定义

这一阶段当前可以认为已经完成了第一批目标：

1. Memory 已经进入聊天控制层。
2. Research 已经具备任务、回调、结果落库闭环。
3. Python Research Worker 已经具备 Search / Read / Extract / Verify / Branch / Write 的轻量研究内部结构。
4. 新增能力没有打坏 QA / Note / Wiki / Artifact 主链路。
