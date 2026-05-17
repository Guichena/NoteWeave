# NoteWeave Project Status

本文档用于给后续 AI 编码代理快速判断当前做到哪里。每次开始新阶段前先读本文档；每完成一个阶段后必须更新本文档。

更新时间：2026-05-17

---

## 1. 当前结论

当前状态：

```text
Phase 0/1、Phase 1.5、Phase 2、Phase 3、Phase 4、Phase 5、Phase 6、Phase 7、Phase 8、Phase 9、Phase 10 和 Phase 10.5 已完成并通过当前阶段测试与必要回归测试。
```

当前代码已包含 Auth/User/Space/Permission、Task/Outbox/Kafka Worker 基础设施、Phase 2 文件上传链路、Phase 3 的 DOCUMENT_PROCESS Worker、文档解析、parsed text 保存、Chunk 切片、indexVersion / activeIndexVersion、Elasticsearch BM25 索引和 Search Debug、Phase 6 的个人 ResearchProject / Source、TEXT/FILE/URL 导入、SOURCE_IMPORT Worker、个人 raw/parsed text 对象保存、owner-only 查询与重试/去重链路，以及 Phase 7 的 SOURCE_COMPILE、ArticleCard / ConceptCard / ConceptAlias / ConceptRelation / ArticleConceptRelation、个人 Card Citation、Card 搜索详情与 Evidence 回溯链路、Phase 8 的 Studio / Artifact、Phase 9 的混合检索 / RRF / 向量回退、Phase 10 的团队 Wiki 草稿/发布/版本/索引/检索闭环，以及 Phase 10.5 的 Methodology preset / matcher / prompt 注入骨架。

下一步：

```text
Phase 11: 个人 Wiki-based Generation。
```

---

## 2. AI 开工读取顺序

每个阶段开始前按这个顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/phase-prompts/{current_phase}_prompt.md
docs/features/{current_phase}.md
```

如果文档冲突，优先级固定为：

```text
PROJECT_STATUS
CONTRACT
DOCKER_MIDDLEWARE
implementation_breakdown
database_api_blueprint
phase prompt
phase document
```

所有编程阶段默认使用测试驱动开发：

```text
先写测试
运行并确认测试失败
再写实现
重构
最后运行当前阶段测试和必要回归测试
```

以下文档只作为背景或历史审查记录，不作为实现权威：

```text
docs/note_weave_功能说明与架构文档.md
docs/features/noteweave_full_arch_review.md
docs/architecture_review_issues_and_recommendations.md
```

---

## 3. 已完成的文档准备

已完成：

```text
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_10_5_methodology_preset_matcher.md
docs/features/phase_11_5_personal_artifact_distillation.md
docs/phase-prompts/*
docker-compose.yml
.env.example
```

关键契约已定：

```text
所有 API 使用 /api/v1
所有中间件通过 Docker Compose 或 Testcontainers 提供
测试临时路径统一使用 target/noteweave-test/{phase}/
Space 是最高业务容器
users.system_role = USER / ADMIN
refresh token 使用 user_session
异步任务统一使用 task / task_attempt / task_event / task_outbox
task.status 包含 TIMEOUT
Artifact 默认不进入 Wiki
团队 Artifact 人工发布为 WikiPage 后进入团队 RAG Index
个人 Artifact 用户确认后 MVP 只沉淀为 SynthesisCard
Citation / Evidence 必须可回溯，Card 证据不能只放 JSON
Quiz / 答题 / 评分 / 题库暂缓
```

---

## 4. 阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| 文档契约整理 | DONE | 契约、蓝图、Docker 中间件契约、Phase prompt 已整理 |
| Phase 0/1 | DONE | 工程骨架、认证、用户、空间、权限代码已完成，并通过回归测试 |
| Phase 1.5 | DONE | Task / Outbox / Worker 代码已完成，并通过回归测试 |
| Phase 2 | DONE | 文件上传与异步摄取，MinIO / Kafka / TaskOutbox 链路已完成并通过回归测试 |
| Phase 3 | DONE | 文档解析、Chunk、ES BM25 索引、版本切换与幂等处理已完成并通过测试 |
| Phase 4 | DONE | 团队 RAG Chat、Citation、最小 RetrievalTrace / LLMCallLog / AnswerFeedback 已完成并通过测试 |
| Phase 5 | DONE | WebSocket Chat Runtime |
| Phase 6 | DONE | 个人 ResearchProject / Source、SOURCE_IMPORT、个人导入入口已完成并通过测试 |
| Phase 7 | DONE | 个人 Wiki Compiler、SOURCE_COMPILE、ArticleCard / ConceptCard / Citation / Evidence 回溯已完成并通过测试 |
| Phase 8 | DONE | Studio / Artifact |
| Phase 9 | DONE | 检索增强 / RRF |
| Phase 10 | DONE | 团队 Wiki 发布入索引 |
| Phase 10.5 | DONE | Methodology 预置模板与 Matcher |
| Phase 11 | PENDING | 个人 Wiki-based Generation |
| Phase 11.5 | PENDING | 个人 Artifact 沉淀为 SynthesisCard |
| Phase 12 | PENDING | Long-term Memory |
| Phase 13 | PENDING | MethodologyCard 完整管理 |
| Phase 14 | PENDING | Evaluation / Observability |
| Phase 15 | PENDING | Admin / Ops |
| Phase 16 | PENDING | Frontend Workspace |

---

## 5. 暂缓范围

当前不要实现：

```text
Quiz
测验答题
评分
题库
错题复习
外部研究资料自动发现
商业化计费
复杂企业审批流
复杂多人实时协同编辑
```

---

## 6. 每阶段完成后更新格式

完成一个阶段后，把本文档中的阶段状态更新为 `DONE`，并补充：

```text
完成阶段：
完成日期：
主要改动：
新增表：
新增 API：
测试命令：
测试结果：
遗留风险：
下一阶段：
```

如果某阶段只完成一部分，状态使用：

```text
IN_PROGRESS
```

不要把未完成阶段标为 `DONE`。

---

## 7. 测试驱动要求

每个编程阶段完成时，必须在本文档的阶段记录中更新：

```text
实现后运行了哪些测试命令
测试结果是什么
哪些场景暂时只能手动验证
```

阶段状态不能只因为代码写完就标为 `DONE`；必须完成验证后才能标为 `DONE`。

---

## 8. 最近验证记录

完成阶段：
```text
文档契约整理
Docker 中间件契约整理
Phase 阶段边界冲突复查
Phase 0/1 验证
Phase 1.5 验证
```

完成日期：
```text
2026-05-15
```

主要改动：
```text
补齐 Docker Compose 中间件：MySQL / Redis / MinIO / Elasticsearch / Kafka
补齐 .env.example
补齐 docs/DOCKER_MIDDLEWARE.md
同步 application.yml 与 test application.yml 的容器化中间件配置
阶段提示词与执行契约增加 TDD 和 Docker/Testcontainers 要求
补齐 Phase 10.5 和 Phase 11.5 feature 文档
收口 Phase 8 / Phase 11 / Phase 11.5 的 Artifact -> Wiki 边界
统一 Studio 生成任务为 ARTIFACT_GENERATE + params.artifactType
统一 Phase 2 上传对象 key 与 Docker dev/test 前缀契约
```

测试命令：
```text
docker compose config --quiet
git diff --check
Markdown code fence balance check
mvn test
```

测试结果：
```text
docker compose config 校验通过
git diff --check 通过，仅有 CRLF 换行提示
Markdown code fence balance check 通过
mvn test 通过：Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
```

下一阶段：
```text
Phase 3: 文档解析、Chunk 切片与索引
```

## 9. Phase 2 Progress (2026-05-15)

Status:

```text
DONE
```

Implemented in this update:

```text
1) KnowledgeBase DELETE uses archive/soft-delete semantics only.
2) Document DELETE uses soft-delete semantics only.
3) merge is idempotent: replay existing documentId/taskId/status/objectKey for repeated merge on same uploadId.
4) refCount hard rule: increment only after Document is created and bound to FileObject; no decrement in Phase 2 soft-delete.
5) DOCUMENT_PROCESS is routed to Kafka document topic; NOOP_TEST and generic tasks route through Kafka task topic and TaskKafkaConsumer.
6) content_hash is computed server-side as SHA-256 during merge.
7) upload chunks are persisted to MinIO with dev/test prefix conventions.
```

Phase 2 related tests added/updated:

```text
Phase2UploadFlowIntegrationTest
- viewerShouldNotInitUpload
- deleteKnowledgeBaseShouldArchiveInsteadOfPhysicalDelete
- mergeShouldBeIdempotentAndNotCreateDuplicateDocumentTaskOrOutbox
- initUploadShouldInstantReuseFileObjectWithinSameSpaceButCreateNewDocumentAndTask
- initUploadShouldNotInstantReuseFileAcrossDifferentSpace
- uploadChunkAndMergeShouldPersistAndCleanupMinioObjects
- cancelUploadShouldCleanupChunkObjectsAndBitmapOnly
- deleteDocumentShouldBeSoftDeleteAndMustNotDecreaseRefCount
- cleanupExpiredUploadShouldDeleteOnlyTempChunksAndBitmap
```

Test commands and results:

```text
1) mvn "-Dtest=Phase2UploadFlowIntegrationTest#uploadChunkAndMergeShouldPersistAndCleanupMinioObjects,Phase2UploadFlowIntegrationTest#cancelUploadShouldCleanupChunkObjectsAndBitmapOnly" test
   - initial run failed as expected (chunk persistence gap), then passed after implementation.

2) mvn "-Dtest=Phase2UploadFlowIntegrationTest,TaskServiceIntegrationTest,TaskControllerTest,SpaceControllerTest,StoragePropertiesValidatorTest" test
   - passed: Tests run: 25, Failures: 0, Errors: 0, Skipped: 0

3) mvn test
   - passed: Tests run: 42, Failures: 0, Errors: 0, Skipped: 0

4) docker compose config --quiet
   - passed
```

Notes:

```text
- Phase 2 keeps Elasticsearch out of runtime/testing dependencies.
- Kafka dispatch failures keep outbox retryable and do not roll back business transaction.
- Physical reclamation of merged FileObject and refCount decrement are deferred to later cleanup phases.
- Temporary upload chunk objects, Redis bitmap state, and upload_chunk rows are cleaned after merge/cancel/expiration.
- UploadCleanupScheduler periodically scans and cleans expired uploads outside tests.
```

Next:

```text
Proceed to Phase 3 document parsing and indexing.
```

## 12. Phase 4 Team RAG Chat / Citation (2026-05-15)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added TEAM_CHAT session/message persistence with chat_session, chat_session_scope and chat_message.
2) Implemented ChatSessionService and ChatController endpoints for creating sessions, listing sessions, reading session detail and reading message history.
3) Implemented TeamChatService RAG flow: requireAskQuestion -> save USER message -> resolve session scope -> BM25 retrieve -> evidence post-process -> no-evidence fallback or LLM answer -> save ASSISTANT message -> save citations -> return answer/citations.
4) Implemented BM25 retrieval with Elasticsearch filters on spaceId and scoped knowledgeBaseIds, plus MySQL recheck on document lifecycle and activeIndexVersion.
5) Implemented EvidencePostProcessor with dedupe, adjacent chunk merge, per-document limiting, score ordering and context truncation.
6) Implemented TeamRagPromptBuilder with grounded-answer rules, citation numbering and prompt-injection-resistant evidence formatting.
7) Added LLM client abstraction, configurable OpenAI-compatible WebClient client and test StubLlmClient; missing API key without stub now fails with LLM_CONFIG_MISSING.
8) Added citation and message_citation persistence; citations store pageNo/startOffset/endOffset/quoteHash/snapshotObjectKey/sourceVersion and are not embedded into message JSON.
9) Citation query path performs second permission check before returning message citations.
10) Added minimal retrieval_trace, llm_call_log and answer_feedback persistence plus feedback submit API.
```

New migration:

```text
src/main/resources/db/migration/V5__phase_4_team_rag_chat_citation.sql
```

New APIs:

```text
POST /api/v1/chat/sessions
GET /api/v1/spaces/{spaceId}/chat-sessions
GET /api/v1/chat/sessions/{sessionId}
GET /api/v1/chat/sessions/{sessionId}/messages
POST /api/v1/chat/sessions/{sessionId}/messages
GET /api/v1/chat/messages/{messageId}/citations
POST /api/v1/chat/messages/{messageId}/feedback
```

RAG request flow:

```text
requireAskQuestion(spaceId)
-> save USER chat_message
-> resolve session scope to visible knowledgeBaseIds
-> BM25 retrieve from Elasticsearch with spaceId/knowledgeBaseId filters
-> MySQL recheck document status and activeIndexVersion
-> EvidencePostProcessor merge/dedupe/limit/truncate
-> if empty: return explicit "暂无相关信息" fallback and empty citations
-> else TeamRagPromptBuilder builds grounded prompt
-> LLMClient or StubLLMClient generates answer
-> save ASSISTANT chat_message
-> CitationService saves citation + message_citation + snapshot object
-> return answer and citations
```

Permission filter points:

```text
1) Create/list/read session: requireViewSpace
2) Ask question: requireAskQuestion
3) Retrieval prefilter: Elasticsearch query filters by spaceId and scoped knowledgeBaseIds
4) Retrieval post-check: MySQL validates document status != deleted and activeIndexVersion matches chunk indexVersion
5) Citation return: requireViewSpace on session space, then requireViewSpace again on each citation.spaceId before returning
```

Citation save and query:

```text
- Citation records are stored in citation.
- Assistant message associations are stored in message_citation.
- Citation fields persisted: pageNo, startOffset, endOffset, quoteHash, snapshotObjectKey, sourceVersion.
- Snapshot object keys use dev/citations/{citationId}/snapshot.txt in local dev, and test/{testRunId}/citations/{citationId}/snapshot.txt in tests.
- Message JSON does not carry citation ids.
- Query API resolves citations through message_citation and rechecks permissions before returning.
```

TDD record:

```text
1) Wrote EvidencePostProcessorTest, TeamRagPromptBuilderTest and Phase4TeamRagIntegrationTest before implementation.
2) Initial red run failed because Phase 4 production classes did not exist yet, matching the expected TDD failure.
3) Implemented minimal chat/RAG/citation/LLM/observability code to satisfy the new tests.
4) Added boundary handling for no-evidence fallback, cross-space isolation, permission-protected citation lookup and missing LLM config fallback.
5) Re-ran current-phase tests and regression tests to green.
```

Test commands and results:

```text
1) mvn "-Dtest=EvidencePostProcessorTest,TeamRagPromptBuilderTest,Phase4TeamRagIntegrationTest" test
   - initial red failed as expected because Phase 4 production classes were not implemented yet.

2) mvn "-Dtest=EvidencePostProcessorTest,TeamRagPromptBuilderTest" test
   - passed

3) mvn "-Dtest=Phase4TeamRagIntegrationTest" test
   - passed

4) mvn "-Dtest=Phase4TeamRagIntegrationTest,Phase3DocumentProcessingIntegrationTest,Phase2UploadFlowIntegrationTest,TaskServiceIntegrationTest,TaskControllerTest,SpaceControllerTest,StoragePropertiesValidatorTest" test
   - passed: Tests run: 33, Failures: 0, Errors: 0, Skipped: 0

5) docker compose config --quiet
   - passed

6) git diff --check
   - passed; only CRLF line-ending warnings were printed

7) mvn test
   - passed: Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- Phase 4 intentionally does not implement WebSocket streaming, DRAFT session resume, vector recall, Wiki retriever, personal Wiki, Artifact generation or Quiz.
- Test environment uses StubLLMClient and does not depend on an external LLM provider.
- No-evidence answers must be explicit and return no fabricated citations.
- Citation pageNo currently defaults to 1 when chunk page metadata is absent, to keep citation responses structurally complete.
```

Next:

```text
Proceed to Phase 5 WebSocket Chat Runtime.
```

## 13. Phase 5 Workspace Chat Runtime (2026-05-16)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added WebSocket runtime baseline: POST /api/v1/chat/ws-ticket and WebSocket /ws/chat/{ticket} with one-time Redis ticket consumption.
2) Extended chat_session with draft_status and chat_message with status, plus V6 migration for Phase 5 runtime fields.
3) Added FORMAL / DRAFT session creation support, convert-to-formal and discard-draft APIs, and DRAFT lifecycle states DRAFT_ACTIVE / DRAFT_EXPIRED / CONVERTED / DISCARDED.
4) Added Redis-backed runtime state store for runtime / short_term / stream / events with seq/ack/resume support and 2-hour TTL.
5) Implemented runtime event envelope and event flow: chat.connected / chat.started / chat.delta / chat.completed / chat.stopped / chat.failed / chat.restored.
6) Implemented ActiveExecutionRegistry and stop flow so stop requests halt further delta emission and preserve partialContent in Redis runtime state.
7) Implemented refresh recovery via chat.resume: replay Redis-buffered events after ack and return restored runtimeStatus / partialContent.
8) Reused Phase 4 retrieval, prompt, LLM and citation stack for WebSocket runtime so HTTP chat and WS chat keep the same permission, evidence and citation semantics.
9) Kept DRAFT out of long-term memory writeback scope; ContextReadRouter for Phase 5 only enables recent history and retrieval evidence.
10) Added real WebSocket integration coverage against RANDOM_PORT plus Redis/Testcontainers runtime verification.
```

New migration:

```text
src/main/resources/db/migration/V6__phase_5_workspace_chat_runtime.sql
```

New APIs:

```text
POST /api/v1/chat/ws-ticket
POST /api/v1/chat/sessions/{sessionId}/convert-to-formal
POST /api/v1/chat/sessions/{sessionId}/discard-draft
WebSocket /ws/chat/{ticket}
```

WS runtime flow:

```text
POST /api/v1/chat/ws-ticket
-> Redis one-time ticket (60s TTL)
-> WebSocket handshake /ws/chat/{ticket}
-> chat.connected
-> client chat.message
-> save USER message
-> runtime_status = RUNNING
-> retrieve evidence + build prompt + generate answer
-> append chat.started / chat.delta* / chat.completed to Redis event buffer
-> FORMAL session saves ASSISTANT message + citations
-> runtime_status = IDLE
```

Stop / resume / draft rules:

```text
- stop: chat.stop marks ActiveExecution stopRequested, stops further delta, keeps partialContent in Redis, sets chat_session.runtime_status = STOPPED and emits chat.stopped.
- resume: chat.resume replays Redis events after ack and emits chat.restored with runtimeStatus + partialContent.
- draft expire: expireDrafts marks DRAFT_ACTIVE -> DRAFT_EXPIRED after TTL.
- convert: only DRAFT_ACTIVE can convert to FORMAL and becomes draft_status = CONVERTED.
- discard: only DRAFT_ACTIVE can discard and becomes draft_status = DISCARDED.
- DRAFT does not write long-term memory in this phase.
```

TDD record:

```text
1) Wrote WebSocketTicketServiceTest, ChatRuntimeStateStoreTest, ContextReadRouterTest and Phase5WorkspaceChatRuntimeIntegrationTest before implementation.
2) Initial red run failed as expected on missing Phase 5 runtime classes, missing draft/message status fields and missing WebSocket APIs.
3) Implemented minimal migration, Redis runtime store, ws-ticket flow, WebSocket handler and runtime service to satisfy the new tests.
4) Added boundary handling for async stop, resume replay after ack, one-time ticket consumption and draft expiration/convert/discard state checks.
5) Re-ran Phase 5 targeted tests and Phase 4/3/2 regression tests to green.
```

Test commands and results:

```text
1) mvn "-Dtest=WebSocketTicketServiceTest,ChatRuntimeStateStoreTest,ContextReadRouterTest,Phase5WorkspaceChatRuntimeIntegrationTest" test
   - initial red failed as expected because Phase 5 runtime classes and fields did not exist yet.

2) mvn "-Dtest=Phase5WorkspaceChatRuntimeIntegrationTest" test
   - passed

3) mvn "-Dtest=WebSocketTicketServiceTest,ChatRuntimeStateStoreTest,ContextReadRouterTest,Phase5WorkspaceChatRuntimeIntegrationTest,Phase4TeamRagIntegrationTest,Phase4TeamRagLlmFailureIntegrationTest,Phase3DocumentProcessingIntegrationTest,Phase2UploadFlowIntegrationTest,TaskServiceIntegrationTest,TaskControllerTest,SpaceControllerTest,StoragePropertiesValidatorTest" test
   - passed: Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- Phase 5 runtime events are buffered in Redis only for temporary seq/ack/resume recovery; Kafka remains the only background async task queue.
- WebSocket runtime reuses the Phase 4 retrieval and citation path, so WS and HTTP chat stay aligned on permissions and evidence semantics.
- Current stop semantics intentionally preserve partialContent in Redis without saving a partial assistant message row.
- Long-term memory writeback remains deferred to Phase 12.
```

Next:

```text
Proceed to Phase 6 personal ResearchProject and Source import.
```

## 10. Phase 2 Final Regression (2026-05-15)

Test command:

```text
mvn "-Dtest=Phase2UploadFlowIntegrationTest,TaskServiceIntegrationTest,TaskControllerTest,SpaceControllerTest,StoragePropertiesValidatorTest" test
mvn test
docker compose config --quiet
```

Result:

```text
BUILD SUCCESS
Targeted tests run: 25, Failures: 0, Errors: 0, Skipped: 0
Full tests run: 42, Failures: 0, Errors: 0, Skipped: 0
Docker Compose config validation passed
```

Notes:

```text
Fixed test baseline Kafka producer serializer in src/test/resources/application.yml so DOCUMENT_PROCESS outbox dispatch is SENT in Phase 2 tests.
DOCUMENT_PROCESS remains Kafka-only dispatch path.
NOOP_TEST and future generic task types use task_outbox -> Kafka noteweave.task -> TaskKafkaConsumer -> task/task_attempt/task_event.
TaskOutboxDispatchScheduler periodically compensates pending/failed outbox records outside tests.
DocumentProcess Kafka payload includes taskId in both Kafka key and payload body.
Upload status for MERGED/PROCESSING/INDEXED reports 100% progress after bitmap cleanup.
Prod/staging profiles reject default MinIO credentials.
Expired upload cleanup has a scheduler and remains disabled in tests for deterministic manual verification.
```

## 11. Phase 3 Document Processing / Chunk / Indexing (2026-05-15)

Status:

```text
DONE
```

Implemented in this update:

```text
1) DOCUMENT_PROCESS Worker only consumes taskId from Kafka, then loads task/document state from DB before execution.
2) DocumentParser supports .txt, .md/.markdown and .pdf; unsupported extensions such as .docx are rejected at upload init, including fake text/plain DOCX uploads.
3) Parsed text is saved to MinIO as test/{testRunId}/parsed-text/document/{documentId}/{indexVersion}.txt or dev/parsed-text/document/{documentId}/{indexVersion}.txt.
4) document_chunk was added with unique constraint (document_id, index_version, chunk_index), chunk metadata and ES document id.
5) indexVersion creates a new immutable chunk/index generation; active_index_version switches only after parse, chunk persistence and ES indexing all succeed.
6) Repeated task consumption is idempotent: SUCCESS tasks with active chunks are skipped, and chunk creation reuses an existing documentId/indexVersion set.
7) Reindex tasks use DOCUMENT_PROCESS:{documentId}:REINDEX:{nextIndexVersion}; failed reindex keeps the old active version searchable.
8) Search Debug uses ES BM25 with spaceId, knowledgeBaseId and lifecycleStatus filters, then rechecks MySQL document status, deletion and activeIndexVersion.
9) Document delete removes matching ES docs and archived KB paths do not return indexed chunks.
10) Processing failures update Document / Task / TaskAttempt and rethrow to Kafka so broker retry / DLT policy can take over.
11) PDF parser tests verify real PDF text extraction and pageCount metadata; Markdown chunks preserve sectionTitle metadata.
```

New migration:

```text
src/main/resources/db/migration/V4__phase_3_document_processing_indexing.sql
```

New APIs:

```text
GET /api/v1/team/documents/{documentId}/chunks
POST /api/v1/team/documents/{documentId}/reindex
GET /api/v1/team/knowledge-bases/{knowledgeBaseId}/search?keyword=...
```

TDD record:

```text
1) Wrote DocumentParserServiceTest, ChunkServiceTest and Phase3DocumentProcessingIntegrationTest before implementation.
2) Initial red run failed on missing Phase 3 production classes.
3) Added extra red tests for octet-stream parsing, DOCX upload rejection, reindex version switch and failed reindex preserving old active version.
4) Implemented minimal parser/chunk/index/worker/reindex behavior and reran tests to green.
```

Test commands and results:

```text
1) mvn "-Dtest=DocumentParserServiceTest,Phase3DocumentProcessingIntegrationTest" test
   - initial red after adding stricter tests: parser octet-stream and DOCX upload rejection failed as expected.

2) mvn "-Dtest=Phase3DocumentProcessingIntegrationTest#failedReindexShouldKeepOldActiveVersionSearchable" test
   - initial red showed failed reindex changed Document status to FAILED; passed after preserving old active version.

3) mvn "-Dtest=DocumentParserServiceTest,ChunkServiceTest,Phase3DocumentProcessingIntegrationTest" test
   - initial red after risk-closure tests: failure rethrow, PDF pageCount metadata and Markdown sectionTitle assertions failed as expected.
   - passed after implementation: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0

4) mvn "-Dtest=Phase2UploadFlowIntegrationTest,TaskServiceIntegrationTest,TaskControllerTest,SpaceControllerTest,StoragePropertiesValidatorTest" test
   - passed: Tests run: 25, Failures: 0, Errors: 0, Skipped: 0

5) docker compose config --quiet
   - passed

6) git diff --check
   - passed; only CRLF line-ending warnings were printed

7) mvn test
   - passed: Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- Phase 3 intentionally does not implement RAG Chat, LLM answers, Citation, embeddings/vector recall, personal Source, Artifact or Quiz.
- Elasticsearch is accessed through a small HTTP service for BM25 indexing/querying; Testcontainers provides Elasticsearch for integration tests.
- Test temporary path remains target/noteweave-test/{phase}/ by contract; MinIO object keys are under test/{testRunId}/...
- Phase 4 retrievers must keep the same spaceId / knowledgeBaseId / lifecycleStatus ES filters and MySQL active document/version recheck when building RAG retrieval.
```

Next:

```text
Proceed to Phase 4 team RAG Chat and Citation.
```

## 14. Phase 6 Personal ResearchProject / Source Import (2026-05-16)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added personal/common, personal/project and personal/source domains so ResearchProject and Source are always resolved inside the current user's ACTIVE PERSONAL space.
2) Added V7 migration with research_project and source tables, including soft delete fields, import/compile status, error_message and source object key fields.
3) Added owner-only personal APIs for ResearchProject CRUD, Source FILE/URL/TEXT import, source list/detail/delete and explicit re-import.
4) TEXT sources now write raw_text_object_key immediately, compute content_hash and become READY without creating a task.
5) FILE sources now store the original object in MinIO, create SOURCE_IMPORT tasks, reuse DocumentParserService and only accept .txt/.md/.markdown/.pdf in the MVP path.
6) URL sources now normalize URL, validate through a safe fetch layer, create SOURCE_IMPORT tasks, and fail on non-2xx, empty body, extraction failure, unsafe redirect, timeout or oversized response without ever marking READY.
7) The safe URL fetch layer now enforces http/https only, blocks localhost / loopback / private / link-local targets, re-validates every redirect hop, limits redirect count and caps response body size.
8) Re-import now reuses an existing PENDING/RUNNING SOURCE_IMPORT task when present; otherwise it creates SOURCE_IMPORT:{sourceId}:attempt:{n}. This is a new business import attempt, distinct from generic /tasks/{taskId}/retry.
9) Dedup now follows the Phase 6 contract: FILE/TEXT dedup at create time by content_hash; URL pre-dedups by normalized URL and content-level canonicalizes in the worker under project lock, soft-deleting the duplicate source and pointing task resultRef to the canonical source.
10) Project archive now performs archive + soft delete semantics and also soft-deletes child sources; hidden/archived parent projects make source detail and re-import unavailable, and SOURCE_IMPORT skips inactive parents or deleted sources.
```

New migration:

```text
src/main/resources/db/migration/V7__phase_6_personal_research_source.sql
```

New tables:

```text
research_project
source
```

New APIs:

```text
POST /api/v1/personal/research-projects
GET /api/v1/personal/research-projects
GET /api/v1/personal/research-projects/{projectId}
PUT /api/v1/personal/research-projects/{projectId}
DELETE /api/v1/personal/research-projects/{projectId}
POST /api/v1/personal/research-projects/{projectId}/sources/upload
POST /api/v1/personal/research-projects/{projectId}/sources/url
POST /api/v1/personal/research-projects/{projectId}/sources/text
GET /api/v1/personal/research-projects/{projectId}/sources
GET /api/v1/personal/sources/{sourceId}
POST /api/v1/personal/sources/{sourceId}/import
DELETE /api/v1/personal/sources/{sourceId}
```

TDD record:

```text
1) Wrote Phase6PersonalResearchSourceIntegrationTest before implementation.
2) Initial red run failed as expected because the /api/v1/personal research-project/source endpoints and Phase 6 import flow did not exist yet.
3) Added SafeUrlContentFetcherTest plus new Phase 6 red cases for unsafe URL rejection, unsupported file types and archived-parent skip behavior; the first run failed as expected because the safe URL fetch layer and new boundaries did not exist yet.
4) Implemented the migration, personal-space lookup, owner-only CRUD, source storage support, SOURCE_IMPORT worker and re-import semantics to satisfy the initial Phase 6 suite.
5) Added boundary handling for READY text constraints, safe URL fetching, URL failure state persistence, project-locked URL canonicalization, archived project write blocking, deleted/inactive parent skip behavior, and re-import driven compile-status invalidation.
6) Re-ran the Phase 6 suite and required regressions to green.
```

Test commands and results:

```text
1) mvn "-Dtest=Phase6PersonalResearchSourceIntegrationTest" test
   - initial red failed as expected because Phase 6 personal endpoints/domain were missing.

2) mvn "-Dtest=Phase6PersonalResearchSourceIntegrationTest" test
   - passed: Tests run: 9, Failures: 0, Errors: 0, Skipped: 0

3) mvn "-Dtest=Phase6PersonalResearchSourceIntegrationTest,SafeUrlContentFetcherTest" test
   - initial red failed as expected because UrlContentFetcher / FetchedUrlContent / UrlFetchTransportResponse and the safe URL boundary were not implemented yet.

4) mvn "-Dtest=SafeUrlContentFetcherTest,Phase6PersonalResearchSourceIntegrationTest" test
   - passed: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0

5) mvn "-Dtest=SafeUrlContentFetcherTest,Phase6PersonalResearchSourceIntegrationTest,TaskServiceIntegrationTest,TaskControllerTest,SpaceServiceTest,DocumentParserServiceTest,Phase3DocumentProcessingIntegrationTest" test
   - passed: Tests run: 36, Failures: 0, Errors: 0, Skipped: 0

6) mvn "-Dtest=Phase6PersonalResearchSourceIntegrationTest,Phase7PersonalWikiCompilerIntegrationTest" test
   - passed: Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- READY 的充分必要条件仍然是 raw_text_object_key 或 parsed_text_object_key 至少一个非空且对象可读；原始文件 object_key 不能单独让 Source 进入 READY。
- SOURCE_IMPORT 复用现有 task / outbox / Kafka worker 链路，不新增专用 topic；DOCUMENT_PROCESS 现有专用链路未改动。
- URL 集成测试使用 mock UrlContentFetcher，不依赖外网；新增 SafeUrlContentFetcherTest 专门覆盖 SSRF / redirect / size-limit 安全边界。
- 测试 profile 下 dispatcher scheduler 关闭，因此 Phase 6 异步流转测试统一通过 taskDispatcher.dispatchPendingMessages() 显式驱动。
- READY URL re-import now performs a true fresh fetch, writes a new raw-text attempt object, and resets source/project compile status back to PENDING before any recompile.
- compile_status 在 Phase 6 仅保留字段与默认值 PENDING，未实现 SOURCE_COMPILE。
```

Next:

```text
Proceed to Phase 7 personal Wiki Compiler.
```

## 15. Phase 7 Personal Wiki Compiler / Cards (2026-05-16)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added V8 migration with article_card, concept_card, concept_alias, concept_relation, article_concept_relation, article_card_citation and concept_card_citation.
2) Added owner-only personal card APIs for article/concept list/detail, concept manual update and concept manual merge.
3) Implemented SOURCE_COMPILE create endpoint and reused the generic task/outbox/Kafka worker chain for asynchronous compile execution.
4) WikiCompilerService now loads Source parsed/raw text, requests strict LLM JSON, parses ArticleCard + Concept output, and writes diagnostic failure state back to Source and Task when compile fails.
5) ArticleCard persistence is idempotent per source, saves summary/key points/tags/evidenceQuotesJson display cache, and writes formal evidence relations through article_card_citation.
6) Concept merge is limited to the same research_project_id, matches by normalized name or alias only, keeps old evidence/citations/article links on merge, and creates a new ConceptCard when confidence is low or no in-project match exists.
7) Added EvidenceBacktraceService so article/concept evidence is verified against Source text; backtrace offsets/sourceVersion are returned, while missing quotes reduce concept confidence instead of silently fabricating evidence.
8) Added personal card citation query service on top of the shared citation table with SOURCE sourceType and relation tables as the formal evidence source.
9) Added project/source compile status updates so successful SOURCE_COMPILE sets Source.compileStatus = READY and recomputes ResearchProject.compileStatus, while failures persist diagnosable errorMessage values.
10) Source compile explicitly does not create SynthesisCard, Artifact outputs, Methodology cards or Quiz content in this phase.
```

New migration:

```text
src/main/resources/db/migration/V8__phase_7_personal_wiki_compiler_cards.sql
```

New tables:

```text
article_card
concept_card
concept_alias
concept_relation
article_concept_relation
article_card_citation
concept_card_citation
```

New APIs:

```text
POST /api/v1/personal/sources/{sourceId}/compile
GET /api/v1/personal/research-projects/{projectId}/article-cards
GET /api/v1/personal/article-cards/{cardId}
GET /api/v1/personal/research-projects/{projectId}/concept-cards
GET /api/v1/personal/concept-cards/{cardId}
PUT /api/v1/personal/concept-cards/{cardId}
POST /api/v1/personal/concept-cards/merge
```

Compile flow:

```text
POST /api/v1/personal/sources/{sourceId}/compile
-> verify owner-only personal source access and READY readable text
-> create Task(type=SOURCE_COMPILE) and mark source/project compile status as compiling
-> generic task outbox dispatch to Kafka noteweave.task / test.noteweave.task.{testRunId}
-> SourceCompileTaskWorker loads Source text (parsed first, raw fallback)
-> WikiCompilerService requests strict ArticleCard JSON and Concept JSON from LLM
-> parse JSON, save/update ArticleCard, create/merge ConceptCard, save aliases/relations/article links
-> write citation + article_card_citation / concept_card_citation as formal evidence
-> backtrace evidence against source text and return offsets/sourceVersion in card payloads
-> success: Source.compileStatus=READY, Task=SUCCESS, recompute ResearchProject.compileStatus
-> failure: Source.compileStatus=FAILED, Task=FAILED, persist error_message for diagnostics
```

Concept merge and evidence rules:

```text
- Auto-merge only runs inside the same research_project_id.
- Merge match is based on normalized name or normalized alias; cross-project concepts stay isolated.
- Low-confidence or unmatched concepts create new ConceptCard records instead of overwriting existing ones.
- Manual merge is also limited to one research project and returns CONCEPT_MERGE_INVALID for cross-project requests.
- Merging keeps prior concept_card_citation rows, article_concept_relation rows and aliases; evidence is appended/moved, not discarded.
- evidenceQuotesJson remains a display cache only. Formal evidence is the citation table plus article_card_citation / concept_card_citation relation tables.
```

TDD record:

```text
1) Wrote Phase7PersonalWikiCompilerIntegrationTest before implementation to cover compile success, readable text precondition, invalid LLM JSON failure and in-project concept dedup/isolation.
2) Initial red run failed as expected because SOURCE_COMPILE endpoints, personal card models/APIs and compile worker flow did not exist yet.
3) Implemented the minimal migration, task worker, compiler service, card persistence, concept merge, citation persistence and evidence backtrace path to satisfy the new suite.
4) Added follow-up red coverage for concept related article title resolution plus manual concept update/merge behavior, then fixed the implementation and re-ran to green.
5) Added regression coverage for compile rollback atomicity, archived-parent direct card access, manual merge field/evidence preservation, retry-time source readiness revalidation, and cross-class Kafka/mock test isolation.
6) Re-ran Phase 7 targeted tests and required Phase 6 / Phase 4 / document-task regressions to green.
```

Test commands and results:

```text
1) mvn "-Dtest=Phase7PersonalWikiCompilerIntegrationTest" test
   - initial red failed as expected before Phase 7 production code existed.

2) mvn "-Dtest=Phase7PersonalWikiCompilerIntegrationTest#compileShouldCreateArticleConceptCardsAndBacktraceableCitations" test
   - red after adding related article title assertion: expected Vector retrieval notes but got Article 1.
   - passed after wiring ConceptCard related article titles to real ArticleCard titles.

3) mvn "-Dtest=Phase7PersonalWikiCompilerIntegrationTest#manualConceptUpdateAndMergeShouldPreserveEvidenceAndRejectCrossProjectMerge" test
   - passed.

4) mvn "-Dtest=Phase7PersonalWikiCompilerIntegrationTest" test
   - passed: Tests run: 9, Failures: 0, Errors: 0, Skipped: 0

5) mvn "-Dtest=Phase6PersonalResearchSourceIntegrationTest" test
   - passed: Tests run: 9, Failures: 0, Errors: 0, Skipped: 0

6) mvn "-Dtest=Phase4TeamRagIntegrationTest" test
   - passed: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0

7) mvn "-Dtest=TaskServiceIntegrationTest,TaskControllerTest,SpaceControllerTest,DocumentParserServiceTest,Phase3DocumentProcessingIntegrationTest,StoragePropertiesValidatorTest" test
   - passed: Tests run: 26, Failures: 0, Errors: 0, Skipped: 0

8) mvn "-Dtest=Phase6PersonalResearchSourceIntegrationTest,Phase7PersonalWikiCompilerIntegrationTest" test
   - passed: Tests run: 18, Failures: 0, Errors: 0, Skipped: 0

9) mvn test
   - passed: Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- SOURCE_COMPILE reuses the existing generic task/outbox/Kafka worker path; Phase 7 does not introduce a new dedicated Kafka topic.
- Evidence backtrace prefers Source.parsedTextObjectKey and falls back to rawTextObjectKey when needed.
- Personal card citations use citation.sourceType = SOURCE and dedupe by (spaceId, sourceType, sourceId, quoteHash).
- Card list/detail and manual concept operations are owner-only through the current user's PERSONAL space, and archived parent projects now hide direct article/concept detail/update paths.
- Failed SOURCE_COMPILE now rolls back partial ArticleCard / citation writes before persisting FAILED state, and manual/auto concept merge preserves prior useCases, misunderstandings and evidence caches instead of overwriting them.
- Full-suite regression initially exposed shared Kafka consumer-group contention across cached Spring test contexts; all ContainerizedIntegrationTest-based Spring integration classes now close their context after each class so async workers do not steal each other's messages during `mvn test`.
- Phase 7 does not create SynthesisCard and does not implement Artifact distillation, personal generation, methodology extraction or quiz workflows.
```

Next:

```text
Proceed to Phase 8 Studio / Artifact.
```

## 16. Phase 8 Studio / Artifact (2026-05-16)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added V9 migration with artifact, artifact_version, artifact_source, artifact_citation, session_artifact and skill_execution_log.
2) Added Studio task APIs and service flow for ARTIFACT_GENERATE creation, query, cancel and retry, reusing the generic task/outbox/Kafka worker chain.
3) Added artifact APIs for space/session listing, detail, manual update, archive, regenerate/generate and markdown export.
4) Implemented a fixed Plan executor with a constrained skill registry and Phase 8 supported artifact types:
   REPORT, STUDY_GUIDE, BRIEFING, FAQ, COMPARISON, WIKI_DRAFT.
5) Implemented artifact generation for RESEARCH_PROJECT and CHAT_MESSAGE source scopes without introducing team Wiki publish, personal Wiki distillation or autonomous agent planning.
6) Enforced artifact history semantics so initial generation, manual edit and regenerate always append artifact_version rows instead of overwriting prior content.
7) Added formal artifact evidence relations through artifact_source and artifact_citation; artifacts default remain outside Wiki and no generated_artifact table/model was introduced.
8) Added redacted skill execution logging bound to taskId/artifactId/artifactVersionId plus GET /api/v1/tasks/{taskId}/skill-logs.
9) Added markdown export object persistence under dev/test artifact export prefixes and archive soft-delete semantics for artifacts.
10) Fixed Phase 8 worker persistence by extracting transaction-bound save/fail logic into ArtifactPersistenceService so pessimistic-lock writes run inside real Spring-managed transactions.
11) Hardened artifact task terminal-state reconciliation, task idempotency fallback, duplicate placeholder cleanup and artifact list query/search filters.
```

New migration:

```text
src/main/resources/db/migration/V9__phase_8_studio_artifact_generation.sql
```

New tables:

```text
artifact
artifact_version
artifact_source
artifact_citation
session_artifact
skill_execution_log
```

New APIs:

```text
POST /api/v1/studio/tasks
GET /api/v1/studio/tasks/{taskId}
POST /api/v1/studio/tasks/{taskId}/cancel
POST /api/v1/studio/tasks/{taskId}/retry
GET /api/v1/spaces/{spaceId}/artifacts
GET /api/v1/chat/sessions/{sessionId}/artifacts
GET /api/v1/artifacts/{artifactId}
PUT /api/v1/artifacts/{artifactId}
DELETE /api/v1/artifacts/{artifactId}
POST /api/v1/artifacts/{artifactId}/regenerate
POST /api/v1/artifacts/{artifactId}/generate
GET /api/v1/artifacts/{artifactId}/export?format=markdown
GET /api/v1/tasks/{taskId}/skill-logs
```

Generation and versioning rules:

```text
- Supported Phase 8 artifact types are currently limited to REPORT / STUDY_GUIDE / BRIEFING / FAQ / COMPARISON / WIKI_DRAFT.
- Artifact records are placeholders while generation is pending; successful generation writes the latest title/content back to artifact and appends a new artifact_version row.
- Manual edit appends artifact_version with changeNote defaulting to "manual update".
- Regenerate appends artifact_version with changeNote "artifact regeneration" and does not overwrite prior manual history.
- Artifact formal citations must go through artifact_citation, not embedded generated JSON.
- Skill logs store redacted input/output summaries only; they do not persist full sensitive prompt context or private raw source text.
```

TDD record:

```text
1) Wrote Phase8StudioArtifactIntegrationTest before implementation to cover studio task creation, artifact export, redacted skill logs, permission denial, version history append, chat-session FAQ generation and archive semantics.
2) Initial red run with mvn "-Dtest=Phase8StudioArtifactIntegrationTest" test failed as expected because Phase 8 Studio/Artifact endpoints and persistence flow did not exist yet.
3) Implemented the minimal migration, models, repositories, services, controllers and worker path to satisfy the new suite.
4) A later red run exposed a real worker failure: "Query requires transaction be in progress, but no transaction is known to be in progress" during artifact generation persistence.
5) Fixed the transaction boundary by moving lock-based save/fail persistence into ArtifactPersistenceService, then re-ran the failing test to green.
6) Re-ran the full Phase 8 suite and required Phase 7 / Phase 4 / task regressions to green.
```

Test commands and results:

```text
1) mvn "-Dtest=Phase8StudioArtifactIntegrationTest" test
   - initial red failed as expected before Phase 8 production code existed.

2) mvn "-Dtest=Phase8StudioArtifactIntegrationTest#studioTaskShouldGenerateProjectArtifactExportAndRedactedSkillLogs" test
   - passed after fixing the transaction-bound artifact persistence path: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0

3) mvn "-Dtest=Phase8StudioArtifactIntegrationTest" test
   - passed: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0

4) mvn "-Dtest=Phase7PersonalWikiCompilerIntegrationTest,Phase4TeamRagIntegrationTest,TaskServiceIntegrationTest,TaskControllerTest,TaskAdminVisibilityIntegrationTest" test
   - passed: Tests run: 25, Failures: 0, Errors: 0, Skipped: 0

5) mvn "-Dtest=StudioTaskServiceTest" test
   - passed: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0

6) mvn "-Dtest=Phase8StudioArtifactIntegrationTest#cancelPendingArtifactTaskShouldReconcilePlaceholderToFailed,Phase8StudioArtifactIntegrationTest#artifactListShouldSupportQueryFiltersAndKeywordSearch,Phase8StudioArtifactIntegrationTest#regenerateFailureShouldKeepLatestSuccessfulArtifactReady" test
   - passed: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- Artifact defaults remain outside Wiki in Phase 8.
- Phase 8 does not implement team Wiki publish, personal artifact distillation to Wiki, quiz/assessment flows or autonomous agent planning.
- RESEARCH_PROJECT generation uses Phase 7 article/concept cards plus their citation backtrace data; CHAT_MESSAGE generation uses existing Phase 4 message citations.
- Markdown export is the only implemented artifact export format in Phase 8; advanced PDF/DOCX/PPT export remains out of scope.
- `GET /api/v1/spaces/{spaceId}/artifacts` supports `artifactType`, `status`, `sourceScopeType`, `researchProjectId` and `keyword` filters.
- Pending cancel, timeout and failure reconcile artifact placeholders back to `READY` when a prior version exists, otherwise to `FAILED`.
```

Next:

```text
Proceed to Phase 9 retrieval enhancement / RRF.
```

## 17. Phase 9 Retrieval Enhancement / RRF (2026-05-17)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added V10 migration to extend retrieval_trace with retrievalMode, per-route counts, fallbackUsed and traceJson for explainable hybrid retrieval traces.
2) Added EmbeddingProperties, EmbeddingClient stub and VectorIndexerService with vector alias/index naming based on model + dimension.
3) Added EMBEDDING_BACKFILL worker path and backfill result contract that records BM25 fallback instead of failing the whole document retrieval baseline.
4) Extended SearchIndexService with dense_vector mapping support, vector index ensure/switch helpers, embedding bulk indexing and kNN search entrypoints.
5) Added RetrievalHit, RetrievalMode, RrfOptions, Retriever abstraction and WeightedReciprocalRankFusion.
6) Added VectorRetriever and HybridRetriever so TeamChatService and SearchDebug can run HYBRID mode with BM25 fallback.
7) Enhanced EvidencePostProcessor with low-score filtering while preserving adjacent merge, dedupe, per-document limiting and context truncation.
8) Extended Search Debug API with mode parameter plus debug counts for bm25/vector/fusion.
9) Wired document processing to attempt embedding backfill after BM25 indexing without breaking the Phase 3 success path when vector work fails.
10) Kept Phase 4 citation contract unchanged while making retrieval traces explain each route and fusion result.
11) Added POST /api/v1/team/documents/{documentId}/embedding-backfill so historical indexed documents can trigger EMBEDDING_BACKFILL through the real task/outbox/Kafka/worker chain.
12) Hardened hybrid retrieval fallback semantics so query-embedding/vector errors degrade to BM25 (+ Wiki when available) instead of aborting TeamChat retrieval.
13) Switched vector alias only after embeddings are written and refreshed, preventing empty alias cutover during backfill.
```

New migration:

```text
src/main/resources/db/migration/V10__phase_9_retrieval_enhancement_rrf.sql
```

Key retrieval/runtime rules:

```text
- Default RRF weights remain BM25=1.0, Vector=1.0, Wiki=1.3, rrfK=60.
- Vector index versioning is bound to embedding model + dimension through alias/index naming.
- Vector failures degrade to BM25-only retrieval and keep permission filters intact.
- retrieval_trace now stores retrieval_mode, bm25_count, vector_count, fusion_count, fallback_used and trace_json.
- Search Debug can be called with mode=HYBRID and returns retrievalMode/debug counts.
```

TDD record:

```text
1) Wrote Phase 9 tests first: WeightedReciprocalRankFusionTest, EmbeddingBackfillTaskWorkerTest, refreshed EvidencePostProcessorTest and Phase9HybridRetrievalIntegrationTest.
2) Initial red run failed as expected because RetrievalHit, WeightedReciprocalRankFusion, VectorIndexerService and EmbeddingBackfillTaskWorker did not exist yet.
3) Implemented the minimal retrieval/vector/RRF/trace code needed to satisfy the new tests.
4) A later regression red run exposed hybrid-runtime metadata loss and an EvidencePostProcessor null indexVersion edge case; both were fixed with metadata-preserving RRF output and null-safe adjacent-chunk merge checks.
5) Added graceful BM25 fallback, vector alias/index helpers and low-score evidence filtering, then re-ran the failing suite to green.
6) Re-ran required Phase 4 / Phase 5 / Phase 3 regression tests to confirm the existing chat/runtime/document indexing flows still passed.
```

Test commands and results:

```text
1) mvn "-Dtest=EvidencePostProcessorTest,WeightedReciprocalRankFusionTest,EmbeddingBackfillTaskWorkerTest,Phase9HybridRetrievalIntegrationTest" test
   - initial red failed as expected because core Phase 9 production types were missing.

2) mvn "-Dtest=WeightedReciprocalRankFusionTest,EvidencePostProcessorTest" test
   - red after regression coverage expansion: failed as expected because fused metadata dropped indexVersion/rawScore and EvidencePostProcessor assumed non-null indexVersion.

3) mvn "-Dtest=WeightedReciprocalRankFusionTest,EvidencePostProcessorTest" test
   - passed: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

4) mvn "-Dtest=Phase5WorkspaceChatRuntimeIntegrationTest,Phase9HybridRetrievalIntegrationTest" test
   - passed: Tests run: 9, Failures: 0, Errors: 0, Skipped: 0

5) mvn "-Dtest=EvidencePostProcessorTest,WeightedReciprocalRankFusionTest,EmbeddingBackfillTaskWorkerTest,Phase9HybridRetrievalIntegrationTest" test
   - passed: Tests run: 11, Failures: 0, Errors: 0, Skipped: 0

6) mvn "-Dtest=Phase3DocumentProcessingIntegrationTest,Phase4TeamRagIntegrationTest,Phase5WorkspaceChatRuntimeIntegrationTest" test
   - passed: Tests run: 17, Failures: 0, Errors: 0, Skipped: 0

7) docker compose config --quiet
   - passed

8) mvn "-Dtest=HybridRetrieverTest,VectorIndexerServiceTest,EvidencePostProcessorTest,WeightedReciprocalRankFusionTest,EmbeddingBackfillTaskWorkerTest,Phase9HybridRetrievalIntegrationTest,Phase10TeamWikiIntegrationTest,MethodologyMatcherTest,Phase10_5MethodologyPresetMatcherIntegrationTest" test
   - passed twice after Phase 9/10 hardening: Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- Phase 9 intentionally does not implement Wiki publish/index, rerank models, Artifact generation, personal Wiki flows or evaluation UI.
- Integration tests use Testcontainers for MySQL / Redis / MinIO / Elasticsearch / Kafka and do not depend on docker compose already running.
- Stub embeddings currently use deterministic local vectors for testing and fallback-friendly development wiring; a real provider can be swapped in later through the EmbeddingClient abstraction.
- The current vector trace payload is intentionally minimal and aimed at Phase 14 observability expansion rather than full ranking analytics.
```

Next:

```text
Proceed to Phase 10 team Wiki publish and index.
```

## 18. Phase 10 Team Wiki Publish / Index (2026-05-17)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added V11 migration with wiki_page, wiki_page_version and wiki_page_citation.
2) Added team Wiki draft create/read/update, publish, version history and keyword search APIs, plus Artifact -> Wiki draft and ChatMessage -> Wiki draft entrypoints.
3) Restricted publish to OWNER and made publish generate an immutable WikiPageVersion snapshot with incrementing versionNo, publishedVersionId and publishedVersionNo.
4) Reused the generic task/outbox/Kafka worker chain for WIKI_INDEX and added a dedicated wiki-page Elasticsearch index through SearchIndexWikiSupport and WikiIndexTaskWorker.
5) Wired only PUBLISHED + INDEXED wiki pages into team RAG via WikiRetriever and HybridRetriever, while keeping Search Debug document-focused.
6) Added source traceability for source_artifact_id / source_message_id and publish-time writeback of message-derived citations into wiki_page_citation.
7) Extended retrieval/citation plumbing so wiki hits persist assistant citations with sourceType = WIKI_PAGE instead of reusing DOCUMENT semantics.
8) Archive now removes indexed wiki documents from Elasticsearch so archived pages stop appearing in wiki search and team RAG retrieval.
9) Team chat citation persistence now carries wiki publishedVersionId into citation.sourceVersion for WIKI_PAGE evidence.
```

New migration:

```text
src/main/resources/db/migration/V11__phase_10_team_wiki_publish_index.sql
```

New tables:

```text
wiki_page
wiki_page_version
wiki_page_citation
```

New APIs:

```text
POST /api/v1/team/spaces/{spaceId}/wiki-pages
GET /api/v1/team/wiki-pages/{pageId}
PUT /api/v1/team/wiki-pages/{pageId}
POST /api/v1/team/wiki-pages/{pageId}/publish
GET /api/v1/team/wiki-pages/{pageId}/versions
GET /api/v1/team/spaces/{spaceId}/wiki-pages/search
POST /api/v1/artifacts/{artifactId}/publish-to-wiki
POST /api/v1/team/chat-messages/{messageId}/wiki-drafts
```

Publish / index rules:

```text
- Team Artifact must be manually confirmed before it becomes a Wiki draft/page; Phase 10 does not auto-publish artifacts.
- Every publish creates a new wiki_page_version snapshot from the current draft content and changeNote.
- Only pages with status = PUBLISHED and index_status = INDEXED are eligible for team RAG retrieval and wiki search.
- WIKI_INDEX failures do not roll back the draft/published wiki record and can be retried through the existing POST /api/v1/tasks/{taskId}/retry path.
- Message-derived wiki publishes copy the underlying citation relations into wiki_page_citation; this does not bypass existing citation permission boundaries.
```

TDD record:

```text
1) Wrote Phase10TeamWikiIntegrationTest first to cover draft CRUD, permission denial, owner publish/version generation, Artifact/ChatMessage source traceability, wiki_page_citation writeback, WIKI_INDEX retry and "unindexed wiki stays out of retrieval".
2) Initial red run failed as expected because the Phase 10 wiki endpoints and publish/index flow did not exist yet.
3) Implemented the minimal migration, repositories, services, controllers, wiki index worker and retrieval/citation source-type plumbing needed for the new suite.
4) A later red run exposed a test-harness gap on the post-index retrieval assertion path; the indexed wiki retrieval case now explicitly stubs the LLM answer path so the suite validates WIKI_PAGE retrieval/citation behavior rather than failing on a null mock response.
5) Re-ran the Phase 10 suite and required Phase 4 / Phase 5 / Phase 8 / Phase 9 regressions to green.
```

Test commands and results:

```text
1) mvn "-Dtest=Phase10TeamWikiIntegrationTest" test
   - initial red failed as expected before the wiki endpoints/publish/index flow existed.

2) mvn "-Dtest=Phase10TeamWikiIntegrationTest#publishedButUnindexedWikiShouldStayOutOfChatRetrievalUntilIndexTaskSucceedsAndFailedTaskShouldBeRetryable" test
   - passed: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0

3) mvn "-Dtest=Phase10TeamWikiIntegrationTest" test
   - passed: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0

4) mvn "-Dtest=Phase9HybridRetrievalIntegrationTest,Phase4TeamRagIntegrationTest,Phase5WorkspaceChatRuntimeIntegrationTest,Phase8StudioArtifactIntegrationTest" test
   - passed: Tests run: 20, Failures: 0, Errors: 0, Skipped: 0

5) mvn "-Dtest=HybridRetrieverTest,VectorIndexerServiceTest,EvidencePostProcessorTest,WeightedReciprocalRankFusionTest,EmbeddingBackfillTaskWorkerTest,Phase9HybridRetrievalIntegrationTest,Phase10TeamWikiIntegrationTest,MethodologyMatcherTest,Phase10_5MethodologyPresetMatcherIntegrationTest" test
   - passed twice after archive/index cleanup and wiki citation-version hardening: Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- Phase 10 does not implement approval workflow,多人实时协同编辑、个人 Artifact 沉淀或 Quiz。
- WIKI_INDEX uses the existing task topic and Testcontainers middleware baseline; no new standalone middleware container, MinIO bucket or Kafka topic was introduced.
- Team chat/runtime retrieval includes Wiki only after successful indexing; Search Debug intentionally keeps wiki hits excluded in this phase.
```

Next:

```text
Proceed to Phase 10.5 methodology preset matcher.
```

## 19. Phase 10.5 Methodology Preset / Matcher (2026-05-17)

Status:

```text
DONE
```

Implemented in this update:

```text
1) Added V12 migration with the minimal methodology_card table required for preset templates and future Phase 11 prompt wiring.
2) Added MethodologyCard model/repository plus PRESET/ACTIVE enums for the Phase 10.5 read-only baseline.
3) Added MethodologySeedService with idempotent startup seeding under a reserved system preset scope (space_id = 0).
4) Seeded five readable presets: Research Report, Study Guide, Comparison Analysis, Work Prep STAR and a General Structured Writing fallback.
5) Added MethodologyMatcher with project -> personal space -> preset lookup order, exact problemType preference, scene signal tie-break and GENERAL fallback.
6) Added GET /api/v1/personal/research-projects/{projectId}/methodology-cards for owner-only preset inspection and later Phase 11 reuse.
7) Wired research-project artifact generation prompt assembly to inject matched workflow, outputStructure and qualityChecklist before the existing card/evidence context.
8) Refreshed two pre-existing RAG unit tests whose constructor expectations had drifted from the current record contracts so the regression suite could compile and run again.
```

New migration:

```text
src/main/resources/db/migration/V12__phase_10_5_methodology_preset_matcher.sql
```

Preset methodology cards:

```text
- Research Report Methodology -> REPORT
- Study Guide Methodology -> STUDY_GUIDE
- Comparison Analysis Methodology -> COMPARISON
- Work Prep STAR Methodology -> WORK_PREP
- General Structured Writing Methodology -> GENERAL fallback
```

Matcher rules:

```text
1) Search order: research-project cards -> personal-space cards -> system PRESET cards.
2) Prefer exact artifactType/problemType match.
3) Use scene/scenario/topic text overlap to break ties inside the same problemType.
4) Fall back to GENERAL when no specific preset exists.
5) If no candidates exist at all, return empty and let prompt generation degrade gracefully.
```

Prompt injection rules:

```text
- Research-project artifact generation now resolves a matched MethodologyCard during LoadGenerationContextSkill.
- buildPrompt injects:
  Selected methodology
  Workflow
  Output structure
  Quality checklist
- Existing article/concept/evidence context remains unchanged after the methodology section.
- CHAT_MESSAGE scoped artifact generation does not force methodology injection in this phase.
```

TDD record:

```text
1) Wrote MethodologyMatcherTest and Phase10_5MethodologyPresetMatcherIntegrationTest first.
2) Initial red run failed as expected because MethodologySeedService, MethodologyMatcher, methodology_card persistence and the read-only methodology API did not exist yet.
3) Implemented the minimal migration, preset seed service, matcher, DTO/controller path and prompt injection wiring to satisfy the new tests.
4) Added idempotent seed handling, GENERAL fallback matching and prompt-section injection without changing Phase 13 editing/version-management scope.
5) Re-ran the new suite plus Phase 8 artifact generation and the touched RAG unit regressions to green.
```

Test commands and results:

```text
1) mvn "-Dtest=MethodologyMatcherTest,Phase10_5MethodologyPresetMatcherIntegrationTest" test
   - initial red failed as expected because the new methodology production classes and persistence layer did not exist yet.

2) mvn "-Dtest=MethodologyMatcherTest,Phase10_5MethodologyPresetMatcherIntegrationTest" test
   - passed: Tests run: 6, Failures: 0, Errors: 0, Skipped: 0

3) mvn "-Dtest=MethodologyMatcherTest,Phase10_5MethodologyPresetMatcherIntegrationTest,Phase8StudioArtifactIntegrationTest,TeamRagPromptBuilderTest,EvidencePostProcessorTest" test
   - passed: Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
```

Notes:

```text
- Phase 10.5 does not implement user-created MethodologyCard CRUD, archive/version management, marketplace/template sharing, Artifact -> Methodology proposal or Quiz flows.
- Presets are stored under reserved system scope space_id = 0 so they are not mistaken for any normal user's personal cards.
- No new middleware container, MinIO bucket, Kafka topic, Elasticsearch index family or local test path was introduced in this phase.
```

Next:

```text
Proceed to Phase 11 personal Wiki-based generation.
```
