create table research_requirement_revision (
    requirement_id varchar(64) not null primary key,
    research_run_id varchar(36) not null,
    lineage_key varchar(64) not null,
    predecessor_requirement_id varchar(64) null,
    revision_reason varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_requirement_revision_current foreign key (requirement_id) references research_requirement(requirement_id),
    constraint fk_requirement_revision_previous foreign key (predecessor_requirement_id) references research_requirement(requirement_id)
);
create index idx_requirement_revision_lineage on research_requirement_revision(research_run_id, lineage_key, created_at);
