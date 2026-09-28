create table artifact_video_material_bundle (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    artifact_job_id varchar(36) not null,
    task_id varchar(36) not null,
    bundle_id varchar(120) not null,
    bundle_version int not null,
    bvid varchar(24) not null,
    part_no int not null,
    input_digest varchar(64) not null,
    content_digest varchar(64) not null,
    material_json longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_video_bundle_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_artifact_video_bundle_job foreign key (artifact_job_id) references artifact_job(id),
    constraint fk_artifact_video_bundle_task foreign key (task_id) references task(id),
    constraint uq_artifact_video_bundle_task_version unique (task_id, bundle_version)
);

create index idx_artifact_video_bundle_workspace_source
    on artifact_video_material_bundle(workspace_id, bvid, part_no, created_at);
