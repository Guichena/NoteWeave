-- 宽搜索摘要只支持范围提案，不授予正式证据资格。
alter table research_discovery_proposal add column supporting_domain_count int not null default 0;
alter table research_discovery_proposal add column supports_json json null;
create table research_discovery_observation (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    observation_key varchar(64) not null,
    query_text varchar(200) not null,
    source_domain varchar(300) not null,
    lineage_digest varchar(64) not null,
    payload_json json not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_discovery_observation_run foreign key (research_run_id) references research_run(id),
    constraint fk_discovery_observation_task foreign key (task_id) references research_agent_task(id)
);
create unique index uq_discovery_observation_task on research_discovery_observation(task_id, observation_key);
create table research_discovery_loop (
    research_run_id varchar(36) primary key,
    phase varchar(32) not null,
    stop_reason varchar(64) null,
    limits_json json not null,
    updated_at timestamp not null default current_timestamp,
    constraint fk_discovery_loop_run foreign key (research_run_id) references research_run(id)
);
create table research_discovery_round (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    round_no int not null,
    task_id varchar(36) not null,
    round_status varchar(32) not null,
    new_proposal_count int not null default 0,
    accepted_proposal_count int not null default 0,
    query_count int not null default 0,
    queries_json json null,
    created_at timestamp not null default current_timestamp,
    constraint fk_discovery_round_run foreign key (research_run_id) references research_run(id),
    constraint fk_discovery_round_task foreign key (task_id) references research_agent_task(id)
);
create unique index uq_discovery_round_number on research_discovery_round(research_run_id, round_no);
create unique index uq_discovery_round_task on research_discovery_round(task_id);
