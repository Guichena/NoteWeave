alter table source_snapshot add column embedding_task_id varchar(36) null;
alter table source_snapshot add column embedding_batch_version varchar(64) null;

create table source_embedding_batch (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    source_id varchar(36) not null,
    source_snapshot_id varchar(36) not null,
    task_id varchar(36) not null,
    batch_version varchar(64) not null,
    embedding_version varchar(300) not null,
    chunker_version varchar(300) not null,
    context_prompt_version varchar(300) not null,
    batch_no int not null,
    chunk_offset int not null,
    chunk_count int not null,
    total_chunks int not null,
    total_batches int not null,
    chunk_digest varchar(64) not null,
    source_metadata_digest varchar(64) not null,
    status varchar(32) not null default 'READY',
    lease_token varchar(36) null,
    lease_until timestamp null,
    context_model_calls int not null default 0,
    embedding_request_budget int not null,
    embeddings_object_key varchar(500) null,
    embeddings_sha256 varchar(64) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_embedding_batch_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_embedding_batch_source foreign key (source_id) references source(id),
    constraint fk_embedding_batch_snapshot foreign key (source_snapshot_id) references source_snapshot(id),
    constraint fk_embedding_batch_task foreign key (task_id) references task(id),
    constraint uk_embedding_batch_version unique (source_snapshot_id, batch_version, batch_no)
);

create index idx_embedding_batch_lease on source_embedding_batch(status, lease_until);
