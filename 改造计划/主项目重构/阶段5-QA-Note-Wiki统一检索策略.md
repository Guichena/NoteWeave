# 阶段 5：QA、Note、Wiki 统一检索策略

> 目标：统一三种回答模式的运行契约、证据模型和检索基础设施，同时保留各模式的业务差异。
> 前置：阶段 3 的 Passage/投影，阶段 4 的 AnswerRun。

## 0. 当前施工进度

第一批稳定契约已落地：

- `AnswerMode`：只允许 QA、NOTE、WIKI，未知值返回 `ANSWER_MODE_UNSUPPORTED`；
- `AnswerContext`：统一 Workspace、Conversation、query message、source scope、请求属性与时间；
- `RetrievalPlan`：包含 plan version、channel step、候选数量/权重/过滤器，以及 evidence/字符/graph hop/node/edge/character 预算；
- `EvidenceBundle`：包含稳定 evidence id、Source Snapshot/Passage 或 Knowledge Version 引用、定位、原始/融合/重排分数、访问 scope、freshness、选择原因和字符成本；
- `PromptSpec` 与 `AnswerPolicy`：显式声明引用 evidence id、prompt version、最低证据、拒答/citation 和输出预算；
- `AnswerModeStrategyRegistry`：按枚举唯一注册，重复实现启动失败，未知或缺失策略明确失败；
- Registry 新增 4 项确定性测试；QA、Note、Wiki adapter 已接入真实运行链路；该批次完成时 Backend `153/153` 通过。

QA 迁移第一批已落地：

- QA 首批迁移曾由 `QaAnswerModeStrategy` 输出 `qa-passage-v1` 的 `RetrievalPlan`，并保持原 QA 模板、问题分类、连续对话和 Chat Control 行为不变；该 plan version 仅作为历史记录，新 AnswerRun 统一使用 `qa-passage-v2`；
- `QaPassageEvidenceRetriever` 将原 `retrieveForQa` 结果适配为 Passage `EvidenceBundle`，未更换原检索与排序算法；
- `EvidenceCitationAssembler` 只允许持久化 `PromptSpec.referencedEvidenceIds` 中且确实属于 bundle 的 Passage 证据；
- AnswerRun、message revision 和 retrieval summary 记录真实 plan/prompt version 与 `evidence-bundle:*` 引用；
- 该批次完整 Backend `145/145` 通过，Research Report 来源标题与 citation 回跳契约保持通过。

Note 迁移第一批已落地：

- `NoteAnswerModeStrategy` 输出 `note-marginalia-v1` RetrievalPlan，并保持原候选配额、Journal、关系扩展、验证批次、阅读窗口与可见模板不变；
- `NoteEvidenceRetriever` 仍调用原 `findNoteRecallPlan/readEntriesMetadataForNote/openSourceWindowsForNote`，把最终窗口适配为 Passage evidence；
- 候选、Journal、关系、验证 trace 与元信息形成 bundle 级 Note retrieval snapshot，策略从 snapshot 重放原回答；
- `EvidenceRetrievalResult` 允许 retriever 同时返回 evidence、bundle metadata 与显式降级信息，QA 适配行为不变；
- `ChatService` 已删除旧 Note builder 且不再直接依赖 `RetrievalService`，Note 差异位于可独立测试的 strategy/retriever；
- AnswerRun、revision 和 retrieval summary 记录 `note-marginalia-v1` 与 `evidence-bundle:*` 引用。

Wiki 迁移第一批已落地：

- `WikiAnswerModeStrategy` 输出 `wiki-page-graph-v1` RetrievalPlan，页面版本作为 `KNOWLEDGE_VERSION` evidence；
- `WikiEvidenceRetriever` 保持原 Wiki 页面召回、页面链接、反链和来源回链算法，并携带原 citation IDs；
- 统一 citation assembler 校验 Knowledge Version evidence，但不伪造 Passage citation；页面绑定的原 citation 仍按原顺序复用；
- Wiki 无页面时仍明确拒绝退回普通资料 RAG；
- `ChatService` 已删除 QA/Note/Wiki builder 和 mode switch，只通过 `AnswerModeStrategyRegistry` 运行统一 pipeline；
- 新增架构门禁，禁止 ChatService 重新依赖 RetrievalService、KnowledgeService 或具体 retriever。

Retriever/Hydrator 第一批已落地：

- `RetrievalHydrator` 批量读取 QA Passage ownership/source provenance、Note 阅读窗口和 source 状态统计；
- QA 的 100 个 ES hit ownership hydrate 固定为 1 次 SQL，不再逐 hit 查询；
- Note 的多 source window/state hydrate 固定为 2 次 SQL，不再逐 source 查询；
- Note related-entry 预览改为 workspace 批量快照，80 个 anchor 下 JDBC 查询固定为 4 次，并保持每个 anchor 排除自身后最多 30 个最近候选的旧语义；
- `QaPassageRetriever` 已承接原 ES 优先、MySQL fallback、关键词评分和来源多样性实现；`QaPassageEvidenceRetriever` 不再依赖巨型 `RetrievalService`；
- QA 抽离后原通用 `RetrievalService` 只剩 Note 职责，现已收敛为 `NoteRetrievalService`；旧类型、旧源文件和生产引用均已删除；
- `NoteReadingRetriever` 与 `NoteReadingPlanner` 已独立承接批量窗口读取、关键词评分、主窗口/相邻窗口/次级窗口规划；
- `NoteJournalRetriever` 已独立承接 Journal 命中、freshness 校验和 source signal，Recall orchestration 改为依赖该组件；
- `NoteRelationGraph` 已独立承接 tag/title/co-citation 加权图、共现信号与 restart 传播算法；
- `NoteRecallRanker` 已独立承接候选配额、source type 多样性、relation expansion 与 verify admission；服务内旧选择 helpers 和私有 scored DTO 已删除；
- `NoteRecallRanker` 进一步承接 coverage-aware metadata scoring、字段权重、term weight 与 coverage trace；服务内旧 metadata scoring helpers/DTO 已删除；
- `NoteRecallRepository` 统一承接候选 Source、Note citation group 与 answered-turn citation group 查询，Recall 与 related-entry preview 不复制 SQL；
- `NoteRecallRetriever` 已独立编排 Repository、Journal、RelationGraph、Ranker、fallback 和 trace；`NoteEvidenceRetriever` 直接调用该 Retriever；
- Ranker/Recall 确定性测试固定 metadata 分数、候选顺序、selection reason、relation expansion、verify admission 和 retrieval shape；
- 新增架构门禁，禁止 `NoteRetrievalService` 重新依赖 `NoteRecallRetriever`，并禁止 Retriever 绕过 Repository 直接依赖 JDBC；
- `RetrievalGoldSet` 定义版本化 schema，覆盖 mode、workspace、query、allowed source scope、候选 evidence、相关 evidence、期望 citation、拒答条件与 topK；
- `DeterministicBm25Baseline` 提供固定 `k1=1.2/b=0.75`、`content^3/title^2/source_type` 字段权重、中英文确定性 tokenizer 与 evidence-id tie-break；
- `RetrievalBenchmarkReplay` 提供文件/程序入口，汇总 Recall@K、MRR、nDCG、citation precision/coverage、scope violation 和 refusal accuracy；
- 第一批 `stage5-fixture-20260714` 覆盖 QA、Note、Wiki 和无证据拒答，并包含高相关但越权候选以验证 scope 过滤；
- 该离线 BM25 是与当前 ES 字段权重对齐的确定性代理，不宣称复刻 ES analyzer/fuzziness，也不把小型内部 fixture 写成外部质量证明；
- gold evaluator 已泛化为可评估任意外部排名；`RetrievalShadowSnapshot` 记录 case ranking、latency 和 candidate count；
- `RetrievalShadowComparator` 对照 baseline/online 的 topK overlap、位置一致性、top1 变化、质量/citation/refusal delta、scope violation 与 p50/p95；
- `RetrievalBenchmarkProfiler` 多轮执行同一 replay，验证报告稳定，并输出运行延迟与 case/candidate/token/topK/citation workload；
- 第一批 shadow fixture 故意注入越权、漏召回和错误拒答，用于证明 comparator 能发现质量回退，而不是伪造绿色线上结果；
- `RetrievalSnapshotSanitizer` 使用带盐 HMAC-SHA256 对 case/workspace/evidence/source/citation ID 做稳定假名化，并脱敏 email、URL、UUID、IPv4、手机号、反斜杠或正斜杠形式的 Windows 绝对路径和 token/API key/secret；盐由 `NOTEWEAVE_RETRIEVAL_EXPORT_SALT` 提供；
- 脱敏后的 gold set 保持 ID 关联一致，可继续复用 replay/comparator，并支持 pretty JSON 写出与脱敏计数审计；
- `QaChunkSearchShadowCapture` 通过生产 `ChunkSearchPort.search(workspaceId, query, 12)` 边界捕获 QA 排名、score、latency 和 candidate count，同时写出 QA-only 脱敏 gold 与 `retrieval-shadow-v1`；
- `QaChunkSearchShadowCapture` 的首批自动化测试以 mock `ChunkSearchPort` 固定 Port 调用、脱敏、落盘和 comparator 对接；后续 r5 已使用真实启用的 Elasticsearch 与生产 Port/selector 边界形成 8-case pilot，但该 pilot 仍不是生产质量门禁；
- `RetrievalQualityGatePolicy` 定义 `retrieval-quality-gate-v1`，将 policy version、dataset version、最小 case 数与阈值绑定，禁止跨数据集误用门禁；
- `RetrievalQualityGate` 同时检查绝对 Recall/MRR/nDCG/citation/refusal、相对 baseline delta、topK overlap、top1 变化比例、scope violation 和 p95，并输出稳定的逐指标 violation；
- 门禁 CLI 在失败时输出结构化报告并以失败状态退出，可用于后续 CI/benchmark job；策略 schema 对比例、delta、计数和延迟范围做确定性校验；
- `stage5-quality-gate-policy-v1.json` 只用于受控 fixture 验证通过/失败路径，不代表生产阈值已经校准；
- `SendMessageRequest` 新增可选 `source_scope_source_ids`，缺省仍保持 Workspace 全量资料语义；当前只允许 QA 使用，Note/Wiki 携带时明确返回 `ANSWER_SOURCE_SCOPE_UNSUPPORTED`；
- QA 的显式 Source scope 已贯通 `AnswerContext`、`RetrievalPlan.filters.source_ids`、`QaPassageEvidenceRetriever` 和 `QaPassageRetriever`；
- ES 命中在 provenance hydrate 前过滤 scope；MySQL fallback 将 Source ID 条件下推 SQL，并在内存层二次过滤，避免 fallback 或 mock/adapter 绕过边界；
- 端到端契约固定回答正文和 `message_citation` 只能来自允许 Source，未选择 Source 不得进入 EvidenceBundle 或 citation 持久化；
- `QaGoldAnnotationRequest` 定义 `retrieval-qa-annotation-request-v1`，要求 dataset version、candidate pool、真实 workspace/query、显式 allowed Source 和 topK；
- `QaGoldAnnotationDraft` 已升级为 `retrieval-qa-annotation-draft-v2`，保留真实 Port 的 rank/score、scope 内外标记和候选摘录，但所有标识符与正文在落盘前完成假名化/脱敏；
- `QaGoldAnnotationDraftCapture` 只调用生产 `ChunkSearchPort` 契约，不把 raw hit 写盘；输出固定为 `PENDING`，`shouldRefuse/relevantEvidenceIds/expectedCitationIds` 留空等待人工标注；
- 草稿中的 citation ID 是与候选 evidence 稳定关联的评测标签，不是数据库中已持久化的 citation row；out-of-scope hit 被保留并显式标记，用于标注和后续 scope gate；
- `RetrievalSnapshotSanitizer.RedactionSession` 允许一次 capture 累积假名化与脱敏计数，仍要求至少 16 字符的盐；
- annotation draft v2 为每个 case 保存 latency、raw candidate count 与 `rawInputFingerprint`；fingerprint 对未脱敏 request 身份、Workspace、query、topK、allowed Source 及全部 raw hit 的 chunk/source/snapshot/rank 内容、类型和精确 score 做长度前缀规范化，再使用当前导出盐执行 HMAC-SHA256，仅落盘 `raw-input-*` 假名，不落盘 raw 输入；
- `QaGoldAnnotationCompiler` 使用相同 request/salt 重新调用 Port，先比较 raw HMAC fingerprint，再要求 reviewed draft 与当前脱敏候选逐项一致；候选数、顺序、score、snapshot 或正文发生变化时拒绝编译并要求重新 capture，人工只能修改审核状态、拒答结论、相关 evidence、期望 citation 与 notes；
- compiler 只接受 `REVIEWED` 且已决定 `shouldRefuse` 的 case；非拒答案例必须选择 scope 内 evidence，expected citation 必须精确等于相关 evidence 的 citation labels；
- 通过校验后直接构造 sanitized `retrieval-gold-v1` 与 `retrieval-shadow-v1`，raw gold 不进入返回值或输出文件；当前 capture latency/candidate count 直接写入 shadow；
- Elasticsearch 职责已拆分：`ElasticsearchIndexer` 只负责 Kafka 驱动的写侧投影，`ElasticsearchChunkSearchAdapter` 独立实现 `ChunkSearchPort`，在线 QA 与离线评测 CLI 共用同一读侧边界；
- 新增最小 Spring CLI `RetrievalEvaluationCli`，通过专用 `retrieval-evaluation-cli` Profile 只装配配置属性、Elasticsearch client、读侧 adapter、sanitizer、draft capture 与 compiler，不启动 Web、JDBC、Flyway、Kafka listener 或后台调度；
- CLI 提供 `capture-draft` 与 `compile-reviewed` 两个命令，盐只从 `NOTEWEAVE_RETRIEVAL_EXPORT_SALT` 读取；专用 Profile 防止 CLI 的 ObjectMapper Bean 被主应用 component scan 加载并覆盖在线 snake_case 契约；
- 已对 `localhost:9200` 真实启动 CLI smoke，并成功写出脱敏 `PENDING` draft；所用 fixture workspace 在该 ES 中不存在、候选为空，因此该 smoke 只证明入口、连接、脱敏与落盘链路，不是检索质量或延迟证明；后续只读盘点发现当前 20 个索引均为单文档 Docker/outbox/upload smoke 数据，同样不具备真实 QA gold 标注价值；
- 新增 `scripts/prepare-stage5-qa-annotation.ps1`，通过真实 Workspace/Upload/Parse/Index API 链路导入仓库中的 3 份实际设计/说明文档，并只在 `backend/target` 生成带真实 Workspace、allowed Source 和 query 的 raw annotation request；脚本当前生成 8 个 QA case，但不生成 REVIEWED、gold 或门禁结论；
- 本地真实内容语料已形成 3 个 READY/INDEXED Source、16 个 PROJECTED chunk、13322 个正文字符；最初 3-case pilot 由生产 `ChunkSearchPort` 分别捕获 7/12/9 个候选，top-1 均位于各自 allowed Source scope，未审核草稿严格为 `PENDING`；
- 首次真实 capture 发现 Markdown 链接中的 `D:/...` 本地绝对路径未被旧反斜杠规则脱敏；sanitizer 已统一覆盖两种 Windows 分隔符并重新 capture，当时的 3-case 草稿记录 13 次 path redaction，raw Workspace ID 与本地绝对路径均未落入脱敏输出；
- 最初 3 个真实 QA case 已完成基于 query、候选正文和允许 Source 的独立审核；随后数据集扩展为 8 个 case，其中 6 个可回答、2 个应拒答，覆盖基础产品能力、QA 标注流程、Research 能力概览、产品能力同义改写、跨 Source 干扰、虚构 theorem、Artifact Version 能力与 Research 安全验证；
- 真实回放修复了 raw annotation candidate 与 admitted shadow ranking 混用的问题：草稿继续保留越界候选供审核，`QaGoldAnnotationCompiler` 和 `QaChunkSearchShadowCapture` 只将允许 Source 内候选投影到 shadow ranking，同时保留过滤前 raw candidate count；最初 3-case scope 修正回放的五轮共 15 次 case 运行排名完全稳定、scope violation 为 0；
- QA 新运行统一使用 `qa-lexical-sufficiency-v2`。已有 `retrieval_strategy_v2_enabled` 数据库字段和设置接口不再参与 QA 运行时决策；它们不会改变 Workspace 的权限、资料或检索隔离。reviewed gold、annotation compiler 与离线 capture 同样固定评估 V2；策略不改 BM25 score 或候选顺序，只决定哪些词法充分的候选可进入 EvidenceBundle/shadow；
- 共享 `QaEvidenceSelectionPolicy` 固定为 `qa-source-diverse-budget-v2`：两个在线 profile 与离线 capture 都先按候选顺序做 evidence 去重和 Source 多样性选择，再按既有 fused score 确定性排序；统一预算均为最多 6 条 evidence、正文字符成本总计最多 8000，离线 `selectFinalBundle` 使用 raw chunk `String.length()` 镜像同一选择口径；baseline 不回滚 Source 多样性、统一 EvidenceBudgeter、scope/ownership/latest Snapshot、fallback/degradation 或 citation 边界，也不引入 vector、RRF 或 rerank；
- 在线 trace 新增 `relevant_primary_hit_count`、`relevance_rejected_count`、`mysql_relevance_rejected_count` 安全计数，不记录 query、正文或资源 ID；
- r5 的 8/8 `REVIEWED`、五轮 shadow 与历史指标保留在对应验收记录中；其临时 target artifacts 已被并发 `clean` 清除，旧 HMAC 盐也未持久化，因此不能恢复为当前可复验的同一谱系；Research/Artifact 在数据中仍只是 QA 上传资料主题，没有调用或修改两个独立 worker；
- r6/recovered 从仍存在的受控 3 Source / 16 projected chunk 语料与 raw 输入建立新的 HMAC lineage，8 个 case（6 答、2 拒）的五轮 `compile-reviewed` 均通过 fingerprint 与候选漂移校验；5 份 gold SHA-256 一致、完整 admitted ranking signature 只有 1 种。当前 content-free receipt 记录 `qa-retrieval-v2` 的 Recall@K `0.9305555555555555`、MRR `1.0`、NDCG@K `1.0`、citation precision `0.8888888888888888`、citation coverage `0.9305555555555555`、refusal accuracy `1.0`、scope violation `0` 和合并 latency `30211/190304/775584/2034074us`（min/p50/p95/max）；它只证明 artifact 摘要和排名签名一致，不证明执行独立性或线上质量；
- online exporter 新切片之前，r5 策略/标注定向测试以 `38/38` 通过，当时 retrieval eval 覆盖范围为 `23/23`；同一历史基线的受控 Backend 回归使用 `-Dspring.kafka.listener.auto-startup=false`，结果为 `365 tests / 0 failures / 0 errors / 2 skipped`；这些数字不覆盖后续扩展到 `44/44` 的 retrieval eval 与 exporter 新切片；
- 完整回归暴露并修复 `SessionEventMux.follow()` 的 replay/live 竞态：subscriber 现在在 channel 临界区内注册并 prime replay，解锁后才启动 drain 与 bridge pump，避免 live terminal 先关闭队列导致 `answer.delta` 或 `citation.upsert` 丢失；
- 新增阻塞 bridge executor 的确定性竞态测试，固定验证 replay、live citation 与 terminal 按序到达；
- 新增 `EvidenceBudgeter`，由 `RetrievalOrchestrator` 统一执行每个 channel step 的 `candidateLimit`，并按 `fusedScore` 在全局 `maxEvidence` 与 `maxEvidenceCharacters` 内确定性选择证据；`RetrievalPlan` 的候选与字符预算不再只是声明；
- QA 继续保留原通道内候选截断，Note/Wiki retriever 在构造 snapshot 前同步应用 step candidate limit，Orchestrator 再做统一兜底，避免不守约的 retriever 绕过计划；
- Note/Wiki strategy 从 snapshot 重放旧模板前，会按最终 EvidenceBundle 过滤窗口或 Knowledge Version；预算淘汰的证据不再出现在回答正文中；
- Wiki 在全部 evidence 入选时保持原 `citationIdsForWikiPages` 顺序；发生全局预算淘汰时，只绑定最终入选 Knowledge Version 自带的 citation IDs，避免 citation 越过 EvidenceBundle 边界；
- Wiki evidence 的字符成本改为实际进入 bundle 的 summary/content excerpt 长度，不再用未进入 evidence 的整页正文长度虚增预算；
- 新增 `retrieval-execution-trace-v1`：每轮 bundle 记录总检索耗时、每个 step 的 channel/candidate limit/raw/admitted count/latency/degradation，以及最终 evidence rank、kind、raw/fused/rerank score 和字符成本；
- trace 不包含 title、excerpt、content、query 或 Source 正文；AnswerRun 的 `retrieval.summary` 事件持久化完整结构化 trace，并在顶层冗余 plan version、latency、candidate/admitted/selected 数量与 selected characters 便于审计；
- 修复旧 `retrieval.summary.selected_evidence_count` 实际混入 citation 数量的问题；现在该值严格来自最终 EvidenceBundle，Wiki 复用的既有 citation 不再被误记为选中 evidence；
- `RetrievalOrchestrator` 输出 `noteweave.retrieval.step.latency`、`noteweave.retrieval.step.candidates`、`noteweave.retrieval.bundle.selected_evidence`、`noteweave.retrieval.bundle.selected_characters` 和 degraded counter；指标只使用 mode/channel/stage/degraded 等低基数 tag；
- `EvidenceRetriever` 请求边界扩展为 `AnswerContext + RetrievalPlan + Step`，具体 retriever 可以执行全局 evidence/graph budget，不再只能看到局部 candidate limit；
- 新增 `WikiGraphBudgeter`：`maxGraphHops=0` 时关闭出链/反链扩展；允许一跳时按 seed page 顺序遍历 outgoing/backlink，并同时服从全局 `maxGraphNodes`、`maxGraphEdges` 与 `maxGraphCharacters`；指向已命中 seed 或已接纳节点的边不重复消耗 node budget，但每条真正入选的关系仍消耗 edge/character budget；
- Wiki 页面候选仍由原算法最多召回 5 个，graph budget 只裁剪页面携带的关系扩展，不删除种子页面正文和来源 citation；budget 后的 contexts 同时用于 snapshot、evidence metadata 与旧模板重放；
- Wiki 默认图预算为 1 hop、30 个扩展 node、60 条 edge 和 4000 个关系渲染字符；character budget 是确定性的 token-safe proxy，只计算真正入选关系的安全渲染成本，不替换 tokenizer，也不修改页面正文 evidence 的字符预算；
- Wiki step trace 新增安全 measurements：`graph_hops_used`、`graph_nodes_used`、`graph_edges_used`、`graph_characters_used` 及对应四项 limit，并随 `retrieval.summary` 持久化，不包含页面标题或关系正文；
- QA ES hit hydrate 从仅按 Source ID 读取 provenance，升级为按 chunk ID 一次批量复核 exact chunk/source/snapshot ownership；SQL 同时要求当前 workspace、Source READY/INDEXED、Snapshot INDEXED 与 Passage PROJECTED；
- ES hit 的 source/snapshot 与数据库 ownership 任一不一致，或 chunk 不属于当前 workspace/已失效时，均 fail closed 丢弃；若全部 ES hit 被拒绝，继续走已有 workspace 约束的 MySQL fallback，不使用空 provenance 冒充合法证据；
- 新增 `EvidenceScopeGuard` 作为 Orchestrator 最后一道通用防线：PASSAGE 必须具有完整 source/snapshot/passage identity，且 `accessScope=workspace-source:<sourceId>`；Knowledge Version 必须具有 item/version identity，且 `accessScope=workspace-knowledge:<itemId>`；
- QA 携带显式 Source scope 时，任何不在 scope 内的 Passage evidence 都触发 `EVIDENCE_SCOPE_VIOLATION`，不进入预算、Prompt 或 citation；非 QA plan 不允许偷偷消费 Source scope；
- scope invariant 失败输出低基数 `noteweave.retrieval.scope_violation{mode,channel}` counter；QA ownership 拒绝日志只记录数量，不记录 workspace/source/chunk ID；
- 新增 `AnswerStrategyContractValidator`：检索前校验 strategy mode、plan/prompt/bundle version、workspace filter、显式 Source scope、step channel/candidate/weight 与 evidence/character/graph budget；生成前校验 evidence ID 唯一性、Prompt 引用必须属于 bundle、最低证据、无证据回答和 citation required；
- `PromptSpec` 新增显式 `refusal`，QA/Note/Wiki 的证据不足路径均标记 refusal 且禁止携带 evidence 引用；不再依赖中文模板文本猜测是否拒答；
- 固定错误码 `ANSWER_RETRIEVAL_PLAN_INVALID` 与 `ANSWER_PROMPT_POLICY_INVALID`，策略实现若返回 mode/filter/budget/version/evidence/citation 不一致的契约，必须在 Prompt 落库和生成前失败；
- 新增 V031 `answer_run.maximum_output_tokens`，三种模式的 `AnswerPolicy.maximumOutputTokens` 随 AnswerRun 持久化、在 API snapshot 与 `retrieval.summary` 中可审计，并通过 `AnswerGenerationMaterial` 传到生成网关；
- OpenAI-compatible LLM 请求显式发送 `max_tokens`；当前 template fallback 继续重放既有答案，不因模型 token 限额改变原模板算法；
- `ChatLlmClient` 的带 `maximumOutputTokens` 方法改为 Provider 必须实现的抽象契约；旧三参数入口只负责注入默认 1200，未来 Provider 无法再通过默认重载静默忽略策略预算；
- QA Passage ownership、QA MySQL fallback 与 Note 阅读窗口均只接纳 Source 最新的已索引 Snapshot；旧 Snapshot 即使仍为 `INDEXED/PROJECTED` 也不会进入当前 EvidenceBundle；Wiki 继续通过 `knowledge_item.latest_version_id` 只召回当前 Knowledge Version；
- Passage/Knowledge evidence 显式记录 `freshness_status=CURRENT_AT_RETRIEVAL` 与本轮 `freshAt` 校验时间，避免历史 AnswerRun 在版本更新后仍被解释为“当前仍有效”；端到端测试固定 Source v1/v2 并存时只接纳 v2，以及 Wiki v2 回答快照只绑定最新 Knowledge Version；
- 新增 V032 `answer_run.retrieval_plan_json/evidence_bundle_json`：完整 RetrievalPlan 与去正文的 `evidence-bundle-snapshot-v1` 随 AnswerRun 持久化，后者保留证据身份、版本、rank/score、scope、freshness、选择原因和字符成本，但不复制 title/excerpt/content 或 retriever opaque metadata；
- 新增 `RetrievalExecutionShadowExporter`，把持久化 execution trace + EvidenceBundle snapshot 映射为现有 `retrieval-shadow-v1`，统一 online trace 与 comparator/quality gate 的 latency、candidate count、ranking、source 和 citation 字段；输出显式携带 `strategyProfile`，comparator 会将其传播到 comparison report，quality gate policy/result 也携带 profile 并要求精确匹配，防止 baseline shadow 误套 V2 policy；导出仍强制 HMAC 假名化；
- 新增独立 `QaAnswerRunShadowExportService` 与 JDBC-only `QaAnswerRunShadowExportCli`：输入原始 `QaGoldAnnotationRequest` 和 `qa-answer-run-shadow-export-request-v2`，后者强制声明单一 `strategyProfile` 及 `caseId -> answerRunId` 映射；固定用 3 次批量查询拼接已完成 QA AnswerRun 的 RetrievalPlan、最终 EvidenceBundle、唯一 `retrieval.summary` 与已持久化 citation；单批最多 500 case，并在 read-only `REPEATABLE_READ` 事务中执行，避免按 case/evidence N+1；一次导出禁止混合 baseline/V2 cohort；
- online exporter 会逐项核对 annotation 的 Workspace/query/Source scope、`QA_PASSAGE/12`、V2 profile 与 plan/relevance/selection/预算、run/plan/summary/trace/bundle 的 schema/version/count/rank/score/character cost、trace 中的 `strategy_v2_enabled`，以及 citation 的 workspace/source/snapshot/chunk/order；任一缺失、重复、非 V2 tuple 或漂移均 fail closed。唯一受支持 tuple 为 `qa-retrieval-v2 / qa-passage-v2 / qa-lexical-sufficiency-v2`，selection 为 `qa-source-diverse-budget-v2`、预算为 `6/8000`；旧 AnswerRun 不作为当前简历演示或导出 cohort；
- DB citation UUID 只用于证明 citation 确实持久化；shadow citation identity 统一重建为 `citation-label:<passageId>` 再做 HMAC，才能与 r5 gold 精确对齐。online `candidateCount` 不再误用 Orchestrator 中最多 6 条的 `rawCandidateCount`：主路径取 `primary_hit_count`，MySQL fallback 取 `mysql_candidate_count`；
- DB 持久化 JSON 使用 Spring `SNAKE_CASE` mapper，annotation/run-map/shadow artifact 使用独立 camelCase mapper；CLI Profile 只装配 DataSource/JdbcTemplate/transaction/Jackson 和 exporter，不启动 Web、ES、Kafka、Redis 或 Flyway。`scripts/export-stage5-qa-answer-run-shadow.ps1` 要求至少 16 字符 HMAC 盐和只读数据库连接环境变量；当前只完成代码/H2 契约验证，尚未产生真实 online AnswerRun 样本导出结果，也未发布生产门禁；
- QA 的 ES→MySQL fallback 不再作为普通成功静默返回：`QaPassageRetriever` 保留原 fallback 查询、关键词评分和来源多样性顺序，同时输出 `qa_primary_no_scoped_hits`、`qa_primary_search_error`、`qa_primary_ownership_rejected`、`qa_mysql_fallback` 稳定原因码，以及 primary/scoped/rejected/MySQL/selected 安全计数；
- `QaPassageEvidenceRetriever` 将上述 diagnostics 传入统一 `EvidenceRetrievalResult`，Orchestrator 再写入 bundle、step trace、`retrieval.summary.degraded/degradation_reasons` 和既有低基数 Micrometer degraded 指标；原因码与 measurements 均不含 query、异常消息或资源 ID；
- `ElasticsearchChunkSearchAdapter` 在 ES 已启用但 client 缺失或请求失败时不再吞错返回空列表；异常由 QA 策略边界捕获并显式降级，离线 capture/benchmark 则会直接看到基础设施失败；失败日志只记录固定低基数原因码，不记录 Workspace、query 或异常原文；显式关闭 ES 时仍保持空候选语义；
- `SendMessageResponse` 与 AnswerRun get/list snapshot 新增 `retrieval_degraded/retrieval_degradation_reasons`；发送响应直接使用本轮 EvidenceBundle，持久化 snapshot 则从 `answer_run.evidence_bundle_json` 投影，事件、API 与数据库不维护第二套降级真源；旧 AnswerRun 没有 bundle snapshot 时返回 `retrieval_degraded=null` 与空原因数组；
- 新增 `EvidenceOwnershipPort/EvidenceOwnershipGuard` 与 JDBC read adapter；Orchestrator 在结构 scope guard 后批量复核每条 PASSAGE 的 workspace/source/snapshot/current projection，以及每条 KNOWLEDGE_VERSION 的 workspace/ACTIVE/latest version，查询正确不再是唯一安全假设；
- ownership adapter 对任意数量 Passage 与 Knowledge evidence 固定最多两次 SQL；identity 任一不匹配、跨 Workspace、历史 Snapshot/Version 或已删除 Knowledge Item 均复用 `EVIDENCE_SCOPE_VIOLATION` fail closed，并进入既有低基数 scope violation 指标；
- V046 的 `retrieval_strategy_v2_enabled` 字段和对应设置接口作为历史兼容保留，不建索引；新 QA AnswerRun 不读取该值，所有 Workspace 都固定生成 `qa-retrieval-v2 / qa-passage-v2`。Workspace 仍继续隔离权限、资料、Source scope、AnswerRun 和引用；
- 原 QA 排序、MySQL fallback 结果、Note metadata 权重、窗口规划、关系评分和未触发预算淘汰时的可见回答算法未改；QA 可见模板中误写的“轻量 rerank”已改为真实执行的“词法充分性校验”，没有借文案暗示尚未引入的算法。`478 tests` 是 receipt 前的历史全量截面；V2-only 收敛后 Backend 当前完整 Surefire 为 `106` 个 suite、`495 tests / 0 failures / 0 errors / 2 skipped`，receipt/QA 定向回归详情写入 r6 验收记录；

本轮用户呈现边界：普通用户在线提问后直接看到回答正文与引用来源，无需人工审核，也不展示 `PENDING`、`REVIEWED`、gold、shadow 或质量指标；人工标注只属于开发侧离线基准。Research 前端保持与 Gemini/GPT/MiroThinker 类似的轻量报告展示，本阶段不建设复杂审核工作台；`workers/research-worker/` 与 `workers/artifact-worker/` 均未读取、未修改，8-case 中出现 Research/Artifact 只是 QA 检索资料主题。

剩余工作改按《阶段 5-6：简历展示最小收尾计划》执行：新 QA AnswerRun 固定展示 `qa-retrieval-v2`，继续保留 Workspace/Source scope/ownership/citation 边界；不再扩展 baseline 对比、真实线上样本、灰度/回切观测、faithfulness、answer relevance、cost、CI 四层编排或生产容器 smoke。r6/recovered 只作为可复验的离线演示证据，不写成生产承诺，也不据此引入 vector、RRF 或 rerank。

## 1. 统一什么，不统一什么

统一：AnswerRun 生命周期、输入上下文、RetrievalPlan、EvidenceBundle、引用校验、LLM gateway、事件、指标和评测协议。

不统一：QA 的资料召回、Note 的写作/日记上下文、Wiki 的页面/关系图与新鲜度策略。策略模式的价值是把差异放进稳定接口，不是写一个充满 `if (mode)` 的万能实现。

## 2. 核心接口

```java
interface AnswerModeStrategy {
    AnswerMode supports();
    RetrievalPlan plan(AnswerContext context);
    PromptSpec compose(AnswerContext context, EvidenceBundle evidence);
    AnswerPolicy policy();
}

interface Retriever {
    RetrievalChannel channel();
    List<EvidenceCandidate> retrieve(RetrievalQuery query);
}

interface EvidenceFusion {
    EvidenceBundle fuse(RetrievalPlan plan, List<EvidenceCandidate> candidates);
}
```

`EvidenceBundle` 应包含稳定 evidence id、source/snapshot/passage 或 knowledge/version 引用、原始分数、融合/重排分数、定位信息、摘录、访问 scope、freshness、选择原因和预算。Prompt 只能引用 bundle 中的 evidence id，CitationAssembler 再校验并持久化。

## 3. 三种策略

### 3.1 QA Strategy

- 以 active Source Snapshot Passage 为主；
- 过滤 Workspace、状态、Source 类型、时间和用户 scope；
- lexical + vector 候选，RRF/加权融合，必要时 rerank；
- 邻接窗口只对选中的 Passage 扩展；
- 无足够证据时明确回答证据不足，不让 Memory 冒充事实来源。

### 3.2 Note Strategy

- 检索相关 Note version、最近 journal、用户选择的 Source 和 Conversation 摘要；
- 按“继续写作、归纳、改写、比较”等 intent 选择证据预算和 prompt；
- Note 当前版本是业务真相，ES 只是召回；
- 写操作通过显式 command 和新 version，不在回答生成中隐式覆盖正文。

### 3.3 Wiki Strategy

- 先召回 Wiki page/version，再按 relation graph 扩展有限 hop；
- freshness/checkpoint 不合格的页面降权或排除；
- graph expansion 有节点、边和 token budget，防止爆炸；
- 页面回答引用具体 Knowledge Version 及其 Source provenance；
- Wiki 重建/撤回属于 Projection Execution，不放进回答请求。

## 4. Retrieval Orchestrator

将当前巨型 `RetrievalService` 拆为：

- `LexicalPassageRetriever`
- `VectorPassageRetriever`
- `KnowledgePageRetriever`
- `RelationGraphRetriever`
- `MetadataFilter`
- `CandidateFusion`
- `Reranker`
- `EvidenceBudgeter`
- `EvidenceHydrator`

Orchestrator 只执行 plan 和收集指标。ES hit 元数据使用文档中稳定冗余字段或批量 JDBC hydrate，禁止逐 hit 查询 Source 造成 N+1。

## 5. 混合检索的引入顺序

1. 建固定评测集和现有 BM25 baseline；
2. 修复过滤、N+1、窗口和 citation 正确性；
3. 增加 embedding pipeline，embedding model/version 进入投影；
4. lexical/vector 各取候选，用 RRF 作为可解释 baseline；
5. 只有离线指标和 p95 延迟证明有收益时增加 cross-encoder/LLM rerank；
6. 每次算法变化提升 `retrieval_plan_version`，支持 A/B 和回放。

不要把 MySQL LIKE fallback 当成与 ES 等价的静默降级。ES 不可用时可返回 degraded lexical result，但响应/指标必须标记，关键场景可直接失败以避免低质量答案。

## 6. ES 与 embedding 投影

Passage 文档包含 tenant filter、source metadata、content、embedding、snapshot/projection/parser/embedding version。Embedding 通过可靠投影执行，版本不一致时不混合比较。索引 mapping 变更使用新索引 + backfill + alias switch；重建期间旧索引继续服务。

权限过滤必须在查询层执行并在 hydrate 再校验，不能只依赖缓存。删除/撤权事件应使检索投影及时不可见，必要时读路径增加 active snapshot/version filter。

## 7. Memory 接入边界

MemoryControlPack 是 prompt 控制信息，不是 EvidenceBundle。它可以影响格式、偏好、禁用模式和检查清单，不能作为事实引用，也不能绕过 Workspace 权限。策略声明允许的 memory neighborhood 和 token budget，由 P6 的 Compiler 生成。

## 8. 评测体系

每种模式建立独立 gold set：查询、允许 scope、相关 evidence、期望 citation、拒答条件。指标至少包括 Recall@K、MRR/nDCG、citation precision/coverage、faithfulness、answer relevance、无证据拒答、p50/p95 latency 和成本。

在线记录 plan version、候选数、各 channel latency、融合前后 rank、selected evidence、LLM/citation 结果。敏感正文不直接进入指标 label/log。

目标评测体系分四层运行：PR 使用小型 deterministic fixture；合并后运行容器 smoke；每日/版本运行完整 retrieval benchmark；模型或 prompt 变更运行质量、token 和延迟联合回归。当前四层 CI 编排及其中的合并后容器 smoke、定时 benchmark 和模型/prompt 联合回归尚未闭环。Judge 结果不能覆盖 citation existence、权限、schema 等确定性失败。

## 9. 迁移步骤

1. 定义 AnswerModeStrategy、RetrievalPlan、EvidenceBundle；
2. 用 adapter 包住现有 QA 逻辑，输出 bundle，不改算法；
3. 迁移 Note，再迁移 Wiki；
4. ChatService 删除 mode switch，Strategy Registry 负责选择；
5. 拆 Retriever 和 hydrate，消除 N+1；
6. 建离线回放和 baseline；
7. 灰度 vector/fusion/rerank；
8. 删除旧 builder/DTO 和 MySQL 隐式 fallback。

## 10. 测试与验收

- Registry 对每个 mode 恰有一个策略，未知 mode 明确拒绝；
- 相同 EvidenceBundle 可重放并得到稳定 citation 映射；
- QA/Note/Wiki 的 scope 和权限过滤均有越权测试；
- ES 结果 100 条时 hydrate 查询次数保持常数级；
- graph expansion 严格服从 hop/node/token budget；
- embedding/rerank 关闭时 baseline 可用；开启后必须达到预设质量且延迟预算不超标；
- Source/Knowledge 版本更新后旧证据不会被误标为当前。

完成标志：三种模式共用 Answer Pipeline 和 EvidenceBundle；模式差异位于可独立测试的策略；RetrievalService 不再是领域合集；检索升级由评测数据驱动。

## 11. 回滚

QA 策略开关不再是运行时功能：新 AnswerRun 统一固定 V2，不提供 V1 回切。ownership/scope/latest-Snapshot 校验、MySQL fallback 降级标记、citation 校验、最少证据策略、Source 多样性和 `6/8000` 预算继续保留；Workspace 隔离与历史数据不受影响。简历展示版只需要说明和演示当前 V2 路径。
