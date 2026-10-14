# 模块一：Deep Research 改造设计

## 1. 现状结论

线上入口是 `app.agent_kafka_consumer`，走 Agent 模式：Java 端的 Coordinator 规划表格并下发角色任务，Worker 执行 WIDE_DISCOVERY、DEEP_CELL、COUNTERFACTUAL、EVIDENCE_AUDIT、SYNTHESIS 这几个角色。`runner.py` + `loop_runtime.py` 的单进程循环只被 benchmark 和 gate1 冒烟脚本使用，但 `planner.py` 和 `search_adapters.py` 被 DEEP_CELL 复用，所以两条链路的问题都要处理。

Java 端的状态治理（单元格版本、Fencing、预算预留、Replan 审计、证据资格校验）是完整的。问题集中在决策内容本身：表格怎么规划、检索什么、怎么补查、怎么判断冲突。

## 2. 缺陷清单

| 编号 | 缺陷 | 位置 |
|---|---|---|
| F1 | 默认配置下表格退化成 1 行 × 4 列（answer / key_evidence / limitations / implications），所有研究问题都一样。打开 `intent-matrix-v2` 后，行靠正则按顿号、和、vs 切分问题得到，列是 4 个固定列加上用户约束，列没有描述和检索意图 | `ResearchAgentRunBootstrapService`、`ResearchMatrixPlanningService`、`application.yml:61` |
| F2 | 单元格检索词质量差。`query_policy.query` 可能是最长 12000 字符的规划文本（包含对话摘要和历史消息），Worker 再拼上实体说明和 `:: counterfactual evidence check` 这类模板，原样发给搜索引擎。单元格对应的列没有参与检索词生成 | `ResearchBriefCompiler.java:66`、`deep_cell_executor.py:366`、`planner.py:192` |
| F3 | 针对评测题硬编码：PostgreSQL / MySQL 的官方文档 URL、产品到域名的映射、`JSONB GIN` 这类查询提示词、`JSON_TABLE` 等技术词正则 | `deep_cell_executor.py:69-91`、`:559` |
| F4 | WIDE_DISCOVERY 没有检索也没有调用模型，只把必需发现项原样转成提案，置信度固定为 0.85 / 0.60 | `discovery_executor.py` |
| F5 | Replan 只记录版本号。修复任务沿用原检索词，仅排除已用过的来源；Java 传下去的 `conflict_trace_id` 和 `conflict_evidence_ids` 在 Worker 里没有被使用，反证检索并不针对具体结论 | `ResearchAgentLocalReplanRecorder`、`ResearchAgentTaskCoordinatorService.java:561` |
| F6 | 候选值按 `claim_text` 完全相等分组，不同来源用不同措辞说的同一件事无法合并，高风险列要求的两个独立来源很难满足 | `deep_cell_executor.py:842` |
| F7 | 冲突检测只覆盖数值和比较方向两类结构化事实，文字表述上互相矛盾的候选值识别不出来 | `ResearchGlobalConflictDetector` |
| F8 | 旧循环链路：Replan 追加 `recovery round N` 模板查询；反证要求在报告含冲突章节或检索词含 counterfactual 时直接 PASS；研究类型靠关键词计数；来源相关度写死 0.82 / 0.68 / 0.42 | `loop_runtime.py:1019`、`verifier.py:1082`、`planner.py:496`、`search.py:128` |

## 3. LLM 调用的降级原则

- **规划和检索词生成**（F1、F2、F4、F5）：LLM 不可用或输出不合规时，退回确定性规则，并在 trace 和 `reason_codes` 里记录降级原因。研究可以继续，只是质量下降。
- **判定类调用**（F7 的矛盾判断、F8 的反证判定）：LLM 不可用时结果记为 `NOT_RUN`，不允许判 PASS。

## 4. 改造方案

### S1 新增 PLANNING 角色，用 LLM 规划表格（解决 F1）

**流程**

1. 创建 Run 时，Java 先按现有确定性规则建出骨架表格（保证任何情况下都能运行），状态标为 `PLAN_DRAFT`，然后下发一个 PLANNING 角色任务。Coordinator 在规划完成前不下发 DEEP_CELL。
2. Worker 的 `PlanningExecutor`：
   - 用问题原文做 1 到 2 次探测检索（受预算约束），拿到标题和摘要作为参考；
   - 调用 `research.plan.matrix`，输入问题、研究意图、对话摘要、来源范围、探测结果，输出：
     ```json
     {
       "research_type": "TECH_SOLUTION_COMPARISON",
       "entities": [{"label": "PostgreSQL", "aliases": ["Postgres"]}],
       "dimensions": [{
         "key": "json_indexing", "label": "JSON 索引能力",
         "description": "支持哪些 JSON 索引类型及适用查询",
         "answer_format": "SHORT_TEXT", "high_risk": false,
         "search_intents": ["JSON index types", "JSON query operators supported by index"]
       }],
       "authoritative_domains": ["postgresql.org"]
     }
     ```
   - 用 pydantic 校验结构，失败最多修复重试一次。
3. Java 的 `ResearchMatrixPlanService` 做权威校验：行数、列数、单元格数上限，标签规范化去重，`authoritative_domains` 必须落在来源策略允许的范围内。校验通过后用新表格替换骨架（`plan_revision` 递增，`planner_version = llm-matrix.v1`），写入 `research_matrix_plan` 和 Replan 审计。
4. LLM 不可用或校验失败时，保留骨架表格，`reason_codes` 记 `PLANNER_FALLBACK_DETERMINISTIC`。

**数据变化**

- `research_cell` 对应的列定义需要描述和检索意图：新增 `research_column` 表（`research_run_id, column_key, label, description, answer_format, high_risk, search_intents_json, plan_revision`），由 DEEP_CELL 任务快照带给 Worker。
- 新增 Flyway 迁移脚本。

### S2 单元格级检索词规划（解决 F2）

1. Java 在 `query_policy` 里拆出两个字段：`research_question`（用户原问题，短）和 `research_brief`（对话上下文，仅供模型参考）。不再把 12000 字符的规划文本当作检索词来源。
2. Worker 新增 `cell_query_planner.py`，调用 `research.cell.queries`，输入问题、实体及别名、目标列的描述和检索意图、已经尝试过的查询、需要排除的来源，输出 2 到 4 条自然语言查询，每条标明对应的列、语言和用途（direct / verify / counter）。
3. 校验：单条不超过 200 字符，不含换行和控制标记，和已尝试的查询做规范化后的 Jaccard 去重（阈值 0.8）。
4. 查询类别写进 `SearchQueryPlanItem.family` 字段，搜索串只保留自然语言。`_classify_query_family` 和 `_search_angle` 不再从字符串里解析 `::`。
5. 降级规则：实体标签 + 列标签 + 从问题中抽取的关键词（复用 `text_terms`），同样不带任何模板标记。

### S3 删除评测题硬编码（解决 F3）

删除 `_OFFICIAL_PRODUCT_DOMAINS`、`_OFFICIAL_PRODUCT_QUERY_HINTS`、`_OFFICIAL_PRODUCT_SEED_URLS` 和技术词正则。替代做法：

- 官方来源优先级来自 S1 规划输出的 `authoritative_domains`，以及来源策略里用户允许的域名；
- `_domain_focused_query` 改为把实体标签和列的检索意图组合成带 `site:` 的查询，不再依赖产品名单。

相关测试改为通用夹具，不再依赖具体产品。

### S4 真实的 WIDE_DISCOVERY（解决 F4）

1. 给 WIDE_DISCOVERY 分配检索预算（1 到 3 次查询）。
2. 输入当前表格（实体、列、已验证单元格的摘要），检索后调用 `research.discover`，提出缺失的实体（ENTITY）、未覆盖的维度（DIMENSION）和补充查询（SEARCH_QUERY）。
3. 置信度按支撑情况计算：提出该项的检索结果来自几个独立域名、是否出现在权威域名中，不再写死常数。
4. Java 端的接受门控（精确率阈值、自动接受开关）保持不变。

### S5 定向反证与按原因改写的 Replan（解决 F5）

1. Java 生成修复任务时，在 `query_policy` 里加入 `repair_target`：单元格、列描述、冲突双方的结论原文、来源域名、修复原因，以及已经尝试过的查询。
2. Worker 按修复原因选择策略：
   - `EVIDENCE_CONFLICT`：调用 `research.counter.queries`，针对目标结论生成反证查询（限制条件、版本差异、反例、官方勘误等方向）；抽取时把目标结论放进提示词，要求模型判断新证据是 SUPPORTS 还是 REFUTES，REFUTES 映射为 `CONFLICTS`；
   - `MARGINAL_GAIN_EXHAUSTED`：根据已尝试的查询放宽或收窄，或者切换语言；
   - `FETCH_FAILED` / `PROVIDER_FAILED`：换检索词和来源类型，保留原始意图。
3. 生成的查询及其摘要写入 Replan 审计，可以回溯每次 Replan 实际改了什么。

### S6 候选值语义聚合（解决 F6）

1. Worker 端对同一单元格内的结论做聚合：先规范化（NFKC、数字和单位统一），再按词项集合相似度聚类；相似度落在中间区间的，调用 `research.claims.equivalent` 判断是否等价。
2. 候选值取簇内置信度最高的表述，`equivalent_claims` 列出簇内每条证据的原始结论。
3. 置信度按独立来源合并：同一域名只计一次，`1 - Π(1 - p_i)`，设上限。
4. Java 资格校验同步调整：证据结论必须等于候选值或在 `equivalent_claims` 中，每条证据仍单独做引文校验。这样高风险列的双来源要求可以真正满足。

### S7 语义冲突检测（解决 F7）

在 EVIDENCE_AUDIT 角色中增加一步：同一单元格存在两个及以上候选簇时，调用 `research.audit.contradiction` 两两判断是否矛盾。判定为矛盾的写成 `SEMANTIC_CONTRADICTION` 类冲突，交给现有的修复流程。LLM 不可用时记 `NOT_RUN`，该单元格不允许进入成文阶段。

### S8 旧循环链路修正（解决 F8）

1. 删除反证判定中按报告章节名和检索词角度直接 PASS 的条件，改为要求反证检索已执行、命中的原文已读取、并由模型给出 SUPPORTS / REFUTES 判定。
2. Replan 改用 S2 和 S5 的检索词规划，删除 `recovery round N` 等模板。
3. 研究类型改用 S1 的规划结果；没有规划结果时退回关键词规则，置信度低时使用通用表格结构，不再默认产品对比。
4. 来源相关度改为标题、摘要、样本文本上的 BM25F 得分，结合来源质量画像归一化。

## 5. 实施顺序

| 阶段 | 内容 | 涉及范围 |
|---|---|---|
| 1A | S2 + S3：单元格检索词规划，删除硬编码 | Worker 为主，Java 拆分 `query_policy` 字段 |
| 1B | S1：PLANNING 角色与 LLM 表格规划 | Java + Worker + 迁移脚本 |
| 1C | S5：定向反证与改写式 Replan | Java + Worker |
| 1D | S6 + S7：候选聚合与语义冲突 | Java 资格校验 + Worker |
| 1E | S4：真实的宽搜索 | Worker 为主 |
| 1F | S8：旧循环链路修正 | Worker |

每个阶段完成后运行 Worker 和 Backend 的全部测试。模块一全部完成后，在 `experiments/deep-research` 的数据集上对比改造前后的有效单元格完成率、引用支撑率和每题 LLM 调用次数，结果保存为可复现的评测文件。

## 6. 配置默认值

1B 完成后 `intent-matrix-v2` 默认打开，1D 完成后 `evidence-audit-v1` 默认打开；Worker 的证据审计角色也默认启用。1E 默认打开 `wide-discovery-v1`，`worker-synthesis-v1` 仍默认关闭。`application.yml` 和 `docker-compose.yml` 同步配置，保留开关用于回退；已有 Run 的冻结开关不被覆盖。

## 7. 参考业界方案后的修订

依据见 [00-业界方案调研](00-业界方案调研.md) 第一部分。

| 修订 | 影响的方案 | 来源 |
|---|---|---|
| Coordinator 每轮按表状态在 `EXPAND_ROWS`（WIDE_DISCOVERY）和 `POPULATE_CELLS`（DEEP_CELL）之间选择，饱和判定加预算上限结束 | S1、S4 | Table-as-Search |
| PLANNING、WIDE_DISCOVERY 的规划调用配置为能力最强的模型 | S1、S4 | Table-as-Search 消融实验 |
| DEEP_CELL 快照增加 `objective`、`column_specs`、`boundaries`、`effort_tier`；PLANNING 输出复杂度等级，按等级分配预算 | S1、S2 | Anthropic |
| 检索词先宽后窄：首轮 2 到 6 个词的短查询 | S2 | Anthropic |
| `ResearchBriefCompiler` 改为由模型生成结构化研究简报（目标、范围、时间、排除项、交付格式） | S2 | open_deep_research |
| 列由多视角提问生成后合并 | S1 | STORM |
| 新增 `PLAN_REVIEW` 状态，前端可增删行列，等待时间可配置（默认不等待） | S1 | Gemini / ChatGPT Deep Research |
| 高风险和冲突单元格强制反证：拆成原子事实，隔离检索与判定，结果为 `REFUTED / NOT_REFUTED / NO_COUNTER_EVIDENCE_FOUND`，报告逐条写明；未执行反证的结论不能进入正式结论 | S5、S7、S8 | SAFE、CoVe、DRNOISE |
| 来源独立性：注册域名去重 + 证据原文 SimHash 合并转载 + noisy-OR（项目自有设计） | S6 | 调研未找到成熟方案 |
| 评测：约 20 条真实问题，单次 LLM 评分（0 到 1 分加通过与否，五个维度），再人工抽查 | 评测 | Anthropic |

## 8. 实施记录

### 1A（2026-10-11 完成）

- Worker 新增 `app/cell_query_planner.py`：调用 `research.cell.queries` 为目标单元格生成 2 到 4 条自然语言检索词，首条为宽泛短查询；校验长度、`::` 和换行、与已尝试查询及同批查询的重复度；全部不合规时带着拒绝原因修复一次；模型不可用或两次都不合规时退回规则查询（实体名 + 问题关键词、实体名 + 列标签），并记录 `CELL_QUERY_LLM_UNAVAILABLE / CELL_QUERY_LLM_CALL_FAILED / CELL_QUERY_LLM_OUTPUT_INVALID`。
- `ResearchPlan` 新增 `query_specs`，查询类别、检索角度、检索通道改为结构化字段；`search_adapters`、`search`、`source_profile` 优先读取字段，没有字段时保持旧的字符串判断（旧循环链路在 1F 统一改造）。
- 检索词从 `research_brief.current_question`（用户原问题）出发，不再使用拼接了对话上下文的规划文本。
- 删除 `_OFFICIAL_PRODUCT_DOMAINS`、`_OFFICIAL_PRODUCT_QUERY_HINTS`、`_OFFICIAL_PRODUCT_SEED_URLS`、`_OFFICIAL_SOURCE_MARKERS`、`_domain_focused_query`；外部域名只取来源策略里明确允许的值，`site:` 限定作用在结构化检索词上。
- Java：`withEntityLabel` 改为 `withCellScope`，`query_policy.column_specs` 带上目标列的标签（来自初始规划和已接受的维度提案，1B 建 `research_column` 表后替换数据来源）；深挖任务的 `llm_calls` 预留由 2 调整为 4（检索词规划 2 次 + 抽取 2 次）。
- 测试：Worker 新增 `tests/test_cell_query_planner.py`（9 个），改写 6 个依赖硬编码产品的测试；Backend 新增列标签测试。

### 1B（2026-10-12 完成）

- 迁移 `V132__research_llm_planning.sql`：新增 `research_column` 表（标签、描述、答案格式、高风险、检索意图）；`research_run` 增加 `plan_status`、`plan_review_deadline`、`research_brief_json`。
- 新增 PLANNING 角色：
  - Java：`ResearchAgentRoles` 统一判断不绑定单元格的角色（替换 6 处 `WIDE_DISCOVERY` 字面量）；能力表、任务创建、快照、完成提交都接入 PLANNING；`noteweave.research.llm-planning-v1` 默认打开。
  - Worker：`PlanningExecutor` 先用问题关键词做一次探测检索（失败不阻断），再调用 `research.plan.matrix`，在一次输出里先列 3 到 5 个研究视角及各自的问题，再给出研究简报、实体、维度、权威域名、投入等级；本地按相同上限校验，不合规修复一次；模型不可用或两次都不合规时返回 `FALLBACK`。
- `ResearchPlanningService`：
  - Coordinator 每次 tick 先经过规划阶段：没有规划任务时下发，进行中时等待，任务结束但没有结果时降级；规划或确认期间不下发深挖任务。
  - PLANNING 任务不计入 Coordinator 快照的波次、任务数和失败数，也不计入完成判定的失败任务。
  - 结果校验：输入摘要必须一致；实体 1 到 20、维度 2 到 8、单元格不超过 80（超出时按列数截断行数并记 `ROWS_TRUNCATED`）；标签规范化去重；行列键由服务端按标签摘要生成；域名格式校验；投入等级限定三档。
  - 校验通过后删除骨架表格并重建行、单元格、`research_column`，写入 `LLM_MATRIX` 规划记录，旧规划标为 `SUPERSEDED`。骨架从未执行，所以不记 Replan 审计。
  - 降级时保留骨架表格，写 `PLAN_FALLBACK` 追踪事件，原因码 `PLANNER_FALLBACK_DETERMINISTIC`。
- 确认阶段：`noteweave.research.plan-review-seconds`（默认 0，不等待）；新增 `GET /research-runs/{id}/plan`、`POST .../plan/approve`、`PUT .../plan`；前端研究工作台加 `ResearchPlanPanel`，展示研究对象、维度、目标和优先参考域名，确认阶段可修改后开始研究。
- 深挖任务的 `query_policy` 带上列描述、检索意图、研究目标、排除项、权威域名、投入等级；Worker 检索词规划据此生成查询，投入等级决定条数（SIMPLE 2、STANDARD 3、COMPLEX 4）。
- `intent-matrix-v2` 默认打开（骨架表格使用正则规划）；顺带修复开关快照解析不兼容 JSON 列被二次转义的问题。
- 测试：Worker 689 通过（新增 `test_planning_executor.py` 7 个）；Backend 1223 通过（新增 `ResearchPlanningServiceTest` 7 个，`ResearchAgentAutomaticRunBootstrapTest` 改为先规划再进入首个波次）；Frontend 294 通过（新增 `ResearchPlanPanel.dom.test.tsx` 4 个）。
- 未完成的验证：本机 WSL Docker 栈未运行，没有在浏览器里截图验证面板，也没有用真实模型跑过完整规划。

### 1C（2026-10-12 完成）

- Java 生成修复任务时，`query_policy.repair_target` 包含单元格、列定义、冲突结论原文及来源域名、修复原因、已试检索词。失败波次的原因按单元格传入；高风险单元格和冲突修复设置 `counter_required`。
- Worker 新增 `repair_queries.py`：冲突使用 `research.counter.queries`；其他原因使用 `research.replan.queries`。冲突查询针对限制条件、版本差异、反例和勘误；收益耗尽时放宽、收窄或切换语言；获取或服务商失败时改写查询并转向官方文档、技术报告等来源。与已试查询重复的查询会被拒绝，生成失败时退回按原因划分的规则并记录降级原因。
- `counter_checks.py` 用 `research.counter.facts` 拆分完整结论，再用 `research.counter.verify` 逐条判定。每条事实单独检索、读取及归档；判定上下文只包含该事实和新片段，过滤原始证据窗口、原引文和已排除来源。`REFUTED` 和 `NOT_REFUTED` 必须引用本次片段的逐字原文；无相关结果记 `NO_COUNTER_EVIDENCE_FOUND`，模型不可用、结构不合规或预算不足记 `NOT_RUN`。
- 每个任务最多执行 6 条原子事实的隔离反证，无法完整覆盖的结论记 `NOT_RUN`。每个新增 purpose 都有结构校验，不合规时带错误重试一次。反证任务预留最多 32 次模型调用、每种检索工具 21 次调用（包含三次租约恢复上限）、12 张证据卡；普通深挖仍预留 4 次模型调用，Worker 按服务端快照限制实际调用上限。
- 查询历史与反证通过完成信封 v2 的可选 `extraction_diagnostics.execution_report` 上报，参与摘要和同一次完成事务；保存实际执行的查询，包括零结果查询。完成重放不会重复写入查询、反证或审计计数。沿用现有归档重放机制，恢复时不把未执行的查询登记为新查询。
- 迁移 `V140__research_repair_counter_checks.sql` 新增 `research_task_query`、`research_counter_check`，扩展 Replan 审计的 `repair_cause / strategy / query_count`，新增 `research_counter_eligible_cell` 视图。视图绑定当前值、当前版本和最后一次合并业务键，旧候选的反证不能用于新候选。
- 合并时拒绝缺失反证、`NOT_RUN` 或候选自身被反驳的结论；冲突双方存在未执行的反证时也拒绝修复候选。高风险双槽合并检查已持久化槽位的反证。完成判定、正式报告、部分报告、证据审计、合成和集合晋升统一使用反证资格门控，报告逐条展示原子事实、判定和实际查询数。
- 新增 `GET /research-runs/{runId}/replans` 和 `GET /research-runs/{runId}/counter-checks`，字段遵循接口约定，使用现有 `ApiResponse`；校验 Run 属于请求中的工作区。没有新增配置开关，该阶段行为随 Agent 模式生效。
- 删除深挖修复对原研究查询的重复使用，改为按原因规划；旧单进程循环的模板和反证规则仍按任务书留待 1F 修改。
- 验证：Worker 703 项通过；Backend 分组验证全部 251 个测试类、1229 项，1218 项通过、11 项沿用既有跳过条件；Frontend 308 项通过，`tsc -b` 和 `ui:check` 通过。新增测试覆盖事实隔离、不可用模型的 `NOT_RUN`、实际查询保存、事务回滚与重放、审计查询数、跨工作区校验以及当前候选的成文资格。实现提交 `b1851f0` 已快进合入集成分支，环境处理细节见阶段交接记录。
- 尚未使用真实模型、真实外部检索服务跑完整研究，也未进行浏览器联调或 MySQL 实例迁移验证。Flyway 迁移已在 H2 的 MySQL 模式验证。

### 1D（2026-10-12 完成）

- Worker 使用 NFKC、数字和单位规范化比较结论，保持结论原文不变。词项集合 Jaccard 相似度低于 0.2 时分开，高于等于 0.9 时合并，中间区间调用 `research.claims.equivalent`；数量或极性不同的结论不直接合并。每个新成员与簇内全部成员比较，避免经由相似度链条合并不同事实。模型不可用时保留独立候选簇。
- 来源按注册域名、文件血缘和引文 SimHash 合并。注册域名使用包含私有后缀的 PSL：Worker 使用 tldextract，Java 使用 Guava，均只读依赖自带快照。引文按 NFKC、小写、词频和 SHA-256 的前 64 位计算 SimHash，至少 4 个词项且 Hamming 距离不超过 3 时视为转载；相同引文也合并。每组采用最高支撑分，独立组使用 `1 - Π(1 - p_i)`，置信度上限为 950000。
- 保持每格一个合并候选的完成协议。候选增加可选 `equivalent_claims`，逐条绑定证据标识、原始结论和来源域名，代表值必须是一条证据的原结论。完成信封 v2 的 `extraction_diagnostics.claim_clusters` 保留全部候选簇；旧字段和旧信封摘要保持兼容。Java 重新计算来源独立性及置信度，每条证据仍做逐字引文、类型事实和来源资格校验。高风险聚合修复必须有两个独立来源。
- `V141__research_semantic_claim_clusters.sql` 新增候选簇、语义检查表，增加候选等价证据和转载指纹、单元格语义版本与状态。语义审计只读取当前计划、实体集合、单元格版本下的簇；合并后进一步限定到最后一次接受的候选任务及其双槽组，旧修复任务的簇不参与新候选审计。
- EVIDENCE_AUDIT 使用 `research-evidence-audit-input.v2` 与 `research-evidence-audit-result.v2`，对同格所有簇两两执行等价和 `research.audit.contradiction` 判定。输出不合规时带错误信息重试一次，调用次数计入预算。Java 校验完整配对、冻结的单元格与语义版本、证据资格和总体状态。矛盾写成 `SEMANTIC_CONTRADICTION` 冲突进入现有定向反证修复；未执行、判断失败或等价与矛盾结果互相冲突时记 `NOT_RUN`，禁止正式成文。审计可在候选尚未合并时执行。
- 双槽结论措辞不同且全部经审计判为等价时，只对当前簇中的证据批准来源复用，记录 `SEMANTIC_EQUIVALENCE_APPROVED` 并生成聚合修复任务。审计不直接改写单元格值；新任务仍通过原子完成、独立来源和反证门控。新的成功审计可以关闭旧语义冲突，类型事实判定不会自行关闭或重新打开语义冲突。
- 正式报告、部分报告、合成和集合晋升统一使用 `research_formal_eligible_cell`，同时满足 1C 反证和本阶段语义资格。`GET /research-runs/{runId}/cells` 返回约定的单元格、等价证据、独立来源数、置信度及冲突，使用 `ApiResponse` 并校验工作区归属。
- `evidence-audit-v1` 和 Worker 的 `research_agent_evidence_audit_enabled` 默认打开，Compose 同步为 true，保留已有 Run 的冻结开关。普通深挖预留 34 次模型调用，高风险及反证任务预留 64 次；审计按每对最多 4 次调用预留，每任务最多 80 次。批量审计按配对数量分批，预算耗尽的配对保留 `NOT_RUN`。没有修改前端源码或旧单进程循环。
- 验证：Worker 722 项通过；Backend 全部 260 个测试类、1279 项已核对，1268 项通过、11 项沿用既有跳过条件；Frontend 308 项通过，`tsc -b` 和 `ui:check` 通过。新增测试覆盖规范化与聚类、转载与独立来源置信度、跨语言摘要和 SimHash、等价证据提交与重放、当前任务及版本范围、语义配对完整性与过期结果、原子审计的 `NOT_RUN` 门控、语义冲突保持、双槽等价后的来源复用及单元格查询的工作区边界。实现提交 `ab8b74b` 已快进合入集成分支；测试环境处理见阶段交接记录。
- 尚未使用真实模型、真实外部检索服务或浏览器跑完整研究，也未在 MySQL 实例执行迁移；迁移已通过 H2 的 MySQL 模式验证。

### 1E（2026-10-12 完成）

- 宽搜索使用冻结的 `research-discovery-input.v2`，包含当前实体、列定义、通过正式资格门控的单元格摘要、已执行查询、已有提案和权威域名。每轮先用 `research.discovery.queries` 规划 1 到 3 条查询，再执行工作区冻结窗口检索或已有外部检索适配器，最后调用 `research.discover` 提出 ENTITY、DIMENSION、SEARCH_QUERY。两个 purpose 各有结构校验与一次修复重试，最多 4 次模型调用。检索摘要与提案分别按规范化 JSON 的实际字节计数限制为 96000 字节，为完成信封保留空间，避免长中文摘要或大量引用超出 262144 字节上限。
- `research-discovery-proposal.v2` 保存实际查询、检索摘要、逐字支撑片段和降级原因。Java 校验资料的工作区、资料版本、窗口、标题与原文；外部摘要校验 HTTP 地址、数值私有地址、冻结的外部搜索许可与域名白名单。Java 根据真实地址或资料版本重新派生来源身份，不信任 Worker 提供的来源域名、血缘或置信度。检索摘要只支持范围发现，不能直接作为正式证据。
- 来源按 1D 的注册域名、血缘及引文 SimHash 合并，令独立来源数为 n、命中的不同权威域名数为 a，权重 w = n + min(n, a)，提案置信度为 floor(1000000 × w / (w + 1))，上限 950000。结果接口的 supporting_domain_count 返回 n。自动接受开关、运行时及冻结精确率门控、800000 分数门槛和只接受实体或维度的规则保持不变。
- 模型不可用或两次输出均不合规时，查询退回研究问题与当前列标签；发现阶段只从实际检索摘要提出 SEARCH_QUERY。没有可用检索片段时返回空提案，保留降级原因。删除从必需发现项和资料标题直接生成固定分数提案的逻辑。
- `ResearchDiscoveryLoopService` 将 EXPAND_ROWS、POPULATE_CELLS 和 STOPPED 持久化。先宽搜索，再填当前表格中尚未尝试的缺口；反证、语义审计与有界修复优先。新任务是否重复尝试按计划、实体集合、单元格版本和任务绑定判断。已有任务未完成时保持等待；重启后恢复同一轮，完成重放不重复保存摘要、提案或轮次计数。
- 两轮连续没有新增且有检索支撑的提案时判为发现饱和，重复键、重复标签和表格中已有项目不算收益；SHADOW 提案也可以提供发现收益。最多 3 轮宽搜索。循环开始时冻结 4096 次模型调用与 256 次检索的总预算，计算已消耗计数和未结算预留。任务化、修复及最终预算预留都检查上限，高风险双槽一起检查；直接调用预算服务同样持有 Run 锁，余额使用锁定当前读，避免 MySQL REPEATABLE_READ 的早期快照漏记并发预留；已结算后释放的预算可以用于剩余单元格。预算不足时保存 LOOP_BUDGET_EXHAUSTED，仍由现有完成门控生成诚实的部分结果，保留 1C、1D 正式成文资格。
- 每个宽搜索任务预留 4 次模型调用、9 次检索工具调用；每次执行最多 3 条查询，9 次预留包含三次租约恢复上限，沿用按实际调用结算的规则。语义审计预算不足时保留 NOT_RUN，不把未执行的判定当作通过；合成预算不足时可使用 Java 的既有报告渲染。
- 接受实体和维度时补齐整张表格的交叉单元格，写入新列定义并保留原列的高风险要求；没有风险判定字段的新维度按高风险处理。扩表继续最多 80 格，并与 SCOPE_EXPANDED 的 Replan 审计同事务写入。新版任务不能用旧提案协议绕过实际检索校验；历史 v1 提交路径保持兼容。
- 迁移 `V142__research_discovery_loop.sql` 新增检索摘要、循环状态、轮次与实际查询，扩展提案支撑计数及引用。新增 `GET /research-runs/{runId}/discovery-proposals`，遵循六个约定字段，使用 ApiResponse 并检查工作区归属。Backend、Worker、Compose 和环境变量示例默认打开宽搜索，已有 Run 的冻结开关保持不变。
- 删除成文前只执行一次宽搜索的旧调度，主循环直接负责发现和填格。未修改前端源码；旧单进程循环仍按任务书留到 1F。同步集成分支时保留了 A 线 F9 到 F11、C 线 2C 与 2D 的改动和协调记录。
- 验证：Worker 730 项通过；Backend 全部 265 个测试类、1312 项已核对，1301 项通过、11 项沿用既有跳过条件，全部报告没有失败、错误、遗漏或陈旧结果；Frontend 320 项通过，`tsc -b` 和 `ui:check` 通过。新增回归覆盖真实工作区与外部适配器查询、查询预算、两个 purpose 的重试、伪造引文降级、来源与权威域名评分、长中文完成信封、服务端重算分数、冻结资料范围、原子重放及回滚、发现与填表交替、饱和与轮次上限、预算释放后的继续调度、并发预算与整数溢出边界、稀疏表格扩展上限及查询字段。默认启动测试改为验证规划后先宽搜索；提案接口遵循 Controller 只调用应用服务的架构约束。
- 测试环境：Worker 使用临时启动器避开本机 WMI 平台元信息查询；Backend 在独立 JVM 中分组全量验证，修复及补充检查按受影响模块复跑，并核对全部 XML 报告。临时脚本、低内存参数和本地日志未进入生产配置。
- 尚未使用真实模型、真实外部搜索服务或浏览器跑完整研究，也未在 MySQL 实例执行迁移；数据库回归使用 H2 的 MySQL 模式。

- 本阶段实现提交 `652378d` 已从基线 `12fd3fd` 快进合入集成分支；完整交接见协作进度看板。


### 1F（2026-10-12 完成）

- 实现提交：`45dc471`，已合入本地 `feat/upgrade-integration`。
- 旧循环的初始规划调用 `research.plan.matrix`，复用 1B 的表格结构校验；不合规时带错误修复一次。研究类型优先采用规划结果，列保留描述、检索意图和高风险标记。模型不可用时使用规则；关键词规则至少得 2 分且有唯一最高类型才采用专用表格，低分或类型并列时使用 `GENERIC`，不再默认产品对比，删除产品名称关键词。
- `planner.py` 删除全部 `::` 查询模板与必需发现项模板生成函数，改用 1A 的 `plan_cell_queries`。`loop_runtime.py` 的恢复查询复用 1C 的 `plan_repair_queries`，传入实际查询历史、目标列及同一冲突单元格的双方结论，按冲突、获取失败、服务商失败、收益耗尽选择策略。重做抽取直接复用原文，不生成新查询。审计记录策略、方法、实际已试查询、计划查询及降级原因。
- `legacy_counter_runtime.py` 复用 `atomic_queries` 与 `judge_fact`，对高风险、冲突和用户要求反证的结论执行独立检索、读取与判定；过滤原始来源、原引文、获取失败及提示注入窗口。每条事实只向模型提供本次的新原文，逐字引用和支持或反驳判定均通过校验后才算反证完成；结果保留 `REFUTED / NOT_REFUTED / NO_COUNTER_EVIDENCE_FOUND / NOT_RUN`。相同实体、列和结论复用一次隔离验证，不按来源重复检索。
- 删除 `verifier.py` 依据章节名、检索角度、读取提示或分支存在就通过反证的条件。原子事实未完整判定、没有相关新原文、被反驳的结论不能进入正式成文；报告逐条列出事实、查询、判定与引文。`NO_COUNTER_EVIDENCE_FOUND` 如实记录，因缺少支持或反驳判定，不计反证通过。旧检查点的 PASS 重新校验，不能保留模板产生的资格。
- 独立反证每轮最多处理 6 个不同结论，每个结论最多 6 条原子事实；整次运行默认最多 6 次新增检索，耗尽后为 `NOT_RUN`。查询历史、反证预算、逐条结果和新原文随计划与检查点保存；最终结果也返回结构化查询和实际查询。模型调用仍受旧循环客户端已有总调用上限约束，并进入已有调用成本账本。线上角色预算与完成协议未增加调用，本阶段没有新的 HTTP 接口、配置开关或迁移。
- `search.py` 来源相关度改为标题、摘要、样本文本的 BM25F，权重为 2.0、1.5、1.0，结合词频、逆文档频率和字段长度，按 `s / (s + 1)` 归一化，再乘来源质量画像。无相关词项时得分为 0；适配器保留质量画像，中文内容长度参与质量判断。读取窗口优先使用查询的结构化用途；保留旧检查点的读取兼容，不再生成旧模板。
- 重复计划摘要增加查询用途字段，排除查询历史、降级记录和反证计数，防止运行记账不断变化导致重复计划保护失效。查询预算为 0 时不发查询，也不生成种子命中。
- 验证：Worker 758 项通过（新增 28 项回归）；Backend 全部 265 个测试类、1312 项已核对，1301 项通过、11 项既有跳过，无失败、错误、遗漏或陈旧报告；Frontend 320 项通过，`tsc -b` 与 `ui:check` 通过。评测脚本 17 项测试通过，20 题数据集试运行清单完整。旧测试改为检查结构化用途与真实判定；抽取夹具显式指定研究类型，预算耗尽断言按列数计算。
- 模块一真实质量对比尚未运行：本机没有评测账号环境变量，评测接口未监听。`experiments/deep-research/upgrade-1f/evaluation-readiness.json` 保存冻结数据集摘要、20 题清单、运行条件及 `NOT_RUN` 原因，不填模拟质量分数。旧基线使用不同数据集，仍需在同一冻结数据集上补跑改造前后两组。真实模型、真实外部检索与完整服务栈的验证继续待补。

### 搜索代理 MCP 接入（2026-10-14，Claude）

- 背景：Serper 直连账户额度用尽，Jina 与 Wikipedia 在本机容器内无法直连，E1 冒烟中深挖单元全部为 `NO_SUPPORTED_CANDIDATE`。用户提供了搜索代理 MCP 服务（Streamable HTTP，Bearer 鉴权），可调用 Serper、Tavily、Exa；按用户决定只用 Serper 与 Tavily。
- 新增 `app/mcp_search_client.py`：只用标准库，经 `credential_safe_urlopen` 发送 JSON-RPC，拒绝重定向；初始化一次并复用会话，会话过期时重新初始化一次。工具返回 `isError` 时按文本判断是否可重试（429、5xx、容量不足）。
- 搜索：链路新增 `serper_mcp`（`search_proxy_serper_search`，支持翻页）与 `tavily_mcp`（`search_proxy_tavily_search`，只取第一页），结果字段沿用现有映射。本机配置 `NOTEWEAVE_RESEARCH_SEARCH_PROVIDER_CHAIN=serper_mcp,tavily_mcp`。
- 正文读取：配置了 MCP 地址与密钥时，`TavilyMcpUrlSnapshotTransport`（`search_proxy_tavily_extract`）排在 Jina 和直接抓取之前。目标地址仍先做公网校验，正文按查询挑选相关段落，状态记为 `FETCHED`、方法 `TAVILY_EXTRACT`；失败时回退到后续读取方式或搜索摘要。
- 配置：`NOTEWEAVE_RESEARCH_MCP_SEARCH_URL`、`NOTEWEAVE_RESEARCH_MCP_SEARCH_API_KEY`，Compose 透传，`.env.example` 留空。未配置时行为不变。
- 验证：新增 9 项测试（协议与会话、可重试错误、两种搜索的参数与翻页、链路构造、正文读取成功、失败回退、私网地址拦截），Research Worker 全部 767 项通过；真实服务上 Serper、Tavily 各返回 5 条结果，Tavily Extract 读到 MySQL 官方文档正文。Exa 测试时返回 503 容量不足，未接入。

### 搜索渠道全部不可用时立即失败（2026-10-14，Claude）

- 背景：E1 冒烟时搜索代理额度用尽，每次调用返回 HTTP 402。搜索静默返回空结果，研究继续跑约 40 分钟，最终给出证据不足的报告，掩盖了真实原因。
- 搜索渠道把额度用尽（402、insufficient quota、Not enough credits 等）和鉴权失败（401、403）记为不可恢复的失败，限流、超时、容量不足仍按可重试处理。某个渠道出现不可恢复的失败后，本次搜索不再向它发送后续查询。
- 组合搜索在没有任何结果、且所有外部渠道都遇到不可恢复的失败时抛出 `RESEARCH_WEB_PROVIDER_UNAVAILABLE`。Worker 按原有路径上报投递失败，后端已有的处理会让整次研究以该原因立即失败。只要有一个渠道可用或已有结果，研究照常继续。
- 新增 12 项测试，Research Worker 全部 779 项通过。
