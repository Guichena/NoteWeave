alter table source_chunk add column projection_status varchar(32) not null default 'PENDING';
alter table source_chunk add column projected_at timestamp null;

create index idx_source_chunk_projection_status
    on source_chunk(source_snapshot_id, projection_status);
