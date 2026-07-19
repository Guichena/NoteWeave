create table retrieval_projection (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    projection_type varchar(32) not null,
    entity_id varchar(36) not null,
    source_id varchar(36) not null,
    source_snapshot_id varchar(36) not null,
    content_hash varchar(128) not null,
    embedding_text_hash varchar(128) not null,
    embedding_provider varchar(64) not null,
    embedding_model varchar(160) not null,
    embedding_dimensions int not null,
    embedding_version varchar(160) not null,
    index_schema_version varchar(64) not null,
    target_index varchar(255) not null,
    status varchar(32) not null,
    attempt_count int not null default 0,
    last_error_code varchar(96) null,
    next_retry_at timestamp null,
    projected_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_retrieval_projection_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_retrieval_projection_source foreign key (source_id) references source(id),
    constraint fk_retrieval_projection_snapshot foreign key (source_snapshot_id) references source_snapshot(id),
    constraint chk_retrieval_projection_type check (projection_type in ('QA_CHUNK', 'NOTE_SOURCE')),
    constraint chk_retrieval_projection_status check (status in ('PENDING', 'EMBEDDING', 'INDEXING', 'READY', 'FAILED', 'STALE')),
    constraint chk_retrieval_projection_dimensions check (embedding_dimensions > 0),
    constraint chk_retrieval_projection_attempts check (attempt_count >= 0),
    constraint uk_retrieval_projection_version unique (
        projection_type, entity_id, source_snapshot_id, embedding_version, index_schema_version, target_index
    )
);

create index idx_retrieval_projection_status_retry
    on retrieval_projection(status, next_retry_at, updated_at);

create index idx_retrieval_projection_workspace_type_status
    on retrieval_projection(workspace_id, projection_type, status);

create index idx_retrieval_projection_snapshot_type
    on retrieval_projection(source_snapshot_id, projection_type, status);

create table retrieval_index_build (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    projection_type varchar(32) not null,
    from_index varchar(255) null,
    target_index varchar(255) not null,
    embedding_provider varchar(64) not null,
    embedding_model varchar(160) not null,
    embedding_dimensions int not null,
    embedding_version varchar(160) not null,
    index_schema_version varchar(64) not null,
    status varchar(32) not null,
    expected_count bigint not null default 0,
    ready_count bigint not null default 0,
    failed_count bigint not null default 0,
    last_error_code varchar(96) null,
    alias_switched_at timestamp null,
    started_at timestamp null,
    completed_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_retrieval_index_build_workspace foreign key (workspace_id) references workspace(id),
    constraint chk_retrieval_index_build_type check (projection_type in ('QA_CHUNK', 'NOTE_SOURCE')),
    constraint chk_retrieval_index_build_status check (status in ('CREATED', 'BACKFILLING', 'VERIFYING', 'SWITCHING', 'COMPLETED', 'FAILED')),
    constraint chk_retrieval_index_build_dimensions check (embedding_dimensions > 0),
    constraint chk_retrieval_index_build_counts check (
        expected_count >= 0 and ready_count >= 0 and failed_count >= 0
    ),
    constraint uk_retrieval_index_build_target unique (workspace_id, projection_type, target_index)
);

create index idx_retrieval_index_build_workspace_status
    on retrieval_index_build(workspace_id, status, updated_at);

create table retrieval_quality_receipt (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    plan_version varchar(96) not null,
    dataset_version varchar(160) not null,
    report_version varchar(160) not null,
    report_sha256 varchar(80) not null,
    passed boolean not null,
    case_count int not null,
    scope_violation_count int not null,
    current_snapshot_error_count int not null,
    citation_ownership_error_count int not null,
    degraded_case_count int not null,
    ablation_complete boolean not null,
    created_by varchar(80) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_retrieval_quality_receipt_workspace foreign key (workspace_id) references workspace(id),
    constraint chk_retrieval_quality_receipt_counts check (
        case_count > 0 and scope_violation_count >= 0 and current_snapshot_error_count >= 0
        and citation_ownership_error_count >= 0 and degraded_case_count >= 0
    )
);

create index idx_retrieval_quality_receipt_workspace_plan
    on retrieval_quality_receipt(workspace_id, plan_version, created_at);
