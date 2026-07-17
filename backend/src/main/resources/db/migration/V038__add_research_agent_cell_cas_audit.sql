-- MA3A: server-side canonical cell CAS and append-only candidate/merge audit.
-- Legacy SEQUENTIAL_V1 continues to use the pre-existing snapshot projection.

alter table research_cell add column cell_version int not null default 0;
alter table research_cell add column plan_revision int not null default 0;
alter table research_cell add column entity_set_version int not null default 0;
alter table research_cell add column last_merge_id varchar(128) null;
alter table research_cell add column active_task_id varchar(128) null;
alter table research_cell add column lease_epoch int not null default 0;
alter table research_cell add column fencing_token bigint not null default 0;

create index idx_research_cell_agent_cas
    on research_cell(research_run_id, cell_key, cell_version, plan_revision, entity_set_version);

create table research_agent_candidate (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(128) not null,
    execution_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    cell_key varchar(160) not null,
    base_cell_version int not null,
    plan_revision int not null,
    entity_set_version int not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    candidate_value longtext not null,
    evidence_ids_json longtext not null,
    confidence_score decimal(5,4) not null,
    submitted_at timestamp not null default current_timestamp,
    constraint fk_research_agent_candidate_run foreign key (research_run_id) references research_run(id)
);

create unique index uq_research_agent_candidate_idempotency
    on research_agent_candidate(research_run_id, idempotency_key);
create index idx_research_agent_candidate_cell
    on research_agent_candidate(research_run_id, cell_key, base_cell_version);

create table research_cell_merge (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    candidate_id varchar(36) not null,
    merge_key varchar(160) not null,
    cell_key varchar(160) not null,
    expected_cell_version int not null,
    result_cell_version int not null,
    verdict varchar(32) not null,
    decision varchar(32) not null,
    reason_code varchar(96) not null,
    accepted_evidence_ids_json longtext null,
    merged_at timestamp not null default current_timestamp,
    constraint fk_research_cell_merge_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_cell_merge_candidate foreign key (candidate_id) references research_agent_candidate(id)
);

create unique index uq_research_cell_merge_key on research_cell_merge(research_run_id, merge_key);
create index idx_research_cell_merge_cell on research_cell_merge(research_run_id, cell_key, merged_at);
