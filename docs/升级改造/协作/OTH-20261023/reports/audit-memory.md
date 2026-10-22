# NoteWeave 记忆反馈组静态核实

## 0. 范围、基线与证据等级

- 核实基线：`70631bd015067b19f9d55aaec92d7429751dc936`；开发分支 `codex/oth-20261023`，服务器开发目录 `/data/workspace/noteweave-oth-20261023`。本报告读取同提交本地只读快照，不修改源码。
- 范围：OTH-02、OTH-06、OTH-07、OTH-03 的反馈判断/围栏子项。没有运行测试、付费模型、部署、线上写入，也没有创建嵌套 agent。
- 已读 [规范](<../../../../../AGENTS.md>)、[执行提示](<../../../../../docs/升级改造/协作/AI执行提示词-其他改造线路-2026-10-22.txt>)、[其他线接手说明](<../../../../../docs/升级改造/协作/AI接手说明-其他改造线路-2026-10-22.txt>)、[任务总账](<../../../../../docs/升级改造/协作/任务总账-2026-10-22.json>)、[Research 共享边界](<../../../../../docs/升级改造/协作/AI接手说明-Research改造线-2026-10-22.txt#L25-L47>)。
- 当前代码及现存测试是静态证据；历史通过数不是本轮通过数。主 agent 提供的生产只读聚合结果单列，不冒充本子 agent 亲查生产。
- 历史提交元信息与祖先关系已有主 agent 保存的 [历史证据:13–37](<history-evidence.txt#L13-L37>)：`7cc66966aa120821ab03feeb80b1c082f12e1065`、`43d638a2797bf2989ec95cc88763ee82ba8ab9db` 均为当前 HEAD 祖先；`baf8bcc` 亦在 [历史证据:1–12](<history-evidence.txt#L1-L12>) 确认。原始 `REVIEW-d10.md` 不在快照，不能声称读过原文。

## 1. 逐项分类

| 子项 | 分类 | 当前事实与触发条件 | 处理边界 |
|---|---|---|---|
| OTH-02 点赞点踩长事务 | 已确认需要修复 | HTTP 外部 bean 调用进入事务代理；锁住 answer_run 后调用最多两次模型。非空 reason、模型启用且慢时占连接与行锁 | 统一由记忆反馈负责人拆分，禁止把方法拆成同类自调用的注解事务 |
| OTH-02 版本/所属重新校验 | 已确认需要修复 | 原方法依赖持续行锁，没有独立快照版本检查；拆分后必须补校验，不能只把模型向前挪 | 绑定 run→message、workspace、usage 注入版本、选中目标与相关反馈状态；模型后重新验证 |
| OTH-03 反馈输入无围栏 | 已确认需要修复 | 判断器仍将原始正文直接拼到 user prompt；合法枚举仍可能是受正文指令影响的错误结论 | 使用既有围栏，系统提示明确数据语义；不加关键词黑名单 |
| OTH-03 判断器异常日志 | 已修复并有证据 | 只打印受限原因码、异常类型、attempt，不打印异常消息/堆栈 | 保留现状和日志测试，不重做；TopicBoundaryJudge 属另一负责人 |
| OTH-03 现有围栏转义 | 仅需验收 | 已转义两类关闭标签及大小写/空白变体；反馈判断器尚未复用 | 优先复用，不因本任务重写共享围栏行为 |
| OTH-06/R4 默认 REQUIRED | 仅需验收 | 当前唯一生产消费者无外层数据库事务；跟进反馈完成后才开抽取持久化事务，默认 REQUIRED 实际创建独立短事务 | 不机械改 REQUIRES_NEW；补真实入口事务测试，明确禁止外层事务调用的契约 |
| OTH-06/R5 冲突返回值 | 已修复并有证据 | 7cc6696 的三种乐观冲突和无结果返回 RETRYABLE，已写来源仍 SKIPPED | 必须保留；只修恢复闭环及并发分支，不把 R5 重列为未开发 |
| OTH-06/R6 冲突粒度 | 已确认需要修复 | 任一 usage 的 outcome_type、slot、revision 或行集变动令全批失败，包括负面 LENGTH 的非目标 terminology 行 | 根据相关目标集合检查，相关变动仍 RETRYABLE，不能直接取消全部快照校验 |
| OTH-06 可重试恢复闭环 | 已确认需要修复 | retryable 只写事件；消费者正常返回即 ack；唯一生产 redrive 入口只接受 Outbox DEAD_LETTER，已 SENT 的语义失败无可达重投 | 与主 agent/Outbox 单一负责人协调，不能擅改共享消费者/任务模块 |
| OTH-06 并发已有 signal 分支 | 已确认需要修复 | extract 首次无 signal，模型期间另一线程写入；persist 再查已有 signal 时直接 completeTask，忽略本轮 followUp RETRYABLE | 记忆抽取负责人修，验收不得只覆盖顺序重复投递 |
| OTH-07 来源数据库唯一性 | 已确认需要修复 | 来源查询只有普通索引，预检在锁前；RETRY 同源并发可重复扣效用；FOLLOW_UP 无目标或已存在 outcome 的目标可重复写 | 优先来源级持久化收据/互斥，不在事件表直接加全局 source_ref 唯一 |
| OTH-07 历史重复迁移 | 缺少资料待核实（生产已部分消除） | 主 agent 汇报生产 outcome 行数 0、V192；未直接读取其他环境，未来迁移前须重新统计 | 生产空表可简化，不等于所有环境允许无条件加约束或删数据 |
| 版本与顺序产品口径 | 缺少产品决定 | 反馈应评价注入 revision 还是当前 revision；并发最后一次按到达、提交还是用户序号；目前无请求版本/序号 | 不阻塞围栏/慢模型改造；默认保留原有注入版本记录与提交顺序，若改变则须明确决定 |
| EDIT 产品调用接入 | 缺少资料待核实 | 服务方法及契约测试存在，全生产 Java 调用搜索未发现 recordArtifactEdit 调用方 | 不扩大本次到产物保存功能；请主 agent 放入 OTH-15 的真实流程核实 |

## 2. OTH-02 完整调用链与锁/写入事实

### 2.1 HTTP、代理、权限、模型

1. [ChatController:151–158](<../../../../../backend/src/main/java/com/noteweave/chat/ChatController.java#L151-L158>) 的 POST `/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/feedback` 使用构造注入的另一 Spring bean；注入点见 [ChatController:44–64](<../../../../../backend/src/main/java/com/noteweave/chat/ChatController.java#L44-L64>)。这是真实跨 bean 调用，不存在同类自调用绕过事务的问题。
2. [请求 DTO:7–13](<../../../../../backend/src/main/java/com/noteweave/chat/AnswerFeedbackRequest.java#L7-L13>) 对 reason 限 500 字符并 trim；rating 限 16 字符并大写。HTTP `@Valid` 生效，直接内部调用不自动具有 DTO 校验。
3. [反馈服务:80–96](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L80-L96>) 的 `@Transactional` 默认 REQUIRED 由外部 bean 调用生效，先调用权限检查，再验证 rating、读取 run。权限检查 [WorkspaceAccessGuard:41–64](<../../../../../backend/src/main/java/com/noteweave/security/WorkspaceAccessGuard.java#L41-L64>) 验证当前用户、workspace ACTIVE、ACL 版本与 ANSWER_RUN 权限。
4. [requireAnswerMessageId/optionalAnswerMessageId:596–618](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L596-L618>) 先按 `(workspace_id,id)` count，再 `SELECT answer_message_id ... FOR UPDATE`，锁 answer_run；禁用反馈或无 answer message 的早退也发生在此锁查询之后。
5. 随后 [反馈服务:96](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L96>) 调 [判断器:93–126](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java#L93-L126>)，最终 `ChatLlmClient.streamChat(PROMPT,user,600,...)`；最多两次调用。blank reason 不调模型；启用且非空时数据库事务/answer_run 行锁持续到两次模型结束及写入提交。

### 2.2 usage、事件、计数、版本

- 模型返回后 [反馈服务:97–140](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L97-L140>) 才锁 usages，再按方面选目标，再锁相同回答活跃 RATING 事件。
- [USAGE_SELECT:636–665](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L636-L665>) 按 workspace、target type/id 过滤 usage，join item，按 usage id 排序，加 `FOR UPDATE` 时可能同时锁 join 匹配的 memory_item，具体锁范围由 MySQL 执行计划/隔离级别决定。查询没有限制 item workspace；后续 [lockItem:710–735](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L710-L735>) 再按 workspace+item id 校验，无匹配即抛异常使事务回滚。不能据此声称能跨 workspace 成功更新，但应在短事务快照读取时显式建立归属一致性。
- [loadActive:668–707](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L668-L707>) 只取 `superseded_at IS NULL`，按 row_no 加锁。[sameRating:569–584](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L569-L584>) 比较 outcome+reason+aspect 及 item id 集；相同内容保持幂等。
- 不同评分先 [reverse:375–397](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L375-L397>) supersede，若无后续效用事件则恢复 before 值，否则扣除历史 delta 并报告 UTILITY_ADJUSTED；退回 beta，清 usage。不能把历史行删除来替代撤销。
- [apply:340–372](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L340-L372>) 逐 usage 锁 item，调用纯政策，分配 row_no，insertEvent→writeItem→writeUsage→addBeta。空目标 [insertAnswerLevel:400–419](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L400-L419>) 仍保存回答级事件。
- [insertEvent:439–482](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L439-L482>) 记录 usage 上的 memory_revision_id，而非当时 item.current_revision_id；[writeItem:737–766](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L737-L766>) 更新 canonical item 及 legacy mirror。[writeUsage:836–852](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L836-L852>) 有 `outcome_type IS NULL` 条件，但不检查影响行数；因此 usage 已有 outcome 并不会阻止 apply 继续新增事件和更新效用。不能把 usage 条件当作来源幂等保证。
- run 自带 version，见 [V030:1–30](<../../../../../backend/src/main/resources/db/migration/V030__create_answer_run_tables.sql#L1-L30>)；生成映射更新会推进它，见 [AnswerRunService:136–146](<../../../../../backend/src/main/java/com/noteweave/answer/AnswerRunService.java#L136-L146>)。item 自带 lock_version/current_revision_id，见 [V080:1–19](<../../../../../backend/src/main/resources/db/migration/V080__create_canonical_memory_runtime.sql#L1-L19>)。本反馈服务既不读 run.version，也不读/推进 item.lock_version；当前安全主要依赖数据库锁。未来只检查 lock_version 不足以检测本服务的并发反馈。

### 2.3 最小修复快照与步骤

1. 非事务入口先验证权限/rating/存在性，不获取 FOR UPDATE；读 `(workspaceId,runId,answerMessageId,run.version)` 及所需 usage/相关反馈状态。判断模型实际仅需 reason，因此不应为了模型读取任何业务正文。
2. 模型调用全程事务外。使用项目现有 TransactionTemplate 执行短提交，或新独立 bean 公开事务写方法；不要在同一 bean 内调用另一个 `@Transactional` 方法。
3. 短事务重新校验权限状态与资源所属、run→answer 映射及相关版本，按统一顺序锁当前目标/usage/item，复核幂等及活跃事件。run.version 如果包含与评分无关的流状态更新，应区分语义相关变化，不机械拒绝一切版本推进。
4. 快照过期不能覆写新评分；返回明确可重试冲突，或有界重新规划。为 HTTP 评分选择明确冲突响应，不冒用后台 task 的 RETRYABLE 枚举直接作为 API 成功。
5. 保存原有 last-rating、reverse/adjust、beta、legacy mirror、CONTENT 不惩罚偏好、UNKNOWN 半权等政策。任一步失败整体回滚，包括收据占位。
6. item 当前版本与历史注入版本不一致时，先按现有历史注入 revision 记录保留语义；如要求只评价当前 revision，必须说明历史回答如何归因，不把版本不相等静默 SKIPPED。

## 3. OTH-06 真实外层事务、冲突与恢复

### 3.1 当前调用链没有覆盖模型的外层数据库事务

- 唯一生产调用 [KafkaTaskConsumer:302–327](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java#L302-L327>)：`onMemoryExtraction → handle → memoryExtractionService.extract`。
- [handle:339–356](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java#L339-L356>) 只解析 payload、设置 MDC、调用 handler；没有数据库事务。指定 factory 在 [KafkaConfig:196–244](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaConfig.java#L196-L244>) 手动创建，没有设置事务管理器，AckMode 为 RECORD。不能把 KafkaListener 当作数据库事务入口。
- [extract:218–224](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L218-L224>) 无注解/外层 execute，先抽取模型，再进 persistExtraction。[persistExtraction:272–288](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L272-L288>) 先调用跟进反馈，结束后才 execute 持久化抽取。
- 跟进反馈 [recordFollowUp:181–191](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L181-L191>) 的 `planFollowUp` 非事务读→模型；仅 `applyFollowUp` 放在 template。template 构造 [76](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L76>) 没设传播，默认 REQUIRED。当前调用线程无事务，因此它创建并提交真实短事务；后面的 extraction template 是另一个事务。
- `resumeExtractedMessage` 也是先反馈，再决定标失败或开短完成事务，见 [236–250](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L236-L250>)。
- REQUIRED 在已有事务中才参与外层；REQUIRES_NEW 只会挂起外层并另取连接执行写区，不会让先于模板的模型调用脱离外层，也不释放外层已持锁。未来新增事务调用方可能造成模型带事务、内层等外层锁、自等待/连接池压力。建议用入口禁止已有数据库事务的契约及集成断言，而非无证据增加 REQUIRES_NEW。
- 不混淆再生成路径：[ConversationTurnModule:158–171](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTurnModule.java#L158-L171>) 的 finalize 外层事务经 [361、390–398](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTurnModule.java#L351-L398>) 调另一个 bean `recordRegeneratedFrom`；该方法 [148–166](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L148-L166>) 的 REQUIRES_NEW 真会挂起 finalize，反馈独立提交，失败由调用方捕获。这里没有反馈模型调用。

### 3.2 R6 既过粗，也不是完整反馈版本

- [planFollowUp:199–231](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L199-L231>) 记录全 usages；[applyFollowUp:239–273](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L239-L273>) 重查来源/前一回答、锁全 usages，然后 `locked.equals(plan.usages())`。
- 比较字段由 [UsageRow:1026–1034](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L1026-L1034>) 决定：usage id、item id、注入 revision id、target type/id、outcome_type、semantic_slot_key；不包含 outcome_score/note/active event id、item 当前 revision/status/lock_version。
- 明确触发：模型判 NEGATIVE LENGTH，只有 length 目标；另线程更新 terminology usage.outcome_type，仍导致 USAGE_SNAPSHOT_CHANGED 整批 RETRYABLE。无关字段变化并非都冲突，例如 note/score 单独变化不会被发现，因此不能表述为任何 usage 字段变化都冲突。
- 更细检查应重算相关目标集合，比较目标的身份/注入版本/相关活跃反馈状态；非目标被改为目标、目标新增/删除仍应冲突。ACCEPTED 覆盖所有未 signaled 目标，不能把其全部旧行都定义为无关。alreadySignaled 的目标变化与 UNKNOWN 全体目标都需保留保护。
- 同 outcome_type 的评分变化或已有 outcome 的重复事件可能不改变 UsageRow；现有 `equals` 不等于完整活跃反馈版本检查。建议来源级幂等先落稳，再定义反馈 head/version，避免仅用 outcome_type 代表所有反馈。

### 3.3 7cc6696 保留，恢复闭环补齐

- 历史与当前实现相符：[反馈服务:244–284](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L244-L284>) 返回三种 RETRYABLE；重复已存在来源 [240–242](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L240-L242>) 仍 SKIPPED。日志只含原因码/workspace/message id。模型失败两次由 [291–302](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L291-L302>) 进入 RETRYABLE；无模型常态由 [211–214](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L211-L214>) 跳过。
- 调用方 [persist:334–341](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L334-L341>) 与 [markTaskForRetry:604–609](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L604-L609>) 会把 task 写 FAILED、retryable=true。但 [TaskService:346–358](<../../../../../backend/src/main/java/com/noteweave/task/TaskService.java#L346-L358>) 的 retryable 仅写 TASK_FAILED 事件 JSON；[TaskStateRepository:91–110](<../../../../../backend/src/main/java/com/noteweave/task/TaskStateRepository.java#L91-L110>) 没持久化待调度重试状态/时间。
- 消费者正常返回即 RECORD ack；既有 Outbox 正常投递成功是 SENT。全生产搜索 `redriveTask(` 仅在 [TaskOutboxDispatcherService:184–205](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java#L184-L205>) 调用，而该入口只接受 DEAD_LETTER。故语义失败的正常消费无法使用所称既有重驱入口。测试手工 `UPDATE task SET task_status='RUNNING'` 不能证明产品存在重驱。
- 可执行修法由主 agent 与 Outbox 负责人选定：memory 专用可重试状态/持久化重排入口，或明确可恢复人工 API，并通过当前 Outbox 契约合法投递新一代事件；有界次数/退避，不能让 `source_ref` 已成功的重投再影响效用。不能为重投修改旧 SENT 行冒充投递失败，也不能只 throw 后被当前消费者 catch 吞掉。
- 另一个真实竞态：[persist:301–303](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L301-L303>) 再查已有 signal 后无条件 completeTask，丢弃 followUp RETRYABLE。两线程均首查无 signal；A 写偏好 signal 但反馈 RETRYABLE；B 的 followUp 也 RETRYABLE，进入 persist 见 A 的 signal。若共用 task 已 FAILED，当前 completeTask guard 防止覆盖；但不同重复 task、task 已被重驱 RUNNING、或并发状态窗口中 task 仍可完成时，B 可错误收尾。因此必须用明确状态准备复现，不声称所有同 task 并发都会覆写。
- 修法：已有 signal 的分支仍服从本轮跟进反馈结果，保留 RETRYABLE，不依靠 signal 存在推断反馈完成；只有收据确认成功/明确 SKIPPED 才收尾。不要重抽或重复建候选。

## 4. OTH-07 来源全集、索引与迁移可行性

### 4.1 来源与调用覆盖

| 来源 | 产生及幂等语义 | 并发事实 |
|---|---|---|
| `rating:{runId}` | [recordAnswerFeedback:100–140](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L100-L140>)；不调 sourceExists，同回答最新评分替代，允许同来源多目标、多历史批次 | 同 run 现有 answer_run 锁串行；模型拆锁后必须保留短写串行及版本栅栏 |
| `retry:{trim(runId)}` | [recordRegeneratedFrom:148–166](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L148-L166>)；sourceExists 全历史去重，预期一次来源作用所有注入项 | 两线程都通过锁前预检；T1 锁 run+usage 提交，T2 随后获 run 锁但无来源复核，仍 apply，重复扣效用 |
| `follow-up:{userMessageId}` | [planFollowUp:199–231](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L199-L231>)、[applyFollowUp:239–273](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L239-L273>) 两次预检，全历史一次来源语义 | 第二次预检仍在 usage 锁前；CONTENT/空匹配插回答级事件不改 usage；两线程可都写。若 usage 已有同 outcome，后续 writeUsage 不动行，快照也未必能防重复 |
| `edit:{type}:{id}` | [recordArtifactEdit:310–337](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L310-L337>)；不调 sourceExists，相同比例幂等，不同比例撤销后重写；允许 superseded 历史 | 当前生产调用未发现，只有 [契约测试:157–175](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackContractTest.java#L157-L175>) 直接调 bean；不能据此扩大实施到保存入口 |

全仓迁移/Java 搜索显示 memory_application_outcome 的生产写入只有本服务的 insertEvent/insertAnswerLevel。sourceExists 仅用于 RETRY 与 FOLLOW_UP，定义 [901–906](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L901-L906>) 不过滤 superseded。旧的一次性 [MemoryOutcomeService:31–146](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryOutcomeService.java#L31-L146>) 只写 usage/item/mirror，不写本事件表，但会产生本次快照冲突与历史效用重算难点。

### 4.2 DDL

- [V163:4–48](<../../../../../backend/src/main/resources/db/migration/V163__memory_application_outcome.sql#L4-L48>)：只有 PRIMARY(id) 和三个普通索引：target(workspace,target_type,target_id,signal_kind,created_at)、item(memory_item_id,row_no)、source(workspace_id,source_ref)。无 source 唯一，无 active-only 唯一，无事件外键。后续迁移搜索未发现补充唯一约束。
- [V011:65–79](<../../../../../backend/src/main/resources/db/migration/V011__create_memory_tables.sql#L65-L79>)、[V082:61–81](<../../../../../backend/src/main/resources/db/migration/V082__cut_over_memory_compiler_to_canonical_runtime.sql#L61-L81>)：usage 有 canonical item/revision FK，但 target+item 是普通索引。生产唯一写入 [MemoryCompilerService:147–189](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java#L147-L189>) 自身也是 count→insert，没有唯一约束。此项为相邻风险，建议先统计，不擅改共享 compiler。
- [V080:22–42](<../../../../../backend/src/main/resources/db/migration/V080__create_canonical_memory_runtime.sql#L22-L42>) 对 revision 只保证 item/version_no 唯一，usage 的两个独立 FK 不保证 revision 属于同一个 item；短事务可校验该关系。
- [V161:18–26](<../../../../../backend/src/main/resources/db/migration/V161__memory_evidence_beta.sql#L18-L26>) 唯一 `(memory_item_id,session_key,direction)` 仅针对证据会话，不能替代反馈来源幂等。

### 4.3 建议方案

1. 优先增加独立来源收据表，对一次性 RETRY/FOLLOW_UP 建立 `(workspace_id,signal_kind,source_ref)` 唯一，单次来源收据与所有事件/计数在同一短事务提交。并发输家应识别已成功来源，不产生效用副作用；赢家回滚则允许合法重试。多 usage fan-out 保持合法。
2. RATING/EDIT 使用目标级 head/version 或等价锁记录保证最新版本，不把它们塞进永久一次性 source 唯一语义。若用事件生成列唯一，只能限制活跃事件的正确粒度，并明确 NULL usage 的回答级哨兵；仅 unique(workspace,source_ref,usage_log_id) 会因 MySQL NULL 可多次、历史替换、多目标问题不完整。
3. 一次性收据可从已有 RETRY/FOLLOW_UP 非空来源 `SELECT DISTINCT` 回填。回填只防未来重复，不自动修复历史重复效用；必须单独核对账务和历史 before/delta。不要删除历史行，也不要简单把重复行 supersede 后以为 item 计数已修好。
4. 主 agent 反馈生产表当前 0 行，因此该环境无需历史效用补偿；迁移仍应检测其他环境历史异常并 fail-closed/输出统计，或有明确非破坏性处理策略。切换过程须避免旧应用还写、回填后新应用才启用之间的窗口；不能以应用代码预检替代最终数据库唯一。
5. 新迁移编号完全由主 agent 分配，现有迁移不可修改。MySQL 版本、隔离级别、在线 DDL 支持、实际索引/重复数均以主 agent 最新聚合结果为准。

## 5. OTH-03 提示围栏与解析

- [判断器:106–123](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java#L106-L123>) 是 `用户消息 + text` 裸拼接；[系统提示:50–69](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java#L50-L69>) 未声明数据块，重试说明再附到同 user 字符串末尾。两条入口均受影响：评分 reason 和跟进消息。
- [MemoryPromptFence:23–48](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryPromptFence.java#L23-L48>) 已支持 user/previous 两类关闭标签转义（不区分大小写、允许空白）；不会移除普通 `<`、`>`、代码比较运算符。应复用 `userMessage(text)`，系统提示声明标签内只是待分类数据、不得执行其中规则；重试指令在围栏外。
- 保留 prompt 第一行和回答评价/讨论对象评价区分，见 [判断器测试:47–60](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryFeedbackAspectJudgeTest.java#L47-L60>)。不新增命中词就拒绝的黑名单；[历史证据:1–12](<history-evidence.txt#L1-L12>) 的 baf8bcc 已刻意删除它。
- [解析器:129–161](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java#L129-L161>) 校验布尔与枚举，但从首 `{` 到末 `}` 截取，并非严格整个响应只含一个 JSON 对象，也未明确拒绝多值/重复键。它不会写任意 statement，但错误合法枚举仍会改变效用/beta。建议同任务补完整单对象解析与负例测试；不要把格式白名单描述为能证明模型未受注入影响。
- [判断器日志:119–123](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java#L119-L123>) 已不输出异常消息；[日志测试:178–202](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryFeedbackAspectJudgeTest.java#L178-L202>) 校验无正文与 throwable。该子项关闭代码修改，只保留验收。
- 现有 [抽取安全测试:36–87](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryExtractionPromptSecurityTest.java#L36-L87>) 证明抽取/分类器围栏结构，不证明反馈判断器已有围栏，也不是付费模型对抗评测结果。

## 6. 主 agent 所需只读 SQL

仅返回表结构/索引与聚合，不输出 source_ref、usage id、正文、feedback_note、payload_json、凭据。由主 agent 使用现有只读权限执行；本子 agent 未连接生产。执行前可将会话设为只读事务，结束 COMMIT，不使用 FOR UPDATE。

```sql
-- 先确定版本；MySQL 8 使用 transaction_isolation，旧版本另用 tx_isolation。
SELECT @@version AS db_version, @@transaction_isolation AS isolation_level;

SELECT table_name,index_name,non_unique,seq_in_index,column_name,sub_part
FROM information_schema.statistics
WHERE table_schema=DATABASE()
  AND table_name IN ('memory_application_outcome','memory_usage_log','memory_item',
    'memory_runtime_revision','answer_run','conversation_message','memory_evidence_session')
ORDER BY table_name,index_name,seq_in_index;

SELECT table_name,constraint_name,constraint_type
FROM information_schema.table_constraints
WHERE constraint_schema=DATABASE()
  AND table_name IN ('memory_application_outcome','memory_usage_log','memory_item',
    'memory_runtime_revision','answer_run','conversation_message','memory_evidence_session');

SELECT signal_kind,COUNT(*) AS rows_n,
 SUM(superseded_at IS NULL) AS active_n,
 SUM(usage_log_id IS NULL) AS answer_level_n,
 SUM(source_ref IS NULL) AS null_source_n
FROM memory_application_outcome GROUP BY signal_kind;

-- 同源 fan-out 不是重复；细化到 usage，NULL 单独代表回答级事件。
SELECT signal_kind,COUNT(*) AS duplicate_groups,SUM(n-1) AS extra_rows,MAX(n) AS max_copies
FROM (
 SELECT workspace_id,source_ref,signal_kind,usage_log_id,COUNT(*) AS n
 FROM memory_application_outcome
 WHERE superseded_at IS NULL AND source_ref IS NOT NULL
 GROUP BY workspace_id,source_ref,signal_kind,usage_log_id HAVING COUNT(*)>1
) d GROUP BY signal_kind;

-- 一次性来源历史也应无重复，不将 RATING/EDIT 合法替换历史计入。
SELECT signal_kind,COUNT(*) AS duplicate_groups,SUM(n-1) AS extra_rows
FROM (
 SELECT workspace_id,source_ref,signal_kind,usage_log_id,COUNT(*) AS n
 FROM memory_application_outcome
 WHERE signal_kind IN ('RETRY','FOLLOW_UP') AND source_ref IS NOT NULL
 GROUP BY workspace_id,source_ref,signal_kind,usage_log_id HAVING COUNT(*)>1
) d GROUP BY signal_kind;

SELECT COUNT(*) AS duplicate_groups,COALESCE(SUM(n-1),0) AS extra_rows
FROM (
 SELECT workspace_id,target_type,target_id,memory_item_id,COUNT(*) AS n
 FROM memory_usage_log WHERE memory_item_id IS NOT NULL
 GROUP BY workspace_id,target_type,target_id,memory_item_id HAVING COUNT(*)>1
) d;

-- 归属和注入版本数据完整性，只计数。
SELECT COUNT(*) AS malformed_usage_n
FROM memory_usage_log u
LEFT JOIN memory_item i ON i.id=u.memory_item_id
LEFT JOIN memory_runtime_revision r ON r.id=u.memory_revision_id
WHERE u.memory_item_id IS NOT NULL
 AND (i.id IS NULL OR NOT (i.workspace_id <=> u.workspace_id)
      OR (u.memory_revision_id IS NOT NULL AND
          (r.id IS NULL OR r.memory_item_id<>u.memory_item_id)));

SELECT COUNT(*) AS source_multi_target_groups
FROM (
 SELECT workspace_id,source_ref,signal_kind
 FROM memory_application_outcome WHERE source_ref IS NOT NULL
 GROUP BY workspace_id,source_ref,signal_kind
 HAVING COUNT(DISTINCT target_type,target_id)>1
) d;

-- 可重试任务是否只有 SENT，而无可重驱 Outbox；不读事件正文。
SELECT t.task_status,t.progress_phase,o.status AS outbox_status,COUNT(*) AS rows_n
FROM task t LEFT JOIN task_outbox o ON o.task_id=t.id
WHERE t.task_type='MEMORY_EXTRACTION'
GROUP BY t.task_status,t.progress_phase,o.status;
```

主 agent 已回报：迁移最高 V192、失败 0；memory_application_outcome 总行数 0，仅主键与 3 个普通索引。上述生产结果应由主 agent 在总报告链接其原始查询回执；本报告不代填未取得的隔离级别/其他表重复数。

## 7. 允许文件清单建议与实施顺序

### 7.1 单一负责人建议

记忆反馈负责人可修改：

- [MemoryApplicationFeedbackService](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java>)：OTH-02/06/07 共用，必须同一负责人。
- [MemoryConversationExtractionService](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java>)：仅反馈结果、重复信号分支、恢复契约；不得顺便改约束或 research。
- [MemoryFeedbackAspectJudge](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java>)；[MemoryPromptFence](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryPromptFence.java>) 原则复用，只有确有必要才改共用行为。
- [反馈契约测试](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackContractTest.java>)、[跟进事务测试](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackFollowUpTransactionTest.java>)、[反馈重试测试](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryConversationExtractionFeedbackRetryTest.java>)、[判断器测试](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryFeedbackAspectJudgeTest.java>)，及新真实 Spring/MySQL 并发测试文件。
- 如独立事务 helper/来源收据 repository 必要，可新增 memory 包文件；迁移仅主 agent 指定的新编号。

需另行协作、默认禁止修改：[KafkaTaskConsumer](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java>)、[TaskOutboxDispatcherService](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java>)、[TaskService](<../../../../../backend/src/main/java/com/noteweave/task/TaskService.java>)、[TaskStateRepository](<../../../../../backend/src/main/java/com/noteweave/task/TaskStateRepository.java>)、[MemoryCompilerService](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java>)、[ConversationTurnModule](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTurnModule.java>) 及公共配置、research、约束/向量文件。恢复路径契约交主 agent 与 Outbox 负责人协调；业务正文日志的其他入口仅记录风险，不私自扩范围。

### 7.2 建议步骤

1. 主 agent 确认来源收据结构、RATING 版本策略、恢复入口和文件归属，统一分配迁移。
2. 围栏可独立小提交：数据区+系统声明+完整单对象解析+离线负例，不动反馈政策。
3. 按来源类型落数据库幂等，锁顺序及重复结果处理先统一；替代仅靠 sourceExists 的先查后写。不能 catch 任意 SQL 异常后当成功。
4. 拆评分模型事务，短事务重读最小相关快照并执行原反转/应用逻辑。
5. 跟进缩小冲突集，保留相关冲突 RETRYABLE；重复信号分支遵守跟进状态。
6. 接入实际可达的持久化恢复路径，保留有界重试和成功来源不再执行；不占用另组共享文件。
7. 完成代码后集中做下节真实事务/两线程验收；未执行前状态只能是已核实或已实现待验收。

## 8. 必须保留的不变量与真实验收

### 8.1 不变量

- 用户评分由 rating 决定，模型只给反馈方面；跟进必须由模型判反馈/极性，不恢复关键词推断。
- CONTENT 负面只写回答级事件；明确槽位只影响对应偏好；UNKNOWN 保留原半权政策；正向跟进仅作用未被 signaled 的项目且权重 0.5。
- last-rating 替换及原 reverse/restore/adjust/beta/legacy mirror 必须原子；历史事件保留，失败事务无部分效用/收据。
- 一次来源可 fan-out 多 usage；不同来源/合法重试不因过宽唯一键被拒绝。已成功来源重复 SKIPPED；相关数据冲突 RETRYABLE；无模型配置的正常跳过不无限重试。
- 不用自动改研究开关、删除 Kafka/Outbox、修改已有迁移或业务正文清理来简化验收。

### 8.2 场景清单

所有数据库并发验收应在隔离 MySQL、真实 Spring 代理/实际 transaction manager 下，至少两独立连接；用 latch/barrier 控制顺序，不用同线程 onCall 更新冒充两线程。模型采用可阻塞离线 stub，记录 `TransactionSynchronizationManager.isActualTransactionActive()`。

1. **评分慢模型**：线程 A 经 HTTP/代理发反馈，在模型处阻塞；断言此刻无数据库事务；线程 B 更新/锁同 run 能及时提交。A 释放后只能在短事务持锁。现有缺陷下 B 会等待，修后不等待模型。
2. **快照过期/归属**：A 模型前后间隙，B 改 run→message、相关 usage revision/slot、撤销授权、删除目标；A 不得更新旧/越权对象，返回定义好的冲突或拒绝，不成功覆盖。
3. **相同评分与最后评分**：A/B 相同输入只一组活跃有效反馈；A/B 不同输入按约定提交顺序处理，较老快照不能在较新结果后无校验覆盖。校验计数、beta、usage 与 superseded 历史。
4. **来源 RETRY 竞态**：两线程在 sourceExists 都读 0 后同时推进；无论谁先持 run 锁，仅一次完整 fan-out，效用/计数只改变一次；赢家 rollback 后输家或重投可成功。
5. **跟进回答级竞态**：同 user message，模型均判 CONTENT，usage 不变，两线程均进入 apply；只留一回答级来源，不能依赖 NULL usage 唯一行为。
6. **跟进已有 outcome 竞态**：usage.outcome_type 已非空、同来源负面仍被 apply 的场景；来源屏障必须防双写，不把 writeUsage 影响 0 当作幂等证据。
7. **R6 无关/相关变化**：A NEGATIVE LENGTH，B 只改 terminology usage 的 outcome，A 应正常提交 length；B 改 length 注入版本/相关活跃反馈或把非目标改成 length，A 应 RETRYABLE。ACCEPTED/UNKNOWN 的相关集合另测。
8. **真实 R5 恢复闭环**：注入相关冲突→task 进入可重试状态→通过实现的真实重投 API/调度入口（不得直接改 task）→原 source 仅一次有效写入→task 正常完成。模型两次失败同样走此路径；已完成重复消息不重复评分。
9. **并发 signal 分支**：A/B 首次均查无 signal；A 写 signal，但本轮反馈未成功；B followUp RETRYABLE，进入 persist 已见 signal，且 B task 仍 RUNNING。B 不能 complete，保留重试。再测同 task 已 FAILED 守卫，不误报原有守卫失效。
10. **默认 REQUIRED 事实**：从真实 Kafka listener handler 到 extraction，模型处无事务；反馈提交后令抽取 persist 故障，验证既有独立反馈提交语义。新增外层事务调用应按明确契约被拒绝，而非悄悄持锁调用模型。
11. **死锁/锁顺序**：不同 answer 共享两条 item 且 usage id 顺序相反，两线程并发评分/跟进/旧 outcome；统一 item 锁顺序或可恢复死锁处理，不丢计数。FOR UPDATE join 的实际锁与隔离级别要纳入验证。
12. **围栏**：混合大小写/空白的 user/answer 闭合标签、代码比较符、正文中的合法 JSON 和伪系统指令；正文仍完整处于数据区，重试指令区分，blank 不调模型、最多两次、600 token 预算不回退。严格解析拒绝前后任意文字、多对象、数组、重复/错误类型字段，合法分类原意保持。
13. **迁移**：空表、正常 fan-out、多次合法 RATING/EDIT 历史、一次性重复、NULL usage、无 source 的旧行都覆盖。任何补偿先审批，不以删除业务记录消除冲突。

### 8.3 当前已有测试能证明什么

- [契约测试:28–40](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackContractTest.java#L28-L40>) 启动 Spring/MockMvc，覆盖顺序 last-rating、CONTENT、禁用、重生成、跟进等，未覆盖模型时事务状态及两线程竞争。
- [跟进事务测试:72–109](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackFollowUpTransactionTest.java#L72-L109>) 直接 new 服务+H2/template，正确证明该直接入口 REQUIRED 在无外层事务时新建短事务；[133–160](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackFollowUpTransactionTest.java#L133-L160>) 的冲突是同线程 stub 回调，不是真实并发。
- [历史 R5 测试:168–201](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackFollowUpTransactionTest.java#L168-L201>) 手工把 task 改 RUNNING；[抽取重试测试:49–91](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryConversationExtractionFeedbackRetryTest.java#L49-L91>) 使用 mock task 状态。它们证明结果传播/补跑，不证明生产有重驱通路。
- 所有上述测试本轮均未执行，不能据静态读测试宣称真实事务/两线程验收已通过。
