# Phase 12: 长期 Memory 深化

本文档用于指导 NoteWeave 第十二阶段编码实现。

范围：

```text
Phase 12: SessionSummary / SpaceMemory / UserMemory / Memory Read Router / Memory Writeback
```

第十二阶段目标是在 Phase 5 会话运行态基础上，补齐长期记忆能力：会话摘要归档、工作台长期记忆、用户稳定偏好、当前轮上下文按需加载。

---

## 1. 参考文档

```text
docs/features/phase_5_workspace_chat_runtime.md
docs/features/workspace_chat_runtime_memory.md
docs/features/database_api_blueprint.md
```

可参考 PaiSmart-main：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\MemoryReadRouter.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\WorkspaceMemoryService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\UserProfileMemoryService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\SessionSummaryArchiveService.java
```

---

## 2. 阶段目标

- 正式会话完成后生成 SessionSummary。
- 重要会话轮次归档到 `session_summary`。
- Space 级长期记忆写入 `space_memory`。
- 用户稳定偏好写入 `user_memory`。
- 当前轮上下文按需加载近期消息、相关摘要、SpaceMemory、UserMemory、检索证据。
- 临时草稿不写长期 Memory。
- Memory 支持过期、置顶、重要性和可信度。

---

## 3. 本阶段不做的事

- 不做团队共享复杂 Memory 权限。
- 不做知识图谱记忆。
- 不做自动个性化推荐。
- 不做外部资料发现。

---

## 4. 数据模型

### SessionSummary

表：`session_summary`

核心字段：

```text
userId
spaceId
sessionId
topic
queryType
scopeType
scopeId
summary
resolvedEntitiesJson
referenceSourceJson
importanceScore
confidenceScore
stale
pin
expiredAt
```

### SpaceMemory

表：`space_memory`

```text
userId
spaceId
topic
summary
focusedSourcesJson
resolvedEntitiesJson
artifactPreferencesJson
conversationPatternsJson
expiresAt
```

### UserMemory

表：`user_memory`

```text
userId
summary
preferencesJson
styleProfileJson
habitProfileJson
```

---

## 5. ContextReadRouter

输入：

```text
spaceId
sessionKind
sessionType
queryType
scopeType
```

输出：

```text
readRecentHistory
readSessionSummary
readSpaceMemory
readUserMemory
readRetrievalEvidence
```

规则：

```text
DRAFT:
  recentHistory = true
  sessionSummary = false
  spaceMemory = false
  userMemory = false
  retrievalEvidence = true

FORMAL TEAM_CHAT:
  recentHistory = true
  sessionSummary = true
  spaceMemory = true
  userMemory = true
  retrievalEvidence = true

FORMAL PERSONAL_RESEARCH_CHAT:
  recentHistory = true
  sessionSummary = true
  spaceMemory = true
  userMemory = true
  retrievalEvidence = true
```

---

## 6. Service 设计

### SessionSummaryService

```java
SessionSummary createAfterRound(ChatSession session, ChatMessage userMessage, ChatMessage assistantMessage, List<Citation> citations);
List<SessionSummary> retrieveRelevant(Long userId, Long spaceId, String query);
```

### SpaceMemoryService

```java
SpaceMemory getOrCreate(Long userId, Long spaceId);
void updateAfterRound(MemoryWriteContext context);
Map<String, Object> getPromptMemory(Long userId, Long spaceId);
```

### UserMemoryService

```java
UserMemory getOrCreate(Long userId);
void updatePreferences(MemoryWriteContext context);
Map<String, Object> getPromptProfile(Long userId);
```

### MemoryWritebackStrategy

```java
MemoryWriteDecision decide(ChatSession session, ChatMessage userMessage, ChatMessage assistantMessage);
```

规则：

- DRAFT 不写回。
- 很短的寒暄不写回。
- 包含明确偏好、项目目标、长期主题时写回。
- 低置信度内容不覆盖已有稳定偏好。

---

## 7. 集成点

Phase 5 ChatRuntimeService 完成后：

```text
保存 ASSISTANT message
  ↓
MemoryWritebackStrategy 判断
  ↓
SessionSummaryService 归档
  ↓
SpaceMemoryService 更新
  ↓
UserMemoryService 更新
```

Prompt 构造前：

```text
ContextReadRouter 决策
  ↓
加载 recentHistory
  ↓
加载 relevant SessionSummary
  ↓
加载 SpaceMemory
  ↓
加载 UserMemory
  ↓
加载 retrieval evidence
```

---

## 8. API 设计

```http
GET /api/v1/spaces/{spaceId}/memory
PUT /api/v1/spaces/{spaceId}/memory
GET /api/v1/users/me/memory
PUT /api/v1/users/me/memory
GET /api/v1/chat/sessions/{sessionId}/summaries
```

说明：

- 这些接口用于查看和手动修正记忆。
- 默认只允许 owner 查看自己的 user memory。

---

## 9. 权限要求

- SpaceMemory 按 userId + spaceId 隔离。
- UserMemory 只能当前用户访问。
- SessionSummary 只能访问自己可访问 Space 下的记录。
- DRAFT 会话不写长期记忆。

---

## 10. 验收清单

- 正式会话完成后能生成 SessionSummary。
- SpaceMemory 能被更新。
- UserMemory 能被更新。
- DRAFT 不写 Memory。
- Prompt 构造时能加载 Memory。
- 用户可以查看/编辑自己的 Memory。
- Memory 不会全量塞入 Prompt，而是摘要式加载。

---

## 11. 给 AI 执行第十二阶段的边界提醒

- 不要做团队共享复杂 Memory。
- 不要把所有历史消息塞入 Prompt。
- 不要让 DRAFT 写长期记忆。
- 不要覆盖用户稳定偏好，除非置信度足够。
- 所有 API 必须使用 `/api/v1`。

