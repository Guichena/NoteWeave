# NoteWeave Implementation Breakdown

本文档用于把 `note_weave_功能说明与架构文档.md` 中的产品与架构设计拆成可执行开发步骤。

核心原则：

- 先跑通一条最小闭环，再扩展个人 Wiki 和 Studio。
- 先稳定领域模型和任务状态机，再接入复杂 AI 能力。
- 团队侧优先解决上传、索引、检索、问答、引用回溯。
- 个人侧优先解决 ResearchProject、Source、ArticleCard、ConceptCard。
- Artifact 与 Wiki 严格分离，避免临时生成内容污染长期知识库。
- 文件上传只负责分片、落库、合并和任务投递，解析、向量化、索引构建全部异步执行。
- 会话执行底座独立于普通消息 CRUD，使用 WebSocket 承载流式生成、中断、恢复和运行态管理。

功能专题文档：

```text
docs/features/README.md
docs/features/file_upload_async_pipeline.md
docs/features/workspace_chat_runtime_memory.md
```

本文档保留总体阶段、依赖关系和开发顺序；具体功能细节以 `docs/features` 下的专题文档为准。

---

## 1. 最终领域模型收口

第一版统一使用以下核心实体名，不再同时使用多个同义概念。

### 1.1 基础实体

```text
User
Space
SpaceMember
```

约定：

- `Space` 是系统最高业务容器。
- `Space.type = PERSONAL | TEAM`。
- 每个用户注册后自动拥有一个个人 Space。
- 团队协作通过 `SpaceMember` 管理成员与角色。

### 1.2 团队侧实体

```text
KnowledgeBase
Document
DocumentChunk
WikiPage
WikiPageVersion
```

约定：

- `KnowledgeBase` 属于团队 Space。
- `Document` 属于 `KnowledgeBase`。
- `DocumentChunk` 是团队 RAG 的最小检索单位。
- `WikiPage` 只保存长期稳定知识，不保存普通报告、测验、学习指南。

### 1.3 个人侧实体

```text
ResearchProject
Source
ArticleCard
ConceptCard
ConceptAlias
ConceptRelation
ArticleConceptRelation
MethodologyCard
```

约定：

- `ResearchProject` 属于个人 Space。
- `Source` 属于 `ResearchProject`。
- 个人 Wiki 编译以 `ResearchProject` 为单位，不做全用户级全量编译。
- MVP 中 `ArticleCard` 和 `ConceptCard` 必做，`MethodologyCard` 可以延后。

### 1.4 通用实体

```text
ChatSession
ChatMessage
Artifact
Task
Citation
SkillExecutionLog
```

约定：

- 用 `Artifact` 统一表示报告、测验、学习指南、Briefing、WikiDraft 等生成产物。
- 不再使用 `generated_artifact` 作为代码实体名。
- 用统一 `Task` 表承载文档索引、资料编译、Artifact 生成等异步工作。
- `Citation` 记录答案、卡片、Artifact 与原文片段之间的证据关系。

---

## 2. PaiSmart-main 可参考实现

以下能力可以参考 `D:\java-projects\PaiSmart-main` 的已有实现，但 NoteWeave 中需要按新的领域模型重命名和收口。

详细设计已经拆分到：

```text
docs/features/file_upload_async_pipeline.md
docs/features/workspace_chat_runtime_memory.md
```

### 2.1 文件上传与异步处理参考

参考文件：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\controller\UploadController.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\UploadService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\consumer\FileProcessingConsumer.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\KafkaConfig.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\MinioConfig.java
```

可复用思路：

- 上传接口只处理分片上传、上传进度查询、分片合并。
- 使用 `fileMd5` 作为内容级身份标识，实现秒传、续传、去重。
- 使用 Redis Bitmap 记录分片状态，key 形如 `upload:{userId}:{fileMd5}`。
- 分片存储到 MinIO，路径形如 `chunks/{fileMd5}/{chunkIndex}`。
- 合并后对象存储路径使用 `merged/{fileMd5}`，避免同名不同内容互相覆盖。
- 合并成功后再投递 Kafka 文件处理任务。
- Kafka Consumer 执行解析、向量化、索引构建等长耗时流程。
- Consumer 执行前检查文件生命周期，文件已删除则跳过后续处理。
- Kafka 配置需要包含事务发送、消费组、重试和 DLT。

NoteWeave 中建议映射：

```text
FileUpload        -> DocumentUpload / UploadSession
ChunkInfo         -> UploadChunk
FileProcessingTask -> Task(type = DOCUMENT_PROCESS)
ParseService      -> DocumentParserService
VectorizationService -> EmbeddingIndexService
DocumentService   -> DocumentLifecycleService
```

### 2.2 WebSocket 会话执行底座参考

参考文件：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\handler\ChatWebSocketHandler.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\config\WebSocketConfig.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\StudentStudySessionRuntimeService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\ConversationStateStore.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\MemoryReadRouter.java
```

可复用思路：

- WebSocket 握手时做认证，把用户身份放入 session attributes。
- 消息协议区分普通提问、停止生成、创建新会话、临时草稿会话。
- 正式会话和临时草稿会话分开建模。
- Redis 保存运行态、短期上下文和流式状态。
- MySQL 保存会话归档、消息、长期工作台记忆和用户个性化记忆。
- 停止生成时通过 `ActiveExecution` 或等价结构设置 `stopRequested`，并取消底层流式请求。
- 每轮生成前按需装配上下文，不全量回放历史消息。
- Memory 读取由 `MemoryReadRouter` 决策，临时草稿默认不读长期记忆。

NoteWeave 中建议映射：

```text
StudySession       -> ChatSession
StudyMessage       -> ChatMessage
WorkspaceMemory    -> SpaceMemory
UserProfileMemory  -> UserMemory
SessionSummaryArchive -> SessionSummary
StudentStudySessionRuntimeService -> ChatRuntimeService
MemoryReadRouter   -> ContextReadRouter
```

---

## 3. 第一条最小闭环

第一阶段实现团队侧基础 RAG 闭环，但文件处理链路从一开始就按异步架构设计。

```text
User/Auth
  ↓
Space/SpaceMember
  ↓
KnowledgeBase
  ↓
Document 分片上传 / 断点续传
  ↓
MinIO 合并
  ↓
Kafka 投递 DOCUMENT_PROCESS 任务
  ↓
Document 解析
  ↓
DocumentChunk 切片
  ↓
Embedding / Vector 可先留接口
  ↓
BM25 检索
  ↓
RAG Answer
  ↓
Citation 回溯
```

第一阶段暂不实现：

- 个人 Wiki Compiler
- Studio 右侧产物区
- Artifact 编辑与导出
- VectorRetriever
- Weighted RRF
- Team Wiki Draft
- MethodologyCard
- 外部论文搜索
- 自主规划 Agent

---

## 4. 阶段拆分

### Phase 0: 工程骨架

目标：建立可持续开发的基础工程。

后端模块：

```text
com.noteweave.auth
com.noteweave.user
com.noteweave.space
com.noteweave.permission
com.noteweave.common
```

任务：

1. 初始化 Spring Boot 项目。
2. 接入 MySQL。
3. 接入 Redis。
4. 配置统一异常处理。
5. 配置统一响应结构。
6. 配置基础日志。
7. 配置数据库迁移工具。

验收标准：

- 服务能启动。
- 健康检查接口可访问。
- 能连接数据库和 Redis。
- 有统一错误响应。

---

### Phase 1: 用户、空间、权限

目标：先把所有业务资源的归属和权限边界定住。

涉及模块：

```text
auth
user
space
permission
```

核心表：

```text
user
space
space_member
```

任务：

1. 实现用户注册。
2. 实现用户登录。
3. 注册后自动创建个人 Space。
4. 实现团队 Space 创建。
5. 实现 Space 成员邀请或添加。
6. 实现 OWNER / EDITOR / VIEWER 三种角色。
7. 实现 `SpacePermissionService`。

关键权限方法：

```java
boolean canViewSpace(Long userId, Long spaceId);
boolean canManageSpace(Long userId, Long spaceId);
boolean canUploadDocument(Long userId, Long spaceId);
boolean canAskQuestion(Long userId, Long spaceId);
```

验收标准：

- 用户可以注册登录。
- 用户注册后拥有个人 Space。
- 用户可以创建团队 Space。
- OWNER 可以添加成员。
- 非成员不能访问团队 Space。

---

### Phase 2: 文件存储与文档上传

目标：让团队文档能通过分片上传进入系统，并保留 Raw Source。

涉及模块：

```text
storage
team.kb
team.document
task
kafka
```

核心表：

```text
knowledge_base
document
document_upload
upload_chunk
task
```

任务：

1. 实现文件存储接口 `FileStorageService`。
2. 接入 MinIO，保留 Raw Source。
3. 实现 KnowledgeBase 创建。
4. 实现分片上传初始化。
5. 实现分片上传接口。
6. 使用 Redis Bitmap 记录分片状态。
7. 分片写入 MinIO。
8. 使用 MD5 做内容级去重。
9. 实现上传进度查询。
10. 实现断点续传。
11. 实现分片合并。
12. 合并成功后创建 `Task`。
13. 合并成功后投递 Kafka。
14. Document 初始处理状态为 `PENDING_PROCESS`。

建议 Redis key：

```text
upload:{userId}:{fileMd5}
```

建议 MinIO object key：

```text
chunks/{fileMd5}/{chunkIndex}
merged/{fileMd5}
```

上传状态：

```text
INIT
UPLOADING
MERGED
PROCESSING
INDEXED
FAILED
DELETED
```

验收标准：

- EDITOR / OWNER 可以上传文档。
- VIEWER 不能上传文档。
- 上传后的原始文件可回溯。
- 上传后能看到文档处理状态。
- 已上传分片可以跳过。
- 中断后再次上传可以从缺失分片继续。
- 相同 MD5 的文件可以秒传或复用已有对象。
- 合并成功后只投递一次处理任务。

---

### Phase 3: Kafka 异步文档处理

目标：上传阶段快速返回，解析、切片、向量化和索引构建由 Kafka Consumer 异步执行。

涉及模块：

```text
team.document
search
task
kafka
embedding
```

核心表：

```text
document
document_chunk
task
```

任务：

1. 定义 Kafka topic：`document-process-topic`。
2. 定义 DLT：`document-process-dlt`。
3. 合并成功后发送 `DocumentProcessMessage`。
4. Consumer 读取 MinIO 合并对象。
5. Consumer 执行 PDF / Markdown / TXT 基础解析。
6. 实现简单 Chunk 切片。
7. Chunk 必须保存原文位置。
8. Chunk 必须保存 `space_id`、`knowledge_base_id`、`document_id`。
9. MVP 可先只写 ES BM25。
10. Embedding 和向量字段先留接口，后续开启。
11. 索引写入 ES。
12. 文档状态更新为 `INDEXED`。
13. 处理失败时记录错误并进入 DLT 或重试。

Kafka 消息建议字段：

```text
task_id
document_id
space_id
knowledge_base_id
file_md5
object_key
file_name
content_type
uploaded_by
created_at
```

Chunk 必备字段：

```text
id
space_id
knowledge_base_id
document_id
chunk_index
content
source_start
source_end
created_at
```

ES 索引必须包含：

```text
space_id
knowledge_base_id
document_id
chunk_id
content
```

验收标准：

- 上传文档能被解析成 Chunk。
- Chunk 能写入数据库。
- Chunk 能写入 ES。
- 文档处理失败时能记录错误信息。
- Consumer 重复消费时不会重复写入 Chunk。
- 文档被删除后 Consumer 不再继续索引。

---

### Phase 4: 团队基础 RAG 问答

目标：跑通第一个核心用户价值。

涉及模块：

```text
team.rag
chat
citation
permission
llm
search
```

核心表：

```text
chat_session
chat_message
citation
```

任务：

1. 创建 Team ChatSession。
2. 用户提问时校验 `canAskQuestion`。
3. ES 查询必须带权限过滤条件。
4. 从 BM25 检索 TopK Chunk。
5. 拼装 Evidence Context。
6. 调用 LLM 生成回答。
7. 保存 ChatMessage。
8. 保存 Citation。
9. 返回答案和引用来源。

权限要求：

```text
Controller 权限校验
  ↓
Search Filter 权限过滤
  ↓
Evidence 二次校验
  ↓
Citation 返回前校验
```

验收标准：

- 用户能在团队 Space 内提问。
- 答案基于上传文档生成。
- 答案带引用来源。
- 非成员无法问答。
- A 团队不能召回 B 团队文档。

---

### Phase 5: WebSocket 会话执行底座

目标：让工作台支持正式会话、临时草稿、流式生成、中断停止和上下文恢复。

涉及模块：

```text
chat
runtime
websocket
memory
redis
llm
```

核心表：

```text
chat_session
chat_message
session_summary
space_memory
user_memory
```

Redis 运行态：

```text
chat:{sessionId}:runtime
chat:{sessionId}:short_term
chat:{sessionId}:stream
```

会话类型：

```text
FORMAL
DRAFT
```

运行状态：

```text
IDLE
RUNNING
STOPPED
FAILED
```

任务：

1. 配置 WebSocket endpoint。
2. 握手阶段校验用户身份。
3. 定义前后端消息协议。
4. 支持创建正式会话。
5. 支持创建临时草稿会话。
6. 支持会话切换。
7. 支持流式返回 token / chunk。
8. 支持停止生成。
9. Redis 记录当前运行态。
10. Redis 记录短期上下文。
11. Redis 记录流式状态和部分输出。
12. MySQL 保存最终消息和会话归档。
13. 当前轮上下文按需加载近期消息、历史摘要、Space 记忆和用户偏好。

上下文装配规则：

```text
正式 Space 会话：
  近期消息
  + 相关历史摘要
  + SpaceMemory
  + UserMemory
  + 当前检索证据

临时草稿会话：
  近期消息
  + 当前检索证据
  不读取长期 SpaceMemory / UserMemory
```

验收标准：

- 前端能通过 WebSocket 建立连接。
- 用户能发起流式问答。
- 用户能中断正在生成的回答。
- 刷新后能恢复会话状态。
- 临时草稿不会写入长期记忆。
- 正式会话完成后能归档摘要。
- 长对话不会全量回放历史消息。

---

### Phase 6: 统一 Task 状态机

目标：让文档索引、资料编译、生成任务都能走统一异步模型。

涉及模块：

```text
task
```

核心表：

```text
task
```

统一状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
```

建议字段：

```text
id
task_type
target_type
target_id
space_id
created_by
status
idempotency_key
retry_count
max_retry_count
error_message
result_ref_type
result_ref_id
started_at
finished_at
created_at
updated_at
```

任务类型：

```text
DOCUMENT_INDEX
DOCUMENT_PROCESS
SOURCE_COMPILE
ARTIFACT_GENERATE
WIKI_PUBLISH
```

验收标准：

- 任务可以创建。
- Worker 可以消费任务。
- 任务成功、失败、重试、取消有明确状态。
- 同一请求可以通过 `idempotency_key` 防重复提交。

---

### Phase 7: 个人 ResearchProject 与 Source

目标：接入个人研究工作台，但先不做复杂知识图谱。

涉及模块：

```text
personal.project
personal.source
task
storage
```

核心表：

```text
research_project
source
task
```

任务：

1. 创建 ResearchProject。
2. 上传个人 Source。
3. 粘贴 URL / 文本导入 Source。
4. Source 进入解析任务。
5. Source 保留 Raw Source。

验收标准：

- 用户能创建研究项目。
- 用户能往项目里添加资料。
- 资料能解析并保留原文。
- Source 属于指定 ResearchProject。

---

### Phase 8: ArticleCard 与 ConceptCard

目标：实现个人 Wiki Compiler 的 MVP。

涉及模块：

```text
personal.compiler
personal.card
llm
citation
```

核心表：

```text
article_card
concept_card
concept_alias
article_concept_relation
citation
```

任务：

1. 基于 Source 生成 ArticleCard。
2. 从 ArticleCard 中抽取 Candidate Concept。
3. 做简单概念归一化。
4. 创建或合并 ConceptCard。
5. 建立 Article 与 Concept 关系。
6. Card 保存 Citation。

验收标准：

- Source 编译后能生成 ArticleCard。
- ArticleCard 能回溯原文。
- 能抽取基础 ConceptCard。
- 同名或近似概念不会大量重复。

---

### Phase 9: Studio 与 Artifact

目标：把报告、测验、学习指南等产物从聊天记录中独立出来。

涉及模块：

```text
studio
artifact
task
skill
llm
citation
```

核心表：

```text
artifact
artifact_source
artifact_citation
session_artifact
skill_execution_log
```

任务：

1. 实现 Studio Button 触发任务。
2. 根据 `artifact_type` 选择固定 Plan。
3. 执行 Skill。
4. 保存 Artifact。
5. Artifact 与来源 Source / Document / Citation 建立关系。
6. 支持 Artifact 查看。

Artifact 类型：

```text
REPORT
QUIZ
STUDY_GUIDE
BRIEFING
FAQ
WIKI_DRAFT
COMPARISON
```

验收标准：

- 用户可以从 Studio 生成报告。
- Artifact 不只存在聊天消息里。
- Artifact 能查看来源和引用。
- Artifact 可以关联产生它的 ChatSession。

---

### Phase 10: 增强检索与 Wiki

目标：在基础闭环稳定后提升质量。

涉及模块：

```text
team.rag
team.wiki
search
embedding
citation
```

任务：

1. 接入 Embedding。
2. 实现 VectorRetriever。
3. 实现 WikiRetriever。
4. 实现 Weighted RRF。
5. 实现 EvidencePostProcessor。
6. 实现 WikiDraft 发布为 WikiPage。
7. WikiPage 进入 RAG 索引。

验收标准：

- BM25、向量、Wiki 多路召回可配置。
- RRF 融合结果可观测。
- WikiDraft 需要人工确认才能发布。
- 发布后的 WikiPage 可以被后续问答召回。

---

## 5. 推荐开发顺序

实际开发建议按以下顺序执行：

```text
0. Spring Boot 工程骨架
1. User / Auth
2. Space / SpaceMember / Permission
3. Task 状态机基础版
4. MinIO FileStorage
5. KnowledgeBase
6. 分片上传 / 断点续传
7. Kafka 文档处理任务
8. Document 解析与 Chunk
9. Elasticsearch BM25
10. Team Chat + Citation
11. WebSocket 会话执行底座
12. 会话记忆与上下文装配
13. ResearchProject
14. Source 上传与解析
15. ArticleCard
16. ConceptCard
17. Artifact
18. Studio Button + 固定 Plan
19. VectorRetriever
20. Weighted RRF
21. Team Wiki
```

---

## 6. 第一阶段建议接口

### Auth

```http
POST /api/auth/register
POST /api/auth/login
GET  /api/users/me
```

### Space

```http
POST /api/spaces
GET  /api/spaces
GET  /api/spaces/{spaceId}
POST /api/spaces/{spaceId}/members
PUT  /api/spaces/{spaceId}/members/{memberId}/role
DELETE /api/spaces/{spaceId}/members/{memberId}
```

### KnowledgeBase

```http
POST /api/team/spaces/{spaceId}/knowledge-bases
GET  /api/team/spaces/{spaceId}/knowledge-bases
GET  /api/team/knowledge-bases/{knowledgeBaseId}
```

### Document

```http
POST /api/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init
POST /api/team/document-uploads/{uploadId}/chunks
GET  /api/team/document-uploads/{uploadId}/status
POST /api/team/document-uploads/{uploadId}/merge
GET  /api/team/knowledge-bases/{knowledgeBaseId}/documents
GET  /api/team/documents/{documentId}
GET  /api/team/documents/{documentId}/chunks
```

### Team Chat

```http
POST /api/team/spaces/{spaceId}/chat-sessions
GET  /api/team/spaces/{spaceId}/chat-sessions
POST /api/team/chat-sessions/{sessionId}/messages
GET  /api/team/chat-sessions/{sessionId}/messages
```

### Chat Runtime

```text
WebSocket /ws/chat/{ticket}
```

消息类型：

```text
chat.message
chat.stop
chat.switch_session
chat.resume
```

### Task

```http
GET  /api/tasks/{taskId}
POST /api/tasks/{taskId}/cancel
```

---

## 7. 第一阶段数据库建表顺序

```text
1. user
2. space
3. space_member
4. task
5. knowledge_base
6. document_upload
7. upload_chunk
8. document
9. document_chunk
10. chat_session
11. chat_message
12. citation
13. session_summary
```

---

## 8. 当前最该避免的事

- 不要一开始就做复杂 Agent 自主规划。
- 不要一开始就接多模型切换。
- 不要一开始就做复杂知识图谱可视化。
- 不要把 Report、Quiz、StudyGuide 默认发布成 Wiki。
- 不要在 ES 检索后才做权限过滤。
- 不要让 Citation 只存在 JSON 字段里。
- 不要让 Artifact 只挂在 ChatMessage 上。
- 不要同时维护 `artifact` 和 `generated_artifact` 两套概念。
- 不要在上传接口里同步解析、Embedding 和建索引。
- 不要把流式生成状态只放在内存 Map，至少要把可恢复状态写入 Redis。
- 不要对长会话全量回放历史消息。

---

## 9. 下一步执行建议

如果开始编码，下一步直接做：

```text
Phase 0 + Phase 1
```

也就是：

```text
Spring Boot 初始化
  ↓
User/Auth
  ↓
Space/SpaceMember
  ↓
SpacePermissionService
```

完成后再进入：

```text
Task 基础状态机
  ↓
MinIO / Kafka 基础配置
  ↓
KnowledgeBase
  ↓
Document 分片上传
  ↓
Kafka 异步处理
```
