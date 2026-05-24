# 文件：06_WebSocketRuntime与长期记忆.md

## 1. 本主题面试官想考什么

这个主题考察实时交互、运行态管理、断线恢复、停止控制、会话生命周期和长期记忆污染控制。NoteWeave 的亮点在于把 WebSocket Runtime 和长期 Memory 明确分层：Redis 管临时运行态，MySQL 管长期记忆，DRAFT 不写长期记忆。

## 2. 高频问题清单

### 基础问题

- HTTP 问答能用，为什么还要 WebSocket？
- WebSocket ticket 是什么？
- `DRAFT` 和 `FORMAL` 会话有什么区别？
- Redis 在 Runtime 中存什么？

### 进阶问题

- stop/resume 怎么设计？
- 为什么运行态放 Redis，而不是全落 MySQL？
- 为什么 DRAFT 不写长期记忆？
- `ContextReadRouter` 为什么要分层读取上下文？
- 长期记忆分几层？

### 深挖追问

- 断线后怎么恢复 partial content？
- 如果前端 ack 丢失怎么办？
- Memory 如何避免写入敏感内容？
- Session summary、Space memory、User memory 分别解决什么？
- 过期但 pinned 的 memory 怎么处理？

### 压力追问

- Redis 丢失会造成什么影响？
- 多端同时发送消息怎么防并发执行？
- Memory 越写越多如何控制噪声？
- 如果用户要求删除记忆，如何保证后续 prompt 不再使用？

## 3. 问答与讲解

### Q1：HTTP 问答已经能用，为什么还要 WebSocket Runtime？

#### 面试官为什么问

面试官想看你是否理解 AI 流式交互不只是“把字一个个推给前端”，还涉及停止、恢复、运行态和会话生命周期。

#### 回答思路

说明 HTTP 适合稳定的一问一答；WebSocket 适合 streaming、stop、resume、partial content、runtime state、DRAFT exploration。

#### 结合我的项目怎么答

项目中 HTTP `TeamChatService` 只允许 FORMAL 团队会话；DRAFT 会话必须走 WebSocket runtime。`ChatRuntimeService` 支持 `chat.connected`、`chat.started`、`chat.delta`、`chat.completed`、`chat.stopped`、`chat.failed`、`chat.restored` 等事件，并用 `ActiveExecutionRegistry` 防止同一 session 并发执行。

#### 技术原理 / 链路设计讲解

WebSocket runtime 包含几个关键能力：

- ticket：一次性连接票据，避免直接暴露长期 token 到 WS path。
- event envelope：统一事件名、requestId、streamId、sessionId、messageId、seq、ack。
- active execution：防止同 session 多个生成并发。
- runtime state：记录 RUNNING/STOPPED/FAILED/IDLE。
- stream state：保存 partialContent，支持恢复。
- event buffer：按 ack 重放事件。

#### 技术栈特点与选型理由

Redis 适合短期运行态和事件缓存，因为它读写快、TTL 自然、适合断线恢复；MySQL 适合最终消息、会话、Citation 和长期记忆。两者分工不同。

#### 可直接复述的面试回答

HTTP 问答适合非流式、稳定的一问一答，但 AI 工作台需要更细的运行态控制，比如流式输出、用户中途 stop、页面刷新后 resume、DRAFT 临时探索，以及 partial content 恢复。所以我单独做了 WebSocket runtime。它用一次性 ticket 建连，服务端统一发 `chat.started`、`chat.delta`、`chat.completed`、`chat.stopped`、`chat.restored` 这些事件；Redis 保存短期 runtime state、stream state 和事件缓冲；MySQL 只保存正式消息、Citation 和长期状态。这样交互体验和持久化边界都更清楚。

#### 常见追问

- 为什么不用 SSE？
- ticket 过期怎么处理？
- 同一个 session 同时发两条消息怎么办？

#### 常见坑

不要把 WebSocket 说成“为了实时显示”。要讲 stop、resume、ack、partial content、DRAFT 生命周期。

---

### Q2：为什么区分 `DRAFT` 和 `FORMAL`？

#### 面试官为什么问

这是 AI 产品边界题。面试官想看你是否能防止探索性内容污染长期知识和记忆。

#### 回答思路

说明 DRAFT 是临时探索，不一定可信、不一定要保存、不应该写 memory；FORMAL 是正式会话，可以持久化消息、Citation、Memory。

#### 结合我的项目怎么答

`ChatRuntimeService` 中 FORMAL 会话完成后会保存 assistant message、Citation，并触发 `memoryWritebackService.writeAfterRound`；DRAFT 会话 streaming 完成后 payload 会标记 persisted=false，不保存正式 assistant message，也不写长期 memory。DRAFT 可以后续 convert-to-formal 或 discard。

#### 技术原理 / 链路设计讲解

AI 交互里用户会大量试探、改写、临时提问。如果所有内容都写入长期记忆，后续 prompt 会被噪声污染。DRAFT/FORMAL 分离是一种“写入闸门”：探索阶段只留运行态，确认进入正式阶段后才进入长期记录。

#### 技术栈特点与选型理由

Redis runtime 支持 DRAFT 的临时性；MySQL 支持 FORMAL 的持久性。用不同生命周期的存储表达不同可信度，避免一个存储承载所有语义。

#### 可直接复述的面试回答

我区分 DRAFT 和 FORMAL，核心是控制知识污染。用户在 AI 工作台里会有很多临时探索，比如试问、改写、发散思考，这些内容不一定代表稳定需求，也不应该进入长期记忆。DRAFT 会话主要存在 Redis runtime，支持流式、停止和恢复，但不写长期 memory；FORMAL 会话才会持久化 assistant message、Citation，并触发 memory writeback。这样既保留探索体验，又避免把临时内容污染后续上下文。

#### 常见追问

- DRAFT 能不能转 FORMAL？
- DRAFT 的 partial content 会不会丢？
- 如果用户想保留 DRAFT 怎么办？

#### 常见坑

不要说 DRAFT 只是“草稿样式”。它是运行态、持久化和 memory 写入边界。

---

### Q3：长期记忆怎么设计？如何避免越写越脏？

#### 面试官为什么问

长期记忆是简历高风险词。面试官会追问写入策略、读取策略、隐私、安全、噪声控制。

#### 回答思路

讲三层记忆：session summary、space memory、user memory。再讲 writeback strategy：DRAFT 排除、敏感信息跳过、短问候跳过、偏好识别、置信度和 TTL。

#### 结合我的项目怎么答

项目有 `session_summary`、`memory_item`、`space_memory`、`user_memory` 表。`MemoryWritebackStrategy` 会跳过 DRAFT、空轮次、敏感内容、短问候；对稳定偏好写 user memory，对有意义轮次写 session summary 和 space memory。`ContextReadRouter` 根据 DRAFT/FORMAL 和会话类型决定是否读取 recentHistory、sessionSummary、spaceMemory、userMemory、retrievalEvidence。

#### 技术原理 / 链路设计讲解

长期记忆不是把历史聊天全塞进 prompt，而是做分层摘要和选择性读取：

- SessionSummary：保留会话级上下文，避免长历史爆 token。
- SpaceMemory：当前用户在某个空间里的工作背景。
- UserMemory：跨空间稳定偏好，比如回答风格。

写入需要策略，读取也需要策略。否则会出现隐私泄露、prompt 污染、token 膨胀和幻觉强化。

#### 技术栈特点与选型理由

MySQL 存长期记忆，因为它需要可查询、可编辑、可删除、可审计；Redis 不适合长期记忆。Prompt 构造时只注入有效、未过期、符合读取计划的记忆。

#### 可直接复述的面试回答

我没有把长期记忆做成“把所有历史都塞进 prompt”。当前分成 session summary、space memory 和 user memory 三层。Session summary 解决单个会话的连续性，space memory 解决某个工作空间内的背景，user memory 只保存比较稳定的用户偏好。写入上有 `MemoryWritebackStrategy`：DRAFT 不写，空轮次不写，短问候不写，包含 password、token、api key 这类敏感信号也不写；偏好类内容才写 user memory，并带置信度、重要性和 TTL。读取上由 `ContextReadRouter` 控制，不同会话类型读不同层，避免把所有历史一股脑喂给模型。

#### 常见追问

- 用户关闭 memory 后怎么办？
- 敏感信息过滤是否足够？
- Memory 删除后旧 prompt 日志怎么办？

#### 常见坑

不要把长期记忆说成“越多越好”。面试官更认可你讲过滤、删除、TTL、置信度和读取边界。

---

## 4. 本主题总结

WebSocket 和 Memory 主题要讲清：Redis 是短期运行态，MySQL 是长期事实；DRAFT 不写长期记忆；stop/resume 是服务端 runtime 能力；长期记忆必须有写入策略和读取策略。

## 5. 面试前自查清单

- 我是否能讲出 WebSocket 事件流？
- 我是否能解释 DRAFT/FORMAL 的持久化差异？
- 我是否能说明 Redis runtime 丢失后的影响边界？
- 我是否能讲清长期记忆三层模型？
- 我是否能回答 memory 污染、隐私和删除问题？

## 6. 整条链路深讲：一次 WebSocket 流式问答如何执行

第一步是获取 ticket。客户端先通过 HTTP 申请 WebSocket ticket，服务端把一次性 ticket 写入 Redis，并设置短 TTL。这样 WebSocket path 不需要长期暴露认证 token。

第二步是建立连接。客户端连接 `/ws/chat/{ticket}`，服务端消费 ticket，确认用户身份后发送 `chat.connected`。ticket 一次性消费可以降低重放风险。

第三步是客户端发送 `chat.message`。事件 envelope 带 requestId、streamId、sessionId、ack 和 payload。服务端把处理放到 applicationTaskExecutor，避免阻塞 WebSocket IO 线程。

第四步是并发保护。`ActiveExecutionRegistry.registerIfAbsent` 确认同一个 session 没有正在执行的生成任务。如果已有 RUNNING execution，就拒绝新请求，避免同一个会话里两个流交错写状态。

第五步是写运行态。服务端保存 user message，更新 session.runtimeStatus 为 RUNNING，并把 RuntimeState、ShortTermContext、StreamState 写入 Redis。随后发送 `session.state.updated` 和 `chat.started`。

第六步是加载证据和上下文。Runtime 复用团队 RAG 的 HybridRetriever、EvidencePostProcessor、TeamRagPromptBuilder，也会通过 `ContextReadRouter` 决定读取 recent history、session summary、space memory、user memory 和 retrieval evidence 的组合。

第七步是流式输出。服务端把答案拆成 delta，逐步发送 `chat.delta`，每个事件写入 Redis event buffer，并更新 partialContent。客户端 ack 后，后续 resume 可以只重放 ack 之后的事件。

第八步是 stop。用户发送 `chat.stop` 时，服务端找到 ActiveExecution，设置 stopRequested，更新 session 和 Redis 状态为 STOPPED，保存 partialContent，并发送 `chat.stopped`。这不是前端自己停止展示，而是服务端停止继续推 delta。

第九步是 completed 或 failed。FORMAL 会话完成后保存 assistant message、Citation，并触发 MemoryWriteback；DRAFT 会话返回 persisted=false，不写正式 assistant message 和长期 memory。失败时写 FAILED runtime state 和 `chat.failed`。

第十步是 resume。页面刷新后客户端发送 `chat.resume`，服务端从 Redis 读取 ack 后事件并重放，再返回 `chat.restored`，携带 runtimeStatus 和 partialContent。

## 7. 原理与设计原因速查

- 为什么不用纯 HTTP：HTTP 难以自然支持 stop、resume、partial content、event ack 和运行态恢复。
- 为什么 ticket 一次性消费：降低 WS URL 泄露和重放连接风险。
- 为什么 Redis 存 runtime：运行态短生命周期、高频读写、有 TTL，适合 Redis；正式消息和 Citation 才进 MySQL。
- 为什么 active execution 必要：防止同一 session 并发生成导致 delta 乱序、messageSeq 冲突和 memory 写入错乱。
- 为什么 DRAFT 不写 memory：探索性内容不稳定，写入长期记忆会污染后续 prompt。
- 为什么 ContextReadRouter 分层：不是所有会话都需要所有上下文，分层读取可以控制 token、隐私和噪声。

## 8. 3 到 5 分钟深答模板

WebSocket Runtime 我会按连接、运行态、恢复和持久化边界讲。客户端先通过 HTTP 拿一次性 ticket，服务端写 Redis 并设置 TTL，WebSocket 建连时消费 ticket 后发送 `chat.connected`。用户发送 `chat.message` 后，后端先用 ActiveExecutionRegistry 防止同一 session 并发执行，然后保存 user message，把 session 和 Redis runtime state 更新为 RUNNING，发送 `chat.started`。生成链路复用团队 RAG 的检索、证据后处理和 prompt 构造，只是输出时会把答案拆成 `chat.delta`，每个 delta 都写入 Redis 事件缓冲和 partialContent。用户 stop 时不是前端自己截断，而是服务端设置 stopRequested，更新 STOPPED 状态并保存 partialContent。刷新后客户端带 ack 调 `chat.resume`，服务端重放 ack 之后的事件，并返回 `chat.restored`。最后，FORMAL 会话才保存 assistant message、Citation 和长期 memory；DRAFT 只保留短期 runtime，不写长期 memory。这样流式体验、停止恢复和知识污染边界都比较清晰。
