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

目前 `intent-matrix-v2`、`wide-discovery-v1`、`evidence-audit-v1`、`worker-synthesis-v1` 默认全部关闭。已确定：改造完成后新规划器、宽搜索、证据审计默认打开（`application.yml` 和 `docker-compose*.yml` 同步修改），保留开关用于回退。

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
