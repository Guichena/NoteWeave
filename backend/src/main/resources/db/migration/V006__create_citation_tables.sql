create table citation (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    source_id varchar(36) not null,
    source_snapshot_id varchar(36) not null,
    source_chunk_id varchar(36) not null,
    title varchar(300) not null,
    quote_text clob not null,
    page_no int null,
    location_info varchar(300) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_citation_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_citation_source foreign key (source_id) references source(id),
    constraint fk_citation_snapshot foreign key (source_snapshot_id) references source_snapshot(id),
    constraint fk_citation_chunk foreign key (source_chunk_id) references source_chunk(id)
);

create table message_citation (
    id varchar(36) primary key,
    message_id varchar(36) not null,
    citation_id varchar(36) not null,
    sort_order int not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_message_citation_message foreign key (message_id) references conversation_message(id),
    constraint fk_message_citation_citation foreign key (citation_id) references citation(id)
);
