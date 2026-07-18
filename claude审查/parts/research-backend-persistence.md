## Research 后端 · 持久化/领域/DTO层
（审查发现如下，分批追加）

## ResearchAgentEvidenceIngestionService

### [高] 插入冲突时无界递归可致 StackOverflow
- 位置：`backend/src/main/java/com/noteweave/research/ResearchAgentEvidenceIngestionService.java:77-79`
- 问题：`appendOne` 在 `insert into source_evidence` 抛 `DataIntegrityViolationException` 时直接递归调用 `appendOne(scope, evidence)`，没有任何重试上限或退出条件。
- 证据：`catch (DataIntegrityViolationException duplicate) { return appendOne(scope, evidence); }`。若 DIVE 由 evidence_key 唯一键之外的原因（如 FK 违约、NOT NULL、并发未提交行导致 select 读不到但 insert 一直等锁/失败）触发，则前置 select `existing` 仍为 null，会无限递归。
- 影响：并发或异常数据下 StackOverflowError，事务线程崩溃；且在 @Transactional 内 DIVE 后连接可能已被标记 rollback-only，递归中的 select/insert 会持续失败。
- 建议：改为有界重试（最多 1-2 次）并在再读仍为 null 时重抛原异常；仅对 evidence_key 唯一约束冲突做幂等重读。

### [中] 遗留写入路径不填 ppm 列，与原子路径产生列级数据不一致
- 位置：`ResearchAgentEvidenceIngestionService.java:66-75`（insert source_evidence）；对比 `ResearchAgentCompletionCommitter.java:460-471`
- 问题：该遗留（`@Deprecated(forRemoval=true)`）路径 insert `source_evidence` 时只写 `support_score`/`conflict_score`（decimal），不写 V045 新增的 `support_score_ppm`/`conflict_score_ppm`/`content_digest`/`agent_completion_id`。而原子完成路径两套都写。
- 证据：V045 迁移新增 `support_score_ppm int null` 等；本 insert 列清单无这些列。
- 影响：同一张表内一部分行 ppm 为 null、一部分非 null。任何以 ppm 为权威（如 content_digest 校验、精确比较）的读路径遇到遗留行会得到 null，导致 NPE 或判定错误。
- 建议：若仍保留该路径，应同步写入 ppm 列（由 decimal 反推或要求上游传 ppm）；否则尽快按 forRemoval 移除并封堵入口。

### [低] BigDecimal 精度可能超列 scale
- 位置：`ResearchAgentEvidenceIngestionService.java:75`（及 `ResearchAgentCellMergeService.java:70`）
- 问题：`BigDecimal.valueOf(evidence.supportScore())` 由 double 直接构造，scale 可能 >4，写入 `decimal(5,4)` 依赖 MySQL 隐式舍入。
- 证据：列定义 `support_score decimal(5,4)`；double 0.123456789 → scale 9。
- 影响：strict SQL mode 下可能触发截断告警/错误；舍入后与 ppm 派生值不一致。
- 建议：显式 `.setScale(4, HALF_UP)` 后再写入，与 `ResearchAgentCompletionCommitter.legacyScore` 保持一致。

## ResearchAgentCellMergeService

### [中] 遗留 merge 更新 research_cell 不写 confidence_score_ppm
- 位置：`ResearchAgentCellMergeService.java:114-137`（update research_cell）
- 问题：合并接受时 SET `confidence_score = ?` 但未 SET `confidence_score_ppm`（V045 于 research_cell 新增列）。原子路径 `ResearchAgentCompletionCommitter.java:601-602` 两者都写。
- 证据：本 update 无 `confidence_score_ppm`；候选 insert（第 51-71 行）也无 `confidence_score_ppm`/`content_digest`/`agent_completion_id`/`research_agent_execution_id`。
- 影响：经遗留路径 VERIFIED 的 cell，其 ppm 列为 null，与原子路径 cell 语义不一致；任何以 ppm 为准的读取/投影出现 null 分支。
- 建议：遗留路径同步写 ppm（由 confidence 反推），或彻底停用该 @Deprecated 路径。

### [低] mapCandidate 将 confidence_score 反序列化为 double，丢失精度且与 ppm 语义偏离
- 位置：`ResearchAgentCellMergeService.java:238-245`
- 问题：`confidence_score` 以 `getBigDecimal(...).doubleValue()` 转 double 存入 record，后续再 `BigDecimal.valueOf(...)` 回写，double 往返引入精度误差。
- 影响：候选置信度在多次读写间可能漂移；与全系统"ppm 整数为权威"的设计（V045）不统一。
- 建议：内部保留 BigDecimal 或统一走 ppm 整数。

### [低] readStringList 对 null/空 JSON 处理不健壮
- 位置：`ResearchAgentCellMergeService.java:268-274`
- 问题：`objectMapper.readValue(rawJson, List<String>)` 若 `evidence_ids_json` 为 null 会 NPE/IllegalArgument，仅捕获 `JsonProcessingException`。`research_agent_candidate.evidence_ids_json` 迁移 V038 定义为 `not null`，但遗留数据或其他写入点无强约束时仍有风险。
- 影响：脏数据触发未被业务异常包裹的运行时异常。
- 建议：对 null/空串返回空列表或抛业务异常，并扩大 catch 到 `IOException | IllegalArgumentException`。

## ResearchAgentTaskService

### [低] 存在多个未使用的私有方法（死代码）
- 位置：`ResearchAgentTaskService.java:380-422`（`readMap`、`canonicalize`、`sha256`）、`469-476`（`isRunnableIncrementalRun`）
- 问题：快照规范化/摘要已委托给 `ResearchAgentTaskSnapshotCanonicalizer`，这些本地方法无调用点；`readMap` 上的 `@SuppressWarnings("unchecked")` 也无对应未检查转换。
- 影响：坏味道，增加维护成本，误导读者以为存在第二套摘要实现（跨运行时一致性风险点）。
- 建议：删除死代码，统一由 canonicalizer 负责。

### [中] createTask 幂等回放仅比对 task_key，不比对角色/绑定/预算等内容
- 位置：`ResearchAgentTaskService.java:55-61, 80-86`
- 问题：`findByIdempotency` 命中后仅校验 `taskKey` 相等即返回既有任务；`role`、`targetCells`、`budget`、`targetBindings`、`executionContext`、`planRevision` 等不同也会被当作幂等成功返回。
- 证据：`if (!existing.taskKey().equals(command.taskKey())) throw ...; return snapshot(existing);` DIVE 分支同理仅比 taskKey。
- 影响：同一 idempotency_key 复用但内容漂移时静默返回旧任务，协调器以为写入了新内容，造成快照/预算与协调器状态不一致（对照 candidate/budget 路径均做了内容比对，此处偏弱）。
- 建议：与候选/预算幂等一致，回放时比对关键内容摘要，不一致抛 `RESEARCH_AGENT_TASK_IDEMPOTENCY_CONFLICT`。

## DTO / 序列化契约

### [低] 冗余且不一致的 @JsonProperty(snake_case)
- 位置：`ResearchRunDetailResponse.java:38,41`、`ResearchRunSummaryResponse.java:52`、`ResearchCheckpointResponse.java:19`、`ResearchResumeCheckpointSummaryResponse.java:14`、`SaveResearchReportSourceResponse.java:6-13`
- 问题：全局已配置 `spring.jackson.property-naming-strategy: SNAKE_CASE`（application.yml:20），字段名经策略自动转 snake_case。个别字段又显式标 `@JsonProperty("saved_report_source")`/`("agent_execution_projection")`，与自动结果重复；同一 DTO 内绝大多数字段不标、少数标注，风格不一致。
- 影响：无功能缺陷，但易误导：若未来去掉全局策略，仅这几个字段仍是 snake_case、其余变 camelCase，契约割裂。
- 建议：统一依赖全局策略，删除这些冗余注解（或全标全不标）。

### [低] @JsonInclude(NON_NULL) 使用不一致
- 位置：`ResearchStateLedgerResponse`、`ResearchCheckpointStateLedgerSummaryResponse`、`ResearchArtifactCandidateResponse`、`ResearchRunArtifactResponse`、`ResearchResumeContextSummaryResponse`、`ResearchCheckpointSnapshotSummaryResponse` 标注了 NON_NULL；而 `ResearchRunDetailResponse`/`ResearchRunSummaryResponse`/`ResearchCheckpointResponse`/`ResearchClosedLoopStateResponse` 等未标注。
- 影响：同一响应树中，部分子对象 null 字段被省略、部分序列化为 null，前端契约与 `API与事件契约-v2.md` 的字段可见性约定不统一。
- 建议：按契约文档统一策略（推荐全局配置 include 规则，DTO 层不再各自为政）。

### [中] 跨运行时规范化排序不一致：TreeMap 自然序 vs UTF-8 字节序
- 位置：`ResearchAgentCompletionCanonicalizer.java:277`（`new TreeMap<>()` 自然序）与 `ResearchAgentBinaryOrder.UTF8`（MySQL `CAST AS BINARY` 字节序）
- 问题：完成信封 canonical JSON 的 Map 键用 `TreeMap` 的 Java String 自然序（UTF-16 code unit 序）排序，而 cell/merge 排序、DB 排序用 UTF-8 无符号字节序（`ResearchAgentBinaryOrder`）。两者对 BMP 内 ASCII 一致，但对含非 ASCII/增补平面字符的键会分歧。
- 证据：`canonicalize` 用 `TreeMap`；`planMergeOutcome`/`applyVerdictsAndCas`/`buildReceipt` 用 `ResearchAgentBinaryOrder.UTF8`。
- 影响：信封 envelope_digest 的跨运行时可复现性依赖"键均为受限 ASCII"这一隐含前提；一旦通用 `canonicalDigest(Object)`/`canonicalJsonValue` 被用于含非 ASCII 键的内容（如 source_policy 的任意键），Java 端与其他运行时/DB 端排序可能不同，导致摘要不匹配。
- 建议：统一 canonical 排序为 UTF-8 字节序（或明确文档化并强校验所有参与摘要的键为 ASCII 稳定键）。

## ResearchAgentTaskSnapshotCanonicalizer

### [中] 快照 canonical JSON 使用 String 自然序 TreeMap，与 DB/字节序不统一
- 位置：`ResearchAgentTaskSnapshotCanonicalizer.java:82-90`（`canonicalValue` 用 `new TreeMap<>()` 自然序）
- 问题：任务快照摘要（`snapshot_digest`，是 CAS/完成校验的核心权威）同样以 Java String 自然序排序键。`source_policy`/`query_policy`/`execution_context` 的键来自上游任意内容。
- 证据：`raw.forEach((key, child) -> sorted.put(String.valueOf(key), canonicalValue(child)))` 基于 `TreeMap` 默认比较器。
- 影响：若这些 policy map 含非 ASCII 键，Java 生成的 snapshot_digest 与其他运行时的排序结果可能不一致，破坏 `ResearchAgentCompletionCommitter.validateAuthority`（第 339-347 行）"服务器重算摘要 == 存储摘要 == 信封摘要"的三方一致性校验。
- 建议：与全局摘要统一为字节序比较器；对 policy 键做 ASCII/稳定键强校验。

### [低] snapshot_digest 被放进 map 后再次 canonicalValue，两次 Json.write 重复计算
- 位置：`ResearchAgentTaskSnapshotCanonicalizer.java:57-59`
- 问题：先 `Json.write(canonicalValue(snapshot))` 求 digest，放入 `snapshot.put("snapshot_digest", digest)` 后又 `Json.write(canonicalValue(snapshot))` 生成返回 JSON，做了两次全量规范化+序列化。
- 影响：性能开销（小），且两次 `canonicalValue` 对同一结构重复构造 TreeMap 树。
- 建议：缓存首次 canonical 结果，仅追加 digest 字段后单次序列化。

## 数据库迁移 / 表结构

### [中] source_id 列宽在各表不一致（36 vs 64），且跨表引用无 FK
- 位置：`V015__...:24`（`source_evidence.source_id varchar(64)`）、`V014__...:24`（`research_row.source_id varchar(36)`）、`V003`（base `source.id varchar(36)`）、`V014:28`（`research_row.evidence_id varchar(64)`）
- 问题：真实来源 `source.id` 为 varchar(36)（UUID）。`source_evidence.source_id` 却是 varchar(64)，`research_row.source_id` 是 varchar(36)；且这些"引用列"都未建外键。`research_row.evidence_id varchar(64)` 是对 `source_evidence.evidence_key`（varchar(64)）的软引用而非对 id。
- 影响：列宽不统一易踩隐式截断/连接不上；无 FK 使孤儿引用不可被 DB 兜底，一致性完全依赖应用层。
- 建议：统一 source 引用列宽（对齐 36 或明确 64 的理由），补充文档说明软引用语义；评估是否加 FK 或至少加索引。

### [中] V020 记录了已知未完成项：verdict 列已加但读模型未同步
- 位置：`V020__add_research_cell_verdict.sql:12-15`（注释）；`research_cell` 新增 `verdict/verdict_reason/verdict_confidence/verdict_round/verdict_used_llm`
- 问题：迁移注释明确写"Java 端 readStateLedgerResponse / readCheckpointStateLedgerSummaryResponse 需要同步增加 verdict 字段读取(后续 PR 完成)"。这是遗留的读模型缺口。
- 影响：CellVerifier 四态判定（SUPPORTS/PARTIALLY_SUPPORTS/CONTRADICTS/NOT_ENOUGH_INFO）已落库但未在状态账本/检查点摘要响应中暴露，前端/报告拿不到 verdict。
- 建议：确认后续 PR 是否落地；若未，补齐 state ledger / checkpoint summary 的 verdict 读取与 DTO 字段。

### [低] ppm 列 nullable 但业务永远写非空，缺少一致性约束
- 位置：`V045__...:35-79`（`source_evidence.support_score_ppm/conflict_score_ppm`、`research_agent_candidate.confidence_score_ppm`、`research_cell.confidence_score_ppm` 均 `int null`）
- 问题：为兼容历史行设为 null，但新路径（原子完成）必写非空、遗留路径必写空（见上文），DB 无法区分二者，也无 CHECK 约束保证 [0,1_000_000] 范围（校验仅在应用层 `ResearchAgentCompletionCanonicalizer`）。
- 影响：数据完整性依赖应用层；直连 SQL/其他写入点可写入越界或缺失 ppm。
- 建议：范围约束（CHECK ppm between 0 and 1000000）；或对新数据要求 NOT NULL 并以 provenance 列区分历史行。

### [低] research_execution_checkpoint 与 research_agent_checkpoint 双检查点体系易混淆
- 位置：`V015`（`research_execution_checkpoint`：object_key/payload_sha256/content_size/snapshot_type）与 `V040`（`research_agent_checkpoint`：checkpoint_seq/high_water_mark/ledger_hash）
- 问题：两套"检查点"概念并存，DTO `ResearchCheckpointResponse`/`ResearchCheckpointSummaryResponse` 映射前者，MA3C 增量投影用后者，命名高度相似。
- 影响：维护者易混淆两表用途，误接错读模型。
- 建议：命名或包结构上区分（如 snapshotCheckpoint vs agentCheckpoint），文档标注各自归属链路。

## ResearchAgentLifecycleService

### [低] recordDeliveryFailure 先查后插存在并发唯一键竞态且未捕获
- 位置：`ResearchAgentLifecycleService.java:120-132`
- 问题：先 select 判 `existing`，再 insert `research_agent_delivery_failure`；唯一键 `uq_research_agent_delivery_failure_key(task_id, failure_key)`。两并发调用同 failure_key 时，第二个 insert 抛未捕获的 `DataIntegrityViolationException`。
- 影响：本应幂等的失败记录在并发下抛 500 级异常而非幂等返回。
- 建议：insert 包 try/catch DIVE，冲突后重读并返回 `idempotentReplay=true`（与 outbox/candidate 等路径一致）。

### [低] substring 截断可能切断 surrogate pair
- 位置：`ResearchAgentLifecycleService.java:151-153`（`normalizeReason`）、`198-202`（`bounded`）
- 问题：`substring(0, min(N, len))` 按 char 截断，若第 N 个位置处于代理对中间会产生非法半个码点。
- 影响：reason/trace_digest 落库为损坏字符串（低概率，取决于输入）。
- 建议：按码点截断或用 canonicalizer 中已有的 codePoint 计数逻辑。

## ResearchRunService（仅持久化/映射部分）

### [中] SEQUENTIAL 重建路径 delete+重插会重生成所有子行 UUID
- 位置：`ResearchRunService.java:1318-1358`（`persistClosedLoopState`）
- 问题：完成时先 `delete from research_cell_evidence/source_evidence/research_cell/research_verifier_decision/research_row/research_branch`，再全量重插并对每行 `Ids.newId()`。虽已由 `!isIncrementalExecutionMode` 正确隔离（INCREMENTAL_V1 走 append-only，未受影响，隔离设计正确），但 SEQUENTIAL 路径下每次重建这些 persisted id（research_cell.id 等）都会变化。
- 影响：任何外部/前端对持久化行 id 的引用在重建后失效；且这些行的 CAS 列（cell_version/plan_revision/active_task_id）保持 DB 默认 0/null，若之后切到 INCREMENTAL 需依赖这一基线。
- 建议：确认无外部对这些 id 的持久引用；若有，改为按 (run_id, cell_key) upsert 保留 id。删除顺序与 FK 依赖正确，隔离逻辑无误，此项为设计约束提示。

### [低] 数值强转 helper 静默吞错，掩盖脏数据；decimalValue 用 double 绑定 decimal(5,4)
- 位置：`ResearchRunService.java:3636-3670`（`intValue`/`decimalValue`/`booleanValue`）
- 问题：`intValue` 解析失败返回 0，`decimalValue` 返回 null，均静默；`decimalValue` 返回 `Double`（双精度）直接绑定到 `decimal(5,4)` 列（support_score/conflict_score/confidence_score），依赖 MySQL 隐式舍入，且 double 无法精确表示。
- 影响：Worker 传入非法数值被悄悄归零/置空，问题难以定位；double 往返与 ppm 权威值不一致（同前述 ppm 一致性问题）。
- 建议：非法数值应记录 trace 或抛业务异常；decimal 列统一用 BigDecimal 并 setScale(4) 后绑定。

### [低] isIncrementalExecutionMode 在事务内做无锁模式读
- 位置：`ResearchRunService.java:891-894`（被 `completeFromWorker` 832 行调用）
- 问题：判定是否走 append-only 还是 delete-重建的关键分支，基于 `select agent_execution_mode ...`（无 `for update`）。模式切换（877-887）虽要求先取消活跃 agent 任务，但完成与切换并发时仍有理论竞态。
- 影响：极端并发下可能误判执行模式（低概率，因切换有前置约束）。
- 建议：completeFromWorker 中对 research_run 行加锁后再读 mode，与切换路径统一锁序。

## ResearchAgentCompletionCommitter / ReplayRepository

### [优化] "supported" 判定逻辑三处重复实现，存在偏差风险
- 位置：`ResearchAgentCompletionCommitter.java:556-558`（planMergeOutcome）、`587-589`（applyVerdictsAndCas）、`ResearchAgentCompletionReplayRepository.java:283-288`（validateMergeOutcomes）
- 问题：候选是否被接受的核心谓词被写了三遍：plan 版判 `evidence!=null && SUPPORTS && value.equals(claim)`；apply 版判 `SUPPORTS && value.equals(claim)`；replay 版额外加 `quoteText!=null && !isBlank`。虽运行期用 `plannedOutcome.equals(mergeOutcome)`（156 行）和 canonicalizer 对 quote_text 非空校验兜住了当前一致性，但三份实现耦合脆弱。
- 影响：未来任一处修改（如放宽/收紧 support 条件）极易造成 commit 与 replay 判定分叉，触发 `IllegalStateException`/收据校验失败。
- 建议：抽取单一 `isSupported(candidate, evidence)` 纯函数，三处共用。

### [低] Committer 依赖 evidence.claim_text == candidate.candidate_value 的强等值语义未文档化
- 位置：`ResearchAgentCompletionCommitter.java:558`
- 问题：接受合并要求每条证据 `claimText` 严格等于候选 `candidateValue`，这是一个很强的结构约束，但仅体现在代码里，DTO/契约文档（API与事件契约-v2）未见明确说明。
- 影响：Worker 侧若不知此约束，构造的合法证据会被判 NOT_ENOUGH_INFO 全部拒绝，难排查。
- 建议：在完成信封契约文档中明确该等值要求。

## 汇总
以上共 21 条发现（高 1 / 中 8 / 低 10 / 优化 2）。核心风险集中在：遗留(@Deprecated)写入路径与原子路径的 ppm/摘要列不一致、evidence 插入无界递归、快照/信封 canonical 排序与字节序不统一（跨运行时摘要可复现性隐患）、以及若干 DTO 序列化契约不一致。增量 append-only 与 SEQUENTIAL delete-重建的模式隔离经核对实现正确。
