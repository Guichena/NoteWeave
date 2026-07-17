-- MA3B: durable task lease lifecycle.  Candidate/cell merge remains MA3A.

create table research_agent_task (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_key varchar(160) not null,
    idempotency_key varchar(200) not null,
    wave_no int not null,
    role varchar(48) not null,
    entity_id varchar(160) not null,
    branch_id varchar(96) not null,
    plan_revision int not null,
    entity_set_version int not null,
    target_cells_json longtext not null,
    budget_json longtext not null,
    status varchar(32) not null,
    lease_epoch int not null default 0,
    fencing_token bigint not null default 0,
    worker_instance_id varchar(128) null,
    lease_expires_at timestamp null,
    attempt_count int not null default 0,
    terminal_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_agent_task_run foreign key (research_run_id) references research_run(id)
);

create unique index uq_research_agent_task_run_key
    on research_agent_task(research_run_id, task_key);
create unique index uq_research_agent_task_idempotency
    on research_agent_task(research_run_id, idempotency_key);
create index idx_research_agent_task_claim
    on research_agent_task(research_run_id, status, lease_expires_at);

create table research_agent_execution (
    id varchar(36) primary key,
    research_agent_task_id varchar(36) not null,
    execution_key varchar(160) not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    worker_instance_id varchar(128) not null,
    status varchar(32) not null,
    termination_reason varchar(256) null,
    usage_json longtext null,
    trace_digest varchar(128) null,
    submitted_at timestamp not null default current_timestamp,
    constraint fk_research_agent_execution_task foreign key (research_agent_task_id) references research_agent_task(id)
);

create unique index uq_research_agent_execution_key
    on research_agent_execution(research_agent_task_id, execution_key);
create index idx_research_agent_execution_task
    on research_agent_execution(research_agent_task_id, submitted_at);
