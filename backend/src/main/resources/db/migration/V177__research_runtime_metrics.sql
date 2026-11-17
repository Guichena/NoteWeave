create table research_runtime_metric (
    event_seq bigint not null auto_increment unique,
    completion_id varchar(36) not null primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    role varchar(32) not null,
    query_count int not null,
    read_page_count int not null,
    llm_calls bigint not null,
    provider_call_count bigint null,
    input_tokens bigint null,
    output_tokens bigint null,
    duration_ms bigint null,
    extracted_fact_count int not null,
    qualified_support_count int not null,
    rejection_counts_json json not null,
    document_fingerprints_json json not null,
    duplicate_document_count int not null,
    unresolved_conflict_count int not null default 0,
    resolved_conflict_count int not null default 0,
    created_at timestamp not null default current_timestamp,
    constraint fk_runtime_metric_completion foreign key (completion_id) references research_agent_completion(id)
);
create index idx_runtime_metric_run on research_runtime_metric(research_run_id, created_at, completion_id);
create table research_cell_gain (
    event_seq bigint not null auto_increment unique,
    completion_id varchar(36) not null,
    research_run_id varchar(36) not null,
    cell_key varchar(255) not null,
    plan_revision int not null,
    entity_set_version int not null,
    eligible_fact_count int not null,
    satisfied_requirement_count int not null,
    new_fact_count int not null,
    new_requirement_count int not null,
    conflict_open boolean not null default false,
    resolved_conflict_count int not null default 0,
    created_at timestamp not null default current_timestamp,
    primary key(completion_id, cell_key),
    constraint fk_cell_gain_completion foreign key (completion_id) references research_agent_completion(id)
);
create index idx_cell_gain_scope on research_cell_gain(research_run_id, cell_key, plan_revision, entity_set_version, created_at);
