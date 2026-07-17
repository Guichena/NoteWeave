-- MA4B: retry/cancellation lifecycle and one task-to-many command deliveries.

alter table research_agent_task
    add column next_attempt_at timestamp null;
alter table research_agent_task
    add column max_attempts int not null default 3;
alter table research_agent_task
    add column terminal_reason varchar(128) null;
alter table research_agent_task
    add column cancelled_at timestamp null;

create index idx_research_agent_task_ready
    on research_agent_task(status, next_attempt_at, lease_expires_at);

alter table research_agent_outbox
    add column delivery_no int not null default 1;

create table research_agent_delivery_failure (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_agent_task_id varchar(36) not null,
    research_agent_outbox_id varchar(36) null,
    failure_key varchar(200) not null,
    reason_code varchar(128) not null,
    trace_digest varchar(128) not null,
    delivery_attempt int not null,
    redrive_status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    redriven_at timestamp null,
    constraint fk_research_agent_delivery_failure_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_agent_delivery_failure_task foreign key (research_agent_task_id) references research_agent_task(id),
    constraint fk_research_agent_delivery_failure_outbox foreign key (research_agent_outbox_id) references research_agent_outbox(id)
);

create unique index uq_research_agent_delivery_failure_key
    on research_agent_delivery_failure(research_agent_task_id, failure_key);
create index idx_research_agent_delivery_failure_run
    on research_agent_delivery_failure(research_run_id, created_at);
