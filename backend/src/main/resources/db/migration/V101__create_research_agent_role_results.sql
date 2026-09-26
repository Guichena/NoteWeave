create table research_agent_role_result (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_agent_task_id varchar(36) not null,
    completion_id varchar(36) not null,
    role varchar(48) not null,
    result_schema_version varchar(64) not null,
    result_status varchar(32) not null,
    input_digest varchar(71) not null,
    result_digest varchar(71) not null,
    payload_json json not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_role_result_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_role_result_task foreign key (research_agent_task_id) references research_agent_task(id),
    constraint fk_research_role_result_completion foreign key (completion_id) references research_agent_completion(id)
);

create unique index uq_research_role_result_completion on research_agent_role_result(completion_id);
create index idx_research_role_result_run_role on research_agent_role_result(research_run_id, role, result_status);

create table research_agent_synthesis_candidate (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    role_result_id varchar(36) not null,
    ledger_digest varchar(71) not null,
    audit_digest varchar(71) not null,
    markdown longtext not null,
    claims_json json not null,
    limitations_json json not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_synthesis_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_synthesis_task foreign key (task_id) references research_agent_task(id),
    constraint fk_research_synthesis_role_result foreign key (role_result_id) references research_agent_role_result(id)
);

create index idx_research_synthesis_candidate_run_status
    on research_agent_synthesis_candidate(research_run_id, status, created_at);
