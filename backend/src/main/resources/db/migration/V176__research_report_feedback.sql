create table research_report_feedback (
    id varchar(36) not null primary key,
    research_run_id varchar(36) not null,
    requirement_id varchar(71) not null,
    cell_key varchar(255) not null,
    source_task_id varchar(36) not null,
    repair_task_id varchar(36) null,
    round_no int not null,
    missing_detail varchar(2000) not null,
    feedback_status varchar(24) not null default 'OPEN',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_report_feedback_source unique (source_task_id, requirement_id),
    constraint fk_report_feedback_run foreign key (research_run_id) references research_run(id),
    constraint fk_report_feedback_task foreign key (source_task_id) references research_agent_task(id)
);
create index idx_report_feedback_pending on research_report_feedback(research_run_id, feedback_status, cell_key);
