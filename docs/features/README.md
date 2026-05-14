# NoteWeave Feature Documents

本目录按功能拆分 NoteWeave 的详细设计文档。

主文档只保留产品定位和总体架构，功能实现细节沉淀到这里，后续编码时按专题逐个落地。

## 文档列表

| 文档 | 说明 |
|---|---|
| [Phase 0/1: 工程骨架、认证、用户、空间与权限](./phase_0_1_bootstrap_auth_space.md) | Spring Boot 初始化、JWT、User、Space、SpaceMember、SpacePermissionService |
| [Phase 2: KnowledgeBase、文件存储、分片上传与异步任务投递](./phase_2_file_upload_async_ingestion.md) | MinIO、Kafka、KnowledgeBase、DocumentUpload、UploadChunk、Document、Task |
| [Phase 3: 文档解析、Chunk 切片与 Elasticsearch 索引](./phase_3_document_processing_indexing.md) | Kafka Consumer、DocumentParser、ChunkService、DocumentChunk、ES BM25 索引 |
| [Phase 4: 团队基础 RAG 问答与 Citation 回溯](./phase_4_team_rag_chat_citation.md) | Team Chat、BM25 检索、Evidence Context、LLM 生成、Citation |
| [Phase 5: 工作台 WebSocket 会话执行底座](./phase_5_workspace_chat_runtime.md) | WebSocket、正式/草稿会话、流式生成、中断停止、Redis 运行态 |
| [Phase 6: 个人 ResearchProject 与 Source 导入](./phase_6_personal_research_source.md) | 个人研究项目、文件/URL/文本 Source、Raw Source、SOURCE_IMPORT 任务 |
| [Phase 7: 个人 Wiki Compiler MVP](./phase_7_personal_wiki_compiler_cards.md) | Source 编译、ArticleCard、ConceptCard、概念归一化、证据回溯 |
| [Phase 8: Studio 与 Artifact 生成系统](./phase_8_studio_artifact_generation.md) | Studio Button、固定 Plan、Skill、Task、Artifact、导出 |
| [Phase 9: 增强检索、向量召回与 Weighted RRF](./phase_9_retrieval_enhancement_rrf.md) | Embedding、VectorRetriever、Weighted RRF、Evidence 增强 |
| [Phase 10: 团队 Wiki Draft、发布与入索引](./phase_10_team_wiki_publish_index.md) | Wiki 草稿、发布、版本、ES 入索引、WikiRetriever |
| [Phase 11: 个人 Wiki-based Generation](./phase_11_personal_generation.md) | 个人研究报告、学习指南、对比分析、方案表达训练、Artifact |
| [Phase 12: 长期 Memory 深化](./phase_12_long_term_memory.md) | SessionSummary、SpaceMemory、UserMemory、上下文按需加载、记忆写回 |
| [Phase 13: MethodologyCard 方法论卡片](./phase_13_methodology_card.md) | 预置方法论模板、任务匹配、输出结构、质量检查清单 |
| [Phase 14: 评测与可观测性](./phase_14_evaluation_observability.md) | LLM 调用日志、检索 Trace、RAG Eval、Citation 质量、用户反馈 |
| [Phase 15: 管理后台与运维能力](./phase_15_admin_ops.md) | Admin Console、任务重试、资源清理、健康检查、AuditLog |
| [Phase 16: 前端工作台](./phase_16_frontend_workspace.md) | Workspace UI、团队知识库、个人研究、Chat、Studio、Artifact、Admin |
| [数据库与 API 蓝图](./database_api_blueprint.md) | 从全量架构文档抽取并修正后的 MySQL 表结构与 `/api/v1` API 总览 |
| [文件上传与异步处理链路](./file_upload_async_pipeline.md) | 分片上传、断点续传、MinIO、Redis Bitmap、Kafka、解析、向量化、ES 索引 |
| [工作台会话执行与记忆底座](./workspace_chat_runtime_memory.md) | WebSocket、正式会话、临时草稿、流式生成、中断停止、上下文恢复、分层记忆 |

## 拆分原则

- 一个文档只描述一个功能域。
- 每个功能文档都要能指导编码。
- 文档中明确参考实现，但不照搬旧命名。
- 领域模型以 NoteWeave 最终命名为准。
- 总体开发顺序仍以 `docs/implementation_breakdown.md` 为准。
