# 阶段 5：线上 AnswerRun Shadow 导出闭环验收（2026-07-16）

> 当前口径补充：本文 baseline/V2 cohort 描述保留为历史工程验收。简历展示版只接受并导出 `qa-retrieval-v2`；`retrieval_strategy_v2_enabled` 不再改变新 QA AnswerRun 的运行时策略，Workspace 仍完整隔离数据与权限。

## 定位与用户边界

本切片实现了将线上已完成 QA AnswerRun 的最终持久化事实导出为既有 `retrieval-shadow-v1` 的代码路径，打通“持久化 RetrievalPlan / EvidenceBundle / execution trace / citation → 离线 comparator 与质量门禁输入”的工程接口。当前验证止于代码/H2 契约与定向测试，尚未产生真实 online AnswerRun 样本导出结果。导出器只读取回答完成后已经落库的产物，不重新执行检索、排序或生成，因此不改变 QA 的 Elasticsearch 主路径、MySQL fallback、回答模板和用户可见算法。

普通用户在线提问后仍直接看到回答正文与引用来源，不经过人工审核；`PENDING`、`REVIEWED`、gold、shadow、漂移报告和质量指标只属于开发侧离线评测，不进入普通用户界面。本切片没有前端改造，也没有读取或修改 `workers/research-worker/`、`workers/artifact-worker/`；数据中的 Research/Artifact 只表示 QA 检索资料主题。

当前结论是：线上导出代码路径与 fail-closed 校验已形成可运行的工程闭环；真实 online AnswerRun 导出验证、production quality policy/阈值及阶段 5 生产门禁仍未完成。

## 独立 JDBC-only Profile、CLI 与输入输出

- `QaAnswerRunShadowExportCliConfiguration` 使用独立 Profile `qa-answer-run-shadow-export-cli`，只装配 DataSource、JdbcTemplate、事务、Jackson、`RetrievalSnapshotSanitizer`、`RetrievalExecutionShadowExporter` 和导出 Service。
- CLI 以 non-web 进程启动，不装配 Web、Elasticsearch、Kafka、Redis 或 Flyway；Hikari 设置 `read-only=true`，连接池上限为 2。
- 第一个输入是原始 `QaGoldAnnotationRequest`，即 `retrieval-qa-annotation-request-v1`；它提供 dataset、case、Workspace、query、允许的 Source、`topK` 和固定候选池大小，不要求把 REVIEWED gold 交给线上导出器。
- 第二个输入是 `qa-answer-run-shadow-export-request-v2`，必须声明本批次唯一的 `strategyProfile`，并用 `cases: [{caseId, answerRunId}]` 表达精确的 `caseId → answerRunId` 映射及 `snapshotVersion`。一次导出禁止混合 baseline/V2 cohort；旧 request-v1 会被 schema 校验拒绝。
- 输出是经 HMAC 假名化的 camelCase `retrieval-shadow-v1`，显式携带同一个非空 `strategyProfile`，可直接交给 comparator/quality gate；comparator 将 profile 传播到 comparison report，quality gate policy/result 也携带 profile 并要求精确匹配，避免不同 cohort 共用门禁；输出路径不得覆盖两个输入文件。
- CLI 命令为 `export-answer-runs <annotation-request.json> <run-map.json> <shadow.json>`；仓库入口为 `scripts/export-stage5-qa-answer-run-shadow.ps1`。

导出运行时必须提供至少 16 字符的 `NOTEWEAVE_RETRIEVAL_EXPORT_SALT` 和 `SPRING_DATASOURCE_*`。生产执行必须使用只授予必要 `SELECT` 权限的只读数据库账号，不能复用应用写账号；Spring/Hikari 的 read-only 标记是纵深防线，不替代数据库 GRANT。脚本会检查盐和 datasource URL，并明确提示使用只读账号，但不会代替数据库验证账号权限。

## 固定三次批量 SQL 与一致性事务

`exportAndWrite` 与核心 `export` 都声明 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`。CLI 通过 Spring 代理调用 `exportAndWrite`，事务在第一条 SQL 前已经生效；事务探针测试同时验证实际事务已启动且为 read-only，避免同类自调用绕过代理。

一次导出最多接纳 500 个唯一 case 和 500 个唯一 AnswerRun；超过上限直接拒绝，不在进程内静默拆批。合法批次始终使用三次集合查询，不随 case 或 evidence 数量产生 N+1：

| 批量查询 | 持久化边界 |
| --- | --- |
| 1 | `answer_run` 联结 query/answer message，读取 run 身份、状态、角色、Workspace、query、RetrievalPlan 与最终 EvidenceBundle |
| 2 | `answer_event` 中唯一的 `retrieval.summary`，读取顶层审计字段和完整 execution trace |
| 3 | answer message 的 citation，并联结 Source、SourceSnapshot、SourceChunk 复核持久化 ownership tuple 与顺序 |

`REPEATABLE_READ` 保证三批读取看到同一事务快照；read-only 事务和只读数据库账号共同限制导出进程只能审计，不能改变线上 AnswerRun。

## Fail-closed 校验矩阵

| 边界 | 必须满足的约束 |
| --- | --- |
| Annotation / run map | request schema、dataset、`candidatePoolSize=12`、非空且受支持的单一 `strategyProfile`、非空 case；case/run ID 均唯一非空；两份输入的 case 集合完全一致；allowed Source 非空且没有空 ID；`1 <= topK <= 6`；请求 profile 与任一 AnswerRun 实际 profile 不一致时终止整批导出 |
| AnswerRun 身份 | 仅接纳 `QA + COMPLETED`；query/answer message 必须属于同一 Workspace/Conversation；角色分别为 USER/ASSISTANT，answer mode 都为 QA；持久化 Workspace 和 query 必须与 annotation 精确一致 |
| RetrievalPlan | Baseline 必须为 `qa-retrieval-baseline-v1 / qa-passage-baseline-v1 / qa-lexical-sufficiency-v1`；V2 必须为 `qa-retrieval-v2 / qa-passage-v2 / qa-lexical-sufficiency-v2`；历史 `qa-passage-v1 + v2 policy` 仅归入 legacy V2 兼容。AnswerRun 列与 plan version 一致；只有一个 `QA_PASSAGE` step，`candidateLimit=12`、`weight=1`；Workspace、保存时的 `snapshot_status=ACTIVE`、排序后的 Source scope 必须与 annotation 一致 |
| Policy 与预算 | 两个新 profile 的 `selection_policy` 都必须是 `qa-source-diverse-budget-v2`，预算都为最多 6 条 evidence、最多 8,000 字符且 graph hop/node 为 0；baseline 只回切 relevance admission，不回滚 Source 多样性、统一预算或安全边界；最终 bundle 也会独立拒绝超过 6 条或 8,000 字符的结果 |
| JSON / schema | 持久化 bundle、summary、trace 的必填字段、对象/数组形状、整数/长整数、有限 score、非负计数均显式检查；不依赖 Jackson 对缺失 primitive 的默认值静默补零 |
| Summary / trace / bundle | 必须恰好一条 `retrieval.summary`；新 AnswerRun 的 `strategy_profile/relevance_policy/selection_policy`、plan/schema version、latency、candidate/admitted/selected 数量、字符成本、degraded 与 degradation reasons 必须一致；step measurement `strategy_v2_enabled` 必须与 profile 对齐，单个 step 的 index/channel/limit/raw/admitted/degradation 必须与顶层 trace 对齐；只有 legacy V2 tuple 允许新增 profile 审计字段缺省 |
| 最终 evidence | 仅接纳 PASSAGE；rank、evidence ID、Source/Snapshot/Chunk、`workspace-source:<sourceId>`、freshAt 均完整；evidence ID 与 chunk ID 不重复；每条 Source 必须属于 annotation 的 allowed Source；bundle 顺序、identity、raw/fused/rerank score、字符成本与 selected trace 逐项一致 |
| Candidate 路径 | `mysql_fallback_used`、`selected_count` 与 step/top-level count 必须完整自洽；后端候选池不得小于预算前 step raw candidate；fallback 原因与 `qa_mysql_fallback` 必须一致 |
| Citation | 数量必须等于最终 evidence 数量，并按 message citation `sort_order` 一一对应；citation 的 Workspace/Source/Snapshot/Chunk 以及所联结 Source/Snapshot/Chunk ownership 必须与最终 evidence 完全一致 |

任一必填字段缺失、重复、跨 Workspace/Source、未知或混合 profile、版本漂移、顺序漂移、预算越界、分数/字符漂移或 citation 不一致都会终止整批导出，不产生部分可信的 shadow。这里兼容的是数据库中历史 `qa-passage-v1` V2 AnswerRun，不是旧 `qa-answer-run-shadow-export-request-v1`。

## Citation、candidateCount 与历史审计语义

数据库随机 citation UUID 只用于证明回答确实持久化过对应 citation，并参与 ownership 联结；它不是稳定的评测身份。QA PASSAGE 的 `passageId` 就是 chunk ID，导出器统一重建 `citation-label:<chunkId>`，再对该语义 ID 做 HMAC 假名化。这样线上导出可以与 r5 gold 的 citation label 精确对齐，也不会把数据库 UUID 暴露到 artifact。

Shadow 的 `candidateCount` 表示实际后端候选池，不使用 Orchestrator 预算后最多 6 条的 raw/admitted 数量冒充：

- Elasticsearch 主路径取 step measurement `primary_hit_count`。
- `mysql_fallback_used=1` 时只取 `mysql_candidate_count`。
- 对应路径 measurement 缺失、为负、溢出或小于 step raw candidate 时直接 fail closed。

历史导出审计的是回答当时已经持久化的 plan、bundle、trace 和 citation tuple。它会校验 Source/Snapshot/Chunk 在这些历史产物之间身份一致，但不会用资料后来更新后的当前 latest Snapshot/Version 再次否定过去合法的 AnswerRun；plan 中的 `snapshot_status=ACTIVE` 是回答时保存的过滤合同，不是导出时重新查询当前 latest 状态。

## CamelCase artifact 与 snake_case DB 隔离

Spring 注入的数据库 ObjectMapper 按 `SNAKE_CASE` 解码 `retrieval_plan_json`、`evidence_bundle_json` 和 `retrieval.summary.payload_json`。文件 artifact 使用独立的默认 ObjectMapper 读取原始 annotation/run-map，并写出 camelCase shadow。

两套 mapper 不共享命名策略：数据库格式变化不会把 artifact 悄悄改成 snake_case，artifact 输入也不会按数据库规则误解。测试同时确认输出存在 `snapshotVersion`、不存在 `snapshot_version`，并且不包含原始 AnswerRun、Workspace、Source、Chunk、citation UUID 或 query。

## Evidence Receipt 与 r6/recovered 离线谱系

`RetrievalEvaluationEvidenceReceiptCli` 已可对一份 sanitized gold 与多份 sanitized shadow 生成 `retrieval-evaluation-evidence-receipt-v1`。receipt 只保留 artifact byte size/SHA-256、安全 dataset/snapshot version、strategy profile、聚合质量/运行时指标和完整 ranking signature；不写文件路径、query、正文或 case/evidence/source/citation 标识。

此前 r5 的 target 目录 artifacts 被并发 `clean` 删除，且旧 HMAC 盐按安全规则没有持久化，因此不能恢复相同的假名、fingerprint 或摘要。后续 r6 是基于仍存在的 3 Source / 16 projected chunk 受控语料重新建立的离线谱系，不是 r5 artifact 恢复，也不能替代真实 online AnswerRun 导出。

r6 5 轮 `compile-reviewed` 的 receipt 已落在 `验收记录/evidence/阶段5-r6-recovered-receipt.json`：8 个 case（6 个可回答、2 个拒答）、单一 `qa-retrieval-v2` profile、一个 ranking signature、`rankingsStable=true`。这仅表示输入给 receipt 的五份 artifact 排名签名一致；它不证明五次执行相互独立，不是数字签名、外部见证或生产 AnswerRun 证据。

## 自动化验证状态

本节中 `48/48`、`24/24` 与 `478` 均为 receipt 和后续 QA profile 测试修正之前的历史切片，不应继续当作当前最新全量回归数字。

- Backend main compile：通过。
- QA/Workspace 核心定向：`50/50`，`0 failures / 0 errors / 0 skipped`。
- `com.noteweave.retrieval.eval.*Test` 14 类全集：`48/48`，`0 failures / 0 errors / 0 skipped`；覆盖 profile-aware comparator/gate 显式传播、baseline/V2 错配和 generic fixture 兼容测试。
- online AnswerRun exporter/CLI 定向：`24/24`，`0 failures / 0 errors / 0 skipped`；覆盖 request-v2 单 profile cohort、baseline/V2/legacy V2 tuple、JDBC-only context、真实 read-only 事务探针、CLI 分派、三次批量 SQL、严格 JSON/plan/budget/scope/trace/citation 校验、camelCase artifact、HMAC 语义 citation 和 backend-path candidate count。

旧的完整 Backend 结果 `365 tests / 0 failures / 0 errors / 2 skipped` 完成于本次 online exporter 新切片之前，只保留为 r5 基线历史证据，不能表述为覆盖本切片的新全回归。

该切片当时的完整 Backend 回归覆盖 104 个 Surefire test suite，执行 `478 tests`，结果为 `0 failures / 0 errors / 2 skipped`，Maven `BUILD SUCCESS`。本切片没有读取或修改独立 Research Worker；该历史结果不替代真实 online AnswerRun、生产分布与 quality policy 门禁。

## r5 Pilot 指标历史记录

线上导出器只改变证据的审计与导出来源，不改变 r5 检索、selection 或回答算法。以下 pilot 指标只属于 `qa-retrieval-v2`，不能作为 baseline 指标：

- `macro Recall@K = 0.916667`。
- `macro MRR = 1.0`。
- `macro NDCG@K = 0.967468`。
- `macro citation precision = 0.822222`。
- `macro citation coverage = 0.916667`。
- `refusal accuracy = 1.0`。
- `scope violation = 0`。

这些数值仅保留为 r5 的历史截面；其原 artifacts 已不再可复验。当前可复验的离线 artifact lineage 见 r6/recovered receipt，但 r6 同样只是 8-case、3 份语料、5 轮回放的 V2 离线证据。baseline 当前只是紧急 relevance 回切路径，尚无独立 reviewed-gold production 阈值。导出代码能力不会把有限样本自动升级为 production threshold。

## 验收结论与剩余门禁

阶段 5 已具备从完成态 QA AnswerRun 以固定查询成本、只读一致性事务、严格 scope/预算/trace/citation 校验和 HMAC 脱敏导出 `retrieval-shadow-v1` 的代码能力，并已通过 H2/自动化契约验证；该能力尚未以真实 online AnswerRun 完成生产式导出。先前 annotation CLI 缺少 JDBC ownership hydrate、不能代表最终线上 EvidenceBundle 的工程边界，已在该导出代码路径中补齐，但不能据此宣称 production gate 已验收。

但以下生产化工作仍未完成：

- 尚无真实 online AnswerRun 样本导出结果，也未积累足量、多轮、独立语料的生产分布；不能据 8-case pilot 直接发布阈值。
- 按 `strategyProfile` 区分的正式 production policy，以及 Recall/MRR/NDCG/citation/refusal/scope/p95 的版本化阈值尚未定稿。
- Workspace `retrieval.strategy.v2` 技术开关、授权 API 与单 AnswerRun profile 冻结已经落地，数据库默认关闭；但尚无真实 online AnswerRun 导出样本、真实灰度/回切观测和正式 production policy，因此不能把技术开关等同于发布门禁。
- faithfulness、answer relevance 与 cost 指标尚未闭环。
- 定时 benchmark、CI 四层评测编排与合并后容器 smoke 尚未闭环；本地 annotation CLI smoke 不等同于该 production gate。
- 在上述门禁完成前，不关闭阶段 5 production gate，也不提前引入 vector、RRF 或 rerank。

下一切片应使用只读数据库账号按单一 `strategyProfile` 批次导出多轮真实 AnswerRun，持续交给 profile-matched comparator/quality gate 观察稳定性和尾部延迟，再依据扩充样本分别校准 production policy 并执行 Workspace 灰度/回切观测。普通用户路径不增加人工审核步骤。
