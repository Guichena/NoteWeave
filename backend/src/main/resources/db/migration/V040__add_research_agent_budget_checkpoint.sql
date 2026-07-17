-- MA3C: task budget ledger and coordinator-owned checkpoint high-water marks.

create table research_budget_reservation (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_agent_task_id varchar(36) not null,
    idempotency_key varchar(200) not null,
    reserved_json longtext not null,
    consumed_json longtext not null,
    released_json longtext not null,
    state varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    settled_at timestamp null,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_budget_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_budget_task foreign key (research_agent_task_id) references research_agent_task(id)
);

create unique index uq_research_budget_task on research_budget_reservation(research_agent_task_id);
create unique index uq_research_budget_idempotency on research_budget_reservation(research_run_id, idempotency_key);

create table research_agent_checkpoint (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    checkpoint_seq int not null,
    wave_no int not null,
    round_no int not null,
    plan_revision int not null,
    entity_set_version int not null,
    ledger_hash varchar(128) not null,
    task_high_water_mark bigint not null,
    candidate_high_water_mark bigint not null,
    merge_high_water_mark bigint not null,
    budget_summary_json longtext not null,
    summary_json longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_agent_checkpoint_run foreign key (research_run_id) references research_run(id)
);

create unique index uq_research_agent_checkpoint_seq on research_agent_checkpoint(research_run_id, checkpoint_seq);
create index idx_research_agent_checkpoint_latest on research_agent_checkpoint(research_run_id, checkpoint_seq desc);
