# Research 与 Artifact 任务骨架施工设计

## 1. 文档目标

这份文档只描述当前已经落地的 `Deep Research` 与 `右侧产物生成` 任务骨架，重点是把下面这条主线打通：

```text
前端发起任务
  -> Java 创建业务对象与 task
  -> 写入 task_outbox
  -> Java 发布 Kafka 消息
  -> Python Worker 消费 Kafka 并按 taskId 拉取输入
  -> Worker 回传 progress / complete / fail
  -> Java 落正式结果
```

当前版本的目标不是一次性做重型智能体平台，而是先把 `Java 主系统 <-> Python Worker` 的任务契约、结果落库、回归测试全部稳定下来，再逐步把真实读取、真实抽取、真实导出填进去。

## 2. 当前已经落地的能力

### 2.1 Java 主链路

已经完成：

1. `artifact_job`
2. `artifact_version`
3. `research_run`
4. `research_trace`
5. `POST /api/v2/workspaces/{workspaceId}/artifact-jobs`
6. `POST /api/v2/workspaces/{workspaceId}/research-runs`
7. `GET /internal/worker/artifact-tasks/{taskId}/input`
8. `GET /internal/worker/research-tasks/{taskId}/input`
9. `POST /internal/worker/tasks/{taskId}/heartbeat`
10. `POST /internal/worker/tasks/{taskId}/progress`
11. `POST /internal/worker/tasks/{taskId}/complete`
12. `POST /internal/worker/tasks/{taskId}/fail`

### 2.2 Artifact Worker 当前内部结构

当前 Artifact Worker 不是空壳了，已经有一套可讲、可测的轻量执行链：

```text
action resolve
  -> schema-gated execution plan
  -> section compose
  -> local repair
  -> output verify
  -> export markdown
```

对应模块：

1. [workers/artifact-worker/app/compiler.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/compiler.py)
2. [workers/artifact-worker/app/composer.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/composer.py)
3. [workers/artifact-worker/app/repair.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/repair.py)
4. [workers/artifact-worker/app/verifier.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/verifier.py)
5. [workers/artifact-worker/app/runner.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/runner.py)

它现在具备的工程语义是：

1. 先根据 `action_key` 编译产物执行计划，而不是直接裸生成。
2. 计划里内置 `schema gate rules`，强制约束章节结构和来源引用。
3. 先按章节生成草稿，再做一次本地修复，补齐缺失 section、去掉禁用表达。
4. 最后做输出校验，保证结果结构可控。

### 2.3 Research Worker 当前内部结构

当前 Research Worker 也不再只是 phase 占位，而是已经有一套轻量版研究闭环：

```text
planner
  -> query bundle
  -> bounded loop runtime
  -> search adapters
  -> read adapters
  -> evidence cards
  -> table-as-state ledger
  -> branch recovery
  -> local verifier
  -> global verifier
  -> report writer
```

对应模块：

1. [workers/research-worker/app/planner.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/planner.py)
2. [workers/research-worker/app/search.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/search.py)
3. [workers/research-worker/app/search_adapters.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/search_adapters.py)
4. [workers/research-worker/app/harness.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/harness.py)
5. [workers/research-worker/app/reader.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/reader.py)
6. [workers/research-worker/app/read_adapters.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/read_adapters.py)
7. [workers/research-worker/app/extractor.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/extractor.py)
8. [workers/research-worker/app/state.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/state.py)
9. [workers/research-worker/app/branch.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/branch.py)
10. [workers/research-worker/app/verifier.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/verifier.py)
11. [workers/research-worker/app/reporter.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/reporter.py)
12. [workers/research-worker/app/llm_client.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/llm_client.py)
13. [workers/research-worker/app/json_repair.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/json_repair.py)
14. [workers/research-worker/app/loop_runtime.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/loop_runtime.py)
15. [workers/research-worker/app/kafka_consumer.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/kafka_consumer.py)
16. [workers/research-worker/app/runner.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/runner.py)

它现在具备的工程语义是：

1. 先编译研究计划和查询集合，而不是直接写报告。
2. 查询集合包含直接查询、资料定向查询、覆盖缺口检查和反证证据检查。
3. `SearchAdapter` 默认走工作台资料快照，有外部搜索 key 时叠加外部搜索。
4. `CompositeSearchAdapter` 会按 `global_search_limit` 控制预算，并按 `source_id / url / title` 合并去重。
5. `search hits` 会记录命中的资料字段、覆盖度分数、搜索角度、检索理由、来源 provider 和 adapter 类型。
6. `ReadAdapter` 默认打开工作台资料窗口，外部 URL 命中在未启用网页抓取时生成可解释 fallback。
7. `CompositeReadAdapter` 按搜索命中顺序读取，并统一遵守 `tool_response_retention_budget`。
8. `read windows` 会记录 `url / provider / adapter / snapshot_status / snapshot_key`，为后续网页快照落 MinIO 做准备。
9. `LlmClient` 提供 fake 与 OpenAI-compatible 两种实现，不配置 key/model 时保持规则模式。
10. `json_repair` 能处理常见 LLM JSON 输出问题，例如 fenced JSON 和 trailing comma。
11. `evidence cards` 优先由 LLM JSON schema 输出生成，坏输出自动回落到规则抽取。
12. `LoopRuntime` 把当前链路升级为最多 2 轮的受控循环，而不是固定单轮流水线。
13. `StopContract` 控制最大轮数、搜索预算、读取窗口预算和最小证据数。
14. 用 `Table-as-State` 形式保存来源、阅读目标、证据摘录、支持度和验证注记。
15. 用 `branch recovery` 在无命中、无窗口、无证据或冲突证据时生成受控恢复计划。
16. 用 `Local Verifier` 检查问题归一化、证据覆盖、来源数量和 evidence policy，并可合并 LLM judge 警告。
17. 用 `Global Verifier` 做整体放行判断，决定是 `READY_TO_WRITE` 还是 `WRITE_WITH_GUARDRAILS`。
18. 达到 loop 预算后必须 `WRITE_WITH_GUARDRAILS`，不能无限循环。
19. 报告生成只渲染真实 `evidence_cards` 中存在的证据 ID，避免 ghost evidence 进入最终报告。
20. Worker result 会输出 `research_checkpoint_candidate` 与 `report_source_candidate`。
21. Java 可以把完成后的研究报告保存为 `GENERATED_RESEARCH_REPORT` 资料，并进入 source parse/index 链路。
22. Research API server 与 Kafka consumer 已有 conda 启动脚本。

## 3. 与 Memory 的关系

当前 Artifact / Research 都已经接上了 `Control Pack`，但仍然严格遵守既定边界：

1. Memory 只作为运行时控制注入。
2. Memory 不作为事实证据。
3. Memory 不替代工作台资料检索。
4. Memory 不参与 citation 排序。

也就是说：

1. Research 的事实基础仍然来自工作台资料和后续搜索证据。
2. Artifact 的原材料仍然来自工作台资料与任务输入。
3. Memory 负责风格、结构、禁用路径、交互约束，不负责“证明事实”。

## 4. 当前明确不做的事情

这一批骨架刻意没有做重：

1. Research 还没有可恢复 checkpoint / URL snapshot 的 MinIO 持久化，当前 checkpoint 仍在 result payload 层。
2. Artifact 还没有接真实 `Skill / MCP / 用户自定义外部能力`。
3. Research 报告还没有自动回写成工作台正式资料。
4. Artifact 导出 PDF / MD 文件还没有正式接 MinIO 持久化链路。
5. Research 还没有把网页快照正式保存到 MinIO，当前 URL reader 只做可选轻量抓取和 fallback 窗口。

## 5. TDD 与回归

### 5.1 Java 合约测试

当前主测试文件：

1. [backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java)

已覆盖：

1. `artifactJobShouldCreateTaskExposeWorkerInputAndPersistVersion`
2. `researchRunShouldCreateTaskExposeWorkerInputAndPersistFinalReport`
3. `researchOutboxDispatcherShouldPublishKafkaMessageAndMarkOutboxSent`
4. `workerFailShouldMarkArtifactTaskAndJobAsFailed`
5. `workerFailShouldMarkResearchTaskAndRunAsFailed`

### 5.2 Python Worker 测试

1. [workers/research-worker/tests/test_runner.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_runner.py)
2. [workers/research-worker/tests/test_kafka_consumer.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_kafka_consumer.py)
3. [workers/research-worker/tests/test_harness.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_harness.py)
4. [workers/research-worker/tests/test_search_adapters.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_search_adapters.py)
5. [workers/research-worker/tests/test_read_adapters.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_read_adapters.py)
6. [workers/research-worker/tests/test_llm_extract_verify.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_llm_extract_verify.py)
7. [workers/research-worker/tests/test_loop_runtime.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/tests/test_loop_runtime.py)
8. [workers/artifact-worker/tests/test_runner.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/tests/test_runner.py)

当前测试重点：

1. 输入模型可解析。
2. runner 能走完整 phase sequence。
3. Kafka 消息可以触发 Research Worker 按 task id 拉取输入并回调 Java。
4. 输出结构包含执行计划、状态账本、验证结果等关键字段。
5. SearchAdapter 无外部 key 时只走工作台资料，有外部 key 时可以合并外部搜索结果。
6. SearchAdapter 结果必须携带 `search_angle / retrieval_reason / confidence_score`。
7. ReadAdapter 能打开 workspace window，并能为 external url hit 生成 fallback 或 fetched window。
8. LLM Extractor 能处理合法 JSON、可修复 JSON 和不可用输出 fallback。
9. Local Verifier 能拒绝无证据结论，并可合并 LLM judge 的警告。
10. Report Writer 只允许真实 evidence card 进入证据账本展示。
11. LoopRuntime 默认最多 2 轮，冲突证据触发反证复核，预算耗尽强制 guardrails 收口。
12. Worker 输出 checkpoint/report source candidate。
13. Java `save-report-as-source` 能把研究报告回写成工作台资料，并保留 `generated_by=research_agent`。
14. Research Worker smoke 覆盖 health、consumer import 和 Kafka bootstrap 配置覆盖。

### 5.3 已通过的回归范围

当前已回归通过：

1. `Phase1And2ContractTest`
2. `Phase3NoteWikiContractTest`
3. `Phase5MemoryContractTest`
4. `Phase6ResearchArtifactContractTest`
5. `workers/research-worker` pytest
6. `workers/artifact-worker` pytest

## 6. 下一步建议

### 6.1 Artifact 下一步

下一轮优先补这几件事：

1. 把 `action_key -> outline` 升级为正式产物模板配置。
2. 给章节生成增加更细的 `source bundle` 和 `style profile` 输入。
3. 接入导出链路，把 markdown / pdf 正式持久化。
4. 再决定是否接内置 skill 与默认 MCP。

### 6.2 Research 下一步

下一轮优先补这几件事：

1. 把 URL snapshot 与 checkpoint 正式保存到 MinIO。
2. Research Run 过程态细粒度落库。
3. Artifact Worker 的 Skill / MCP 编排增强。

## 7. 完成定义

这份骨架施工文档当前对应的完成定义是：

1. Artifact 与 Research 都能创建业务对象和 task。
2. Worker 都能按 taskId 拉取输入。
3. Worker 都能回传 progress / complete / fail。
4. Java 都能把最终结果落进正式业务表。
5. Memory Control Pack 已有稳定注入点。
6. 这批新增能力没有打坏 QA / Note / Wiki / Memory 主链路。
7. Artifact / Research 的失败回调都能同步业务对象状态与 task 状态。
