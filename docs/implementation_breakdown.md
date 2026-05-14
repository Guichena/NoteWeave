# NoteWeave Implementation Breakdown

本文档是 NoteWeave 当前唯一的阶段执行计划。它根据 `architecture_review_issues_and_recommendations.md` 和 `review2.md` 对原计划做了收敛：统一 Phase 0/1 到 Phase 16 的阶段线，前置 Task/Outbox/权限/认证等地基能力，移出当前暂缓功能，并把前端从最后一次性阶段调整为随阶段验证的薄片交付。

具体功能细节仍以 `docs/features` 下对应专题文档为准；如果专题文档与本文档冲突，先按本文档修正专题文档，再进入编码。

---

## 1. 执行原则

- 先统一契约，再写代码：数据库表、API 路径、状态枚举、错误码、分页格式和权限边界必须先收敛。
- 先稳定异步任务，再接 AI 能力：上传、解析、索引、Source 编译、Artifact 生成、Wiki 入索引、评测都必须走统一 Task/Worker 模型。
- 先跑通团队 RAG 最小闭环，再扩展个人研究、Studio、Wiki 和 Memory。
- 团队侧与个人侧分治：团队以 `Space -> KnowledgeBase -> Document -> DocumentChunk` 为主线，个人以 `Space -> ResearchProject -> Source -> ArticleCard/ConceptCard` 为主线。
- Artifact 与 Wiki 严格分离：阶段性产物保存在 Artifact；只有人工确认的长期稳定内容才能发布为 Wiki。
- 所有业务 API 统一使用 `/api/v1`；旧的 `/api/...` 示例视为待修正文档，不再作为实现依据。
- 所有列表 API 默认支持分页、筛选和排序；统一响应、统一错误码和统一认证头是 Phase 0/1 的交付物。
- 当前阶段暂缓 Quiz/测验/答题/评分/题库、外部资料自动发现、商业化计费、复杂企业审批流、复杂多人实时协同编辑。

---

## 2. 单一事实源

编码前先确认以下文档口径一致：

| 契约 | 权威来源 | 要求 |
|---|---|---|
| 阶段顺序 | `implementation_breakdown.md` | 本文档为唯一执行顺序。 |
| 表结构与 API | `database_api_blueprint.md` | 必须同步所有阶段用到的表、索引、枚举和 `/api/v1` API。 |
| 功能细节 | `docs/features/phase_*.md` | 按本文档的阶段顺序修正后再编码。 |
| 上传链路 | `file_upload_async_pipeline.md` | 必须补齐 cancel、Outbox、对象引用、过期清理。 |
| 会话运行态 | `workspace_chat_runtime_memory.md` | 必须与 Phase 5 的 WebSocket 事件名、DRAFT 生命周期一致。 |
| 暂缓功能 | 本文档第 9 节 | 不进入当前阶段验收。 |

---

## 3. 领域模型收口

### 3.1 基础与权限

```text
User
UserSession / RefreshToken
Space
SpaceMember
SystemRole
ResourceAccessService
```

约定：

- `Space` 是最高业务容器，`Space.type = PERSONAL | TEAM`。
- 每个用户注册后自动拥有一个 PERSONAL Space。
- 团队协作通过 `SpaceMember` 管理，角色为 `OWNER / EDITOR / VIEWER`。
- 系统后台通过 `User.systemRole = USER | ADMIN` 或等价系统角色表管理，不能复用 SpaceMember 角色。
- 通用资源访问必须经过 `ResourceAccessService` 或模块内等价权限服务，不能只按 id 查询。

### 3.2 团队侧

```text
KnowledgeBase
DocumentUpload
UploadChunk
FileObject
Document
DocumentChunk
WikiPage
WikiPageVersion
```

约定：

- `KnowledgeBase` 只属于 TEAM Space。
- `Document` 属于 `KnowledgeBase`，并通过 `FileObject` 引用对象存储内容。
- `DocumentChunk` 是团队 RAG 的基础证据单位。
- 文档重处理必须版本化，不能先删除旧索引再写新索引。
- Wiki 只保存人工确认后的长期稳定知识。

### 3.3 个人侧

```text
ResearchProject
Source
SourceChunk / ParsedSourceText
ArticleCard
ConceptCard
ConceptAlias
ConceptRelation
ArticleConceptRelation
MethodologyCard
```

约定：

- `ResearchProject` 属于 PERSONAL Space。
- Source 导入完成必须产出可编译 Raw Text 或 Parsed Text，不能仅保存元数据后标记 READY。
- ArticleCard/ConceptCard 的证据关系必须能回溯到 Source 原文，证据不应只放 JSON。
- MethodologyCard 的预置模板和匹配器需要在个人生成前可用，高级编辑能力可以后置。

### 3.4 通用侧

```text
Task
TaskAttempt
TaskEvent
TaskOutbox
ChatSession
ChatSessionScope
ChatMessage
Citation / Evidence
Artifact
ArtifactVersion
ArtifactSource
SkillExecutionLog
LLMCallLog
RetrievalTrace
AuditLog
```

约定：

- 统一 `Task` 承载异步任务，不再使用 `generation_task`、`index_task` 等并行概念。
- `Citation/Evidence` 覆盖 Message、Card、Artifact、WikiDraft、Eval，不只服务团队问答。
- `Artifact` 必须有版本或修订记录，编辑和重新生成不能覆盖历史。
- LLM、检索、Skill 日志必须默认脱敏，不直接暴露完整 Prompt、Memory 或私有原文。

---

## 4. 编码前必须先修正的 P0 契约

这些不是“后续优化”，而是开始阶段编码前需要先写入设计文档的地基。

### 4.1 API 与通用响应

- 所有业务接口统一 `/api/v1`。
- 统一响应结构：`success/code/message/data/timestamp/requestId`。
- 分页响应统一：`items/page/pageSize/total/sort`。
- 所有列表 API 必须明确分页、筛选、排序参数。
- 建立全局错误码注册表，阶段文档只能追加，不能各自发明冲突错误码。

### 4.2 认证与用户

- Auth 必须包含 register、login、refresh、logout、logout-all。
- 增加 `UserSession` 或 `RefreshToken` 表，refresh token 存 hash，可撤销，可过期。
- 增加用户资料管理：`GET/PUT /api/v1/users/me`、修改密码。
- 增加系统角色：`USER / ADMIN`。
- 非 dev 环境必须显式配置 JWT secret、数据库密码、MinIO 密钥和 LLM key，不能使用默认弱值启动。

### 4.3 数据库与迁移

- Phase 0 直接引入 Flyway 或 Liquibase；`ddl-auto=update` 只能作为本地临时模式。
- 业务表统一包含适用的审计字段：`created_at/updated_at/created_by/updated_by/deleted_at/deleted_by/status`。
- 核心隔离字段不能缺：团队资源必须有 `space_id`，个人资源必须有 `user_id` 或 owner 可追溯字段，项目资源必须有 `research_project_id`。
- 明确外键策略：可以采用数据库 FK 或逻辑 FK，但必须在蓝图中写出关系和索引。

### 4.4 Task / Outbox / Worker

统一状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
TIMEOUT
```

统一任务类型：

```text
DOCUMENT_PROCESS
SOURCE_IMPORT
SOURCE_COMPILE
ARTIFACT_GENERATE
WIKI_INDEX
EMBEDDING_BACKFILL
RAG_EVAL_RUN
CLEANUP_RESOURCE
```

必须具备：

- `task.idempotency_key` 唯一约束。
- `task_attempt` 记录每次执行、错误、耗时、worker、开始/结束时间。
- `task_event` 记录状态迁移，便于前端和 Admin 查看。
- `task_outbox` 或等价机制保证 DB 提交与 Kafka/队列投递最终一致。
- RUNNING 任务支持 `cancel_requested`，Worker 在安全点停止。
- Kafka message key 使用 `taskId` 或稳定幂等键；Consumer 处理前查 DB 状态。

### 4.5 文件对象与上传

- 分片对象 key 使用 `chunks/{uploadId}/{chunkIndex}`，不要只用 `fileMd5/chunkIndex`。
- 合并后对象进入 `FileObject`，由 `content_hash/object_key/ref_count/size/content_type` 管理复用。
- 秒传只能复用对象内容，不能复用权限；不同 Space 必须有独立 Document 元数据。
- 上传必须支持 cancel、expiresAt、过期清理和失败补偿。

### 4.6 Citation / Evidence

- Citation 需要记录 `sourceType/sourceId/chunkId/pageNo/startOffset/endOffset/quoteHash/snapshotObjectKey/sourceVersion` 等可回溯字段。
- Message、Card、Artifact、WikiDraft 都通过关联表绑定 Evidence，不能只放 `citation_ids` JSON。
- Citation 返回前必须二次权限校验。

### 4.7 WebSocket 协议

统一事件 envelope：

```json
{
  "event": "chat.delta",
  "requestId": "uuid",
  "streamId": "uuid",
  "sessionId": 1,
  "messageId": 2,
  "seq": 12,
  "payload": {},
  "error": null
}
```

统一事件名：

```text
chat.connected
chat.started
chat.delta
chat.completed
chat.stopped
chat.failed
chat.restored
session.state.updated
```

DRAFT 会话必须有生命周期：

```text
DRAFT_ACTIVE
DRAFT_EXPIRED
CONVERTED
DISCARDED
```

---

## 5. 修正后的阶段计划

### Phase 0: 工程骨架与契约基线

目标：建立可以持续迭代的工程地基。

必须完成：

1. Spring Boot 工程骨架。
2. MySQL、Redis、本地 Docker Compose。
3. Flyway/Liquibase 数据库迁移。
4. 统一响应、统一错误码、统一分页。
5. 请求 `requestId`、基础日志、健康检查。
6. 配置 Profile：dev/test/prod。
7. OpenAPI 或等价 API 契约导出约定。

验收：

- 服务可启动，健康检查可访问。
- `mvn test` 可运行。
- Flyway/Liquibase 可执行首个迁移。
- 统一错误响应和分页结构有测试。

### Phase 1: Auth / User / Space / Permission

目标：锁定身份、空间、成员和权限边界。

必须完成：

1. 用户注册、登录、刷新 token、退出登录、退出全部设备。
2. 当前用户查询与个人资料更新。
3. `UserSession` 或 `RefreshToken`。
4. 注册后创建 PERSONAL Space 和 OWNER SpaceMember。
5. 创建 TEAM Space。
6. Space 成员添加、列表、角色更新、移除。
7. 系统角色 `USER / ADMIN`。
8. `SpacePermissionService` 和 `ResourceAccessService` 初版。

关键 API：

```http
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
POST /api/v1/auth/logout-all
GET  /api/v1/users/me
PUT  /api/v1/users/me
PUT  /api/v1/users/me/password
POST /api/v1/spaces
GET  /api/v1/spaces
GET  /api/v1/spaces/{spaceId}
GET  /api/v1/spaces/{spaceId}/members
POST /api/v1/spaces/{spaceId}/members
PUT  /api/v1/spaces/{spaceId}/members/{memberId}/role
DELETE /api/v1/spaces/{spaceId}/members/{memberId}
```

验收：

- 非成员不能访问 TEAM Space。
- ADMIN 与 Space OWNER 权限分离。
- Refresh token 可撤销，logout 后不可刷新。
- 所有受保护接口走统一认证和权限入口。

### Phase 1.5: Task / Outbox / Worker 基础设施

目标：在任何长任务接入前先稳定异步模型。

必须完成：

1. `task`、`task_attempt`、`task_event`、`task_outbox`。
2. 统一 Task 状态、类型和幂等键生成规则。
3. Task 查询、取消、重试基础 API。
4. Worker 拉取/投递基础能力。
5. 本地 Worker 测试和失败重试测试。
6. 最小健康检查：MySQL、Redis、Worker 状态。

关键 API：

```http
GET  /api/v1/tasks/{taskId}
POST /api/v1/tasks/{taskId}/cancel
POST /api/v1/tasks/{taskId}/retry
GET  /api/v1/tasks/{taskId}/events
```

验收：

- 重复请求不会重复创建同一幂等任务。
- Worker 失败会写 attempt 和 event。
- Cancel 请求能被 RUNNING Worker 在安全点识别。
- Outbox 未投递消息可补偿投递。

### Phase 2: KnowledgeBase、文件对象、分片上传与任务投递

目标：让团队文档可靠进入系统，但不做真实解析。

必须完成：

1. KnowledgeBase CRUD/归档。
2. `FileObject` 与对象引用计数。
3. DocumentUpload 初始化、分片上传、状态查询、merge、cancel。
4. Redis Bitmap key 使用 `upload:{uploadId}` 或等价唯一 key。
5. MinIO 分片 key 使用 `chunks/{uploadId}/{chunkIndex}`。
6. merge 后创建 Document、FileObject 引用、Task、Outbox。
7. 上传过期清理基础任务。
8. Kafka/队列投递幂等。

关键 API：

```http
POST   /api/v1/team/spaces/{spaceId}/knowledge-bases
GET    /api/v1/team/spaces/{spaceId}/knowledge-bases
GET    /api/v1/team/knowledge-bases/{knowledgeBaseId}
PUT    /api/v1/team/knowledge-bases/{knowledgeBaseId}
DELETE /api/v1/team/knowledge-bases/{knowledgeBaseId}
POST   /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init
POST   /api/v1/team/document-uploads/{uploadId}/chunks
GET    /api/v1/team/document-uploads/{uploadId}/status
POST   /api/v1/team/document-uploads/{uploadId}/merge
POST   /api/v1/team/document-uploads/{uploadId}/cancel
GET    /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents
GET    /api/v1/team/documents/{documentId}
DELETE /api/v1/team/documents/{documentId}
```

验收：

- OWNER/EDITOR 可以上传，VIEWER 不可上传。
- 上传可断点续传、可取消、可过期清理。
- 秒传复用 FileObject，但权限和 Document 元数据独立。
- DB 提交和任务投递具备补偿路径。

### Phase 3: 文档解析、Chunk、BM25 索引

目标：把团队 Document 处理成可检索证据。

必须完成：

1. Consumer 只消费 Task 或 taskId，并按 DB 状态幂等执行。
2. PDF/Markdown/TXT 支持；DOCX 要么明确支持，要么上传阶段拒绝。
3. 解析文本保存为 `parsedTextObjectKey` 或等价字段。
4. Chunk 保存页码/section/startOffset/endOffset/contentHash。
5. `indexVersion` 和 active version 机制。
6. ES BM25 索引写入，字段命名统一。
7. Reindex 创建新版本，成功后切换 active，失败不破坏旧索引。
8. 失败写 TaskAttempt、Document 错误状态。

验收：

- 重复消费不会重复创建 active Chunk。
- 旧索引在新索引成功前仍可检索。
- 文档删除或 KB 归档后不会被检索召回。
- Search Debug 接口只返回当前用户有权限的 Chunk。

### Phase 4: 团队基础 RAG、Citation、最小可观测

目标：跑通第一个用户价值闭环。

必须完成：

1. TEAM_CHAT ChatSession 与 ChatMessage。
2. `chat_session_scope` 结构化检索范围。
3. BM25 Retriever，ES 查询必须带 `spaceId/knowledgeBaseId/status` filter。
4. EvidencePostProcessor 初版。
5. TeamRagPromptBuilder，包含 prompt injection 防护规则。
6. LLMClient 与 StubLLMClient。
7. Citation/Evidence 保存与 Message 关联。
8. 最小 `LLMCallLog`、`RetrievalTrace`、`AnswerFeedback`。
9. HTTP 非流式问答。

关键 API：

```http
POST /api/v1/chat/sessions
GET  /api/v1/spaces/{spaceId}/chat-sessions
GET  /api/v1/chat/sessions/{sessionId}
GET  /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/sessions/{sessionId}/messages
GET  /api/v1/chat/messages/{messageId}/citations
POST /api/v1/chat/messages/{messageId}/feedback
```

验收：

- A Space 不能召回 B Space 文档。
- 回答带 Citation，Citation 可查看证据片段。
- LLM 调用和检索 Trace 可反查。
- 无证据时返回明确兜底回答，不编造。

### Phase 5: WebSocket 会话执行底座

目标：支持流式输出、中断、恢复、正式会话和临时草稿。

必须完成：

1. ws-ticket 获取与一次性消费。
2. 统一 WebSocket envelope 和事件名。
3. FORMAL / DRAFT 会话。
4. DRAFT 生命周期：active、expired、converted、discarded。
5. ChatMessage 状态：created、streaming、completed、stopped、failed。
6. Redis runtime、short term、stream state。
7. eventSeq/ack/resume 基础机制。
8. stop 后取消底层 LLM stream，并保留可恢复状态。

关键 API：

```http
POST /api/v1/chat/ws-ticket
POST /api/v1/chat/sessions/{sessionId}/convert-to-formal
POST /api/v1/chat/sessions/{sessionId}/discard-draft
WebSocket /ws/chat/{ticket}
```

验收：

- 刷新后可 resume 到 partialContent 或最近落库消息。
- stop 后不再推送 delta。
- DRAFT 不写长期 Memory。
- 前端和后端事件名完全一致。

### Phase 6: 个人 ResearchProject 与 Source Import

目标：建立个人研究入口，并保证 Source 可被后续编译。

必须完成：

1. ResearchProject CRUD/归档。
2. Source 文件、URL、文本导入。
3. FILE 复用 DocumentParser 解析为 raw/parsed text。
4. URL 至少实现正文抓取或明确失败状态，不能无文本标记 READY。
5. TEXT 直接保存 raw text。
6. `SOURCE_IMPORT` Task、失败重试、去重策略。
7. Source 详情、列表、删除、重新导入。

验收：

- 只有 owner 能访问个人项目和 Source。
- `importStatus=READY` 的 Source 必须有可读取文本。
- 重复导入同一内容不会重复生成后续卡片。

### Phase 7: 个人 Wiki Compiler MVP

目标：基于 Source 生成 ArticleCard 和 ConceptCard。

必须完成：

1. `SOURCE_COMPILE` Task。
2. ArticleCard 生成与更新。
3. Concept 抽取、归一化、合并。
4. ConceptAlias、ConceptRelation、ArticleConceptRelation。
5. Card Evidence/Citation 关联。
6. Card 搜索、详情、证据查看。
7. 编译失败和 JSON 解析失败重试。

验收：

- Source 无 raw text 时不能 compile。
- ArticleCard、ConceptCard 能回溯原文证据。
- 同项目内同名或近似概念不会大量重复。
- Card 搜索可用。

### Phase 8: Artifact 基础与 Studio 生成

目标：把结构化产物从 ChatMessage 中独立出来，但不做暂缓 Quiz。

必须完成：

1. Artifact、ArtifactVersion、ArtifactSource、ArtifactCitation、SessionArtifact。
2. Artifact 查看、编辑、归档、Markdown 导出。
3. Studio Task 创建、取消、重试、进度查询。
4. 固定 Plan 与 Skill Registry 初版。
5. SkillExecutionLog 脱敏记录。
6. 支持类型：REPORT、STUDY_GUIDE、BRIEFING、FAQ、COMPARISON、WIKI_DRAFT。
7. 不实现 Quiz、答题、评分、题库。

验收：

- Artifact 不只存在 ChatMessage 里。
- 编辑 Artifact 产生新版本或可追踪修订。
- Artifact 可查看来源和引用。
- Quiz 不进入当前验收。

### Phase 9: 增强检索、Embedding、Weighted RRF

目标：在 BM25 稳定后增加向量召回和融合排序。

必须完成：

1. EmbeddingClient、Embedding 模型配置。
2. `EMBEDDING_BACKFILL` Task。
3. ES index alias 和向量维度版本绑定。
4. BM25Retriever、VectorRetriever、WikiRetriever 接口。
5. Weighted RRF。
6. EvidencePostProcessor 增强：相邻 Chunk 合并、低分过滤、同文档限流。
7. RRF trace 进入 RetrievalTrace。

验收：

- 向量失败可降级 BM25。
- 历史 Chunk 可通过 backfill 获得 embedding。
- 权限 filter 始终存在。

### Phase 10: 团队 Wiki Draft、发布、版本与入索引

目标：让高价值团队内容人工确认后沉淀为 Wiki。

必须完成：

1. WikiPage、WikiPageVersion。
2. 手动创建/编辑草稿。
3. ArtifactVersion 发布为 Wiki 草稿。
4. ChatMessage/Citation 一键生成 Wiki 草稿。
5. OWNER 发布，MVP 不做复杂审批流。
6. `WIKI_INDEX` Task 异步入索引。
7. Wiki 搜索、版本列表、版本详情。

验收：

- 只有 PUBLISHED 且已索引的 Wiki 进入 RAG。
- 发布失败可重试，不影响草稿。
- Artifact/Wiki/Message 来源可追踪。

### Phase 10.5: MethodologyCard 预置模板与匹配器

目标：在个人生成前提供稳定输出框架。

必须完成：

1. 预置 MethodologyCard seed。
2. MethodologyMatcher 初版。
3. Prompt 注入 workflow、outputStructure、qualityChecklist。
4. 不做模板市场和复杂自动抽取。

说明：

- 这是从 Phase 13 前移出的最小切片。
- 用户自定义和高级管理仍留在 Phase 13。

### Phase 11: 个人 Wiki-based Generation

目标：基于 ResearchProject、ArticleCard、ConceptCard、MethodologyCard 生成 Artifact。

必须完成：

1. PersonalGenerationService。
2. ResearchContextService。
3. PersonalEvidenceService。
4. Report、StudyGuide、Comparison、WorkPrep。
5. Artifact 关联 ResearchProject、Source/Card、Citation、MethodologyCard。
6. Owner-only 权限。

验收：

- 不全量塞入 Source 原文，只按需回溯证据。
- 生成结果保存为 ArtifactVersion。
- 其他用户不能访问个人生成结果。

### Phase 12: 长期 Memory 深化

目标：补齐会话摘要、用户偏好和工作台长期上下文。

必须完成：

1. SessionSummary。
2. UserMemory。
3. User-Space 私有 SpaceMemory 或明确命名的 WorkspaceMemory。
4. Memory item 支持多主题、过期、置信度、来源和删除。
5. Memory 查看、编辑、删除、禁用写入。
6. MemoryWritebackStrategy，敏感信息过滤。
7. DRAFT 永不写长期 Memory。

验收：

- 用户知道哪些 Memory 会影响后续回答。
- Memory 不会泄露给无权限用户。
- 低置信度内容不会覆盖稳定偏好。

### Phase 13: MethodologyCard 完整管理

目标：补齐方法论卡片的用户自定义、编辑和高级匹配。

必须完成：

1. 用户创建/编辑/归档 MethodologyCard。
2. 模板版本、状态、createdBy、scope。
3. 项目内、个人 Space、系统预置三级匹配。
4. 生成任务可选择或自动匹配 MethodologyCard。

验收：

- 预置模板可读。
- 用户模板 owner-only。
- Prompt 中可确认使用了匹配到的方法论。

### Phase 14: 评测与可观测性增强

目标：在前置最小日志基础上补齐管理、指标和 RAG Eval。

必须完成：

1. PromptVersion 管理。
2. LLMCallLog 查询。
3. RetrievalTrace 查询和明细。
4. AnswerFeedback 管理查询。
5. RagEvalCase、RagEvalRun、RagEvalResult。
6. Eval Run 隔离执行，不写正式会话和 Memory。
7. Prompt/Trace 脱敏、保留期和访问边界。

验收：

- 可以反查一次回答的 Prompt 版本、检索证据、Citation 和 token/latency。
- Eval 输出 recall@k、MRR、citationCoverage、latency、错误。
- 非管理员不能看敏感日志。

### Phase 15: 管理后台与运维能力

目标：补齐上线后的管理、清理、健康和审计。

必须完成：

1. Admin Guard 基于 `User.systemRole=ADMIN`。
2. 用户、Space、任务、Document、Artifact 基础查询。
3. 任务重试、取消、mark failed。
4. Resource cleanup scan/execute。
5. `ops_cleanup_job` 和 `ops_cleanup_item`。
6. AuditLog。
7. MySQL、Redis、MinIO、Kafka、ES、LLM Provider 健康检查。
8. Dashboard summary。

验收：

- 普通用户不能访问 `/api/v1/admin/**`。
- 清理默认先 scan，不直接删除。
- 重试和清理写 AuditLog。
- 健康详情不向无权限用户泄露内部配置。

### Phase 16: 前端工作台总集成

目标：整合前面阶段已经交付的前端薄片，形成完整工作台。

说明：

- Phase 16 不再是从零开始写全部前端。
- 每个后端阶段完成后都应同步交付最小前端薄片。
- Phase 16 只做导航整合、状态管理统一、体验打磨、异常页和端到端验收。

总集成必须覆盖：

1. 登录/注册/刷新/退出。
2. Space 切换与成员角色展示。
3. 团队知识库上传、处理状态、文档列表。
4. 团队 Chat 流式输出、停止、Citation drawer、反馈。
5. 个人 ResearchProject、Source、Card、生成。
6. Studio、Artifact 预览、编辑、导出。
7. Wiki 草稿、发布、搜索、版本。
8. Memory 查看和编辑。
9. Admin 任务、健康、日志、Eval。

---

## 6. 前端薄片交付计划

| 前端薄片 | 对应后端阶段 | 目标 |
|---|---|---|
| F1 Auth/Space | Phase 0/1 | 登录、注册、刷新、退出、Space 列表、成员查看。 |
| F2 KB Upload | Phase 2/3 | 知识库、分片上传、取消、处理状态、文档列表、检索测试。 |
| F3 Team RAG | Phase 4 | HTTP 问答、Citation 展示、反馈、Trace 调试入口。 |
| F4 WebSocket Runtime | Phase 5 | 流式输出、停止、恢复、DRAFT 会话。 |
| F5 Personal Research | Phase 6/7 | 项目、Source 导入、Card 列表、Card 搜索、证据查看。 |
| F6 Studio/Artifact | Phase 8/11 | Studio 任务、Artifact 预览、编辑、导出、个人生成。 |
| F7 Retrieval/Wiki | Phase 9/10 | Hybrid 搜索调试、Wiki 草稿、发布、搜索、版本。 |
| F8 Memory/Admin/Ops | Phase 12/14/15 | Memory 管理、任务/健康/日志/Eval/Admin。 |
| F9 Workbench Integration | Phase 16 | 统一导航、状态、异常处理和端到端验收。 |

---

## 7. 推荐实际开发顺序

```text
0. 工程骨架、迁移、统一响应、错误码、分页、OpenAPI
1. Auth/User/RefreshToken/Space/SpaceMember/SystemRole/Permission
2. Task + TaskAttempt + TaskEvent + TaskOutbox + Worker
3. FileObject + MinIO + KnowledgeBase
4. DocumentUpload 分片/续传/秒传/cancel/merge
5. Kafka/Worker 投递与补偿
6. Document parse + parsed text + Chunk
7. ES BM25 + indexVersion + Search Debug
8. Team Chat HTTP RAG + Citation + Trace + Feedback
9. WebSocket Runtime + DRAFT lifecycle
10. ResearchProject + Source import + raw text
11. ArticleCard + ConceptCard + Evidence
12. ArtifactVersion + Studio Task + Skill Plan
13. Embedding backfill + VectorRetriever + Weighted RRF
14. Team Wiki Draft/Publish/Index/Search
15. Methodology preset + Personal Generation
16. Memory deepen
17. Methodology full management
18. RAG Eval + Observability admin
19. Admin/Ops full console
20. Frontend final integration
```

---

## 8. 第一批建议修改的文档

进入编码前建议按优先级修正：

| 优先级 | 文档 | 必须修正 |
|---|---|---|
| P0 | `database_api_blueprint.md` | Auth refresh/logout、SystemRole、Task/Outbox、FileObject、soft delete、Phase 14/15 表、移出 Quiz。 |
| P0 | `phase_0_1_bootstrap_auth_space.md` | refresh/logout/user profile/systemRole/Flyway/非 dev 密钥要求。 |
| P0 | `phase_2_file_upload_async_ingestion.md` | Task 前置、Outbox、FileObject/refCount、upload cancel、过期清理、对象 key。 |
| P0 | `file_upload_async_pipeline.md` | `/api/v1`、uploadId key、FileObject、cancel、补偿投递。 |
| P1 | `workspace_chat_runtime_memory.md` | `/api/v1`、WS 事件名、DRAFT 生命周期、移除 `citation_ids` JSON。 |
| P1 | `phase_6_personal_research_source.md` | Source 必须产出 raw/parsed text，URL 失败重试，去重。 |
| P1 | `phase_7_personal_wiki_compiler_cards.md` | Evidence/Citation 关联、Card 搜索、ConceptRelation。 |
| P1 | `phase_8_studio_artifact_generation.md` | 移出 Quiz，增加 ArtifactVersion，Skill 日志脱敏。 |
| P1 | `phase_16_frontend_workspace.md` | 改为前端薄片计划，统一 API 和 WS 协议。 |

---

## 9. 当前暂缓功能

以下功能不进入当前阶段开发，也不应进入当前验收清单：

- Quiz、测验生成、答题、评分、题库、错题复习。
- 外部研究资料自动发现、论文搜索、搜索 API 推荐资料。
- 商业化计费、套餐、额度售卖。
- 企业级复杂审批流。
- 复杂多人实时协同编辑。
- 完整自主规划 Agent。
- 多模型管理后台和复杂 A/B 实验平台。

文档中如需保留这些内容，必须放入 Future Backlog，并明确标注 `DEFERRED`。

---

## 10. 是否可以开始编码

当前结论：需要先小修文档，然后可以开始 Phase 0/1 编码。

开始编码前的最小修正文档清单：

1. `database_api_blueprint.md`
2. `phase_0_1_bootstrap_auth_space.md`
3. `phase_2_file_upload_async_ingestion.md`
4. `file_upload_async_pipeline.md`
5. `docs/features/README.md`

只要这些文档完成 P0 修正，就可以进入 Phase 0/1；不要在旧计划状态下让 AI 从 Phase 0/1 连续执行到 Phase 16。
