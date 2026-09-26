-- M4 phase 1 (D-39): MA4 task-level recovery observability for re-executions.
--
-- When a DEEP_CELL task is re-claimed after a lease expiry or worker death, the new
-- execution starts from a fresh usage counter: nothing in the schema used to know how many
-- provider tool rounds the abandoned attempt had already been authorized to spend. The task
-- budget reservation therefore bounded a single attempt instead of the whole task lifetime,
-- so a restart silently reset the effective consumption ceiling.
--
-- research_agent_tool_grant is the durable, server-side record of every tool round the
-- server authorized for a task, keyed by lease identity. One row per (task, lease, tool):
-- re-requesting a permit inside the same lease is idempotent (the worker retries transport,
-- not the provider), while a new lease always inserts a new row. Atomic completion then
-- charges `granted leases before the current one + the current attempt's reported usage`
-- against the reservation, so the abandoned attempt keeps counting.
--
-- Expand-only and nullable-free: this is a new table, so no historical row is touched and
-- every pre-existing task simply reads as "no recorded prior round".
create table research_agent_tool_grant (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_agent_task_id varchar(36) not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    worker_instance_id varchar(160) not null,
    tool_identity varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_agent_tool_grant_run
        foreign key (research_run_id) references research_run(id),
    constraint fk_research_agent_tool_grant_task
        foreign key (research_agent_task_id) references research_agent_task(id),
    constraint uk_research_agent_tool_grant_lease
        unique (research_agent_task_id, lease_epoch, fencing_token, tool_identity)
);

create index idx_research_agent_tool_grant_task
    on research_agent_tool_grant(research_agent_task_id, tool_identity);
