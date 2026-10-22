# OTH-01 至 OTH-16 核实表及执行批次

日期：2026-10-23；主 agent 本轮明确授权：实际实施非 research 工作包，先核实、子 agent 实现、主 agent 审查集成、代码优先后集中验证。基线 `70631bd015067b19f9d55aaec92d7429751dc936`；开发 `/data/workspace/noteweave-oth-20261023`，分支 `codex/oth-20261023`。运行发布 worktree `083b299f` 不变，不部署、不合并主线、不付费评测。

## 核实表

路径均相对仓库；详细逐调用链证据见本目录 reports/，原始行号指基线。

| 编号 | 文档主张与触发 | 代码/迁移与证据 | 当前分类 | 依赖、预计文件与验收 |
|---|---|---|---|---|
| OTH-01 | detail/length-limit FORBID 丢 value/反义 | ConstraintSlot:67-78→ConstraintOpMerger:404-413→DB→chat/ConversationRetrievalContextAssembler:131-133。ChatService helper二次禁止确实存在，但主链已有context时不用该helper | 已确认需要修复；MemorySlots类别词表缺实际误判资料，保留 | A组；先OTH04/05再更语义版本；保留完整可直接渲染正文兼容research，只读核对其renderer。验收值/极性/范围/重申/撤销和正式QA/Note/Wiki窗口，不重写旧冻结快照 |
| OTH-02 | 点赞点踩慢模型持锁 | ChatController:151→跨Spring代理反馈服务@Transactional:80→answer_run FOR UPDATE:607→judge:96→usage/event/item写入 | 已确认需要修复 | B组同服务串行OTH06/07；最小只读快照→事务外判断→短事务重新校验所属/映射/相关版本/幂等。真实Spring代理+两连接验证模型期间无写锁、冲突/回滚 |
| OTH-03 | 输入边界与异常日志 | FeedbackAspectJudge:106裸拼且readJudgment截{}；ConstraintOpSchema:69截[]；TopicBoundaryJudge:58及ReviewService:54异常正文；MemoryExtraction.parse:395-415截[]且第3合法项break遗漏第4非法项 | 已确认需要修复；反馈判断器异常日志已修，不重做 | A负责constraint/Topic，B负责memory；完整JSON边界，最多单一整段围栏兼容，非法整批失败，反馈用现有围栏，不回加INSTRUCTION_LIKE；定向离线安全与日志测试 |
| OTH-04 | 规则版本/空基线预先提交 | 真正实现是ConversationTopicProjectionV2Service.readConstraintState:381-406，不是仅record的ProjectorV2；切分事务先改版本/水位后调模型，失败旧ACTIVE仍可见 | 已确认需要修复，并纠正文件范围 | A组；提交代与候选重建区分，模型失败不得发布混合代；版本/水位/steps/ACTIVE原子更新，失败/部分成功/SQL回滚验证 |
| OTH-05 | 并发refold与READY一致性 | 常规CAS/refold已存在；latestState:317跨版本内存归零不重抽；refold后缀依赖旧ACTIVE；apply失败结果未传播至freeze。真实删除有DELETED gate及派生清理 | 已确认需要修复；常规CAS已有证据，删除清理仅需验收 | A组；不同generation/ACTIVE依赖不盲合并，有限事务外重准备；chat freeze持锁复核完整性。不改RES05，不保留已删正文以减少重抽。两线程/版本/删除竞态 |
| OTH-06 | 反馈传播、冲突过粗、重试 | Kafka→extract→followUp先于持久化模板，无外层DB事务；REQUIRED本身非缺陷。全usage.equals过粗；7cc6696 RETRYABLE已保留但failTask只记事件，正常消费ack后无实际重投；persist已有signal可吞反馈RETRYABLE | 已确认需要修复；R4仅需验收；R5返回值已修并有证据 | B组业务，后续D组消费恢复接线；只比较相关目标及反馈状态，保留相关冲突可重试；已有signal不等于反馈完成。真实任务/Outbox/消费者重试闭环与有限退避 |
| OTH-07 | 来源只有预检无唯一保证 | V163只普通source索引；retry/followUp在锁前sourceExists；空targets回答级事件可重复。一次来源允许多usage fan-out，rating/edit允许替换历史 | 已确认需要修复 | B组，V193来源收据/必要目标版本。一次来源收据与fan-out同事务，rating/edit非永久一次性；历史SELECT DISTINCT回填不删除事件/效用，异常重复统计。两连接同源、赢家回滚、合法改评 |
| OTH-08 | 批次无运行期续租 | claim随机lease_token已正确；requireOwned校验当前身份+token+未过期；coordinator网络期间无renew。ProcessingBudget已有动态lease下限 | 已确认需要修复；fencing已修并有证据；P99仅需验收 | C组，保留现有lease/poll下限先加有限独立heartbeat，过期token不能复活，发送前/提交前复核；慢调用/失租/接管/取消/关闭测试 |
| OTH-09 | embedding_request_budget未执行 | Repository初始化/映射仅存total，无used；SDK内隐式重试不可观察，context_model_calls是另一预算 | 已确认需要修复；历史真实耗费缺资料，人工追加预算缺产品决定 | C组V194。仅持久source批次新许可路径，每真实子批/HTTP重试前CAS+attempt账本；旧普通client保持语义。缓存0、失败计、重claim不清零；历史未知显式隔离而非伪造0。人工加预算不实现 |
| OTH-10 | 仅恢复过期RUNNING | Repository:194-208无READY/activeVersion；先limit100旧行可阻塞有效行；Outbox单行重驱复位其他批次READY | 已确认需要修复 | C组先仓储，D组再Outbox；候选当前资格前置，事务内重验task/snapshot/version/token；节流与在途抑制，SENT不代表完成；完成批不重投、两恢复器/回滚 |
| OTH-11 | 14处Outbox无业务唯一 | 主agent重新统计14处/10文件；V002/V022/V023只有id唯一；投递单行fencing不防两行重复；finalize false后无条件failTask绕过旧批守卫 | 已确认需要修复 | D组待B/C交付后串行接管其入队文件，V195预留。在途语义键区分批次version与Kafka分区键，历史SENT/合法重驱保留；重复不同payload不能吞；旧批晚到失败不污染当前任务 |
| OTH-12 | ES不可用QA/Note不可用 | 已有受开关控制MySQL/BM25F降级；无ES解析产生DISABLED/NOT_PROJECTED，降级SQL/回填却只认INDEXED/PROJECTED，真实本地资料不可读。ProductionGuard现禁止开本地回退；分通道错误亦丢可用结果 | 已确认需要修复，纠正完全未做降级的旧表述 | E组；保留默认和production guard，仅显式允许路径引入当前PARSED/DISABLED本地读取；不放开FAILED/DELETED/旧版/撤权/research资格。与A共享ChatService排队或不改；隔离替身故障验证 |
| OTH-13 | 干净构建/Node类型依赖 | manifest/Docker/CI已固定pnpm11.7.0+frozen lock；缺显式@types/node，锁中只有间接26.1.1，Node22目标；历史构建通过不是本轮 | 已确认需要修复；pnpm固定已修并有证据 | F组唯一manifest/lock/Docker/CI负责人；补Node22兼容显式类型，不换包管理器。冻结后空依赖目录install/typecheck/build/ui检查及锁不变 |
| OTH-14 | no-table-use-bullets作用域冲突 | Prompt49-53+Merger379-395+普通测试默认当前话题；无既有ACTIVE的eval fixture474-524却要求CONVERSATION；历史明确待产品决定 | 缺少产品决定 | 暂不改prompt作用域/fixture，不放宽两种都过；需用户决定无长期措辞时当前话题或全会话，已有宽scope UPDATE语义保持 |
| OTH-15 | 完整非research浏览器流程 | 只有历史登录健康API；当前e2e混research/真模型/共享工作台，Memory只刷新、Note仅非空，artifact终态允许failed/wait | 仅需验收 | 集成冻结后白名单+独立栈/账号/数据/替身，必要真实模型/线上写入另获授权；artifact edit→反馈生产调用点缺，先记覆盖缺口不擅加功能 |
| OTH-16 | d10两修复独立复审 | baf8bcc/7cc6696/43d638a均基线祖先；已独立读diff/current。去词表与RETRYABLE窄修复有效；既有同线程H2/stub不证明真实并发/完整恢复 | 仅需验收；两窄修复已修复并有证据 | 独立复审agent不实现memory；剩余发现归01/02/03/06/07/11，不新增任务计数；实现后独立审代码/隔离验证 |

## 第一批与依赖

1. A约束：先04/05共同代/完整性，再01否定正文和03解析/日志；14单独等待，不改research。
2. B记忆：先03数据围栏/完整解析，再07来源屏障，02短事务，06目标冲突与持久恢复契约；同一负责人。
3. C流水线：08心跳/09许可账本/10READY恢复统一仓储设计，必须保留callback fencing/旧token拒绝；Outbox全局替换留D。
4. F构建可与A/B/C并发，独立文件，无安装旧业务依赖。
5. D Outbox待B/C文件交付并冻结后实施全14入队点与恢复接线。E检索待二次审查确认允许状态矩阵，可与D独立但不得改A的ChatService。
6. 主agent独立检查每项diff与证据、按小提交集成开发分支；不自动合并主线。最后集中编译/定向/真实双连接数据库/干净前端及可授权流程验证。

## 文件唯一负责人

- A：conversation/constraint/**、ConversationConstraintProjectionV2Service、ConversationTopicProjectionV2Service、TopicBoundaryJudge、TopicBoundaryReviewService、chat/ConversationRetrievalContextAssembler、ChatService仅约束渲染、关联tests。
- ContextV2ShadowSnapshotService：主agent保留协调权，本轮仅将chat freeze最小改动授权A（必须单独列diff）；compileResearch不改。ConversationTurnModule、ConversationContextCompilerV2Service保留，不擅动RES05。
- B：MemoryApplicationFeedbackService、MemoryConversationExtractionService、MemoryFeedbackAspectJudge、MemoryPromptFence、新memory收据/恢复类、memory tests、V193。
- C：SourceEmbeddingBatchRepository/SourceRetrievalProjectionCoordinator/SourceRetrievalProjectionService/SourceRetrievalProjectionFinalizer、新batch heartbeat/attempt类、EmbeddingClient/OpenAiCompatibleEmbeddingClient只新增专用批次入口、SourcePipelineProcessingBudget、关联tests、V194。OpenAiSdkClients/配置/Compose不授权。
- D：TaskOutboxDispatcherService、DurableOutboxDispatcher（不得改变research outbox行为）、KafkaDeadLetterTaskRecovery/KafkaTaskConsumer仅非research必要分支、新TaskOutboxEnqueuer、V195。14入队点在B/C交付后转交D，严禁并改。TaskService恢复API如需另报。
- E：QA/Note本地读取和检索模块及tests（与C provider client接口约定，只调用不改），不得并改ChatService。
- F：frontend/package.json、pnpm-lock.yaml、Dockerfile、.github/workflows/ci.yml；仅必要构建修改。
- 主agent：台账/证据/迁移号与共享配置/集成，所有未分配共享文件默认保留。

## 迁移登记（仅源码预留，未应用数据库）

在主线/所有worktree重新核对无 V193+ 后，由主agent登记：V193=memory反馈一次性来源收据及必要目标版本；V194=embedding请求许可账本/已用预算/历史未知标记与恢复时间；V195=Outbox在途语义唯一。A暂不需迁移，如确需先报告。不得改V001–V192，不允许子agent自占其他编号。

## 明确缺口与待决定项

- OTH14产品scope独立等待。
- MemorySlots旧数据误分类无足够实际证据，保留已有类别风险规则，补验证不删词表。
- 19个G2旧编号仅有编号没有原文，不猜实现；ARC01不执行。
- 历史embedding已用请求无法还原，旧未完成批次不得伪标新账本已用0；先安全隔离/拒绝并说明迁移切换方案，人工追加预算不默认实现。
- 本轮没有付费模型授权，不运行research质量或线上故障注入。完整用户流程必要真模型/线上数据步骤后续单列。
- 当前资料已更新模型/DeepSeek/2560维，前次配置检查结论过期，不在此次代码改造中重置凭据或配置。

## 独立证据

reports/audit-constraints.md、audit-memory.md、audit-pipeline.md、audit-review-build.md、audit-main-outbox-retrieval.md 是逐链证据；精确基线文件位置链接在报告中。主agent另保留只读SQL脚本及输出，所有历史测试仅标历史，本轮待实现后验收。
