# 2026 Benchmark Shortlist for NoteWeave

Snapshot date: 2026-05-27.

This file records benchmarks that are large enough to matter but can be sampled down into a small local evaluation set. The goal is not to chase leaderboard coverage. The goal is to pick slices that stress NoteWeave's actual product paths: team knowledge RAG, workspace chat, document ingestion, personal research, artifact generation, citations, and traceability.

## Best Fits

| Priority | Benchmark | Lane | Why it fits NoteWeave | Initial sample |
|---|---|---|---|---|
| P0 | EnterpriseRAG-Bench | team | Synthetic company corpus with internal source types similar to team knowledge spaces: Slack, Gmail, GitHub, Jira, Confluence, Drive-like docs. It explicitly tests missing info, conflicts, constrained retrieval, and multi-document reasoning. | 10% of questions, capped at 50; ingest only gold docs plus distractors first. |
| P0 | MTRAG-UN / MTRAGEval | team | Multi-turn RAG conversation benchmark with answerability, underspecified, non-standalone, and unclear-response cases. This maps to workspace chat runtime and retrieval trace debugging. | 5% of tasks, stratified by domain and challenge type. |
| P1 | ParseBench | team + personal ingestion | Tests whether PDF/document parsing preserves tables, charts, formatting, and visual grounding before chunking and indexing. This catches failures before RAG scoring. | 2-5 pages per capability dimension first. |
| P1 | AutoResearchBench | personal | Scientific literature discovery with Deep Research and Wide Research task types. This maps to personal source collection, research projects, and artifact generation. | 5 Deep + 5 Wide tasks, then expand by 5% slices. |
| P1 | CiteRAG | personal | Academic citation prediction over a large paper corpus. This is useful for personal research answers and artifact citation quality. | 1% of each task, capped at 100 cases per task. |
| P2 | AgenticRAGTracer | team + personal advanced retrieval | Hop-aware multi-step retrieval benchmark with intermediate validation. Useful once NoteWeave trace evaluation needs multi-hop diagnosis. | 5%, capped at 65 cases. |
| P2 | Text-and-table RAG benchmarks | team | Financial or mixed text-table documents stress hybrid retrieval and parser quality. Use after ParseBench because bad parsing will dominate. | 1-2% by document type. |

## Source Notes

- EnterpriseRAG-Bench: arXiv `2605.05253`, last revised 2026-05-19. The abstract reports roughly 500k synthetic enterprise documents, nine source types, 500 questions, ten categories, plus public code/data/eval harness. Source: https://arxiv.org/abs/2605.05253 and https://github.com/onyx-dot-app/EnterpriseRAG-Bench
- MTRAG-UN: arXiv `2602.23184`, submitted 2026-02-26. The abstract reports 666 tasks and over 2,800 conversation turns across six domains, focused on answerability and multi-turn failures. Source: https://arxiv.org/abs/2602.23184
- MTRAGEval / SemEval-2026 Task 8: official task page for multi-turn RAG conversations, with Task A retrieval, Task B generation with passages, and Task C end-to-end RAG. Source: https://ibm.github.io/mt-rag-benchmark/MTRAGEval/
- ParseBench: arXiv `2604.08538`, submitted 2026-04-09. The abstract describes about 2,000 human-verified pages across enterprise documents and five parsing dimensions. Source: https://arxiv.org/abs/2604.08538 and https://github.com/run-llama/ParseBench
- AutoResearchBench: arXiv `2604.25256`, submitted 2026-04-28. It defines Deep Research and Wide Research tasks for scientific literature discovery and releases data/code. Source: https://arxiv.org/abs/2604.25256 and https://github.com/CherYou/AutoResearchBench
- CiteRAG: arXiv `2601.14949`, revised 2026-01-26, WWW 2026. It defines coarse list-specific and fine position-specific citation prediction, with 7,267 and 8,541 instances and a 554k-paper corpus. Source: https://arxiv.org/abs/2601.14949
- AgenticRAGTracer: arXiv `2602.19127`, submitted 2026-02-22. It focuses on step-by-step validation for multi-step retrieval reasoning in agentic RAG. Source: https://arxiv.org/abs/2602.19127 and https://github.com/YqjMartin/AgenticRAGTracer

## NoteWeave Scenario Mapping

### Team Lane

Use team lane for:

- document upload, parse, chunk, index
- hybrid BM25/vector/wiki retrieval
- multi-source evidence selection
- citation coverage
- no-answer behavior
- retrieval trace inspection
- workspace multi-turn query rewrite and context carryover

Best benchmark order:

1. ParseBench small slice to verify ingestion quality.
2. EnterpriseRAG-Bench question slice for end-to-end RAG.
3. MTRAG-UN / MTRAGEval for multi-turn failures.
4. AgenticRAGTracer after trace schema supports hop-level expected sources.

### Personal Lane

Use personal lane for:

- research source collection
- article/concept/synthesis card quality
- artifact generation with citations
- methodology-aware generation
- source provenance and literature discovery

Best benchmark order:

1. AutoResearchBench for source discovery and research project quality.
2. CiteRAG for citation recommendation and citation-backed artifact sections.
3. ParseBench for PDFs that become personal sources.
4. AgenticRAGTracer only for multi-hop personal answering once personal retrieval APIs exist.

## Sampling Rules

Use proportional sampling, but always keep failure classes:

- Preserve all explicit `unanswerable`, `underspecified`, `conflict`, `missing`, or `unclear` labels when possible.
- Stratify by domain/source type/task category before random sampling.
- Keep deterministic seeds in every sample manifest.
- For giant corpora, ingest only gold documents, required neighbors, and controlled distractors first.
- Keep team and personal splits in separate output files to avoid mixing product assumptions.

## What Not To Do Yet

- Do not import full 500k-document corpora into local dev before the small slice has signal.
- Do not compare team and personal scores directly; their workflows and success criteria differ.
- Do not use citation text overlap alone as proof of groundedness.
- Do not let benchmark runs write normal user chat history or memory.
