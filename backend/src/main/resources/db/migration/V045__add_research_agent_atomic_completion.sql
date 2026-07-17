-- MA4G expand-only schema for one-transaction agent execution completion.
-- Historical rows remain nullable/LEGACY_UNBOUND; no provenance is guessed.

create table research_agent_completion (
    id varchar(36) primary key,
    research_agent_task_id varchar(36) not null,
    execution_id varchar(36) not null,
    completion_key varchar(160) not null,
    schema_version varchar(64) not null,
    envelope_digest varchar(71) not null,
    envelope_json longtext not null,
    envelope_size_bytes int not null,
    snapshot_digest varchar(71) not null,
    worker_instance_id varchar(128) not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    receipt_json longtext not null,
    receipt_digest varchar(71) not null,
    committed_at timestamp not null default current_timestamp,
    constraint fk_research_agent_completion_task
        foreign key (research_agent_task_id) references research_agent_task(id),
    constraint fk_research_agent_completion_execution
        foreign key (execution_id) references research_agent_execution(id)
);

create unique index uq_research_agent_completion_task
    on research_agent_completion(research_agent_task_id);
create unique index uq_research_agent_completion_execution
    on research_agent_completion(execution_id);
create unique index uq_research_agent_completion_task_key
    on research_agent_completion(research_agent_task_id, completion_key);
create index idx_research_agent_completion_digest
    on research_agent_completion(research_agent_task_id, envelope_digest);

alter table source_evidence add column agent_completion_id varchar(36) null;
alter table source_evidence add column content_digest varchar(71) null;
alter table source_evidence add column support_score_ppm int null;
alter table source_evidence add column conflict_score_ppm int null;
alter table source_evidence add constraint fk_source_evidence_agent_completion
    foreign key (agent_completion_id) references research_agent_completion(id);
create index idx_source_evidence_agent_completion
    on source_evidence(agent_completion_id, evidence_key);

alter table research_agent_candidate add column agent_completion_id varchar(36) null;
alter table research_agent_candidate add column content_digest varchar(71) null;
alter table research_agent_candidate add column research_agent_execution_id varchar(36) null;
alter table research_agent_candidate add column confidence_score_ppm int null;
alter table research_agent_candidate add constraint fk_research_agent_candidate_completion
    foreign key (agent_completion_id) references research_agent_completion(id);
alter table research_agent_candidate add constraint fk_research_agent_candidate_execution
    foreign key (research_agent_execution_id) references research_agent_execution(id);
create index idx_research_agent_candidate_completion
    on research_agent_candidate(agent_completion_id, idempotency_key);
create index idx_research_agent_candidate_execution
    on research_agent_candidate(research_agent_execution_id);

alter table research_cell_merge add column agent_completion_id varchar(36) null;
alter table research_cell_merge add column content_digest varchar(71) null;
alter table research_cell_merge add constraint fk_research_cell_merge_completion
    foreign key (agent_completion_id) references research_agent_completion(id);
create index idx_research_cell_merge_completion
    on research_cell_merge(agent_completion_id, merge_key);

alter table research_cell_evidence add column agent_completion_id varchar(36) null;
alter table research_cell_evidence add column content_digest varchar(71) null;
alter table research_cell_evidence add constraint fk_research_cell_evidence_completion
    foreign key (agent_completion_id) references research_agent_completion(id);
create index idx_research_cell_evidence_completion
    on research_cell_evidence(agent_completion_id, evidence_key);

alter table research_budget_reservation add column agent_completion_id varchar(36) null;
alter table research_budget_reservation add column settlement_key varchar(160) null;
alter table research_budget_reservation add column finalized_at timestamp null;
alter table research_budget_reservation add constraint fk_research_budget_agent_completion
    foreign key (agent_completion_id) references research_agent_completion(id);
create index idx_research_budget_agent_completion
    on research_budget_reservation(agent_completion_id);

alter table research_cell add column confidence_score_ppm int null;
