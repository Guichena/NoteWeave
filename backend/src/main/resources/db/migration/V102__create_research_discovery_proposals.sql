create table research_discovery_proposal (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    role_result_id varchar(36) not null,
    proposal_key varchar(160) not null,
    proposal_type varchar(32) not null,
    candidate_key varchar(160) not null,
    label varchar(500) not null,
    search_query longtext not null,
    source_lead varchar(500) not null,
    source_domain varchar(255) null,
    lineage_digest varchar(71) null,
    rationale longtext not null,
    confidence_ppm int not null,
    plan_revision int not null,
    entity_set_version int not null,
    proposal_status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_discovery_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_discovery_task foreign key (task_id) references research_agent_task(id),
    constraint fk_research_discovery_role_result foreign key (role_result_id) references research_agent_role_result(id),
    constraint ck_research_discovery_confidence check (confidence_ppm between 0 and 1000000)
);

create unique index uq_research_discovery_proposal_key
    on research_discovery_proposal(research_run_id, plan_revision, proposal_key);

create index idx_research_discovery_proposal_status
    on research_discovery_proposal(research_run_id, proposal_status, created_at);
