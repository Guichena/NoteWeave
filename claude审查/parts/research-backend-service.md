## Research 后端 · 服务/编排/协调层
（审查发现如下，分批追加）

## ResearchRunService（核心编排，3994 行）

### [高] 终态 Run 可被迟到的 Worker 完成回写覆盖（状态机漏洞）
- 位置：`backend/src/main/java/com/noteweave/research/ResearchRunService.java:818-831`（completeFromWorker）
- 问题：`update research_run set status='COMPLETED' ... where id=?` 没有任何当前状态守卫。
- 证据：`ResearchAgentLifecycleService.cancelRun` 会把 run 置为 `CANCELLED`；此后若同一 run 的 legacy `noteweave.research.run` 任务回调到达，会把 `CANCELLED` 覆盖为 `COMPLETED`。`markRunning`(763)、`markWaiting`(777)、`markFailed`(851) 同样只按 `task_id` 无条件改状态。
- 影响：已取消/已失败的研究任务可被重新翻回 RUNNING/COMPLETED，与真实生命周期不一致，用户看到错误终态与报告。
- 建议：所有状态迁移加 `and status not in ('COMPLETED','FAILED','CANCELLED')`（COMPLETED 幂等回写除外），并对 update 影响行数=0 做显式跳过/告警。

### [高] listRuns 严重 N+1 且无分页/无上限
- 位置：`backend/src/main/java/com/noteweave/research/ResearchRunService.java:140-259`
- 问题：主查询 `where workspace_id=? order by updated_at desc` 无 LIMIT，返回工作区全部 run；且对**每一行**再执行 loadTraces、buildReportStructure、buildClosedLoopState、loadResumeCheckpointSummary(含 loadCheckpointRow)、loadSavedReportSource、loadRunWaitContext、loadSourceOrigins 等一系列查询。
- 证据：单行 RowMapper 内触发十余次二级查询（150-256 行），行数越多查询数线性放大；无游标/分页参数。
- 影响：工作区 run 增多后列表接口查询数爆炸（成百上千次 SQL），响应时间与 DB 负载随数据量线性恶化。
- 建议：分页；把 traces/checkpoints/source 等批量按 runId 集合一次性查出再在内存聚合；列表页只返回摘要字段，重负载放到详情接口。

### [中] loadSourceScopeSnapshot / loadSourceScopeItem 为 N+1 且含相关子查询
- 位置：`ResearchRunService.java:1082-1123`、`resolveRequestedSourceScopeIds:1137-1162`
- 问题：对每个 sourceId 单独查询，每次查询内还有 `sample_text` 相关子查询（取 chunk/window 第一条）。
- 影响：范围最多 20 个 source 时 20 次查询；在 listRuns 内叠加放大。
- 建议：`in (...)` 批量查询 + 一次性取样本，或建立采样物化列。

### [中] 事务内进行对象存储写与重量级解析/索引（事务边界过大）
- 位置：`saveReportAsSource:706-760`（@Transactional），内部调用 `writeResearchReportFile`(storage.write)、`sourceParseService.parseAndIndex`、`wikiIngestService.enqueueAndRunSourceIngestIfEnabled`；`completeFromWorker:816`、`persistExecutionCheckpoint:1675` 亦在事务内 `storage.write`。
- 问题：对象存储写不受事务管理，若事务随后回滚会残留孤儿对象；把解析/索引/Wiki 摄取纳入同一 DB 事务，导致长事务、锁持有时间过长。
- 影响：孤儿存储对象、事务超时、DB 连接与行锁长时间占用，影响并发。
- 建议：存储写与外部重活移到事务提交后（如 outbox/事件或 TransactionSynchronization afterCommit）；报告落资料池拆分为持久化事务 + 异步解析。

### [低] 报告落资料池会重复 bump 目录版本且解析同步执行
- 位置：`saveReportAsSource:742` `sourceCatalogVersionService.bump(workspaceId)`
- 问题：与上条相关，bump 在事务内，且 parseAndIndex 同步阻塞返回。
- 建议：确认幂等（该方法有 report_source_id 幂等短路，尚可），解析异步化。

### [中] persistClosedLoopState 全删后重建（无幂等/无并发保护）
- 位置：`ResearchRunService.java:1308-1371`
- 问题：`completeFromWorker` 对非增量 run 调用此方法，先 `delete from research_cell_evidence/source_evidence/research_cell/research_verifier_decision/research_row/research_branch`（1318-1323）再重建。无 run 行级锁、无版本校验。
- 证据：完全依赖 completeFromWorker 的单次调用；若同一 run 的完成回调被重复投递（legacy outbox 至少一次语义）且未被幂等短路，会二次全删重插，期间读接口可能读到半截数据。
- 影响：重复/并发完成时的数据抖动；与增量 INCREMENTAL_V1 表（research_agent_* / source_evidence）共用 source_evidence 表，删除范围 `where research_run_id=?` 可能误删增量链路写入的 evidence（虽有 isIncrementalExecutionMode 守卫跳过重建，但删除逻辑仅在非增量分支，风险有限）。
- 建议：completeFromWorker 增加终态幂等短路（见状态机条）；重建前锁 run 行；或改增量 upsert。

### [优化] completeFromWorker 缺少对已完成 run 的幂等短路
- 位置：`ResearchRunService.java:810-848`
- 问题：无 “已 COMPLETED 则直接返回” 逻辑，重复回调会重复写 trace、重复 persistClosedLoopState。
- 建议：入口检查当前 status，若已 COMPLETED 且报告一致则幂等返回。

## ResearchAgentCommandDispatcher（MA4/MA4F 命令发布）

### [高] 发布无 claim/租约保护，多实例/双入口并发会重复发布
- 位置：`backend/src/main/java/com/noteweave/research/ResearchAgentCommandDispatcher.java:56-68`（publish）
- 问题：`publish()` 非事务，先 SELECT READY 行，再逐条 `publisher.publish(...)`，最后用 `delivery_no + status='READY'` CAS 标记 SENT。发布与选择之间没有行级 claim/lease。
- 证据：全局 scheduler `dispatchReady` 与灰度端点 `dispatchReadyForRun` 可同时运行；多个后端实例也会各自 SELECT 到同一批 READY 行并各自 publish。CAS 只保证 SENT 只标记一次，但**publish 已对两者都执行**，产生重复 Kafka 消息。
- 影响：命令重复投递（至少一次），依赖 Worker 侧 claim 竞态/幂等兜底；若消费端幂等有缺陷会重复执行工具链。
- 建议：发布前用 `update ... set status='SENDING', lease_owner=?, lease_until=? where id=? and status='READY'` 抢占（类似 worker 的 ResearchOutboxDispatcherService），只发布抢占成功的行。

### [中] publish 异常中断整批且无投递失败记账/退避
- 位置：`ResearchAgentCommandDispatcher.java:58-66`
- 问题：`publisher.publish` 抛异常时未捕获，整个 for 循环中断，剩余 READY 行本轮不再处理；且没有 attempt 计数、错误落库或退避（对比 worker 版 ResearchOutboxDispatcherService 有 attempt_count/DEAD_LETTER）。
- 证据：设计 MA4F 只要求“publish 异常时 delivery 保持可重试”，但未做批内隔离与失败可观测。
- 影响：一条持续失败的 command 会在每次调度阻塞其后同批行；无死信、无告警。
- 建议：try/catch 单条隔离，记录失败与退避 `next_attempt_at`，达到阈值转 DLQ/标记。

## worker/ResearchOutboxDispatcherService（legacy run 级 Kafka 投递）

### [中] publish 成功后标记 SENT 失败会重复投递（至少一次，需消费端幂等）
- 位置：`backend/src/main/java/com/noteweave/worker/ResearchOutboxDispatcherService.java:66-73`
- 问题：先 `publisher.publish`，再 `update ... status='SENT'`。若 publish 成功但进程在 SENT 更新前崩溃，行保持 PROCESSING，租约到期后被重新领取再次 publish。
- 影响：重复的 research-run 命令；依赖 research 消费端幂等。属正常 at-least-once，但需明确并保证消费幂等。
- 建议：确认下游按 task_id/trace_id 幂等；文档标注该语义。

### [低] 领取递增 attempt_count 与 catch 内 attempt 计算依赖读时值
- 位置：`ResearchOutboxDispatcherService.java:52-90`
- 问题：claim `update ... attempt_count=attempt_count+1`，而失败分支用 `row.attemptCount()+1` 计算 exhausted。二者在正常路径一致，但当 PROCESSING 行被并发/重复处理时，读时 attemptCount 可能与库内不同步。
- 影响：极端并发下 DEAD_LETTER 判定可能偏差一次。当前 CAS(`status='PROCESSING' and lease_owner=?`) 基本可控。
- 建议：失败更新直接用库内 `attempt_count` 判定（`case when attempt_count>=MAX ...`），不依赖读时值。

### [优化] failTask 在 catch 分支中独立于 outbox 更新调用，缺乏原子性
- 位置：`ResearchOutboxDispatcherService.java:87-90`
- 问题：DEAD_LETTER 后 `taskService.failTask(...)` 与前面的 outbox update 非同一事务；两者之间崩溃会出现 outbox 死信但 task 未失败。
- 建议：将死信标记与 task 失败纳入同一事务或做补偿对账。

## ResearchAgentTaskService（MA3B 租约生命周期）

### [中] 死代码：readMap / canonicalize / sha256 未被使用
- 位置：`ResearchAgentTaskService.java:380-422`（readMap 381、canonicalize 401、sha256 413）与 `isRunnableIncrementalRun:469-476`
- 问题：grep 确认 readMap/sha256/isRunnableIncrementalRun 仅有定义无调用；canonicalize 仅自递归无外部调用。快照摘要已迁移至 ResearchAgentTaskSnapshotCanonicalizer。
- 影响：误导维护者，增加攻击面/维护成本。
- 建议：删除。

### [中] enqueueRetry 读改无行锁，delivery_no 递增存在竞态
- 位置：`ResearchAgentCommandOutboxService.java:41-53`（enqueueRetry）
- 问题：`findByTaskId` 读 existing.deliveryNo() 后 `update ... delivery_no=nextDelivery`，非 `for update`。两个并发 retry 读到相同 delivery_no 会并发 +1 到同值。
- 影响：并发重投时 delivery_no 可能不单调；MA4F 依赖 delivery_no CAS 防旧发布覆盖新 retry，重复 delivery_no 会削弱该保护。
- 建议：`select ... for update` 锁定该 outbox 行后再递增，或用 `delivery_no=delivery_no+1` 原子自增并回读。

### [低] submitExecution 与 /submit 端点为 @Deprecated(forRemoval) 但仍可达
- 位置：`ResearchAgentTaskService.java:179-228`、`ResearchAgentTaskInternalController.java:64-74`
- 问题：MA4G 已用原子 complete 取代；旧 submit 仅靠拦截器/rejectSplitCompletionIfRequired 阻挡 atomic 任务，legacy 任务仍走旧路径。
- 建议：确认无生产调用后移除，减少双写路径。

### [低] createTask 幂等冲突后重新抛 DataIntegrityViolationException（500）
- 位置：`ResearchAgentTaskService.java:80-86`
- 问题：唯一约束冲突且非同一 taskKey 时 `throw duplicate`（原始 DataIntegrityViolationException），未转 BusinessException。
- 影响：返回 500 而非可读业务码；同类问题见 budget.reserve:57、cellMerge.submitCandidate:80、outbox.createDelivery:69。
- 建议：统一包装为幂等冲突业务异常。

## ResearchAgentTaskCoordinatorService（MA4E 协调器）

### [中] loadTrustedSources 为 N+1 且整段在持有 run 行锁的事务内
- 位置：`ResearchAgentTaskCoordinatorService.java:93-112`、`planAndEnqueue:40-80`
- 问题：`requireIncrementalRunnableRun` 已 `select ... for update` 锁住 research_run；随后对每个 source 单独查询（含 sample_text 相关子查询），再循环 createTask/reserve/enqueue。
- 影响：长事务持有 run 行锁，阻塞该 run 上的 claim/complete/cancel 等操作；source 多时放大。
- 建议：source 批量查询；锁范围最小化，或先只读校验、写入阶段再锁。

### [低] created/replayed 计数以“预留是否已存在”判定，语义不准
- 位置：`ResearchAgentTaskCoordinatorService.java:72-76`
- 问题：`existed = count(research_budget_reservation)>0` 用来区分 created/replayed，而非依据 task 是否新建。task 幂等命中但预留缺失时会被计为 created。
- 影响：回执统计不准确（仅指标）。
- 建议：以 createTask 回执的幂等标志判定。

### [优化] entityId 以 cellKey 首个 ':' 前缀切分，隐式契约
- 位置：`ResearchAgentTaskCoordinatorService.java:127`
- 问题：`cellKey.indexOf(':')` 解析 entity 作用域，格式约定隐式；非法格式抛 SCOPE_INVALID。
- 建议：文档化 cell_key 结构或在写入侧强校验。

## ResearchBudgetAndCheckpointService（MA3C 预算/检查点）

### [中] 成功任务未用预算不回收（预算守恒缺口，设计已知）
- 位置：`settle:61-81`、`settleForExecution:84-107`；对照 `docs/ResearchAgent-MA4F-...md:174`
- 问题：settle 计算 `released = reserved - consumed` 并写 released_json，单条预留守恒 OK；但设计文档指出：成功 task/Run 完成时缺少 Run 级“reserved = consumed + released”收口断言与未用额度统一回收，lifecycle release 仅覆盖取消/失败。
- 影响：Run 级预算账目可能无法在成功路径闭合，难以对账。
- 建议：定义成功路径的 Run 级预算守恒收口与最终断言。

### [低] appendCheckpoint 高水位仅校验不回退但无并发序号锁
- 位置：`appendCheckpoint:176-198`
- 问题：`lockRunnableRun` 锁 run 行，latestCheckpoint 取最大序号 +1 插入。锁 run 行可串行化同一 run 的 checkpoint，尚可；但跨 run 无问题。属正常。
- 建议：无需改动，标注确认。

## ResearchAgentCellMergeService（MA3A 合并授权，legacy）

### [中] hasExactEvidenceBinding 用 count(*) 对比 size，重复 evidence_key 会误判
- 位置：`ResearchAgentCellMergeService.java:206-218`
- 问题：`count(*) ... evidence_key in (...)` 与 `candidate.evidenceIds().size()` 比较；若 evidenceIds 含重复 key，count（去重匹配行）会小于 size，导致本应成立的绑定被判 EVIDENCE_BINDING_INVALID。
- 影响：合并被误拒。
- 建议：先去重再比较，或 `count(distinct evidence_key)` 与去重后 size 比较。

### [低] mergeCandidate 无 run/mode 守卫，可对任意 run 合并（legacy 路径）
- 位置：`ResearchAgentCellMergeService.java:84-146`
- 问题：不校验 run 是否 INCREMENTAL_V1/是否终态；仅靠 CAS 谓词（active_task_id/lease/version）。MA4G 已用原子 committer 取代该路径。
- 影响：legacy 路径与原子路径并存，存在被绕过风险；CAS 谓词提供了主要保护。
- 建议：确认调用方（仅 ResearchAgentCandidateIngressService，已 @Deprecated 且 rejectAtomicSplitCompletion 拦截 atomic 任务）后整体退役。

### [低] submitCandidate confidence 用 double 存 BigDecimal，精度隐患
- 位置：`ResearchAgentCellMergeService.java:70,244`
- 问题：`BigDecimal.valueOf(command.confidence())` 与读回 `.doubleValue()`，double 往返可能精度漂移；对照 committer 用 ppm 整数。
- 建议：统一改用 ppm 整数存储。

## ResearchAgentLifecycleService（MA4B 回收/取消）

### [中] cancelRun 未取消 legacy task_outbox，仅取消 research_agent_outbox
- 位置：`ResearchAgentLifecycleService.java:82-114`
- 问题：取消 run 时把 research_agent_task/research_cell/research_agent_outbox 处理干净，但未处理 legacy `task_outbox`（run 级 Kafka 命令）里 READY/PROCESSING 的行。
- 影响：取消后 legacy run 命令仍可能被 ResearchOutboxDispatcherService 投递并触发 Worker 执行，与取消语义冲突。
- 建议：cancelRun 同步取消该 run 对应 task 的 task_outbox READY 行，并置 task 终态。

### [低] reapExpiredLeases 与 TaskService.expireLeases 双份过期实现
- 位置：`ResearchAgentLifecycleService.reapExpiredLeases:25-79` 与 `ResearchAgentTaskService.expireLeases:144-177`
- 问题：两处各自实现租约过期（一处置 EXPIRED，一处置 RETRY_WAIT/FAILED 并释放预算+enqueueRetry），语义不同且都对外暴露（/expire 端点 vs reaper scheduler）。
- 影响：并存两套过期状态机易产生不一致（EXPIRED vs RETRY_WAIT），维护混乱。
- 建议：统一为单一 reaper 权威路径，废弃另一个。

### [低] backoffSeconds 移位边界
- 位置：`ResearchAgentLifecycleService.java:147-149`
- 问题：`1L << Math.min(6, max(0, attempt-1))`，attempt=1 → 1<<0=1s，上限 64→clamp 60s，正常；但 attempt 由调用方传入，逻辑无 attempt 上界外的问题。属正常，标注确认。

## ResearchAgentRateLimitService（MA4C 限流）

### [中] Redis 令牌桶脚本按浮点 refill 累加，低速率补充精度依赖浮点
- 位置：`ResearchAgentRateLimitService.java:18-35,60-64`
- 问题：Lua 用 `(now-updated)*refill`，refill=refillPerMs（默认 10/min ≈ 0.000167/ms），tokens 以浮点字符串 HSET。多键（provider/workspace/run）任一 <1 即整体拒绝，脚本先在 values[] 暂存再统一 HSET/PEXPIRE，是原子的。
- 影响：低速率配置下补充精度依赖浮点，长期存在小偏差；多维度耦合拒绝符合设计。
- 建议：确认 refillPerMinute 与 capacity 匹配；如需严格用毫秒整数累加器。

### [低] allowLocalFallback 在 Redis 不可用时直接放行
- 位置：`ResearchAgentRateLimitService.java:81-85`
- 问题：`allowLocalFallback=true` 时 Redis 不可用直接 return 放行且不计限流（开发逃生口）；默认 false 时 fail-closed 抛 503。
- 影响：若误在生产开启则限流失效。
- 建议：生产 profile 下禁止该开关或启动告警。

## ResearchAgentEvidenceIngestionService / CandidateIngressService（MA4D-2B，均 @Deprecated）

### [中] appendOne 递归重试无深度限制（DataIntegrityViolation 兜底）
- 位置：`ResearchAgentEvidenceIngestionService.java:52-80`
- 问题：insert 冲突时 `return appendOne(scope, evidence)` 递归重试。若冲突源不是可幂等化解的重复而是持续存在，理论上可无限递归/栈溢出。
- 影响：极端情况栈溢出；正常情况下第二次进入会命中 existing 分支返回，故风险低。
- 建议：改为有限循环重试或直接依赖 existing 判定，不递归。

### [中] structuralVerdict / hasExactEvidenceBinding 动态 in 列表未去重
- 位置：`ResearchAgentCandidateIngressService.java:101-114`、evidence 校验
- 问题：`evidence_key in (...)` 用 proposal.evidenceKeys 原样拼占位符，`evidence.size()!=evidenceKeys.size()` 判定；重复 key 时 DB 去重导致 size 不等而误判 NOT_ENOUGH_INFO。
- 建议：去重后比较。

### [低] 两个 ingress 服务共用 source_evidence 表且与 committer 语义并行
- 位置：Evidence/Candidate ingress + committer.appendEvidence
- 问题：三处向 source_evidence 写入，字段/幂等键（evidence_key）语义需严格一致；committer 用 content_digest + agent_completion_id，legacy 无这些列值（NULL）。
- 影响：混用时 evidence_key 冲突检测（committer.lockAndRejectChildKeyConflicts）会把 legacy 写入视为“已被占用”而拒绝原子完成。属设计意图（防串写），但需确保同一 run 不混用两种路径。
- 建议：文档与运行时强制单路径。

## ResearchAgentCompletionCommitter（MA4G 原子完成，核心）

### [低] 释放绑定行数断言可能因非目标 cell 被绑定而误报
- 位置：`ResearchAgentCompletionCommitter.java:179-186`
- 问题：完成末尾 `update research_cell set active_task_id=null where research_run_id=? and active_task_id=?` 期望影响行数 == targets.size()，否则抛 SNAPSHOT_STALE。若历史上有超出 target 集合的 cell 被同一 task 绑定（理论上 bindClaimedTargetCells 只绑 target），行数不符会误判。
- 影响：正常路径一致；绑定/释放不变量若被其他路径破坏则完成失败。属强不变量校验，可接受。
- 建议：保留断言，同时确保只有 bindClaimedTargetCells 写 active_task_id。

### [优化] appendEvidence 对每条 evidence 单条 insert（小批量可接受）
- 位置：`ResearchAgentCompletionCommitter.java:459-471`
- 问题：evidence≤12、candidate≤3，循环单条 insert。规模小，无需批处理；但故障注入 checkpoint 在每条后触发。
- 建议：规模受限，维持现状。

### [中] 该类为 package-private @Service 且逻辑近千行，职责集中
- 位置：`ResearchAgentCompletionCommitter.java` 全类
- 问题：单事务内完成锁序、鉴权、预算、evidence/candidate/merge/cell CAS、outbox 取消、绑定释放，圈复杂度极高，测试与演进成本大。
- 影响：可维护性；正确性依赖大量隐式不变量。
- 建议：拆分为鉴权校验器、预算结算器、合并执行器等协作组件（保持同一事务）。

## 跨类 / 编排整体

### [高] INCREMENTAL_V1 Run 缺少端到端收口：task 完成后不自动推进 Run 终态（设计已知）
- 位置：对照 `docs/ResearchAgent-MA4F-...md:178`；代码中无协调器在所有 agent task 完成后触发下一轮 taskization/global verify/final report/Run terminal transition。
- 问题：MA4F 只完成 task pipeline，agent task 全部 SUBMITTED 后，research_run 停留在非终态，无自动 checkpoint/收口/报告生成。
- 影响：增量模式下 Run 永不自动完成，需人工或未实现的后续协调器；对用户表现为“卡住”。
- 建议：实现协调器收口循环（依据 canonical cells 与预算决定继续/冻结/完成），并保证重放幂等。

### [中] crash 窗口：cell CAS 成功后 submit 前崩溃在 legacy 拆分路径不可恢复（设计已知）
- 位置：对照 `docs/ResearchAgent-MA4F-...md:162,93`
- 问题：legacy DeepCellExecutor 顺序为 evidence/candidate merge（可能已推进 cell version）后才 submit；此窗口崩溃后新 epoch 因 snapshot stale 无法重放。MA4G 原子路径已消除该窗口（单事务），但 legacy 路径仍在。
- 影响：若仍使用 legacy 拆分路径，存在不可恢复的中间态。
- 建议：全面切换原子完成路径并退役拆分写端点。

### [中] 长调用期间无心跳续租，健康 Worker 可能被 reaper 抢占（设计已知）
- 位置：对照 `docs/ResearchAgent-MA4F-...md:166`；heartbeat API 存在（TaskService.heartbeat）但 Kafka DeepCellExecutor 未在长 Search/Fetch/Read/LLM 调用期间周期续租。
- 影响：真实 provider 延迟时租约到期被回收，任务被重复执行、预算浪费。
- 建议：Worker 增加后台 heartbeat loop + 长调用接管测试。

### [低] 大量 @Deprecated(forRemoval) 双写路径与 legacy/atomic 并存
- 位置：submit、candidate-batches、workspace-evidence-batches、CellMergeService、两个 ingress 服务、拦截器/过滤器守卫。
- 问题：为拦截 atomic 任务走旧路径，引入拦截器+原始体过滤器+多处 rejectAtomicSplitCompletion 计数查询，复杂度高。
- 影响：维护面大、易出现守卫遗漏。
- 建议：确认无生产流量后整体删除 legacy 写端点及其守卫。

## 校验 / 拦截器 / 其他

### [中] 拦截器 taskSchema 每请求查询 DB，且对多 task_id 逐个查询
- 位置：`ResearchAgentLegacyResultRouteGuardInterceptor.java:72-74,182-195`
- 问题：preHandle 对 body 中每个 task_id 调用 taskSchema（各一次 DB 查询），在 MVC 转换前执行。
- 影响：每个 legacy 写请求增加 1..N 次 DB 往返；恶意/畸形 body 若含多个 task_id 会放大（虽有 duplicate 提前拒绝，但 lexical 扫描上限 2）。
- 建议：合并为单次 `in (...)` 查询；对 legacy 端点整体退役后可移除。

### [低] 原始体过滤器与 completion 控制器各自实现 bounded 读，逻辑重复
- 位置：`ResearchAgentLegacyResultRawBodyFilter.java:67-94` 与 `ResearchAgentCompletionInternalController.java:37-58`
- 问题：两处独立实现 MAX_PAYLOAD_BYTES bounded 读取，易分叉。
- 建议：抽取公共 bounded-body 工具。

### [低] ResearchAgentRateLimitInternalController.positiveLong 不接受 JSON 大整数
- 位置：`ResearchAgentRateLimitInternalController.java:49-53`
- 问题：fencing_token 仅接受 Integer/Long；若 JSON 反序列化为超 int 的数值可能为 Long（OK）或 BigInteger（抛 invalid）。fencing_token 可能增长较大。
- 影响：极端大 fencing_token 请求被误判非法。
- 建议：兼容 BigInteger 或 Number 统一转换。

---

发现统计：共 33 条 —— 严重 0，高 4，中 15，低 12，优化 2。
（高：completeFromWorker 终态覆盖、listRuns N+1 无分页、CommandDispatcher 无 claim 重复发布、INCREMENTAL_V1 无端到端收口）
