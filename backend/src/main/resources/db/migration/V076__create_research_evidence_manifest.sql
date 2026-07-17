create table research_evidence_manifest (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    research_run_id varchar(36) not null,
    report_content_hash char(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_evidence_manifest_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_research_evidence_manifest_run foreign key (research_run_id) references research_run(id),
    constraint uq_research_evidence_manifest_run unique (research_run_id)
);

create table research_evidence_manifest_item (
    id varchar(36) primary key,
    manifest_id varchar(36) not null,
    rank_no int not null,
    evidence_id varchar(200) not null,
    source_id varchar(128) null,
    source_snapshot_id varchar(128) null,
    passage_id varchar(128) null,
    title varchar(500) null,
    excerpt longtext not null,
    content_hash char(64) not null,
    location_info varchar(1000) null,
    constraint fk_research_evidence_manifest_item_manifest foreign key (manifest_id) references research_evidence_manifest(id),
    constraint uq_research_evidence_manifest_item_rank unique (manifest_id, rank_no)
);

create index idx_research_evidence_manifest_workspace_run
    on research_evidence_manifest(workspace_id, research_run_id);
