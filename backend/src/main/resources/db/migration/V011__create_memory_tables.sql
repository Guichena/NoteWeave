create table memory_signal (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    user_id varchar(36) not null,
    source_type varchar(64) not null,
    source_id varchar(128) null,
    signal_type varchar(64) not null,
    signal_text clob not null,
    task_neighborhood varchar(64) not null,
    compile_hints_json clob null,
    confidence_score decimal(5,4) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_memory_signal_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_memory_signal_user foreign key (user_id) references users(id)
);

create index idx_memory_signal_workspace_created on memory_signal(workspace_id, created_at);
create index idx_memory_signal_workspace_neighborhood on memory_signal(workspace_id, task_neighborhood, created_at);

create table memory_candidate (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    user_id varchar(36) not null,
    candidate_type varchar(64) not null,
    normalized_statement clob not null,
    task_neighborhood_json clob not null,
    evidence_gate_status varchar(32) not null,
    novelty_score decimal(5,4) not null,
    marginal_utility_score decimal(5,4) not null,
    negative_memory_flag boolean not null default false,
    conflict_status varchar(32) not null,
    staleness_status varchar(32) not null,
    compile_policy_json clob null,
    forbidden_pattern_json clob null,
    created_from_signal_ids_json clob not null,
    review_status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_memory_candidate_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_memory_candidate_user foreign key (user_id) references users(id)
);

create index idx_memory_candidate_workspace_review on memory_candidate(workspace_id, review_status, created_at);

create table memory_object (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    user_id varchar(36) not null,
    memory_type varchar(64) not null,
    memory_scope varchar(32) not null,
    canonical_statement clob not null,
    task_neighborhood_json clob not null,
    compile_policy_json clob null,
    forbidden_pattern_json clob null,
    ledger_json clob null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_memory_object_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_memory_object_user foreign key (user_id) references users(id)
);

create index idx_memory_object_workspace_status on memory_object(workspace_id, status, created_at);

create table memory_usage_log (
    id varchar(36) primary key,
    memory_object_id varchar(36) not null,
    workspace_id varchar(36) not null,
    task_type varchar(64) not null,
    target_type varchar(64) not null,
    target_id varchar(128) not null,
    compiled_as varchar(64) not null,
    used_at timestamp not null default current_timestamp,
    effect_feedback varchar(255) null,
    constraint fk_memory_usage_object foreign key (memory_object_id) references memory_object(id),
    constraint fk_memory_usage_workspace foreign key (workspace_id) references workspace(id)
);

create index idx_memory_usage_target on memory_usage_log(workspace_id, task_type, target_type, used_at);
