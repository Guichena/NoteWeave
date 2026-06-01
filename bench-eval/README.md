# NoteWeave Benchmark Evaluation Workspace

This folder is intentionally separate from the Spring backend. It is for 2026 benchmark research, proportional sampling, and lightweight evaluation adapters before anything is promoted into `src/main/java/com/noteweave/rageval`.

## What This Covers

NoteWeave has two evaluation lanes:

- `team`: shared knowledge-base ingestion, hybrid RAG, citations, wiki retrieval, multi-turn workspace chat, and admin observability.
- `personal`: private research projects, source import, card/wiki compilation, artifact generation, synthesis, and citation-backed research output.

The current backend already has `rag_eval_case`, `rag_eval_run`, and `rag_eval_result` for team RAG. This workspace keeps benchmark-specific normalization and sampling independent so we can test small slices first.

## Files

- `PLAN.md`: overall execution plan and completion criteria.
- `docs/benchmark_selection_2026.md`: researched benchmark shortlist and NoteWeave fit.
- `docs/data_and_code_architecture.md`: data shapes, seed files, script responsibilities, and adapter roadmap.
- `docs/metrics_baselines_marketing.md`: metrics, baselines, competitors, and marketing guardrails.
- `docs/run_rounds.md`: R0-R5 evaluation rounds.
- `docs/marketing_result_template.md`: template to fill only after real run outputs exist.
- `benchmarks/registry.json`: machine-readable benchmark registry with source URLs and sample policy.
- `configs/sampling.default.json`: default proportional sampling plan.
- `configs/metrics.json`: machine-readable metric catalog.
- `configs/baselines.json`: internal baselines and external comparison targets.
- `configs/run_rounds.json`: machine-readable R0-R5 plan.
- `configs/downloads.json`: source URLs, target directories, and license check reminders.
- `configs/api_targets.example.json`: example local API target config.
- `configs/import_profiles.json`: import and run profiles for later execution.
- `configs/normalizers/*.json`: per-suite normalizer configs for generic adapter scripts.
- `schemas/normalized_case.schema.json`: normalized case schema used by the scripts.
- `schemas/run_manifest.schema.json`, `schemas/score_row.schema.json`, `schemas/api_import_result.schema.json`: file contracts for later execution outputs.
- `samples/enterprise_questions.sample.jsonl`: synthetic mini input for smoke tests.
- `data/team_seed_cases.jsonl`: team-lane seed cases.
- `data/personal_seed_cases.jsonl`: personal-lane seed cases.
- `scripts/noteweave_bench.py`: stdlib-only CLI for planning, normalizing, exporting, manifest generation, scoring, and summarizing.
- `scripts/normalize_generic.py`: generic config-driven normalizer for other suites.
- `scripts/noteweave_api.py`: dry-run first API import/run/collect helper.
- `scripts/download_sources.py`: dry-run first download helper.
- `scripts/build_doc_map.py`: local doc-id mapping helper for later internal-id scoring.
- `data/`: place normalized cases here.
- `downloads/`: place raw benchmark downloads here.
- `results/`: place model/system run outputs here.

## Quick Start

```powershell
python bench-eval/scripts/noteweave_bench.py plan
python bench-eval/scripts/noteweave_bench.py normalize-enterprise-rag `
  --input bench-eval/samples/enterprise_questions.sample.jsonl `
  --output bench-eval/data/enterprise_rag.normalized.sample.jsonl `
  --ratio 1.0
python bench-eval/scripts/noteweave_bench.py export-noteweave-rageval `
  --input bench-eval/data/enterprise_rag.normalized.sample.jsonl `
  --output bench-eval/data/enterprise_rag.noteweave_cases.sample.jsonl
```

For real data, download the benchmark into `bench-eval/downloads/`, run the matching normalizer, then import the exported `rag-eval-case` JSON into `/api/v1/admin/spaces/{spaceId}/rag-eval-cases`.

Additional planned commands:

```powershell
python bench-eval/scripts/noteweave_bench.py validate-cases `
  --inputs bench-eval/data/team_seed_cases.jsonl bench-eval/data/personal_seed_cases.jsonl
python bench-eval/scripts/noteweave_bench.py export-personal-manifest `
  --input bench-eval/data/personal_seed_cases.jsonl `
  --output bench-eval/data/personal_seed.manifest.jsonl
python bench-eval/scripts/noteweave_bench.py make-run-manifest --round-id R1
python bench-eval/scripts/normalize_generic.py normalize `
  --config bench-eval/configs/normalizers/mtrag_un.json `
  --input bench-eval/downloads/mtrag_un/raw.jsonl `
  --output bench-eval/data/mtrag_un.team.normalized.jsonl
python bench-eval/scripts/noteweave_api.py plan --profile-id team_enterprise_r1
python bench-eval/scripts/build_doc_map.py --input bench-eval/data/doc_map.example.jsonl --output bench-eval/data/doc_map.json
```

These commands prepare files only. They do not execute NoteWeave benchmark runs.

## Current Recommendation

Start with:

1. `EnterpriseRAG-Bench` for team knowledge RAG and citation retrieval.
2. `MTRAG-UN / MTRAGEval` for multi-turn workspace chat failures.
3. `ParseBench` for document ingestion quality before retrieval.
4. `AutoResearchBench` and `CiteRAG` for personal research and citation-backed artifact generation.
5. `AgenticRAGTracer` only after we evaluate multi-hop retrieval traces, because it is more agentic than NoteWeave's current simple RAG loop.
