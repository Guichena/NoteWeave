create table research_execution_checkpoint (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    checkpoint_no int not null,
    snapshot_type varchar(64) not null,
    object_key varchar(500) not null,
    payload_sha256 varchar(64) not null,
    content_size bigint not null,
    active_branch_key varchar(64) null,
    final_loop_decision varchar(64) null,
    summary_json longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_checkpoint_run foreign key (research_run_id) references research_run(id)
);

create index idx_research_checkpoint_run_created on research_execution_checkpoint(research_run_id, created_at);
create unique index uq_research_checkpoint_run_no on research_execution_checkpoint(research_run_id, checkpoint_no);

create table source_evidence (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    evidence_key varchar(64) not null,
    window_id varchar(64) null,
    source_id varchar(64) null,
    source_title varchar(300) null,
    source_url varchar(1000) null,
    provider varchar(64) null,
    adapter varchar(64) null,
    search_query longtext null,
    read_focus longtext null,
    quote_text longtext null,
    claim_text longtext null,
    relation_type varchar(32) null,
    support_score decimal(5,4) null,
    conflict_score decimal(5,4) null,
    snapshot_status varchar(32) null,
    snapshot_key varchar(255) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_source_evidence_run foreign key (research_run_id) references research_run(id)
);

create index idx_source_evidence_run_created on source_evidence(research_run_id, created_at);
create unique index uq_source_evidence_run_key on source_evidence(research_run_id, evidence_key);

create table research_cell_evidence (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_cell_id varchar(36) not null,
    source_evidence_id varchar(36) not null,
    evidence_key varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_cell_evidence_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_cell_evidence_cell foreign key (research_cell_id) references research_cell(id),
    constraint fk_research_cell_evidence_source foreign key (source_evidence_id) references source_evidence(id)
);

create index idx_research_cell_evidence_run_created on research_cell_evidence(research_run_id, created_at);
create unique index uq_research_cell_evidence_cell_source on research_cell_evidence(research_cell_id, source_evidence_id);
