# Phase 16: 前端工作台

本文档用于指导 NoteWeave 第十六阶段编码实现。

范围：

```text
Phase 16: Workspace UI / Team Knowledge / Personal Research / Chat Runtime / Studio / Artifact / Admin Console
```

第十六阶段目标是把前面各阶段已经交付的前端薄片组织成一个可使用的前端工作台。首屏应是实际工作界面，而不是产品介绍页；本阶段不再从零一次性实现全部页面。

---

## 1. 参考文档

```text
docs/features/phase_0_1_bootstrap_auth_space.md
docs/features/phase_2_file_upload_async_ingestion.md
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_5_workspace_chat_runtime.md
docs/features/phase_6_personal_research_source.md
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_10_team_wiki_publish_index.md
docs/features/phase_11_personal_generation.md
docs/features/phase_14_evaluation_observability.md
docs/features/phase_15_admin_ops.md
docs/features/database_api_blueprint.md
```

---

## 2. 阶段目标

- 提供登录、注册、当前用户信息和空间切换界面。
- 提供团队知识库上传、处理状态、文档列表和检索问答界面。
- 提供个人 ResearchProject、Source、ArticleCard、ConceptCard、SynthesisCard、个人生成界面。
- 提供工作台 Chat + Studio + Artifact 的核心交互。
- 支持 WebSocket 流式输出、中断停止、会话切换、草稿会话恢复。
- 支持 Citation 展示和证据定位。
- 支持 Artifact 预览、编辑、导出。
- 提供基础管理后台：任务、健康检查、日志、RAG Eval。

---

## 3. 本阶段不做的事

- 不做营销落地页。
- 不做复杂主题市场。
- 不做移动端原生 App。
- 不做多人实时协同编辑。
- 不做复杂富文本排版器。
- 不做 Quiz、测验、答题记录等暂缓功能。
- 不做外部研究资料自动发现。

---

## 4. 前端信息架构

建议主导航：

```text
Workspace
  Team Knowledge
  Personal Research
  Chat
  Studio
  Artifacts
  Wiki
  Memory
Admin
```

页面层级：

```text
/login
/register
/spaces
/spaces/:spaceId/team/knowledge-bases
/spaces/:spaceId/team/knowledge-bases/:knowledgeBaseId
/spaces/:spaceId/team/chat
/spaces/:spaceId/personal/projects
/spaces/:spaceId/personal/projects/:projectId
/spaces/:spaceId/personal/projects/:projectId/sources
/spaces/:spaceId/personal/projects/:projectId/cards
/spaces/:spaceId/personal/projects/:projectId/generate
/spaces/:spaceId/workbench/chat
/spaces/:spaceId/workbench/studio
/spaces/:spaceId/artifacts/:artifactId
/spaces/:spaceId/wiki
/spaces/:spaceId/memory
/admin/tasks
/admin/health
/admin/evaluation
/admin/logs
```

---

## 5. 全局布局

### AppShell

结构：

```text
左侧主导航
顶部空间切换与用户菜单
中间内容区域
右侧可选详情抽屉
```

要求：

- 整体是工作型工具界面，信息密度适中，避免营销式大 Hero。
- 导航、列表、工具栏尺寸稳定，不因内容刷新跳动。
- 图标按钮优先使用现有 icon 库，按钮含义不清时提供 tooltip。
- 不在卡片里再嵌套卡片。
- 列表页优先使用表格、分栏、筛选器、状态标签。

### 全局状态

```text
currentUser
currentSpace
spaceMembers
authToken
websocketStatus
activeSession
activeProject
notificationQueue
```

---

## 6. 核心页面设计

### 登录与注册

能力：

```text
登录
注册
刷新 token
退出登录
```

接口：

```http
POST /api/v1/auth/register
POST /api/v1/auth/login
GET  /api/v1/users/me
```

### Space 选择页

能力：

```text
查看加入的 Space
创建 Space
进入 Space
查看成员角色
```

接口：

```http
GET  /api/v1/spaces
POST /api/v1/spaces
GET  /api/v1/spaces/{spaceId}/members
```

---

## 7. 团队知识库界面

### KnowledgeBaseList

功能：

```text
知识库列表
创建知识库
查看文档数量
查看最近处理状态
进入知识库详情
```

### KnowledgeBaseDetail

分区：

```text
文档列表
上传入口
处理任务状态
检索测试入口
```

上传交互：

```text
选择文件
计算 MD5
查询是否秒传
分片上传
显示分片进度
支持暂停和继续
完成后显示异步处理状态
```

接口：

```http
GET  /api/v1/team/spaces/{spaceId}/knowledge-bases
POST /api/v1/team/spaces/{spaceId}/knowledge-bases
POST /api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init
POST /api/v1/team/document-uploads/{uploadId}/chunks
POST /api/v1/team/document-uploads/{uploadId}/merge
POST /api/v1/team/document-uploads/{uploadId}/cancel
GET  /api/v1/tasks/{taskId}
```

---

## 8. Chat 工作台界面

### WorkbenchChat

布局：

```text
左侧会话列表
中间消息流
底部输入区
右侧 Citation / Context / Artifact 抽屉
```

能力：

```text
创建正式会话
创建临时草稿
切换会话
流式生成
中断停止
上下文恢复
查看引用证据
提交反馈
```

WebSocket 事件：

```text
chat.delta
chat.completed
chat.failed
chat.stopped
chat.restored
session.state.updated
```

接口：

```http
GET  /api/v1/spaces/{spaceId}/chat-sessions
POST /api/v1/chat/sessions
GET  /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/messages/{messageId}/feedback
```

交互要求：

- 流式输出时输入区保留停止按钮。
- 停止后保留已经生成的部分内容，并标记为 stopped。
- 切换会话时保留草稿输入。
- Citation 点击后在右侧抽屉展示来源片段、文档名、页码或位置。

---

## 9. 个人研究界面

### ResearchProjectList

功能：

```text
项目列表
创建项目
归档项目
查看 Source / Card / Artifact 数量
```

### ResearchProjectDetail

Tabs：

```text
Sources
Cards
Generate
Artifacts
Methodology
```

Sources：

```text
上传文件 Source
粘贴 URL Source
粘贴文本 Source
查看导入任务状态
重新导入失败 Source
```

Cards：

```text
ArticleCard 列表
ConceptCard 列表
按概念搜索
查看证据来源
触发 Wiki Compiler
```

Generate：

```text
选择生成类型
选择 MethodologyCard
选择 Source/Card 范围
生成 Artifact
查看任务状态
```

接口：

```http
GET  /api/v1/personal/research-projects
POST /api/v1/personal/research-projects
GET  /api/v1/personal/research-projects/{projectId}/sources
POST /api/v1/personal/research-projects/{projectId}/sources/upload
POST /api/v1/personal/research-projects/{projectId}/sources/url
POST /api/v1/personal/research-projects/{projectId}/sources/text
GET  /api/v1/personal/research-projects/{projectId}/article-cards
GET  /api/v1/personal/research-projects/{projectId}/concept-cards
POST /api/v1/studio/tasks
GET  /api/v1/personal/research-projects/{projectId}/methodology-cards
```

---

## 10. Studio 与 Artifact 界面

### Studio

功能：

```text
选择 Studio Skill
填写必要参数
选择上下文范围
查看生成计划
启动生成任务
查看进度
打开 Artifact
```

Skill 示例：

```text
Study Guide
Research Report
Comparison Analysis
Work Prep
Technical Proposal
```

### ArtifactViewer

能力：

```text
Markdown 预览
结构化内容预览
简单编辑
版本查看
导出 Markdown
导出 HTML 或 PDF 的预留入口
关联 Citation 查看
沉淀到个人 Wiki
查看关联 Wiki Card
```

接口：

```http
GET  /api/v1/studio/skills
POST /api/v1/studio/tasks
GET  /api/v1/artifacts/{artifactId}
PUT  /api/v1/artifacts/{artifactId}
POST /api/v1/artifacts/{artifactId}/distill-to-personal-wiki
GET  /api/v1/artifacts/{artifactId}/card-relations
GET  /api/v1/artifacts/{artifactId}/export?format=markdown
```

个人 Artifact 沉淀弹窗：

```text
选择沉淀方式：Synthesis / Concept / Methodology
预览提炼结果
查看引用证据
确认写入
```

MVP 只开放 Synthesis；Concept / Methodology 先作为禁用选项或隐藏，等 merge proposal 能力完成后再上线。

---

## 11. Wiki 与 Memory 界面

### TeamWiki

功能：

```text
查看 Wiki 页面列表
创建草稿
编辑草稿
发布 Wiki
查看版本
搜索 Wiki
```

### MemoryPage

功能：

```text
查看 SpaceMemory
编辑 SpaceMemory
查看 UserMemory
编辑 UserMemory
查看 SessionSummary
查看 SynthesisCard
```

要求：

- Memory 编辑需要明确保存动作。
- 用户能看到哪些内容会影响后续回答。
- DRAFT 会话不展示写入长期 Memory 的提示。
- 个人 Artifact 不自动写入 Wiki，只有用户点击并确认后才展示新增 SynthesisCard。

---

## 12. 管理后台界面

### AdminTasks

```text
任务列表
状态筛选
任务详情
失败原因
重试
取消
```

### AdminHealth

```text
组件健康状态
最近检查时间
错误详情
手动刷新
```

### AdminEvaluation

```text
RagEvalCase 列表
创建 Eval Case
启动 Eval Run
查看 Eval Result
查看 recall@k / MRR / citationCoverage
```

### AdminLogs

```text
LLMCallLog 查询
RetrievalTrace 查看
AuditLog 查询
```

---

## 13. 前端 API 层

建议目录：

```text
src/api/auth.ts
src/api/spaces.ts
src/api/knowledgeBases.ts
src/api/documentUploads.ts
src/api/tasks.ts
src/api/chat.ts
src/api/personalResearch.ts
src/api/studio.ts
src/api/artifacts.ts
src/api/wiki.ts
src/api/memory.ts
src/api/admin.ts
```

统一要求：

- 所有请求从统一 HTTP client 发出。
- 自动附加 Authorization Header。
- 401 统一跳转登录或刷新 token。
- API 错误统一转成可展示错误对象。
- 长任务统一轮询 `task` 或订阅 WebSocket 状态。
- 列表接口统一读取 `ApiResponse<PageResponse<T>>`。
- 每个页面上线前必须完成 API 对齐检查，不允许只接 mock 数据。

API 对齐矩阵：

| 页面 | 必须对齐的 API |
|---|---|
| Workspace / Space | auth、users、spaces、members |
| Team Knowledge | knowledgeBases、documentUploads、documents、tasks |
| Team Chat | chat session、WebSocket ticket、citation、feedback |
| Team Wiki | wiki draft、publish、versions、publish artifact to wiki |
| Personal Research | researchProjects、sources、articleCards、conceptCards、methodologyCards、synthesisCards |
| Studio / Artifact | studio tasks、artifacts、artifact versions、artifact sources、artifact card relations、personal wiki distillation |
| Memory | sessionSummary、spaceMemory、userMemory、memoryItems |
| Admin | users、spaces、tasks、cleanup、health、LLMCallLog、RetrievalTrace、AuditLog |

---

## 14. 组件建议

```text
AppShell
SpaceSwitcher
UserMenu
TaskStatusBadge
UploadProgressPanel
CitationDrawer
EvidenceSnippet
ChatMessageList
ChatInput
SessionList
ArtifactViewer
ArtifactEditor
SourceList
CardList
MethodologyPicker
StudioSkillPicker
HealthStatusTable
EvalRunResultTable
```

---

## 15. 状态与异常处理

必须覆盖：

```text
loading
empty
error
permission denied
upload paused
upload failed
task pending
task running
task failed
task retrying
websocket disconnected
stream stopped
artifact generating
```

交互原则：

- 长耗时操作必须展示进度或状态。
- 失败任务要能看到原因和下一步动作。
- WebSocket 断开要展示重连状态。
- 上传和生成不应阻塞用户浏览已有内容。

---

## 16. 验收清单

- 用户能登录并进入 Space。
- 用户能创建知识库并上传文件。
- 上传进度、异步任务状态能正确展示。
- 团队 Chat 支持流式输出、停止、Citation 查看。
- 用户能创建个人 ResearchProject 并导入 Source。
- 用户能查看 ArticleCard / ConceptCard。
- 用户能查看 SynthesisCard。
- 用户能从 Studio 或个人生成入口创建 Artifact。
- 用户能预览和编辑 Artifact。
- 管理员能查看任务、健康状态、Eval Run 和日志。
- 前端所有业务接口使用 `/api/v1`。
- Workspace/Team/Personal/Chat/Studio/Artifact/Wiki/Memory/Admin 页面至少各有一条真实 API 联调用例。

---

## 17. 给 AI 执行第十六阶段的边界提醒

- 不要做营销首页，首屏就是工作台。
- 不要把所有功能堆成一个页面，要按导航和任务流拆分。
- 不要做复杂富文本编辑器，先支持 Markdown/结构化预览和基础编辑。
- 不要实现暂缓的 Quiz 或外部资料发现。
- 不要引入与后端文档不一致的 API 前缀。
- 所有 API 必须使用 `/api/v1`。




