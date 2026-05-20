# NoteWeave Interview Review Outline

## 0. Review Goal

Use this project as a strong applied-systems story:

- not just "I used an LLM"
- but "I built a permission-aware, traceable, operable AI knowledge workspace"

## 1. Default Deep Intro

Suggested structure:

1. What the product is:
   - NoteWeave is an AI knowledge workspace with `TEAM` and `PERSONAL` spaces.
2. What the core value is:
   - Team side focuses on knowledge-base ingestion, retrieval, grounded chat, and wiki publishing.
   - Personal side focuses on research ingestion, card compilation, artifact generation, and synthesis.
3. What the technical backbone is:
   - A unified async task system, hybrid retrieval, citation traceability, runtime state management, and admin observability.

Default expectation:

- answer as a 2-minute minimum version
- if the interviewer gives more room, naturally expand to 3 to 5 minutes
- do not stop at product description; quickly move into architecture and implementation ownership

## 2. Expanded Deep Intro

Expand in this order:

1. Product boundary
   - Why split team collaboration and personal research
2. Architecture backbone
   - auth, permission, task/outbox/worker, storage, search, LLM
3. Team flow
   - upload -> parse -> chunk -> index -> chat -> citation -> wiki
4. Personal flow
   - source -> compile -> cards -> artifact -> synthesis
5. Reliability and operations
   - memory boundaries, eval, observability, admin health/cleanup/audit

## 3. Recommended Review Order

### Layer A: Product and Boundary

- What problem does NoteWeave solve?
- Why both `TEAM` and `PERSONAL` spaces?
- Why is Artifact not equal to Wiki?

### Layer B: Shared Architecture

- Auth, session, permission
- Task, outbox, retry, cancellation
- MinIO, Elasticsearch, Kafka, Redis, MySQL roles

### Layer C: Team Mainline

- upload and resumable ingestion
- parsing, chunking, versioned indexing
- hybrid retrieval and evidence post-processing
- citation, trace, feedback
- WebSocket runtime and DRAFT/FORMAL

### Layer D: Personal Mainline

- source import and safe URL fetch
- wiki compiler and evidence backtrace
- methodology-guided generation
- artifact distillation into synthesis

### Layer E: Operability

- memory writeback rules
- prompt versioning
- LLM logs and retrieval trace
- eval runs
- cleanup jobs, health checks, audit logs

## 4. Best Scenario-Solution-Benefit Stories

### Story 1: Why a unified async backbone

- 场景: upload, parse, compile, generate, eval, cleanup are all long-running and failure-prone
- 方案: normalize them into `Task + Attempt + Event + Outbox + Kafka + Worker`
- 收益: retries, cancellation, auditability, and lower architecture drift

### Story 2: Why retrieval is evidence-first

- 场景: knowledge chat must be grounded and auditable
- 方案: BM25/vector/wiki recall + evidence post-processing + citation persistence + retrieval traces
- 收益: better answer quality and much stronger explainability

### Story 3: Why team and personal are separated

- 场景: team knowledge and personal exploration have different permission and curation needs
- 方案: `TEAM` side emphasizes shared KB/Wiki; `PERSONAL` side emphasizes cards, artifacts, and explicit synthesis
- 收益: avoids accidental contamination and keeps permission boundaries clear

### Story 4: Why DRAFT and FORMAL are separated

- 场景: users need exploratory chat without polluting stable memory
- 方案: DRAFT sessions stay in runtime state and skip long-term writeback
- 收益: continuity without uncontrolled memory pollution

### Story 5: Why Artifact is not Wiki

- 场景: generated output may be useful but not yet trustworthy as long-term knowledge
- 方案: keep Artifact as versioned working output; only publish/distill after confirmation
- 收益: keeps the knowledge base cleaner and easier to trust

## 5. How to Answer When Metrics Are Missing

Use this template:

1. Say the repo already has the instrumentation surface.
2. Point to where validation would happen:
   - LLM logs
   - retrieval traces
   - eval runs
   - task events
   - health and audit logs
3. Explain the metric you would measure:
   - recall@k
   - citation coverage
   - end-to-end success rate
   - task latency and retry rate
4. Be explicit that the current repo does not provide a production number.

## 6. Highest-Value Deep Follow-Ups

- Why choose Elasticsearch as the retrieval hub?
- Why store citations relationally instead of inside message JSON?
- Why use outbox rather than direct Kafka publish inside the transaction?
- Why is file-object reuse limited by space?
- How do you prevent stale indexes during reindex?
- How do you prevent unsafe URL ingestion?
- Why does personal distillation require confirmation?
- How do you keep memory useful without writing too much?
- How do you debug a bad answer end to end?

## 7. Answer Depth Rule

For this project, default to deep-answer mode:

- start at 2-minute depth
- include architecture boundary plus end-to-end flow
- include at least one tradeoff or failure-handling point
- include concrete module or class anchors when possible
