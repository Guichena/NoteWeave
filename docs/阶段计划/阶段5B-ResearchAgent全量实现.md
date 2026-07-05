# 阶段5B-Research Agent 全量实现

## 1. 目标

这份文档用于把 `Deep Research 智能体` 从当前可运行 MVP 推进到完整可交付形态。

当前已完成的是：

1. Java 创建 `research_run / task / task_outbox`。
2. Research 任务通过 `noteweave.research.run` Kafka 下发。
3. Python Research Worker 能消费 Kafka、拉取 Java 输入、回调 progress / complete / fail。
4. Worker 内部已有轻量 `Plan -> Search -> Read -> Extract -> Verify -> Branch -> Report` 闭环。
5. Search 命中已具备 `search_angles / matched_fields / coverage_score`。
6. `ResearchHarness` 与 `ResearchStepTrace` 已接入 runner，结果里会输出 `harness_trace / harness_summary`。
7. `SearchAdapter` 已完成第一阶段落地：无外部 key 时只走工作台资料，有外部 key 时叠加外部搜索并做预算控制、结果归一化和去重。
8. `ReadAdapter` 已完成第一阶段落地：workspace hit 打开资料窗口，external url hit 在未启用网页抓取时生成可解释 fallback。
9. `LlmClient / JSON Repair / LLM Extract / LLM Verify` 已完成第一阶段落地：无模型配置时保持规则 fallback，有模型配置时尝试 OpenAI-compatible JSON 契约。
10. `LoopRuntime / Stop Contract` 已完成第一阶段落地：默认最多 2 轮，冲突证据触发反证复核，预算耗尽强制 guardrails 收口。
11. `research_checkpoint_candidate / report_source_candidate` 已输出到 Worker result payload。
12. Java 已支持 `save-report-as-source`，能把完成后的研究报告写回工作台资料池并复用 source parse/index 链路。
13. Research Worker 启动脚本与 smoke test 已完成第一阶段落地。

还没有完成的是：

1. checkpoint / URL snapshot 正式保存到 MinIO。

## 2. 参考项目映射

### 2.1 MiroFlow / MiroThinker

吸收点：

1. 单主 Agent 通过工具管理器调用搜索、读取、推理工具。
2. 每次任务都有结构化 tracer，记录步骤、状态、错误和最终结果。
3. LLM client 与工具执行解耦，方便替换模型与工具实现。
4. 长任务需要可观测执行轨迹，而不是只存最终答案。

NoteWeave 落地口径：

1. 不照搬多子 Agent 复杂形态。
2. 保留单主 Research Worker。
3. 引入 `ResearchHarness` 作为 LLM/规则双轨执行入口。
4. 引入 `ResearchStepTrace`，先写入 worker result payload，后续再落 DB checkpoint。

### 2.2 Marco-Agent-DeepResearch

吸收点：

1. 搜索、访问页面、最终回答由工具调用循环驱动。
2. 有强制收口策略，达到回合或预算上限后必须生成答案。
3. Accuracy profile 会打开 verify，Efficiency profile 会减少验证成本。

NoteWeave 落地口径：

1. Profile 映射到 `StopContract` 和 verifier 策略。
2. 不让模型自由无限循环。
3. 每次 Search / Read / Extract / Verify 都必须生成结构化对象。

### 2.3 Table-as-Search / TaS

吸收点：

1. 研究过程不是普通聊天历史，而是状态表。
2. Search / Read / Verify 都围绕表格状态补字段。
3. 查询不只是一句 prompt，而是一组面向字段、来源和反证的查询。

NoteWeave 落地口径：

1. 当前 `state_ledger` 是第一阶段 Table-as-State。
2. 后续升级为 `research_checkpoint` 和细粒度 `research_trace`。
3. Query bundle 保留 direct / source_scoped / coverage_gap / counterfactual。

## 3. 总体施工树

```text
5B.1 Harness 契约与 Trace
  -> 定义 ResearchHarness / ResearchHarnessMode
  -> 定义 ResearchStepTrace
  -> runner 输出 harness_trace
  -> TDD 覆盖规则模式和 fallback

5B.2 真实搜索 Adapter
  -> 定义 SearchAdapter
  -> workspace adapter 作为默认实现
  -> web search adapter 读取环境变量配置
  -> 无 key 时自动降级 workspace adapter
  -> TDD 覆盖 query bundle、搜索预算、空结果恢复
  -> 状态：已完成第一阶段实现

5B.3 读取与网页快照 Adapter
  -> 定义 ReadAdapter
  -> workspace source_window 默认实现
  -> url reader / fetched page snapshot 预留实现
  -> TDD 覆盖窗口预算、来源快照、失败降级
  -> 状态：已完成第一阶段实现

5B.4 LLM 驱动抽取与验证
  -> 定义 LlmClient 协议
  -> 默认 Fake / RuleBased client 保证本地测试稳定
  -> OpenAI-compatible client 读取 env
  -> Evidence Extractor 支持 JSON schema 输出
  -> Local / Global Verifier 支持 LLM judge + rule fallback
  -> TDD 覆盖 JSON 解析、坏输出修复、证据不足警告
  -> 状态：已完成第一阶段实现

5B.5 Loop Runtime 与 Stop Contract
  -> 引入 bounded loop，不再固定单轮
  -> 每轮决定 SEARCH_MORE / READ_MORE / VERIFY / WRITE
  -> StopContract 控制最大轮次、最大搜索数、最小证据数
  -> TDD 覆盖预算耗尽、冲突分支、强制收口
  -> 状态：已完成第一阶段实现

5B.6 过程快照与报告回写
  -> Java 增加 report save-as-source 接口实现
  -> Worker result 输出 report_source_candidate
  -> Research 完成后可由 Java 回写资料池
  -> TDD 覆盖 generated_by=research_agent 和 citation 保留
  -> 状态：已完成第一阶段实现

5B.7 启动部署与运行脚本
  -> conda worker 环境
  -> Research API server 启动
  -> Research Kafka consumer 启动
  -> docker compose / PowerShell 本地脚本
  -> TDD / smoke 覆盖 health、consumer import、配置读取
  -> 状态：已完成第一阶段实现
```

## 4. 5B.1 Harness 契约与 Trace

### 4.1 新增文件

1. `workers/research-worker/app/harness.py`
2. `workers/research-worker/tests/test_harness.py`

### 4.2 模型变更

在 `models.py` 增加：

1. `ResearchStepTrace`
2. `ResearchHarnessDecision`

字段建议：

```text
trace_id
phase
status
message
inputs
outputs
warnings
```

### 4.3 函数设计

```text
build_harness(task_input) -> ResearchHarness
ResearchHarness.plan(task_input) -> tuple[ResearchPlan, list[ResearchStepTrace]]
ResearchHarness.summarize_trace(...) -> dict
```

### 4.4 TDD

先写测试：

1. `test_harness_should_emit_planning_trace`
2. `test_runner_should_include_harness_trace_in_result_payload`
3. `test_harness_should_fallback_to_rule_mode_without_llm_config`

完成定义：

1. 不配置 LLM 时所有测试稳定通过。
2. `result_payload.harness_trace` 至少包含 `PLANNING / SEARCHING / READING / EXTRACTING / VERIFYING / WRITING`。
3. Trace 不包含大段全文，只存摘要和关键对象 id。

## 5. 5B.2 真实搜索 Adapter

状态：已完成第一阶段实现。

### 5.1 新增文件

1. `workers/research-worker/app/search_adapters.py`
2. `workers/research-worker/tests/test_search_adapters.py`

### 5.2 函数设计

```text
SearchAdapter.search(task_input, plan) -> list[ResearchSearchHit]
WorkspaceSearchAdapter.search(...)
ExternalSearchAdapter.search(...)
CompositeSearchAdapter.search(...)
```

### 5.3 已落地行为

1. `build_default_search_adapter()` 默认返回 `WorkspaceSearchAdapter`。
2. 配置 `NOTEWEAVE_RESEARCH_SEARCH_API_KEY`、`SERPER_API_KEY` 或 `SEARCH_API_KEY` 后，自动启用 `CompositeSearchAdapter`。
3. 外部搜索 transport 采用 Serper-compatible JSON HTTP 契约，且不引入额外 Python 依赖。
4. `CompositeSearchAdapter` 按 `global_search_limit` 控制总搜索预算。
5. 合并结果时按 `source_id / url / title` 去重，工作台资料优先级高于外部同名结果。
6. 外部搜索命中统一归一化为 `ResearchSearchHit`，携带 `url / provider / adapter / search_angle / retrieval_reason / confidence_score`。
7. runner 已从直接调用 `run_workspace_search` 改为调用 `run_research_search`。

### 5.4 TDD

1. 无外部 key 时只使用 workspace adapter。
2. 有外部 key 时外部搜索结果与 workspace 命中合并去重。
3. 所有 hit 必须有 `search_angle / retrieval_reason / confidence_score`。
4. 空结果必须触发 branch recovery。

## 6. 5B.3 读取 Adapter

状态：已完成第一阶段实现。

### 6.1 新增文件

1. `workers/research-worker/app/read_adapters.py`
2. `workers/research-worker/tests/test_read_adapters.py`

### 6.2 已落地行为

1. `WorkspaceReadAdapter` 负责读取工作台资料命中，优先使用 `sample_text`，其次使用搜索 snippet 和 summary。
2. `UrlReadAdapter` 负责读取外部 URL 命中。
3. 默认不主动抓取网页，未配置 URL reader 时会生成包含搜索 snippet 与 URL 的 `FALLBACK` 读取窗口。
4. 配置 `NOTEWEAVE_RESEARCH_ENABLE_URL_READER=true` 后，会启用轻量 `HttpUrlSnapshotTransport` 尝试抓取网页文本。
5. `CompositeReadAdapter` 按搜索命中顺序读取，并统一遵守 `tool_response_retention_budget`。
6. `ResearchReadWindow` 已携带 `url / provider / adapter / snapshot_status / snapshot_key`，为后续 MinIO 快照落盘预留字段。
7. runner 已从直接调用 `open_read_windows` 改为调用 `run_research_read`。

### 6.3 TDD

1. workspace hit 能打开窗口。
2. url hit 在没有网络 reader 时生成可解释 fallback。
3. retention budget 生效。
4. read window 必须携带 `source_id / query / read_focus / token_estimate`。

## 7. 5B.4 LLM 抽取与验证

状态：已完成第一阶段实现。

### 7.1 新增文件

1. `workers/research-worker/app/llm_client.py`
2. `workers/research-worker/app/json_repair.py`
3. `workers/research-worker/tests/test_llm_extract_verify.py`

### 7.2 已落地行为

1. `LlmClient` 定义 `complete_json(purpose, payload)` 契约。
2. `FakeLlmClient` 用于 TDD，不依赖真实 API。
3. `OpenAICompatibleLlmClient` 使用轻量标准库 HTTP 请求，不额外引入依赖。
4. `build_default_llm_client()` 读取 `NOTEWEAVE_LLM_API_KEY / NOTEWEAVE_LLM_MODEL / NOTEWEAVE_LLM_BASE_URL`。
5. 不配置 key 或 model 时 runner 保持规则模式。
6. `json_repair` 支持 fenced JSON、截取 JSON 对象和 trailing comma 修复。
7. `extract_evidence_cards` 支持 LLM JSON schema 输出，坏输出自动回落到规则抽取。
8. `run_local_verifier` 支持可选 LLM judge 警告与恢复动作合并。
9. `write_research_report` 只渲染真实 `evidence_cards` 中存在的证据 ID，避免 ghost evidence 进入报告。

### 7.3 TDD

1. fake LLM 返回合法 JSON 时生成 evidence cards。
2. fake LLM 返回坏 JSON 时走 repair / fallback。
3. verifier 必须拒绝无证据结论。
4. report synthesizer 必须只引用 evidence cards 中存在的证据。

## 8. 5B.5 Loop Runtime

状态：已完成第一阶段实现。

### 8.1 新增文件

1. `workers/research-worker/app/loop_runtime.py`
2. `workers/research-worker/tests/test_loop_runtime.py`

### 8.2 已落地行为

1. 新增 `ResearchLoopDecision` 与 `ResearchLoopRoundSummary`。
2. `StopContract` 增加 `max_loop_rounds=2` 与 `min_evidence_cards=1`。
3. `run_research_loop()` 统一执行 `Search -> Read -> Extract -> Verify -> Branch -> Global Verify`。
4. 第一轮无搜索命中时输出 `EXPAND_SOURCE_SCOPE`，不继续空转。
5. 有搜索命中但无 evidence card 时输出 `READ_MORE` 或 `EXTRACT_AGAIN`。
6. 冲突证据在预算内触发 `COUNTERFACTUAL_RECHECK`。
7. 达到最大轮数仍未满足条件时输出 `WRITE_WITH_GUARDRAILS`。
8. runner 已从固定单轮改为调用 `run_research_loop()`。
9. result payload 新增 `loop_rounds` 与 `loop_decision`。

### 8.3 TDD

1. 默认最大 2 轮。
2. 第一轮无证据时触发 `SEARCH_MORE` 或 `EXPAND_SOURCE_SCOPE`。
3. 冲突证据触发 `COUNTERFACTUAL_RECHECK`。
4. 达到预算必须 `WRITE_WITH_GUARDRAILS`，不能无限循环。

## 9. 5B.6 报告回写

状态：已完成第一阶段实现。

### 9.1 Java 侧新增或完善

1. `POST /api/v2/research-runs/{researchRunId}/save-report-as-source`
2. `source.generated_by = research_agent`
3. report markdown 存 MinIO / local storage
4. citation 关系保留

### 9.2 已落地行为

1. Worker result payload 输出 `research_checkpoint_candidate`。
2. Worker result payload 输出 `report_source_candidate`。
3. 新增 migration `V013__add_research_report_source_mapping.sql`。
4. `source` 增加 `generated_by / generated_ref_id`。
5. `research_run` 增加 `report_source_id`。
6. Java 新增 `POST /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/save-report-as-source`。
7. 已完成的 Research Run 可以生成 `GENERATED_RESEARCH_REPORT` 类型资料。
8. 保存后的报告会创建 `file_object / source / source_snapshot / source_chunk / source_window`。
9. 保存后会调用 `SourceParseService.parseAndIndex`，因此该报告与用户上传资料处于同一检索层级。
10. 未完成或无报告正文的 Research Run 会返回 `RESEARCH_REPORT_NOT_READY`。

当前边界：

1. checkpoint candidate 已输出，但还没有正式落 MinIO。
2. URL snapshot 正式持久化还没有落地。
3. citation 当前保留在 research trace/result payload，尚未额外拆成专门关系表。

### 9.3 TDD

1. 完成的 research run 可以保存为 source。
2. 未完成或失败的 run 不能保存。
3. 保存后的 source 能被 QA / Note / Wiki 检索链路使用。

## 10. 5B.7 启动部署

状态：已完成第一阶段实现。

### 10.1 新增脚本

1. `workers/scripts/start-research-api.ps1`
2. `workers/scripts/start-research-consumer.ps1`

### 10.2 已落地行为

1. `setup-workers-conda.ps1` 负责创建/更新 `noteweave-workers` conda 环境。
2. `start-research-api.ps1` 进入 `workers/research-worker` 并通过 conda 运行 `uvicorn app.main:app`。
3. `start-research-consumer.ps1` 进入 `workers/research-worker` 并通过 conda 运行 `python -m app.kafka_consumer`。
4. Kafka consumer 支持 `NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS` 覆盖。
5. smoke test 覆盖 health、consumer import、Kafka 配置读取。

### 10.3 TDD / Smoke

1. `python -m app.kafka_consumer` import 成功。
2. `/health` 返回 worker_type。
3. `NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS` 能覆盖默认值。

## 11. 当前下一步

阶段 5B 第一阶段已经完成。

后续增强建议单独进入新阶段：

1. checkpoint / URL snapshot 正式保存到 MinIO。
2. Research Run 过程态细粒度落库。
3. 外部 URL reader 接入更稳的网页抓取服务。
4. Artifact Worker 的 Skill / MCP 编排增强。
