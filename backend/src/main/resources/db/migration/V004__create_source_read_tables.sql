create table source_chunk (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    source_id varchar(36) not null,
    source_snapshot_id varchar(36) not null,
    chunk_no int not null,
    heading varchar(300) null,
    content clob not null,
    token_estimate int not null,
    location_info varchar(300) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_source_chunk_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_source_chunk_source foreign key (source_id) references source(id),
    constraint fk_source_chunk_snapshot foreign key (source_snapshot_id) references source_snapshot(id)
);

create index idx_source_chunk_snapshot_no on source_chunk(source_snapshot_id, chunk_no);

create table source_window (
    id varchar(36) primary key,
    source_chunk_id varchar(36) not null,
    window_no int not null,
    content clob not null,
    location_info varchar(300) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_source_window_chunk foreign key (source_chunk_id) references source_chunk(id)
);
