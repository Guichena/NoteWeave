# Phase 16 Prompt: Frontend Workspace

你是 NoteWeave 项目的编码代理。请执行 Phase 16：前端工作台整合。

## 测试驱动执行规则

本阶段必须采用测试驱动开发：

```text
1. 先根据本阶段目标和验收标准写测试
2. 运行测试，确认关键测试失败且失败原因符合预期
3. 再写最小实现让测试通过
4. 重构和补齐边界处理
5. 最后运行当前阶段相关测试和必要回归测试
```

## Docker 中间件执行规则

本阶段涉及的所有中间件必须通过 Docker Compose 或 Testcontainers 提供：

```text
MySQL
Redis
MinIO
Elasticsearch
Kafka
```

要求：

- 不允许依赖本机散装安装的中间件。
- 本地开发中间件统一维护在根目录 `docker-compose.yml`。
- 集成测试中间件统一使用 Testcontainers。
- 当前 Phase 新增中间件、bucket、topic、index 或测试路径时，必须同步更新 `docs/DOCKER_MIDDLEWARE.md`。
- 测试临时路径统一使用 `target/noteweave-test/{phase}/`，不能写用户机器绝对路径。

## Subagent 分工模板

本节描述 AI 编码代理执行本 Phase 时允许使用的 subagent 协作方式。`subagent` 只表示编码执行分工，不是 NoteWeave 产品运行态 Agent/Skill 设计。

通用规则：

- 主代理是本 Phase 的 owner，负责最终集成、测试、文档更新和交付结论。
- subagent 必须先按本文档的“必读文档”顺序读取上下文，再开始自己的子任务。
- 每个 subagent 必须有明确 ownership，限定可修改的模块、文件或测试范围。
- 不允许多个 subagent 同时修改同一文件或同一模块；需要交叉修改时由主代理统一合并。
- 不允许 subagent 扩大当前 Phase 范围，遇到范围外问题只记录为遗留风险。
- 所有实现仍必须遵守 TDD：先写失败测试，再写最小实现，再重构和回归。
- subagent 产出必须由主代理 review 后合入，主代理不能直接信任未验证结果。

推荐分工：

```text
lead-agent:
- 读取全部必读文档，拆分任务，维护 Phase 边界和视觉/交互一致性。
- 负责最终集成、运行前端验证、更新 PROJECT_STATUS。

test-agent:
- 先写核心路由、API client、权限错误、WebSocket 恢复和端到端薄片测试。
- 覆盖登录、Space、上传、Chat、Studio、Wiki、Memory、Admin 主流程。

app-shell-agent:
- 负责全局布局、导航、认证态、Space 切换和错误页。
- 不实现业务页面深层逻辑。

api-client-agent:
- 负责 `/api/v1` client、统一分页、错误处理、token refresh。
- 不暴露 Quiz 路由或入口。

team-workspace-agent:
- 负责团队 KnowledgeBase、上传、文档列表、Team RAG Chat、Citation drawer。
- 保持权限和处理状态展示清晰。

personal-workspace-agent:
- 负责 ResearchProject、Source、ArticleCard / ConceptCard / SynthesisCard 页面。
- 个人数据 owner-only。

studio-artifact-agent:
- 负责 Studio、Artifact Viewer、编辑、导出、重新生成、沉淀/发布入口展示。
- Artifact 默认不自动进入 Wiki。

wiki-memory-admin-agent:
- 负责 Wiki、Memory、Task Center、Admin 基础页整合。
- 不实现商业化或复杂协同编辑。

visual-review-agent:
- 只做 review，不直接改代码。
- 重点检查桌面/移动端可用性、状态反馈、空态、错误态和交互一致性。
```
## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_16_frontend_workspace.md
```

还要按已实现能力读取对应 Phase 文档，至少包括：

```text
docs/features/phase_0_1_bootstrap_auth_space.md
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_2_file_upload_async_ingestion.md
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_5_workspace_chat_runtime.md
docs/features/phase_6_personal_research_source.md
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_10_team_wiki_publish_index.md
docs/features/phase_11_personal_generation.md
```

## 目标

实现前端工作台整合：

```text
Auth
Space 切换
团队 KnowledgeBase
上传
Chat
WebSocket Runtime
个人 ResearchProject
Source
ArticleCard / ConceptCard / SynthesisCard
Studio
Artifact Viewer
Wiki
Task Center
Admin 基础页
```

## 严格边界

不要实现：

```text
Quiz
外部资料自动发现
商业化计费
复杂多人实时协作编辑
```

## 必须遵守

- 所有请求使用 `/api/v1`。
- 不要暴露 Quiz 路由或入口。
- 个人 Artifact 不自动写入 Wiki，只能用户确认后沉淀为 SynthesisCard。
- 团队 Artifact 发布 WikiPage 需要人工确认。
- 前端每个列表使用统一分页响应。
- WebSocket 要处理断线、恢复、停止和事件顺序。

## 交付要求

实现代码和前端验证。并确保以下内容已落实：

```text
完成哪些页面
接入哪些 API
如何处理权限和错误状态
如何验证核心流程
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```
