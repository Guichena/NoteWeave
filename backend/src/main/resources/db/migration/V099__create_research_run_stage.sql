create table research_run_stage (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    stage varchar(64) not null,
    stage_revision int not null,
    status varchar(32) not null,
    barrier_digest varchar(71) not null,
    expected_task_count int not null,
    settled_task_count int not null,
    blocker_count int not null default 0,
    stage_version varchar(64) not null,
    barrier_json json not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_run_stage_run foreign key (research_run_id) references research_run(id),
    constraint ck_research_run_stage_revision check (stage_revision >= 1),
    constraint ck_research_run_stage_counts check (
        expected_task_count >= 0 and settled_task_count >= 0 and blocker_count >= 0
    )
);

create unique index uq_research_run_stage_revision
    on research_run_stage(research_run_id, stage, stage_revision);

create index idx_research_run_stage_status
    on research_run_stage(status, updated_at);
