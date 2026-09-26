-- M4-A (D-37): hydration idempotency + explicit resume decision provenance.
--
-- Before this migration a canonical-ledger hydration had no explicit idempotency key:
-- hydrate() re-copied the whole ledger under fresh ids on every call and only stopped
-- by accident when a copied table happened to carry a unique index. research_verifier_decision
-- has only a plain index, so a partial replay silently duplicated decision rows.
--
-- (hydrated_from_research_run_id, hydrated_from_checkpoint_seq) is the explicit replay key.
-- resume_mode_reason records why the effective resume mode was chosen, so an AUTO request that
-- could not hydrate is persisted as an observable degradation instead of a silent fallback.
--
-- Expand-only and nullable: every pre-existing Run keeps NULL markers (never a hydration
-- source, never a hydrated descendant, never a guessed decision reason). No historical row is
-- backfilled, and the unique index tolerates unlimited NULL keys.
alter table research_run
    add column hydrated_from_research_run_id varchar(36) null;

alter table research_run
    add column hydrated_from_checkpoint_seq int null;

alter table research_run
    add column hydrated_source_ledger_digest varchar(128) null;

alter table research_run
    add column resume_mode_reason varchar(64) null;

-- Defence in depth for concurrent hydrations of the same (source run, source checkpoint):
-- the deterministic replay rejection is an explicit pre-read in the hydrator, not this index.
create unique index uq_research_run_hydration_source
    on research_run(hydrated_from_research_run_id, hydrated_from_checkpoint_seq);
