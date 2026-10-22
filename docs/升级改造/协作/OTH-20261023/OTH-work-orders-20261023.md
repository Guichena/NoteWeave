# 第一批并发详细执行单

基线与协调：产品基线70631bd0；从主agent保存核实/登记的提交各建独立服务器worktree。禁止在主仓库/发布目录开发。具体分支由主agent派发。主agent保留集成和迁移/共享配置权，不自行推送或部署。语言遵循AGENTS。读核实表和对应reports后动手。只改明确授权文件，新增相同包内窄职责类与对应tests允许，其他需要先报告。

所有子agent交付：问题编号、实际修改路径及关键行、设计与失败处理、git diff --check、定向验证命令/实际结果或尚未执行、迁移/配置变化、保留不变量、已知风险、简体中文小提交SHA。测试代码随实现写，较完整测试由主agent冻结后集中执行；不跑真实模型、不访问生产业务数据、不改密钥、不停容器。远端执行一律 WSL→SSH，工具read/glob/grep在本机源码快照定位。每次写回前确认远端分支/文件没有他人变化，禁止覆盖来源不明修改。

## A：约束一致性（P0，OTH01/03/04/05）

### 已核实证据
- Slot67-78固定DETAIL/LENGTH_LIMIT丢value；Merger409持久化后，正式ConversationRetrievalContextAssembler133直接读取正文；ChatService helper二次禁止不是正式主链必经但仍错误。
- TopicProjection.readConstraintState381-406先提交新版本/空steps，extract后失败留下旧ACTIVE；latestState317-320跨代伪归零、refold尾部依赖败者ACTIVE；applied结果未传freeze。
- Schema69-77截[]可执行嵌套对象内数组；TopicBoundaryJudge58/ReviewService54日志打印异常正文。

### 文件范围与独占
conversation/constraint/**、ConversationConstraintProjectionV2Service、ConversationTopicProjectionV2Service、TopicBoundaryJudge、TopicBoundaryReviewService、chat/ConversationRetrievalContextAssembler、ChatService仅constraint render、相应tests；ContextV2ShadowSnapshotService仅freezeAnswer/persistSnapshot/readReady必要的chat完整性接线，必须单独列diff且不得动compileResearch。ConversationTurnModule/ConversationContextCompilerV2Service/ResearchContextV2Question仅只读；MemorySlots只核实不删规则。公共配置、Compose、迁移不授权；其他组不能碰本组文件。

### 分步骤
1. 重读TopicProjection的segment/independentTransaction/apply/latestState/readConstraintState，ConstraintProjection.extract/refold，freezeAnswer→persistSnapshot及compiler门控，记录所需完整USER cutoff与状态版本。
2. 先统一 committed generation：读旧state不提前发布新版本；重建应在事务外得到完整前缀，再在短事务CAS+约束对账同提交。首条或中间失败保持已提交代，不能新旧混合声称完整。缺行、空/损坏steps、删除/版本切换分别有受限原因。
3. 不同generation直接有限重准备，禁止拿旧后缀当全量；同generation冲突，仅当原ACTIVE依赖与胜者一致才无模型refold，否则事务外从受影响处重抽。保留合理胜者重复seq去重和有限重试，不锁着模型调用。可采用保守有限重准备优先于过度复杂指纹协议，但需保留已证安全快路径。
4. 将chat refresh是否完整、版本/已处理USER水位传递到freeze，持会话锁复核。未完成不得写READY；FAILED记录的后续可重试不能被exists早退永久挡住。真实删除仍撤回清空、拒绝冻结，不为了恢复保留敏感steps。
5. 在原子重建修复后处理FORBID：保留完整可直接读的正文格式，DETAIL/LENGTH_LIMIT/NEGATION保留真实value，不固定反义、不叠加禁止前缀。明确定义目标value/完整指令的规范化，覆盖value含否定情况；更新必要语义版本。不得把存储改为裸正向对象导致research消费反转；只读兼容断言。
6. 完整JSON解析只接受单一完整数组，若保留markdown仅允许整个响应一个json代码围栏；拒绝prose、对象内数组、拼接根/重复键/截断，保持枚举/MAX_OPS/合法scope/重试/失败不推进水位。完善数据围栏而不回加关键词拒绝表。
7. Judge和外层ReviewService日志只reason/type/attempt；NOT_RUN、次数和token上限保持。

### 保留/依赖/验收
保留稳定ID、极性、scope、撤回链、低置信度、历史READY快照SHA、当前research渲染/开关、删除隐私。OTH14不改fixture或放宽断言。无新增迁移，确需先申请。
验收代码：完整merger→DB→compile→正式窗口QA/Note/Wiki；不要简短/不要详细/不要太长/不限制字数/表格改口/缺值/值含否定；版本重建首条/中段失败、CAS两线程、NOOP/targetId依赖交错、删除并发、落库异常回滚、重复freeze/失败重试、模型不在写事务。小提交先一致性后语义/安全，主agent逐提交审。

## B：记忆反馈（P0，OTH02/03/06/07）

### 证据
ChatController→Spring@Transactional recordAnswerFeedback80→run FOR UPDATE607→judge96；全usage.equals248造成无关目标冲突；sourceExists仅预检，V163无来源唯一且一次来源可多usage；RETRY锁后未再查来源，FOLLOW_UP空targets可重复；7cc6696返回RETRYABLE已修，但persist301重复signal无条件complete；真正重投通路缺失。

### 文件与迁移
独占MemoryApplicationFeedbackService、MemoryConversationExtractionService、MemoryFeedbackAspectJudge、MemoryPromptFence（优先复用）、新memory来源receipt/target-head/retry repository/service、memory tests。V193__memory_feedback_receipts.sql 由主agent分配给本组，可包含必要短事务receipt/head/retry表；不能动V163等旧迁移。KafkaTaskConsumer/TaskOutboxDispatcher/TaskService公共恢复接线留D组，配置/Compose/MemoryCompiler/ConversationTurnModule禁止改。记忆抽取中的Outbox调用现阶段保持，后续串行转D。

### 步骤
1. 重读调用链/真实代理/事务、reverse/apply/item/usage/beta/legacy镜像政策，确定反馈版本和来源的不同语义。
2. OTH03先复用MemoryPromptFence.userMessage，system声明正文是分类数据、重试说明围栏外；单完整对象解析、枚举与NOT_RUN保持。MemoryConversationExtraction.parse不截任意[]，全部元素验证后才有界输出，不让第4非法项绕过，不回加INSTRUCTION_LIKE。保留合法旧字段兼容，不顺手强改所有字段。
3. OTH07一次来源RETRY/FOLLOW_UP用独立receipt唯一(workspace,kind,source_ref)，认领/事件fan-out/效用/计数在同事务；赢家回滚可重试，重复成功无副作用。RATING/EDIT保留替换历史与原提交顺序语义，必要target head/version序列化，不能唯一整个事件表source_ref。历史SELECT DISTINCT回填不删原事件；有异常重复可报告/阻止激活，不凭guess修效用。
4. OTH02入口非长事务读取最小run→answer身份/相关usage/feedback-head，judge不带事务/行锁。短TransactionTemplate再查权限/归属/映射/相关版本与幂等，执行原reverse/apply。过期结论拒绝或有限重新准备，返回明确冲突；不能覆盖更新后的评分。注入revision归因保持现有语义，不另定新产品政策。
5. OTH06当前无外层事务，勿机械REQUIRES_NEW；明示入口契约。按相关目标集合+反馈状态检查，非目标变动不误冲突，目标新增/删除/变成目标/相关revision/head变化仍RETRYABLE。已有signal分支必须服从本次反馈结果，不重复抽取/候选。
6. 实现memory包内可持久重试的窄接口/状态，先提交接口方案给主agent再接线：不能只failTask(true)当恢复；建议保存task+message绑定、due/attempt/cap/lease并由memory恢复调度器通过公共Outbox重新投递新消息。现阶段若公共enqueuer尚未到位，提交可由D组调用的prepare/mark retry契约，不自行改共享consumer或泛化所有task。必须有限退避、无模型常态SKIPPED、已成功receipt不重扣、不复位任意终态任务。
7. 附迁移兼容与MySQL/H2测试方案，生产outcome目前0但不能假设所有环境空；不在线应用迁移。

### 验收与交付
真实Spring代理+两连接：慢模型期间无answer_run写锁、同源两线程、多usage合法、空targets、改评分后反转/回滚、同类重复评分、相关/无关usage交错、删回答/撤权、模型异常不泄密、RETRYABLE→持久恢复→成功→重复无副作用。保留7cc6696和原反馈政策，不用手工UPDATE RUNNING冒充完整重试。小提交先边界/receipt后事务/恢复，主agent审。

## C：向量批次（P1，OTH08/09/10）

### 证据
Repository只有claim一次lease、无renew；已有随机token+requireOwned+token对象路径必须保留。embedding_request_budget仅写/读，真正HTTP在SDK内有子批和隐式retry。recovery只RUNNING，READY缺失、activeVersion缺失、旧无效行先limit可饿死有效行。

### 文件与迁移
独占SourceEmbeddingBatchRepository、SourceRetrievalProjectionCoordinator、SourceRetrievalProjectionService、SourceRetrievalProjectionFinalizer（仅预算耗尽不无意义重试）、SourcePipelineProcessingBudget、新batch heartbeat/permit/attempt类，EmbeddingClient/OpenAiCompatibleEmbeddingClient仅新增显式预算路径及对应tests。V194__embedding_attempt_budget_and_recovery.sql。OpenAiSdkClients工厂、application.yml、Compose、Kafka/Outbox共享服务/SourceParseService/SourceReprocessService暂不改；需要跨组接线列出契约给主agent。

### 步骤
1. 重读初始化/claim/上下文检查点/cache/末批Note/对象存储/complete/INDEX/DLT链，保留所有当前workspace/snapshot/task/version/digest/token守卫。
2. claim后有限线程heartbeat在模型与存储阻塞期间独立短事务renew；续租只对仍有效token+RUNNING+当前身份，使用统一DB时间，过期不得复活；失租标志阻止后续permit/complete；finally和关闭清理句柄。暂不缩短现有有效lease/poll下限，不捏造P99。
3. 新增明确source batch专用HTTP许可入口，普通embedQuery/embedDocuments保持原SDK行为；预算路径独立maxRetries=0 client+有限手工retry，实际每子批/每次发送前短事务CAS used<budget与attempt唯一账本。缓存命中不计，Note每发计，失败/超时计，重复登记同许可幂等但不能同许可重发免扣。新claim/新task复用同冻结batch不清累计。
4. 保守记录RESERVED/UNKNOWN发送窗口，不宣称精确等于收费。历史未完成batch的已用量未知，新增显式legacy-unknown标记并拒绝不经确认的再次预算发送，完成batch可复用；新batch从0。不给人工额外预算功能，单独记录需要产品决定，不凭默认重置隐式追加。错误码贯穿finalizer，预算耗尽不得无限新任务重试。
5. 恢复候选当前资格先过滤再限量，覆盖过期RUNNING和长期READY；事务内重验最新身份/版本、completed、token，独立恢复时间CAS/节流与入队同事务；在途已有相同批次版本消息则不重复，新SENT历史不永久阻止恢复。版本语义键明确包含batchVersion，与稳定Kafka message_key分离或由D组用专门dedup key接入。
6. 保留全部完成后唯一INDEX投递；shared Outbox late failure绕过资格由D组修，不自行覆盖。守卫和失败finalize原子化若需改跨组类先给最小接口，D后接线。
7. 对异步账本范围明确：同步/重建普通路径不伪造batch owner或账本，本轮保留现有语义并清楚列验证边界。

### 验收与交付
慢调用跨多心跳/失租接管/旧token续租提交release拒绝；缓存0、HTTP503两次=2、401一次、子批/Note计数、并发预算边界拒绝前无HTTP、claim/task重试不清；READY无消息/只有SENT/已有在途/两个恢复器/旧任务版本/完成/无效前100/事务回滚；manifest损坏不重算、完整双索引gate保持。小提交08/09/10同负责人顺序；不在线迁移、不调provider。

## F：干净构建依赖（P2，OTH13）

### 证据与范围
frontend/package.json:5已固定pnpm11.7.0，Dockerfile6和CI33相同；三个地方frozen lock已有。manifest devDependencies无@types/node，lock仅间接/peer26.1.1，vite.config.ts直接node:url，目标Node22。独占package.json/pnpm-lock.yaml；Dockerfile/CI只必要且先解释，不改功能页面/research/e2e业务。

### 步骤与验收
读取manifest/lock importer/tsconfig.node/vite/CI/Docker，保留现有版本与锁。用指定pnpm产生显式兼容Node22的@types/node及完整锁图，不手写伪integrity、不改npm/yarn、不依赖全局类型。若pnpm版本实际不可用需报告证据，不能擅降版本。优先完成代码/锁更新；集中验证时空node_modules，记录Node/pnpm/lock hash，frozen install不改锁→tsc/vite build→ui:check，必要单测独立列。不要把历史Node类型错误当本轮已复现。交付小提交和原始可复验命令，主agent独立审差异。

## 下一批D/E及集中验收

D先等待B/C交付，再取得14个入队文件的独占权统一替换；V195预留，任何扩大TaskService或research通道需主agent审。E依据二审的完整SQL→hydration→JdbcEvidenceOwnershipAdapter→UI能力边界设计，不能只改候选SQL就宣称用户能用。OTH15保持仅需验收，浏览器工作使用ego-browser技能与独立测试环境，不触生产/付费研究。
