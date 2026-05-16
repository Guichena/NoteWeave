# NoteWeave Phase Prompts

本目录保存每个开发阶段可直接复制给 AI 编码代理的执行提示词。

使用规则：

1. 先选择对应阶段的 prompt 文件。
2. 把 prompt 整段发给 AI。
3. AI 必须先读取 prompt 中列出的文档，再改代码。
4. 如果文档冲突，优先级固定为：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
当前 phase 文档
相关依赖文档
```

所有编程阶段统一采用测试驱动开发：

```text
先写测试
运行并确认测试失败
再写实现
重构
最后运行当前阶段测试和必要回归测试
```

如果某个阶段存在无法自动化测试的部分，必须在 `docs/PROJECT_STATUS.md` 的阶段记录中写清楚手动验证方式。

所有中间件必须通过 Docker Compose 或 Testcontainers 提供。如果当前阶段用到新的中间件、测试 bucket、topic、index 或本地临时路径，必须同步更新 `docs/DOCKER_MIDDLEWARE.md`。

后台异步任务消息队列统一使用 Kafka。`task_outbox` 只做 DB 事务外盒和补偿投递；Redis Stream 不是项目级消息队列，只能作为 Phase 5 Chat runtime 的可选临时事件缓冲，用于 WebSocket 流式状态、ack/resume、断线恢复和短期上下文。上传、解析、索引、生成、评测、清理等后台任务不得使用 Redis Stream；如果普通 Redis key/list/zset 足够，Phase 5 也可以不引入 Redis Stream。

不要把以下文档作为实现权威：

```text
docs/note_weave_功能说明与架构文档.md
docs/features/noteweave_full_arch_review.md
docs/architecture_review_issues_and_recommendations.md
```

这些只作为背景或历史审查记录。若其中出现旧 `/api/...`、`generation_task/index_task`、`generated_artifact`、Quiz 当前实现或完全自主 Agent 口径，均以当前 Phase prompt 和权威契约为准。

## 阶段提示词

| 阶段 | Prompt |
|---|---|
| Phase 0/1 | [phase_0_1_bootstrap_auth_space_prompt.md](./phase_0_1_bootstrap_auth_space_prompt.md) |
| Phase 1.5 | [phase_1_5_task_outbox_worker_prompt.md](./phase_1_5_task_outbox_worker_prompt.md) |
| Phase 2 | [phase_2_file_upload_async_ingestion_prompt.md](./phase_2_file_upload_async_ingestion_prompt.md) |
| Phase 3 | [phase_3_document_processing_indexing_prompt.md](./phase_3_document_processing_indexing_prompt.md) |
| Phase 4 | [phase_4_team_rag_chat_citation_prompt.md](./phase_4_team_rag_chat_citation_prompt.md) |
| Phase 5 | [phase_5_workspace_chat_runtime_prompt.md](./phase_5_workspace_chat_runtime_prompt.md) |
| Phase 6 | [phase_6_personal_research_source_prompt.md](./phase_6_personal_research_source_prompt.md) |
| Phase 7 | [phase_7_personal_wiki_compiler_cards_prompt.md](./phase_7_personal_wiki_compiler_cards_prompt.md) |
| Phase 8 | [phase_8_studio_artifact_generation_prompt.md](./phase_8_studio_artifact_generation_prompt.md) |
| Phase 9 | [phase_9_retrieval_enhancement_rrf_prompt.md](./phase_9_retrieval_enhancement_rrf_prompt.md) |
| Phase 10 | [phase_10_team_wiki_publish_index_prompt.md](./phase_10_team_wiki_publish_index_prompt.md) |
| Phase 10.5 | [phase_10_5_methodology_preset_matcher_prompt.md](./phase_10_5_methodology_preset_matcher_prompt.md) |
| Phase 11 | [phase_11_personal_generation_prompt.md](./phase_11_personal_generation_prompt.md) |
| Phase 11.5 | [phase_11_5_personal_artifact_distillation_prompt.md](./phase_11_5_personal_artifact_distillation_prompt.md) |
| Phase 12 | [phase_12_long_term_memory_prompt.md](./phase_12_long_term_memory_prompt.md) |
| Phase 13 | [phase_13_methodology_card_prompt.md](./phase_13_methodology_card_prompt.md) |
| Phase 14 | [phase_14_evaluation_observability_prompt.md](./phase_14_evaluation_observability_prompt.md) |
| Phase 15 | [phase_15_admin_ops_prompt.md](./phase_15_admin_ops_prompt.md) |
| Phase 16 | [phase_16_frontend_workspace_prompt.md](./phase_16_frontend_workspace_prompt.md) |
