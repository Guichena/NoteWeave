create table research_collection (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    research_run_id varchar(36) not null,
    title varchar(500) not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_collection_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_research_collection_run foreign key (research_run_id) references research_run(id),
    constraint uq_research_collection_run unique (research_run_id)
);

create table research_collection_source (
    id varchar(36) primary key,
    collection_id varchar(36) not null,
    source_key varchar(512) not null,
    source_kind varchar(32) not null,
    evidence_id varchar(200) not null,
    source_id varchar(128) null,
    source_snapshot_key varchar(512) null,
    source_url varchar(2048) null,
    source_domain varchar(255) null,
    title varchar(500) null,
    excerpt longtext not null,
    citation_count int not null default 1,
    adopted_at timestamp not null default current_timestamp,
    constraint fk_research_collection_source_collection foreign key (collection_id) references research_collection(id),
    constraint uq_research_collection_source_key unique (collection_id, source_key)
);

create table research_note (
    id varchar(36) primary key,
    collection_id varchar(36) not null,
    note_key varchar(255) not null,
    note_type varchar(32) not null,
    title varchar(500) not null,
    content longtext not null,
    evidence_refs_json longtext not null,
    note_status varchar(32) not null default 'ACTIVE',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_research_note_collection foreign key (collection_id) references research_collection(id),
    constraint uq_research_note_key unique (collection_id, note_key)
);

create index idx_research_collection_workspace_updated on research_collection(workspace_id, updated_at);
create index idx_research_collection_source_collection on research_collection_source(collection_id, adopted_at);
create index idx_research_note_collection on research_note(collection_id, note_type);
