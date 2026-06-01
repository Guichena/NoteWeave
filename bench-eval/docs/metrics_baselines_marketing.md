# Metrics, Baselines, Comparisons, and Marketing

Snapshot date: 2026-05-27.

This document defines what to measure, who to compare against, and how to turn results into credible product messaging without overstating claims.

## 1. Metrics

### Team Retrieval Metrics

| Metric | Meaning | Why it matters |
|---|---|---|
| `recall_at_k` | Whether expected sources appear in top-k retrieved items. | Basic retrieval correctness. |
| `mrr` | Reciprocal rank of the first expected source. | Rewards putting useful evidence early. |
| `source_type_recall` | Recall by source type: doc, wiki, chat-derived page, table, etc. | Shows whether one source dominates or fails. |
| `selected_evidence_rate` | Ratio of retrieved items that become final prompt evidence. | Detects noisy retrieval. |
| `evidence_sufficiency` | Whether selected evidence is enough to answer. | More useful than rank-only metrics. |
| `conflict_resolution_accuracy` | Whether the answer uses the latest/authoritative source when sources conflict. | Directly maps to enterprise messy-knowledge use. |
| `no_answer_accuracy` | Correctly refuses when evidence is absent. | Prevents confident hallucination. |

### Team Generation and Citation Metrics

| Metric | Meaning | Why it matters |
|---|---|---|
| `answer_fact_coverage` | Expected facts covered by the answer. | Measures usefulness, not just exact match. |
| `groundedness_score` | Claims supported by retrieved evidence. | Core trust metric. |
| `citation_coverage` | Key claims contain citations. | User-facing evidence habit. |
| `citation_precision` | Citation actually supports the cited claim. | Avoids decorative citations. |
| `answer_completeness` | Covers all requested parts. | Important for multi-document questions. |
| `format_following` | Uses requested structure or constraints. | Useful for work outputs. |

### Multi-Turn Workspace Metrics

| Metric | Meaning | Why it matters |
|---|---|---|
| `context_carryover_accuracy` | Correct use of prior turns. | Measures workspace chat behavior. |
| `query_rewrite_success` | Current turn is rewritten with needed context. | Finds non-standalone failures. |
| `stale_context_resistance` | Ignores irrelevant old turns. | Prevents drift. |
| `answerability_classification` | Detects unanswerable/underspecified turns. | Maps to MTRAG-UN challenge classes. |
| `turn_level_retrieval_recall` | Retrieval quality per turn. | Finds which turn failed. |

### Ingestion and Parsing Metrics

| Metric | Meaning | Why it matters |
|---|---|---|
| `parse_pass_rate` | Page/case passes semantic parse checks. | Upstream quality gate for RAG. |
| `table_structure_accuracy` | Rows, columns, and cell relations preserved. | Critical for enterprise docs. |
| `chart_data_accuracy` | Visual chart values retained. | Prevents wrong numeric answers. |
| `format_semantics_accuracy` | headings, lists, emphasis, key-value blocks preserved. | Affects chunking and answer grounding. |
| `visual_grounding_accuracy` | Visual elements linked to text meaning. | Important for scanned/PDF docs. |

### Personal Research Metrics

| Metric | Meaning | Why it matters |
|---|---|---|
| `source_recall` | Expected papers/sources discovered or used. | Core research discovery metric. |
| `citation_recall` | Expected citations surfaced. | Maps to CiteRAG. |
| `citation_precision` | Suggested citations are relevant. | Avoids noisy bibliographies. |
| `research_completeness` | Covers constraints and subtopics. | Measures Wide Research quality. |
| `target_source_accuracy` | Finds the exact target paper/source. | Measures Deep Research quality. |
| `artifact_groundedness` | Artifact sections are supported by personal sources. | Product trust metric. |
| `synthesis_precision` | Synthesis cards capture stable reusable insight. | Measures long-term personal wiki value. |

### Operations Metrics

| Metric | Meaning | Why it matters |
|---|---|---|
| `latency_ms` | End-to-end or stage-level latency. | User experience and cost. |
| `input_tokens` | Prompt/context size. | Cost and context pressure. |
| `output_tokens` | Response size. | Cost and verbosity. |
| `total_tokens` | Full call token cost. | Budgeting. |
| `error_rate` | Failed cases divided by total cases. | Reliability. |
| `trace_completeness` | Each answer has retriever, evidence, prompt version, and citation trace. | Debuggability. |

## 2. Internal Baselines

These are the first comparisons because they prove NoteWeave's design choices.

| Baseline | Team | Personal | Purpose |
|---|---:|---:|---|
| `bm25_only` | yes | later | Measures lexical baseline. |
| `vector_only` | yes | later | Measures embedding baseline. |
| `hybrid_rrf` | yes | later | Current intended team RAG default. |
| `hybrid_rrf_no_wiki` | yes | no | Measures value of wiki recall. |
| `hybrid_rrf_with_wiki` | yes | no | Measures team wiki benefit. |
| `no_memory_context` | yes | personal later | Measures workspace memory impact. |
| `personal_sources_only` | no | yes | Measures raw source-to-artifact quality. |
| `personal_cards_only` | no | yes | Measures card/wiki compilation value. |
| `personal_cards_plus_synthesis` | no | yes | Measures synthesis reuse. |
| `artifact_no_methodology` | no | yes | Measures generation without methodology preset. |
| `artifact_with_methodology` | no | yes | Measures MethodologyCard impact. |

## 3. External Comparisons

### Benchmark Leaderboards and Papers

Use these for orientation, not as direct product claims unless our setup matches their official evaluation.

| Benchmark | Compare against | Use in messaging |
|---|---|---|
| EnterpriseRAG-Bench | Public harness / leaderboard and reported baseline systems. | "Evaluated on enterprise-style internal knowledge scenarios." |
| MTRAGEval | SemEval-2026 Task 8 retrieval/generation/end-to-end submissions. | "Covers multi-turn RAG failure modes, including unanswerable and non-standalone turns." |
| ParseBench | Published parser baselines such as VLM parsers, specialized parsers, LlamaParse variants. | "Measures parser semantic correctness before RAG." |
| AutoResearchBench | Reported agent baselines for Deep Research and Wide Research. | "Tests literature discovery and research-source grounding." |
| CiteRAG | Paper retriever/generator baselines. | "Tests citation recommendation quality, not just answer text." |
| AgenticRAGTracer | Hop-level agentic RAG baselines. | "Used for trace diagnosis after multi-hop evaluation matures." |

### Product Competitors

These are product positioning comparisons, not always apples-to-apples benchmarks.

| Category | Examples | Comparison angle |
|---|---|---|
| Enterprise search / RAG | Glean, Onyx, Danswer-style systems, Elastic AI search | Internal knowledge retrieval, source connectors, citation quality. |
| Team knowledge + wiki | Notion AI, Confluence AI, Slite/knowledge-base AI | From answer to durable wiki/page output. |
| Personal research | Elicit, Perplexity Deep Research, Gemini Deep Research, ChatGPT Deep Research | Literature discovery, citation-backed synthesis, artifact generation. |
| Document parsing | LlamaParse, Unstructured, Azure AI Document Intelligence, Adobe/PDF extraction stacks | Parser correctness before RAG. |
| Eval tooling | RAGAS, DeepEval, TruLens, LangSmith, Arize Phoenix | Observability and evaluation workflow. |

## 4. Marketing Positioning

### Core Claim

NoteWeave is an evidence-first AI knowledge workbench that evaluates both team knowledge collaboration and personal research workflows, not only single-turn chatbot answers.

### Strong Messages If Metrics Support Them

- "Benchmarked on 2026 enterprise-style RAG, multi-turn RAG, document parsing, and research-discovery datasets."
- "Team and personal knowledge workflows are evaluated separately, with metrics matched to each workflow."
- "Every answer can be traced through retrieval, selected evidence, citations, prompt version, and run metadata."
- "The evaluation includes failure modes: missing information, conflicting sources, underspecified follow-ups, and non-standalone turns."
- "Wiki and synthesis are measured as durable knowledge outputs, not decorative side effects."

### Claims To Avoid Until Proven

- Do not claim "state of the art" unless we run the official harness and beat named baselines.
- Do not compare directly with Glean, Notion AI, Perplexity, or Deep Research products unless we test the same public task and methodology.
- Do not claim full enterprise readiness from synthetic benchmark scores alone.
- Do not collapse team and personal scores into one vanity number.

### Demo Story

1. Import a messy team corpus slice.
2. Ask a question requiring cross-document evidence.
3. Show answer, citations, and retrieval trace.
4. Publish or retrieve related wiki content.
5. Switch to personal research.
6. Import paper/source slice.
7. Generate an artifact with citations.
8. Distill stable findings into personal wiki/synthesis.
9. Show eval dashboard: retrieval, citation, groundedness, latency, and failure cases.

## 5. Public Result Format

Use a compact results table:

| Lane | Suite | Sample | Primary metric | Baseline | NoteWeave variant | Delta |
|---|---|---:|---|---|---|---:|
| team | EnterpriseRAG-Bench | 50 | recall@k | bm25_only | hybrid_rrf_with_wiki | TBD |
| team | MTRAG-UN | 34 | answerability_accuracy | no_rewrite | context_rewrite | TBD |
| team | ParseBench | 50 | parse_pass_rate | default_parser | improved_parser | TBD |
| personal | AutoResearchBench | 20 | source_recall | sources_only | cards_plus_synthesis | TBD |
| personal | CiteRAG | 159 | citation_recall | naive_keyword | personal_retrieval | TBD |
