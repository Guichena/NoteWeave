# Data and Code Architecture

This document explains how benchmark data and evaluation code are organized before any run happens.

## 1. Data Types

### Raw Downloads

Raw external data goes under:

```text
bench-eval/downloads/{suite_id}/
```

Do not edit raw downloads. Keep license notes and source URLs beside them.

### Normalized Cases

Normalized cases go under:

```text
bench-eval/data/{suite_id}.{lane}.normalized.jsonl
```

They all follow:

```text
bench-eval/schemas/normalized_case.schema.json
```

This normalized shape is the stable bridge between external benchmark formats and NoteWeave.

### Exported Team Cases

Team cases are exported to the current backend RAG eval request shape:

```text
bench-eval/data/{suite_id}.team.noteweave_cases.jsonl
```

Each row maps to:

```text
POST /api/v1/admin/spaces/{spaceId}/rag-eval-cases
```

Fields:

- `name`
- `queryText`
- `expectedAnswer`
- `expectedSourceJson`
- `tagsJson`
- `enabled`

### Exported Personal Manifests

Personal cases are exported to workflow manifests:

```text
bench-eval/data/{suite_id}.personal.manifest.jsonl
```

Personal eval is not forced into `rag_eval_case` because the workflow is not just one RAG query. A case may require:

```text
research project -> source import -> compile -> artifact generation -> citation/synthesis scoring
```

### Response and Score Files

After approved runs, outputs should go under:

```text
bench-eval/results/
```

Expected shapes:

- `*.responses.jsonl`
- `*.scores.jsonl`
- `*.summary.json`
- `*.failure_buckets.json`

## 2. Seed Data

Seed data exists to stabilize schemas before downloading large benchmark corpora.

| File | Lane | Purpose |
|---|---|---|
| `data/team_seed_cases.jsonl` | team | Conflict, no-answer, and multi-turn team RAG cases. |
| `data/personal_seed_cases.jsonl` | personal | Literature discovery, citation recommendation, and artifact generation cases. |
| `samples/enterprise_questions.sample.jsonl` | team | EnterpriseRAG-style adapter smoke input. |

## 3. Code Layout

Primary script:

```text
bench-eval/scripts/noteweave_bench.py
```

Supported command families:

| Command | Purpose | Executes a real benchmark run? |
|---|---|---:|
| `plan` | Print planned sampling by suite. | no |
| `normalize-enterprise-rag` | Convert EnterpriseRAG-style JSONL into normalized cases. | no |
| `validate-cases` | Check normalized case shape. | no |
| `export-noteweave-rageval` | Convert normalized team cases into backend request JSONL. | no |
| `export-personal-manifest` | Convert normalized personal cases into personal workflow manifest. | no |
| `make-run-manifest` | Generate a planned run manifest from R0-R5 config. | no |
| `score` | Score a response JSONL after a run. | offline scoring only |
| `summarize` | Summarize a score JSONL. | offline summarization only |

Future backend API scripts should be separate:

- `noteweave_api_import.py`
- `noteweave_api_run.py`
- `noteweave_api_collect.py`

Those should only be added once the data slice and run variant are approved.

## 4. Adapter Roadmap

| Suite | Current adapter status | Next code needed |
|---|---|---|
| EnterpriseRAG-Bench | Basic question JSONL normalizer exists. | Add official raw file parser after download. |
| MTRAG-UN / MTRAGEval | Planned. | Add conversation-turn normalizer. |
| ParseBench | Planned. | Add page-level parser score input adapter. |
| AutoResearchBench | Planned. | Add Deep/Wide task normalizer. |
| CiteRAG | Planned. | Add citation prediction normalizer. |
| AgenticRAGTracer | Planned. | Add hop-level expected source normalizer. |

## 5. Guardrails

- Keep raw data, normalized data, and scored output separate.
- Do not mix team and personal cases in a single score table.
- Do not use external benchmark IDs as NoteWeave database IDs.
- Store external IDs in `tagsJson` and `expected_sources.external_doc_id`.
- Record sample seed and sample ratio in every manifest.
- Keep generated marketing claims separate from raw result files.
