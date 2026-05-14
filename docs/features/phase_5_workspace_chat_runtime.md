# Phase 5: 工作台 WebSocket 会话执行底座

本文档用于指导 NoteWeave 第五阶段编码实现。

范围：

```text
Phase 5: WebSocket Runtime / Formal Session / Draft Session / Streaming / Stop / Resume / Redis Runtime State
```

第五阶段目标是在 Phase 4 HTTP RAG 问答基础上，构建工作台会话执行底座，支持正式会话、临时草稿、流式生成、中断停止和刷新恢复。

本阶段不做长期 Memory 深度写回，不做个人 ResearchProject，不做 Artifact，不做 VectorRetriever / RRF。

---

## 1. 参考文档

请严格参考：

```text
docs/features/phase_4_team_rag_chat_citation.md
docs/features/workspace_chat_runtime_memory.md
docs/features/database_api_blueprint.md
docs/implementation_breakdown.md
```

可参考 PaiSmart-main：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\handler\ChatWebSocketHandler.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\WebSocketConfig.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\StudentStudySessionRuntimeService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\ConversationStateStore.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\MemoryReadRouter.java
```

---

## 2. 阶段目标

第五阶段完成后，系统应具备：

- 前端可以获取 WebSocket ticket。
- WebSocket 握手时完成认证。
- 用户可以通过 WebSocket 发送团队问答消息。
- 支持 `FORMAL` 正式会话。
- 支持 `DRAFT` 临时草稿会话。
- 支持流式返回 `chat.delta`。
- 支持用户中断正在生成的回答。
- Redis 保存 runtime / short_term / stream 状态。
- 刷新重连后可以恢复当前运行态和部分输出。
- 正式会话完成后保存消息和 Citation。
- 临时草稿默认不写入长期摘要或记忆。

---

## 3. 本阶段不做的事

- 不做长期 Memory 深度总结和偏好提炼。
- 不做向量召回。
- 不做 Weighted RRF。
- 不做 Wiki Draft。
- 不做 Artifact。
- 不做个人 ResearchProject。
- 不做多用户协同编辑。

---

## 4. 技术栈新增

```text
Spring WebSocket
Redis
WebClient Streaming
```

Maven 依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

如果前面阶段已经引入 WebFlux，则本阶段复用 WebClient。

---

## 5. 包结构

新增或补全：

```text
com.noteweave.chat.runtime
  ├── controller
  ├── dto
  ├── handler
  ├── protocol
  └── service

com.noteweave.websocket
  ├── config
  └── security
```

建议类：

```text
chat.runtime.controller.WebSocketTicketController
chat.runtime.handler.ChatWebSocketHandler
chat.runtime.protocol.ClientEvent
chat.runtime.protocol.ServerEvent
chat.runtime.service.WebSocketTicketService
chat.runtime.service.ChatRuntimeService
chat.runtime.service.ChatRuntimeStateStore
chat.runtime.service.ContextReadRouter
chat.runtime.service.ActiveExecutionRegistry
websocket.config.WebSocketConfig
websocket.security.WebSocketAuthHandshakeInterceptor
```

---

## 6. 数据模型调整

Phase 4 已有 `chat_session`，本阶段必须启用：

```text
session_kind: FORMAL / DRAFT
runtime_status: IDLE / RUNNING / STOPPED / FAILED
latest_context_snapshot_json
last_active_at
```

Phase 4 已有 `chat_message`，本阶段继续复用。

临时草稿会话：

```text
chat_session.session_kind = DRAFT
chat_session.status = ACTIVE
```

草稿消息是否落库：

- 用户输入可以落库，便于刷新恢复。
- Assistant 部分输出默认只写 Redis stream state。
- 如果用户确认保存，可以后续转正式会话，当前阶段可不做。

---

## 7. Redis 运行态

Runtime：

```text
chat:{sessionId}:runtime
```

Short Term：

```text
chat:{sessionId}:short_term
```

Stream：

```text
chat:{sessionId}:stream
```

TTL：

```text
2 hours
```

Stream 内容示例：

```json
{
  "status": "running",
  "partialContent": "已经生成的内容",
  "streamId": "uuid",
  "updatedAt": "2026-05-14T10:00:00"
}
```

---

## 8. WebSocket 协议

Endpoint：

```text
WebSocket /ws/chat/{ticket}
```

客户端事件：

```text
chat.message
chat.stop
chat.resume
chat.switch_session
```

服务端事件：

```text
chat.connected
chat.started
chat.delta
chat.completed
chat.stopped
chat.failed
chat.restored
```

### chat.message

```json
{
  "type": "chat.message",
  "sessionId": 100,
  "spaceId": 10,
  "sessionKind": "FORMAL",
  "scopeType": "KNOWLEDGE_BASE",
  "scopeIds": [1001],
  "content": "部署流程是什么？"
}
```

### chat.stop

```json
{
  "type": "chat.stop",
  "sessionId": 100
}
```

### chat.resume

```json
{
  "type": "chat.resume",
  "sessionId": 100
}
```

---

## 9. API 设计

```http
POST /api/v1/chat/ws-ticket
```

响应：

```json
{
  "ticket": "one-time-ticket",
  "expiresIn": 60,
  "webSocketUrl": "/ws/chat/one-time-ticket"
}
```

继续复用 Phase 4 HTTP 接口：

```http
POST /api/v1/chat/sessions
GET  /api/v1/spaces/{spaceId}/chat-sessions
GET  /api/v1/chat/sessions/{sessionId}
GET  /api/v1/chat/sessions/{sessionId}/messages
```

---

## 10. Service 设计

### WebSocketTicketService

```java
String createTicket(Long userId);
Long consumeTicket(String ticket);
```

要求：

- ticket 写 Redis。
- ticket 一次性消费。
- TTL 60 秒。

### ChatRuntimeStateStore

```java
void writeRuntimeState(Long sessionId, RuntimeState state);
void writeShortTermContext(Long sessionId, ShortTermContext context);
void writeStreamState(Long sessionId, StreamState state);
Optional<RuntimeSnapshot> readSnapshot(Long sessionId);
void clearRuntime(Long sessionId);
```

### ActiveExecutionRegistry

```java
void register(Long sessionId, ActiveExecution execution);
Optional<ActiveExecution> get(Long sessionId);
void stop(Long sessionId);
void remove(Long sessionId);
```

### ContextReadRouter

本阶段做轻量决策：

```text
DRAFT:
  recent_history = true
  retrieval_evidence = true
  long_memory = false

FORMAL:
  recent_history = true
  retrieval_evidence = true
  long_memory = false
```

长期 Memory 读取和写回留后续。

### ChatRuntimeService

职责：

- 处理 WebSocket `chat.message`。
- 调用 Phase 4 的检索、Prompt、LLM 能力。
- 流式推送 delta。
- 保存正式会话消息和 Citation。
- 写 Redis 运行态。

流程：

```text
收到 chat.message
  ↓
校验用户和 Space 权限
  ↓
读取或创建 ChatSession
  ↓
保存 USER message
  ↓
注册 ActiveExecution
  ↓
写 runtime = RUNNING
  ↓
检索 Evidence
  ↓
构造 Prompt
  ↓
LLM stream
  ↓
持续写 stream partialContent
  ↓
推送 chat.delta
  ↓
完成后保存 ASSISTANT message + Citation
  ↓
写 runtime = IDLE
  ↓
推送 chat.completed
```

停止流程：

```text
收到 chat.stop
  ↓
ActiveExecution.stopRequested = true
  ↓
取消底层 LLM stream
  ↓
写 stream status = stopped
  ↓
chat_session.runtime_status = STOPPED
  ↓
推送 chat.stopped
```

---

## 11. 权限要求

- WebSocket ticket 必须基于已登录用户创建。
- WebSocket 握手必须校验 ticket。
- 发送消息必须校验 `requireAskQuestion`。
- 恢复会话必须校验当前用户可访问该 session 所属 Space。

---

## 12. 错误码补充

```text
WS_TICKET_INVALID
WS_TICKET_EXPIRED
CHAT_RUNTIME_NOT_FOUND
CHAT_RUNTIME_ALREADY_RUNNING
CHAT_RUNTIME_STOP_FAILED
CHAT_STREAM_FAILED
```

---

## 13. 测试建议

```text
WebSocketTicketServiceTest
ChatRuntimeStateStoreTest
ContextReadRouterTest
ChatRuntimeServiceTest
```

重点覆盖：

- ticket 只能消费一次。
- 未认证 WebSocket 连接失败。
- 正式会话流式生成完成后落库。
- stop 后不再推送 delta。
- resume 能返回 Redis 中的 partialContent。
- DRAFT 会话不写长期记忆。

---

## 14. 验收清单

- 能获取 ws-ticket。
- 能建立 WebSocket 连接。
- 能发送 `chat.message`。
- 能收到 `chat.started`。
- 能持续收到 `chat.delta`。
- 完成后收到 `chat.completed`。
- 正式会话保存 USER / ASSISTANT message。
- 正式会话保存 Citation。
- 能发送 `chat.stop` 中断。
- 刷新后 `chat.resume` 能恢复状态。
- 非成员不能通过 WebSocket 提问。

---

## 15. 给 AI 执行第五阶段的边界提醒

- 不要实现长期 Memory 写回。
- 不要实现 VectorRetriever。
- 不要实现 RRF。
- 不要实现 Wiki Draft。
- 不要实现 Artifact。
- 不要实现个人 ResearchProject。
- 所有 HTTP API 必须使用 `/api/v1`。
- WebSocket endpoint 使用 `/ws/chat/{ticket}`。
- 运行态必须写 Redis，不要只放 JVM Map。

