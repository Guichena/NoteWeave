# MySQL：业务事实、表结构与事务边界

> 本文件为 2026-06-01 加深版。面试问 MySQL 时，不要只讲 ACID，要讲 NoteWeave 如何把身份权限、任务状态、知识资产、证据链、记忆、评测和运维都落成可事务化事实。

## 0. 一句话定位

MySQL 是 NoteWeave 的最终业务事实源：谁能看什么、任务执行到哪、文档是否可检索、Artifact/Wiki/Synthesis 是否已确认、一次坏答案如何追溯，都要能回到表结构和状态字段。

## 1. 表结构按题组讲

### 身份与空间边界

核心表：
- `users`：账号、邮箱、密码 hash、`system_role`、`status`。
- `space`：空间类型、owner、状态。
- `space_member`：`space_id + user_id` 唯一，保存 OWNER/EDITOR/VIEWER 和成员状态。
- `user_session`：refresh token hash、过期和撤销。

面试重点：
- `system_role` 和 `space_member.role` 分开，避免平台 Admin 和空间协作权限混在一起。
- `idx_space_member_user_status`、`idx_space_member_space_status` 支撑“查我在哪些空间”和“查空间成员”两类高频路径。

### 长任务与 Outbox

核心表：
- `task`：`task_type`、`target_type/target_id`、`task_status`、`idempotency_key`、`input_json/output_json`、`cancel_requested`、`retry_count`。
- `task_attempt`：每次执行 attempt 和 worker 状态。
- `task_event`：状态流转、失败原因和进度事件。
- `task_outbox`：同事务写入消息事实，后续投递 Kafka。

面试重点：
- `uk_task_idempotency` 防止同一个业务动作重复创建任务。
- `idx_task_type_status` 用来查某类任务积压，`idx_task_target` 用来按业务对象反查任务。
- `task_outbox.status + next_retry_at` 是调度器扫描重试的关键索引。

### 团队知识摄取与索引

核心表：
- `knowledge_base`：团队知识库容器。
- `document_upload`：分片上传会话、文件 md5、总 chunk 数、任务 id、状态。
- `upload_chunk`：`upload_id + chunk_index` 唯一，记录每个分片对象。
- `file_object`：`space_id + content_hash` 唯一，管理 MinIO 对象和 `ref_count`。
- `document`：业务文档状态、`parse_status`、`index_status`、`active_index_version`、软删除字段。
- `document_chunk`：`document_id + index_version + chunk_index` 唯一，记录 chunk、页码、offset、ES doc id。

面试重点：
- `document` 是业务事实，`document_chunk` 是可追溯分片事实，ES 只是检索读模型。
- `active_index_version` 让重建索引失败时不破坏旧版本。
- `file_object` 按 space 隔离 content hash，避免跨空间秒传泄露文件存在性。

### Chat、RAG、证据和反馈

核心表：
- `chat_session`：用户、空间、session kind、scope snapshot、runtime status。
- `chat_session_scope`：会话绑定的 KB/Wiki/范围。
- `chat_message`：`session_id + message_seq` 唯一，保存对话顺序。
- `citation`：证据来源、chunk、页码、offset、quote、snapshot、source version。
- `message_citation`：消息和 citation 多对多，并可绑定 `retrieval_trace_id`。
- `retrieval_trace` / `retrieval_trace_item`：召回、融合、后处理、选中证据。
- `llm_call_log`：provider、model、prompt version、token、latency、scene。
- `answer_feedback`：`user_id + message_id` 唯一，避免重复反馈。

面试重点：
- Citation 不放在 message JSON 里，是为了独立权限校验、证据复用、Trace 排障和版本追溯。
- `retrieval_trace_item` 把“召回候选”和“最终 evidence”分开，能解释为什么答案错。
- `llm_call_log.scene` 和 `prompt_version_id` 能定位是哪条场景、哪版 prompt 产生的问题。

### 个人研究、Artifact、Wiki 和长期沉淀

核心表：
- `research_project`、`source`：个人研究项目和资料来源，Source 有 import/compile 两套状态。
- `article_card`、`concept_card`、`concept_alias`、`concept_relation`、`article_concept_relation`：个人知识结构化。
- `artifact`、`artifact_version`、`artifact_source`、`artifact_citation`、`session_artifact`：版本化产物和来源。
- `methodology_card`：方法论卡片，保存 workflow、output structure、quality checklist，并有 SYSTEM/SPACE/PROJECT 范围。
- `synthesis_card`、`artifact_distillation_proposal`、`artifact_card_relation`、`synthesis_card_citation`：用户确认后的个人沉淀。
- `wiki_page`、`wiki_page_version`、`wiki_page_citation`、`wiki_page_link`：团队长期知识、版本、证据和图谱链接。

面试重点：
- Artifact 和 Wiki/Synthesis 分开，是为了避免模型草稿自动污染长期知识。
- `artifact_version_id` 出现在 proposal、relation 和 synthesis 中，是为了确认用户沉淀的是某个确定版本。
- `wiki_page.published_version_id + index_status` 让发布事实和 ES 入索引状态分开。
- `wiki_page_link` 用 `source_page_id + target_title` 唯一，支持 unresolved link 和图谱修复。

### 记忆、评测和运维

核心表：
- `session_summary`、`space_memory`、`user_memory`、`memory_item`：分层长期记忆和可过期 memory item。
- `prompt_version`：scene + version 唯一，管理不同场景 Prompt。
- `rag_eval_case`、`rag_eval_run`、`rag_eval_result`：离线评测样本、执行批次和指标结果。
- `audit_log`、`ops_cleanup_job`、`ops_cleanup_item`、`system_health_snapshot`：审计、资源清理和健康快照。

面试重点：
- Memory 不是一张大表强塞所有内容，而是 session/space/user/item 分层，服务不同读取场景。
- RAG Eval 与正式 ChatSession 分离，避免评测污染用户会话和 Memory。
- cleanup 分 job/item 两层，scan 和 execute 可审计、可回放。

## 2. 高频深问与答法

### Q1: 为什么不用一张 JSON 大表保存所有 AI 结果？

答：AI 系统最需要追溯和边界。一张大 JSON 表短期开发快，但无法稳定回答“这个证据来自哪里、用户现在还有没有权限、哪个版本生成了这个 Artifact、哪个任务失败、哪个 prompt 产生坏答案”。NoteWeave 把事实拆成 message、citation、trace、artifact_version、wiki_version、synthesis、memory、eval_result，是为了让权限、版本、任务和排障都能被索引和查询。

### Q2: 为什么当前迁移里很少显式外键？

答：当前项目主要靠服务层校验、唯一索引、状态机和集成测试维护关系，比如 `space_id`、`document_id`、`artifact_id`、`citation_id` 都会在 Service 层做权限和存在性检查。这样在快速重构和异步任务里更灵活，也减少跨模块迁移耦合。面试可以补一句：如果进入更严格生产阶段，核心强关系可以逐步补 FK 或约束校验，但不能用 FK 替代权限和业务状态判断。

### Q3: 表索引设计怎么讲？

答：按查询路径讲，而不是背索引名。权限边界查 `space_id/status`，任务调度查 `task_type/task_status` 和 `task_outbox(status,next_retry_at)`，索引一致性查 `document_id/index_version`，RAG 排障查 `retrieval_trace(scene,created_at)` 和 `retrieval_trace_item(trace_id,rank_no)`，评测查 `rag_eval_run(space_id,created_at)`，运维查 `audit_log(action,created_at)` 和 `system_health_snapshot(component,checked_at)`。

### Q4: MySQL 和 ES 状态不一致怎么办？

答：MySQL 是事实源。先看 `document.status/active_index_version/index_status` 或 `wiki_page.index_status/published_version_id`，再看 ES 是否还有旧 doc。RAG 检索要过滤 space、knowledgeBase、lifecycleStatus、documentStatus，并校验 indexVersion 等于 activeIndexVersion。修复时可以重跑 DOCUMENT_REINDEX、WIKI_INDEX 或 cleanup，而不是直接信 ES。

### Q5: 为什么软删除这么多？

答：知识系统里历史消息、Citation、Trace、Wiki 版本和 Artifact 都可能引用旧资源。直接物理删除会破坏审计和排障。软删除先让业务查询和检索不可见，保留追溯；后续再由 cleanup scan/execute 根据引用和保留策略清理 MinIO/ES 残留。

## 3. 不能说满

- 不要说数据库已经做了分库分表、读写分离或线上压测。
- 不要说所有关系都由外键强约束；当前重点是服务层边界、唯一索引和状态机。
- 不要说 ES/Redis/Kafka 可以代替 MySQL 事实源。
- 不要说软删除后对象会立刻被物理清理。
