create table research_matrix_plan (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    planner_version varchar(64) not null,
    plan_mode varchar(32) not null,
    plan_status varchar(16) not null,
    row_count int not null,
    column_count int not null,
    cell_count int not null,
    bounded boolean not null default false,
    reason_codes_json json not null,
    plan_json json not null,
    plan_digest varchar(71) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_matrix_plan_run foreign key (research_run_id) references research_run(id),
    constraint ck_research_matrix_plan_rows check (row_count between 1 and 20),
    constraint ck_research_matrix_plan_columns check (column_count between 2 and 8),
    constraint ck_research_matrix_plan_cells check (cell_count between 2 and 80)
);

create unique index uq_research_matrix_plan_version
    on research_matrix_plan(research_run_id, planner_version);

create index idx_research_matrix_plan_status
    on research_matrix_plan(plan_status, created_at);
