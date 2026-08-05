alter table research_agent_outbox add column lease_owner varchar(128) null;
alter table research_agent_outbox add column lease_until timestamp null;
alter table research_agent_outbox add column attempt_count int not null default 0;
alter table research_agent_outbox add column next_attempt_at timestamp null;
alter table research_agent_outbox add column last_error varchar(1000) null;
alter table research_agent_outbox add column dead_lettered_at timestamp null;

create index idx_research_agent_outbox_dispatch
    on research_agent_outbox(status, next_attempt_at, lease_until, created_at);
