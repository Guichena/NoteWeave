create table artifact_video_knowledge_plan (
    id varchar(36) primary key,
    bundle_id varchar(36) not null,
    content_digest varchar(64) not null,
    plan_json longtext not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_video_knowledge_bundle foreign key (bundle_id)
        references artifact_video_material_bundle(id),
    constraint uq_artifact_video_knowledge_bundle unique (bundle_id)
);
