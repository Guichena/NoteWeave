# 文件：06_WebSocket_Memory_会话恢复加深版.md

## 1. 这个主题要回答什么

这个主题覆盖：

- HTTP 能问答，为什么还要 WebSocket？
- WebSocket 怎么实现流式输出？
- stop/resume 怎么做？
- 短期记忆、长期记忆、summary 怎么设计？
- 长期记忆什么时候写入？
- 哪些内容不能写入？
- Redis 和 MySQL 在记忆系统里怎么分工？

## 2. 面向初学者：WebSocket 是什么

HTTP 是请求-响应模式：

```text
客户端发请求 -> 服务端返回响应
```

WebSocket 是长连接双向通信：

```text
客户端 <-> 服务端
```

服务端可以主动推消息给客户端。AI 流式输出很适合 WebSocket，因为模型生成过程是逐步产生内容的。

## 3. 为什么 NoteWeave 要 WebSocket

普通 HTTP 问答只能做到：

- 用户提问。
- 服务端处理完。
- 一次性返回完整答案。

但 AI 工作台需要：

- `chat.delta` 流式输出。
- 用户中途 stop。
- 页面刷新后 resume。
- 保存 partialContent。
- DRAFT 临时探索。
- 服务端知道 runtimeStatus。

所以 NoteWeave 做了 WebSocket Runtime。

## 4. WebSocket 完整链路

### 4.1 ticket 建连

```text
POST /api/v1/chat/ws-ticket
-> Redis 写 ticket
-> ticket TTL
-> WebSocket /ws/chat/{ticket}
-> 服务端消费 ticket
-> chat.connected
```

为什么用 ticket：

- 不把长期 JWT 放在 URL。
- ticket 一次性消费。
- TTL 自动过期。

### 4.2 发送消息

客户端发送：

```json
{
  "event": "chat.message",
  "requestId": "...",
  "streamId": "...",
  "sessionId": 1,
  "ack": 0,
  "payload": {
    "content": "..."
  }
}
```

服务端处理：

```text
校验 session
-> requireAskQuestion
-> ActiveExecutionRegistry 防并发
-> 保存 USER message
-> RuntimeState = RUNNING
-> chat.started
-> 检索证据
-> 构造 prompt
-> 生成 answer
-> 分段发送 chat.delta
```

### 4.3 stop

```text
client chat.stop
-> 找 ActiveExecution
-> execution.stop()
-> runtimeStatus STOPPED
-> 保存 partialContent
-> chat.stopped
```

stop 不是前端不显示，而是后端停止继续推送。

### 4.4 resume

```text
client chat.resume(ack=12)
-> Redis readEventsAfter(12)
-> replay events
-> read snapshot
-> chat.restored(runtimeStatus, partialContent)
```

ack 的作用是告诉服务端：客户端已经收到哪条事件。服务端只重放后面的事件。

## 5. 八股知识点：WebSocket 和 SSE 区别

SSE 是 Server-Sent Events，服务端向客户端单向推送。

WebSocket 是双向通信。

SSE 优点：

- 简单。
- 基于 HTTP。
- 适合服务端单向推流。

WebSocket 优点：

- 双向。
- 适合 stop、resume、ack、状态同步。
- 更适合复杂实时交互。

NoteWeave 需要客户端发 stop/resume，所以 WebSocket 更合适。

## 6. Memory 设计：短期和长期

### 6.1 短期运行态

存在 Redis：

- RuntimeState。
- StreamState。
- ShortTermContext。
- Event buffer。
- partialContent。

它解决“当前这轮生成到哪里了”。

### 6.2 长期记忆

存在 MySQL：

- session_summary。
- memory_item。
- space_memory。
- user_memory。

它解决“后续对话需要记住哪些稳定信息”。

### 6.3 Summary 是什么

Summary 是会话摘要。它不是完整聊天记录，而是把历史压缩成较短内容，减少 token。

为什么需要 summary：

- 聊天历史太长。
- 直接塞历史会超 token。
- 历史里有噪声。
- 摘要能保留主线。

## 7. MemoryWriteback 策略

不是所有内容都能写 memory。

NoteWeave 会跳过：

- DRAFT。
- 空轮次。
- 短问候。
- password/token/secret/api-key/email 等敏感信息。

可能写入：

- 稳定偏好。
- 有意义的正式对话摘要。
- 当前空间相关背景。

为什么 DRAFT 不写？因为 DRAFT 是探索态，用户可能随便试问，不代表稳定意图。

## 8. 八股知识点：上下文工程

大模型上下文不是越多越好。

过多上下文会导致：

- token 成本高。
- 模型注意力分散。
- 噪声增加。
- 隐私风险上升。
- 错误历史被强化。

所以 NoteWeave 用 `ContextReadRouter` 决定读哪些上下文：

```text
DRAFT:
recentHistory + retrievalEvidence

FORMAL:
recentHistory + sessionSummary + spaceMemory + userMemory + retrievalEvidence
```

## 9. 底层原理补充：WebSocket、ack 和恢复语义

### 9.1 WebSocket 握手

WebSocket 最开始是 HTTP 请求，通过 Upgrade 头把协议升级成长连接。连接建立后，客户端和服务端可以双向发送 frame。

为什么要 ticket？因为 WebSocket URL 可能出现在日志或浏览器记录里，不适合长期携带 JWT。一次性 ticket 更安全。

### 9.2 ack 是什么

ack 是 acknowledgement，确认。客户端告诉服务端“我已经收到第几条事件”。

没有 ack 时，页面刷新后服务端不知道客户端收到哪里了，只能全量重发或放弃恢复。有 ack 后可以：

```text
readEventsAfter(ack)
```

只重放缺失事件。

### 9.3 恢复语义不是强一致

WebSocket resume 恢复的是近期 runtime 事件，不是永久消息日志。如果 Redis 过期或丢失，系统可以退化为读取 MySQL 已完成消息，但无法恢复未完成 partialContent。这就是短期运行态和长期事实的边界。

### 9.4 Memory 写入像数据清洗

长期记忆不是“保存所有信息”，更像对会话进行过滤、摘要和归类。写入前要判断价值和风险：

- 是否稳定。
- 是否敏感。
- 是否只是寒暄。
- 是否属于用户偏好。
- 是否属于空间上下文。

## 10. 可直接复述的深答

NoteWeave 的 WebSocket Runtime 不只是为了流式显示，而是为了管理一次 AI 生成的完整运行态。客户端先通过 HTTP 拿一次性 ticket，再建立 WebSocket 连接。用户发送 chat.message 后，服务端会校验权限，用 ActiveExecutionRegistry 防止同一 session 并发生成，然后把 RuntimeState 写 Redis，发送 chat.started。生成过程中服务端持续推 chat.delta，并把事件和 partialContent 存到 Redis。用户 stop 时，服务端设置 stopRequested，更新 STOPPED 状态并保存 partialContent；页面刷新后，客户端带 ack 调 chat.resume，服务端重放 ack 之后的事件并返回 chat.restored。长期记忆则和 runtime 分开，存在 MySQL。FORMAL 会话完成后才可能写 session summary、space memory 和 user memory；DRAFT、短问候、敏感内容都不会写。这样既支持实时交互，也能控制长期记忆污染。

## 11. 面试官可能追问

### 11.1 Redis 丢了怎么办

丢的是运行态和 partialContent，正式 message、citation、memory 已在 MySQL 的不会丢。

### 11.2 Memory 会不会越写越脏

会有风险，所以要有写入过滤、TTL、用户管理、pin、置信度和读取计划。

### 11.3 为什么不把所有历史都塞给模型

成本高、噪声大、隐私风险高，而且模型可能被历史错误误导。
