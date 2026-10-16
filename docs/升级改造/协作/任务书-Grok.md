# 任务书：C 线（Grok）检索与记忆

你负责 NoteWeave 升级改造中检索链路（QA、Note、Wiki）与 Memory、上下文的后端实现。另外两条工作线：A 线（Claude）负责规划、前端和设计文档；B 线（GPT）负责 Research Agent、产物生成框架和资料处理流水线。

## 1. 开始前必读

1. 仓库根目录 `AGENTS.md`：回复、注释、文档、提交信息一律使用简体中文；文中不使用中文弯引号；避免翻译腔和生造词。
2. `docs/升级改造/协作/分工总览.md`：目录归属、迁移版本号范围（你使用 V160 到 V179）、分支与合并、完成标准、测试命令。
3. `docs/升级改造/协作/接口约定.md`：你要实现的 HTTP 接口、字段，以及给 B 线使用的内部接口 `ScopedPassageSearch`。
4. 设计文档：
   - `docs/升级改造/03-检索链路改造设计.md`：全文，含第 6 节修订（其中 S6 片段上下文增强由 B 线在流水线中实现，你不需要做）。
   - `docs/升级改造/02-Memory与上下文改造设计.md`：全文，含第 6 节修订。
   - `docs/升级改造/00-业界方案调研.md`：第二、三部分。

## 2. 相关代码位置

以下位置在改造设计调研时已经确认，路径前缀为 `backend/src/main/java/com/noteweave/`。

- 多轮查询拼接：`chat/ConversationRetrievalContextAssembler.java`（`assemble` 与 `assembleV2` 把整段对话拼成多行文本），由 `chat/ChatService.java` 约第 258 行传给三种模式。
- QA 混合检索：`chat/QaHybridRetriever.java`、`retrieval/qa/QaRrfFusionService.java`、`retrieval/qa/QaRerankService.java`、`retrieval/QaRetrievalStrategyProfile.java`、`chat/QaQueryExpansionService.java`、`chat/QaPassageRetriever.java`。
- Note 链路：`chat/NoteRecallRetriever.java`（五路信号直接相加、全量加载资料）、`chat/NoteRecallRanker.java`（降级时的手调字段权重）、`chat/NoteReadingPlanner.java`、`chat/NoteReadingRetriever.java`（子串得分加余弦乘 100）。
- Wiki 链路：`knowledge/KnowledgeWikiSearchEngine.java`（每次加载最多 5000 页全文在内存算 BM25F）、`chat/WikiGraphBudgeter.java`（只有一跳）、`chat/WikiEvidenceRetriever.java`。
- Memory：`memory/MemoryCandidateService.java`（冲突只看正负极性、全表扫描）、`memory/MemoryCandidatePolicy.java`（固定查表打分）、`memory/MemoryConversationExtractionService.java`（正则初筛、重复信号直接跳过）、`memory/MemoryCompilerService.java` 与 `MemoryCompilerPolicy.java`（注入与问题无关）、`memory/MemoryOutcomeService.java` 与 `MemoryOutcomePolicy.java`（效果反馈接口没有调用方）。
- 上下文：`conversation/TopicSegmenterV2.java`（只用词项 TF-IDF 和句首正则）、`conversation/ContextWindowPlannerV2.java`。
- 已有的检索评测设施：`retrieval/eval/**`（标注、回放、质量门禁），评测时复用。

## 3. 阶段任务

按顺序完成。每个阶段完成后：全量测试通过、提交、更新进度看板与模块文档的实施记录。

### 3A 查询改写（03 号文档 S1 与第 6 节修订第 1 条）——优先

- 新增 `QueryRewriteService`：有对话上下文时调用模型，输出 `is_standalone`、`standalone_query`、`keyword_query`、`alternatives`、`question_type`、`must_terms`、`low_level_keywords`、`high_level_keywords`、`language`；首轮问题跳过改写。
- 向量通道用独立问题，BM25 用最后一轮原文与改写的拼接并对 `must_terms` 加权，召回不足时用 `alternatives` 多查询召回并 RRF；Rerank 用独立问题。对话上下文只传给生成阶段。
- 改写结果写进 `RetrievalPlan.filters`，并按接口约定在回答记录中返回 `retrieval_rewrite`。
- 模型不可用时：独立问题取当前问题原文，关键词查询取当前问题加话题锚点关键词，不再拼接整段对话。
- 提供接口约定中的 `ScopedPassageSearch` 实现，B 线 4B 会调用。

### 3B BM25F 公共组件与 Note 链路（03 号文档 S2、S3）

- 把 `KnowledgeWikiSearchEngine.bm25f` 及其切词规则抽成 `retrieval/lexical/Bm25fScorer`，Note 降级打分、Wiki 降级检索、记忆匹配共用。
- Note 资料召回五路信号改为加权 RRF，权重写进策略档案并用评测集校准；窗口就绪度改为过滤条件；阅读窗口用 BM25 与向量两路 RRF 后再 Rerank；候选集合只取各通道召回结果的并集。

### 2A 到 2E Memory 与上下文（02 号文档全文与第 6 节修订）

- 2A：抽取输出增加 `slot`、`value`、`polarity`；`memory_item` 增加 `slot_key` 并回填；冲突两段式（槽位精确判定，其余取最相似 5 条由模型选择 `ADD / MERGE / SUPERSEDE / NOOP`）；`memory_runtime_revision` 增加 `valid_from`、`invalid_at`，替代只设失效时间；候选匹配改为 SQL 过滤加 ES 向量检索（新建 `noteweave-memory` 索引，写入器放在你自己的目录，复用 embedding 客户端）。`GET /memory/review` 返回接口约定中的冲突字段。
- 2B：Beta 后验评分（先验按来源类型，等价信号强化、反驳信号削弱，同一会话只计一次）；风险分由类别、作用域、冲突情况组成；阈值移到配置；删除正则初筛，改为两段式（短提示二分类加完整抽取），新增隐式反馈抽取。
- 2C：记忆分常驻类与按需类；`compileChatControlPack` 等增加 `query`；价值等于置信度乘效用乘相关度乘新近度衰减，按价值与 token 之比贪心选取；编译 trace 记录入选原因。
- 2D：后端部分：`POST /answer-runs/{runId}/feedback`、提交回答时的 `regenerated_from`、产物编辑时的编辑比例、下一轮消息中的不满或正常追问，都写入 `memory_application_outcome`；负面反馈先判断针对的方面再映射到槽位。前端按钮由 A 线做。
- 2E：话题切分以改写后的独立问题计算向量，相邻消息相似度加 TextTiling 深度分数找边界，显式切换规则保留为强信号；不确定区间异步调用模型复核；向量不可用时退回 TF-IDF；用 Pk 和 WindowDiff 评测。

### 3C、3D、3·S7 Wiki 与证据充分性（03 号文档 S4、S5、S7）

- 3C：`noteweave-wiki` 索引（标题、摘要、正文、页面向量、计数字段），页面改版经 outbox 写入；`multi_match` 与 kNN 两路 RRF，加权威度加成后 Rerank；内存 BM25F 只作降级且最多加载 500 页；去掉每行 4 个关联子查询。
- 3D：个性化 PageRank 多跳扩展（重启概率 0.3、最多 20 次迭代、最多 3 跳、邻接按需加载），扩展分数除以 `log(1 + 入度)`；按三项预算贪心选取；证据项返回 `graph_path`；`graph_hops_used` 等改为真实统计。
- 3·S7：生成前判断证据是否足以回答，结合 Rerank 分数决定回答、部分回答或说明资料不足；回答记录返回 `evidence_sufficiency`；模型不可用时为 `NOT_RUN`，按原流程回答。

## 4. 约束

- 只修改你归属的目录；ES 投影写入器 `retrieval/projection/**`、`retrieval/index/**` 归 B 线，你新增的索引写入器放在自己的目录。需要改其他目录时在进度看板协调区提出。
- 新增 HTTP 接口先对照接口约定；需要改动约定时先改文档并在协调区说明。
- LLM 调用的降级原则、默认打开、直接删除被替代代码，见分工总览第 3 节。
- 每个新增的 LLM 调用：输出做结构校验，不合规时带着错误信息重试一次。Java 端现有的模型客户端是 `chat/ChatLlmClient`（流式接口），需要 JSON 输出时可以参照 `memory/MemoryConversationExtractionService` 的用法。
- 在 Windows 的 Bash 工具里写文件时，反斜杠转义可能被吃掉或转成真实字符；写完检查生成的文件。

## 5. 不需要等待其他人

- 你的全部阶段都可以独立推进。合并按分工总览第 4 节由你自己完成，协调区的问题按进度看板中的规则处理，不需要等待 A 线确认。
- 后端阶段全部完成后，如果 A 线还没有做对应的前端任务，可以认领 `任务书-Claude.md` 第 2 节中与你的阶段对应的前端编号，规则见该文件第 4 节。

## 6. 交付

每个阶段完成后在 `docs/升级改造/协作/进度看板.md` 更新状态并追加交接记录，在对应模块文档末尾的实施记录追加本阶段记录，然后提交。3A 完成后在协调区通知 B 线 `ScopedPassageSearch` 已可用。遇到设计与代码现状不一致、需要做取舍时，先在协调区写明你的判断和理由再继续。
