-- MA4A: agent-task commands have their own outbox; they are not top-level task_outbox records.
create table research_agent_outbox (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_agent_task_id varchar(36) not null,
    topic varchar(160) not null,
    message_key varchar(200) not null,
    payload_json longtext not null,
    status varchar(32) not null,
    sent_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_agent_outbox_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_agent_outbox_task foreign key (research_agent_task_id) references research_agent_task(id)
);

create unique index uq_research_agent_outbox_task on research_agent_outbox(research_agent_task_id);
create index idx_research_agent_outbox_ready on research_agent_outbox(status, created_at);
