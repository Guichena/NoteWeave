# 文件：06_Memory_WebSocket_会话恢复.md

## 1. 本主题覆盖的通用问题

- 记忆系统怎么设计？
- 短期记忆、长期记忆、summary 分别怎么存？
- 长期记忆什么时候写入？
- 哪些信息不写入，为什么？
- 会话中断后的后端恢复链路怎么设计？
- HTTP 问答已经有了，为什么还要 WebSocket？

## 2. 架构视角怎么切入

NoteWeave 把“运行态”和“长期记忆”分开：

- 运行态：当前这轮生成状态，存在 Redis，用于 stop、resume、partialContent。
- 长期记忆：跨轮次和跨会话的稳定上下文，存在 MySQL，用于后续 prompt。

这个边界很重要。短期运行态丢失不应破坏长期业务事实；长期记忆写入必须谨慎，避免污染。

## 3. 完整链路深讲

### 3.1 WebSocket Runtime

```text
POST /chat/ws-ticket
-> Redis ticket
-> WebSocket connect
-> chat.connected
-> client chat.message
-> ActiveExecutionRegistry register
-> RuntimeState RUNNING
-> chat.started
-> retrieval + prompt + LLM
-> chat.delta events
-> StreamState partialContent
-> chat.completed / stopped / failed
```

Runtime 状态包括：

- `RuntimeState`: sessionId、userId、spaceId、sessionKind、runtimeStatus、requestId、streamId。
- `StreamState`: streamId、status、partialContent。
- `ShortTermContext`: recentMessages、evidenceTitles。
- event buffer: seq、ack、payload。

### 3.2 Stop

用户发 `chat.stop`：

```text
find ActiveExecution
-> execution.stop()
-> session runtimeStatus STOPPED
-> Redis StreamState 保存 partialContent
-> append chat.stopped
```

它不是前端停止展示，而是服务端停止继续推送 delta。

### 3.3 Resume

刷新后客户端发 `chat.resume`：

```text
read events after ack
-> replay events
-> read RuntimeSnapshot
-> send chat.restored(runtimeStatus, partialContent)
```

### 3.4 长期记忆写入

FORMAL 会话完成后触发 MemoryWriteback。DRAFT 不写。

写入策略：

- 空轮次跳过。
- DRAFT 跳过。
- 短问候跳过。
- 敏感内容跳过。
- 偏好信号写 user memory。
- 有意义轮次写 session summary / space memory。

### 3.5 长期记忆读取

`ContextReadRouter` 根据 sessionKind 和 sessionType 决定读取计划：

- DRAFT：recentHistory + retrievalEvidence。
- FORMAL TEAM_CHAT：recentHistory + sessionSummary + spaceMemory + userMemory + retrievalEvidence。

## 4. 关键实现锚点

- `ChatRuntimeService`
- `ChatRuntimeStateStore`
- `ActiveExecutionRegistry`
- `ContextReadRouter`
- `MemoryWritebackStrategy`
- `MemoryWritebackService`
- `MemoryContextService`
- `SessionSummaryService`
- `SpaceMemoryService`
- `UserMemoryService`

## 5. 为什么这么设计

### 5.1 为什么 WebSocket 不只是流式显示

流式显示只是表面。真正的 runtime 要支持：

- stop。
- resume。
- ack。
- partialContent。
- 防并发执行。
- DRAFT/FORMAL 生命周期。

SSE 可以做单向流，但 stop/resume 和双向控制不如 WebSocket 清晰。

### 5.2 为什么 DRAFT 不写长期记忆

DRAFT 是探索态，用户可能只是试问、改写、临时发散。如果写入 memory，会污染后续回答。FORMAL 才代表用户愿意保留的正式上下文。

### 5.3 为什么长期记忆不能全量历史

全量历史会带来 token 膨胀、噪声积累、隐私风险和错误强化。分层 memory + 策略写入 + 策略读取更稳。

## 6. 可直接复述的深答

NoteWeave 把 WebSocket runtime 和长期 memory 分开设计。WebSocket 解决的是当前这轮生成的运行态：ticket 建连、chat.delta 流式输出、stop、resume、partialContent 和 ack 重放，这些短期状态放 Redis。长期 memory 解决的是正式会话后的稳定上下文，放 MySQL，包括 session summary、space memory 和 user memory。DRAFT 会话只用于探索，不写长期 memory；FORMAL 完成后才触发 MemoryWriteback。写入策略会跳过敏感内容、短问候、空轮次和 DRAFT，只把有意义轮次或稳定偏好写入。读取时也不是全量塞历史，而是由 ContextReadRouter 决定读 recent history、summary、space memory、user memory 和 retrieval evidence。这样既支持流式体验和断线恢复，也能避免长期记忆污染。

## 7. 追问兜底

### 如果问“Redis 丢了怎么办”

丢失的是 runtime 恢复能力和 partialContent，不应该影响已落库的正式 message、citation 和 memory。

### 如果问“Memory 会不会越写越脏”

靠写入过滤、敏感跳过、TTL、pin、用户可管理、读取计划和后续 eval/bad case 复盘控制。

### 如果问“用户隐私怎么保证”

个人 memory 是当前用户私有，space memory 当前阶段也是 user + space 私有，不是团队共享 memory。

