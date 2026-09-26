-- DR-305: persist the business terminal decision produced by RunCompletionGate.
-- Before this migration a non-finalizable incremental run could only end as
-- research_run.status = 'FAILED' with the opaque task reason
-- RESEARCH_AGENT_TERMINAL_BARRIER_UNSATISFIED, which flattened "evidence was
-- insufficient" into the same bucket as "the provider is not configured".
-- These columns keep research_run.status inside the existing
-- RUNNING/COMPLETED/FAILED/CANCELLED vocabulary (so every existing terminal
-- guard keeps working) while recording the business terminal state separately:
--   COMPLETED_VERIFIED | COMPLETED_WITH_LIMITATIONS | INSUFFICIENT_EVIDENCE | INFRASTRUCTURE_FAILURE
-- Expand-only and nullable: runs that terminated before this migration keep
-- their historical shape instead of receiving a guessed terminal state.
alter table research_run
    add column completion_terminal_state varchar(32) null;

alter table research_run
    add column completion_reason_codes_json longtext null;

alter table research_run
    add column completion_unresolved_cells_json longtext null;

alter table research_run
    add column completion_limitations_json longtext null;

alter table research_run
    add column completion_promotable_claims_json longtext null;

create index idx_research_run_completion_terminal_state
    on research_run(completion_terminal_state, updated_at);
