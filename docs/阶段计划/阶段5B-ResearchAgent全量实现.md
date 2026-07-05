# 阶段5B-Research Agent 全量实现

## 1. 目标

这份文档用于把 `Deep Research 智能体` 从当前可运行 MVP 推进到完整可交付形态。

当前已完成的是：

1. Java 创建 `research_run / task / task_outbox`。
2. Research 任务通过 `noteweave.research.run` Kafka 下发。
3. Python Research Worker 能消费 Kafka、拉取 Java 输入、回调 progress / complete / fail。
4. Worker 内部已有轻量 `Plan -> Search -> Read -> Extract -> Verify -> Branch -> Report` 闭环。
5. Search 命中已具备 `search_angles / matched_fields / coverage_score`。

还没有完成的是：

1. 真实外部搜索或浏览器搜索 adapter。
2. LLM 驱动的计划、证据抽取、验证和报告合成。
3. 可恢复的研究过程快照。
4. 最终研究报告回写为工作台资料。
5. 正式 worker 启动脚本与 Kafka consumer 部署入口。

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

5B.3 读取与网页快照 Adapter
  -> 定义 ReadAdapter
  -> workspace source_window 默认实现
  -> url reader / fetched page snapshot 预留实现
  -> TDD 覆盖窗口预算、来源快照、失败降级

5B.4 LLM 驱动抽取与验证
  -> 定义 LlmClient 协议
  -> 默认 Fake / RuleBased client 保证本地测试稳定
  -> OpenAI-compatible client 读取 env
  -> Evidence Extractor 支持 JSON schema 输出
  -> Local / Global Verifier 支持 LLM judge + rule fallback
  -> TDD 覆盖 JSON 解析、坏输出修复、证据不足警告

5B.5 Loop Runtime 与 Stop Contract
  -> 引入 bounded loop，不再固定单轮
  -> 每轮决定 SEARCH_MORE / READ_MORE / VERIFY / WRITE
  -> StopContract 控制最大轮次、最大搜索数、最小证据数
  -> TDD 覆盖预算耗尽、冲突分支、强制收口

5B.6 过程快照与报告回写
  -> Java 增加 report save-as-source 接口实现
  -> Worker result 输出 report_source_candidate
  -> Research 完成后可由 Java 回写资料池
  -> TDD 覆盖 generated_by=research_agent 和 citation 保留

5B.7 启动部署与运行脚本
  -> conda worker 环境
  -> Research API server 启动
  -> Research Kafka consumer 启动
  -> docker compose / PowerShell 本地脚本
  -> TDD / smoke 覆盖 health、consumer import、配置读取
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

### 5.3 TDD

1. 无外部 key 时只使用 workspace adapter。
2. 有外部 key 时外部搜索结果与 workspace 命中合并去重。
3. 所有 hit 必须有 `search_angle / retrieval_reason / confidence_score`。
4. 空结果必须触发 branch recovery。

## 6. 5B.3 读取 Adapter

### 6.1 新增文件

1. `workers/research-worker/app/read_adapters.py`
2. `workers/research-worker/tests/test_read_adapters.py`

### 6.2 TDD

1. workspace hit 能打开窗口。
2. url hit 在没有网络 reader 时生成可解释 fallback。
3. retention budget 生效。
4. read window 必须携带 `source_id / query / read_focus / token_estimate`。

## 7. 5B.4 LLM 抽取与验证

### 7.1 新增文件

1. `workers/research-worker/app/llm_client.py`
2. `workers/research-worker/app/json_repair.py`
3. `workers/research-worker/tests/test_llm_extract_verify.py`

### 7.2 TDD

1. fake LLM 返回合法 JSON 时生成 evidence cards。
2. fake LLM 返回坏 JSON 时走 repair / fallback。
3. verifier 必须拒绝无证据结论。
4. report synthesizer 必须只引用 evidence cards 中存在的证据。

## 8. 5B.5 Loop Runtime

### 8.1 新增文件

1. `workers/research-worker/app/loop_runtime.py`
2. `workers/research-worker/tests/test_loop_runtime.py`

### 8.2 TDD

1. 默认最大 2 轮。
2. 第一轮无证据时触发 `SEARCH_MORE` 或 `EXPAND_SOURCE_SCOPE`。
3. 冲突证据触发 `COUNTERFACTUAL_RECHECK`。
4. 达到预算必须 `WRITE_WITH_GUARDRAILS`，不能无限循环。

## 9. 5B.6 报告回写

### 9.1 Java 侧新增或完善

1. `POST /api/v2/research-runs/{researchRunId}/save-report-as-source`
2. `source.generated_by = research_agent`
3. report markdown 存 MinIO / local storage
4. citation 关系保留

### 9.2 TDD

1. 完成的 research run 可以保存为 source。
2. 未完成或失败的 run 不能保存。
3. 保存后的 source 能被 QA / Note / Wiki 检索链路使用。

## 10. 5B.7 启动部署

### 10.1 新增脚本

1. `workers/scripts/start-research-api.ps1`
2. `workers/scripts/start-research-consumer.ps1`

### 10.2 TDD / Smoke

1. `python -m app.kafka_consumer` import 成功。
2. `/health` 返回 worker_type。
3. `NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS` 能覆盖默认值。

## 11. 当前下一步

立即执行 `5B.1 Harness 契约与 Trace`。

本阶段不直接接真实 OpenAI API，先确保：

1. Harness 契约稳定。
2. Trace 可观测。
3. runner 可以在不配置 LLM 的情况下稳定通过所有测试。

