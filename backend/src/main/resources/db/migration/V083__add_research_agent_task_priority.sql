-- MA4I follow-up: durable, auditable scheduling priority for bounded dispatch.

alter table research_agent_task
    add column priority_score int not null default 0;

alter table research_agent_task
    add column priority_reason varchar(128) not null default 'LEGACY_FIFO';

create index idx_research_agent_task_priority
    on research_agent_task(status, wave_no, priority_score);
