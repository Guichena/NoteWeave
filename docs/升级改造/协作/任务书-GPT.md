# 任务书：B 线（GPT）智能体与资料管线

你负责 NoteWeave 升级改造中 Research Agent 剩余阶段、产物生成框架、资料处理流水线的后端与 Worker 实现。另外两条工作线：A 线（Claude）负责规划、前端和设计文档；C 线（Grok）负责检索链路与 Memory。

## 1. 开始前必读

1. 仓库根目录 `AGENTS.md`：回复、注释、文档、提交信息一律使用简体中文；文中不使用中文弯引号；避免翻译腔和生造词。
2. `docs/升级改造/协作/分工总览.md`：目录归属、迁移版本号范围（你使用 V140 到 V159）、分支与合并、完成标准、测试命令。
3. `docs/升级改造/协作/接口约定.md`：你要实现的 HTTP 接口和字段，字段名不要自行改动。
4. 设计文档：
   - `docs/升级改造/01-DeepResearch改造设计.md`：第 4 节 S4 到 S8、第 7 节修订、第 8 节 1A 和 1B 的实施记录。
   - `docs/升级改造/04-产物生成框架改造设计.md`：全文，含第 6 节修订。
   - `docs/升级改造/05-资料处理流水线改造设计.md`：全文，含第 6 节修订。
   - `docs/升级改造/03-检索链路改造设计.md`：只看第 6 节的 S6 片段上下文增强，它在你的 5B 中实现。
   - `docs/升级改造/00-业界方案调研.md`：第一、四、五部分。

## 2. 当前代码状态（1A、1B 已完成）

- 线上 Research 走 Agent 模式：Worker 入口 `workers/research-worker/app/agent_kafka_consumer.py`，按角色分派给 `PlanningExecutor`、`DeepCellExecutor`（DEEP_CELL 与 COUNTERFACTUAL）、`DiscoveryExecutor`、`EvidenceAuditExecutor`、`SynthesisExecutor`。`runner.py` 和 `loop_runtime.py` 的单进程循环只被 benchmark 和冒烟脚本使用，属于 1F 的范围。
- `app/cell_query_planner.py`：单元格检索词规划（`research.cell.queries`），输出 `ResearchQuerySpec`；查询类别、检索角度、检索通道是结构化字段，`app/query_specs.py` 提供查找和替换。
- `app/planning_executor.py` 与 Java `ResearchPlanningService`：PLANNING 角色由模型规划表格，Java 校验后写入 `research_row`、`research_cell`、`research_column`，规划记录的 `plan_mode = 'LLM_MATRIX'`。
- Java `ResearchAgentRoles` 统一判断不绑定单元格的角色和以 ROLE_RESULT 提交的角色，新增角色时在这里登记。
- `ResearchAgentTaskCoordinatorService.withCellScope` 把实体名、列定义（`column_specs`）、研究目标、排除项、权威域名、投入等级放进深挖任务的 `query_policy`。
- LLM 调用预算：Worker 上报的是调用次数（不是重试次数），Java 按任务预留并在完成时结算，超出会拒绝完成。深挖任务目前预留 4 次（检索词规划 2 次加抽取 2 次）。新增调用时必须同步调整 `ResearchAgentTaskCoordinatorService.deepCellBudget` 或对应角色的预算，以及 Worker `role_executor.py` 中角色的 `max_llm_calls`。
- PLANNING 任务不计入 Coordinator 快照和完成判定；新增类似的管理型任务时参照这个处理。

## 3. 阶段任务

按顺序完成。每个阶段完成后：全量测试通过、提交、更新进度看板与模块文档的实施记录。

### 1C 定向反证与按原因改写的 Replan（01 号文档 S5 与第 7 节修订第 8 条）

- Java 生成修复任务时在 `query_policy` 中加入 `repair_target`：单元格、列定义、冲突双方的结论原文与来源域名、修复原因、已经尝试过的检索词。已尝试的检索词需要持久化：Worker 在完成信封中上报本次执行的检索词，Java 按任务保存（新增表或扩展现有表，迁移版本号在你的范围内）。
- Worker 按修复原因选择策略：
  - `EVIDENCE_CONFLICT`：调用 `research.counter.queries` 生成反证查询；先把待验证结论拆成原子事实，每条事实隔离地检索和判定（判定时不把原结论的证据放进上下文），结果为 `REFUTED / NOT_REFUTED / NO_COUNTER_EVIDENCE_FOUND`，模型不可用时为 `NOT_RUN`；
  - `MARGINAL_GAIN_EXHAUSTED`：根据已试查询放宽、收窄或切换语言；
  - `FETCH_FAILED / PROVIDER_FAILED`：换检索词和来源类型。
- Replan 审计记录策略与查询条数；反证结果持久化。实现接口约定中的 `GET /research-runs/{runId}/replans` 和 `GET /research-runs/{runId}/counter-checks`。
- 验收：冲突单元格的修复任务生成的查询与原查询不同且针对冲突结论；没有执行反证的结论不能进入正式结论；有对应测试。

### 1D 候选值语义聚合与语义冲突（01 号文档 S6、S7）

- Worker 对同一单元格的结论聚类：规范化后按词项集合相似度聚类，中间区间调用 `research.claims.equivalent` 判断；候选值附 `equivalent_claims`。
- 置信度按独立来源合并：注册域名去重，证据原文 SimHash 合并转载内容，再用 `1 - Π(1 - p_i)` 并设上限。
- Java 资格校验接受 `equivalent_claims` 中的证据，每条证据仍单独做引文校验，使高风险列的双来源要求可以满足。
- EVIDENCE_AUDIT 增加 `research.audit.contradiction`，同一单元格有多个候选簇时两两判断，矛盾写成 `SEMANTIC_CONTRADICTION` 冲突交给修复流程；模型不可用时记 `NOT_RUN`，该单元格不允许进入成文。
- `evidence-audit-v1` 改为默认打开。实现 `GET /research-runs/{runId}/cells`。

### 1E 真实的宽搜索与显式主循环（01 号文档 S4 与第 7 节修订第 1 条）

- WIDE_DISCOVERY 分配 1 到 3 次检索，调用 `research.discover` 提出 ENTITY、DIMENSION、SEARCH_QUERY；置信度按支撑的独立域名数和是否命中权威域名计算，删除写死的常数。
- Coordinator 每轮按表状态在 `EXPAND_ROWS` 和 `POPULATE_CELLS` 之间选择，结束条件为饱和判定加预算上限。
- `wide-discovery-v1` 默认打开，接受门控保持现有逻辑。实现 `GET /research-runs/{runId}/discovery-proposals`。

### 1F 旧循环链路修正（01 号文档 S8）

- 删除 `verifier.py` 中按报告章节名和检索词角度直接判反证通过的条件。
- `loop_runtime.py` 的 Replan 改用 1A 和 1C 的检索词规划，删除 `recovery round N` 等模板；`planner.py` 的 `query_set` 不再拼接 `::` 模板。
- 研究类型优先使用规划结果，退回关键词规则时置信度低则用通用表格结构；`search.py` 的来源相关度改为 BM25F 加来源质量画像。

### 4A 到 4E 产物生成框架（04 号文档全文与第 6 节修订）

- 4A：声明式输出契约与规则插件（规则含 `level` 和 `backtrack_to`），`artifact.compile_intent` 意图编译。
- 4B：`POST /internal/worker/artifact-tasks/{taskId}/retrieve`，调用 C 线提供的 `ScopedPassageSearch`（见接口约定）；Worker 新增 `RETRIEVE` 节点，按大纲章节检索并分配预算。
- 4C：节点执行器（`LLM_STEP / RETRIEVE / VERIFY / REPAIR / TOOL`），重写简历亮点图；删除 `skill_graph.py` 中全部模板生成函数、写死的定位句和影响描述，以及按数量补齐的修复逻辑。
- 4D：以陈述为单位的证据支撑校验，计算引用召回率与精确率；修复循环最多 2 轮；产物版本详情返回接口约定中的 `verification` 字段。
- 4E：按章节生成和独立的 `cite` 节点，学习指南、测验、Wiki 页面迁移到新图。

### 5A 到 5D 资料处理流水线（05 号文档全文与第 6 节修订）

- 5A：新建 `workers/parse-worker`（Docling），输出带元素类型、页码、坐标的文档结构；Java 转成 Markdown 与元素列表交给切片器，表格、图注、公式作为不可拆分块；Java 保留 PDFBox 和纯文本解析作为降级；文本类文件流式读取。断点续传、秒传、格式列表接口见接口约定。
- 5B：向量化按每批 64 个片段拆成多条消息，批次结果落盘并记录状态；向量缓存键为 `(embedding_version, chunker_version, context_prompt_version, sha256(片段文本))`；片段上下文增强（03 号文档 S6）在切片与向量化之间执行，默认打开；Kafka 配置 `max.poll.records=1`、按 P99 设定 `max.poll.interval.ms`、静态组成员。
- 5C：在 30 份样例上对比 Docling 与 MinerU，Docling 未覆盖的版面问题再补处理；结果放进 `experiments/pipeline/`。
- 5D：OCR 兜底（按页判断，记录 `text_origin` 与 `ocr_confidence`）；`workers/artifact-worker/app/source_transcription.py` 的转写改为有界队列加固定线程池。

## 4. 约束

- 只修改你归属的目录；需要改其他目录时在进度看板的协调区提出。
- 新增 HTTP 接口先对照接口约定；需要改动约定时先改文档并在协调区说明。
- LLM 调用的降级原则、默认打开、直接删除被替代代码，见分工总览第 3 节。
- 每个新增的 LLM 调用：独立的 `purpose` 名称，输出做结构校验，不合规时带着错误信息重试一次，调用次数计入预算。
- 在 Windows 的 Bash 工具里写文件时，反斜杠转义（换行符转义、Unicode 转义等）可能被吃掉或转成真实字符；需要时在代码中用 `chr()` 拼出，写完用 `od -c` 检查。

## 5. 不需要等待其他人

- 你的全部阶段都可以独立推进。合并按分工总览第 4 节由你自己完成，协调区的问题按进度看板中的规则处理，不需要等待 A 线确认。
- 后端阶段全部完成后，如果 A 线还没有做对应的前端任务，可以认领 `任务书-Claude.md` 第 2 节中与你的阶段对应的前端编号，规则见该文件第 4 节。

## 6. 交付

每个阶段完成后在 `docs/升级改造/协作/进度看板.md` 更新状态并追加交接记录，在对应模块文档末尾的实施记录追加本阶段记录，然后提交。遇到设计与代码现状不一致、需要做取舍时，先在协调区写明你的判断和理由再继续。
