---
name: noteweave-interview-review
description: Review the NoteWeave project from an interview perspective. Use when Codex needs to help explain this repository, map interview questions to real modules, prune or rewrite the local question bank under `面试准备`, prepare scenario-solution-benefit answers, or run mock interviewer drills based on the actual code, docs, and tests.
---

# NoteWeave Interview Review

## Goal

Turn the NoteWeave repository into reusable interview assets instead of a plain code walkthrough. Focus on project intro, module mapping, deep follow-up answers, tradeoffs, and truthful boundaries.

Default answer depth is the deep-answer version:

- answer as if the user is giving a 2-minute minimum interview response
- do not default to short summary answers unless the user explicitly asks for a short version
- prioritize architecture, implementation path, tradeoffs, and failure handling over high-level slogans

## Start Here

1. Rebuild the current truth of the project before giving interview answers.
2. Read only what is needed from:
   - `README.md`
   - `docs/PROJECT_STATUS.md`
   - `docs/implementation_breakdown.md`
   - `docs/features/database_api_blueprint.md`
   - the controller, service, worker, test, and migration files for the module being discussed
3. Treat `面试准备/*.md` as raw material, not final truth.
4. Preserve the original `面试准备` files unless the user explicitly asks to overwrite them.

## Truth Rules

1. Prefer actual code and tests over planning docs when they disagree.
2. If `docs/PROJECT_STATUS.md` lags behind the code, say that explicitly and prefer code/test/migration evidence.
3. Separate three layers in every answer:
   - `已实现`
   - `设计目标`
   - `后续可扩展`
4. Never invent numbers such as QPS, token/day, online scale, or latency benchmarks if the repo does not provide them.
5. Do not claim NoteWeave has unrelated capabilities such as microservice拆分, 分库分表, 秒杀链路, 本地二级缓存, RL training, full GraphRAG mainline, or fully autonomous multi-agent orchestration unless the current repo really implements them.

## Resume Guardrails

When the user is preparing for real interviews based on a resume, keep the wording practical and interview-facing:

1. For repo-backed mainline capabilities, answer them externally as things the user has done.
2. Then naturally add what was optimized, what tradeoff was handled, or what would be improved next.
3. If a capability is only partially supported by the repo, move it out of the main intro and only use it in future-optimization answers.
4. If the resume says something slightly ahead of the repo, restate it into an honest engineering form instead of deleting it outright.
5. Do not explain internal classification systems to the interviewer; the goal is strong but truthful ownership, not taxonomy.

## Resume Risk Areas For NoteWeave

Pay extra attention to these phrases because interviewers may drill down fast:

- `Hybrid RAG`
- `长期记忆`
- `Artifact / Wiki 分层`
- `Skill 执行`
- `Redis runtime state`
- `MCP`
- `Bibtex`

Recommended handling:

- `Hybrid RAG`, `长期记忆`, `Artifact/Wiki`, `Methodology`, `Observability/Admin/Ops`: safe to present as implemented and still improvable
- `Skill`: present as a plan-based generation pipeline plus skill logging, not as a fully open-ended agent platform
- `Redis runtime state`: present as Redis-backed WebSocket runtime state, resume control, and session execution coordination; do not expand it into a fake main async queue story
- `MCP`: present as a controlled tool-extension path. The repo already has Studio Bilibili MCP support, but it should not be described as a universal open MCP platform.
- `Bibtex`: only present as future-ready or easy extension unless stronger repo evidence is added

## Recommended Modes

Choose the mode that best fits the user's request:

- `overview`: prepare a 1-minute and 3-minute project intro
- `overview`: prepare a deep project intro first, and only compress to short versions if the user asks
- `module-drill`: deep-dive one module and its likely follow-ups
- `question-bank`: clean, rewrite, or expand interview questions
- `answer-pack`: draft polished answers for selected questions
- `mock-interview`: ask one question at a time and critique the user's answer
- `mock-review-loop`: run mock interview rounds, record weaknesses, and target them in the next round

## Interview Style Preference

Default to a big-tech interviewer style unless the user asks otherwise:

1. Prioritize architecture, design boundaries, feature implementation details, tradeoffs, and failure handling.
2. Treat project-intro questions as warm-up, not the main body.
3. Prefer questions like:
   - why this architecture
   - why not an obvious alternative
   - how a feature actually works end to end
   - where consistency, permissions, or idempotency are enforced
   - what breaks under retry, replay, deletion, reindex, or disconnect
4. Keep pushing from “what it does” to “how it is built” and then to “why it is designed this way”.

## Core Workflow

1. Use `references/project-map.md` to identify the module boundary and the strongest talking points.
2. Use `references/interview-outline.md` to choose a review order.
3. Use `references/question-bank.md` to select or rewrite questions.
4. For each important module, explain it in this order:
   - `场景`: what problem this module solves
   - `方案`: what NoteWeave actually implements
   - `收益`: what benefit this design brings
   - `权衡`: why this design instead of obvious alternatives
   - `追问`: what a strong interviewer will ask next
5. When preparing speaking material, compress each module into:
   - `2 分钟深答版本`
   - default answer
   - deeper follow-up expansion if the interviewer keeps drilling
6. Anchor answers to concrete repository evidence whenever useful:
   - package names
   - controller/service/worker names
   - migration names
   - integration tests
7. When the user asks for mock interview, also read and maintain `references/mock-loop.md`.

## What Good NoteWeave Answers Usually Emphasize

- dual-space design: `PERSONAL` and `TEAM`
- permission-first resource access
- unified `Task + Outbox + Kafka + Worker` async backbone
- upload -> parse -> chunk -> index ingestion
- hybrid retrieval, evidence post-processing, citation traceability
- WebSocket runtime with `DRAFT` vs `FORMAL`
- personal research pipeline from `Source` to cards to artifacts to synthesis
- long-term memory writeback boundaries
- wiki graph and auto-maintenance loop
- observability, eval, and admin/ops support

## Coaching Rules

1. If a question from `面试准备` is off-project, rewrite it into a NoteWeave-specific question.
2. If the user wants to sound stronger, improve the structure and depth, not the facts.
3. If a question asks for metrics the repo does not have, answer with:
   - what would be measured
   - where the instrumentation already exists
   - how to validate it honestly
4. When doing mock interview, challenge vague answers with concrete follow-ups from the cleaned question bank.
5. In multi-round mock interview, prefer exposing recurring weak points instead of maximizing question breadth.
6. Unless the user explicitly asks for a short answer, draft all interview answers in a 2-minute-depth form by default.

## Mock Interview Loop

Use this workflow when the user wants repeatable mock interview practice:

1. Read `references/mock-loop.md` first.
2. Ask only one main question at a time.
3. After the user's answer, give a compact review in four parts:
   - `评价`: overall judgment
   - `不足点`: what was weak, vague, risky, or missing
   - `改进口径`: how to answer it better next time
   - `追问`: 1 to 3 follow-up questions
4. Record the result into `面试准备/05-mock-面试记录.md` after each round.
5. For each round, update:
   - date
   - main question
   - weakness tags
   - key missing points
   - suggested improved wording
6. At the start of the next mock interview, review the previous record and prioritize:
   - recurring weakness tags
   - modules the user answered vaguely
   - resume risk terms the user explained weakly
7. If the user improves on a previously weak topic, mark it as improved instead of repeating the same criticism forever.
8. Keep the record in Chinese and optimized for practical reuse.
9. In big-tech mock mode, prefer module-level deep dives over broad product chatting.

## References

- Read `references/project-map.md` first for module boundaries and doc-vs-code truth notes.
- Read `references/interview-outline.md` when the user wants a review syllabus or speaking outline.
- Read `references/question-bank.md` when the user wants cleaned questions, added deep dives, or mock interview prompts.
- Read `references/mock-loop.md` when the user wants repeated mock interviews with weakness tracking.
