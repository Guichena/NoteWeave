create table answer_run_evidence_manifest (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    answer_run_id varchar(36) not null,
    rank_no int not null,
    evidence_id varchar(200) not null,
    evidence_kind varchar(64) not null,
    source_id varchar(36) null,
    source_snapshot_id varchar(36) null,
    passage_id varchar(100) null,
    knowledge_item_id varchar(36) null,
    knowledge_version_id varchar(36) null,
    title varchar(500) null,
    excerpt longtext not null,
    content_hash char(64) not null,
    location_info varchar(1000) null,
    fresh_at timestamp null,
    character_cost int not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_answer_evidence_manifest_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_answer_evidence_manifest_run foreign key (answer_run_id) references answer_run(id),
    constraint uq_answer_evidence_manifest_rank unique (answer_run_id, rank_no)
);

create index idx_answer_evidence_manifest_run on answer_run_evidence_manifest(answer_run_id, rank_no);
