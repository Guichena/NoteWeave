# Evaluation Rounds

This file defines the planned run rounds. No formal run should start until the data slice and run variant are confirmed.

## R0: Schema and Code Smoke

Purpose:

- Validate that benchmark rows can be normalized.
- Validate that team cases can be exported to current `rag_eval_case` request JSONL.
- Validate that personal cases can be exported as an execution manifest.

Data:

- `samples/enterprise_questions.sample.jsonl`
- `data/team_seed_cases.jsonl`
- `data/personal_seed_cases.jsonl`

Variants:

- none; this round validates file shape only.

Outputs:

- normalized JSONL
- exported team request JSONL
- exported personal manifest JSONL
- validation report

Stop condition:

- Every row has `case_id`, `suite`, `lane`, `scenario`, `query`, and `tags`.

## R1: Team Mini Slice

Purpose:

- Prove end-to-end team RAG evaluation on a small external benchmark slice.

Data:

- EnterpriseRAG-Bench, 10% capped at 50 cases.
- Gold docs plus controlled distractors.

Variants:

- `bm25_only`
- `vector_only`
- `hybrid_rrf`
- `hybrid_rrf_with_wiki`

Metrics:

- `recall_at_k`
- `mrr`
- `selected_evidence_rate`
- `citation_coverage`
- `citation_precision`
- `no_answer_accuracy`
- `latency_ms`
- `total_tokens`

Outputs:

- `results/r1_team_enterprise.responses.{variant}.jsonl`
- `results/r1_team_enterprise.scores.{variant}.jsonl`
- `results/r1_team_enterprise.summary.json`

Stop condition:

- At least 95% of cases finish without system error.
- Every answer has trace metadata.

## R2: Team Multi-Turn Slice

Purpose:

- Evaluate workspace chat runtime on multi-turn failure modes.

Data:

- MTRAG-UN / MTRAGEval, 5% capped at 40 cases.

Variants:

- `last_turn_only`
- `conversation_concat`
- `context_rewrite`
- `context_rewrite_with_trace`

Metrics:

- `turn_level_retrieval_recall`
- `answerability_classification`
- `context_carryover_accuracy`
- `stale_context_resistance`
- `groundedness_score`
- `latency_ms`

Outputs:

- per-turn retrieval trace
- conversation-level score summary
- failure buckets by challenge type

Stop condition:

- Each failed conversation is bucketed into retrieval, rewrite, generation, or answerability failure.

## R3: Ingestion and Parser Slice

Purpose:

- Separate document parsing quality from retrieval quality.

Data:

- ParseBench, 2.5% capped at 50 pages/cases.

Variants:

- `current_parser`
- `current_parser_with_layout_metadata`
- future parser improvements

Metrics:

- `parse_pass_rate`
- `table_structure_accuracy`
- `chart_data_accuracy`
- `content_faithfulness`
- `format_semantics_accuracy`
- `visual_grounding_accuracy`

Outputs:

- parser score JSONL
- failed-page gallery/manifest
- downstream RAG blocked-by-parser report

Stop condition:

- Parser-related failures are clearly separated from retriever/model failures.

## R4: Personal Research Slice

Purpose:

- Evaluate personal research source discovery, artifact generation, citation support, and synthesis.

Data:

- AutoResearchBench, first 5 Deep + 5 Wide tasks, then 5% capped at 20.
- CiteRAG, 1% capped at 200 cases.

Variants:

- `personal_sources_only`
- `personal_cards_only`
- `personal_cards_plus_synthesis`
- `artifact_no_methodology`
- `artifact_with_methodology`

Metrics:

- `source_recall`
- `target_source_accuracy`
- `research_completeness`
- `citation_recall`
- `citation_precision`
- `artifact_groundedness`
- `synthesis_precision`

Outputs:

- personal execution manifest
- artifact score JSONL
- citation score JSONL
- synthesis quality report

Stop condition:

- Personal result can explain which source/card/artifact step lost quality.

## R5: Marketing Candidate Run

Purpose:

- Produce credible numbers for README, demo, interview story, or product positioning.

Data:

- Frozen samples from R1-R4.
- No cherry-picking after the sample is frozen.

Variants:

- current best NoteWeave variant for each lane
- strongest internal baseline for each lane

Metrics:

- one primary metric per suite
- two supporting metrics per suite
- latency/cost guardrails
- failure bucket table

Outputs:

- `results/marketing_candidate.summary.json`
- `docs/marketing_result_template.md` filled with real numbers
- screenshots or dashboard captures only after UI run is approved

Stop condition:

- Claims are backed by reproducible run manifest, frozen input sample, and result files.
