# Conversation、AnswerRun、SSE 与上下文恢复

> 本文是独立 A 档横切功能，默认讲第 14 节的 4 到 5 分钟回答；Turn/Run 分层、持久回放与实时订阅、Owner Lease 三组重点可各自继续展开三分钟。浏览器 Store、请求门禁和渲染背压另见[前端工作台一体化手册](39-前端工作台-流式状态与恢复一体化面试手册.md)。

> 定位：这篇回答“用户点一次发送，为什么不能只插两条 Message，再开一个 SSE”。它是 QA、Note、Wiki 和 Research 共用的会话可靠性底座，也是 Java 并发、网络和数据库面试的高密度功能点。

## 1. 一句话定位

我把用户发送消息建模为幂等 Turn，把一次模型执行建模为 AnswerRun，把流式输出建模为可持久化 AnswerEvent。MySQL 保存语义真源，Redis 只负责低延迟实时桥，SSE 断线后通过 `Last-Event-ID` 从持久事件续播，因此浏览器连接、生成线程和会话历史彼此解耦。

## 2. 为什么 Message 表不够

一次发送至少有四种时间尺度：

1. 用户语义动作：提交一个 Turn，可能因双击或网络重试重复到达。
2. 准备阶段：冻结历史、检索范围、Memory 和策略，期间也可能失败。
3. 执行阶段：模型持续输出 Token，可能取消、超时或切换 Provider。
4. 展示阶段：浏览器 SSE 可能断线、刷新或建立多个连接。

若只有 Message：无法区分“用户重复提交”和“模型重试”，无法证明旧答案基于哪版历史，也无法在断线后确定已经收到哪些片段。

## 3. 核心对象

```text
Conversation
  -> ConversationMessage(query / assistant placeholder)
  -> TurnSubmission(clientRequestId, preparation state)
       -> RunInputSnapshot(historyHead, messages, summary, memory, policy)
       -> AnswerRun(status, strategy, model, output budget)
            -> MessageRevision
            -> AnswerEvent(seq, type, payload)
            -> EvidenceManifest
  -> ConversationEventMux
```

| 对象 | 负责什么 | 唯一性或版本边界 |
| --- | --- | --- |
| `TurnSubmission` | 用户的一次语义提交 | `(actor, conversation, clientRequestId)` 唯一 |
| `ConversationMessage` | 用户可见消息槽位 | 内容可由 Revision 演进，不承载执行租约 |
| `RunInputSnapshot` | 本次运行实际看到的输入 | 一个 AnswerRun 对应一份不可变快照 |
| `AnswerRun` | 一次不可变的语义回答运行 | `assistantRequestId` 唯一，冻结输入、策略和预算并具有独立终态 |
| `AnswerExecutionAttempt` | 同一 AnswerRun 的基础设施接管或恢复代际 | `[目标设计]` 按 Run 内 Attempt No 唯一，记录 Owner、Lease、Provider Receipt 和本代新增消费 |
| `MessageRevision` | 流式聚合后的版本 | 按 Run 和 Revision No 追踪 |
| `AnswerEvent` | 可续播的细粒度事件 | `(answerRunId, seq)` 唯一且单调 |
| `EvidenceManifest` | 本次回答的证据 | 固定当时 Snapshot 和 Rank |

## 4. 幂等 Turn Submission

前端为每次点击生成稳定 `clientRequestId`。服务端先写 Turn Ledger，再准备 Query Message、Assistant Placeholder、Input Snapshot 和 AnswerRun。相同 Actor、Conversation、Client Request 再次提交时，返回已有业务结果，而不是再创建一组消息。

这比 Controller 上简单查重更强，因为 Ledger 记录准备状态、尝试次数、错误码和恢复租约。若进程在“已插入用户消息、尚未创建 Run”时退出，恢复扫描器可领取未完成提交并继续准备。

推荐回答：

> HTTP 幂等不是“重复请求返回 200”这么简单，而是重复请求必须指向同一语义 Turn，并且半完成状态可恢复。唯一约束负责并发仲裁，Ledger 负责解释和恢复。

## 5. History Head 与不可变输入快照

### 5.1 为什么要冻结 History Head

用户可能在生成期间继续发消息、删除旧消息或晋升新的 Segment Summary。若模型执行时每一步都读取“当前会话”，同一个 Run 的上下文会漂移。准备阶段记录 History Head，并将参与本次运行的消息、摘要、Memory、检索策略和版本编译成 RunInputSnapshot。

### 5.2 快照不是复制全部原文

快照可以保存规范化 JSON、消息引用、摘要哈希和策略版本。大对象仍由 Source Snapshot 或对象存储持有。关键是运行能证明“当时看到的逻辑输入”，不是无界复制所有二进制资料。

### 5.3 Replay 与重新生成的区别

Replay 使用旧 Input Snapshot 和旧 Evidence 查看或复现当时行为；Regenerate 可以基于新模型或新策略创建新 Run。两者必须分开，否则历史答案会因索引更新而不可解释。

## 6. AnswerRun 状态与结果未知

`[当前实现]` AnswerRun 不是简化的 PENDING/RUNNING 两态，而是按准备、检索、生成和提交拆开：

```text
PREPARING -> RETRIEVING -> GENERATING -> FINALIZING -> COMPLETED
                     |             |            |
                     +-------------+------------+-> FAILED / CANCELLED
```

旧入口还可能短暂使用 `CREATED -> RETRIEVING`，但不会把它包装成新的统一状态。拆分阶段是为了区分输入尚未冻结、检索正在执行、模型正在生成和最终内容正在原子提交。真正重要的是条件迁移：

- 只有持有当前 `stream_owner` 且 Lease 有效的执行者能从 GENERATING 推进到 FINALIZING。
- COMPLETED、FAILED、CANCELLED 是终态，晚到 Token 或旧 Owner 不能改回运行态。
- COMPLETED 的 Run 重复 Completion 返回既有结果，不重复生成 Message Revision。
- Provider 超时只说明调用方未知，不等于 Provider 一定没有生成结果。
- FINALIZING 把“生成完成”和“Message Revision、Run 终态、事件都已提交”分开，关闭部分写入窗口。

“结果未知”场景不能盲目重试写入。系统用 Run 身份、幂等请求、事件序号和 Revision 唯一约束让重复副作用收敛。

## 7. 一个事实事件，多种订阅投影

### 7.1 Domain Event

每次状态变化只由拥有该不变量的聚合追加一次权威事件。Answer Event 属于单个 Run，常见类型包括开始、增量内容、证据、完成、失败和取消，`seq` 在 Run 内单调递增；Task 可以拥有接纳和取消请求事件，但由 AnswerRun、Research 或 Artifact 完成驱动的 Task 终态只能引用原 `source_event_id`，不能再创造一份语义独立的完成事实。会话聚合也只做投影。

### 7.2 Conversation Stream Projection

Conversation 视图不仅有回答内容，还会出现消息创建、状态更新、Research 进度和其他运行变化。`ConversationEventMux` 将领域事件投影为会话级条目，每条保存 `source_event_id`、Conversation Cursor 和受控 View Payload。`source_event_id` 唯一约束保证同一领域事件重复投影时收敛，Conversation Cursor 只表示订阅位置，不成为第二套业务顺序。

### 7.3 为什么保留两种读取形态

单 Run 流适合精确恢复和调试，会话流适合工作台聚合。两种读取形态不等于两份事实真源：Run Event 决定业务状态，Conversation Entry 是可重建投影。投影丢失可以从 Domain Event 重建，投影内容不能反向完成 Run。

## 8. SSE 断线恢复

### 正常路径

1. 浏览器提交 `Last-Event-ID` 订阅 Run 或 Conversation SSE。
2. 服务端先建立实时通知订阅，并记录数据库投影高水位。
3. 回放 Cursor 之后到该高水位的持久事件。
4. 消费订阅建立期间缓存的新通知，并按 Event ID/Sequence 去重。
5. 新事件先形成业务记录，再由 Outbox/Projector 生成 Stream Entry 和实时通知。
6. 客户端按事件 ID 去重，并用服务端 Snapshot 校正 UI。

### 断线窗口

最危险的窗口是数据库历史追平完成但实时订阅尚未建立。推荐协议固定为“先订阅并记录高水位，再回放到高水位，最后消费期间缓存”，不再把“再次检查”与“先订阅”并列为两个未裁决方案。若实时层不支持可靠期间缓存，则使用数据库 Tail Poll 补到最新高水位后再切实时，宁可短暂增加查询，也不能留下事件缺口。

### Redis 故障

Redis 不是事件真源。实时桥不可用时可降级为数据库短轮询或重新连接，最终仍能从 AnswerEvent 恢复。代价是延迟上升，不能继续承诺正常实时性。

### 多连接

同一用户可打开多个标签页。SSE 是读投影，不应因为多个订阅者导致多次模型生成。生成 Owner 与订阅连接分离，连接关闭也不自动取消 Run，除非用户显式发出取消命令。

## 9. 流所有权与 Lease

生成线程可能因实例重启而中断。运行协调使用 Owner、Lease 或条件状态更新限制只有当前持有者可追加事件和提交终态。Lease 过期后新实例可以接管可恢复阶段；旧实例晚到时必须被 Token 或状态条件拒绝。

它与 SSE 连接无关：

- SSE Subscriber 是消费者，可以有多个。
- Generation Owner 是生产者，同一 Run 同一时刻只能有一个有效提交者。
- Redis Channel 是实时传输，不拥有业务状态。

## 10. Segment Summary 与长会话

### 10.1 为什么不能每轮全带历史

上下文成本线性增长，并最终超过模型窗口。简单滑窗又会丢掉早期约束，所以系统把稳定历史切成 Segment，并为 Segment 生成 Summary Revision。

### 10.2 Promotion

构建出的 Summary 先处于候选 Revision，只有通过完整性检查和晋升动作后才进入后续 Context Projection。这样旧 Ready Revision 在新摘要失败时仍可用，不会因为一次摘要错误破坏全部会话。

### 10.3 删除与 Replay Redaction

用户删除消息后，未来 Run 不应继续编译该内容；历史 Run 的不可变 Snapshot 又不能被直接篡改。解决思路是保留审计事实，同时在读取和 Replay 层应用 Redaction，相关 Segment Summary 标记 Stale 或 Deleted，并触发重建。

这体现“事实不可变”和“用户不可见”是两个不同维度。物理删除、审计保留和派生失效需要按隐私政策继续细化。

## 11. 前端如何避免把流状态当真源

前端 Store 可以乐观追加 Token，但刷新、重连或乱序事件后，应请求 AnswerRun/Conversation Snapshot 校正。核心规则：

1. Event 只做增量更新，Snapshot 决定完整状态。
2. Event ID 去重，旧 Revision 不覆盖新 Revision。
3. 终态优先级高于晚到增量事件。
4. 当前选中 Run 与历史 Run 分开，重新生成不会覆盖旧证据。
5. 页面卸载只释放订阅，不隐式取消服务端任务。

## 12. 关键指标

| 指标 | 公式或采集点 | 意义 |
| --- | --- | --- |
| Turn Duplicate Rate | `幂等重放提交 / Turn 提交` | 客户端重试与网络抖动程度 |
| Preparation Recovery Rate | `恢复成功的半完成 Turn / 被领取半完成 Turn` | Ledger 是否真正可恢复 |
| TTFT | 首个内容事件时间减提交时间 | 用户感知启动速度 |
| Stream Gap Rate | 出现非连续 Seq 的 Run / Run 总数 | 事件持久化或消费是否丢序 |
| Reconnect Recovery Latency | 重连到追平最新事件的时间 | SSE 恢复体验 |
| Duplicate Event Convergence | 重放后 UI/业务最终状态是否一致 | 去重正确性 |
| Cancel Effectiveness | 取消后仍产生的计费 Token / 取消请求 | 取消传播速度和成本泄漏 |
| Snapshot Compile Latency | RunInputSnapshot 编译耗时 | 长会话准备成本 |
| Summary Compression Ratio | Summary Token / 原 Segment Token | 只表示压缩程度，不表示信息质量 |
| Summary Retention Score | Gold 约束在摘要中保留比例 | 长上下文语义损失 |

## 13. 面试官连续追问

### Q1：为什么用 SSE，不用 WebSocket

当前主要是服务端单向流，SSE 基于 HTTP，浏览器自动重连和网关兼容更简单。用户取消、反馈等低频命令继续走 HTTP。若未来需要高频双向协作或语音，再考虑 WebSocket。SSE 的代价是连接数、代理缓冲和浏览器每域连接限制需要治理。

### Q2：SSE 能保证不丢消息吗

协议本身不能。可恢复性来自持久 AnswerEvent、单调 ID、`Last-Event-ID` 和去重。即使存在重复发送，客户端最终也能收敛到同一 Snapshot。

### Q3：数据库每个 Token 插一行会不会撑不住

按字符块或 30 到 100 毫秒时间窗聚合持久 Delta，不逐 Token 落库；终态、错误和 Citation 立即持久化。实时桥可以推送更细片段，但 Snapshot 与持久块决定恢复结果。Batch 越大，写放大越低，断线重放粒度越粗，需要用 Event Write QPS、恢复 RPO 和可见字符延迟共同校准。

### Q4：事务提交后，Redis 发布前宕机怎么办

事件已经在 MySQL，订阅者短暂收不到实时通知，但重连或轮询可补齐。若要求更低延迟，可通过 Outbox 或定时扫描补发。不能让 Redis 发布成功成为业务提交条件。

### Q5：如何处理乱序

服务端用 Run 内 Seq 和唯一约束，客户端只接受大于当前水位的事件；缺口触发回放或 Snapshot 校正。跨 Run 不要求全局排序，会话 Mux 只提供展示顺序和稳定游标。

### Q6：取消请求与完成同时发生怎么办

由数据库条件更新仲裁终态。谁先把 RUNNING 更新成合法终态谁获胜，后到操作读取既有终态并返回幂等结果。Provider 的实际计算可能仍在继续，所以还要向下游传播取消并观测取消后的 Token 泄漏。

### Q7：消息删除后，旧快照还在是否违反隐私

产品语义需要分层：用户立即不可见、派生摘要和索引失效、审计数据按保留政策处理、到期后物理清理。当前 Replay Redaction 解决读取面，不等于完成全部法规删除流程。

## 14. 简历与口述版本

### 简历表达

> 设计可恢复的流式会话链路，将 Turn、AnswerRun、Input Snapshot 与持久 AnswerEvent 解耦；利用客户端幂等键、唯一约束、运行 Owner 和 `Last-Event-ID` 实现重复提交收敛、SSE 断线续播与长会话摘要版本治理。

### 60 秒回答

> 用户点发送后，我不会直接创建一个异步线程。先用 clientRequestId 建立 Turn Ledger，事务内准备消息和运行身份，再冻结当前 History Head、资料证据和策略为 Input Snapshot。生成过程属于 AnswerRun，输出按单调 Seq 写入 AnswerEvent，同时经 Redis 推给 SSE。Redis 只是加速，断线后浏览器带 Last-Event-ID 从 MySQL 事件补齐。长会话则把旧消息切成 Segment，摘要以 Revision 构建并晋升，失败不会覆盖上一版。这样重复请求、实例重启和浏览器刷新都不会改变同一次语义提交。

### 4 到 5 分钟标准回答

用户点击一次发送，看起来只是插入一条问题和一条回答，实际上同时存在五个独立问题：双击和网络重试会重复提交；准备上下文时进程可能退出；检索和模型生成需要持续执行；浏览器 SSE 会断线或刷新；生成期间会话、资料和 Memory 还可能变化。若只用 Message 表和一个异步线程，系统无法判断重复请求是不是同一次语义动作，也无法解释回答基于哪版输入，更不能在断线后知道客户端漏了哪些内容。

入口先创建 `TurnSubmission`。客户端给一次点击生成稳定 `clientRequestId`，服务端用 Actor、Conversation 和 Client Request 唯一约束语义提交，同时保存 Request Hash。完全相同的请求返回已有 Submission，复用同一组 Query Message、Assistant Placeholder 和 Run；相同幂等键但 Payload 不同则拒绝冲突。Submission 在 PREPARING 时还保存冻结准备材料、尝试次数和 60 秒 Recovery Lease，进程在半完成阶段退出后可以由恢复入口继续，不需要再插一遍消息。

准备成功后产生不可变 `RunInputSnapshot`。它记录 History Head、消息引用、Segment Summary Revision、Memory Revision、Source/Evidence 范围、检索策略、版本和输入有效性 Epoch。普通新消息、偏好更新或摘要晋升不改变当前 Run；隐私删除、成员撤权或 Source 安全封禁会推进 Scope/Revocation Epoch。`[目标设计]` 高风险工具调用与 FINALIZING 提交前必须重新校验 Epoch，失效后进入 `INPUT_REVOKED` 或受控取消，不能用“Snapshot 已冻结”继续发布敏感结果。如果每一步都重新读取普通最新内容，同一个 Run 重试又会得到不同输入，因此内容身份保持冻结，提交资格单独校验。Replay 读取旧 Snapshot 的允许部分来解释历史，Regenerate 创建新 Run 使用新上下文，两者不能混成一个按钮。

AnswerRun 把一次执行拆成 PREPARING、RETRIEVING、GENERATING、FINALIZING 和 COMPLETED，失败与取消进入独立终态。检索完成时固化 Evidence Manifest 和 Retrieval Trace；生成阶段创建 STREAMING Message Revision。执行者通过 `stream_owner` 和当前 150 秒 Lease 获得生成权，续租只能由同一 Owner 完成。结束时先用条件更新从 GENERATING 进入 FINALIZING，再原子固定 Message Revision、Run 内容和 COMPLETED 终态。取消、失败和完成竞争由数据库前置状态与 Owner 条件仲裁，旧生成者晚到不能覆盖终态。

流式内容不是只写内存。`AnswerEvent` 按 Run 内 `seq` 单调持久化，Redis Realtime Bridge 和 JVM Channel 只负责低延迟通知。浏览器带 `Last-Event-ID` 或 `after` 重连，服务端先读取游标之后的 MySQL 事件，再读取 AnswerRun Snapshot；如果 Run 已经完成但客户端漏了终态，接口补发 `answer.snapshot` 和终态事件。若 Run 仍在执行，Subscriber 再跟随实时流。Redis 在数据库提交后、发布前宕机只会让实时通知变慢，持久事件仍可恢复。

这里刻意区分 Generation Owner 和 SSE Subscriber。一个 Run 同时只能有一个有效生成者，但可以有多个标签页订阅；关闭浏览器只释放 Subscriber，不等于取消模型任务。若把连接生命周期当任务生命周期，手机切网或页面刷新就会浪费已经生成的内容，并可能造成重复计费。显式取消通过 HTTP 进入 Run 状态机，再向下游传播中止信号，同时统计取消后仍发生的 Token 消费。

会话级别还有 `ConversationEventMux`。单 Run Event 用于精确恢复，Conversation Event 把多个 AnswerRun、消息和长任务变化聚合到工作台。两层都带稳定顺序，但不承诺不同 Run 之间存在业务全序。前端先用 Event 做增量，最后用 Snapshot 校正；终态优先于晚到 Delta，历史 Run 和当前 Run 分开保存。

长会话使用 Segment Summary Revision。新摘要先构建和校验，再 Promotion；失败时继续使用上一版 READY，不覆盖历史。消息删除后，新 Run 不再编译该内容，旧 Snapshot 通过 Replay Redaction 控制读取，派生 Summary、索引和 Memory 继续失效；仍在执行的 Run 还要在外部工具调用与最终提交前检查 Revocation Epoch。不可变审计与用户可见性是两条语义，完整物理删除仍由隐私保留策略决定。

当前源码能证明 Turn 幂等、准备恢复、输入快照、真实 AnswerRun 状态、Owner Lease、持久 Event、Last-Event-ID、终态 Snapshot 补发和 Summary/Redaction 入口。跨刷新 Client Request 稳定复用仍取决于前端持久化，数据库回放与 Redis Live 的严格无缝切换还要持续用 Gap 指标和故障注入验证；生产连接规模、代理缓冲、跨实例 Trace 和真实 TTFT 仍属于生产待验证。浏览器端的多 Run Store、过期请求和恢复细节见[前端工作台、流式状态与恢复](39-前端工作台-流式状态与恢复一体化面试手册.md)。

## 15. 源码与迁移导航

| 主题 | 入口 |
| --- | --- |
| 提交与生成 | `ChatController`、`AnswerSubmissionService`、`AnswerGenerationOrchestrator` |
| Run 与事件 | `AnswerRunService`、`AnswerEventResponse` |
| 会话事件聚合 | `ConversationEventMux`、`ConversationController` |
| 实时桥 | `RedisAnswerRealtimeBridge` |
| 输入快照 | `RunInputSnapshotService`、`ConversationContextProjectionService` |
| 删除与重放 | `ConversationMessageDeletionService`、`RunReplayRedactionService` |
| Segment Summary | `ConversationSegmentBuildService`、`SegmentSummaryPromotionService` |
| 关键 Schema | `V030`、`V060`、`V072` 到 `V078` |

## 16. 面试前自检

1. Turn、Message、AnswerRun 为什么不是一个对象。
2. 唯一约束和幂等查询如何处理两个并发重复请求。
3. 数据库追平与 Redis 订阅之间的事件缺口怎样关闭。
4. SSE 连接断开为什么不取消生成。
5. 取消和完成竞争时由谁仲裁。
6. History Head 与 Input Snapshot 如何保证可复现。
7. Summary Revision 为什么需要 Promotion。
8. 删除、历史审计和 Replay Redaction 的边界是什么。

## 17. 重点知识点：Turn、Message 和 AnswerRun 为什么必须分开

### 3 分钟回答

Turn 表示用户的语义动作，Message 表示用户可见内容，AnswerRun 表示一次输入、策略和预算冻结的语义回答运行。三者生命周期不同。用户双击发送产生的是同一个 Turn，不应重复创建 Message；同一个问题重新生成、切换模型或改变要求可以复用 Query Message，但必须产生新的 AnswerRun 和 Assistant Revision；同一语义输入上的瞬时故障恢复只创建新的 `AnswerExecutionAttempt`，不能伪装成用户重新生成。一次 Run 失败也不应该删除原始问题。把这些对象塞进 Message 表，会让幂等、恢复、历史展示和执行状态互相覆盖。

NoteWeave 用 `TurnSubmission` 保存 Client Request、Request Hash、准备状态、冻结材料和恢复租约。唯一约束负责并发仲裁，相同请求返回原 Receipt，Payload 不同则拒绝幂等键复用。Message 只承担 Conversation 中的稳定槽位和顺序；Assistant Placeholder 可以先出现，再由对应 Run 的 Message Revision 固定内容。AnswerRun 保存检索计划、Evidence Bundle、模型、总 Token Budget、状态和最终结果；`[目标设计]` Owner、Lease、Provider Receipt 与本代新增消费下沉到 AnswerExecutionAttempt，避免恢复一次就重置预算或混淆用户再生成次数。

这种拆分还解决结果未知。客户端超时后可以按 Client Request 查询 Submission，确定请求是否已经创建 Run，而不是重新发一条消息；Run Completion 响应丢失时读取 Run 终态和 Revision，而不是再次生成。代价是对象和状态更多，Service 必须保证准备阶段的多表写入与恢复语义。只有简单同步 CRUD、没有重试和重新生成时，单 Message 模型才足够。

### 二阶追问：为什么不只给 AnswerRun 加幂等键

Run 创建前还有消息、上下文快照和准备步骤，进程可能在中间退出。只约束 Run 无法解释半完成 Turn，也无法阻止重复 Query Message。Submission 把幂等边界放在用户语义动作最外层，Run 再负责执行身份。

### 二阶追问：重新生成算新 Turn 还是新 Run

如果用户问题和输入范围不变，只是换模型或策略重新回答，通常是同一会话语义下的新 Run，并保留旧 Revision 供比较；如果用户修改问题、资料范围或关键约束，应创建新 Turn。判断标准是用户意图和冻结输入是否发生语义变化。

## 18. 重点知识点：持久回放与实时订阅怎样关闭事件缺口

### 3 分钟回答

SSE 自动重连只负责重新建立 HTTP 连接，不保证消息不丢。可靠性来自 MySQL `AnswerEvent`、单调 Sequence、`Last-Event-ID`、客户端去重和权威 Snapshot。实时层即使全部丢失，浏览器仍能按游标读取历史并恢复终态。

难点在 Replay 和 Live 的交界。如果先查数据库到水位 100，再建立 Redis 订阅，事件 101 恰好在中间发布，就可能只存在于已错过的实时通知。通用做法有两类：先订阅再回放，缓存订阅期间的事件后按 Sequence 去重；或先回放、建立订阅，再读取一次数据库高水位补缝。无论哪种，必须以持久 Sequence 为准，不能用本地到达时间排序。

当前 AnswerRun SSE 会先读取持久事件和 Snapshot，再跟随 Authorized Run Live；终态 Run 会直接补 Snapshot 与 Completed/Failed/Cancelled。`SessionEventMux` 和 `ConversationEventMux` 还能从数据库与 Redis 追平并按 Sequence 去重，但生产结论仍要用 Stream Gap Rate、Reconnect Recovery Latency 和故障注入验证。若检测到 Gap，客户端停止盲目拼接 Delta，重新请求事件或完整 Snapshot。

事件不必逐 Token 持久化。可以按字符、时间窗或语义块聚合，降低 MySQL 写放大；实时层仍可更细。Batch 越大，恢复时最多丢失的未提交显示片段越大，必须在写吞吐、TTFT 和 Replay 粒度之间选择。业务终态、Citation 和状态事件不与普通 Delta 任意合并。

### 二阶追问：客户端收到重复 Delta 会不会重复显示

Event ID 和 Run Sequence 去重，Store 只应用高于当前水位或明确可幂等 Upsert 的事件。发现同 Sequence 内容不一致时不能随便覆盖，应记录协议错误并用 Snapshot 校正。

### 二阶追问：为什么不直接从 Kafka 推到浏览器

Kafka 是内部持久命令和事件传输，不负责 Workspace 鉴权、浏览器连接、游标裁剪和 UI Snapshot。后端先按用户权限读取业务事件，再通过 SSE 交付，避免把 Broker 协议和内部 Topic 暴露到用户面。

## 19. 后端流式链路的二阶追问

### Stream Lease 150 秒是怎么定的

这是当前代码常量，只能解释为允许生成者在一定窗口内续租和被判定失联，不能当生产最优值。正式推导需要 Heartbeat 间隔、允许漏报次数、GC/调度暂停、Provider 最长静默时间和误接管率。Lease 太短会双 Owner，太长会拖慢恢复；即使 Lease 合理，终态写入仍要 Owner 条件防旧执行者晚到。

### FINALIZING 为什么不能省

模型停止输出不等于回答已完成。还要固定 Message Revision、写最终内容和 Token、更新 Run 终态并追加事件。若直接 GENERATING 到 COMPLETED 且多表分开写，可能出现 Run 完成但 Revision 仍是 STREAMING。FINALIZING 是内部提交阶段，允许条件更新与事务把这些变化收口，也便于恢复器识别卡在提交而不是仍在调用模型。

### SSE 连接达到上限怎么办

先把生成与 Subscriber 解耦，断开慢客户端不会取消 Run。连接执行器必须有界，代理关闭缓冲并设置心跳、空闲超时与每用户连接上限；客户端退避重连。容量看并发连接、每连接事件速率、写缓冲、文件描述符和网关限制，不从线程池默认值直接推导。

### 取消后 Provider 仍在计费怎么办

数据库终态先阻止后续内容提交，同时尽力调用 Provider Cancel 或中断本地执行。不能保证外部计算立刻停止，所以记录 Cancel Request 到最后 Token/调用结束的延迟和取消后成本。对结果未知的 Provider 调用使用 Operation ID/Receipt 查询，不因为取消与超时同时发生就盲目重试。

### Conversation Sequence 和 Run Sequence 为什么不能共用

Run Sequence 表示一个生成尝试内的严格增量顺序，Conversation Sequence 表示多个 Run 和消息在工作台的投影顺序。强行共用会让单 Run 回放依赖其他任务，并在并发 Run 时产生全局热点。Conversation Event 可以携带 Run ID 与 Run Sequence，用两级游标关联。

## 20. SSE 与恢复数字怎样形成

流式链路要把开始速度、持续稳定、恢复和终态正确性分开。TTFT 很快但频繁断流，或者重连很快却拼出重复文本，都不能算体验可靠。

| 指标 | 计算 | 测量边界 |
| --- | --- | --- |
| TTFT | `首个可见内容事件时间 - Turn 接收时间` | 包含准备、检索、排队和模型首 Token |
| Stream Completion Rate | `客户端最终拿到权威终态的 Run / 已开始 Run` | 失败与取消不能从分母删除 |
| Gap Rate | `观察到 Sequence 缺口的 Run / 有流事件的 Run` | Gap 可恢复也要计数，表示实时链路异常 |
| Replay Recovery Rate | `重连后与权威 Snapshot 一致的 Run / 发起重连的 Run` | 一致包括内容、终态和最后 Sequence |
| Reconnect Recovery P95 | `追平权威水位时间 - 重连建立时间` 的 P95 | 必须注明积压事件量和连接环境 |
| Duplicate Convergence | `重复或乱序注入后最终内容与 Snapshot 一致的 Case / 注入 Case` | 不用“重复事件数为 0”作为目标 |
| Cancel Leakage | `取消确认后仍计费的 Token 或调用成本 / 取消 Run` | 衡量取消向 Provider 传播的损失 |
| Event Write Amplification | `持久 Event 行数 / 输出字符或 Token` | 用于选择字符块与时间窗，不能只追求最低写入 |

故障演练至少覆盖六个窗口：Turn 创建一半退出、数据库事件提交后 Redis 发布前退出、Replay 与 Live 切换时发布新事件、同一 Delta 重复到达、旧请求晚到、取消和完成竞争。每个 Case 保存 Run、Sequence、Last-Event-ID、最终 Snapshot、客户端收敛结果和恢复耗时。只有终态和内容都一致，才算恢复成功。

`[演练假设]` 可以准备 `N=200` 个固定 Run，其中 `60` 个在不同 Sequence 注入断线，Replay Recovery 为 `60/60`，Reconnect Recovery P95 为 `420 ms`，重复与乱序注入 `80` 次后最终 Snapshot 一致为 `80/80`，TTFT P95 为 `1.8 s`。这些数字只用于解释数据卡；`60/60` 不能写成恢复可靠性 100%，真实代理缓冲、跨地域网络和长输出仍未覆盖。

### 二阶追问：TTFT 降了，为什么总完成时间反而上升

Provider 可以更早返回首个 Token，却以更低速率持续生成；也可能为了抢 TTFT 提前开始生成，后续工具或证据不足导致 Repair。应同时看 TTFT、Token Inter-arrival、Completion P95、失败率和最终 Citation。若 TTFT 优化来自跳过检索或缩短证据，质量护栏下降就不能宣传为性能提升。

### 二阶追问：为什么 Gap Rate 不要求绝对为 0

实时通知层允许暂时丢失，持久事件和 Snapshot 负责恢复。Gap 本身应被观测，因为它会增加重连延迟和用户抖动，但系统正确性目标是检测 Gap 后停止盲目拼接并最终收敛。若要求通知层永不丢，会把 Redis 或单条 SSE 连接错误地提升成业务真源。
