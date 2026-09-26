# Research Agent 能力覆盖与发布门禁

> 基线日期：2026-07-11；最终复审：2026-07-12  
> 用途：M0 阶段以代码和测试证据区分“已实现、部分实现、尚未实现”，禁止用类名、字段名或 passed 总数替代能力验收。

## 1. 状态定义

- `GREEN`：主路径和负例测试均通过，可按当前边界对外描述。
- `YELLOW`：工程骨架存在，但关键语义不完整；只能描述为 prototype。
- `RED`：存在已确认的正确性或安全缺口；不得作为已实现亮点宣传。
- `NOT_RUN`：存在代码或历史记录，但本轮未执行验证。

严格 `xfail` 表示已恢复执行、已确认失败且绑定到后续里程碑；它不是 skip，也不计为能力通过。若缺口意外消失，`strict=True` 会让测试以 XPASS 失败，要求维护者复核后才能改为 GREEN。

## 2. 当前能力矩阵

| 能力 | 状态 | 当前代码证据 | 自动化证据 | 退出门槛 |
| --- | --- | --- | --- | --- |
| Planner / Stop Contract | GREEN | `planner.py` | planner、runner、search adapter tests | 深度档位和 query budget contract 保持通过 |
| Search provider chain | GREEN（有界调度） | `search_adapters.py` | retry/pagination/fallback/provider-quota tests | provider 首槽配额避免 workspace 填满全局预算；不宣称效果最优 |
| HTTP/Jina Fetch | GREEN（安全执行边界） | `fetch_adapters.py`、`rate_limit.py` | charset/retry/SSRF/content-type/size/fallback tests | 公网 DNS 环境仍需发布前 live canary；当前环境将公网 DNS 映射到保留地址并被 guard 正确阻断 |
| Read window retention / Adaptive Horizon | GREEN | `read_adapters.py`、`evidence_horizon.py`、`loop_runtime.py` | horizon uncertainty/diversity/retry 与 window selection tests | 决策实际修改下一轮 target columns 和 window budget，不只输出 trace |
| 无 LLM 不伪造 Evidence | GREEN | `extractor.py` | unusable/no-LLM extraction tests | 空结果进入 recovery，不恢复关键词假证据 |
| Entity/Source 分离 | GREEN | `entity_identity.py`、`state.py::EntityResolver` | entity/source foundation tests | canonical entity ID 在 hit/card/row/cell 一致，多 source 聚合不改实体身份 |
| Evidence -> Cell 精确绑定 | GREEN | `ResearchEvidenceCard`、`state.py`、`research_tools.py::verify_cells` | exact entity+column binding 正/负例 | 仅相同 `entity_id + column_key` 的 Evidence 可进入 CellVerifier |
| Required Cell -> Row READY | GREEN | `research_tools.py::verify_cells`、`state.py::_apply_requirement_bindings` | state/runner/requirement tests | 所有 required cells VERIFIED 且无冲突后 row 才 VERIFIED/READY |
| 跨轮 retry / FROZEN | GREEN | `_seed_cell_history`、`_merge_ledger_history`、`_freeze_overspent_cells` | cross-round replay/freeze/checkpoint tests | retry、VERIFIED、FROZEN 可进入逐轮 durable snapshot 并恢复 |
| Wide discovery / Entity Freeze | GREEN | `_freeze_entity_candidate_set`、plan horizon | candidate freeze/rejected candidate tests | 第一轮后 entity set/version 固定，新来源只能补已知 entity，新增候选进入 audit |
| Local Verifier | GREEN（当前规则边界） | `verifier.py::run_local_verifier`、`cell_verifier.py` | local/cell verifier tests | 输入来自精确 cell binding，冲突-only 证据不能被 LLM 假判支持 |
| Global Verifier 独立性 | GREEN | `verifier.py::run_global_verifier` | fallback/archive/intent truth-table tests | Global 从 ledger/current facts 重算 contract、alignment、score，不复制 Local 结论 |
| Counterfactual Branch | GREEN（有界顺序执行） | `branch.py`、`loop_runtime.py`、`state.py` | fresh/resume/dedup/terminal lifecycle tests | `ACTIVE -> RESOLVED`、裁决、结果 evidence、去重和 mainline merge 可审计；不宣称并行 |
| 实时 Progress / Tool Trace | GREEN | `research_tools.py`、`trace_security.py`、`callback.py` | callback、phase、secret-redaction tests | event/run/round/branch/tool/attempt/started/ended 实际填充，正文和 secret 不进入 trace |
| Report Structure / Markdown | GREEN（结构化报告边界） | `reporter.py` | report/provenance/checkpoint tests | finding、row、cell、requirement 与 evidence ID 一致 |
| Citation Association / Support | GREEN（规则审计边界） | `citation_verifier.py`、`report_refiner.py`、`runner.py` | span/hash/association/support/demotion tests | 不通过的 finding 按 section 降级 guarded；后续可用 learned entailment judge 提升召回，但不影响安全门禁 |
| 最终结果 checkpoint | GREEN | Worker + Backend | checkpoint contract tests | 版本化 final snapshot 保留 |
| 运行中 crash-safe checkpoint | GREEN | `loop_runtime.py`、`callback.py`、`ResearchRunService`、V024 | per-round callback 与 Backend idempotent persistence integration test | 每轮/branch 终态写 checkpoint，成功 ACK 后继续；旧轮不再被 final completion 删除 |
| Kafka poison continuation | GREEN | `kafka_consumer.py` | retryable/non-retryable/DLQ/commit tests | failed message/attempt 分离，durable DLQ ack 后才 commit |
| SSRF / Prompt Injection 防护 | GREEN（代码与自动化边界） | `fetch_adapters.py`、`extractor.py`、`verifier.py` | IPv4/IPv6/redirect/IP pinning/injection/content boundary tests | scheme、credential、私网、metadata、redirect、类型、大小、注入隔离均有负例；连接固定到已校验 IP，HTTPS 继续校验原 hostname |
| DeepWideSearch 风格评测 | GREEN（内部 gold-set harness） | `evaluation.py`、`evaluate_gold_set.py` | deterministic evaluator tests | 输出 entity/cell/row/citation/branch、Avg/Max/Pass@N、token/cost/retry；不等同于外部基准领先 |

## 3. M0 测试基线

```powershell
cd workers/research-worker
python -m pytest tests -q -rxX
```

2026-07-11 结果：

```text
132 passed, 12 xfailed, 0 skipped
```

- 原来的 `83 passed, 61 skipped` 已改为全部参与收集和执行。
- 可迁移测试已改为显式 Fake LLM、动态 evidence ID 和实时 tool trace 断言。
- checkpoint candidate 缺失的 `audit_summaries/toolbox_summary` 已补齐。
- 仍由 M1/M2 阻塞的 12 项使用带里程碑/P0 编号的 strict xfail，不再隐藏为 skip。

## 4. 对外描述门禁

M1/M2 完成前只允许使用：

> `Built a prototype of a verification-oriented Deep Research workflow with structured research-state objects, layered verification, recovery decisions, external search/read adapters, and auditable report delivery.`

当前仍禁止使用：

- “反证分支已经真正并行执行和裁决”。
- “已达到或超过 DeepWideSearch 外部基准水平”。
- “所有公网来源均已完成生产 live canary”。

## 5. 状态更新纪律

1. `RED/YELLOW -> GREEN` 必须同时提供正例、负例和集成证据。
2. 删除 strict xfail 前必须先让其 XPASS，并审查不是因为断言被弱化。
3. 新增架构术语前，先在本表增加能力行、状态和退出门槛。
4. 测试汇报必须同时报告 passed、failed、xfailed、skipped。
5. 历史强口径若与本表冲突，以本表和 `ResearchWorker-Repair-Plan.md` 为准。

## 6. M1/M2 验证记录（2026-07-11）

```text
145 passed, 0 failed, 0 xfailed, 0 skipped
```

该段为历史 M1/M2 截面。M3/M4 及最终反向审计的当前结论见下一节；仍不得扩写为“真正并行分支”“通用语义蕴含 verifier”或“达到 DeepWideSearch 外部效果水平”。

## 7. M3/M4 与最终审计验证记录（2026-07-12）

- Research Worker：`201 passed, 0 failed, 0 xfailed, 0 skipped`。
- Backend Research/Callback 定向测试与完整套件均通过：`83 passed, 0 failed, 0 skipped`。
- Frontend：`40 passed`，production build 与 UI contract check 通过。
- Compose：`docker compose config --quiet` 通过。

当前可安全描述：逐轮 durable checkpoint、heartbeat/cancel ACK、callback idempotency、Kafka durable DLQ、SSRF/IP pinning/prompt-injection 边界、受限并发与 rate limit、per-purpose LLM policy/context/cost ledger、混合 citation audit/section repair 和内部 gold-set evaluator 已实现。未完成的外部证明包括正常公网 live canary、经过外部 benchmark 验证的通用 NLI 能力与 DeepWideSearch 官方外部 benchmark；这些边界不得通过文字包装绕过。
