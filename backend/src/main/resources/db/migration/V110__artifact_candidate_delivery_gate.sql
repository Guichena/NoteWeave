-- A reserved identity belongs to a Run. Only a successful Host commit creates a Version.
alter table artifact_job_run add column reserved_version_id varchar(36) null;
update artifact_job_run set reserved_version_id = uuid() where reserved_version_id is null;
alter table artifact_job_run modify column reserved_version_id varchar(36) not null;
create unique index uq_artifact_run_reserved_version on artifact_job_run(reserved_version_id);

-- Existing immutable versions remain readable. New writes enter READY only with files.
alter table artifact_version add column delivery_status varchar(32) not null default 'READY';

create table artifact_candidate_receipt (
    task_id varchar(36) primary key,
    candidate_id varchar(128) not null,
    candidate_digest varchar(64) not null,
    artifact_version_id varchar(36) not null,
    input_snapshot_id varchar(36) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_candidate_task foreign key (task_id) references task(id),
    constraint fk_artifact_candidate_version foreign key (artifact_version_id) references artifact_version(id)
);
create unique index uq_artifact_candidate_id on artifact_candidate_receipt(candidate_id);
