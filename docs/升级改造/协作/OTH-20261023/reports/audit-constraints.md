# OTH 约束线只读静态核实报告

- 核实日期：2026-10-23；指定基线：`70631bd015067b19f9d55aaec92d7429751dc936`。
- 读取对象：本机同提交源码快照；开发目标为服务器分支 `codex/oth-20261023`，本轮未连接远端、未修改源码、未执行测试或模型、未写数据库、未创建子 agent。
- 已完整读取 [AGENTS.md](<../../../../../AGENTS.md>)、[执行提示词](<../../../../../docs/升级改造/协作/AI执行提示词-其他改造线路-2026-10-22.txt>)、[接手说明](<../../../../../docs/升级改造/协作/AI接手说明-其他改造线路-2026-10-22.txt>)、[任务总账](<../../../../../docs/升级改造/协作/任务总账-2026-10-22.json>)，并读取 [research 接手边界](<../../../../../docs/升级改造/协作/AI接手说明-Research改造线-2026-10-22.txt#L27-L35>)。
- 历史证据来自快照中 [HANDOFF.md](<../../../../../HANDOFF.md>) 的提交记录与当前测试源码，不等于重新查验 Git 对象或重跑历史日志。快照未发现 Git 元数据、原始 REVIEW-d9/d10 或 live 原始日志；相关提交归因明确标为历史文档记录。

## 1. 逐项结论

| 项目 | 分类 | 核实结论 |
|---|---|---|
| OTH-01：FORBID value 与渲染 | 已确认需要修复 | detail/length-limit 的 value 确实在落库时丢失；正式会话窗口直接消费错误正文。双重否定存在于独立渲染 helper，但不能称为当前正式主链必然输出。 |
| OTH-01：MemorySlots.categoryOf | 缺少资料待核实 | 真实用途已核对：候选风险分类、缺字段回填、旧记忆冲突读回，不是已删除的 INSTRUCTION_LIKE 解析拒绝表。没有足够证据支持删除整个分类规则。 |
| OTH-03：ConstraintOpSchema | 已确认需要修复 | 任意前后缀/对象内数组被截取为操作；未校验完整响应。用户正文已有围栏，不能重复声称完全无保护。 |
| OTH-03：TopicBoundaryJudge | 已确认需要修复 | 异常正文仍入日志；调度外层也有同类输出。两次失败保持 NOT_RUN 的语义已存在，须保留。 |
| OTH-04：版本/空基线重置 | 已确认需要修复 | 实际事务所有者为 ConversationTopicProjectionV2Service；切分事务先写新版本和空基线，再调用模型，失败留下旧 ACTIVE 行可被编译。 |
| OTH-05：旧常规乐观冲突 | 已修复并有证据 | 已有最多三次 refold 和胜者覆盖复用；历史记录归因 a0167f1。仅覆盖无依赖歧义的常规冲突，不能关闭整个工作包。 |
| OTH-05：跨版本/依赖变化/READY | 已确认需要修复 | 版本归一和 refold 不重建完整抽取前缀，且缺少可传播到 freeze 的结果状态；尾部 ops 的 ACTIVE 依赖可能失效。 |
| OTH-05：删除整段进度 | 仅需验收 | 整行清理真实存在且承担删除派生正文的隐私职责；真实删除还被编译器拒绝，不能仅凭 applied=false 推出必然落 READY。细粒度恢复需要另行定义，不能直接保留旧 steps。 |
| OTH-14 | 缺少产品决定 | 当前 prompt、ADD 默认值与测试均偏向无长期措辞默认本话题；fixture 却要求 CONVERSATION，历史明确要求产品确认，本轮不替用户选择。 |

## 2. 完整主调用链及事务边界

1. HTTP 入口 [ChatController:85–91](<../../../../../backend/src/main/java/com/noteweave/chat/ChatController.java#L85-L91>) → [ConversationTurnModule:118–175](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTurnModule.java#L118-L175>)。Answer prepare 用 TransactionTemplate 提交后，163 行在事务外调用 freeze；research 在 148–153 行另走外层事务，归 RES-05，本轮不改变。
2. [freezeShadowAnswer:179–198](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTurnModule.java#L179-L198>) → [freezeAnswer:64–79](<../../../../../backend/src/main/java/com/noteweave/conversation/ContextV2ShadowSnapshotService.java#L64-L79>)：查询 answer_run 身份和 cutoff → topics.refreshForInputCutoff → 独立短事务 persistSnapshot。失败由调用方记录 FAILED，而不是主动阻断整个 Answer。
3. [ConversationTopicProjectionV2Service:60–111](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L60-L111>)：TransactionTemplate 声明 REQUIRES_NEW；但 independentTransaction 发现任意活动事务就直接 body.get，不仅限于确实持有会话锁者。正式 Answer 当前在事务外，所以切分与应用确实分别提交；不能用注解名称推定 research 已移出事务。
4. [segment:113–153](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L113-L153>) 获取 conversation FOR UPDATE、校验 pending assistant cutoff、读取 CURRENT 前缀并切分；[223–227](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L223-L227>) 读取/重置约束 state 后提交。注意向量切分仍在此段，非本报告新增改造范围。
5. [extract:89–133](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationConstraintProjectionV2Service.java#L89-L133>) 按 USER seq 增量抽取，每条模型输入包含 merger.activeConstraints(all)。[LlmConstraintOpsExtractor:88–133](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/LlmConstraintOpsExtractor.java#L88-L133>) 最多两次，失败返回 Unusable，不伪装 NOOP；[145–160](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/LlmConstraintOpsExtractor.java#L145-L160>) 使用线程池和有界等待。投影遇失败 break，watermark 不越过失败消息。
6. [applyConstraintsInTransaction:260–301](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L260-L301>) 再拿 conversation FOR UPDATE；以 processed_seq + lock_version CAS state，再在同一事务中对账 constraint 行。冲突重读 state、refold，最多三次。state 表以 conversation_id 为主键，见 [V169:5–15](<../../../../../backend/src/main/resources/db/migration/V169__conversation_constraint_incremental_state.sql#L5-L15>)。
7. [persistConstraints:324–374](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L324-L374>) 完整覆盖文本、scope、极性、状态、版本，并仅删除 valid_from_seq <= processedSeq 且快照缺失的旧行。保留失败水位之后的行是现有恢复约定，不应单独粗暴删除。
8. [persistSnapshot:96–133](<../../../../../backend/src/main/java/com/noteweave/conversation/ContextV2ShadowSnapshotService.java#L96-L133>) 再拿同一会话锁，调用独立 Spring bean compiler；compiler 的 [readOnly 事务方法:41–44](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationContextCompilerV2Service.java#L41-L44>) 在此加入已有短事务。它校验完整 CURRENT 消息前缀和话题覆盖，但 [102–118](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationContextCompilerV2Service.java#L102-L118>) 不校验 state 水位或 rule_version。snapshot 固定落 READY；[V117:17–19](<../../../../../backend/src/main/resources/db/migration/V117__context_v2_shadow_snapshot.sql#L17-L19>) 只有每 answer_run 唯一与 READY/FAILED/REDACTED 状态约束，没有约束投影完整性约束。
9. [ChatService:509–518](<../../../../../backend/src/main/java/com/noteweave/chat/ChatService.java#L509-L518>) 在 active rollout 下读取冻结快照；[readReadyForAnswer:168–208](<../../../../../backend/src/main/java/com/noteweave/conversation/ContextV2ShadowSnapshotService.java#L168-L208>) 校验状态、SHA、身份和 FULL，不检查约束水位。planner [48–53](<../../../../../backend/src/main/java/com/noteweave/conversation/ContextWindowPlannerV2.java#L48-L53>) 选 ACTIVE 可用约束。
10. 最终正文的真实路径是 [ConversationRetrievalContextAssembler:88–136](<../../../../../backend/src/main/java/com/noteweave/chat/ConversationRetrievalContextAssembler.java#L88-L136>)：有约束即 hasContext=true，133 行直接加入 rule.text。[ChatService:265–275](<../../../../../backend/src/main/java/com/noteweave/chat/ChatService.java#L265-L275>) 将窗口注入 ConversationWindow，并关闭额外冻结约束渲染；[ConversationWindow:12–21](<../../../../../backend/src/main/java/com/noteweave/chat/ConversationWindow.java#L12-L21>) → [QA compose:165–178](<../../../../../backend/src/main/java/com/noteweave/chat/QaAnswerModeStrategy.java#L165-L178>) → [ChatService:305–323](<../../../../../backend/src/main/java/com/noteweave/chat/ChatService.java#L305-L323>) 得到最终 PromptSpec。Note/Wiki 也消费此窗口，不应只测独立 helper。

## 3. OTH-01：语义忠实性

### 已确认问题与触发条件

- 模型给 `DETAIL, value=简短, FORBID` 时，[ConstraintSlot:67–78](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintSlot.java#L67-L78>) 固定返回 `不要展开细节`；LENGTH_LIMIT 固定返回 `不要限制字数`。在 [Merger:404–413](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpMerger.java#L404-L413>) value 被转换成错误 constraint_text 后持久化。故不依赖模型随机性，只要合法 op 进入合并器就能确定复现。
- [ChatService:471–477](<../../../../../backend/src/main/java/com/noteweave/chat/ChatService.java#L471-L477>) helper 又加 `禁止：`，会生成 `禁止：不要使用表格`。但正式 assembleV2 链存在约束就关闭此额外渲染，因此旧报告将双重否定当作每次生产输出过度泛化。正式链明确受到固定正文反义影响，helper 仍必须纠正，防止其它调用或未来接线重现。
- 当前 prompt 的 [value 约定:86–90](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpsPrompt.java#L86-L90>) 还允许 value 为 `不要太长`；不能简单统一 `不要 + value`，否则仍双重否定。NEGATION 当前也是直接前缀拼接，需给明确的值/语句契约，不用关键词删除用户文本来猜语义。
- [ResearchContextV2Question:28–33](<../../../../../backend/src/main/java/com/noteweave/conversation/ResearchContextV2Question.java#L28-L33>) 只渲染正文，不读 polarity。只读兼容核实已完成：若将现有正文改成裸正向对象，research 将把禁止要求解释为正向；禁止未经协调采用该存储变化。

### 修法建议

父 agent 已明确采用可直接渲染的完整正文兼容方案，不改 research renderer。建议实现时：

1. 将 wire value 的目标含义说清，DETAIL/LENGTH_LIMIT/NEGATION 保留模型表达的实际对象或完整规范指令；不得用固定反义句替代。新增字段若有必要须显式版本化，不擅自扩大成研究协议改造。
2. 存储保持完整、单次否定的可读指令，Chat helper 不再把完整否定句嵌进第二层禁止；同一语义在正式窗口、helper、research 裸正文下均一致。
3. 修复抽取版本/重放时先完成 OTH-04/05，否则 bump prompt 版本会触发原子性缺陷。旧 READY 快照是冻结历史，不应静默批量重写或破坏 SHA；旧 steps 可按确定的语义版本重折，但无法从已丢 value 的旧文本反推用户原意。
4. 保留 REQUIRE、FORBID 独立极性、稳定 id、同槽覆盖、用户 scope、撤回链与低置信度门槛；不要再以词表过滤合法偏好。

### 测试与历史证据

- 历史明确记录 value 丢失：[HANDOFF:443–458](<../../../../../HANDOFF.md#L443-L458>)；极性持久化归因 `43e1734/15f041b`：[HANDOFF:151–154](<../../../../../HANDOFF.md#L151-L154>)。
- [ConstraintPolarityAnswerPromptEndToEndTest:85–122](<../../../../../backend/src/test/java/com/noteweave/chat/ConstraintPolarityAnswerPromptEndToEndTest.java#L85-L122>) 当前反而断言双重否定字符串；145–147 行直接调 helper，并非正式 buildAnswerDraft 全链。测试应改为保留原意的行为断言，不是保留错误字符串。
- [ConstraintSlotMappingTest:48–55](<../../../../../backend/src/test/java/com/noteweave/conversation/constraint/ConstraintSlotMappingTest.java#L48-L55>) 未覆盖 DETAIL/LENGTH_LIMIT；[PersistenceFidelityTest:139–159](<../../../../../backend/src/test/java/com/noteweave/conversation/ConversationConstraintPersistenceFidelityTest.java#L139-L159>) 证明极性落库与重抽覆盖已实现。
- 验证矩阵：不要简短/不要详细/不要太长/不限制字数/不要表格→改用表格/不要中文但使用英文；同一 op 经 merger→DB→compiler→正式 assembleV2→QA/Note/Wiki PromptSpec；同时只读 research renderer 兼容断言。补缺值与原 value 已含否定的场景。

### MemorySlots.categoryOf 的真实语义

- [MemorySlots:48–63](<../../../../../backend/src/main/java/com/noteweave/memory/MemorySlots.java#L48-L63>) 分类优先级为 forbidden→terminology→structure→interaction→style；[65–109](<../../../../../backend/src/main/java/com/noteweave/memory/MemorySlots.java#L65-L109>) 只有合法 slot 缺失时才按 category 回填，显式 REQUIRE/FORBID 优先，缺失时 NEGATIVE 或 forbidden 才给 FORBID。
- 候选链：[MemoryCandidateService:45–66](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryCandidateService.java#L45-L66>) 调 complete 和门控；[MemoryCandidateGate:30–47](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryCandidateGate.java#L30-L47>) 用 category 计算风险；[MemoryCandidatePolicy:62–75](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryCandidatePolicy.java#L62-L75>) forbidden 0.28/style 0.10、WORKSPACE +0.16，默认阈值 0.70。无冲突时两者都低于默认阈值，不能宣称任何否定词都会挡掉候选；自定义阈值可能改变结论。冲突状态本身也单独 blocking。
- 冲突读回：[MemoryConflictService:115–125](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConflictService.java#L115-L125>) 读 normalized_value_json；已有规范槽位/极性时 category 不覆盖它们，旧数据缺极性时才可能影响判断。另有公共 signal 创建 [MemorySignalService:68–95](<../../../../../backend/src/main/java/com/noteweave/memory/MemorySignalService.java#L68-L95>) 与回填 [MemorySlotBackfillService:97–118](<../../../../../backend/src/main/java/com/noteweave/memory/MemorySlotBackfillService.java#L97-L118>)。
- [设计文档:38–45](<../../../../../docs/升级改造/02-Memory与上下文改造设计.md#L38-L45>) 明确类别风险是既有约定；[MemorySlotConflictTest:13–35](<../../../../../backend/src/test/java/com/noteweave/memory/MemorySlotConflictTest.java#L13-L35>) 固定缺槽位回填行为。它与 `baf8bcc` 删除的抽取阶段 INSTRUCTION_LIKE 全批拒绝机制不同。
- 结论：保留，交由记忆负责人补脱敏旧 payload/实际门控配置和最小复现。可验收显式极性不被覆盖、合法 slot 不被改写、旧缺字段否定文本的 readback、阈值两侧边界。无需为 OTH-01 修改该共享文件来凑工作量。

## 4. OTH-03：解析与日志

### ConstraintOpSchema

- [parse:62–95](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpSchema.java#L62-L95>) 取首 `[` 至末 `]`，而不是验证完整响应。`{"explanation":[{"op":"ADD","slot":"table"}]}` 的内嵌数组可被当成顶层 ops；解释正文引用操作数组也会被执行。Jackson 是否接受多个根值还取决于配置，不必依赖该假设，上述嵌套对象例已由截取逻辑确定成立。
- [ConstraintOpSchemaTest:71–84](<../../../../../backend/src/test/java/com/noteweave/conversation/constraint/ConstraintOpSchemaTest.java#L71-L84>) 明确容忍任意 prose，缺少拒绝错误片段的测试。
- [ConstraintOpsPrompt:109–119](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpsPrompt.java#L109-L119>) 已有 user_message 数据块，157–160 行转义精确闭合标签；SYSTEM 92–97 声明数据边界。不能把它描述为全程裸拼。ACTIVE JSON 的文本仍是模型/用户派生数据，建议与 user_message 一并使用明确数据容器、规范序列化，不改变正常用户表达。
- 文档注释声称 targetId 只能引用已有 id，但 schema 并未拿 ACTIVE 列表：[测试:253–261](<../../../../../backend/src/test/java/com/noteweave/conversation/constraint/ConstraintOpSchemaTest.java#L253-L261>) 专门允许任意 targetId；[Merger:189–194](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpMerger.java#L189-L194>) 找不到 UPDATE target 会退成 ADD。这是现有兼容行为，不能以为已经有强校验，也不要未经设计直接改变全部合法重试。
- 建议：只接受完整数组；如保留 Markdown 兼容，仅允许整个响应是单一完整代码围栏；拒绝额外 prose、嵌套对象、多个根、截断、重复字段等歧义。保持最多8项、封闭枚举、任一非法整批失败、一次重试和失败不推进水位。验证模型回显用户 JSON 不会被当作独立结构化输出。

### TopicBoundaryJudge

- 调度入口 [TopicBoundaryReviewService:47–90](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicBoundaryReviewService.java#L47-L90>) 读最多20个 PENDING/UNCERTAIN 段，查询消息和锚点形成 prompt [119–142](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicBoundaryReviewService.java#L119-L142>) → judge → 条件更新 [180–199](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicBoundaryReviewService.java#L180-L199>)。本服务无外层数据库事务注解/行锁，模型调用不在它自建的持锁事务里；结果落库后影响 CURRENT_TOPIC 的最终适用范围。
- [TopicBoundaryJudge:58–60](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicBoundaryJudge.java#L58-L60>) 直接记录 ex.getMessage；调度 catch [54–55](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicBoundaryReviewService.java#L54-L55>) 也直接输出异常正文。触发为 SDK/业务异常信息包含请求片段或响应正文。
- 建议统一只记录受限 reason、异常类名和 attempt，不传 Throwable/消息正文；外层一起收口，不能只修 judge 后漏掉调度。保留两次尝试、600 token、失败 NOT_RUN、RETURN 越界不确认、只对仍 PENDING 的段条件更新。
- [TopicBoundaryJudgeTest:13–103](<../../../../../backend/src/test/java/com/noteweave/conversation/TopicBoundaryJudgeTest.java#L13-L103>) 已覆盖重试/预算/NOT_RUN，无日志泄漏断言。新增含敏感标记的异常替身与日志捕获；assert reason/type 存在且正文/URL/token 不存在。历史问题见 [HANDOFF:433](<../../../../../HANDOFF.md#L433>)。
- 附带边界：judge 当前使用正则抽字段，不是严格 JSON；可另列同任务内硬化，但不要为此扩大到 research 或模型公共客户端重构。

## 5. OTH-04：版本与空基线原子性

- 文档列出的 [ConversationConstraintProjectorV2](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationConstraintProjectorV2.java#L5-L24>) 当前只是记录结构。实际需修改对象是 [ConversationTopicProjectionV2Service:381–406](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L381-L406>)。
- 触发 A：旧 rule_version；触发 B：processed_seq>0 但 steps 为空/不可解析。readConstraintState 在切分事务中先归零 watermark、清空 steps、写当前版本、lock_version+1；之后模型首条失败，applyConstraints 在 [235–236](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L230-L244>) 直接 return。旧 ACTIVE constraints 未改变，编译器又不检查版本，因此新 state 版本与旧约束同时存在，READY 可以冻结旧要求。
- 即使前几条成功、后续失败，[persistConstraints:358–365](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L358-L365>) 保留水位之后的旧行，仍可能形成新旧混合的完整回答视图。
- 历史记录 [HANDOFF:423](<../../../../../HANDOFF.md#L423>) 与源码一致。现有 [PersistenceFidelityTest:115–137](<../../../../../backend/src/test/java/com/noteweave/conversation/ConversationConstraintPersistenceFidelityTest.java#L115-L137>) 只证明重抽成功能覆盖；[218–247](<../../../../../backend/src/test/java/com/noteweave/conversation/ConversationConstraintPersistenceFidelityTest.java#L218-L247>) 固定保留未处理旧行；没有验证规则重建失败不能发布新版本。
- 修法：读取旧 state 为不可变快照，不先发布新 active version。将重建意图/完整性与 committed state 区分；事务外重抽需要的完整前缀，短事务中校验原 state、消息/删除 epoch、规则版本，再原子切换 state 和对应约束。若仅成功部分，允许保存独立待续建进度或保持旧 committed generation，但不得宣称新版本完整 READY。不要删除旧 committed 约束来掩盖失败。
- 版本身份应包含真正影响折叠的规则：[Projection VERSION:32–33](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationConstraintProjectionV2Service.java#L32-L33>) 目前只拼 PROMPT_VERSION，[Merger MERGER_VERSION:48–49](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpMerger.java#L48-L49>) 未纳入此身份。实现本次语义变更要给可验证的兼容版本策略，避免只改常量却无法可靠触发重建。
- 验证：版本升级首条超时、第三条失败、空/损坏 steps、成功完整重建、CAS失败重试、落约束 SQL 异常回滚；断言 committed 版本/基线/水位/所有 ACTIVE 行一致，且冻结不能冒充完整新版本。

## 6. OTH-05：并发、refold、删除和冻结

### 6.1 已修复并需保留的部分

[apply:273–299](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L273-L299>) 已实现短事务 CAS、重读、refold，胜者覆盖全部则复用；[HANDOFF:161](<../../../../../HANDOFF.md#L161>) 归因 `a0167f1`。已有 [IncrementalTest:138–181](<../../../../../backend/src/test/java/com/noteweave/conversation/ConversationTopicProjectionV2ConstraintIncrementalTest.java#L138-L181>) 和 [FreezeConcurrentConflictPromptTest:101–140](<../../../../../backend/src/test/java/com/noteweave/chat/ConstraintFreezeConcurrentConflictPromptTest.java#L101-L140>)。后者使用模型 callback 在同线程直接写胜者状态，不是真实双线程锁验证。常规无需依赖重判的合并已修，仍需真实并发验收，不能删除所有 refold 退回每次全重抽。

### 6.2 R2：版本变化时只拿本轮 ops

[latestState:304–320](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L304-L320>) 发现版本不符便返回内存 `processedSeq=0,steps=[]`，但没有把库中 processed_seq 改成0，也没有恢复早期消息抽取。后续 refold 只收到 pending.steps：

- 实际数据库新版本 state.processed_seq>0：下一次 CAS 条件期望0，持续失败，最多3次放弃；READY 门控仍未感知失败。
- 实际数据库版本变化恰好伴随 state.processed_seq=0：CAS可成功，把原先仅本轮后缀 ops 写为完整新基线，水位跳到高 seq，早期要求可能被 persistConstraints 删除。

这是代码确定的跨版本风险，不能把 latestState 的归零当作全量重抽。建议不同 generation 不走普通 refold，返回需要重新准备的状态，事务外从正确前缀重建，保留重试上限和受限原因码。

### 6.3 R3：覆盖同 seq 不是单独缺陷，重用依赖旧 ACTIVE 的后缀才是问题

[refold:149–171](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationConstraintProjectionV2Service.java#L149-L171>) 丢弃 seq<=胜者 watermark 的本轮步骤是合理的先提交者优先规则，不能同时保存两份互相矛盾模型判断。但后缀步骤在 [extract:113–120](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationConstraintProjectionV2Service.java#L113-L120>) 是依据败者 ACTIVE 列表产生的，不能无条件认作与胜者基线无关。

确定的静态反例：A 对 m1 生成 LANGUAGE 英文并落库；B 对同 m1 生成另一个槽位/无约束，B 后续 m3 因自己已有的约束输出 NOOP（重申）或 UPDATE(targetId指向B独有id)。refold 丢弃 B 的 m1 后保留 m3；NOOP 不会纠正 A 的不同要求，UPDATE 找不到 targetId 会退 ADD，可能保留本应撤回的宽 scope。模型未越权，错误源于应用了依赖不同上下文的判定。

建议每步保存/比较抽取输入 ACTIVE 的规范指纹、source/generation/topic 版本；同基线且同依赖的尾部才可无模型 refold。依赖变化时从最早受影响步骤事务外重抽，合法重复消息仍只采用一个胜者；不能保留锁等待模型，也不能无限重试。补两线程 barrier 测试覆盖不同槽位、同槽不同值、NOOP、targetId、话题归属复核交错。

### 6.4 R1：applied=false 与 READY 的准确范围

- [refreshAtCutoff:84–89](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L84-L89>) 返回的只是 segmentation projection；[applyConstraints:230–244](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L230-L244>) 抽取0条直接返回、applied=false只打日志；freeze 不知道约束是否完整，固定写 READY。这一接口缺口明确需要修。
- 但真实删除会使 [compiler:79–82](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationContextCompilerV2Service.java#L79-L82>) 发现 DELETED 并抛异常，不能声称删除必然导致 READY。旧 [IncrementalTest:183–197](<../../../../../backend/src/test/java/com/noteweave/conversation/ConversationTopicProjectionV2ConstraintIncrementalTest.java#L183-L197>) 只人工删除 state，没有调用真实删除 API、也没有冻结测试，不足以推断生产漏洞。
- 仍可稳定论证的不完整 READY：模型失败导致水位未覆盖当前 USER，或上述版本不符CAS耗尽而消息前缀仍 CURRENT。compiler 不读 state，snapshot 没有完整性闸门。
- 建议 chat 专用 refresh 返回结果携带 `COMPLETE/RETRYABLE/INVALIDATED`、有效规则版本、已处理 USER 水位与版本标识，snapshot 在持 conversation 锁时复验。应比较 cutoff 前最后 USER 而非简单等于任意 assistant cutoff。当前 DDL 只有 READY/FAILED/REDACTED，若用 FAILED+受限原因码需明确后续重试，因为 exists(answerRunId) 会把已有 FAILED 也视为存在；readReadyForAnswer 对 FAILED 返回null触发旧路径降级，不能误把失败记录当成修复完成。
- 共享修改清单：主 agent 保留 [ContextV2ShadowSnapshotService](<../../../../../backend/src/main/java/com/noteweave/conversation/ContextV2ShadowSnapshotService.java>) 与 [ConversationTurnModule](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTurnModule.java>)；约束实现方只能提出 chat freeze 契约，不能顺带修改 compileResearch/RES-05。不要为了引入 COMPLETE gate 强迫 research 事务改造。

### 6.5 删除进度：真实链与不变量

[ConversationController:107–111](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationController.java#L107-L111>) → Spring 代理 [DeletionService:25–45](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationMessageDeletionService.java#L25-L45>)：同一 conversation FOR UPDATE，原文清空并 DELETED，调用 replay redaction → [RunReplayRedactionService:84–92](<../../../../../backend/src/main/java/com/noteweave/conversation/RunReplayRedactionService.java#L84-L92>) → snapshot redaction。随后覆盖摘要/话题标 STALE，来源约束撤回清空；[99–110](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationMessageDeletionService.java#L99-L110>) 删除整个 state，避免 steps_json 留派生 value。

这不是已证明可直接删掉的坏代码。只删被删除消息的 Step 也不一定安全：后续 ops 的 value/targetId 可能由该消息的 ACTIVE 推导。保留所有后缀也可能继续泄漏派生内容。现有新 refresh 在 [segment:132–135](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java#L132-L135>) 拒绝任何非 CURRENT 前缀，因此注释中的下次从0重抽不是当前真实删除后的常规恢复流程。

[ConversationTurnModuleContractTest:1025–1056](<../../../../../backend/src/test/java/com/noteweave/conversation/ConversationTurnModuleContractTest.java#L1025-L1056>) 已断言真实删除后 compiler 抛异常、派生正文清空。建议本批验收删除与模型应用交错、不复活 ACTIVE、不产生含删除内容 READY、不保留 steps 敏感片段、重复删除幂等。若需要细粒度恢复，先约定保留安全前缀和待重建后缀的 generation/隐私依赖模型；不能仅为了减少重抽移除清理或解除 DELETED gate。

## 7. OTH-14：既有约定与待产品确认

已找到互相冲突的明确证据：

1. [ConstraintOpsPrompt:49–53](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpsPrompt.java#L49-L53>)：没有长期措辞用 current-topic；以后/每次等才用 conversation。
2. [Merger:379–395](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpMerger.java#L379-L395>)：ADD 无scope默认CURRENT_TOPIC；UPDATE命中旧目标且未显式this-turn时保留旧宽度，不应把这两类情形混为一谈。
3. [ConstraintOpMergerTest:50–60](<../../../../../backend/src/test/java/com/noteweave/conversation/constraint/ConstraintOpMergerTest.java#L50-L60>) 固定ADD默认话题；[ConstraintScope:8–19](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintScope.java#L8-L19>) 将 THIS_TURN 折成 CURRENT_TOPIC 是现有简化，不在本任务顺手改为真正单轮。
4. [fixture:474–524](<../../../../../backend/src/test/resources/conversation/constraint-ops-eval-v1.json#L474-L524>) 对无既有 ACTIVE、只有一句不要表格，改用分点，给两条ADD+CONVERSATION；与当前prompt默认冲突。
5. [HANDOFF:327–342](<../../../../../HANDOFF.md#L327-L342>) 记录18/19以及实际CURRENT_TOPIC；[426](<../../../../../HANDOFF.md#L426>) 与 [总账:344–355](<../../../../../docs/升级改造/协作/任务总账-2026-10-22.json#L344-L355>) 明确要求产品决定，不能把旧建议视为已授权改期望。
6. planner [178–191](<../../../../../backend/src/main/java/com/noteweave/conversation/ContextWindowPlannerV2.java#L178-L191>) 真实区别：CONVERSATION直接适用；CURRENT_TOPIC须来源段和当前段均CONFIDENT且topicId相同，跨话题失效。

**待用户确认的问题**：没有长期措辞、也没有已存在的更宽同槽约束时，纠正回答格式默认只对当前话题有效，还是要扩展到整个会话？当前实现约定偏前者；本报告不替用户选择。确认后统一prompt、fixture、普通eval与live严格scope断言，不接受二选一宽松通过。验证需含跨新话题、返回旧话题、明确以后、明确这次、已有会话级约束的UPDATE/重申。真实模型评测仍需另行授权，不能把历史18/19当本轮通过。

## 8. 共享文件与实施顺序建议

| 归属/协调 | 文件与用途 |
|---|---|
| 约束组可提议认领 | [ConstraintSlot](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintSlot.java>)、[ConstraintOpMerger](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpMerger.java>)、[ConstraintOpsPrompt](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpsPrompt.java>)、[ConstraintOpSchema](<../../../../../backend/src/main/java/com/noteweave/conversation/constraint/ConstraintOpSchema.java>)、[ConversationConstraintProjectionV2Service](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationConstraintProjectionV2Service.java>)、[ConversationTopicProjectionV2Service](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTopicProjectionV2Service.java>)；多项同文件顺序实现。 |
| 主 agent 保留 | [ContextV2ShadowSnapshotService](<../../../../../backend/src/main/java/com/noteweave/conversation/ContextV2ShadowSnapshotService.java>)、[ConversationTurnModule](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationTurnModule.java>)；chat freeze 门控单独提交契约，不碰研究事务。 |
| 需与 OTH-12 协调 | [ChatService](<../../../../../backend/src/main/java/com/noteweave/chat/ChatService.java>)、[ConversationRetrievalContextAssembler](<../../../../../backend/src/main/java/com/noteweave/chat/ConversationRetrievalContextAssembler.java>)；后一文件是旧清单漏列的真实正文消费点。 |
| 仅验收，暂勿删除规则 | [MemorySlots](<../../../../../backend/src/main/java/com/noteweave/memory/MemorySlots.java>) 与记忆候选/冲突/回填调用方；[ConversationMessageDeletionService](<../../../../../backend/src/main/java/com/noteweave/conversation/ConversationMessageDeletionService.java>) 的派生清理。 |
| 日志独立小修 | [TopicBoundaryJudge](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicBoundaryJudge.java>) 及 [TopicBoundaryReviewService](<../../../../../backend/src/main/java/com/noteweave/conversation/TopicBoundaryReviewService.java>) 的外层catch。 |
| research 只读 | [ResearchContextV2Question](<../../../../../backend/src/main/java/com/noteweave/conversation/ResearchContextV2Question.java>)；保持完整正文兼容，不改渲染或 RES-05。 |

建议顺序：先统一 OTH-04/05 committed generation 与完整性契约，再处理会触发版本变更的 OTH-01 正文语义和 OTH-03 schema；日志脱敏可独立。OTH-14等待产品决定，不阻塞其他已确认修复。新增迁移如确有必要，由主 agent 根据最新登记分配，不改写已应用 [V113](<../../../../../backend/src/main/resources/db/migration/V113__conversation_topic_projection_v2.sql>)、[V117](<../../../../../backend/src/main/resources/db/migration/V117__context_v2_shadow_snapshot.sql>)、[V169](<../../../../../backend/src/main/resources/db/migration/V169__conversation_constraint_incremental_state.sql>)、[V170](<../../../../../backend/src/main/resources/db/migration/V170__conversation_constraint_polarity.sql>)。

本轮只有静态证据，以上所有建议测试均未执行。完成标准必须区分源码已修、隔离验证通过、真实双线程数据库验证通过和产品scope已确认。
