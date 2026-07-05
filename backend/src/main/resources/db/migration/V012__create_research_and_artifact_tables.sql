create table artifact_job (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    task_id varchar(36) not null,
    action_key varchar(64) not null,
    style_profile_key varchar(64) null,
    context_snapshot_id varchar(128) null,
    source_scope_json clob not null,
    control_pack_json clob null,
    status varchar(32) not null,
    result_title varchar(300) null,
    latest_version_no int not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_artifact_job_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_artifact_job_task foreign key (task_id) references task(id)
);

create index idx_artifact_job_workspace_created on artifact_job(workspace_id, created_at);
create index idx_artifact_job_task on artifact_job(task_id);

create table artifact_version (
    id varchar(36) primary key,
    artifact_job_id varchar(36) not null,
    version_no int not null,
    title varchar(300) not null,
    content_markdown clob not null,
    result_payload_json clob null,
    trace_summary clob null,
    citations_json clob null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_version_job foreign key (artifact_job_id) references artifact_job(id)
);

create unique index uq_artifact_version_job_no on artifact_version(artifact_job_id, version_no);

create table research_run (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    task_id varchar(36) not null,
    question clob not null,
    profile_key varchar(64) not null,
    context_snapshot_id varchar(128) null,
    source_scope_json clob not null,
    control_pack_json clob null,
    status varchar(32) not null,
    final_report_title varchar(300) null,
    final_report_markdown clob null,
    trace_summary clob null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_run_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_research_run_task foreign key (task_id) references task(id)
);

create index idx_research_run_workspace_created on research_run(workspace_id, created_at);
create index idx_research_run_task on research_run(task_id);

create table research_trace (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    trace_type varchar(64) not null,
    trace_message varchar(1000) null,
    payload_json clob null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_trace_run foreign key (research_run_id) references research_run(id)
);

create index idx_research_trace_run_created on research_trace(research_run_id, created_at);
