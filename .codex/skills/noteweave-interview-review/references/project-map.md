# NoteWeave Project Map

## Truth Priority

Use this order when facts conflict:

1. Current controller/service/worker code
2. Current integration tests
3. Flyway migrations
4. `docs/PROJECT_STATUS.md`
5. `README.md`
6. `面试准备/*.md`

Important note:

- `docs/PROJECT_STATUS.md` can lag behind the code in a few places.
- The current codebase already contains Methodology CRUD, observability/eval admin APIs, admin ops APIs, Team Wiki graph/auto-maintenance features, and Studio Bilibili MCP support.
- For interview prep, treat code, tests, and migrations as the source of truth, and mention doc drift when needed.

## Resume-Safe Talking Points

Use these as practical interview guardrails instead of explicit internal taxonomy:

### Mainline Capabilities You Can Speak As Implemented

- dual-space architecture: `TEAM` + `PERSONAL`
- auth, user, space, permission boundaries
- unified `Task + Attempt + Event + Outbox + Kafka + Worker`
- upload, chunk merge, object reuse, async parse/index
- hybrid retrieval with BM25/vector/wiki + weighted RRF
- citation persistence and retrieval trace
- WebSocket runtime with `DRAFT` / `FORMAL`, stop, restore
- personal source import, safe URL fetch, wiki compiler, concept merge
- artifact versioning, personal generation, methodology cards
- artifact distillation into synthesis cards
- long-term memory writeback and layered context loading
- prompt versioning, llm logs, eval, cleanup, health, audit
- team wiki graph and auto-maintenance loop
- controlled Bilibili MCP tool extension for Studio

### Good Optimization / Extension Angles

- chunk merging and retrieval reranking heuristics
- methodology matching quality
- memory writeback quality and filtering
- artifact plan richness and skill planning depth
- Wiki retrieval coverage and richer publish workflows
- more document types such as DOCX or scanned PDF
- more complete prompt governance and eval metrics
- more advanced retrieval-query rewrite and planner behavior
- stronger partial-content persistence and replay policies
- broader tool-extension governance beyond the current Bilibili MCP path

### Topics To Downgrade Unless The Repo Grows

- Redis runtime state expanded into a fake main async queue story
- MCP as if it were a first-class integrated protocol already used in main flows
- Bibtex as if it were already implemented end to end
- fully autonomous agent orchestration
- production-scale metrics the repo does not expose

## Strongest Project Story

NoteWeave is a dual-space AI knowledge workspace:

- `TEAM` space solves team knowledge-base ingestion, retrieval, chat, citations, wiki publishing, and admin/ops.
- `PERSONAL` space solves research project ingestion, source compilation, card extraction, artifact generation, synthesis, and methodology-driven output.
- Both sides reuse common foundations: auth, permission, task execution, storage, search, LLM calls, observability, and memory.

## Module Map

### 1. Auth, User, Space, Permission

Core packages:

- `com.noteweave.auth`
- `com.noteweave.user`
- `com.noteweave.space`
- `com.noteweave.permission`
- `com.noteweave.common.security`

What to say:

- The project starts by defining `PERSONAL` and `TEAM` as first-class spaces.
- Permissions are not scattered across controllers; they go through central access checks.
- This is the boundary that keeps later RAG, memory, and admin features safe.

Likely follow-ups:

- Why split `systemRole` and `space role`?
- Why must resource access be rechecked on citations and traces?

### 2. Unified Async Backbone

Core packages:

- `com.noteweave.task`
- `TaskService`
- `TaskOutboxService`
- `TaskDispatcher`
- `TaskKafkaPublisher`
- `TaskKafkaConsumer`
- `TaskExecutionCoordinator`

What to say:

- Long-running work is normalized into `Task + TaskAttempt + TaskEvent + TaskOutbox`.
- Business writes and Kafka dispatch are decoupled through the outbox pattern.
- This avoids every AI or indexing feature inventing its own background execution model.

Repository anchors:

- `src/main/java/com/noteweave/task/service/TaskService.java`
- `src/main/java/com/noteweave/task/service/TaskOutboxService.java`
- `src/test/java/com/noteweave/task/service/TaskServiceIntegrationTest.java`

Likely follow-ups:

- Why not write directly to Kafka in the business transaction?
- How do idempotency, retry, and cancel_requested work?

### 3. Team Knowledge Base Upload and Processing

Core packages:

- `com.noteweave.team.kb`
- `com.noteweave.team.document`

What to say:

- Team documents go through init upload, chunk upload, merge, object reuse, async processing, and soft-delete-aware indexing.
- The design supports resumable upload, object dedup inside a space, and async parse/index processing.

Repository anchors:

- `DocumentUploadService`
- `DocumentProcessingService`
- `ChunkService`
- `VectorIndexerService`
- `Phase2UploadFlowIntegrationTest`
- `Phase3DocumentProcessingIntegrationTest`

Likely follow-ups:

- Why is file reuse scoped by `spaceId`?
- Why soft delete documents without immediately decrementing `refCount`?
- How does reindex avoid breaking the active search path?

### 4. Team RAG, Search, Citation

Core packages:

- `com.noteweave.team.rag`
- `com.noteweave.chat`
- `com.noteweave.citation`
- `com.noteweave.search`

What to say:

- Team chat is evidence-grounded, not just prompt-to-LLM.
- Retrieval supports BM25, vector recall, wiki recall, and weighted RRF fusion.
- Answers are tied to citations and retrieval traces so they can be audited later.

Repository anchors:

- `HybridRetriever`
- `EvidencePostProcessor`
- `TeamRagPromptBuilder`
- `TeamChatService`
- `SearchDebugService`
- `Phase4TeamRagIntegrationTest`
- `Phase9HybridRetrievalIntegrationTest`

Likely follow-ups:

- Why Elasticsearch instead of only vector DB or only MySQL?
- Why keep citation as relational data instead of message JSON?
- What happens when vector retrieval fails?

### 5. WebSocket Runtime and Session State

Core packages:

- `com.noteweave.chat.runtime`
- `com.noteweave.websocket`

What to say:

- HTTP chat covers stable question-answer flow, but WebSocket runtime handles streaming, stop, restore, and draft sessions.
- `DRAFT` and `FORMAL` are separated so temporary exploration does not pollute long-term memory.

Repository anchors:

- `ChatRuntimeService`
- `ChatRuntimeStateStore`
- `ActiveExecutionRegistry`
- `ContextReadRouter`
- `Phase5WorkspaceChatRuntimeIntegrationTest`

Likely follow-ups:

- Why is runtime state in Redis and not persisted like normal messages?
- Why does `DRAFT` not write long-term memory?

### 6. Personal Research Ingestion and Safe URL Fetching

Core packages:

- `com.noteweave.personal.project`
- `com.noteweave.personal.source`

What to say:

- Personal research starts from files, text, or URLs.
- URL ingestion is guarded to reduce SSRF-style risk and unsafe internal address access.
- A source is not considered ready unless readable text exists for later compilation.

Repository anchors:

- `SourceService`
- `SourceImportService`
- `SafeUrlContentFetcher`
- `Phase6PersonalResearchSourceIntegrationTest`

Likely follow-ups:

- How do you prevent fetching localhost or private network addresses?
- Why require readable text before marking import ready?

### 7. Personal Wiki Compiler and Evidence Backtrace

Core packages:

- `com.noteweave.personal.compiler`
- `com.noteweave.personal.card`

What to say:

- Personal sources are compiled into `ArticleCard`, `ConceptCard`, aliases, relations, and evidence links.
- The compiler does not stop at LLM extraction; it backtraces evidence to source text and stores formal citation relations.

Repository anchors:

- `WikiCompilerService`
- `EvidenceBacktraceService`
- `ConceptMergeService`
- `Phase7PersonalWikiCompilerIntegrationTest`

Likely follow-ups:

- How do you prevent duplicate concepts in the same project?
- Why keep evidence both as display cache and as formal citation relations?

### 8. Artifact Generation, Methodology, Distillation

Core packages:

- `com.noteweave.artifact`
- `com.noteweave.studio`
- `com.noteweave.personal.generation`
- `com.noteweave.personal.methodology`
- `com.noteweave.personal.distillation`

What to say:

- Artifact is a separate lifecycle from chat messages and wiki pages.
- Personal generation uses research context, evidence, and methodology cards to shape output.
- Distillation to personal wiki is explicit and version-bound to avoid automatic knowledge pollution.
- Studio currently looks more like a controllable plan-based generation pipeline than an unrestricted autonomous agent.

Repository anchors:

- `ArtifactService`
- `StudioTaskService`
- `PersonalGenerationService`
- `MethodologyCardService`
- `PersonalArtifactDistillationService`
- `Phase8StudioArtifactIntegrationTest`
- `Phase11PersonalGenerationIntegrationTest`
- `Phase11_5PersonalArtifactDistillationIntegrationTest`

Likely follow-ups:

- Why separate Artifact from Wiki?
- Why require explicit confirmation before creating `SynthesisCard`?
- How do methodology cards change prompt structure without becoming hard-coded templates?
- What does “Skill” really mean in this project, and how far does it go today?
- How does the Bilibili MCP tool sit inside Studio without turning the whole repo into a generic MCP platform?

### 9. Long-Term Memory

Core packages:

- `com.noteweave.memory`
- `ContextReadRouter`
- `MemoryWritebackService`

What to say:

- Memory is layered into session summary, space memory, and user memory.
- Writeback is controlled by strategy rules so DRAFT rounds, greetings, or sensitive content do not pollute memory.

Repository anchors:

- `MemoryWritebackService`
- `MemoryWritebackStrategy`
- `Phase12LongTermMemoryIntegrationTest`

Likely follow-ups:

- How do you keep useful continuity without dumping full history into every prompt?
- How do you avoid writing unsafe or low-value memory?

### 10. Team Wiki, Graph, Auto Maintenance

Core packages:

- `com.noteweave.team.wiki`
- `com.noteweave.graph`

What to say:

- Team Wiki is the long-term team knowledge layer, separate from temporary chat answers and generated artifacts.
- Wiki pages have versions, citations, search, relation links, graph views, and an auto-maintenance path.
- The graph feature is useful for browsing and relation inspection, but should not be oversold as a complete GraphRAG mainline.

Repository anchors:

- `TeamWikiController`
- `TeamWikiService`
- `WikiIndexTaskWorker`
- `WikiGraphService`
- `AutoWikiMaintenanceService`
- `KnowledgeGraphService`
- `Phase10TeamWikiIntegrationTest`

Likely follow-ups:

- Why separate Artifact from Wiki?
- Why does Wiki publishing still go through indexing?
- What is the boundary between Wiki graph and GraphRAG?

### 11. Observability, Eval, Admin, Ops

Core packages:

- `com.noteweave.prompt`
- `com.noteweave.llm`
- `com.noteweave.rageval`
- `com.noteweave.admin`

What to say:

- The project is built to inspect prompt versions, LLM logs, retrieval traces, eval runs, cleanup jobs, audit logs, and health checks.
- This is one of the strongest differentiators because it turns an AI demo into an operable system.

Repository anchors:

- `AdminObservabilityController`
- `AdminOpsController`
- `SystemHealthService`
- `Phase14ObservabilityEvaluationIntegrationTest`
- `Phase15AdminOpsIntegrationTest`

Likely follow-ups:

- How do eval runs avoid polluting normal chat history?
- What can an admin trace after a bad answer?
- Why are health checks split by MySQL, Redis, MinIO, Kafka, Elasticsearch, and LLM provider?
- How does wiki auto-maintenance affect the read/write path and search freshness?

## Resume Phrase Guardrails

### Hybrid RAG

Safe wording:

- “我做的是权限约束下的混合检索链路，核心是 Elasticsearch BM25、向量召回、Wiki recall 和 Weighted RRF 融合。”

### Redis runtime state

Safe wording:

- “当前主异步链路还是 Task/Outbox/Kafka。Redis 这块我主要用来承载 WebSocket runtime state、stop/resume 控制和断线恢复相关状态，而不是把它讲成主任务队列。”

### Skill

Safe wording:

- “这里的 Skill 更像可控的生成步骤编排和执行日志，不是完全开放式 agent 插件生态。”

### MCP

Safe wording:

- “当前仓库已经有 Studio Bilibili MCP 工具路径，包括本地/远程工具服务、对话显式触发和 Artifact workflow 编排；但我不会把它夸成完整通用 MCP 平台，它更像受控工具扩展能力。”

### Bibtex

Safe wording:

- “学术资料导入是个人研究侧很自然的下一步扩展，当前仓库主实现还在 Source/URL/File/Text 这几类输入。”
