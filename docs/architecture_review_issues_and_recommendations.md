# NoteWeave 架构问题与改进建议

## 1. 总体判断

- 当前设计覆盖了 NoteWeave 的大部分核心能力，但还不是完全可直接编码的稳定契约，最大问题是阶段顺序、API 路径、Task 状态机、权限模型和数据库蓝图存在多套口径。
- 不建议在未修正文档前直接进入大规模阶段编码；Phase 0/1 可以作为方向参考，但至少要先补齐认证刷新/退出、管理员角色、统一 API 前缀和迁移策略。
- 最大返工风险来自 `implementation_breakdown.md` 与 `README.md` 的 Phase 编号不一致，尤其是“统一 Task 状态机”在一个文档中是 Phase 6，在专题文档中却被 Phase 6 个人研究替代。
- 最大安全风险是 Admin API 要求 `ADMIN`，但用户/角色模型没有系统级管理员字段或表；Citation、Artifact、Memory、Source 的跨团队/个人隔离也没有形成统一资源权限模型。
- 最大可靠性风险是上传合并、数据库写入、Kafka 投递、异步处理之间没有事务外盒或补偿任务，容易出现 Document 已创建但消息丢失、Task 成功但 Document 仍未处理、对象存储残留等不一致。
- 最应该先修的是四份契约文档：`database_api_blueprint.md`、`implementation_breakdown.md`、`phase_0_1_bootstrap_auth_space.md`、`phase_2_file_upload_async_ingestion.md`。
- 设计中合理且应保留的部分包括：以 Space 作为最高业务容器、团队 KnowledgeBase 与个人 ResearchProject 分治、Artifact 与 Wiki 分离、上传与解析异步拆分、Citation 不放 JSON 而使用关联表、WebSocket 运行态写入 Redis。
- 暂缓功能在文档中仍有残留，尤其是 Quiz/测验和外部资料自动发现；它们不应作为当前缺失能力处理，但应从当前阶段和数据库/API 蓝图中移出。

## 2. 高优先级问题

| 编号 | 问题 | 影响 | 涉及文档 | 改进建议 |
|---|---|---|---|---|
| H-01 | Phase 编号和阶段范围不一致：`implementation_breakdown.md` 只拆到 Phase 10，且 Phase 6 是“统一 Task 状态机”；`README.md` 则有 Phase 0/1 到 Phase 16，Phase 6 是个人 ResearchProject。 | AI 按阶段执行时会漏做 Task 基础设施，或在不同阶段重复/错序实现同一能力。 | `implementation_breakdown.md`, `README.md`, `phase_2_file_upload_async_ingestion.md`, `phase_6_personal_research_source.md` | 以 `README.md` 的 Phase 0/1-16 为准重写 `implementation_breakdown.md`；明确 Task 基础设施放在 Phase 2 前置子步骤，或单独插入 Phase 1.5。 |
| H-02 | API 前缀和资源路径不统一：总文档、实现拆分、上传专题、会话专题仍使用 `/api`，专题阶段多使用 `/api/v1`；前端阶段又出现 `/api/v1/spaces/{spaceId}/knowledge-bases`、`/api/v1/uploads` 等与后端不一致的路径。 | 前后端和测试会按不同路径实现，导致大量接口返工。 | `note_weave_功能说明与架构文档.md`, `implementation_breakdown.md`, `file_upload_async_pipeline.md`, `workspace_chat_runtime_memory.md`, `phase_16_frontend_workspace.md`, `database_api_blueprint.md` | 所有文档统一使用 `/api/v1`；团队资源统一采用 `/api/v1/team/...`；上传统一采用 `document-uploads/{uploadId}/chunks` 和 `merge`；废弃旧路径。 |
| H-03 | 认证设计缺少刷新 token、退出登录、token 吊销、修改密码/个人资料接口；前端却要求“刷新 token、退出登录”。 | 直接影响登录态安全和前端落地，后续补会改 AuthResponse、表结构和拦截器。 | `phase_0_1_bootstrap_auth_space.md`, `database_api_blueprint.md`, `phase_16_frontend_workspace.md` | 增加 refresh token 表或会话表，接口至少包括 `POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout`、`PUT /api/v1/users/me`、`PUT /api/v1/users/me/password`；明确 access token 短期、refresh token 可吊销。 |
| H-04 | Admin API 依赖 `ADMIN` 角色，但用户、SpaceMember、权限服务都只有 OWNER/EDITOR/VIEWER，没有系统级角色模型。 | `/api/v1/admin/**` 无法安全鉴权，可能被错误复用 Space OWNER 权限导致越权。 | `phase_0_1_bootstrap_auth_space.md`, `phase_15_admin_ops.md`, `database_api_blueprint.md` | 在 Phase 0/1 增加 `users.system_role` 或 `user_system_role` 表，定义 `USER/ADMIN`；Admin 权限与 Space 权限分离。 |
| H-05 | Task 状态机不统一：有 `SUCCESS`、`SUCCEEDED`、`TIMEOUT` 多套状态；Task 类型也有 `DOCUMENT_PROCESS`、`DOCUMENT_PARSE`、`DOCUMENT_INDEX`、`STUDIO_GENERATION`、`PERSONAL_GENERATION` 等多套命名。 | Worker、Admin 重试、前端状态展示和数据库索引会互相不兼容。 | `implementation_breakdown.md`, `database_api_blueprint.md`, `phase_2_file_upload_async_ingestion.md`, `phase_8_studio_artifact_generation.md`, `phase_15_admin_ops.md` | 统一 Task 状态为 `PENDING/RUNNING/SUCCESS/FAILED/CANCELLED/TIMEOUT` 或明确不用 `TIMEOUT`；统一 Task 类型枚举；补 `cancel_requested`、`attempt_no`、`locked_by`、`heartbeat_at`、`timeout_at`。 |
| H-06 | 上传合并后创建 Document/Task 与 Kafka 发送之间没有事务外盒或补偿；Phase 2 占位 Consumer 建议把 Task 标记 `SUCCESS` 但 Document 保持 `PROCESSING`。 | 可能出现消息发送失败、任务丢失、Task 成功但文档永远无法索引，Phase 3 也无法可靠接管旧任务。 | `phase_2_file_upload_async_ingestion.md`, `file_upload_async_pipeline.md`, `phase_3_document_processing_indexing.md` | 引入 `task_outbox` 或 Task 扫描补偿；Phase 2 不应把占位消费标为业务 `SUCCESS`，可标记 `DISPATCHED`/`PLACEHOLDER_CONSUMED` 或保持 `PENDING` 等 Phase 3 真实消费。 |
| H-07 | 上传幂等 key 与对象 key 设计过粗：Redis key 为 `upload:{userId}:{fileMd5}`，MinIO 合并对象为 `merged/{fileMd5}`，缺少 uploadId、chunkSize、space/kb 引用和对象引用计数。 | 同一用户同一文件跨 Space/KB 上传会冲突；删除一个 Document 可能误删其他 Space 复用的对象；清理孤儿对象困难。 | `file_upload_async_pipeline.md`, `phase_2_file_upload_async_ingestion.md`, `database_api_blueprint.md` | 增加 `file_object`/`storage_object_ref` 表记录 `content_hash/object_key/ref_count`；Redis key 至少包含 `uploadId`；对象路径使用 `objects/{hash}`，Document 持有引用，不按 Space 删除共享对象。 |
| H-08 | 个人 Source 导入链路不完整：Phase 6 对 FILE/URL 只是标记 READY 或占位，Phase 7 却要求加载 Source Raw Text 编译 ArticleCard/ConceptCard。 | 个人 Wiki Compiler 会在 Phase 7 缺少原文输入，无法稳定生成卡片或证据回溯。 | `phase_6_personal_research_source.md`, `phase_7_personal_wiki_compiler_cards.md`, `database_api_blueprint.md` | Phase 6 必须复用文档解析能力，产出 `raw_text_object_key`、`source_chunk` 或至少可读取的纯文本；URL Source 明确抓取/失败/重试状态。 |
| H-09 | Citation/Evidence 模型不足以覆盖团队回答、个人卡片、Artifact 和 Wiki：Citation 缺少 `user_id`、`research_project_id`、`target_type/target_id`、answer span、retrievalTraceId；ArticleCard 仍把证据放 JSON。 | Evidence 查看和越权校验难落地，卡片/产物证据无法统一追踪，后续 RAG Eval 很难关联。 | `database_api_blueprint.md`, `phase_4_team_rag_chat_citation.md`, `phase_7_personal_wiki_compiler_cards.md`, `phase_8_studio_artifact_generation.md`, `phase_14_evaluation_observability.md` | 统一 `citation` 和关联表：增加 scope 字段、目标关联表、quote source span、retrieval_trace_id；ArticleCard/ConceptCard 证据也通过 Citation 或 Evidence 表记录，JSON 只放展示摘要。 |
| H-10 | 数据库蓝图缺少外键、软删除字段和完整审计字段；Phase 15 又依赖 `deletedAt` 做软删除清理。 | 数据孤儿风险高，删除/归档/清理没有可靠依据，权限隔离靠应用代码单点兜底。 | `database_api_blueprint.md`, `phase_15_admin_ops.md` | 核心表补 `deleted_at`、`created_by`、`updated_by`；明确 FK 或至少逻辑外键约束；为 `space_id/user_id/owner_id` 建组合索引；清理策略以软删除字段为准。 |
| H-11 | Frontend Phase 16 是超大整合阶段且 API 与后端蓝图明显漂移。 | 如果后端按前面阶段实现，前端最后阶段会集中暴露接口不一致，返工面大。 | `phase_16_frontend_workspace.md`, `database_api_blueprint.md`, `phase_2_file_upload_async_ingestion.md`, `phase_5_workspace_chat_runtime.md` | 把前端拆成随后端阶段增量交付；先修正 Phase 16 API 清单，直接引用后端蓝图而不是另起路径。 |
| H-12 | Quiz/测验作为暂缓功能仍进入主模型、数据库、API 和 Studio Plan。 | AI 执行阶段时可能误实现暂缓能力，扩大 MVP 范围。 | `note_weave_功能说明与架构文档.md`, `database_api_blueprint.md`, `phase_8_studio_artifact_generation.md`, `phase_11_personal_generation.md`, `phase_16_frontend_workspace.md` | 从当前阶段删除 `QUIZ` Artifact 类型、Quiz Plan、quiz 表和 API；保留到“未来扩展/暂缓功能”附录。 |
| H-13 | 文档重建索引采用“先删除旧 Chunk/ES，再重新写入”的幂等策略，缺少处理版本和提交点。 | Worker 崩溃会导致原可检索文档突然不可检索，Citation 指向的 chunk 可能消失。 | `phase_3_document_processing_indexing.md`, `file_upload_async_pipeline.md` | 增加 `process_version`/`index_version`；新版本先写入临时或新版本索引，成功后切换 active version，再异步清理旧版本。 |
| H-14 | Artifact、Citation、Memory、Source 的权限边界各自描述，缺少统一 ResourcePermission 规则。 | 团队数据和个人数据容易在 Studio 生成、Citation 展示、Memory 读取时串权限。 | `phase_8_studio_artifact_generation.md`, `phase_12_long_term_memory.md`, `phase_14_evaluation_observability.md`, `database_api_blueprint.md` | 定义统一 `ResourceScope`：`TEAM_SPACE/PERSONAL_PROJECT/USER_PRIVATE`；所有跨模块读取必须调用统一权限校验，不允许只按 id 查询。 |

## 3. 中优先级问题

| 编号 | 问题 | 影响 | 涉及文档 | 改进建议 |
|---|---|---|---|---|
| M-01 | 多数列表 API 没有逐项定义分页、筛选、排序参数。 | 前端列表、Admin 查询和大数据量场景会出现接口扩展返工。 | `database_api_blueprint.md`, `phase_2_file_upload_async_ingestion.md`, `phase_4_team_rag_chat_citation.md`, `phase_16_frontend_workspace.md` | 为每个 list/search API 明确 `page/pageSize/sort/filter/status/query`，并统一响应结构。 |
| M-02 | KnowledgeBase 删除/归档与 Document、Chunk、ES、Citation、Artifact 之间的级联关系不清。 | 删除知识库后可能残留索引、引用和对象文件。 | `phase_2_file_upload_async_ingestion.md`, `phase_3_document_processing_indexing.md`, `phase_15_admin_ops.md` | 明确归档优先于物理删除；删除时写清理 Task，依次处理 Document、Chunk、ES、Citation、MinIO 引用。 |
| M-03 | Document 同时有 `status`、`parse_status`、`index_status`，但状态迁移图不完整。 | UI 和 Worker 可能展示互相矛盾的处理状态。 | `database_api_blueprint.md`, `phase_2_file_upload_async_ingestion.md`, `phase_3_document_processing_indexing.md` | 增加状态迁移表，定义主状态由子状态如何推导；失败重试时各状态如何重置。 |
| M-04 | ES 字段有 snake_case 和 camelCase 两套写法，索引名和 alias 策略不明确。 | 查询 filter 可能写错字段，后续索引升级困难。 | `file_upload_async_pipeline.md`, `phase_3_document_processing_indexing.md`, `phase_9_retrieval_enhancement_rrf.md` | 统一 ES 字段命名为 camelCase 或 snake_case；引入 index alias 和 mapping version。 |
| M-05 | Embedding 接入缺少历史 Chunk 回填、模型维度变化、向量版本管理。 | Phase 9 上线后旧文档没有向量，换 embedding 模型会导致 ES dense_vector 维度冲突。 | `phase_9_retrieval_enhancement_rrf.md`, `phase_3_document_processing_indexing.md` | 增加 `EMBEDDING_BACKFILL` Task，记录 `embedding_model/version/dimension/status`。 |
| M-06 | RAG Prompt 没有明确处理 prompt injection、恶意文档内容、引用不支持回答等安全规则。 | 用户上传文档可能诱导模型泄露系统提示或越权回答。 | `phase_4_team_rag_chat_citation.md`, `phase_9_retrieval_enhancement_rrf.md` | 在 PromptBuilder 增加“资料内容不具备指令优先级”的规则；EvidencePostProcessor 标记不可信来源；引用必须支撑结论。 |
| M-07 | WebSocket 协议命名不一致，缺少 event seq、ack、重放和去重规则。 | 断线恢复可能丢 token、重复保存消息或无法确认最终状态。 | `phase_5_workspace_chat_runtime.md`, `workspace_chat_runtime_memory.md`, `phase_16_frontend_workspace.md` | 统一事件名为 `chat.delta/chat.completed/...`；增加 `streamId`、`eventSeq`、`lastAckSeq`；resume 按序重放。 |
| M-08 | ChatSession 使用 `scope_ids_json` 保存范围，后续按 KB/Source/Card 查询会困难。 | 检索、列表筛选、权限审计和索引追踪难写 SQL。 | `database_api_blueprint.md`, `phase_4_team_rag_chat_citation.md`, `workspace_chat_runtime_memory.md` | 保留快照 JSON 的同时增加 `chat_session_scope` 关联表。 |
| M-09 | `space_memory` 对 `user_id + space_id` 做唯一约束，但字段又包含 `topic`，无法存多个主题记忆。 | 重要主题会互相覆盖，长期 Memory 难演进。 | `database_api_blueprint.md`, `phase_12_long_term_memory.md` | 改为唯一约束 `user_id + space_id + memory_key/topic`，或拆 `space_memory_item` 表。 |
| M-10 | PromptVersion、LLMCallLog、RetrievalTrace、Eval、AuditLog、Health 等 Phase 14/15 表没有纳入中央 `database_api_blueprint.md`。 | 代码生成和迁移以蓝图为准时会漏表。 | `database_api_blueprint.md`, `phase_14_evaluation_observability.md`, `phase_15_admin_ops.md` | 把 Phase 14/15 数据模型同步到蓝图，并标注实现阶段。 |
| M-11 | Source 去重只靠 `content_hash` 索引，没有定义项目内唯一约束、跨项目复用和 Source 版本。 | 同一资料重复导入、覆盖或重新抓取后版本不可追踪。 | `database_api_blueprint.md`, `phase_6_personal_research_source.md` | 增加 `source_version` 或 `source_snapshot`；项目内可选唯一 `research_project_id + content_hash`。 |
| M-12 | MethodologyCard 没有 `status`、`created_by`、系统预置模板归属和版本。 | 预置模板升级和用户自定义模板管理困难。 | `database_api_blueprint.md`, `phase_13_methodology_card.md` | 增加 `status/version/created_by/system_scope`，预置模板用 seed/migration 管理。 |
| M-13 | Team Wiki 有发布和入索引，但没有明确用户搜索 Wiki 的 API。 | 前端 Wiki 搜索和用户浏览无法落地，只能依赖 RAG 检索。 | `phase_10_team_wiki_publish_index.md`, `phase_16_frontend_workspace.md` | 增加 `GET /api/v1/team/spaces/{spaceId}/wiki-pages/search` 或在列表 API 定义 `keyword/status`。 |
| M-14 | LLM 日志只说默认不存完整 Prompt，但脱敏、对象存储、保留期和访问边界未定。 | Prompt、Memory、私有 Source 可能进入日志造成敏感信息扩散。 | `phase_14_evaluation_observability.md`, `phase_15_admin_ops.md` | 定义日志脱敏策略、保留周期、管理员可见字段、敏感快照对象权限。 |
| M-15 | 资源清理只有 job 表，没有清理明细表和 dry-run 结果结构。 | Scan 与 Execute 难以可审计，误删后无法追责。 | `phase_15_admin_ops.md` | 增加 `ops_cleanup_item`，记录候选对象、原因、执行状态和错误。 |
| M-16 | 测试策略以单测清单为主，缺少 Testcontainers/集成环境矩阵。 | MinIO/Kafka/ES/WebSocket 链路容易到后期才暴露问题。 | `phase_2_file_upload_async_ingestion.md`, `phase_3_document_processing_indexing.md`, `phase_5_workspace_chat_runtime.md` | 统一增加本地开发和 CI 集成测试策略，至少覆盖 MySQL、Redis、MinIO、Kafka、ES。 |
| M-17 | 命名风格有 `users`、`space`、`knowledge_base` 混用单复数，Task 字段有 `task_status` 与文档中的 `status`。 | JPA 实体、Repository 和 SQL 迁移命名不稳定。 | `database_api_blueprint.md`, `phase_2_file_upload_async_ingestion.md` | 制定表名、字段名、枚举名规范，并在蓝图中一次性收敛。 |

## 4. 低优先级问题

| 编号 | 问题 | 影响 | 涉及文档 | 改进建议 |
|---|---|---|---|---|
| L-01 | Phase 0/1 说后续再改 Flyway/Liquibase，但 Phase 0 目标又要求数据库迁移工具。 | 早期开发可行，但多人协作时迁移容易失控。 | `phase_0_1_bootstrap_auth_space.md`, `implementation_breakdown.md` | Phase 0 直接引入 Flyway，`ddl-auto` 仅允许本地临时使用。 |
| L-02 | 默认 JWT secret、MinIO admin 等开发默认值容易被误带到共享环境。 | 安全基线较弱。 | `phase_0_1_bootstrap_auth_space.md`, `phase_2_file_upload_async_ingestion.md` | 在配置说明中要求非 dev profile 必须显式配置密钥，否则启动失败。 |
| L-03 | 文档解析支持类型与 content-type 校验较粗，`application/octet-stream` 可能绕过类型限制。 | 解析失败率和安全风险上升。 | `phase_3_document_processing_indexing.md` | 使用扩展名、MIME sniffing、大小限制和白名单组合校验。 |
| L-04 | API 版本升级和废弃策略未定义。 | 后续前后端并行时难灰度。 | `database_api_blueprint.md` | 增加 `/api/v1` 版本兼容和废弃规则。 |
| L-05 | KnowledgeBase、ResearchProject 列表没有冗余统计字段或统计接口设计。 | 前端要展示文档数、Source 数、失败任务数时可能产生 N+1 查询。 | `phase_16_frontend_workspace.md`, `database_api_blueprint.md` | 增加 summary API 或异步统计字段。 |
| L-06 | Artifact 导出接口在文档中有 GET 和 POST 两种口径。 | 前端实现会不一致。 | `database_api_blueprint.md`, `phase_8_studio_artifact_generation.md`, `phase_16_frontend_workspace.md` | 统一为 `GET /api/v1/artifacts/{artifactId}/export?format=markdown`，高级导出后续再扩展。 |
| L-07 | 错误码分散在各阶段，没有中央错误码表。 | 前端错误展示和测试断言难统一。 | `phase_0_1_bootstrap_auth_space.md`, `phase_2_file_upload_async_ingestion.md`, `phase_14_evaluation_observability.md` | 在蓝图或单独文档维护全局 ErrorCode 注册表。 |
| L-08 | 前端设计强调布局，但没有明确浏览器兼容、文件大上传限制和移动端降级策略。 | 非核心但会影响体验边界。 | `phase_16_frontend_workspace.md` | 补充最小浏览器支持和大文件上传 UI 限制。 |

## 5. 功能完整性缺口

| 功能域 | 当前问题 | 缺失能力 | 改进建议 |
|---|---|---|---|
| 用户与认证 | 注册、登录、`GET /users/me` 有设计，但刷新 token、退出登录、个人资料编辑缺失。 | refresh token、logout/token revoke、修改资料、修改密码、禁用用户后的登录拒绝。 | 在 Phase 0/1 和蓝图补 AuthSession/RefreshToken、用户资料 API、密码策略和退出登录流程。 |
| Space 与权限 | Space/SpaceMember/OWNER/EDITOR/VIEWER 基本完整，但没有系统 ADMIN，邀请流程偏简化。 | ADMIN 系统角色、成员邀请 pending/accepted、成员操作审计、权限服务覆盖 ResourceScope。 | 区分系统角色和空间角色；成员添加可先直添，但保留 invitation 状态字段或后续扩展点。 |
| 团队知识库 | 创建、列表、更新、删除/归档基本覆盖，但删除后的资源级联和状态统计不足。 | KB 归档策略、文档/索引/Citation/Artifact 级联清理、统计摘要。 | 明确 `ARCHIVED` 不参与上传和检索；清理由 Task/Ops 执行。 |
| 文件上传与异步处理 | 分片、断点续传、MD5 秒传、状态查询有设计，但取消上传、对象复用、Kafka 补偿不足。 | 上传取消 API、对象引用计数、事务外盒、分片过期清理明细。 | 增加 `POST /api/v1/team/document-uploads/{uploadId}/cancel`；增加 storage ref 表和 outbox。 |
| 文档解析与索引 | PDF/Markdown/TXT、Chunk、ES BM25 覆盖，但重建索引和版本切换不安全。 | process_version、索引别名/版本、解析失败重试策略、Embedding 回填状态。 | 改为新版本成功后切换 active，失败不破坏旧索引。 |
| 团队 RAG 问答 | 基础 HTTP RAG、Citation、Evidence Context 覆盖，但反馈和 trace 到 Phase 14 才补。 | 最小 RetrievalTrace、回答反馈、prompt injection 防护、Citation span。 | Phase 4 就落最小 trace 和 feedback 表/接口，Phase 14 再做 Eval 平台。 |
| 工作台会话 | WebSocket、正式/草稿、停止、恢复有设计，但只明确团队问答，事件可靠性不足。 | eventSeq/ack、断线重放、部分回答落库策略、PersonalResearchChat。 | 统一协议事件名，定义 message 状态；明确个人研究会话是否进入当前 MVP。 |
| Memory | SessionSummary、SpaceMemory、UserMemory 有设计，但唯一约束和敏感信息策略不合理。 | 多主题 memory、用户可控删除/禁用、敏感内容过滤、保留期。 | 拆 memory item；增加 Memory 写入白名单、可见/可编辑/可删除规则。 |
| 个人研究项目 | ResearchProject CRUD 覆盖，但归档与 Space 绑定、统计和搜索不完整。 | 项目归档后的 Source/Task 处理、项目内搜索/筛选、项目 summary。 | 在 API 中补 `status` filter 和项目详情统计。 |
| Source 导入 | 文件/URL/文本入口有设计，但 FILE/URL 的真实解析和 raw text 产出不足。 | Source parse Worker、URL 抓取失败重试、RawText 存储、Source 去重。 | Phase 6 必须产出可编译文本，不要只标记 READY。 |
| ArticleCard/ConceptCard | 生成、查看、合并基本覆盖，但证据回溯和搜索薄弱。 | Card 搜索、Citation 关联、ConceptRelation API、卡片重编译。 | 将 evidence 从 JSON 展示字段提升到 Citation/Evidence 表；增加 `keyword` 查询。 |
| Studio 与 Artifact | 任务、Artifact CRUD、编辑、导出、重新生成覆盖，但包含暂缓 Quiz，Artifact 版本不清。 | Artifact 版本/修订、预览格式、生成取消/重试统一 Task、移出 Quiz。 | Phase 8 拆为 Artifact 基础与 Studio 生成；当前阶段只保留 Report/StudyGuide/Briefing/FAQ/Comparison。 |
| MethodologyCard | 预置、创建、匹配、应用到 Prompt 覆盖，但版本/状态/预置归属缺失。 | 模板版本、系统预置 seed、状态和归档。 | 增加 `version/status/card_scope/created_by`，并明确 Phase 11 是否依赖它。 |
| 团队 Wiki | 草稿、发布、版本、入索引覆盖，但搜索 API 和发布权限不稳定。 | Wiki 搜索、版本查看 API、发布权限固定策略、取消发布/回滚。 | MVP 固定 OWNER 发布；增加版本列表和搜索接口。 |
| 检索增强 | BM25/Vector/Wiki/RRF 有设计，但缺少向量回填和模型版本。 | EmbeddingBackfill Task、embedding 状态、vector dimension 迁移。 | 在 Phase 9 增加 backfill 子流程和降级策略。 |
| 评测与可观测性 | Phase 14 设计较完整，但中央 DB/API 蓝图未纳入，且引入过晚。 | 最小 LLMCallLog/RetrievalTrace 早期埋点、日志脱敏策略。 | Phase 4/8 开始记录最小日志，Phase 14 做完整 Eval。 |
| 管理后台与运维 | Phase 15 有任务、清理、健康、审计，但 ADMIN 角色和清理明细缺失。 | ADMIN 模型、cleanup item、task attempt、组件健康公开边界。 | Admin 权限前置到 Phase 0/1；Ops 表同步进蓝图。 |
| 前端工作台 | 覆盖页面全面，但接口路径与后端不一致，阶段过大。 | 增量页面交付、API client 与后端契约对齐、WS 事件一致性。 | 按后端阶段拆前端子阶段；Phase 16 仅做总集成和打磨。 |
| 通用工程能力 | 有统一响应/错误/测试建议，但迁移、配置、CI、本地环境和分页不够硬。 | Flyway、Testcontainers、统一分页排序、配置 profile、全局错误码表。 | 在 Phase 0 增加工程基线文档和 CI 验收标准。 |

## 6. 数据库与 API 改进建议

### Auth/User/Space

- 问题：缺少 refresh token/logout/token revoke；没有系统 ADMIN 角色；`users` 缺少 `last_login_at`、`disabled_at`、`created_by/updated_by`；Space 个人空间唯一约束只在文字说明里。
- 建议：增加 `auth_refresh_token` 或 `user_session` 表；`users` 增加 `system_role/status/disabled_at/last_login_at`；`space` 增加唯一约束或应用级保证 `owner_id + type=PERSONAL`；API 补 `POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout`、`PUT /api/v1/users/me`、`PUT /api/v1/users/me/password`。

### KnowledgeBase/Document/Upload/Chunk

- 问题：上传 cancel 状态有枚举但无 API；Redis/MinIO key 粒度不够；Document 删除、KB 归档、Chunk、ES、Citation、MinIO 引用关系不清；缺少 `deleted_at`。
- 建议：增加 `document_upload.cancelled_at` 和 cancel API；增加 `storage_object` 与 `storage_object_ref`；Document/Chunk 增加 `process_version/index_version/deleted_at`；Document reindex 创建新版本，不直接删除旧索引。

### Task/Kafka

- 问题：Task 状态/类型命名不统一；Kafka 投递无 outbox；重试缺少 attempt 记录、锁、心跳和取消请求；Phase 2 placeholder 会制造状态不一致。
- 建议：统一 Task 枚举；增加 `task_attempt` 或 attempt 字段；增加 `cancel_requested/locked_by/heartbeat_at/timeout_at`；用 outbox 或扫描补偿保证 Kafka 投递；Phase 2 不把占位消费标业务成功。

### Chat/Session/Message/Citation

- 问题：`scope_ids_json` 不利于查询；Message 缺少状态；Citation 不足以表达回答句子与证据片段关系；WebSocket 协议缺少 seq/ack。
- 建议：增加 `chat_session_scope`；`chat_message` 增加 `status/generation_id/parent_message_id`；Citation 增加 `target_type/target_id/answer_span/source_span/retrieval_trace_id`；WebSocket 增加 `streamId/eventSeq/lastAckSeq`。

### Personal Research/Source/Card

- 问题：Source FILE/URL 解析占位导致 Phase 7 无 raw text；ArticleCard 证据放 JSON；ConceptRelation 没有 API；Source 去重和版本缺失。
- 建议：Phase 6 增加 Source parse task，产出 `raw_text_object_key` 或 `source_chunk`；Card evidence 走 Citation/Evidence；增加 SourceVersion；补 Card 搜索和 ConceptRelation 查询接口。

### Studio/Artifact

- 问题：Phase 8 同时做 Studio、Skill、Task、Artifact、导出和重新生成，范围偏大；Quiz 暂缓仍在当前类型中；Artifact 没有版本/修订。
- 建议：先实现 Artifact 基础 CRUD/Markdown 导出，再实现 Studio Task；移除 Quiz；增加 `artifact_revision` 或最小 `version_no`，编辑不覆盖历史。

### Wiki

- 问题：只有发布流程，没有搜索和版本查看 API；发布权限“OWNER 或可选 EDITOR”不确定；Wiki 与 Artifact 的关系只用 `source_artifact_id` 单向字段。
- 建议：MVP 固定 OWNER 发布；增加 `GET /versions`、`GET /search`；增加 `artifact_wiki_page` 关联或确保双向可追踪。

### Memory

- 问题：`space_memory` 唯一键使 topic 失效；敏感信息写入和用户控制不足；DRAFT 不写 Memory 只在规则中说明，缺少持久化防线。
- 建议：拆 `space_memory_item`；增加 memory 类型、置信度、来源、可见性；提供删除/禁用记忆写入 API；MemoryWriteback 必须做敏感信息过滤。

### Evaluation/Observability

- 问题：Phase 14 表未进入蓝图；日志可能泄露 Prompt/Memory；Eval Run 是否复用正式 Chat 链路需要更明确隔离。
- 建议：同步表结构到蓝图；Prompt snapshot 只存脱敏摘要或对象引用；Eval Run 使用隔离 session/task，不写正式会话和用户 Memory。

### Admin/Ops

- 问题：ADMIN 角色缺失；清理只有 job 没有 item；健康检查可能泄露内部依赖详情；Task retry 缺少 attempt 语义。
- 建议：Admin 权限前置；增加 `ops_cleanup_item`；普通 Space owner 只看自身资源摘要；全局健康详情仅 ADMIN 可见；重试创建新 attempt 或新 Task 并关联原 Task。

## 7. 阶段拆分调整建议

| 阶段 | 当前问题 | 风险 | 调整建议 |
|---|---|---|---|
| Phase 0/1 | Auth/User/Space 基本可用，但缺少 refresh/logout/profile/admin role/Flyway。 | 第一阶段代码写完后马上因认证和 Admin 补字段返工。 | 先小修文档：补认证会话、系统角色、迁移工具、统一 API；再开始编码。 |
| Phase 2 | 同时做 KB、MinIO、分片、Task、Kafka；缺 upload cancel、outbox、对象引用；占位 Consumer 状态不安全。 | 上传链路最容易产生不一致和孤儿对象。 | 在 Phase 2 前半固定 Task 基础；后半做上传；Kafka 投递必须有补偿；取消占位 `SUCCESS`。 |
| Phase 3 | 解析/Chunk/ES 设计清楚，但重建索引先删后写不安全。 | 崩溃会丢可检索数据和 Citation 指向。 | 增加 index version 和 active 切换；保留旧 Chunk 到新版本成功。 |
| Phase 4 | Team RAG 闭环完整，但 Citation/Trace/Feedback 最小能力不足。 | 后续评测和证据查看补表成本高。 | Phase 4 就落最小 RetrievalTrace、Citation span、AnswerFeedback；完整 Eval 留 Phase 14。 |
| Phase 5 | WebSocket 支持正式/草稿/停止/恢复，但只覆盖团队问答，协议可靠性不足。 | 断线恢复和停止后落库行为容易前后端扯皮。 | 补 event seq/ack、partial message 状态；明确 PersonalResearchChat 是否暂缓。 |
| Phase 6 | 个人 Source 只是入口，FILE/URL 解析占位。 | Phase 7 编译没有可用 raw text。 | 将 Source import 做成真实解析任务，至少产出 raw text 和失败重试。 |
| Phase 7 | Card 编译流程较清楚，但 Evidence 仍偏 JSON。 | 证据回溯和越权校验不可复用。 | 引入统一 Citation/Evidence，Card JSON 只做展示缓存。 |
| Phase 8 | Studio 与 Artifact 范围过大，并包含暂缓 Quiz。 | AI 执行会扩范围，Artifact 基础未稳就做生成编排。 | 拆为 Artifact 基础、Studio Task、Skill Plan 三步；移出 Quiz。 |
| Phase 9 | 检索增强位置合理，但缺 Embedding 回填和版本。 | 旧文档无法向量召回，模型维度变化难升级。 | 增加 Embedding backfill task 和 embedding version。 |
| Phase 10 | Wiki 发布和入索引合理，但搜索、版本查看和权限策略不固定。 | 前端 Wiki 页面与 RAG WikiRetriever 脱节。 | 固定 OWNER 发布；补版本/搜索 API；明确 Wiki index alias。 |
| Phase 11 | 个人生成复用 Artifact 合理，但和 Phase 8 职责重叠，Methodology 又在 Phase 13。 | 生成类型、Task 类型和上下文选择可能重复实现。 | Phase 11 只做个人上下文和个人 Plan；Artifact CRUD 不重复；若强依赖方法论，把预置 Methodology 前移。 |
| Phase 12 | Memory 深化时机可以，但 Phase 5 已有 ContextReadRouter，且表约束不支持多主题。 | 两阶段重复实现 Router，Memory 写入可能覆盖。 | Phase 5 只做接口占位；Phase 12 替换为完整 Router；修正 memory 表。 |
| Phase 13 | MethodologyCard 设计合理，但在个人生成之后才实现。 | Phase 11 若先生成，后续接 Methodology 会改 Prompt/Plan。 | 预置 Methodology 可提前到 Phase 11 前作为轻量模板；高级编辑留 Phase 13。 |
| Phase 14 | 可观测性完整但过晚。 | Phase 4-13 的 LLM/检索链路会先无日志，后补侵入大。 | 最小 LLMCallLog/RetrievalTrace 前移到 Phase 4/8；Phase 14 做管理、Eval 和指标。 |
| Phase 15 | Admin/Ops 必要，但放到最后太晚；ADMIN 模型缺失。 | Phase 2/3 之后失败任务和孤儿资源无管理入口。 | 拆出 Ops 基础到 Phase 3 后：任务重试、健康检查、清理 scan；完整 Admin Console 留 Phase 15。 |
| Phase 16 | 前端阶段过大，且 API 与后端不一致。 | 最后集中集成会暴露大量契约错位。 | 改为每个后端阶段提供最小前端/API client；Phase 16 只做工作台整合和体验。 |

## 8. 暂缓或应移出的功能

| 功能 | 出现位置 | 问题 | 建议 |
|---|---|---|---|
| Quiz、测验、答题、评分、题库 | `note_weave_功能说明与架构文档.md` 的 NotebookLM/Studio 章节、`database_api_blueprint.md` 的 Quiz 表和 API、`phase_8_studio_artifact_generation.md` 的 Artifact 类型与 Quiz Plan、`phase_11_personal_generation.md` 的提示、`phase_16_frontend_workspace.md` 的排除项 | 虽然部分文档说不做评分，但 Quiz 生成、表结构和 API 已进入当前蓝图，容易被误实现。 | 从当前数据库/API 和 Phase 8 中移出；只保留未来扩展附录。 |
| 外部研究资料自动发现 | `note_weave_功能说明与架构文档.md` 的 `Research Source Discovery`，以及多个阶段的“不做外部资料发现”说明 | 总文档仍保留增强流程和外部搜索数据源，容易和 Phase 6 手动 URL 导入混淆。 | 当前阶段只保留“手动 URL/PDF/Text 导入”；外部搜索、论文 API、推荐资料移到未来扩展。 |
| 商业化计费、套餐 | `phase_15_admin_ops.md` 的“不做计费、套餐、额度售卖” | 未发现实际设计进入当前阶段。 | 保持排除，不需要作为缺失功能。 |
| 复杂企业审批流 | `phase_15_admin_ops.md`、`phase_10_team_wiki_publish_index.md` 的“不做复杂审核/审批流” | 未发现实际落地设计，只是明确排除。 | 保持排除；Wiki MVP 只保留简单人工发布。 |
| 复杂多人实时协同编辑 | `phase_5_workspace_chat_runtime.md`、`phase_8_studio_artifact_generation.md`、`phase_10_team_wiki_publish_index.md`、`phase_16_frontend_workspace.md` | 文档均明确不做，未发现明显当前实现设计。 | 保持排除，不要把协同编辑作为当前缺口。 |

## 9. 建议立即修改的文档

| 优先级 | 文档 | 修改原因 | 建议修改点 |
|---|---|---|---|
| P0 | `database_api_blueprint.md` | 中央数据库/API 契约不完整且包含暂缓 Quiz。 | 补 Auth refresh/logout、ADMIN、Task 状态、软删除/FK/审计字段、Phase 14/15 表；移出 Quiz。 |
| P0 | `implementation_breakdown.md` | 阶段编号与专题文档不一致，API 仍无 `/api/v1`。 | 重写 Phase 0/1-16 顺序；明确 Task 基础位置；统一 API 前缀和资源命名。 |
| P0 | `phase_0_1_bootstrap_auth_space.md` | 第一阶段会决定所有后续权限和认证边界。 | 补 refresh token、logout、用户资料、系统 ADMIN、Flyway、非 dev 密钥要求。 |
| P0 | `phase_2_file_upload_async_ingestion.md` | 上传/Kafka/Task 链路可靠性风险最高。 | 补 outbox/补偿、upload cancel、对象引用、Redis key、占位 Consumer 状态。 |
| P0 | `file_upload_async_pipeline.md` | 专题文档仍使用旧 `/api`，且 key/ref 设计会影响对象生命周期。 | 统一 `/api/v1`；修正 Redis/MinIO key；增加 storage ref 和取消上传。 |
| P1 | `workspace_chat_runtime_memory.md` | 与蓝图冲突：旧 API、`citation_ids` JSON、Memory 写回和 Phase 5 边界不一致。 | 改 `/api/v1`；移除 `citation_ids` JSON；补 event seq/ack；明确 Phase 5/12 分工。 |
| P1 | `phase_8_studio_artifact_generation.md` | 范围过大且包含暂缓 Quiz。 | 移出 Quiz；拆 Artifact 基础与 Studio Task；补 Artifact revision。 |
| P1 | `phase_16_frontend_workspace.md` | 前端 API 与后端蓝图不一致。 | 统一后端路径、WebSocket 事件名；拆成增量前端阶段。 |
| P1 | `phase_6_personal_research_source.md` | Source 导入不足以支撑 Phase 7。 | 增加真实 Source parse、raw text、URL 失败重试、Source 去重。 |
| P1 | `phase_7_personal_wiki_compiler_cards.md` | Card 证据模型与统一 Citation 方向不一致。 | Card 证据使用 Citation/Evidence 表；补搜索和重编译。 |
| P2 | `phase_14_evaluation_observability.md` | 设计完整但需要和早期链路集成。 | 标注最小日志前移；同步数据表到蓝图；补脱敏和保留期。 |
| P2 | `phase_15_admin_ops.md` | 依赖 ADMIN 和 Task 状态，但前置文档未定义。 | 对齐 Task 状态；补 cleanup item；明确 Space owner 与 ADMIN 的边界。 |

## 10. 是否可以开始第一阶段编码

需要先小修文档。

原因：Phase 0/1 的主体方向是对的，User、Space、SpaceMember、JWT、统一响应和权限服务可以保留；但认证刷新/退出、系统 ADMIN、API 前缀、迁移工具和中央蓝图字段如果不先修，第一阶段编码完成后很快会在前端、Admin、权限和后续阶段中返工。建议先完成 P0 文档修订，再开始 Phase 0/1 编码。
