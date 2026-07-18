alter table memory_object
    add column latest_version_id varchar(36) null;

alter table memory_object
    add column current_version_no int not null default 0;

create table memory_version (
    id varchar(36) primary key,
    memory_object_id varchar(36) not null,
    workspace_id varchar(36) not null,
    version_no int not null,
    canonical_statement longtext not null,
    task_neighborhood_json longtext not null,
    compile_policy_json longtext null,
    forbidden_pattern_json longtext null,
    status varchar(32) not null,
    supersedes_version_id varchar(36) null,
    valid_from timestamp not null default current_timestamp,
    valid_to timestamp null,
    created_from_candidate_id varchar(36) null,
    policy_version varchar(64) not null,
    risk_score decimal(5,4) not null default 0.0000,
    scope_status varchar(32) not null default 'VALID',
    created_at timestamp not null default current_timestamp,
    constraint fk_memory_version_object foreign key (memory_object_id) references memory_object(id),
    constraint fk_memory_version_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_memory_version_candidate foreign key (created_from_candidate_id) references memory_candidate(id),
    constraint uq_memory_version_object_no unique (memory_object_id, version_no)
);

create index idx_memory_version_workspace_status
    on memory_version(workspace_id, status, valid_from);

create index idx_memory_object_workspace_latest
    on memory_object(workspace_id, latest_version_id, status);
