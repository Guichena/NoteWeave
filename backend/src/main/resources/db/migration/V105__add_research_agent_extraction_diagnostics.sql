-- DR-102: persist structured extraction diagnostics per execution.
-- Before this column, a zero-card extraction was indistinguishable between
-- "LLM not configured" (llm_calls=0), "invalid JSON", "unknown window",
-- "wrong column", and "non-exact quote"; all of them looked like empty usage.
-- This column carries the bounded diagnostics object (termination reason,
-- accepted/rejected counts, per-reason rejection counts, rejection samples and a
-- provider receipt digest) so every extraction outcome is explainable.
-- Expand-only and nullable: historical rows keep null instead of a guessed cause.
alter table research_agent_execution
    add column extraction_diagnostics_json longtext null after trace_digest;
