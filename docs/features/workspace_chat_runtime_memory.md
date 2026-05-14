# 工作台会话执行与记忆底座

本文档描述 NoteWeave 的工作台会话执行底座，包括 WebSocket、正式会话、临时草稿、流式生成、中断停止、上下文恢复和分层记忆。

目标是支撑复杂研究任务下的连续多轮交互，同时避免每轮全量回放历史消息。

---

## 1. 参考实现

可参考 `PaiSmart-main` 中以下文件：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\handler\ChatWebSocketHandler.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\WebSocketConfig.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\StudentStudySessionRuntimeService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\ConversationStateStore.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\MemoryReadRouter.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\model\StudySession.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\model\WorkspaceMemory.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\model\UserProfileMemory.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\model\SessionSummaryArchive.java
```

参考点：

- WebSocket 握手认证。
- 正式会话与临时草稿会话分开。
- Redis 保存 runtime、short term context、stream state。
- MySQL 保存会话、消息、长期记忆和历史摘要。
- 停止生成时维护 active execution 和 stop flag。
- MemoryReadRouter 决定当前轮读取哪些记忆层。

---

## 2. 设计目标

工作台会话需要支持：

```text
正式会话
临时草稿
会话切换
流式生成
中断停止
上下文恢复
近期对话加载
相关历史摘要加载
工作台长期记忆
用户稳定偏好
```

核心原则：

- Chat CRUD 只负责会话和消息持久化。
- Chat Runtime 负责 WebSocket 执行态。
- Redis 保存当前轮运行态。
- MySQL 保存长期数据。
- 每轮上下文按需装配，不全量回放历史消息。

---

## 3. 领域模型

### 3.1 ChatSession

```text
id
user_id
space_id
session_type
session_kind
scope_type
scope_ids
title
status
runtime_status
last_message_preview
session_summary
latest_context_snapshot_json
last_active_at
created_at
updated_at
```

`session_type`：

```text
TEAM_CHAT
PERSONAL_RESEARCH_CHAT
ARTIFACT_CHAT
```

`session_kind`：

```text
FORMAL
DRAFT
```

`runtime_status`：

```text
IDLE
RUNNING
STOPPED
FAILED
```

### 3.2 ChatMessage

```text
id
session_id
message_seq
role
content
message_type
message_citation 关联
artifact_id
created_at
```

`role`：

```text
USER
ASSISTANT
SYSTEM
TOOL
```

### 3.3 SessionSummary

用于保存历史会话摘要，支持后续相关摘要召回。

```text
id
user_id
space_id
session_id
topic
query_type
scope_type
scope_id
summary
resolved_entities_json
reference_source_json
importance_score
confidence_score
stale
pin
created_at
updated_at
expired_at
```

### 3.4 SpaceMemory

工作台长期记忆。

```text
id
user_id
space_id
topic
summary
focused_sources_json
resolved_entities_json
artifact_preferences_json
conversation_patterns_json
created_at
updated_at
expires_at
```

### 3.5 UserMemory

用户稳定偏好和个人风格。

```text
id
user_id
summary
preferences_json
style_profile_json
habit_profile_json
created_at
updated_at
```

---

## 4. Redis 运行态设计

### 4.1 Runtime State

key：

```text
chat:{sessionId}:runtime
```

内容：

```json
{
  "sessionId": "string",
  "spaceId": 1,
  "sessionKind": "formal",
  "runtimeStatus": "running",
  "memoryScene": "space_chat",
  "queryType": "summary",
  "startedAt": "2026-05-14T10:00:00"
}
```

### 4.2 Short Term Context

key：

```text
chat:{sessionId}:short_term
```

内容：

```json
{
  "recentHistory": [],
  "evidenceSources": [],
  "memoryScene": "space_chat",
  "updatedAt": "2026-05-14T10:00:00"
}
```

### 4.3 Stream State

key：

```text
chat:{sessionId}:stream
```

内容：

```json
{
  "status": "running",
  "partialContent": "当前已经生成的内容",
  "streamId": "string",
  "updatedAt": "2026-05-14T10:00:00"
}
```

TTL 建议：

```text
runtime: 2 小时
short_term: 2 小时
stream: 2 小时
```

---

## 5. WebSocket 协议

Endpoint：

```text
WebSocket /ws/chat/{ticket}
```

握手：

- 前端先通过 HTTP 获取一次性 ticket。
- WebSocket 握手时校验 ticket。
- 服务端把 `userId` 写入 session attributes。

客户端消息类型：

```text
chat.message
chat.stop
chat.switch_session
chat.resume
```

服务端事件类型：

```text
chat.connected
chat.started
chat.delta
chat.completed
chat.stopped
chat.failed
chat.restored
```

### 5.1 chat.message

```json
{
  "type": "chat.message",
  "sessionId": "string",
  "spaceId": 1,
  "sessionKind": "FORMAL",
  "scopeType": "KNOWLEDGE_BASE",
  "scopeIds": [1],
  "content": "问题内容"
}
```

### 5.2 chat.stop

```json
{
  "type": "chat.stop",
  "sessionId": "string"
}
```

### 5.3 chat.resume

```json
{
  "type": "chat.resume",
  "sessionId": "string"
}
```

---

## 6. ContextReadRouter

`ContextReadRouter` 决定当前轮读取哪些上下文层。

输入：

```text
space_id
session_kind
query_type
scope_type
```

输出：

```text
scene
read_recent_history
read_session_summary
read_space_memory
read_user_memory
read_retrieval_evidence
```

建议规则：

```text
DRAFT:
  recent_history = true
  session_summary = false
  space_memory = false
  user_memory = false
  retrieval_evidence = true

FORMAL + TEAM_CHAT:
  recent_history = true
  session_summary = true
  space_memory = true
  user_memory = true
  retrieval_evidence = true

FORMAL + PERSONAL_RESEARCH_CHAT:
  recent_history = true
  session_summary = true
  space_memory = true
  user_memory = true
  retrieval_evidence = true
```

---

## 7. 生成执行流程

```text
WebSocket 收到 chat.message
  ↓
校验用户和 Space 权限
  ↓
创建或读取 ChatSession
  ↓
保存用户 ChatMessage
  ↓
构造 ActiveExecution
  ↓
写 Redis runtime = running
  ↓
ContextReadRouter 决策
  ↓
加载近期消息
  ↓
加载相关历史摘要
  ↓
加载 SpaceMemory / UserMemory
  ↓
执行检索
  ↓
组装 PromptContext
  ↓
LLM 流式生成
  ↓
持续写 Redis stream partialContent
  ↓
WebSocket 推送 chat.delta
  ↓
完成后保存 assistant ChatMessage
  ↓
保存 Citation
  ↓
更新 SessionSummary / SpaceMemory / UserMemory
  ↓
写 Redis runtime = idle
  ↓
推送 chat.completed
```

---

## 8. 中断停止流程

```text
WebSocket 收到 chat.stop
  ↓
查找 ActiveExecution
  ↓
设置 stopRequested = true
  ↓
取消底层 LLM stream
  ↓
保留 partialContent
  ↓
写 Redis stream status = stopped
  ↓
写 ChatSession runtime_status = STOPPED
  ↓
推送 chat.stopped
```

说明：

- 被停止的回答可以不落正式 assistant message。
- 如果产品需要保留部分回答，可保存为 `message_type = PARTIAL_ASSISTANT`。

---

## 9. 上下文恢复流程

```text
前端刷新
  ↓
WebSocket 重连
  ↓
发送 chat.resume
  ↓
读取 chat:{sessionId}:runtime
  ↓
读取 chat:{sessionId}:stream
  ↓
返回当前 runtimeStatus 和 partialContent
```

若 Redis 已过期：

```text
从 MySQL 读取 ChatSession
  ↓
读取最近 ChatMessage
  ↓
返回 idle 状态
```

---

## 10. 记忆写回策略

正式会话完成后可以写回：

```text
SessionSummary
SpaceMemory
UserMemory
```

临时草稿默认不写回长期记忆。

建议写回策略：

- 每轮都可更新 `ChatSession.session_summary`。
- 重要轮次归档为 `SessionSummary`。
- SpaceMemory 只保存工作台层面的长期上下文。
- UserMemory 只保存稳定偏好，不保存敏感临时输入。

---

## 11. 接口设计

### HTTP

```http
POST /api/v1/chat/ws-ticket
POST /api/v1/chat/sessions
GET  /api/v1/spaces/{spaceId}/chat-sessions
GET  /api/v1/chat/sessions/{sessionId}
GET  /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/sessions/{sessionId}/archive
POST /api/v1/chat/sessions/{sessionId}/convert-to-formal
POST /api/v1/chat/sessions/{sessionId}/discard-draft
```

### WebSocket

```text
WebSocket /ws/chat/{ticket}
```

---

## 12. 验收标准

- WebSocket 握手必须经过认证。
- 用户可以创建正式会话。
- 用户可以创建临时草稿。
- 用户可以切换会话。
- 用户可以流式接收回答。
- 用户可以停止正在生成的回答。
- 刷新后可以恢复运行态或看到最近已保存消息。
- 临时草稿不写长期记忆。
- 正式会话完成后能形成摘要。
- 当前轮上下文不是全量历史消息，而是近期消息、摘要、记忆和检索证据的组合。



