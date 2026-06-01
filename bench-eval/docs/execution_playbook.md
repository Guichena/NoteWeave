# Execution Playbook

This playbook is for later execution. It is prepared now, but not executed while business code is still changing.

## Default Rule

- Do not run detection, validation, tests, or benchmark scoring unless explicitly asked for that round.
- Treat scripts as preparation artifacts until the run is approved.
- Use dry-run mode by default for every new helper script.

## R0 Prepared Steps

1. Validate seed file shapes with `scripts/noteweave_bench.py validate-cases`.
2. Generate a run manifest with `scripts/noteweave_bench.py make-run-manifest --round-id R0`.
3. Export team seed cases to the current backend JSONL shape.
4. Export personal seed cases to a workflow manifest JSONL.

## R1 Prepared Steps

1. Keep EnterpriseRAG-Bench core questions separate from metadata questions.
2. Keep core slice and metadata slice in separate files.
3. Use `scripts/normalize_generic.py` or `scripts/noteweave_bench.py normalize-enterprise-rag` depending on the raw format.
4. If the corpora are ingested into NoteWeave later, create a document-id map and regenerate team export files that contain internal document ids.

## R2 Prepared Steps

1. Normalize multi-turn conversations with the `mtrag_un` config.
2. Store each turn in the conversation field.
3. Keep answerability and non-standalone labels in tags.
4. Do not merge conversation tasks into single-turn RAG cases.

## R3 Prepared Steps

1. Download only the smallest parse slice first.
2. Keep parser results separate from retrieval results.
3. Record parse failures by capability dimension.

## R4 Prepared Steps

1. Export personal research cases into a workflow manifest.
2. Attach source ids, expected citations, and artifact type.
3. Keep personal metrics separate from team metrics.

## R5 Prepared Steps

1. Freeze the sample manifest.
2. Run only the comparison variants listed in `configs/baselines.json`.
3. Produce a marketing summary only from frozen outputs.

## Approved Non-Detection Helpers

- `scripts/normalize_generic.py`
- `scripts/noteweave_api.py`
- `scripts/download_sources.py`
- `scripts/noteweave_bench.py`

## What Remains Unexecuted

- No backend import has been started.
- No evaluation run has been triggered.
- No score or summary has been generated from a model response.
- No production-facing claim has been finalized.
