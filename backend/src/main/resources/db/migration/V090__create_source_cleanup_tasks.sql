create table source_cleanup_task (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    source_id varchar(36) not null,
    cleanup_type varchar(32) not null,
    projection_type varchar(32) null,
    bucket_name varchar(128) null,
    object_key varchar(1000) null,
    object_key_sha256 char(64) null,
    status varchar(32) not null default 'READY',
    attempt_count int not null default 0,
    next_attempt_at timestamp null,
    lease_owner varchar(128) null,
    lease_until timestamp null,
    last_error varchar(1000) null,
    dead_lettered_at timestamp null,
    completed_at timestamp null,
    created_at timestamp not null default current_timestamp,
    constraint fk_source_cleanup_workspace foreign key (workspace_id) references workspace(id),
    constraint chk_source_cleanup_type check (cleanup_type in ('RETRIEVAL_PROJECTION', 'SOURCE_OBJECT')),
    constraint chk_source_cleanup_status check (status in ('READY', 'PROCESSING', 'COMPLETED', 'DEAD_LETTER')),
    constraint chk_source_cleanup_target check (
        (cleanup_type = 'RETRIEVAL_PROJECTION' and projection_type is not null
            and bucket_name is null and object_key is null and object_key_sha256 is null)
        or (cleanup_type = 'SOURCE_OBJECT' and projection_type is null
            and bucket_name is not null and object_key is not null and object_key_sha256 is not null)
    ),
    constraint uq_source_cleanup_projection unique (source_id, cleanup_type, projection_type),
    constraint uq_source_cleanup_object unique (source_id, cleanup_type, object_key_sha256)
);

create index idx_source_cleanup_dispatch
    on source_cleanup_task(status, next_attempt_at, lease_until, created_at);
