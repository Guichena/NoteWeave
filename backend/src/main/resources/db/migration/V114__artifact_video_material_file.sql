create table artifact_video_material_file (
    id varchar(36) primary key,
    bundle_id varchar(36) not null,
    file_id varchar(100) not null,
    media_type varchar(32) not null,
    storage_backend varchar(32) not null,
    bucket_name varchar(120) not null,
    object_key varchar(512) not null,
    size_bytes bigint not null,
    checksum_sha256 varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_video_material_file_bundle foreign key (bundle_id)
        references artifact_video_material_bundle(id),
    constraint uq_artifact_video_material_file_id unique (bundle_id, file_id),
    constraint uq_artifact_video_material_object_key unique (bucket_name, object_key)
);
