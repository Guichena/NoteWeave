# 06 WebSocket Runtime 与 Memory 链路

## 0. 本篇定位

这条链路回答：NoteWeave 如何支持流式回答、stop/resume、DRAFT/FORMAL，以及长期记忆的分层写回和加载。

核心链路：

```text
ws-ticket
-> WebSocket /ws/chat/{ticket}
-> chat.started / chat.delta / chat.completed
-> Redis runtime state
-> stop / resume / partialContent
-> FORMAL writeback
-> SessionSummary / SpaceMemory / UserMemory
```

## 面试先说版

这条链路我会从“AI 工作台的运行态和长期记忆怎么分开”讲。HTTP 一问一答只能解决同步请求，但真实 AI 产品需要流式输出、中途停止、刷新恢复、草稿探索和正式知识沉淀。NoteWeave 用 WebSocket 承接流式体验，用 Redis 保存短期 runtime state，用 MySQL 保存正式消息、Citation、Artifact 和 Memory。

另一个关键点是 DRAFT 和 FORMAL。DRAFT 是临时探索，不写长期 Memory；FORMAL 才可能根据策略写会话摘要、空间记忆或用户偏好。这样既支持顺滑交互，也避免把用户随手试的问题污染长期记忆。

## Q1：为什么要做 WebSocket Runtime，不只用 HTTP 问答？

**答：**

HTTP 问答适合非流式、请求响应式的场景，但真实 AI 工作台需要流式输出、停止生成、刷新恢复、DRAFT 临时探索等能力。

WebSocket Runtime 解决的是会话执行过程中的运行态问题。用户发出消息后，服务端会推送 `chat.started`、多个 `chat.delta`、最后 `chat.completed`。如果用户中途 stop，就发 `chat.stopped` 并停止后续 delta。如果浏览器刷新，可以通过 ack/resume 恢复事件和 partialContent。

## Q2：Redis 在 Runtime 里承担什么职责？

**答：**

Redis 在这里主要承载短期运行态，不是主业务事实源，也不是后台任务队列。

具体包括一次性 WebSocket ticket、当前 session runtime status、partialContent、事件 buffer、seq/ack/resume 状态、stop 标记等。这些状态有明显临时性，适合 TTL 过期，不适合全部落 MySQL。

正式 ChatMessage、Citation、Task、Artifact、Memory 这些长期事实仍然在 MySQL。

## Q3：为什么要区分 DRAFT 和 FORMAL？

**答：**

因为用户在 AI 工作台里有两类行为：一类是正式知识生产，一类是临时探索。

FORMAL 会话适合长期保留，可以写 ChatMessage、Citation、Memory，总结后影响后续上下文。DRAFT 会话更像临时草稿，用户可能随便试问题、改方向、探索不成熟想法。如果 DRAFT 也写长期 Memory，很容易污染用户画像和空间记忆。

所以 DRAFT 默认不写长期 Memory，可以被转换为 FORMAL，也可以丢弃或过期。

## Q4：长期记忆为什么要分层？

**答：**

因为不同记忆的作用域不同，不能把所有历史会话都塞进 Prompt。

SessionSummary 记录某次正式会话的摘要；SpaceMemory 记录当前用户在某个空间里的工作上下文；UserMemory 记录跨空间的稳定偏好；MemoryItem 承载具体偏好或空间上下文条目。

这样既能减少 token 和噪声，也能按作用域、置信度、过期时间、pin、禁用开关控制记忆写入和加载。

## 常见追问

**追问：Redis 丢了会怎样？**

短期体验会受影响，比如不能恢复最近 delta 或 partialContent，但正式落库的历史消息、Artifact、Citation、Task 不应该丢。

**追问：Memory 写入怎么避免污染？**

通过写入策略控制。DRAFT 不写，短问候不写，包含 password、token、secret、api-key 等敏感信号的不写，低置信度偏好不覆盖高置信度偏好。

## 实现兜底锚点

- `WebSocketTicketService`
- `ChatWebSocketHandler`
- `ChatRuntimeService`
- `ChatRuntimeStateStore`
- `ActiveExecutionRegistry`
- `MemoryWritebackStrategy`
- `MemoryWritebackService`
- `MemoryContextService`
- `ContextReadRouter`
- `Phase5WorkspaceChatRuntimeIntegrationTest`
- `Phase12LongTermMemoryIntegrationTest`

## 3 到 5 分钟深答模板

> HTTP 问答能解决“一问一答”，但 AI 工作台真正麻烦的是运行态：流式输出、中途 stop、刷新后 resume、DRAFT 探索态和长期记忆写回。NoteWeave 为此单独做了 WebSocket Runtime。客户端先拿一次性 ticket 建连，服务端按 `chat.started`、`chat.delta`、`chat.completed` 推送事件，Redis 保存短期 runtime state、事件缓冲和 partialContent；用户 stop 时会设置停止标记并保留 partialContent，刷新后再根据 ack / resume 重放最近事件。正式消息、Citation、Artifact 和长期 Memory 仍然落 MySQL。Memory 也不是无脑写回，而是分成 SessionSummary、SpaceMemory 和 UserMemory，并通过写入策略过滤 DRAFT、寒暄、敏感信息和低价值内容。这样流式体验和长期知识边界就被分开了。

## 边界和不能说满的地方

- 可以坚定讲：Redis 负责短期运行态，MySQL 负责正式事实，DRAFT 不写长期 Memory。
- 不要讲成：Redis 是主事实源；resume 能恢复所有历史状态；长期记忆会无差别保存全部会话内容。
