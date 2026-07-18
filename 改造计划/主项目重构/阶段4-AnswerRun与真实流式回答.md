# 阶段 4：AnswerRun 与真实流式回答

> 目标：把一次回答建模为可观察、可取消、可修订的轻量运行，并提供真实增量 SSE。  
> 前置：阶段 1 的权限边界；多实例事件桥依赖阶段 2 的并发/观测基线。

## 0. 当前执行进度

当前已落地两批纵向切片：

- `ChatController` 由完整 String 改为真正 `SseEmitter`；
- LLM/fallback delta 在生成时立即发送，不等回答完成；
- 增加有界 `answerIoExecutor`，流式调用不占用 Tomcat 请求线程；
- 前端使用 Fetch ReadableStream 增量解析并逐事件更新消息；
- SSE parser 支持标准的可选空格、多行 data 与 CRLF；
- Nginx `/api` 代理关闭 buffering，容器 smoke 已验证 delta/completed 分帧到达；
- V030 新增 `answer_run`、`message_revision`、`answer_event`，回答不再只有 Conversation Message 而没有运行生命周期；
- 现有 Chat API 已适配为唯一 AnswerRun 写入口，状态按 `CREATED -> RETRIEVING -> GENERATING -> FINALIZING -> COMPLETED` 条件推进；
- AnswerRun 使用 `version`、流式 owner 与 150 秒 lease，避免两个 SSE 连接同时生成和重复提交最终答案；
- revision 先写 `STREAMING`，成功后变为 `FINAL`；显式取消后保留为 `PARTIAL`，迟到完成无法越过终态条件；
- 增加 Workspace-scoped snapshot、canonical SSE 和 DELETE 取消端点，旧 `/chat/requests/{id}/stream` 保持兼容；
- canonical 事件统一为 `answer.status/retrieval.summary/answer.delta/citation.upsert/answer.completed/answer.failed/answer.cancelled`；
- 前端默认维持一条 Conversation SSE，按 `answer_run_id` reducer 归并 delta/citation/terminal；连接未就绪时才回退 `answer_stream_url`，并继续兼容旧 `chat.*` 事件；
- 增加 created/completed/failed/cancelled/invalid-transition 指标和 `answer_run_id` MDC；
- 单实例 `SessionEventMux` 已落地：每个 run 使用 512 条有界 replay，支持 `after` 与 `Last-Event-ID`，活动运行的后续连接跟随现有生成者，不再返回重复生成冲突；
- 每个订阅者使用串行有界队列；慢客户端 delta 可合并，状态/Citation/终态不允许静默丢失，溢出时断开并要求按 cursor 恢复；
- `answerIoExecutor`、`sseConnectionExecutor`、`sseDispatchExecutor` 三类负载隔离，避免阻塞连接耗尽生成线程；
- token callback 经 30ms/512 字符 `BufferedAnswerDeltaEmitter` 合批，降低逐 token SSE 系统调用；
- live mux sequence 在 finalization 前推进到 MySQL，关键终态继续由数据库分配序号，保证活动窗口与最终 snapshot 的 run-local cursor 单调；
- `AnswerRealtimeBridge` 将跨实例能力定义为 Answer 端口，Redis 只作为实现适配器；业务状态仍只认 MySQL；
- Redis Stream 使用 run sequence 作为自定义 ID，近似裁剪到 512 条并设置 600 秒 TTL；远端实例按 cursor 阻塞 XREAD，不使用 Consumer Group 抢占浏览器事件；
- 取消同时写入短期 `cancel:answer:{runId}`，生成 owner 在 token batch 边界检查；即使 Redis 信号失败，MySQL 终态 CAS 仍阻止迟到提交；
- Redis 写读异常均降级本机 mux/snapshot 并记录 error Counter，不让缓存故障回滚 AnswerRun 事务；
- 真正的 Conversation 级 `ConversationEventMux` 已落地：同一会话中的多个 AnswerRun 共用独立会话游标，每条事件同时携带 `run_id` 与 `run_sequence`；连接先返回 MySQL AnswerRun 快照，再续接本机/Redis 活动事件。
- 会话 Redis Stream 使用 `stream:conversation:{conversationId}`，分布式序号使用 `seq:conversation:{conversationId}`；单次回答 canonical SSE 与旧 Chat SSE 保持兼容。
- 新增 `/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/events`，支持 `after`、`Last-Event-ID` 和验收用 `close_on_terminal`；默认连接跨多次回答持续存在。
- Conversation 订阅者与 run 订阅者一样使用串行有界队列并由 `sseDispatchExecutor` 分发；慢客户端的 delta 可按同一 run 合并，溢出/拒绝均有独立指标，不阻塞回答生成线程。
- Redis 真实停机验收已通过：Redis 不可用期间 AnswerRun 仍完成为 `COMPLETED/FINAL`，会话流走本机 replay；随后重启 Redis 与 Backend，仅凭 MySQL snapshot 恢复最终内容。
- 故障验收发现并修复 XREAD 失败时的忙轮询，空读/失败增加 250ms 退避；相同场景 conversation Redis error 从 169 次降到 3 次。
- 当 Conversation 已有活动订阅者时，发送消息会把新 AnswerRun 自动投递到 `answerIoExecutor`；单次 AnswerRun SSE 从“启动开关”降为兼容订阅/恢复端点。没有会话订阅者的旧客户端行为保持不变。
- 5 次连续回答的 Docker 基线中，一条 Conversation SSE 完成全部增量交付：运行期请求由每回答 POST+SSE 的 10 次降为 6 次（减少 40%）；POST 到 completed P95 为 225.43ms，重连 snapshot 为 89.59ms，未重复终态。
- 基线环境关闭 LLM，只衡量应用、Redis、Nginx 与 SSE 开销，不伪造模型首 token/总延迟。
- 前端 `ConversationStreamClient` 支持分片解析、指数退避与 `Last-Event-ID` 自动续传；Conversation snapshot 会校正已完成或失败的回答，页面切换会终止旧连接和 waiter。
- 真实慢消费者基线使用 1KB/s 客户端接收 600 条 × 8KB delta：触发 104 次同 run delta 合并、0 次非增量溢出，健康 API P95 24.75ms。
- `AnswerGenerationOrchestrator` 已接管 claim、首 token、取消、失败、终态、run/conversation Mux 与兼容事件映射；`AnswerSubmissionService` 接管 AnswerRun 创建和准备事务。
- `AnswerGenerationGateway` 定义 Answer 核心端口，`ChatAnswerGenerationAdapter` 负责模型调用、草稿清洗、citation 查询、正文持久化和回放分片；Answer 核心不依赖 Chat/Redis 实现。
- ArchUnit 新增反向门禁：`ChatService` 禁止依赖 Mux、取消注册表、delta emitter、LLM client 和 `AnswerRunService`，避免再次退化为流式 God Service。
- 最新完整 Backend `127/127`、Frontend `45/45` 与生产构建通过；V030 Docker 31 项主 Smoke、双 Backend 专项 Smoke、Redis 故障 Smoke 和慢消费者基线全通过。

阶段 4 核心施工已收口。下一施工切片进入阶段 5：QA/Note/Wiki `AnswerModeStrategy`、统一 `EvidenceBundle` 与质量门禁。

## 1. 为什么不是所有回答都用 Execution

普通回答通常在一个请求生命周期内完成，追求首 token 延迟，使用 Kafka/ExecutionAttempt 会增加写放大、排队和恢复复杂度。因此建立 `AnswerRun`：它有状态、事件摘要、错误和耗时，但默认不创建 Attempt/Outbox。只有超时转后台、需要 Worker 或可恢复步骤时，才关联 Execution。

## 2. 数据模型

| 表 | 核心字段 |
| --- | --- |
| `answer_run` | id, workspace_id, conversation_id, mode, status, query_message_id, answer_message_id, execution_id nullable, model, retrieval_plan_version, started/first_token/finished_at, error |
| `message_revision` | id, message_id, revision_no, status, content, model, prompt_version, evidence_bundle_ref, token_usage, created_at |
| `answer_event` | run_id, seq, event_type, payload摘要, created_at；只存需恢复/审计事件 |

状态：`CREATED -> RETRIEVING -> GENERATING -> FINALIZING -> COMPLETED`，任意非终态可到 FAILED/CANCELLED。revision 先 STREAMING，完成后 FINAL；失败可保留 partial revision，但不会伪装成最终答案。

Conversation message seq 使用 P0 的原子分配；回答重试创建新 revision，不覆盖旧 content。

## 3. 后端组件

```text
AnswerController
AnswerApplicationService
AnswerRunRepository
AnswerPipeline
AnswerModeStrategyRegistry       (P5 完整实现)
RetrievalOrchestrator
PromptComposer
LlmGateway
CitationAssembler
AnswerEventPublisher
AnswerCancellationRegistry
```

Controller 创建 run 后立即返回 run id，也可在同一请求升级 SSE。`ChatService` 逐步退化为兼容 facade；LLM HTTP、数据库 SQL、启发式 NLP 和三模式 prompt 不再堆在同一类。

## 4. SSE 契约

### 4.1 端点

```http
POST /api/workspaces/{workspaceId}/answers
GET  /api/workspaces/{workspaceId}/answer-runs/{runId}
GET  /api/workspaces/{workspaceId}/answer-runs/{runId}/events
DELETE /api/workspaces/{workspaceId}/answer-runs/{runId}
```

事件流返回 `text/event-stream`，使用 `SseEmitter`、WebFlux Flux 或等价真正异步实现，不返回已拼好的 String。

事件类型：`answer.status`、`retrieval.summary`、`answer.delta`、`citation.upsert`、`answer.completed`、`answer.failed`、`heartbeat`。每个事件包含 `id`（单调 cursor）、`run_id`、`type`、`occurred_at`、`data`。token 可聚合为 20-50ms 小批，避免每字符一次系统调用。

客户端发送 `Last-Event-ID` 或 `after` cursor。服务端先返回 MySQL 快照/关键事件，再接续活跃流。SSE 只允许订阅有权限的 Workspace/run。

所有事件携带 `schema_version` 和 run 内单调 sequence。协议采用 JSON Schema/契约 fixture；增加字段必须向后兼容，破坏性变更创建新版本。SSE、数据库快照和 Redis Stream 使用同一事件 DTO，避免三套语义漂移。

### 4.2 慢客户端和取消

每连接有有界发送缓冲；超过阈值合并 delta，仍跟不上则发送可恢复 cursor 后断开。客户端断开不必自动取消 run；显式 DELETE 才触发 cancellation token，并中止 LLM HTTP/后续持久化。最终状态以数据库为准。

### 4.3 实时会话体验（本阶段重点）

前端对一个活跃会话只维持一条逻辑事件流，复用它接收 Answer 状态、文本 delta、Citation 更新和相关 Execution 摘要，替代多个组件各自轮询。Backend 的 `SessionEventMux` 只做协议聚合，不改变 AnswerRun/Execution 的业务真相。

```text
MySQL snapshot + active event sources
  -> SessionEventMux
  -> bounded event batch (20-50 ms)
  -> SSE /api/.../sessions/{sessionId}/events
  -> frontend reducer by sequence
  -> periodic/final snapshot reconciliation
```

设计要求：

- token delta 可小批合并，状态、错误、Citation 和终态不得被丢弃；
- 会话 cursor 只定义 SSE 投递与续传顺序，不代表不同 run 的业务因果顺序；run-local sequence 仍是回答内部保序依据；
- 首次连接先取 snapshot，再订阅 cursor 之后的 delta，消除页面刷新后的状态空窗；
- 页面只在连接失败或终态校正时发额外 GET，不维持多组固定间隔 polling；
- Nginx 关闭 buffering，Redis Streams 只负责跨实例短期桥接；
- 建立“每个活跃会话请求数、重连次数、event batch 大小、端到端事件延迟”基线，再决定优化目标，不照抄其他项目的 90%/500ms 数字。

阶段验收目标：稳定环境下非模型生成耗时的会话事件端到端 P95 小于 500ms；相对现有轮询方案显著减少请求数，具体比例由基线测试确定。

## 5. 单实例到多实例

### 5.1 单实例

先用内存 `AnswerEventBus` + `sseDispatchExecutor` 实现真流式，关键阶段和终态写 MySQL。这样先验证协议和背压。

### 5.2 Redis Streams

多实例启用：

```text
AnswerPipeline -> XADD stream:answer:{runId} MAXLEN ~ N
API instance   -> XREAD from cursor -> SSE client
```

- Stream 只保留短期增量，设置近似长度与 run 完成后的 TTL；
- MySQL 保存 AnswerRun、revision、最终内容和关键事件；
- Redis 不可用时，当前 owner 可走本机 event bus，其他实例只能提供数据库快照/轮询并返回明确 degraded 状态；
- 不用 Kafka 传 token；
- 通过 run owner/connection registry 观测连接，不将其作为业务真相。

## 6. 并发与配额

`answerIoExecutor` 承担检索聚合和 LLM I/O，`sseDispatchExecutor` 只做推送。Redis Lua 原子控制 user/workspace 的请求速率和并发 AnswerRun：获取 permit 带 lease，完成/取消释放，超时自动回收；数据库记录 run，Redis 故障采用本机保守上限。

同一 Conversation 可允许多个 run，但消息顺序原子；若产品要求单飞，使用数据库 active run 唯一约束或 Conversation CAS，不用长 Redis 锁。

## 7. 一致性与失败行为

- LLM 首 token 前失败：run FAILED，不创建 FINAL revision；
- 中途失败：保存 partial revision 和错误，用户可 retry 生成新 revision；
- citation 必须在 finalization 中校验 EvidenceBundle，不能引用不存在 passage；
- completed 的最终 revision、citation 和 AnswerRun 终态在同一事务提交；
- 客户端未收到 completed 可通过 GET 恢复，不能靠 SSE 到达作为提交依据。

## 8. 前端接入

建立 `answerApi`、`useAnswerRun`、`useAnswerEventStream`。使用 EventSource（GET）或 fetch streaming parser（需 POST/header 时）逐事件更新规范化 store；按 event id 去重和续传。页面卸载关闭连接，重连使用指数退避；completed 后以 GET 快照校正。

禁止继续把完整 SSE 文本 fetch 完成后再解析。UI 展示 retrieving/generating/finalizing、取消、重试和 partial 状态，但不暴露内部实现教程文字。

## 9. 测试和指标

- 首 token 在完整回答完成前到达；
- delta 顺序、重复 event id、断线续传和 completed 快照一致；
- 慢客户端不会阻塞 LLM/其他连接；
- 两实例下任意实例可订阅同一 run；Redis 重启后最终答案不丢；
- 取消可终止外部 HTTP 并阻止迟到结果提交；
- 并发 Conversation 消息无 seq 冲突；
- 非成员不能订阅猜测出的 run id。

指标：time-to-first-token、总耗时、各阶段耗时、active run/SSE、断线/恢复、buffer overflow、cancel latency、LLM error/token usage、Redis stream lag/trim。

质量与成本门禁使用固定 fixture：确定性 schema/citation 校验为主，模型 Judge 仅作辅助信号。每次 prompt/model/pipeline 变更比较完成率、citation、输入/输出 token、首 token、总延迟和单位成功回答成本，并保存阶段 trace 便于回归。

## 10. 灰度和验收

通过 `answer.run.v2`、`answer.sse.v2` 按 Workspace 灰度；旧 chat API 适配到新用例但只有一个回答写入者。完成标志：真正增量响应、run/revision 可审计、断线可恢复、单/多实例行为有测试，`ChatService` 不再负责流协议和运行状态。

回滚时停止新 run，进行中 run 允许完成；读取继续显示新 revision。Redis bridge 可独立关闭并降级单实例/快照，不回滚数据表。
