## 其他模块 · chat/conversation/answer
（审查发现如下，分批追加）

## ChatService

### [高] sendMessage 在同一事务内先写库、事务提交后才异步启动生成，assistant 消息预置的是模板占位正文
- 位置：`backend/src/main/java/com/noteweave/chat/ChatService.java:103-135`、`ChatController.java:56-73`
- 问题：`sendMessage`（@Transactional）先把带 `[TEMPLATE PLACEHOLDER]` 标记的 `draft.answer()` 作为 ASSISTANT 消息 content 落库，随后由 Controller 在事务外用 `answerIoExecutor` 异步 `startRun` 触发真正生成。若异步执行被拒（队列满，`RejectedExecutionException ignored`）且客户端从不订阅任何 SSE 端点，则该 ASSISTANT 消息会永久停留在“模板占位原型”正文（`> **[TEMPLATE PLACEHOLDER]** ... 未接 LLM`），answer_run 停在 GENERATING。
- 证据：`ChatController.sendMessage` catch `RejectedExecutionException ignored` 仅注释“canonical SSE endpoint can still recover”，但恢复依赖前端主动连 events 流；无后台兜底扫描。
- 影响：高并发下部分回答永远不会被生成/覆盖，用户看到占位符。
- 建议：增加 answer_run 超时兜底（定时扫描 GENERATING 且无 stream lease 的 run 重新触发），或在写库时不落占位正文而落空串。

### [中] classifyQuestion / isNoiseToken 仅覆盖中英少量关键词，comparison 等分类对真实多语种/长句极易误判
- 位置：`backend/src/main/java/com/noteweave/chat/ChatService.java:345-360,579-586`
- 问题：问题分类完全基于 `contains("比较")` 等硬编码子串，且默认落到 `definition`。QA 策略据此输出“这个问题属于比较类问题”等固定话术，一旦误判会产生与实际不符的解释性文本。
- 证据：`classifyQuestion` 无兜底置信度，`value.contains("source")` 会把任何含 "source" 的英文问题判为 source_lookup。
- 影响：回答话术与问题类型错配，属于可讲的启发式但准确率低。
### [中] buildConversationContext 每轮追问都重新执行历史查询 + 全套主题启发式，且 history 查询按 message_seq 无 workspace/conversation 复合索引保证
- 位置：`backend/src/main/java/com/noteweave/chat/ChatService.java:362-378`
- 问题：仅当 `isContextDependentQuestion` 为真才查历史（limit 12），这点是好的；但该查询在 sendMessage 事务内同步执行，且 `where workspace_id=? and conversation_id=? and id<>? order by message_seq desc` 依赖复合索引，否则连续深聊场景每轮全表扫描。
- 证据：SQL 无 LIMIT 之外的优化提示；无缓存，主题锚点/摘要每轮重算。
- 影响：连续对话热点会话下检索前置延迟增加。
- 建议：确认 `conversation_message(workspace_id, conversation_id, message_seq)` 索引存在；主题编译结果本轮内无需缓存但应确认索引。

### [低] trim 截断实现不一致：ChatService.trim 用 `max-1`+"..." 但当 max<=0 时 substring 越界风险已被 Math.max(0,...) 规避，EvidenceCitationAssembler.trim 无该保护
- 位置：`backend/src/main/java/com/noteweave/chat/ChatService.java:338-343` vs `EvidenceCitationAssembler.java:111-116`、`QaAnswerModeStrategy.java:158-163`
- 问题：`ChatService.trim` 用 `Math.max(0, max-1)`，而 `EvidenceCitationAssembler.trim`、`QaAnswerModeStrategy.trim`、`NoteAnswerModeStrategy.trim`、`WikiAnswerModeStrategy.trim` 均直接 `substring(0, max-1)`。这些调用点 max 都是常量正数（>1），当前安全，但为重复代码且不一致。
- 证据：四处几乎相同的 `trim` 私有方法。
- 影响：坏味道/重复；若将来传入 max<=1 会抛异常。
- 建议：抽取公共 `TextTrimmer` 工具类统一实现。

### [优化] ChatService 内 appendChatControlSection/renderChatControlSection 与各策略的 Chat Control 渲染职责重叠
- 位置：`backend/src/main/java/com/noteweave/chat/ChatService.java:289-314`
- 问题：ChatService 把 chat control 渲染成字符串塞进 attribute，各策略再原样 append，控制包渲染逻辑集中在 ChatService 而非策略，耦合 Memory 概念到 Chat 编排层。
- 影响：可维护性；新增链路必须记得读取 `ATTRIBUTE_CHAT_CONTROL_SECTION`。
## ChatController

### [高] streamAnswerRun：非终态且已有活跃流时，先异步 streamRun(ignored)，再本线程 followRun，二者对 mux/DB 存在竞态且事件可能双发
- 位置：`backend/src/main/java/com/noteweave/chat/ChatController.java:166-190`
- 问题：当 run 处于非终态且 `!hasActiveStream` 时，提交一个 `answerIoExecutor` 任务跑 `chatService.streamRun(...)`（真正生成），同时当前 `sseConnectionExecutor` 线程立即调用 `chatService.followRun` 订阅。生成任务与订阅任务之间没有“订阅已就绪”栅栏，早期 delta 可能在 follow 注册前发布，只能靠 SessionEventMux replay 兜底；`hasActiveStream` 检查与 `claimStream` 之间也存在 TOCTOU（两个并发 events 请求可能都判定无活跃流各提交一次生成，靠 DB `claimStream` CAS 二次拦截）。
- 证据：`hasActiveStream` 读的是 DB lease，`claimStream` 才是真正 CAS；期间无锁。
- 影响：偶发重复 startRun（被 `ANSWER_RUN_ALREADY_STREAMING` 拦截，但产生无用调度）与事件顺序抖动。
- 建议：由订阅侧统一在 follow 注册后再触发生成，或让生成仅经 claimStream 结果驱动。

### [中] canonicalEvent 私有方法为死代码
- 位置：`backend/src/main/java/com/noteweave/chat/ChatController.java:203-211`
- 问题：`private String canonicalEvent(String legacyEvent)` 定义后在整个类中无任何调用（真正做映射的是 AnswerGenerationOrchestrator.canonicalType）。
- 证据：类内 grep 无 `canonicalEvent(` 调用点。
- 影响：死代码/坏味道。
### [中] streamAnswerRun 在 COMPLETED 分支用 `snapshot.content()` 作为 answer.snapshot 且 answer.completed 携带 answerMessageId，与 stream() 事件 id 语义不统一
- 位置：`backend/src/main/java/com/noteweave/chat/ChatController.java:145-165`
- 问题：COMPLETED 时直接发 `answer.snapshot`(content) + `answer.completed`(answerMessageId)，其 SSE id 用 `terminalSequence`/`terminalSequence+1`，但 `terminalSequence+1` 是凭空 +1 的伪序号，可能与 answer_event 真实 seq 冲突/错位；CANCELLED/FAILED 分支只发单事件。前端按 id 去重/游标续传时，`+1` 伪序号会破坏单调游标契约。
- 证据：`Long.toString(terminalSequence + 1)` 未从事件表分配。
- 影响：Last-Event-ID 续传语义受损，可能漏发或重复。
- 建议：终态补发事件应使用真实持久化 seq，或明确该端点为“快照重放”不参与游标续传。

### [中] 两处 resolveCursor / send(SseEmitter,...) 在 ChatController 与 ConversationController 完全重复
- 位置：`backend/src/main/java/com/noteweave/chat/ChatController.java:218-235` 与 `conversation/ConversationController.java:103-132`
- 问题：`resolveCursor`（Last-Event-ID 解析）与 SSE send 封装几乎逐行重复，仅错误码不同。
- 影响：重复代码，维护易漂移（如已出现错误码文案一处中文一处英文）。
- 建议：抽取到公共 SSE 支持类。

### [低] SSE send 抛 IllegalStateException("SSE client disconnected") 会被 orchestrator 的 deliver 吞掉，但 ChatController.send 抛出后 emitter 任务 catch(Exception) completeWithError
- 位置：`backend/src/main/java/com/noteweave/chat/ChatController.java:229-235`
- 问题：客户端断连时抛 IllegalStateException 正常，但该异常在 `streamAnswerRun` 的 `sseConnectionExecutor` 任务里被 `catch (Exception) -> completeWithError` 捕获，属正常收尾；而在 orchestrator followRun 的 consumer 内则被 `deliver` 静默 debug 吞掉——两条路径断连处理语义不一致。
- 影响：断连日志可观测性不一致。
## AnswerGenerationOrchestrator

### [高] stream() 失败分支先 advanceEventSequence 后 fail，但 publishTerminal 使用的是 fail 之后再读的 currentEventSequence，terminal 事件序号可能落后于已发 delta
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerGenerationOrchestrator.java:81-94,216-224`
- 问题：`emit` 通过 `runEventMux.publish` 递增内存 channel.sequence 并返回该序号作为 SSE id，但 answer_run.event_seq（DB）只有在 `advanceEventSequence` 时才被推进到 `runEventMux.currentSequence`。`publishTerminal` 读的是 `answerRunService.currentEventSequence`（DB 值），再 `publishAt(sequence,...)`。若 DB 推进与内存 mux 序号不同步（例如 advance 的 update 条件 `status='GENERATING'` 在 fail 已改状态后失效），终态事件序号可能与之前 delta 序号重叠或倒退。
- 证据：`advanceEventSequence` SQL 带 `and status = 'GENERATING'`；而失败路径顺序是 advance -> fail(改为 FAILED) -> publishTerminal(读 DB seq)。complete 路径同理 advance 后 complete。
- 影响：终态事件 SSE id 与 delta id 可能非严格单调，破坏续传/去重。
- 建议：终态序号应基于内存 mux 的 currentSequence 统一分配，DB event_seq 推进不应依赖 status 条件。

### [高] 异常路径调用 fail 后又 publishTerminal("answer.failed") 会经 conversationEventMux/realtimeBridge 再发一次，且 AnswerRunService.fail 内部已 appendEvent("answer.failed") 落库 —— 终态事件双写
- 位置：`AnswerGenerationOrchestrator.java:85-90` + `AnswerRunService.fail:226-230`
- 问题：`fail()` 内部 `appendEvent(runId,"answer.failed",...)` 已把终态事件写入 answer_event 并推进 event_seq；随后 orchestrator 再 `publishTerminal("answer.failed")` 又向 mux/bridge 发一条 answer.failed。complete 路径同样：`complete()` appendEvent("answer.completed") + orchestrator publishTerminal("answer.completed")。恢复端（streamAnswerRun 的 stored events 回放）会读到 DB 里的 answer.completed/failed，而实时端从 mux 又收到一条，去重仅靠 seq。
- 证据：complete() 第197行 appendEvent("answer.completed")；orchestrator 118行 publishTerminal("answer.completed")。
- 影响：终态事件在实时流与恢复流之间可能重复或序号错配。
### [中] replayCompleted 与 followExistingGeneration 在 run==null（无 answer_run，纯 chat 兼容路径）时不落库不发终态到 DB，历史消息重放依赖内容拆分
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerGenerationOrchestrator.java:107-109,146-156`
- 问题：当 `requireByAssistantRequest` 返回 null（无对应 answer_run）时走 fallbackSequence 纯内存分支，`chat.completed` 只发内存事件、不更新任何持久状态；对于现流程每条消息都建了 answer_run，此分支基本 dead，但仍保留全套逻辑。
- 影响：死/半死分支增加复杂度与测试负担。
- 建议：确认是否仍需支持无 answer_run 的 chat，否则删除 run==null 分支。

### [中] emitDelta 每个 batch 都调用 ensureNotCancelled(run)，取消检测依赖内存 registry + 可选 bridge，取消后已 buffer 的 batch 仍可能先 flush
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerGenerationOrchestrator.java:182-195` + `BufferedAnswerDeltaEmitter.flush()`
- 问题：取消信号通过 `cancellationRegistry.isCancelled` 检测，但 `BufferedAnswerDeltaEmitter` 是 try-with-resources，`close()->flush()` 会在 catch(AnswerRunCancelledException) 之前把 pending 内容发出（close 在 try 块结束时先执行），故取消瞬间的残留 token 仍会作为 delta 送达。
- 证据：try-with-resources 关闭顺序：先 `deltaEmitter.close()`(flush pending) 再进入 catch。
- 影响：取消后仍可能多发一个 batch；不致命但与“立即停止”预期不符。
## AnswerRunService

### [高] appendEvent 用 `select event_seq ... for update` 悲观锁自增序号，但 stream 路径的 delta 事件不走 appendEvent（走内存 mux），两套序号生成机制并存
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerRunService.java:397-415`
- 问题：状态类事件（answer.status/retrieval.summary/citation.upsert/answer.completed 等）经 `appendEvent` 用 `for update` 锁行并 `event_seq=event_seq+1` 落库；而流式 delta 事件由 `SessionEventMux.publish` 在内存里 `++channel.sequence` 生成，仅在结束时 `advanceEventSequence` 把 DB event_seq 拉齐到内存值。两套自增源在并发/恢复场景下难以保证全局单调一致（例如 appendEvent 在 delta 之间插入 citation.upsert 时，DB seq 与 mux seq 会交叉）。
- 证据：complete() 里 persistCitationEvents(appendEvent) 在 advanceEventSequence 之后调用，会把 DB event_seq 再推到 delta 之后，但这些 citation 事件的 seq 从未通过 mux 广播给实时订阅者。
- 影响：实时订阅者与 DB 恢复订阅者看到的 citation 事件序号不同，游标续传可能漏/重。
- 建议：统一事件序号来源（单一权威计数器），delta 与状态事件共用。

### [中] persistCitationEvents 在 complete 之前调用，但 complete 内又 appendEvent(answer.completed)，citation.upsert 与 completed 之间无事务隔离保证顺序
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerGenerationOrchestrator.java:112-118` + `AnswerRunService.persistCitationEvents/complete`
- 问题：orchestrator 顺序为 advanceEventSequence -> persistCitationEvents -> complete。persistCitationEvents 与 complete 是两个独立 @Transactional，中间若失败，会出现 citation 事件已落库但 run 未 COMPLETED 的中间态。
- 影响：崩溃窗口内状态不一致。
- 建议：将 citation 落库与 complete 合并到同一事务。

### [中] hasActiveStream 判定 `stream_lease_until >= current_timestamp`，claimStream 用 `< current_timestamp` 抢占，租约 150s 但 SSE emitter 超时 120s，租约长于连接
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerRunService.java:136,358-364` + `ChatController` SseEmitter(120_000L)
- 问题：stream 租约 150s，但 SSE 连接 120s 超时断开。断开后 150-120=30s 内 lease 仍有效，新连接无法 claim（`hasActiveStream` 为真且 claimStream CAS 失败），只能走 follow 等待，期间若原生成已随连接中断而停止，则回答卡住直到 lease 过期。
- 证据：leaseUntil = now+150s；SseEmitter timeout 120s。
- 影响：断线重连后最长 30s 无法接管生成。
### [中] advanceEventSequence 带 `and status='GENERATING'` 条件，若在 fail/complete 已改状态后调用则静默不推进
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerRunService.java:367-375`
- 问题：`update ... set event_seq=... where ... and status='GENERATING'`。orchestrator 失败路径先 `advanceEventSequence` 再 `fail`，此时仍 GENERATING 可推进；但若并发 cancel 已把状态改为 CANCELLED，则 advance 静默失败（update 0 行但无 requireTransition 校验），DB event_seq 停留旧值，导致后续基于 currentEventSequence 的终态序号偏低。
- 证据：该 update 不校验受影响行数。
- 影响：并发取消 + 完成竞态下事件序号不推进。
- 建议：advance 逻辑不应绑定 status，或用 max() 语义无条件推进。

### [低] eventsAfter 对每个事件调用 eventPayload，其中 citation.upsert 会对每行 objectMapper.readTree 解析，N 条 citation 时 N 次 JSON 解析
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerRunService.java:325-337,417-427`
- 问题：恢复端拉取历史事件时，每条 citation.upsert 都单独 readTree 提取 text 字段。
- 影响：事件多时轻微性能开销（非热点）。
- 建议：可接受，或缓存/批量解析。

### [低] get() 与 listConversationRuns() 的大段 select 列表完全重复
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerRunService.java:265-305`
- 问题：两个方法 select 子句逐字段重复（含 left join message_revision）。
- 影响：重复代码，字段增删易漏改一处。
- 建议：抽取公共 SQL 常量或视图。

### [低] response() 用 `(Integer) rs.getObject("maximum_output_tokens")` 依赖驱动返回 Integer，跨库（如 BIGINT 列）可能 ClassCastException
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerRunService.java:444,446`
- 问题：`(Integer) rs.getObject(...)` 强转，若 DDL 中该列为 BIGINT/其他数值类型，JDBC 可能返回 Long。
- 影响：潜在类型转换异常（取决于列 DDL）。
## EvidenceCitationAssembler / 引用编号一致性

### [高] citation 落库 sort_order 与 prompt 正文中的“证据 1/2/3”编号可能不一致（KNOWLEDGE_VERSION 被跳过导致偏移）
- 位置：`backend/src/main/java/com/noteweave/chat/EvidenceCitationAssembler.java:60-104` + `QaAnswerModeStrategy.compose:130-138`
- 问题：QA 正文按 `bundle.evidence()` 顺序输出“证据 1..N”并把全部 evidence id 作为 referencedEvidenceIds。assemble 遍历 referencedEvidenceIds，对 KNOWLEDGE_VERSION 类型 `continue`（不生成 citation），对 PASSAGE 生成 citation 并按 `result` 中的 index 作为 message_citation.sort_order。若 bundle 混有 KNOWLEDGE_VERSION 与 PASSAGE（Wiki 场景），则正文“证据序号”按 evidence 全序，而 citation.sort_order 只按 PASSAGE 子序，两套编号错位；用户点“证据 3”回跳的 citation 可能不对应。
- 证据：assemble 的 `result.add` index 是 PASSAGE-only 计数，而 compose 的 `index+1` 是全 evidence 计数。
- 影响：引用回跳编号与正文编号不一致（跨类型混合时）。
- 建议：citation sort_order 应与正文引用编号采用同一 evidence 全序基准。

### [中] EvidenceCitationAssembler.persist 与 ChatService.bindExistingCitations 的 sort_order 拼接依赖调用方传入 newCitationCount 作为 offset，耦合脆弱
- 位置：`backend/src/main/java/com/noteweave/chat/EvidenceCitationAssembler.java:35-40` + `ChatService.java:112-115,316-325`
- 问题：persist 内部对新 citation 用 `index`(0..n-1) 作 sort_order，返回 count；ChatService 再用该 count 作为 bindExistingCitations 的 `sortOffset`。若 persist 内部排序与返回 count 语义变化，binding 偏移即错。两处对 sort_order 空间的约定是隐式的。
- 影响：Wiki 既有 citation 与新 citation 的排序耦合，易回归。
- 建议：由单一装配器统一管理 message_citation 全序写入。

### [中] persist 对同一 message 的 citation 无幂等保护，重试/重复调用会重复插入 message_citation
- 位置：`backend/src/main/java/com/noteweave/chat/EvidenceCitationAssembler.java:25-41`
- 问题：无 `on duplicate`/去重，若 sendMessage 事务因外层重试重复执行，会对同一 assistantMessageId 生成两套 citation。
- 影响：重复引用。
## QaPassageRetriever / RAG 检索

### [高] Integer.parseInt(hit.chunkNo()) 未做 NumberFormatException 防护，搜索引擎返回非数字 chunkNo 会整条检索抛异常
- 位置：`backend/src/main/java/com/noteweave/chat/QaPassageRetriever.java:123,258`
- 问题：`Integer.parseInt(hit.chunkNo())` 在主检索路径和 admitSearchHits 里直接解析外部搜索命中的 chunkNo。若 ChunkSearchPort（Elasticsearch 等）返回非数字或空，抛 NumberFormatException——虽被外层 `catch (Exception)` 捕获并降级到 mysql fallback，但会把整批有效命中丢弃。
- 证据：try 块内 254-267 admitSearchHits 也 parseInt，异常会冒泡到 154 的 catch。
- 影响：单条脏数据导致整个主检索通道降级。
- 建议：逐条 try/skip，或在 ChunkSearchHit 层保证 chunkNo 数值化。

### [中] measurements 中 `mysql_relevance_rejected_count` 计算为 `(long) scopedCandidates.size() - relevantCandidates.size()` 运算符优先级正确但可读性差且未防负
- 位置：`backend/src/main/java/com/noteweave/chat/QaPassageRetriever.java:241-242`
- 问题：`(long) scopedCandidates.size() - relevantCandidates.size()`——强转只作用于第一个 size()，结果为 long 差值，逻辑上 relevant 是 scoped 的子集不会为负，但表达式脆弱。
- 影响：可读性/健壮性。
- 建议：显式括号 `(long)(a - b)` 或 Math.max(0, ...)。

### [中] mysql fallback 查询 `limit 80` 后再 admit+select，但 order by s.updated_at desc 与相关性无关，脏 fallback 可能选出与 query 无关的最近资料
- 位置：`backend/src/main/java/com/noteweave/chat/QaPassageRetriever.java:198-201,216-233`
- 问题：fallback 先取“最近更新的 80 个 chunk”，再本地 `score()` 关键词打分。若相关 chunk 不在最近 80 内则永远召不回；`score()` 对空 terms 返回 1（全部并列），此时选择退化为“最近资料”。
- 证据：`if (terms.isEmpty()) return 1;` + order by updated_at。
- 影响：无有效关键词或资料量大时，fallback 相关性差（已标 degraded，可接受但需知晓）。
- 建议：fallback 也应有 workspace 级全文检索或扩大候选。

### [低] retrieve(workspaceId, query) 与 retrieve(workspaceId, query, sourceScope) 两个 public 重载疑似死代码
- 位置：`backend/src/main/java/com/noteweave/chat/QaPassageRetriever.java:43-53`
- 问题：真正入口是 `retrieveWithDiagnostics`（经 QaPassageEvidenceRetriever 调用）；两个只返回 chunks() 的重载可能仅测试使用。
- 影响：潜在死代码。
## RetrievalService 删除后残留检查

### [信息] RetrievalService.java 删除后无悬空引用
- 位置：`backend/src/main/java/com/noteweave/chat/`（git 显示 D RetrievalService.java、D KnowledgeService.java）
- 结论：全仓 grep `RetrievalService` 的命中全部指向 `NoteRetrievalService`（不同类，仍存在），无任何对已删除 `com.noteweave.chat.RetrievalService` 的 import 或调用。删除干净。
- 备注：`QaPassageRetriever.retrieve(...)` 两个重载已成事实死代码（仅测试引用），可能是 RetrievalService 拆分后的残留；见 QaPassageRetriever 条目。

## ConversationController / ConversationService

### [中] streamConversation 先发 conversation.snapshot（含最多 20 条 run），再 follow；snapshot 与 follow 之间的事件存在窗口丢失/重复
- 位置：`backend/src/main/java/com/noteweave/conversation/ConversationController.java:76-92`
- 问题：任务内先 `listConversationRuns(...,20)` 组 snapshot 发出，然后 `conversationEventMux.follow(cursor,...)`。snapshot 读取与 follow 订阅注册之间若有新事件发布，且该事件序号 > cursor 但未进入 snapshot、又早于 follow 的 replay 起点，则可能错位。cursor 来自客户端 after/Last-Event-ID 与 snapshot 内容无关联，前端需自行对账。
- 影响：断线续传时快照与增量边界不严格。
- 建议：snapshot 应携带其对应的 conversation 事件序号，follow 从该序号续。

### [低] streamConversation 无 Service 层权限校验，仅依赖全局 WorkspaceAuthorizationInterceptor（防御纵深不足）
- 位置：`backend/src/main/java/com/noteweave/conversation/ConversationController.java:61-101`
- 问题：`streamConversation` 只调 `requireConversation`（存在性校验），无 `WorkspaceAccessGuard.requirePermission`。经核实 `WorkspaceAuthorizationInterceptor`（`WebMvcAuthorizationConfig` 注册于 `/api/v2/**`）的 `WORKSPACE_PATH` 会对 `/api/v2/workspaces/{id}/conversations/{cid}/events` GET 强制 `WORKSPACE_READ`，因此**不构成越权**。但 answer 流路径（ChatController.followRun/streamRun）在 Service 层仍额外做了 requirePermission，会话事件流未做，防御纵深不一致。
- 影响：若将来路径重构导致 interceptor 正则不匹配，会话事件流将失去校验。
- 建议：Service 层补一层 requirePermission(WORKSPACE_READ)，与 answer 流对齐。

### [中] cancelAnswerRun (DELETE) 经 interceptor 落到默认 WORKSPACE_ADMIN 权限，取消回答需要管理员权限，过严
- 位置：`backend/src/main/java/com/noteweave/chat/ChatController.java:107-115` + `security/WorkspaceAuthorizationInterceptor.java:55-84`
- 问题：`DELETE /api/v2/workspaces/{id}/answer-runs/{runId}` 命中 WORKSPACE_PATH，但 `workspacePermission` 对 DELETE 且路径含 `answer-runs` 的情况无任何分支匹配，最终 fall-through 到 `return WorkspacePermission.WORKSPACE_ADMIN`。即取消自己发起的回答运行需要 WORKSPACE_ADMIN。而创建/读取回答只需 ANSWER_RUN/WORKSPACE_READ，取消却要 ADMIN，权限阶梯不合理。
- 证据：interceptor 无 `answer-runs` 分支，默认返回 WORKSPACE_ADMIN。
- 影响：普通协作者无法取消自己的回答生成。
## WikiGraphBudgeter

### [中] graphHopsUsed 恒为 0/1，实现只做单跳扩展，未消费 budget.maxGraphHops>1 的图预算
- 位置：`backend/src/main/java/com/noteweave/chat/WikiGraphBudgeter.java:48-54`
- 问题：`graphHopsUsed = usage.edgeCount()>0 ? 1 : 0`，且 apply 只从 roots 展开一层 outgoing/backlinks，从不做二跳。`RetrievalPlan.Budget.maxGraphHops` 虽支持 >1（默认构造给 1），但即使传入更大 hop 预算也不会多跳扩展。
- 证据：无递归/队列遍历，单层 for。
- 影响：与“页面知识网络多跳”设计意图有差距（当前 Wiki plan maxHops=1，尚未暴露，但契约留有 hop 预算字段却未实现多跳）。
- 建议：若不做多跳应在文档/契约中标注 hop 上限为 1，避免误导。

### [低] linkCharacterCost 用标题+状态+mentionCount 字符长度作为 token proxy，与文档声明一致但纯启发式
- 位置：`backend/src/main/java/com/noteweave/chat/WikiGraphBudgeter.java:94-99`
- 问题：字符成本 proxy 固定 +10 基数，文档已明确“不是精确 token 计数”，实现与文档一致，属可接受近似。
- 影响：无。
- 建议：保持，注释已足。

## Snapshot Codec (Note/Wiki)

### [低] 两个 SnapshotCodec 实现逐行重复，仅错误码/类型不同
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRetrievalSnapshotCodec.java` 与 `WikiRetrievalSnapshotCodec.java`
- 问题：encode/decode 结构完全相同，可泛型化为 `SnapshotCodec<T>`。
- 影响：重复代码。
- 建议：抽取泛型基类。

### [低] decode 失败抛 BusinessException（默认 500 级），但快照是本请求内自产自消，理论不应失败；一旦 Jackson 版本/字段变更会使整条回答 500
- 位置：`NoteRetrievalSnapshotCodec.decode:30-39` + `NoteAnswerModeStrategy.compose:69`
- 问题：compose 阶段 decode 快照失败会中断回答生成。快照由同实例 encode，正常不失败，但字段演进/反序列化兼容性问题会放大为回答失败。
- 影响：脆弱耦合。
## NoteRetrievalService / Note 链路

### [中] Note 元信息装配在检索同步路径内执行大量 SQL + 关系图传播，Note 回答检索前置延迟高
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRetrievalService.java:46-83,132-203,261-266`
- 问题：`readEntriesMetadataForNote` 对每次 Note 提问同步执行：hydrateNoteWindows、hydrateNoteSourceStats、relatedEntriesForSources（含 relationRows limit 30 查询 + 双 knowledge_version_citation 自连接 co-citation 查询），再对每个 anchor 调 `noteRelationGraph.build/propagate(...,4,0.2)` 做 4 轮传播。整条链路串行、无缓存。
- 证据：co-citation SQL 两次 join knowledge_version_citation + citation；propagate 迭代 4 次。
- 影响：Note 链路检索延迟随 workspace 资料/引用规模增长明显。
- 建议：关系信号异步预计算或缓存；co-citation 结果按 workspace 短期缓存。

### [中] relatedEntriesForSources 的 relationRows 与 co-citation 均按 `order by updated_at desc limit 30`，与 anchor 主题无关，关系候选可能与当前问题无关
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRetrievalService.java:141-155,218-221`
- 问题：关系候选来源是“最近更新的 30 条 source”，再按 tag/词法重叠过滤。若相关资料不在最近 30 内则无法作为关系扩展候选。
- 影响：关系扩展召回受“最近性”偏置。
- 建议：候选池应结合 query 相关性而非仅 updated_at。

### [低] extractTags 仅作为 parseJsonStringArray 的异常兜底调用，正常路径不触发
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRetrievalService.java:84-94,104-106`
- 问题：`extractTags` 用正则从 tagsJson 抽取，仅在 Jackson 解析失败时兜底。功能重复（正则解析 JSON 字符串数组）且脆弱。
- 影响：坏味道。
- 建议：确认 tags_json 始终合法 JSON 后可删除正则兜底。

### [低] CandidateSource / ReadingWindow 为超宽 record（19/15 字段）并配多个 withXxx 拷贝方法，任一字段增改需同步所有 withXxx
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRetrievalService.java:359-453`
- 问题：19 字段 record + 5 个 withXxx 手写全量构造，极易漏字段。
- 影响：可维护性差，易引入拷贝错误。
### [高] Note 链路每次提问全 workspace 扫描 + 关系图传播重复计算，O(workspace 资料量)，无 query 预过滤
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRecallRetriever.java:37-100,175-177` + `NoteRetrievalService.java:261-266`
- 问题：`retrieve` 调 `repository.findCandidates(workspaceId)` 拉全部候选、`relationGraph.build(sources,...)` 在**全部** source 上建图并 `propagate(...,4,0.2)`；随后 Note 元信息装配阶段 `NoteRetrievalService.relatedEntriesForSource` 又对每个 anchor 再次 `build/propagate`。关系图传播在单次 Note 提问内至少计算两遍，且规模随 workspace 资料量线性增长，无基于 query 的候选裁剪。
- 证据：NoteRecallRetriever 第175-177 与 NoteRetrievalService 第261-266 均 build+propagate。
- 影响：大 workspace 下 Note 链路检索延迟与 CPU 显著，且重复计算。
- 建议：关系图构建结果在单次请求内复用；候选先按 query/metadata 裁剪再建图。

### [中] freshness(workspaceId) 聚合查询在同一次 Note 提问中被执行两次
- 位置：`backend/src/main/java/com/noteweave/chat/NoteJournalRetriever.java:24,47,67-82`
- 问题：`retrieve()` 与 `sourceSignals()` 各自独立调用 `freshness(workspaceId)`，而 NoteRecallRetriever 在同一次提问里先后调用 `sourceSignals` 与 `retrieve`，导致相同的 note 新鲜度聚合查询（多表 left join + group by）跑两遍。
- 证据：NoteRecallRetriever:39-40 连续调用 sourceSignals 和 retrieve。
- 影响：重复 DB 聚合查询。
- 建议：freshness 结果在一次提问内复用/传参。

### [中] sourceSignals 查询无 LIMIT，加载 workspace 全部 NOTE-citation-source 关联行
- 位置：`backend/src/main/java/com/noteweave/chat/NoteJournalRetriever.java:48-55`
- 问题：`select ... from knowledge_item ... join citation ... where workspace_id=?`（无 limit），随 Note 与引用规模线性增长全部载入内存打分。
- 影响：内存/延迟随规模增长。
## 文档偏差

### [中] 事件类型命名偏差：契约文档写 `answer.citation`，实现发布/持久化为 `citation.upsert`
- 位置：`docs/API与事件契约-v2.md:196`（answer.citation）vs 代码 `AnswerRunService.persistCitationEvents:343`（"citation.upsert"）+ `AnswerGenerationOrchestrator.canonicalType:256`（chat.citation -> citation.upsert）+ `ChatController.canonicalEvent:206`（同样映射到 citation.upsert）
- 问题：正式 answer 事件流的引用事件类型，文档声明为 `answer.citation`，实现统一用 `citation.upsert`。前端若按文档实现将收不到引用事件。
- 影响：契约与实现不一致，前端集成风险。
- 建议：统一命名（改文档或改代码），并在 canonicalType 映射处标注权威名。

### [中] 兼容 chat 流仍是“模板占位原型”，未接 LLM 时正文带 `[TEMPLATE PLACEHOLDER]` 标记
- 位置：`ChatService.prefixWithTemplateMarker:246-249` + `ChatAnswerGenerationAdapter.prepareDraft/generate:47-92`
- 问题：assistant 消息落库正文强制加 `> **[TEMPLATE PLACEHOLDER]** 当前答案为 xxx 拼接原型，未接 LLM`。生成时 prepareDraft 剥离标记后：LLM 启用则把“拼接原型”作为 user prompt 交给模型改写；未启用则 replayChunks 原样回放（含被剥离前的原型正文）。即在 LLM 未配置的部署中，用户最终看到的是模板拼接文本而非真实回答。
- 证据：configuredModel() 返回 "template-fallback" 时全链路为模板回放。
- 影响：与“统一聊天助手基于证据回答”的设计目标存在阶段性差距（已知占位状态）。
- 建议：确认上线部署 LLM 已启用；否则前端应隐藏占位标记或明示为原型。

### [低] retrieval.summary 文档示例 plan_version 为 `qa-passage-v1`，实现 QA plan 版本为 `qa-passage-v2`
- 位置：`docs/API与事件契约-v2.md:216,227` vs `QaAnswerModeStrategy.V2_PLAN_VERSION="qa-passage-v2":21-23`
- 问题：文档示例仍用 v1 版本号，实现已升级到 v2。
- 影响：文档滞后。
## NoteRecallRepository / NoteRelationGraph

### [中] sourceIdsByNote / sourceIdsByAnsweredTurn 为无 LIMIT 的 workspace 全量关联查询，且每次 Note 提问被调用两次
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRecallRepository.java:61-96`
- 问题：两查询各自加载 workspace 全部 NOTE 引用对 / 全部 ASSISTANT 消息引用对（无 limit）。NoteRecallRetriever.retrieve 调一次，NoteRetrievalService.relatedEntriesForSources 又调一次（同一次 Note 提问）。
- 影响：随会话与 Note 规模线性膨胀，且重复。
- 建议：请求内复用结果；必要时分页/裁剪。

### [低] NoteRelationGraph.build 为 O(候选²)，coCitation 内层再遍历所有 group，整体近 O(n²·groups)，且每问计算两次
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRelationGraph.java:35-47,84`
- 问题：双重 for 对 40 候选构成 ~780 对，每对 `coCitation` 再遍历所有 note/turn group。配合前述“每问 build 两次”，CPU 成本翻倍。
- 影响：大 workspace CPU 峰值。
- 建议：预建倒排（group->sources）加速 co-citation，结果请求内缓存。

### [低] findCandidates 用 `min(c.content)` 作为 sample_text，语义为“字典序最小的 chunk 文本”，非代表性样本
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRecallRepository.java:30`
- 问题：`coalesce(min(c.content),'')` 取聚合最小值，对排序/展示无意义，仅为凑一个样本文本。
- 影响：sample_text 质量差（用于 metadataForScoring 打分会引入噪声）。
- 建议：取首个 chunk（chunk_no 最小）的 content 更合理。

## 其他记录/接口类

### [低] AnswerMode.parse 对 null/空白静默返回 QA，但 SendMessageRequest 已强制 @NotBlank answerMode，二者默认策略重复
- 位置：`answer/strategy/AnswerMode.java:11-14` + `chat/SendMessageRequest.java:8-16`
- 问题：请求层强制 answerMode 非空并 upper-case，AnswerMode.parse 又对 null/空白兜底返回 QA。双重容错，行为分散。
- 影响：轻微不一致（对非法值 parse 抛 ANSWER_MODE_UNSUPPORTED，对空返回 QA）。
- 建议：统一由一处决定默认模式。

### [低] ChatLlmClient.streamChat(messages,...) 默认实现把 ASSISTANT 历史折进 user 段，与实际多轮语义不符（当前未被调用）
- 位置：`chat/ChatLlmClient.java:38-49`
- 问题：多轮重载把 assistant 消息以 `[上一轮助手]` 前缀拼进 user prompt。当前 ChatAnswerGenerationAdapter 只用双参 streamChat，该重载为未使用的默认实现。
- 影响：潜在死代码 + 语义近似。
## 测试缺口

### [高] AnswerRunService 状态机（claimStream CAS / complete / fail / cancel / advanceEventSequence）无专门单元测试
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerRunService.java`（无 `AnswerRunServiceTest`）
- 问题：核心并发状态机（乐观锁 version、stream lease 抢占、非法转换、终态幂等）仅可能被 Phase 契约测试间接触及，无针对 CAS 竞态、lease 过期抢占、advanceEventSequence status 条件失效等边界的直接测试。
- 影响：并发回归无护栏。
- 建议：补 AnswerRunService 状态机与并发抢占单测。

### [高] AnswerGenerationOrchestrator 流式/终态/取消编排无单元测试
- 位置：`backend/src/main/java/com/noteweave/answer/AnswerGenerationOrchestrator.java`（无对应 test）
- 问题：stream() 的 claim/replay/follow 分支、取消中断、失败终态序号、citation 落库顺序等关键逻辑无直接测试。
- 影响：SSE/终态/取消回归风险高（前述多条竞态/双写发现均落在此类）。
- 建议：补 orchestrator 编排测试（含取消、失败、并发订阅）。

### [中] ChatController / ConversationController / ConversationService 无 Controller 层测试
- 位置：`chat/ChatController.java`、`conversation/ConversationController.java`、`conversation/ConversationService.java`
- 问题：SSE 端点游标解析、RejectedExecution 兜底、snapshot+follow 边界、权限（cancel 需 ADMIN 等）无 MockMvc/切片测试。
- 影响：HTTP 契约与降级路径无护栏。
- 建议：补 Controller 切片测试。

### [中] ChatService 连续对话上下文窗（buildConversationContext 及话题边界/摘要）无针对性单测，仅 Wiki citation 选择被测
- 位置：`chat/ChatService.java`（仅 `ChatServiceWikiCitationSelectionTest`）
- 问题：文档 §10 要求的“连续三轮/超窗摘要/话题切换”验收，仅可能在 Phase1And2ContractTest 覆盖；ChatService 内大量启发式（classifyQuestion/topic anchor/summary）无隔离单测。
- 影响：启发式回归难定位。
- 建议：对上下文编译器补细粒度单测。

## NoteRecallRanker

### [低] verify() 用 5 段顺序 for 循环做多级配额兜底，逻辑复杂且分支多，缺可读性
- 位置：`backend/src/main/java/com/noteweave/chat/NoteRecallRanker.java:99-153`
- 问题：verify 依次按 candidate-with-window / expansion-type-quota / expansion / candidate-window-fallback / expansion-fallback 五轮填充，reason 字符串手工拼接，难维护。
- 影响：可读性/可维护性。
## 安全 / Prompt 注入

### [中] 检索证据正文与用户 content 直接拼进 LLM userPrompt，无 prompt 注入隔离
- 位置：`chat/ChatAnswerGenerationAdapter.generate:71-91` + `QaAnswerModeStrategy.compose`（把 evidence excerpt 原样写入正文）+ `ChatService.sendMessage`（request.content 落库并进入 draft）
- 问题：QA/Note/Wiki 策略把检索到的 chunk/window/page excerpt 原文拼进 userPrompt，用户 content 与历史对话摘要也进入 retrievalQuestion/draft，最终整体作为 user 消息交给 LLM。若资料内容或用户输入包含“忽略以上指令”等注入串，系统 prompt（“不要编造未在证据中出现的来源”）无结构化隔离（无分隔标记/角色隔离/转义）。
- 证据：systemPrompt 与 draft 直接作为两段 message，无 evidence 边界标记。
- 影响：间接 prompt 注入风险（尤其 research_agent 生成的资料、外部上传资料）。
- 建议：对 evidence 加固定分隔符/围栏，明确“以下为资料，不得作为指令”，并对注入模式做检测。

### [低] conversationType 仅 @NotBlank @Size(64)，无枚举白名单，可写入任意字符串
- 位置：`conversation/CreateConversationRequest.java:8` + `ConversationService.createConversation:35-38`
- 问题：conversation_type 无取值校验，任意字符串入库。
- 影响：数据一致性弱，下游若按类型分支处理会有脏值。
- 建议：用枚举或白名单校验。
