create table file_object (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    object_key varchar(500) not null,
    sha256 varchar(64) not null,
    file_size bigint not null,
    mime_type varchar(160) null,
    ref_count int not null default 1,
    created_at timestamp not null default current_timestamp,
    constraint fk_file_object_workspace foreign key (workspace_id) references workspace(id),
    constraint uk_file_object_workspace_hash unique (workspace_id, sha256)
);

create table source (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    file_object_id varchar(36) not null,
    title varchar(300) not null,
    source_type varchar(64) not null,
    status varchar(32) not null,
    parse_status varchar(32) not null,
    index_status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_source_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_source_file_object foreign key (file_object_id) references file_object(id)
);

create index idx_source_workspace_status_updated on source(workspace_id, status, updated_at);
create index idx_source_workspace_parse_index on source(workspace_id, parse_status, index_status);

create table source_snapshot (
    id varchar(36) primary key,
    source_id varchar(36) not null,
    file_object_id varchar(36) not null,
    version_no int not null,
    object_key varchar(500) not null,
    sha256 varchar(64) not null,
    parse_status varchar(32) not null,
    index_status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_source_snapshot_source foreign key (source_id) references source(id),
    constraint fk_source_snapshot_file_object foreign key (file_object_id) references file_object(id),
    constraint uk_source_snapshot_version unique (source_id, version_no)
);

create table document_upload (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    file_name varchar(300) not null,
    file_size bigint not null,
    mime_type varchar(160) null,
    chunk_size int not null,
    total_chunks int not null,
    status varchar(32) not null,
    uploaded_chunks int not null default 0,
    source_id varchar(36) null,
    task_id varchar(36) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_document_upload_workspace foreign key (workspace_id) references workspace(id)
);

create table upload_chunk (
    id varchar(36) primary key,
    upload_id varchar(36) not null,
    chunk_index int not null,
    content_md5 varchar(128) null,
    object_key varchar(500) not null,
    byte_size bigint not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_upload_chunk_upload foreign key (upload_id) references document_upload(id),
    constraint uk_upload_chunk_index unique (upload_id, chunk_index)
);
