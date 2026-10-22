-- 需求覆盖与单元格代表值分开保存，事实原文仍沿用既有证据归档。
create table research_requirement (
    requirement_id varchar(64) primary key,
    research_run_id varchar(36) not null,
    cell_key varchar(160) not null,
    plan_revision int not null,
    entity_set_version int not null,
    requirement_text varchar(1000) not null,
    entity_label varchar(1000) not null,
    origin varchar(32) not null,
    mandatory boolean not null default true,
    as_of_date varchar(10) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_requirement_run foreign key (research_run_id) references research_run(id)
);
create index idx_requirement_scope on research_requirement(research_run_id, cell_key, plan_revision, entity_set_version);

create table research_requirement_fact (
    fact_id varchar(64) primary key,
    research_run_id varchar(36) not null,
    cell_key varchar(160) not null,
    plan_revision int not null,
    entity_set_version int not null,
    claim_text longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_requirement_fact_run foreign key (research_run_id) references research_run(id)
);
create index idx_requirement_fact_scope on research_requirement_fact(research_run_id, cell_key, plan_revision, entity_set_version);

create table research_requirement_fact_support (
    support_id varchar(64) primary key,
    fact_id varchar(64) not null,
    completion_id varchar(36) not null,
    cluster_key varchar(64) not null,
    evidence_keys_json json not null,
    qualification_status varchar(32) not null,
    reason_codes_json json not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_requirement_support_fact foreign key (fact_id) references research_requirement_fact(fact_id),
    constraint fk_requirement_support_completion foreign key (completion_id) references research_agent_completion(id)
);
create index idx_requirement_support_fact on research_requirement_fact_support(fact_id, qualification_status);

create table research_requirement_observation (
    observation_id varchar(64) primary key,
    requirement_id varchar(64) not null,
    completion_id varchar(36) not null,
    fact_ids_json json not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_requirement_observation_requirement foreign key (requirement_id) references research_requirement(requirement_id),
    constraint fk_requirement_observation_completion foreign key (completion_id) references research_agent_completion(id)
);
create index idx_requirement_observation_requirement on research_requirement_observation(requirement_id);

create table research_requirement_progress (
    completion_id varchar(36) primary key,
    research_run_id varchar(36) not null,
    new_fact_count int not null,
    new_satisfied_requirement_count int not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_requirement_progress_completion foreign key (completion_id) references research_agent_completion(id),
    constraint fk_requirement_progress_run foreign key (research_run_id) references research_run(id)
);
create index idx_requirement_progress_run on research_requirement_progress(research_run_id, created_at);
