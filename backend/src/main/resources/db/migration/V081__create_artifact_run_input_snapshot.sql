create table artifact_run_input_snapshot (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    artifact_job_id varchar(36) not null,
    user_requirement longtext not null,
    inputs_json longtext not null,
    source_scope_snapshot_json longtext not null,
    upstream_refs_json longtext not null,
    control_pack_json longtext null,
    compiler_version varchar(120) not null,
    replay_availability varchar(32) not null default 'FULL',
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_input_snapshot_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_artifact_input_snapshot_job foreign key (artifact_job_id) references artifact_job(id)
);

create index idx_artifact_input_snapshot_job_created
    on artifact_run_input_snapshot(artifact_job_id, created_at);

alter table artifact_job_run add column input_snapshot_id varchar(36) null;

insert into artifact_run_input_snapshot(
    id, workspace_id, artifact_job_id, user_requirement, inputs_json,
    source_scope_snapshot_json, upstream_refs_json, control_pack_json, compiler_version
)
select r.task_id, aj.workspace_id, r.artifact_job_id, r.user_requirement, r.inputs_json,
       r.source_scope_json, '[]', r.control_pack_json, 'artifact-input-legacy-v1'
from artifact_job_run r
join artifact_job aj on aj.id = r.artifact_job_id;

update artifact_job_run set input_snapshot_id = task_id;

alter table artifact_job_run modify column input_snapshot_id varchar(36) not null;
alter table artifact_job_run add constraint fk_artifact_job_run_input_snapshot
    foreign key (input_snapshot_id) references artifact_run_input_snapshot(id);

create index idx_artifact_job_run_input_snapshot on artifact_job_run(input_snapshot_id);
