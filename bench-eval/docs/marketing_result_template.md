# Marketing Result Template

Do not fill this with claims until R5 has real outputs.

## Headline

NoteWeave was evaluated on sampled 2026 benchmarks covering enterprise RAG, multi-turn RAG, document parsing, and personal research workflows.

## Result Table

| Lane | Benchmark | Sample | NoteWeave variant | Baseline | Primary metric | Result | Delta |
|---|---|---:|---|---|---|---:|---:|
| team | EnterpriseRAG-Bench | TBD | TBD | TBD | recall@k | TBD | TBD |
| team | MTRAG-UN | TBD | TBD | TBD | answerability_accuracy | TBD | TBD |
| team | ParseBench | TBD | TBD | TBD | parse_pass_rate | TBD | TBD |
| personal | AutoResearchBench | TBD | TBD | TBD | source_recall | TBD | TBD |
| personal | CiteRAG | TBD | TBD | TBD | citation_recall | TBD | TBD |

## Safe Copy

- "Evaluated on sampled 2026 benchmark tasks."
- "Team and personal workflows are measured separately."
- "Each run records sources, citations, traces, and failure buckets."

## Unsafe Copy

- "State of the art" without official leaderboard parity.
- "Beats Glean/Notion/Perplexity" without identical test setup.
- "Production enterprise-ready" from synthetic benchmark results alone.

## Demo Script

1. Show frozen sample manifest.
2. Show team corpus ingestion.
3. Ask a cross-document team question.
4. Show answer with citation and trace.
5. Show failed/no-answer case handling.
6. Switch to personal research project.
7. Generate citation-backed artifact.
8. Show score summary and failure buckets.
