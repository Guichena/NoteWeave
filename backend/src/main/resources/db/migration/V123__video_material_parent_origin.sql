-- Existing PDF Job material keeps its original owner. New material-only tasks
-- belong to one parent request and do not need a placeholder PDF Job.
alter table artifact_video_material_bundle
    modify column artifact_job_id varchar(36) null;

alter table artifact_video_material_bundle
    add column video_learning_request_id varchar(36) null;

alter table artifact_video_material_bundle
    add constraint fk_artifact_video_bundle_parent
        foreign key (video_learning_request_id) references video_learning_request(id);

alter table artifact_video_material_bundle
    add constraint ck_artifact_video_bundle_one_origin
        check ((artifact_job_id is null and video_learning_request_id is not null)
            or (artifact_job_id is not null and video_learning_request_id is null));

create unique index uq_artifact_video_bundle_parent_version
    on artifact_video_material_bundle(video_learning_request_id, bundle_version);
