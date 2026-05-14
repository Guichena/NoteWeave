# Phase 4: 团队基础 RAG 问答与 Citation 回溯

本文档用于指导 NoteWeave 第四阶段编码实现。

范围：

```text
Phase 4: Team Chat / BM25 Retrieval / Evidence Context / LLM Answer / Citation
```

第四阶段目标是基于 Phase 3 已写入 Elasticsearch 的 `DocumentChunk`，跑通团队空间内的基础 RAG 问答闭环：

```text
用户提问
  ↓
权限校验
  ↓
BM25 检索 Chunk
  ↓
证据后处理
  ↓
组装 Prompt
  ↓
LLM 生成回答
  ↓
保存 ChatMessage
  ↓
保存 Citation
  ↓
返回答案与引用来源
```

本阶段不做 WebSocket 流式生成，不做向量召回，不做 Weighted RRF，不做 Team Wiki，不做个人 ResearchProject，不做 Artifact。

---

## 1. 参考文档

请严格参考：

```text
docs/features/phase_0_1_bootstrap_auth_space.md
docs/features/phase_3_document_processing_indexing.md
docs/features/database_api_blueprint.md
docs/implementation_breakdown.md
```

可参考 PaiSmart-main：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\HybridSearchService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\ElasticsearchService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\ChatHandler.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\client\DeepSeekClient.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\AnswerCitationAuditHook.java
```

注意：只借鉴检索、Prompt、LLM 调用和引用理念，不照搬旧实体与接口。

---

## 2. 阶段目标

第四阶段完成后，系统应具备：

- 用户可以创建团队 ChatSession。
- 用户可以查看团队 ChatSession 和消息。
- 用户可以在团队会话中发送问题。
- 提问前校验 `SpacePermissionService.canAskQuestion`。
- ES 检索必须按 `spaceId` 和 `knowledgeBaseId` 做 filter。
- 只能召回当前用户有权限访问的团队 Chunk。
- LLM 回答必须基于 Evidence Context。
- 回答需要保存为 `ChatMessage`。
- 引用片段需要保存为 `Citation`。
- `message_citation` 记录回答和引用的关系。
- API 返回 answer 和 citations。
- 没有检索结果时返回可解释的兜底回答。

---

## 3. 本阶段不做的事

- 不做 WebSocket。
- 不做流式响应。
- 不做中断停止。
- 不做长期 Memory。
- 不做 VectorRetriever。
- 不做 Weighted RRF。
- 不做 Rerank。
- 不做 Team Wiki Draft。
- 不做 Artifact。
- 不做个人 ResearchProject。
- 不做多模型切换管理台。

LLMClient 可以只实现一个模型提供方，默认通过配置接入。

---

## 4. 依赖 Phase 3 的能力

本阶段依赖：

```text
Space / SpaceMember / SpacePermissionService
KnowledgeBase
Document
DocumentChunk
Elasticsearch document chunk index
SearchIndexService / Search Debug 能按关键词返回 Chunk
```

Phase 3 Search Debug 可升级为本阶段 `Bm25Retriever` 的内部能力。

---

## 5. 技术栈新增

在 Phase 3 基础上新增：

```text
Spring WebFlux WebClient
LLM API Client
```

Maven 依赖建议：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

如果 Phase 0/1 已经添加 WebFlux，则不重复添加。

---

## 6. 推荐包结构

新增或补全：

```text
com.noteweave.chat
  ├── controller
  ├── dto
  ├── model
  ├── repository
  └── service

com.noteweave.team.rag
  ├── retriever
  ├── evidence
  ├── prompt
  └── service

com.noteweave.citation
  ├── dto
  ├── model
  ├── repository
  └── service

com.noteweave.llm
  ├── config
  ├── dto
  └── service
```

建议类：

```text
chat.model.ChatSession
chat.model.ChatMessage
chat.service.ChatSessionService
chat.service.TeamChatService

team.rag.retriever.Bm25Retriever
team.rag.retriever.RetrievedChunk
team.rag.evidence.EvidencePostProcessor
team.rag.evidence.EvidenceItem
team.rag.prompt.TeamRagPromptBuilder

citation.model.Citation
citation.model.MessageCitation
citation.service.CitationService

llm.service.LLMClient
llm.service.ConfigurableLLMClient
```

---

## 7. 配置文件

`application.yml` 新增：

```yaml
llm:
  api:
    base-url: ${LLM_API_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${LLM_API_KEY:}
    model: ${LLM_API_MODEL:deepseek-chat}
    temperature: ${LLM_TEMPERATURE:0.3}
    max-tokens: ${LLM_MAX_TOKENS:2000}
    timeout-seconds: ${LLM_TIMEOUT_SECONDS:60}

rag:
  retrieval:
    top-k: ${RAG_RETRIEVAL_TOP_K:8}
    per-document-limit: ${RAG_PER_DOCUMENT_LIMIT:3}
    context-max-chars: ${RAG_CONTEXT_MAX_CHARS:12000}
  prompt:
    no-result-text: "暂无相关信息"
    citation-style: "inline"
```

说明：

- `LLM_API_KEY` 为空时，可以启用 stub client 或在调用时报明确配置错误。
- 测试环境建议使用 stub client，避免真实 API 依赖。

---

## 8. 数据模型

### 8.1 ChatSession

表：`chat_session`

本阶段必需字段：

```text
id
userId
spaceId
sessionType
sessionKind
title
scopeType
scopeIdsJson
scope 表：chat_session_scope
summary
status
runtimeStatus
createdAt
updatedAt
```

本阶段枚举：

```text
sessionType = TEAM_CHAT
sessionKind = FORMAL
scopeType = SPACE / KNOWLEDGE_BASE
status = ACTIVE / ARCHIVED
runtimeStatus = IDLE
```

说明：

- `DRAFT` 和运行态恢复留给 WebSocket 阶段。
- 本阶段可以创建 `FORMAL` 会话。

### 8.2 ChatMessage

表：`chat_message`

字段：

```text
id
sessionId
messageSeq
role
content
messageType
artifactId
status
requestId
tokenUsageJson
errorCode
createdAt
updatedAt
```

枚举：

```text
role = USER / ASSISTANT / SYSTEM
messageType = TEXT
```

### 8.3 Citation

表：`citation`

字段：

```text
id
spaceId
sourceType
sourceId
chunkId
title
quoteText
locationInfo
pageNo
startOffset
endOffset
quoteHash
snapshotObjectKey
createdAt
updatedAt
```

本阶段：

```text
sourceType = DOCUMENT
sourceId = documentId
chunkId = documentChunkId
```

### 8.4 MessageCitation

表：`message_citation`

字段：

```text
id
messageId
citationId
createdAt
updatedAt
```

### 8.5 RetrievalTrace / LLMCallLog / AnswerFeedback

本阶段落最小字段，Phase 14 再扩展为完整评测平台。

```text
retrieval_trace:
  id, userId, spaceId, sessionId, messageId, queryText, topK, latencyMs, createdAt

llm_call_log:
  id, userId, spaceId, sessionId, messageId, provider, model, promptHash, inputTokens, outputTokens, latencyMs, success, errorCode, createdAt

answer_feedback:
  id, userId, spaceId, sessionId, messageId, rating, reason, comment, createdAt
```

---

## 9. Repository 清单

### ChatSessionRepository

```java
Optional<ChatSession> findByIdAndStatus(Long id, ChatSessionStatus status);
List<ChatSession> findBySpaceIdAndUserIdAndStatusOrderByUpdatedAtDesc(Long spaceId, Long userId, ChatSessionStatus status);
```

### ChatMessageRepository

```java
List<ChatMessage> findBySessionIdOrderByMessageSeqAsc(Long sessionId);
Optional<ChatMessage> findTopBySessionIdOrderByMessageSeqDesc(Long sessionId);
```

### CitationRepository

```java
List<Citation> findBySpaceIdAndChunkIdIn(Long spaceId, Collection<Long> chunkIds);
```

### MessageCitationRepository

```java
List<MessageCitation> findByMessageId(Long messageId);
```

---

## 10. Service 设计

### 10.1 ChatSessionService

职责：

- 创建团队会话。
- 查询会话列表。
- 查询会话详情。
- 查询消息列表。
- 归档会话。

方法：

```java
ChatSessionResponse createTeamSession(Long userId, CreateChatSessionRequest request);
List<ChatSessionResponse> listBySpace(Long userId, Long spaceId);
ChatSessionResponse getSession(Long userId, Long sessionId);
List<ChatMessageResponse> listMessages(Long userId, Long sessionId);
void archive(Long userId, Long sessionId);
```

权限：

- 创建和查看均需要 `requireViewSpace`。
- `sessionType` 本阶段只允许 `TEAM_CHAT`。

### 10.2 Bm25Retriever

职责：

- 从 ES 中按关键词检索 Chunk。
- 必须带权限过滤字段。
- 检索前必须由调用方完成 `SpacePermissionService.canViewSpace / canAskQuestion` 校验。
- ES 查询必须内嵌 metadata filter，不能先全局召回再在 Java 内存中过滤。

方法：

```java
List<RetrievedChunk> retrieve(TeamRetrievalQuery query);
```

TeamRetrievalQuery：

```java
public record TeamRetrievalQuery(
    Long userId,
    Long spaceId,
    List<Long> knowledgeBaseIds,
    String query,
    int topK
) {}
```

RetrievedChunk：

```java
public record RetrievedChunk(
    Long chunkId,
    Long documentId,
    Long knowledgeBaseId,
    Long spaceId,
    Integer indexVersion,
    Integer chunkIndex,
    String documentTitle,
    String content,
    Double score
) {}
```

ES 查询要求：

```text
must:
  match content / title

filter:
  spaceId = 当前 Space
  knowledgeBaseId in scopeIds
  document.status = INDEXED
  document.activeIndexVersion = chunk.indexVersion
```

如果 `scopeType = SPACE`：

- 可检索该 Space 下用户可见的全部 KnowledgeBase。

如果 `scopeType = KNOWLEDGE_BASE`：

- 只检索 `scopeIds` 中的知识库。

### 10.3 EvidencePostProcessor

职责：

- 对召回 Chunk 做基础后处理。

本阶段只做：

```text
去重
相邻 Chunk 合并
按分数排序
同文档限流
上下文长度截断
```

方法：

```java
List<EvidenceItem> process(List<RetrievedChunk> chunks, EvidenceOptions options);
```

默认参数：

```text
maxEvidencePerDocument = 3
mergeAdjacentChunks = true
maxMergedChars = 2400
finalTopK = 8 ~ 12
maxContextChars = 按模型上下文窗口配置
```

合并边界：

- 只合并同一 `documentId`、同一 `indexVersion` 且 `chunkIndex` 连续的 Chunk。
- 合并后仍算作该文档的一个 evidence block。
- 超过 `maxEvidencePerDocument` 的同文档证据必须丢弃或降级为候选，不进入 Prompt。

EvidenceItem：

```java
public record EvidenceItem(
    int citationIndex,
    Long chunkId,
    Long documentId,
    String documentTitle,
    Integer indexVersion,
    Integer chunkIndex,
    String content,
    Double score
) {}
```

### 10.4 TeamRagPromptBuilder

职责：

- 组装 system prompt。
- 组装 evidence context。
- 约束回答必须基于引用。

方法：

```java
PromptMessages build(String userQuestion, List<EvidenceItem> evidenceItems, List<ChatMessage> recentMessages);
```

Prompt 规则：

```text
你是 NoteWeave 团队知识助手。
你只能基于给定资料回答。
如果资料不足，请说“暂无相关信息”，并说明缺少什么。
回答需要先给结论，再给依据。
引用资料时使用 [来源#编号]。
不要编造不存在的资料、文件名或结论。
```

Evidence 格式：

```text
[来源#1]
文档：部署手册
位置：chunk 3
内容：...
```

### 10.5 LLMClient

职责：

- 调用 LLM。
- 支持测试 Stub。

方法：

```java
LLMResponse chat(List<LLMMessage> messages, LLMOptions options);
```

要求：

- API key 缺失时返回明确错误，或使用配置启用 stub。
- 记录 latency。
- 捕获超时和 API 错误。

### 10.6 CitationService

职责：

- 根据 EvidenceItem 创建 Citation。
- 建立 MessageCitation。
- 查询消息引用。

方法：

```java
List<CitationResponse> saveForAssistantMessage(Long assistantMessageId, Long spaceId, List<EvidenceItem> evidenceItems);
List<CitationResponse> listByMessage(Long messageId);
```

幂等要求：

- 同一个 assistantMessageId 和 chunkId 不应重复关联。

### 10.7 TeamChatService

职责：

- 编排团队问答流程。

方法：

```java
TeamAskResponse ask(Long userId, Long sessionId, TeamAskRequest request);
```

流程：

```text
读取 ChatSession
  ↓
校验 sessionType = TEAM_CHAT
  ↓
校验 requireAskQuestion(spaceId)
  ↓
保存 USER ChatMessage
  ↓
根据 session scope 构造检索范围
  ↓
Bm25Retriever 检索
  ↓
EvidencePostProcessor 后处理
  ↓
如无证据，生成 no-result 回答
  ↓
TeamRagPromptBuilder 组装 Prompt
  ↓
LLMClient 生成回答
  ↓
保存 ASSISTANT ChatMessage
  ↓
CitationService 保存引用
  ↓
返回 answer + citations
```

消息序号：

- 使用当前 session 最大 `messageSeq + 1`。
- 同一轮中 USER 和 ASSISTANT 各占一个序号。

事务建议：

- 保存 USER message 和 ASSISTANT message 可以在一个业务事务内。
- LLM 调用不应放在长事务中。
- 推荐流程：

```text
事务 1：保存 USER message
LLM 检索与生成
事务 2：保存 ASSISTANT message + Citation
```

---

## 11. API 设计

接口统一前缀：

```text
/api/v1
```

### 11.1 Chat Session

```http
POST /api/v1/chat/sessions
GET  /api/v1/spaces/{spaceId}/chat-sessions
GET  /api/v1/chat/sessions/{sessionId}
GET  /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/sessions/{sessionId}/archive
```

CreateChatSessionRequest：

```json
{
  "spaceId": 10,
  "sessionType": "TEAM_CHAT",
  "title": "部署流程问答",
  "scopeType": "KNOWLEDGE_BASE",
  "scopeIds": [1001]
}
```

说明：

- `sessionKind` 本阶段默认为 `FORMAL`。
- `scopeType` 支持 `SPACE` / `KNOWLEDGE_BASE`。

### 11.2 Team Ask

```http
POST /api/v1/chat/sessions/{sessionId}/messages
```

TeamAskRequest：

```json
{
  "content": "这个项目的部署流程是什么？"
}
```

TeamAskResponse：

```json
{
  "userMessageId": 2001,
  "assistantMessageId": 2002,
  "answer": "部署流程分为三步...",
  "citations": [
    {
      "id": 1,
      "sourceType": "DOCUMENT",
      "sourceId": 10,
      "chunkId": 100,
      "title": "部署手册",
      "quoteText": "部署前需要先准备...",
      "locationInfo": "chunk 3"
    }
  ]
}
```

### 11.3 Citation

```http
GET /api/v1/chat/messages/{messageId}/citations
```

说明：

- 只能查看自己可访问 Space 下消息的引用。

### 11.4 Answer Feedback

```http
POST /api/v1/chat/messages/{messageId}/feedback
GET  /api/v1/chat/messages/{messageId}/feedback
```

说明：

- 只能对自己可访问 Space 下的消息提交反馈。
- Phase 14 可以扩展反馈查询、统计和 RAG Eval。

---

## 12. 权限要求

创建团队会话：

```text
requireViewSpace(userId, spaceId)
```

发送团队问答：

```text
requireAskQuestion(userId, session.spaceId)
```

ES 检索：

```text
filter spaceId
filter knowledgeBaseId
```

Citation 返回前：

```text
校验 Citation.spaceId 属于当前用户可访问 Space
```

严禁：

- 先全局检索再在 Java 内存中过滤权限。
- 返回不属于当前 Space 的 Chunk。
- 返回没有对应 evidence 的引用。

---

## 13. 错误码补充

```text
CHAT_SESSION_NOT_FOUND
CHAT_SESSION_ACCESS_DENIED
CHAT_SESSION_TYPE_UNSUPPORTED
CHAT_MESSAGE_EMPTY
RAG_RETRIEVAL_FAILED
RAG_NO_EVIDENCE
LLM_CONFIG_MISSING
LLM_CALL_FAILED
CITATION_SAVE_FAILED
```

---

## 14. 测试建议

### 14.1 单元测试

```text
Bm25RetrieverTest
EvidencePostProcessorTest
TeamRagPromptBuilderTest
CitationServiceTest
TeamChatServiceTest
```

重点覆盖：

- ES 检索 query 包含 `spaceId` filter。
- ES 检索 query 包含 `knowledgeBaseId` filter。
- Evidence 去重有效。
- 同文档限流有效。
- 无证据时返回 no-result。
- Prompt 包含引用编号。
- Citation 能保存并关联 Assistant Message。
- 非成员不能创建会话。
- VIEWER 可以提问。

### 14.2 集成测试

如果本地有 ES 和测试数据：

```text
创建团队 Space
创建 KnowledgeBase
上传并索引文档
创建 ChatSession
发送问题
返回 answer
返回 citations
检查 chat_message
检查 citation / message_citation
```

测试环境建议使用 Stub LLM：

```text
输入 evidence
返回固定 answer
```

这样避免测试依赖真实 API key。

---

## 15. 验收清单

Phase 4 验收：

- 可以创建 TEAM_CHAT 会话。
- 可以列出 Space 下会话。
- 可以查看会话消息。
- 可以发送问题。
- 发送问题后保存 USER message。
- 能从 ES 召回当前 Space / KnowledgeBase 下 Chunk。
- ES 检索带权限 filter。
- 能组装 evidence prompt。
- 能调用 LLM 或 Stub LLM。
- 能保存 ASSISTANT message。
- 能保存 Citation。
- 能通过 API 返回 citations。
- 无检索结果时返回“暂无相关信息”类回答。
- 非成员不能创建会话或提问。
- A Space 的问题不能召回 B Space 的文档。

---

## 16. 实现顺序建议

```text
1. 添加 LLM 配置和 LLMClient / StubLLMClient
2. 实现 ChatSession / ChatMessage 实体和 Repository
3. 实现 Citation / MessageCitation 实体和 Repository
4. 实现 ChatSessionService
5. 实现 Bm25Retriever
6. 实现 EvidencePostProcessor
7. 实现 TeamRagPromptBuilder
8. 实现 CitationService
9. 实现 TeamChatService
10. 实现 ChatController
11. 实现 Citation 查询接口
12. 补单元测试
13. 运行 mvn test 或 mvn package
```

---

## 17. 给 AI 执行第四阶段的边界提醒

执行第四阶段时必须遵守：

- 不要实现 WebSocket。
- 不要做流式响应。
- 不要实现停止生成。
- 不要实现 Memory。
- 不要实现 VectorRetriever。
- 不要实现 Weighted RRF。
- 不要实现 Wiki Draft。
- 不要实现 Artifact。
- 不要实现个人 ResearchProject。
- 不要把 Citation 存进消息 JSON 字段，必须使用 `message_citation` 关联表。
- 所有 API 必须使用 `/api/v1`。
- ES 检索必须带 `spaceId` 和 `knowledgeBaseId` filter。
- LLM 调用失败必须返回明确错误或可观测失败状态。



