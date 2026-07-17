-- MA4D: immutable, server-authoritative execution scope for real agent executors.
-- Existing MA3/MA4A tasks remain transport-compatible but cannot enable MA4D execution
-- until the coordinator supplies these fields.

alter table research_agent_task
    add column target_bindings_json longtext null;
alter table research_agent_task
    add column execution_context_json longtext null;
alter table research_agent_task
    add column snapshot_schema_version varchar(64) null;
alter table research_agent_task
    add column snapshot_digest varchar(128) null;

create index idx_research_agent_task_snapshot_ready
    on research_agent_task(research_run_id, snapshot_schema_version, status);
