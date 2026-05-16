# NoteWeave Project Status

本文档用于给后续 AI 编码代理快速判断当前做到哪里。每次开始新阶段前先读本文档；每完成一个阶段后必须更新本文档。

更新时间：2026-05-16

---

## 1. 当前结论

当前状态：

```text
Phase 0/1、Phase 1.5、Phase 2、Phase 3、Phase 4、Phase 5、Phase 6 和 Phase 7 已完成并通过当前阶段测试与必要回归测试；可以进入 Phase 8。
```

当前代码已包含 Auth/User/Space/Permission、Task/Outbox/Kafka Worker 基础设施、Phase 2 文件上传链路、Phase 3 的 DOCUMENT_PROCESS Worker、文档解析、parsed text 保存、Chunk 切片、indexVersion / activeIndexVersion、Elasticsearch BM25 索引和 Search Debug、Phase 6 的个人 ResearchProject / Source、TEXT/FILE/URL 导入、SOURCE_IMPORT Worker、个人 raw/parsed text 对象保存、owner-only 查询与重试/去重链路，以及 Phase 7 的 SOURCE_COMPILE、ArticleCard / ConceptCard / ConceptAlias / ConceptRelation / ArticleConceptRelation、个人 Card Citation、Card 搜索详情与 Evidence 回溯链路。

下一步：

```text
进入 Phase 8: Studio / Artifact。
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
| Phase 8 | PENDING | Studio / Artifact |
| Phase 9 | PENDING | 检索增强 / RRF |
| Phase 10 | PENDING | 团队 Wiki 发布入索引 |
| Phase 10.5 | PENDING | Methodology 预置模板与 Matcher |
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
