# Research Worker 修复计划、偏差审计与验收基线

> 审计日期：2026-07-11；最终复审：2026-07-12  
> 审计对象：`workers/research-worker`、Research 后端契约、Research 前端交付契约、Deep Research 设计/简历文档，以及 `reference` 下四个指定参考项目。  
> 文档性质：这是代码事实驱动的修复清单，不以类名、字段名或测试“绿色”代替能力完成度。

## 1. 结论先行

当前 Research Worker 已经具备可运行的单进程研究骨架：

`Planner -> Search -> Fetch -> Read -> Extract -> Table State -> Cell/Local/Global Verify -> Branch Decision -> Report`

外部搜索适配器、HTTP/Jina 抓取、状态对象、进度事件、结果回调、运行中 checkpoint、报告回流候选等工程链路已经存在。M0-M4 修复和 2026-07-12 反向审计后，结论更新为：

> **Research Agent 的内部 verification-centric 闭环已经达到可运行、可恢复、可审计状态；此前发现的 P0 真源、跨轮状态、虚假分支解决、Global Verifier、citation provenance、运行中 checkpoint 和隐藏 Fake 测试问题均已修复。对外仍必须保留边界：分支执行是有界顺序执行；citation support 使用 span/hash + association，并可调用独立语义 judge，失败时降级到 lexical/polarity verifier，但这仍不等同于经过外部 benchmark 验证的通用 NLI；内部 gold set 也不等同于 DeepWideSearch 官方成绩。**

下表是完成修复后的最终判定；第 4～6 节保留的是 M0 前的历史问题快照，不代表当前代码仍存在对应缺陷：

| 简历亮点 | 当前判定 | 主要原因 |
| --- | --- | --- |
| Research Harness | 已完成（内部工程口径） | bounded loop、真实 tool adapter、timeout/retry/cancel/heartbeat、cost/context policy、可审计 trace 已接入 |
| Closed-Loop Research | 已完成 | current/history 分离；guard、Global gate、预算终态和 lazy replan 实际控制下一轮 |
| Table-as-State | 已完成 | canonical entity、精确 `(entity,column)` 绑定、required-cell readiness、跨轮 retry/freeze 和候选集冻结已落地 |
| Dual Verifier | 已完成 | Global 从当前 ledger 独立重算，不复制 Local 结论 |
| 反证分支 | 已完成（顺序、有界） | 只有新轮次、不同 source、同 cell 的独立证据才能 resolve；分支预算、终态和 merge audit 已落地 |
| Adaptive Evidence Horizon | 已完成 | cell uncertainty/source diversity/retry/conflict 决策会改变目标列和下一轮窗口预算 |
| Conflict-Scoped Recovery | 已完成 | 查询、来源排除、cell target、branch lifecycle 和 guarded budget terminal 已闭合 |
| Verifier-Gated Synthesis | 已完成 | 未 READY 且预算尚存时禁止 synthesis；预算终态只能 guarded write，citation 失败会覆盖 gate |
| Checkpoint / Resume | 已完成 | 每轮 durable checkpoint、幂等 callback、heartbeat/cancel 和恢复游标已接入 |
| Citation-backed Report | 已完成（受限混合 verifier） | citation 从报告实际 evidence refs 派生，验证 span/hash、entity association，并由独立语义 judge 或 lexical/polarity fallback 审核 support |

## 2. 审计范围与参考基线

### 2.1 项目内对照材料

本轮对照了以下文档中的目标和简历口径：

- `docs/项目亮点与简历草案.md`
- `docs/DeepResearch-ResearchAgent架构文档.md`
- `docs/DeepResearch-ResearchAgent面试回答手册.md`
- `docs/深度研究智能体执行手册.md`
- `docs/深度研究智能体工程落地设计.md`
- `docs/阶段计划/阶段5B-ResearchAgent全量实现.md`

重点核查的简历关键词为：

- `Verification-Centric Research Harness`
- `Closed-Loop Research`
- `Table-as-State`
- `Dual Verifier`
- `Counterfactual Branching`
- `Adaptive Evidence Horizon`
- `Conflict-Scoped Recovery`
- `Verifier-Gated Synthesis`
- `Full-Horizon Plan + Lazy Replanning`
- `Premature Commitment Guard`
- `Stop Contract`

### 2.2 四个指定参考项目及应吸收边界

#### MiroFlow

参考目录：`reference/MiroFlow`。

应吸收的是工程稳健性，而不是复制其通用多 Agent 平台：

- 统一 Tool Manager、显式 tool schema、tool blacklist 和 server/tool resolution。
- async tool execution、每次调用 timeout、失败/空结果的结构化返回。
- main/sub-agent 的多轮工具编排与 task tracer。
- context limit、summary retry、网络 retry、每轮工具调用上限。
- 配置化模型、工具和 benchmark profile，以及可复现实验 trace。

修复后 NoteWeave 已用单进程 `ResearchToolbox` 统一承载工具阶段、预算、取消检查、调用级 timeout/retry、usage/cost 和 trace。边界仍是同步有界流水线，不是 MiroFlow 式分布式多 Agent runtime。

#### Table-as-Search

参考目录：`reference/Marco-DeepResearch/Marco-DeepResearch-Family/Table-as-Search`。

应吸收的是“表就是搜索控制面”的语义：

- 先确定 schema，再发现候选 row，再按 row 的空 cell 做 deep fill。
- row 是问题中的业务实体，不是网页或来源。
- cell 更新必须携带稳定 `(entity_id, column_key)`，且 schema 校验、去重、部分更新均可执行。
- wide candidate discovery 与 deep cell completion 职责分离。
- 多 row/cell 可并发，状态持久化后可 resume。

修复后 Search Hit 只进入 source/candidate pool，Evidence Card 携带系统解析的 `entity_id/column_key`，业务实体 row 与来源分离；同一实体可聚合多来源，Cell 只接受精确绑定。alias/merge/split 的复杂人工治理仍不是本阶段目标。

#### DeepWideSearch

参考目录：`reference/Marco-DeepResearch/Marco-DeepResearch-Family/DeepWideSearch`。

该目录本质上首先是 benchmark/evaluation 基线，不应被误写为可直接复用的 runtime 框架。应吸收的是：

- 同时评估 depth、width、efficiency，而不是只看最终有无报告。
- 使用 Core Entity Accuracy、Column-F1、Row-F1、Item-F1/Cell-F1 衡量表完成质量。
- 多次独立 rollout 的 Avg/Max/Pass 指标，暴露搜索不稳定性。
- 记录搜索成本、重试成本和网络失败带来的开销。

修复后的内部评测已拆分 Core Entity、Column/Row/Cell F1、值准确率、引用准确率、分支修正率、rollout Avg/Max/Pass 和成本统计；它只用于内部回归，不能证明达到 DeepWideSearch 官方 benchmark 水平。

#### Marco-Agent-DeepResearch

参考目录：`reference/Marco-DeepResearch/Marco-DeepResearch-Family/Marco-Agent-DeepResearch`。

应吸收的是 verification-centric 单 Agent 运行能力：

- 明确的 LLM/tool call round budget、token safety buffer、tool response retention/truncation。
- LLM 与 tool failure retry、异常输出处理、空搜索重启策略。
- efficiency / accuracy 两种执行模式。
- test-time scaling 下的 context discard、候选答案收集、early stop 与 verification。
- rollout trace、token usage、execution time 和终止原因。

修复后已有按 purpose 的模型/timeout/retry/cost 策略、响应 usage 账本、确定性上下文压缩和 safety buffer；当前 context guard 仍以字符预算近似 token 上限，多 rollout 只在评测 harness 中执行，不宣称已实现 Marco-Agent 的 test-time scaling runtime。

## 3. 当前实现中已经成立的能力

以下能力经代码核查确实存在，可以保留，但必须按其真实边界表述：

- `runner.py` 和 `loop_runtime.py` 已形成单主 Worker 的 bounded loop。
- `search_adapters.py` 已支持 workspace search、外部 provider chain、query family budget、分页、provider fallback 记录和有限重试。
- `fetch_adapters.py` 已支持 HTTP/Jina transport chain、分块读取、charset fallback、失败原因和 snapshot metadata。
- `read_adapters.py` 已把 fetch document 转成有 retention metadata 的 read window。
- `extractor.py` 在无 LLM 时不再制造 Evidence Card，并拒绝不存在的 `window_id`。
- `cell_verifier.py` 已提供 `SUPPORTS / PARTIALLY_SUPPORTS / CONTRADICTS / NOT_ENOUGH_INFO` 四路判定对象。
- `verifier.py` 已输出 local/global decision records、intent completion/alignment 和部分 source/fetch guard。
- `research_tools.py` 已输出 tool traces，并在执行中发送真实 phase progress。
- `callback.py` 已完成 input/progress/complete/fail 的 Java 回调。
- Backend 已能持久化 Research 结果、最终 checkpoint、artifact candidate，并提供 checkpoint 查询和 resume API。
- 报告可以形成结构化 payload、Markdown、source candidate，并回流工作台。

这些是后续修复的基础，不应推翻；修复重点是让对象之间的语义真正闭合。

## 4. P0：必须先修复的正确性问题（M0 前历史快照，现已关闭）

### P0-1 Evidence Card 丢失实体和列绑定，Table-as-State 不是可信真源

**代码证据**

- `extractor.py` 的 LLM schema要求返回 `entity_id` 和 `column_key`，但构造 `ResearchEvidenceCard` 时没有保存这两个值。
- `models.py::ResearchEvidenceCard` 本身也没有这两个字段。
- `state.py::_filter_cards_for_column` 只能用 `column_key` 与 claim/quote 的英文 token 做启发式匹配；匹配不到时会返回全部 cards。
- `research_tools.py::verify_cells` 会把同一 entity 的全部 Evidence Card 绑定给该 entity 的每一个 cell，完全忽略 column。

**影响**

- “某来源支持某一列”会被放大为“支持该实体的所有列”。
- 中文列名经 `_column_tokens` 解析后通常没有 token，更容易退化为“全部 cards 绑定全部 cells”。
- Cell Verifier、required finding progress、coverage 和最终报告都会产生假阳性。

**修复方案**

1. `ResearchEvidenceCard` 增加必填 `entity_id`、`column_key`，可选增加 `value_text/value_normalized`。
2. Extractor 校验 `column_key in plan.research_schema.columns`；无效值拒绝，不允许 fallback 到全部列。
3. Entity ID 必须由系统 resolver 生成；LLM 只能返回 `entity_key/entity_name` 提示，不能自由生成真源 ID。
4. Cell binding 只允许精确 `(entity_id, column_key)`；启发式路由只能产出 `UNBOUND` 候选，不能进入 VERIFIED。
5. 数据库/结果契约同步保存 evidence-to-cell binding。

**验收标准**

- 给同一实体的 `price` 和 `license` 两张不同证据，二者只能进入对应 cell。
- 中文 schema、同义列名、未知列、空列都有 contract test。
- 未绑定 Evidence Card 不计 coverage、不生成 final citation。

### P0-2 EntityResolver 把“来源”当“业务实体”，且 hit/card ID 算法不一致

**代码证据**

- hit entity key 使用 `(url, title, source_id)`。
- card entity key 使用 `(source_id, source_title)`。
- Evidence Card 没有保存 extractor 返回的 entity 信息。

**影响**

- 同一网页会形成一个无 evidence 的 hit row 和另一个无 URL 的 card row。
- 产品对比/论文调研时，row 往往代表网页，而不是产品/论文/公司等目标实体。
- citation、source URL、row 去重、跨轮 merge 和 checkpoint resume 均可能错位。

**修复方案**

- 拆分 `SourceRef` 与 `ResearchEntity`；一个 entity 可由多个 source/evidence 支撑。
- 定义单一 canonical entity key 策略：优先外部稳定 ID，其次规范化业务主键，最后才使用 title fingerprint。
- Search hit 先进入 source/candidate pool；只有 entity extraction/resolution 后才创建 row。
- 支持 alias、merge、split 和 ambiguous 状态，并保留 resolver trace。

**验收标准**

- 同一实体的多个 URL 合并为一个 row，多来源分别保留。
- 同一 URL 中出现多个实体时可形成多个 row。
- checkpoint/resume 后 entity ID 不变化。

### P0-3 Cell/Row 状态会产生假 VERIFIED，跨轮 retry 实际不会累积

**代码证据**

- `verify_cells` 中只要 entity 任一 cell 为 `VERIFIED` 且没有冲突，整行就被标成 `VERIFIED`，没有要求所有 required cells 完成。
- 每轮 `build_state_ledger` 都重新创建 cells，`repair_count` 从 0 开始。
- `_merge_ledger_history` 只合并 branch/session/decision/unresolved，不合并 cells/rows 的 retry/verdict 历史。
- `_freeze_overspent_cells` 因此很难看到累计超过阈值的 cell。
- `verify_cells` 中 `max_retry`、`stop_contract_max_retry` 是未使用变量，真实阈值没有传入 Cell Verifier。

**影响**

- `max_retry_per_cell` 和 `FROZEN` 亮点表面存在、实际失效。
- 一个已验证 cell 可把缺失的必填 cells 所在整行提前升级为 VERIFIED。
- “Premature Commitment Guard” 与 “Cell Completion Loop” 均不真实。

**修复方案**

- 由上一轮 ledger 原地/事件式更新 cells，不得每轮从 evidence 全量重建状态历史。
- row READY 条件改为：所有 required cells 达到 schema 指定的 accepted verdict；optional cells 不阻断。
- retry 仅在针对该 cell 的真实 recovery attempt 后递增，并记录 `last_attempt_round/action/query`。
- FROZEN 必须携带原因：预算耗尽、来源不可达、冲突未解或人工接管。
- 将 retry budget 作为显式参数传入 cell completion scheduler。

**验收标准**

- 三轮恢复后 retry 为 3，并在下一轮停止调用该 cell。
- 一个 required cell 缺失时 row 绝不能 READY/VERIFIED。
- 已 VERIFIED cell 在无新冲突证据时跨轮不回退。

### P0-4 反证分支只有 decision/session 元数据，没有可裁决的分支执行

**代码证据**

- `branch.py` 对每张冲突卡生成 `PARALLEL_CANDIDATE`，但 `loop_runtime.py` 仍在同一同步 loop 中执行一次 search/fetch/read/extract。
- 没有 branch-scoped plan、ledger、evidence namespace、执行 future/task 或结果对象。
- branch 没有 `RESOLVED / REJECTED / MERGED / EXHAUSTED` 转移。
- 第一条 counterfactual branch 使用 `branch-recovery-1`，与通用 recovery branch ID 冲突。
- 旧冲突 Evidence Card 会被跨轮合并保留，`has_conflict` 持续为真，容易一直 recheck 到预算耗尽。

**影响**

- `PARALLEL_CANDIDATE` 是标签，不是并行事实。
- 无法回答“反证推翻了什么、保留了什么、为何 merge”的面试追问。
- Counterfactual Branching 和 Conflict-Scoped Recovery 目前不能按简历强口径描述。

**修复方案**

1. 先实现可验证的 sequential branch executor，再决定是否并行。
2. 每个 branch 拥有 `branch_id + hypothesis + target_cells/evidence + query budget + artifacts + verdict`。
3. mainline 在冲突期间冻结；branch 只搜索能证伪/修正目标 claim 的材料。
4. 增加 branch resolver，输出 `UPHOLD_MAINLINE / REVISE_MAINLINE / INCONCLUSIVE` 和 merge patch。
5. branch 完成后关闭 active 状态；旧冲突不能仅因仍在 ledger 中无限触发。

**验收标准**

- trace 中能看到 mainline freeze、branch 开始/结束、branch evidence、裁决和 merge。
- 两个冲突可产生互不覆盖的 branch ID 和隔离预算。
- 已解决冲突下一轮不会再次无条件开相同分支。

### P0-5 Global Verifier 不独立，历史合并会让系统无法恢复到干净状态

**代码证据**

- `run_global_verifier` 直接消费 `LocalVerifierResult`，并原样复用 local 的 `intent_completion_contract` 和 `research_intent_alignment`。
- `_merge_local_result/_merge_global_result` 只要历史出现 WARN，最终 status 永久保持 WARN。
- `_merge_ledger_history` 对 `unresolved_questions` 做历史并集，不会在问题解决后移除。
- branch decisions 只追加、不关闭，Global Verifier 看到任何历史 recovery branch 就认为仍存在 recovery branch。
- `completion_score` 在跨轮 merge 时取历史最大值，而不是基于当前最终状态重算。

**影响**

- recovery 成功后，状态仍可能被旧 WARN/unresolved/active branch 污染。
- Global Verifier 不是第二条独立证据链，而是 Local Verifier 的汇总器。
- status、decision、completion score 可能彼此矛盾。

**修复方案**

- 区分 `current_state` 与 `audit_history`；历史告警留在 history，不直接参与当前 gate。
- Global Verifier 只读取 final ledger、branch terminal states、source foundation、intent contract 和 report draft，不读取 local 的最终 status 作为结论。
- completion score 每轮从当前状态重算，并拆成 coverage/support/diversity/conflict/intent 五个分项。
- 定义唯一 gate truth table，保证 `status/decision/report_action` 不矛盾。

**验收标准**

- 第一轮 WARN、第二轮修复成功后可变为 PASS，同时 audit history 保留第一轮 WARN。
- 删除 Local Verifier status 输入后，Global 仍能独立复算相同或可解释的结果。

### P0-6 Citation、quote grounding 与报告 provenance 存在错误

**代码证据**

- extractor 只校验 `window_id`，不校验 LLM 返回的 `quote_text` 是否真的是 `window_text` 子串。
- row 构造只复制第一张 card 的 claim/quote/evidence_id，未同步 relation/support/conflict/search/read 等字段，报告读取到的是模型默认值。
- `citations_payload` 直接取 `ledger.rows[:3]`，不要求 evidence_id/quote/source 有效，也不与最终写入的 claim 对齐。
- 报告以 row 的第一张 evidence 代表整行，但 cells 可能来自多张 evidence。

**影响**

- citation association 和 citation support 都可能失真。
- 报告可能显示空 citation、错误 relation、0 分 support，或把一条 quote 当作整行所有 cell 的依据。

**修复方案**

- quote 必须通过规范化 substring/span 校验；保存 `char_start/char_end/content_sha256/snapshot_key`。
- report finding 应以 claim/cell 为单位生成 citation binding，不以 row 第一张 evidence 代替。
- citation 从最终 report AST/structure 中实际引用的 evidence refs 派生，并做反向完整性校验。
- 报告生成后执行 citation entailment/association verifier；不通过则降级或修订。

**验收标准**

- 每条 final finding 至少一条有效 evidence span；每条 citation 都能反查 snapshot。
- 无 evidence 的 row 不进入 citation payload，citation_count 与实际引用一致。

### P0-7 “83 passed”不能代表核心回归绿色

本轮实际执行：

```powershell
cd workers/research-worker
python -m pytest tests -q -rs
```

结果为：`83 passed, 61 skipped`。

61 个 skip 中绝大多数是旧 `gate1/harness/llm_extract_verify` 核心测试，理由是 row/evidence/RULE 行为变更后“等待 M2/M3 重建”；另有 conflict fixture 和 runner 用例仍未迁移。也就是说，跳过数量接近通过数量，且跳过的正是 Extract/Verify/Branch/Harness 核心链路。

**修复要求**

- P0 修复期间禁止用总 passed 数声明 regression green。
- 移除批量 skip，按新契约重写测试；无法重写的旧测试应删除并由等价新测试替代。
- 建立 capability coverage 表，至少覆盖 entity/cell binding、跨轮 state、branch lifecycle、global independence、citation grounding、checkpoint resume。
- `FakeLlmClient` 必须按 case 注入，禁止 autouse fake 让大量测试无意中共享同一种“永远支持”输出。

### P0-8 Live Web 上线前必须补 SSRF 与 Prompt Injection 边界

**当前风险**

- HTTP fetch 会直接访问搜索结果 URL，没有 scheme/私网/loopback/link-local/DNS rebinding/redirect 目标校验。
- 网页正文直接进入 extraction LLM payload，没有把外部内容标记为不可信数据，也没有 injection classifier/隔离策略。
- 当前 `forbidden_patterns` 更接近内容规则，不是网页 prompt injection 防护。

**修复要求**

- 仅允许 `http/https`，解析每次重定向，拒绝私网、metadata endpoint、本机和保留地址。
- 设置下载字节、解压后大小、跳转次数、总 wall-clock 和 content-type allowlist。
- 把 web content 封装为明确的不可信 evidence block；检测“忽略系统指令/调用工具/泄露 secret”等注入模式。
- LLM 工具权限与抓取内容解耦；网页内容永远不能改变 tool policy。
- 增加恶意 HTML、重定向、DNS/IPv6、超大响应测试。

## 5. P1：P0 正确性收敛后的能力补强（历史快照，现已完成）

### P1-1 实现真正的 Wide Discovery -> Deep Cell Completion 调度

当前 query family 和 lane 已存在，但 search/fetch/read/extract 仍按整轮串行执行，且 workspace 先填满 global budget 时可能完全不给 external/diverse provider 留预算。

目标设计：

1. Wide 阶段按非重叠 query family 收集业务 entity candidates。
2. 去重/实体解析后冻结候选表版本。
3. Deep 阶段只为 missing/partial required cells 生成任务。
4. 在全局 semaphore、provider rate limit 和 branch budget 下并发 cell tasks。
5. 以 source/domain/query diversity 做配额，不能只由第一个 adapter 填满总预算。

### P1-2 把 Adaptive Evidence Horizon 做成真实策略

当前实现只是最多取 5 个排序后的窗口。应改为：

- horizon key 为 `(entity_id, column_key, uncertainty, source_diversity, retry_count)`。
- 已有强 primary source 支持时缩短；冲突/低质量/单源时扩大。
- 每次扩展记录边际收益；连续低收益则 stop/freeze。
- horizon 决策进入 trace 和 cost ledger。

### P1-3 LLM Gateway、上下文和成本治理

参考 Marco-Agent 与 MiroFlow 增加：

- LLM retry/backoff、错误分类、per-purpose timeout。
- schema validation + repair，不只依赖 JSON mode。
- per-purpose model/temperature/max tokens 配置。
- prompt/input/output token、latency、cost、retry 次数和 termination reason。
- tool response retention、context compaction 和 safety buffer。
- QUICK/STANDARD/DEEP 对应真实的调用/搜索/读取/token/cost budgets。

### P1-4 Report Draft -> Audit -> Revision 循环

当前 `report_refinement_guard` 只生成 PASS/WARN 字段。应增加：

1. 由 verified cells 生成 report AST/draft。
2. Global Report Verifier 检查 intent、coverage、citation support、冲突表述和禁用模式。
3. 只修订失败 section，不全量重写。
4. 修订后重新验证，超过预算则 guarded output。

### P1-5 运行中 checkpoint、取消、heartbeat 和幂等恢复

当前 checkpoint 在 Worker 完成后随 complete payload 一次性落库，只能用于基于历史结果创建新 run，不是 crash-safe execution checkpoint。

应增加：

- 每轮和重要 branch terminal event 后持久化 checkpoint。
- checkpoint write 成功后再推进 durable cursor。
- resume 恢复 current rows/cells/retries/branches/budgets，而不是只恢复 artifacts 列表。
- callback 幂等键、progress sequence、complete/fail terminal CAS。
- heartbeat、cancel_requested 检查和 timeout/cancellation propagation。

### P1-6 Kafka 失败语义与 DLQ

当前失败消息重试后仍 commit 并计入 skipped，没有独立 DLQ/redrive 记录；`failed_count` 统计的是失败尝试次数而不是失败消息数。

应增加：

- retryable/non-retryable 分类和指数退避。
- DLQ topic + 原消息 + error metadata + attempt count。
- 只有 complete/fail/DLQ durable ack 后提交 offset。
- 区分 `failed_message_count` 与 `failed_attempt_count`。
- transient progress callback 失败不应无条件重跑整个昂贵研究任务。

### P1-7 Source Quality 与 freshness/diversity

当前 source quality 主要依赖 URL/provider/title/长度和 placeholder 规则。后续需增加：

- primary/secondary/aggregator/user-generated 的显式类型。
- publication/update time、作者/机构、内容完整度、可复现 snapshot。
- 同域去重、来源独立性和 primary-source quota。
- 对时效问题执行 freshness policy；报告显示 as-of time。

## 6. P2：评测、展示与工程质量（历史快照，现已完成）

### P2-1 建立 DeepWideSearch 风格离线评测

至少建立内部小型 gold set，并输出：

- Core Entity Accuracy。
- Required Column-F1 / Cell-F1。
- Row completeness/precision/recall。
- Citation association/support accuracy。
- Branch correction success rate。
- Avg/Max/Pass@N、token/cost/latency、retry overhead。

### P2-2 Trace 真源统一

当前既有实时 tool trace，又在 runner 结束后追加汇总 phase trace，容易重复。应统一 event schema：

- `event_id/run_id/round/branch/tool/attempt/started_at/ended_at/status`。
- 输入只存安全摘要和 hash，避免整页/secret 进入 trace。
- Process 展示读 execution event；Audit 展示 verifier/branch/checkpoint event。

### P2-3 类型与代码清理

- 删除 `verify_cells` 中未使用的 retry 变量和误导性 P0 注释。
- 不在函数内部动态 import `CellVerifier`。
- Search/fetch/LLM settings 统一进入 typed config，而不是各模块散读环境变量。
- 给 adapter/tool 定义统一 error/result envelope。
- 对结果 payload 做版本化，避免 Backend 依赖大量松散 `Map<String,Object>`。

## 7. 分阶段实施顺序

### M0：冻结对外口径与测试止血

- 将简历/面试口径临时降级为“implemented prototype/skeleton”。
- 建立 capability coverage 清单，取消“passed 即完成”的判断。
- 重写 61 个 skipped 核心测试，先让失败真实暴露。

### M1：重建 Entity/Cell/Evidence 真源

- 新契约：Entity、Source、Evidence、CellBinding 分离。
- 修复 extractor、resolver、ledger、cell verifier 和 report provenance。
- 完成 row readiness、cell retry/freeze 和跨轮 state transition。

这是其他所有亮点成立的前置条件。

### M2：闭合 Verifier 与 Counterfactual Branch

- current state/history 分离。
- Global Verifier 独立化。
- branch executor、resolver、terminal state 和 merge patch。
- conflict 去重与“已处理”语义。

### M3：工具编排与运行可靠性

- async bounded concurrency、rate limit、timeout/cancel。
- LLM gateway/context/cost ledger。
- live web SSRF/prompt-injection 防护。
- mid-run checkpoint、heartbeat、idempotency、DLQ。

### M4：Report Refinement 与正式验收

- report AST、citation verifier、section repair loop。
- DeepWideSearch 风格 gold set 和多 rollout 评测。
- 真实 external web E2E、Docker profile、Backend/Frontend contract 回归。

## 8. 必须新增的测试矩阵

| 测试层 | 必测场景 |
| --- | --- |
| Unit | entity canonicalization、column binding、quote span、row readiness、retry/freeze、branch transition |
| Property | evidence merge 不丢 SUPPORTS/CONFLICTS；任意顺序重放得到同一 current state |
| Contract | Worker payload/result schema、Backend persist/read/resume、citation count/binding |
| Integration | fake search/fetch/LLM 的两轮修复成功；冲突分支 merge；callback retry/idempotency |
| Security | SSRF、redirect、prompt injection、超大响应、危险 content-type、secret 不进入 trace |
| E2E | workspace-only、external-only、mixed-source、fetch fallback、mid-run resume、cancel |
| Evaluation | entity/row/cell/citation/branch 指标与 cost/latency/rollout 指标 |

合并门槛：

- 核心 suite 不允许无解释 skip。
- P0 能力必须有至少一个负例，证明 guard 真能阻止错误写入。
- E2E 报告中的每条 final finding 均可回溯到 snapshot span。
- 恢复后 current state 与无故障连续运行结果等价，audit history 可不同。

## 9A. 2026-07-14：以 Research 能力为中心的参考项目复审与补强

本节是对 `reference/MiroFlow`、`reference/Marco-DeepResearch/Marco-DeepResearch-Family/Table-as-Search`、`DeepWideSearch`、`Marco-Agent-DeepResearch` 的第二次代码级对照。结论以当前实现和自动化测试为准，不将“结构相似”误写为“效果相同”。

| 参考能力 | NoteWeave 当前状态 | 结论与边界 |
| --- | --- | --- |
| Table-as-Search 的 schema -> wide discovery -> entity freeze -> deep cell completion | 已实现 | `ResearchSchema`、entity/cell ledger、evidence-to-cell 精确绑定、freeze、逐轮 checkpoint 和 targeted recovery 已形成闭环。当前分支是受限顺序执行，不是并行 sub-agent。 |
| Table-as-Search 的独立 Main/Tabular/Deep Agent 与 MongoDB table tool | 未直接复制 | 当前以固定、可审计的 `ResearchToolbox` 替代动态 agent tool-calling，降低了 prompt 驱动任意写表的风险；但不具备可交互 table operator、独立 agent 模型角色和真正的 cell-task 并行调度。 |
| DeepWideSearch 的 entity / column / row / item 指标与多 rollout 成本观察 | 已实现内部版本 | 已输出 Core Entity、Column/Row/Cell F1、citation/branch 指标、Avg/Max/Pass@N、token/cost/retry。它是内部 gold-set harness，不能宣称 DeepWideSearch 官方成绩。 |
| DeepWideSearch 的整表 exact-match 判分 | 新增严格内部口径 | `evaluation.py` 现在区分 coverage `passed` 与 `exact_table_success`。只有 gold 明确声明 `require_exact_table_match=true`，且每个 required cell 给出 value 时，才产出严格整表结论；额外 VERIFIED cell、实体集合不一致、值不符均失败。数值/日期容差必须逐 cell 显式声明，默认不做模糊匹配。 |
| DeepWideSearch 官方 JSONL + CSV gold、LLM judge 和真实四次 rollout | 尚未完成 | 需要取得可用官方 gold/许可边界、真实 provider 与固定实验环境后再编写 adapter 并运行；当前禁止将内部样例替代官方 benchmark。 |
| Marco 的 call/token/context/cost budget、accuracy/efficiency profile、early stop | 大部分已实现 | 已有 purpose 配置、调用数上限、retry/cost ledger、context safety buffer、depth profile、verifier early stop。上下文仍是字符预算近似而非模型 tokenizer 精确预算。 |
| Marco 的 task-wide wall-clock deadline | 新增 | QUICK/STANDARD/DEEP 分别默认 90/240/480 秒；到期时完成正在执行的一轮，持久化 `wall_clock_seconds_consumed` 到 checkpoint，并给出 `WALL_CLOCK_BUDGET_EXHAUSTED` 的 guarded output。resume 不会重置已消耗预算；设为 `0` 可显式关闭该限制。 |
| Marco 的 test-time candidate collection / verifier rerank | 尚未完成 | 生产主链路不默认重复 rollout，以免成本和不确定性放大；适合在后续离线 benchmark harness 中作为可选实验能力实现。 |
| MiroFlow 的 ToolManager、动态工具选择、多 provider orchestration | 有意不完整 | 当前 search/fetch/read/extract 是显式 allowlist 流水线，具有 adapter fallback、trace、timeout/retry/cancel、并发 fetch 和 rate limit。尚无可注册 tool schema registry、语义结果缓存或 provider/model fallback；引入前需先定义 capability policy 与安全边界。 |

### 9.1 本轮新增的可验证风险控制

1. **总时限而非单点 timeout**：此前 fetch/LLM 各自有 timeout，但多轮 retry 仍可能累积成不可控的总耗时。现在总时限以单调时钟计量，并随 checkpoint 续存；不会中断当前轮造成半写状态，而是在当前轮完成后强制进入 guarded terminal。
2. **严格表格成功率**：此前 `passed` 的定位是“所需覆盖、引用和分支门槛通过”，因此允许额外行/列。该指标继续保留用于诊断；新增 strict result 后，报告必须说明使用的是 coverage 还是 exact-table 口径，二者不得混用。
3. **值等价不再隐式放宽**：严格评测默认采用规范化精确匹配。`number_near` 需要 `relative_tolerance` 或 `absolute_tolerance`，`date_near` 需要 `date_tolerance_days`；未声明即不接受近似值。

### 9.2 后续优先级（按研究质量，不按功能数量）

1. 建立经过许可确认的 DeepWideSearch 数据转换和标准化 runner，输出固定 rollout 文件、版本、配置、输入/输出成本与失败原因；在真实 LLM 配置完成后才可报告外部结果。
2. 将字符级 context compaction 升级为按实际模型 tokenizer 计算的预算，并保留 evidence/card/cell 的结构化语义摘要，避免只靠字符串截断丢失关键绑定。
3. 为离线评测实现可开关的 candidate collection + independent rerank，比较单次、Pass@N、成本与延迟；不进入默认生产路径。
4. 若确有动态工具需求，再设计最小 ToolManager：每个工具必须声明输入 schema、输出 envelope、数据权限、网络能力、timeout、预算和 trace contract。不要直接引入不受控的 LLM 工具选择。

### 9.3 本轮验证边界

- 新增 deadline、checkpoint budget 与严格整表评测均有单元/集成回归测试。
- `python -m pytest workers/research-worker/tests -q`：`210 passed`。
- 自动化测试使用 Fake LLM、模拟 HTTP 或 workspace source；当前未配置可验证的真实 Research LLM URL/API key，也未完成真实公网 E2E 或外部 benchmark rollout。
- 因此可以描述为“具备可审计、受预算约束的研究运行时和内部严格评测能力”，不能描述为“已经达到 Table-as-Search/Marco 的多 agent 完整度”或“达到 DeepWideSearch 官方分数”。

## 9. 审计起始验证记录（M0 执行前）

M0 开始前只执行了 Research Worker 本地测试，用于确认旧基线，不代表 P0 验收；M0 完成后的新结果见第 12 节：

```powershell
cd workers/research-worker
python -m pytest tests -q
# 83 passed, 61 skipped

python -m pytest tests -q -rs
# 确认大部分 skip 属于旧核心契约尚未迁移
```

原文记录的 Frontend、Compose、Backend build 结果属于此前工作记录，本轮未重新执行，不应写成 2026-07-11 的新验证结论。

## 10. 修复完成后的简历安全门槛

M0 阶段建议使用：

> `Built a prototype of a verification-oriented Deep Research workflow with structured research state, layered verification, recovery decisions, external search/read adapters, and auditable report delivery.`

M1/M2 完成后、M3/M4 完成前，可升级为以下受限口径：

> `Implemented a verification-oriented Research Worker with entity-and-cell Table-as-State, independently recomputed local/global gates, bounded sequential counterfactual recovery, and snapshot-grounded evidence provenance.`

这里的 `sequential`、`Worker` 和 `snapshot-grounded` 是必要边界：当前不代表并行 branch runtime、crash-safe durable resume 或 claim-citation 语义准确率已经完成验收。

在 M1/M2/M3/M4 全部通过后，才可以使用当前强口径：

> `Designed and implemented a Verification-Centric Research Harness with Closed-Loop Research, Table-as-State, independent Dual Verifier, bounded Counterfactual Branching, evidence-grounded citations, and crash-resumable report delivery.`

以下表述在对应验收前禁止使用：

- “真正并行反证分支”——必须有 branch-scoped 并发执行 trace。
- “Table-as-State 是运行真源”——必须先解决 entity/cell/evidence binding 和跨轮持久状态。
- “Dual Verifier 独立审计”——Global 必须不复用 Local 结论。
- “Adaptive Evidence Horizon”——必须存在按 cell uncertainty/边际收益动态扩缩的策略。
- “可恢复长任务”——必须有运行中 durable checkpoint，而不只是完成后的快照复用。
- “citation-backed / citation accurate”——必须通过 quote span 和 claim-citation support 验证。

## 11. 最终完成定义

Research Worker 只有同时满足以下条件，才能从“工程骨架”升级为简历所描述的 Deep Research Agent：

1. row 真正代表研究实体，cell 真正代表 schema 属性，Evidence 精确绑定 cell。
2. 当前状态与历史审计分离，跨轮 retry/branch/verdict 可以正确演进和恢复。
3. Counterfactual Branch 有实际执行、裁决、终态和 merge，不只是 decision metadata。
4. Local 与 Global Verifier 形成两条可解释、相互独立的 gate。
5. final finding、citation、quote span、snapshot 形成完整 provenance chain。
6. live web 具备 SSRF、prompt injection、timeout、rate limit 和内容边界。
7. checkpoint 能覆盖运行中故障，Kafka/callback 具备幂等、DLQ、cancel/heartbeat 语义。
8. 核心测试不再大面积 skip，并通过 entity/row/cell/citation/branch 与成本效率评测。

在这些条件完成前，后续开发优先级应是“修正状态和证据真源”，而不是继续增加新的展示字段或架构名词。

## 12. M0 执行记录（2026-07-11）

M0 已完成，执行结果如下：

- 已在项目亮点、架构文档、面试手册和执行手册顶部加入 prototype / target architecture 边界声明。
- 已新增 `docs/ResearchAgent-Capability-Coverage.md`，以 GREEN/YELLOW/RED、代码证据、测试证据和退出门槛管理能力完成度。
- 已移除 Research Worker 测试中的全部显式 skip 和 `P0_INCOMPAT` skip marker。
- 无 LLM / 无法解析的 extraction 用例已按“返回空 Evidence、进入 recovery”的新契约重写。
- 旧的固定 evidence ID、RULE-mode 假证据和事后 phase payload 断言已迁移为显式 Fake LLM、动态 ID 和实时 tool trace 断言。
- `research_checkpoint_candidate` 已补齐 `audit_summaries` 与 `toolbox_summary`，保证 checkpoint payload 与顶层审计/工具摘要契约一致。
- 12 个已经由 M1/M2 缺口确认阻塞的测试改为 `strict=True` xfail，并逐项绑定 P0 编号；它们不算通过，若意外 XPASS 会使 suite 失败并要求复核。

M0 最终验证：

```powershell
cd workers/research-worker
python -m pytest tests -q -rxX
# 132 passed, 12 xfailed, 0 skipped
```

因此 M0 的完成含义是“口径已冻结、能力账已建立、隐藏 skip 已清零、已知缺口已转成可执行失败契约”，不表示 M1/M2 的 P0 正确性问题已经修复。

## 13. M1/M2 执行记录（2026-07-11）

M1 已完成的代码事实：

- 新增统一 entity identity，hit、Evidence、row、cell 复用同一 canonical ID；Source 作为 provenance 聚合，不再充当 entity。
- `ResearchEvidenceCard` 强制携带 `entity_id + column_key`，ledger 与 CellVerifier 只接受精确二元组绑定；旧的“同 entity 所有 Evidence 填入每个 cell”路径已移除。
- requirement contract 已绑定 row/cell；row 只有在所有 required cells VERIFIED 且无冲突时才可 VERIFIED/READY。
- retry、VERIFIED、FROZEN 在跨轮 ledger rebuild 前 seed、合并时保持；FROZEN 不再被后续模型输出解冻。
- quote 会在 opened window 中定位；无法定位的模型 quote 会修复为真实窗口片段，并记录 `quote_start/quote_end`、`snapshot_key`、`content_sha256`。

M2 已完成的代码事实：

- Global Verifier 不再复制 Local 的 intent contract/alignment；它从当前 ledger、required-finding progress、active branch 和 factual blocker 独立重算 gate 与 completion。
- Local/Global 当前状态不再通过历史最大 score 或 sticky WARN 合并；decision records 保留审计历史，current status 使用本轮结果。
- Counterfactual branch 已有有界顺序执行；只有同一 `(entity,column)`、来自不同 source、且 `discovery_round > created_round` 的新 Evidence 才能产生 result evidence 并进入 `ACTIVE_BRANCH -> RESOLVED_BRANCH`，否则保持 active 并公开 `COUNTERFACTUAL_EVIDENCE_MISSING`。
- 已处理的旧冲突不会仅因 Evidence 仍保存在 ledger 中被无限重开；恢复 checkpoint 中的活动 branch 可以被关闭且不会复制 branch ID。

最终验证：

```powershell
python -m pytest workers/research-worker/tests -q
# 145 passed, 0 failed, 0 xfailed, 0 skipped
```

M1/M2 的完成边界仅限当前 Research Worker 与 Fake-provider/fixture 自动化契约。以下仍留给 M3/M4：运行中 durable checkpoint、取消/心跳/幂等/DLQ 的生产闭环、Live Web SSRF 与 prompt-injection 防护、真实并发 branch executor、独立 claim-citation semantic verifier、gold-set 多 rollout 效果和成本评测。因此完整简历强口径仍未解锁。

## 14. M3/M4 执行记录（2026-07-11）

M3 已完成：

- `run_research_loop` 在每个完成 round 后生成包含 rows/cells/retry/branches/budgets/cursor 的 runtime checkpoint；真实 Callback 以稳定幂等键发送，Backend 按 `(research_run_id, checkpoint_no)` 幂等落对象存储和数据库，final completion 不再删除旧轮。
- Worker 发送 heartbeat，并在 coordinator 返回 `CANCELLED` 时停止；progress/complete/fail/checkpoint 带 idempotency key，Backend 新增 durable callback receipt。
- Kafka consumer 区分 `failed_attempt_count`、`failed_message_count`、`dead_letter_count`，区分 retryable/non-retryable；生产入口使用 `KafkaDeadLetterSink` 并等待 broker ack，只有 complete 或 durable DLQ ack 后 commit，内存 sink 仅供显式单测/本地调用。
- Fetch 增加有界并发、provider 首槽配额、rate limit、timeout/retry；LLM client 增加 retry/backoff、per-purpose model/temperature/max tokens、带 safety buffer 的可审计上下文压缩，以及 token/latency/cost/termination ledger。
- Live Web 仅允许 HTTP(S)，拒绝 credential、loopback/private/link-local/metadata/reserved address，并逐跳校验 redirect；HTTP/HTTPS 实际连接固定到已校验 IP，TLS SNI/证书仍校验原 hostname，从而关闭“校验 DNS 与连接时再次解析”的 TOCTOU；同时限制响应字节、解压大小、content encoding 和 content-type。
- 外部正文进入 LLM 前标记为 `UNTRUSTED_EXTERNAL_CONTENT`，注入 classifier 检测 instruction override、tool policy、secret exfiltration 和 role impersonation；命中项成为 Global blocker。
- trace schema 的 event/run/round/branch/tool/attempt/started/ended 字段已在构造时实际填充，正文改存 hash/size，secret 字段统一 redaction。

M4 已完成：

- 新增 citation verifier，对每条 verified finding 检查 snapshot span/hash、entity/cell association、quote-only lexical support 和明显否定反转；citation payload 只从通过审计且实际被 finding 引用的 evidence 派生。它不使用模型自己生成的 `claim_text` 作为自证来源。
- 新增 report AST 与 section-level revision loop；失败 finding 只降级对应 section 为 `CITATION_GUARDED`，不做无差别全文重写，报告公开 citation audit 与 revision termination。
- 新增 Adaptive Evidence Horizon，按 `(entity, column, uncertainty, source diversity, retry)` 输出 shrink/hold/expand/counterfactual 决策。
- 新增版本化内部 gold set 与 CLI evaluator，输出 Core Entity Accuracy、Cell/Column-F1、row completeness、citation association/support、branch success、Avg/Max/Pass@N 和 token/cost/retry overhead。
- Source provenance 契约增加 source type、author/institution、published/updated time 和 freshness status；workspace/外部 adapter 未能取得可靠发布时间时必须保留 `UNKNOWN`，当前不声称所有来源都已完成 freshness 抽取。

最终验证：

```text
Research Worker: 175 passed
Backend Research/Callback 定向测试: passed
Backend full suite: 81 tests, 3 unrelated Phase3 Wiki failures（Research 链路未失败）
Frontend: 40 passed; production build passed; UI contract passed
Compose: configuration validation passed
```

公网 live canary 在当前机器上没有伪造为通过：系统 DNS 将 `example.com` 映射到 `198.18.0.23`（保留测试网段），新 SSRF guard 正确返回 `SSRF_BLOCKED`。发布环境应在正常公网 DNS/egress 下执行同一 canary；不得为追求测试绿色而允许保留地址。另一个持续边界是反事实分支当前为**有界顺序执行**，因此仍禁止简历表述“真正并行反证分支”或“达到 DeepWideSearch 外部 benchmark 水平”。

## 15. 2026-07-12 最终反向审计与补强记录

本次不是继续按字段清单验收，而是从“怎样制造一个看似通过但语义错误的结果”反向检查。发现并修复了以下假完成路径：

1. **反事实分支假解决**：旧实现会把原冲突 Evidence 自己写入 `result_evidence_ids` 并在下一轮自动 resolve。现改为必须出现新轮次、不同来源、同一 cell 的独立 Evidence；单来源冲突会诚实保持 active，预算耗尽后仅 guarded write。
2. **Adaptive Evidence Horizon 只产 decision 不控制执行**：现将 EXPAND/COUNTERFACTUAL 的目标列和动态窗口预算写入下一轮 plan，reader 会按目标列、来源排除和 counterfactual 策略选择窗口。
3. **Full-Horizon / Lazy Replanning 只有简历名称**：`ResearchPlan` 现保存完整 phase horizon、revision 和 replan patch history；replan 只记录受影响 cell/phase，保留其余 horizon。
4. **Premature Commitment Guard 不阻断写作**：required cell 未验证、FROZEN、active branch、最小来源或最小 Evidence 不满足时，预算内必须继续读；Global 非 READY 也不得提前 synthesis。
5. **Citation 自证循环**：support 不再比较模型生成的 card claim，只比较 report claim 与 snapshot quote；新增 span/hash、entity association 和 polarity inversion 检查，失败 finding 会被 section-level demote。
6. **隐藏 autouse Fake LLM**：Fake builder 改为模块显式 opt-in；无 LLM 的 extractor 测试不再被 conftest 偷偷注入 Evidence。当前套件没有 skip/xfail 掩盖核心路径。
7. **Evidence ID 跨轮碰撞**：ID 改为带 window 与内容摘要的稳定 hash，相同卡去重，不同来源/quote 不再覆盖。
8. **branch budget 只在配置中存在**：新分支创建现在计算已使用 branch ID 和剩余预算，超额时产生 `BRANCH_BUDGET_EXHAUSTED`，不会继续制造 session。
9. **Entity Freeze 只有 phase 名**：第一轮 wide discovery 后冻结 candidate set/version；后续只允许新 Evidence 补全已知 entity，新 entity 进入 rejected candidate audit，不会悄悄改变比较表总体。
10. **DLQ “成功”只写内存**：生产入口创建 Kafka producer，使用 `acks=all` 并等待 send/flush ack；DLQ 发布失败时不提交 poison message offset。
11. **Trace 字段空壳**：tool/harness trace 现在实际写入 UTC started/ended、run、round、branch、event、tool 和 attempt；大文本 hash 化、secret redaction 保持不变。
12. **LLM policy 只有统一模型**：Research Worker 使用独立的 `NOTEWEAVE_RESEARCH_LLM_PURPOSE_OPTIONS` 为 extraction/cell/local 等 purpose 分配 model、temperature、max_tokens；Artifact Worker 使用独立 `NOTEWEAVE_ARTIFACT_LLM_*`，两者均不读取 Backend 的 `NOTEWEAVE_LLM_*`。超长 payload 会在 safety buffer 前进行确定性压缩并保留原长度/hash。
13. **HTTP 解压错误混为 size limit**：unsupported encoding、损坏 gzip、解压后超限分别产生不同 failure code，避免错误恢复策略。
14. **冲突范围污染主分支**：row/cell 只在其冲突 Evidence 命中目标 branch 时迁移；无关实体和 cell 保持原 branch，ledger 同时保存全部 active branch IDs。
15. **反事实 Entity 二次哈希**：恢复计划现在传递系统拥有的 target entity/branch 映射；Extractor 只对新业务实体做 canonicalize，对允许的恢复 target 保留精确 ID。
16. **引用替换造成伪 grounding**：模型 quote 不在 snapshot 中时直接拒绝 Evidence Card，不再用窗口首句替换 quote 后保留原 claim/relation。
17. **Requirement readiness 早于 Cell Verifier**：Cell Verifier 后基于最终 cell/row 状态重算 requirement、row 和 cell completion，杜绝 stale ready。
18. **Checkpoint 同号覆盖与恢复漂移**：同一 run/checkpoint_no 采用不可变 hash 冲突检查；resume 恢复保存的 plan revision、query、horizon、trace 和真实 round cursor。
19. **全局搜索预算逐轮重置**：每轮只分配全局剩余额度，所有轮次合计不超过 stop contract 的 global limit。
20. **Callback 重启幂等掩盖取消**：execution ID 进入 progress/heartbeat key；后端先检查 terminal status，再检查 duplicate receipt，`CANCELLED` 优先返回。
21. **确定性错误盲目重试**：LLM、Search、Fetch 对 4xx（408/429 除外）不重试；trace 记录实际 provider/transport attempt count，失败回调区分 retryable/non-retryable。
22. **终止原因只有 decision**：最终 decision 现在同时输出 `terminal_disposition`、`handoff_required`、`abandon_reason`，后端 API 与前端 Research Process 均可见。
23. **Wiki 本地模式 outbox 无消费者**：全量回归发现 Kafka 关闭时 Wiki ingest 只入队不执行；现由 Wiki 服务统一 Kafka 投递和同步降级，并移除上传路径重复发布。
24. **后端 Kafka 异常被吞并提交 offset**：consumer handler 现在向容器抛出失败，关闭 auto commit，按 record ack；两次重试后由 `DeadLetterPublishingRecoverer` 写入 `<topic>.DLT`。通用 source/wiki outbox 只在事务提交后发送，原子 claim `READY -> DISPATCHING`，broker ACK 后才置 `SENT`，失败回到 `READY` 并由定时 redrive 重投。
25. **Source Kafka 解析同类调用绕过事务**：异步入口先在事务外读取对象，再用 `TransactionTemplate` 显式包裹 chunk/window/source 状态写入；失败继续抛给 Kafka retry/DLT，不再留下半提交状态或被 consumer 当作成功。

### 15.1 完成定义的最终对照

| 完成条件 | 结论 | 证据边界 |
| --- | --- | --- |
| schema-first entity/cell state | PASS | canonical ID、精确 cell binding、required readiness、entity freeze |
| verifier-scoped recovery | PASS | recovery target、AEH、lazy replan 和非 sticky current state |
| counterfactual result/裁决/终态 | PASS（顺序执行） | 独立来源/新轮次硬条件；不声称并行 executor |
| verifier-gated synthesis | PASS | premature guard + Global READY gate + citation override |
| snapshot-grounded citation | PASS（受限混合语义） | span/hash/association + 可选独立 semantic judge + lexical/polarity fallback；不声称通用 NLI |
| crash resume / callback idempotency | PASS | round checkpoint、V024 receipt、heartbeat/cancel |
| Kafka poison-message safety | PASS | durable Kafka DLQ ack 后 commit |
| DeepWideSearch 风格评测维度 | PASS（内部评测） | 指标与 rollout 聚合已实现；未在官方外部数据集上宣称 SOTA/同等水平 |
| MiroFlow 风格工具治理 | PASS（单进程工具箱） | bounded tool calls、retry/error/trace/context/cost；不声称分布式并行运行时 |

### 15.2 当前可安全使用的简历口径

> Designed and implemented a verification-centric research harness with schema-first Table-as-State, independent local/global verification, bounded sequential counterfactual recovery, adaptive cell-level evidence expansion, snapshot-grounded citation auditing, and crash-resumable delivery.

不得删除 `bounded sequential`，不得把内部 gold set 写成 DeepWideSearch 官方 benchmark 成绩，也不得把当前“可选语义 judge + lexical/polarity fallback”的 citation verifier 描述成“经过外部 benchmark 验证的通用语义蕴含模型”。

### 15.3 最终验证记录

```text
Research Worker: 201 passed, 0 failed, 0 skipped
Artifact Worker 回归: 192 passed
Backend Research/Callback 定向测试: passed
Frontend: 40 passed; production build passed
Backend full suite: 83 passed, 0 failed, 0 skipped
Compose: configuration validation passed
Python compileall: passed
git diff --check: passed（仅既有 LF/CRLF 提示）
```

## 16. 2026-07-14 Research Worker 专项复审

本轮从真实运行边界复查 Worker，并落实以下改进：

1. **Redirect origin 凭据隔离**：HTTP 抓取遇到 scheme、hostname 或有效 port 改变的 redirect 时，会移除 `Authorization` 与 `X-API-Key`；此前只比较 hostname，HTTPS 降级或同 host 跨 port redirect 可能保留凭据。
2. **单 hit 抓取故障隔离**：一个 Fetch Adapter 抛出异常时只丢弃该 hit，不再使并发批次的其余来源全部失败；日志中的错误文本先脱敏。
3. **Kafka 原始字节与 DLQ 脱敏**：Kafka consumer 保持原始 bytes 到 worker 层再 decode，因此非法 UTF-8 可进入 DLQ；DLQ 中的错误文本也统一 secret redaction。
4. **Trace 列表项脱敏**：列表中的字符串也经过 secret sanitizer，避免 header/error array 绕过顶层 key 脱敏。
5. **LLM 调用上限**：Research Worker 独立使用 `NOTEWEAVE_RESEARCH_LLM_MAX_TOTAL_CALLS`，默认每任务 64 次。达到上限写入 `CALL_BUDGET_EXHAUSTED` trace 并返回空结果，触发既有 verifier/recovery 路径；设为 `0` 可显式关闭。
6. **LLM 配置隔离**：Research Worker 仅读取 `NOTEWEAVE_RESEARCH_LLM_*`，不读取 Backend 的 `NOTEWEAVE_LLM_*`；Artifact Worker 对应使用 `NOTEWEAVE_ARTIFACT_LLM_*`。

本轮验证：Research Worker `207 passed`；Artifact Worker 配置隔离回归 `193 passed`；`docker compose --profile app config --quiet` 与 `git diff --check` 均通过。仍未把模拟测试包装成真实模型效果：真实 provider URL/key/model 配置后，仍应独立执行 live smoke、费用校准和质量基准评测。

## 17. 2026-07-18 全量审计与 TDD 补强记录

本轮从 `67a8323e` 创建隔离 worktree 复核 Research Agent，先运行审计信号，再按公开 seam 做红 → 绿测试。审计发现并已修复：

1. **跨数据库 JSON 兼容**：H2 JSON 兼容类型可能把 `source_domains_json/evidence_ids_json` 返回为一层 JSON 字符串，quorum completion 会误报 `RESEARCH_AGENT_COMPLETION_QUORUM_CONFLICT`。`ResearchAgentCompletionCommitter` 现在只解包一层并继续执行严格数组/非空字符串校验；Research completion 定向测试恢复为全绿。
2. **MA5 公平性不是 manifest 装饰**：benchmark runner 现在实际绑定 `max_concurrency`、`max_llm_calls`、temperature、seed；LLM request body 支持 seed；超过 `max_cost` 的 rollout fail-closed。
3. **外部能力隔离**：`allow_external=false` 的 rollout 会在临时环境中清空 Search/Jina 凭证并关闭 URL reader；`archive_required=true` 时任何未归档或 fallback 的 external document 都不会进入 benchmark record。
4. **A/B 快照一致性**：benchmark record schema 升为 `research-agent-benchmark-record.v2`，保存 `observed_source_snapshot_digest`；比较器拒绝真实读取快照不一致的 rollout 集，避免把实时网页变化当成算法收益。
5. **旧 Worker HTTP 执行入口移除**：`/debug/run-task` 与 `/tasks/{task_id}/run` 不再注册，Worker HTTP 面只保留 health；正式执行入口为 agent Kafka consumer。`start-research-consumer.ps1` 已切换到 `app.agent_kafka_consumer`。
6. **Windows 验证脚本可移植性**：新增 `.gitattributes` 将 `scripts/ma4g/*.sh` 固定为 LF；修复前 LockMatrix 的 13 个测试虽已运行，最终 verifier 会因 CRLF 在 `set -euo pipefail` 处失败并正确留下 FAILED manifest。修复后重新执行，MySQL 8.4.9 / READ-COMMITTED 的 A–L2 共 `13/13`，manifest 为 `VERIFIED`。
7. **有限并发下的研究优先级**：Coordinator 不再只依赖 cell-key/FIFO；任务持久化 `priority_score/priority_reason`，以服务端权威的 counterfactual、high-risk 和报告关键列计算精炼优先级，Dispatcher 按 `priority desc, wave asc` 取有限批次。两个公共 seam 测试先因列缺失红灯，再由 V083 迁移和实现转绿。

隔离 worktree 的 Worker 全量结果为：

```text
377 passed, 0 failed, 0 skipped
```

Backend 当前 Research Agent 聚合为 `212 tests, 0 failures, 0 errors, 1 skipped`；skip 仍是环境门控 Redis integration。MySQL LockMatrix 为 `13/13` 且最终 verifier/manifest 均通过，而不是只读取测试进程日志。

当前仍未完成、且不能包装成已完成的外部证明：真实 Research LLM/Search 凭证、真实公网四轮 A/B、生产网络长期 lease/recovery soak、答案质量与 p95 优于 TAS/MiroFlow/DeepWideSearch 的证据。MA5 runner 的 `SEQUENTIAL_V2/LOCAL_PARALLEL` 只证明同进程算法 seam，不替代分布式 `INCREMENTAL_V1` 的运行证据。
