-- 音视频图文快照与切片时间元数据，原视频不进入资料对象存储。
alter table source_snapshot add column source_url varchar(1024) null;
alter table source_snapshot add column selected_parts_json text null;
alter table source_snapshot add column media_document_json longtext null;

alter table source_chunk add column part int null;
alter table source_chunk add column start_ms bigint null;
alter table source_chunk add column end_ms bigint null;
alter table source_chunk add column frame_ids_json text null;
alter table source_chunk add column source_url varchar(1024) null;

create table source_media_frame (
    workspace_id varchar(36) not null,
    source_id varchar(36) not null,
    source_snapshot_id varchar(36) not null,
    frame_id varchar(100) not null,
    part int not null,
    at_ms bigint not null,
    object_key varchar(500) not null,
    media_type varchar(100) not null,
    primary key (source_snapshot_id, frame_id),
    constraint fk_source_media_frame_snapshot foreign key (source_snapshot_id) references source_snapshot(id)
);
create index idx_source_media_frame_source on source_media_frame(workspace_id, source_id, frame_id);
