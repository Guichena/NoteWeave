alter table artifact_version add column origin_task_id varchar(36) null;

update artifact_version
set origin_task_id = (
    select aj.task_id from artifact_job aj where aj.id = artifact_version.artifact_job_id
)
where origin_task_id is null;

alter table artifact_version
    add constraint fk_artifact_version_origin_task foreign key (origin_task_id) references task(id);

create index idx_artifact_version_origin_task on artifact_version(origin_task_id);

create table artifact_job_run (
    task_id varchar(36) primary key,
    artifact_job_id varchar(36) not null,
    run_no int not null,
    trigger_type varchar(32) not null,
    source_version_no int null,
    user_requirement longtext not null,
    inputs_json longtext not null,
    source_scope_json longtext not null,
    control_pack_json longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_job_run_task foreign key (task_id) references task(id),
    constraint fk_artifact_job_run_job foreign key (artifact_job_id) references artifact_job(id)
);

insert into artifact_job_run(
    task_id, artifact_job_id, run_no, trigger_type, source_version_no,
    user_requirement, inputs_json, source_scope_json, control_pack_json
)
select task_id, id, 1, 'INITIAL', null, coalesce(user_requirement, ''),
       coalesce(inputs_json, '{}'), source_scope_json, control_pack_json
from artifact_job;

create unique index uq_artifact_job_run_no on artifact_job_run(artifact_job_id, run_no);
create index idx_artifact_job_run_job_created on artifact_job_run(artifact_job_id, created_at);

create table artifact_file (
    id varchar(36) primary key,
    artifact_version_id varchar(36) not null,
    file_format varchar(16) not null,
    file_name varchar(300) not null,
    media_type varchar(128) not null,
    storage_backend varchar(32) not null,
    bucket_name varchar(128) not null,
    object_key varchar(1000) not null,
    size_bytes bigint not null,
    checksum_sha256 varchar(64) not null,
    status varchar(32) not null,
    error_message varchar(1000) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_file_version foreign key (artifact_version_id) references artifact_version(id)
);

create unique index uq_artifact_file_version_format on artifact_file(artifact_version_id, file_format);
create index idx_artifact_file_version on artifact_file(artifact_version_id, created_at);

alter table task_outbox add column lease_owner varchar(128) null;
alter table task_outbox add column lease_until timestamp null;
alter table task_outbox add column dead_lettered_at timestamp null;

create index idx_task_outbox_lease on task_outbox(topic, status, lease_until, created_at);
