create table research_branch (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    branch_key varchar(64) not null,
    parent_branch_id varchar(36) null,
    branch_reason varchar(128) not null,
    branch_status varchar(32) not null,
    hypothesis_summary longtext null,
    target_evidence_ids_json longtext null,
    created_round int not null default 1,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_branch_run foreign key (research_run_id) references research_run(id)
);

create index idx_research_branch_run_created on research_branch(research_run_id, created_at);
create unique index uq_research_branch_run_key on research_branch(research_run_id, branch_key);

create table research_row (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    row_key varchar(128) not null,
    branch_id varchar(36) null,
    source_id varchar(36) null,
    source_title varchar(300) null,
    search_query longtext null,
    read_focus longtext null,
    evidence_id varchar(64) null,
    row_status varchar(32) not null,
    relation_type varchar(32) null,
    support_score decimal(5,4) null,
    conflict_score decimal(5,4) null,
    support_level varchar(32) null,
    verification_status varchar(64) null,
    verifier_note longtext null,
    repair_hint longtext null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_row_run foreign key (research_run_id) references research_run(id)
);

create index idx_research_row_run_updated on research_row(research_run_id, updated_at);
create unique index uq_research_row_run_key on research_row(research_run_id, row_key);

create table research_cell (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_row_id varchar(36) not null,
    cell_key varchar(160) not null,
    branch_id varchar(36) null,
    column_key varchar(128) not null,
    candidate_value longtext null,
    cell_status varchar(32) not null,
    confidence_score decimal(5,4) null,
    evidence_refs_json longtext null,
    last_verifier_decision varchar(64) null,
    repair_count int not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_cell_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_cell_row foreign key (research_row_id) references research_row(id)
);

create index idx_research_cell_row on research_cell(research_row_id, column_key);
create index idx_research_cell_run_updated on research_cell(research_run_id, updated_at);
create unique index uq_research_cell_run_key on research_cell(research_run_id, cell_key);

create table research_verifier_decision (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    branch_id varchar(36) null,
    decision_scope varchar(32) not null,
    decision_type varchar(64) not null,
    reason_code varchar(128) not null,
    target_id varchar(64) null,
    evidence_ids_json longtext null,
    action_text longtext null,
    decision_status varchar(32) null,
    notes_json longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_verifier_run foreign key (research_run_id) references research_run(id)
);

create index idx_research_verifier_run_created on research_verifier_decision(research_run_id, created_at);
