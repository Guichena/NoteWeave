create table knowledge_item (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    item_type varchar(32) not null,
    page_kind varchar(32) null,
    title varchar(300) not null,
    status varchar(32) not null,
    latest_version_id varchar(36) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_knowledge_item_workspace foreign key (workspace_id) references workspace(id)
);

create index idx_knowledge_item_workspace_type_updated on knowledge_item(workspace_id, item_type, updated_at);

create table knowledge_version (
    id varchar(36) primary key,
    item_id varchar(36) not null,
    version_no int not null,
    content clob not null,
    summary varchar(1000) null,
    source_message_id varchar(36) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_knowledge_version_item foreign key (item_id) references knowledge_item(id),
    constraint fk_knowledge_version_message foreign key (source_message_id) references conversation_message(id),
    constraint uk_knowledge_version_no unique (item_id, version_no)
);

create table knowledge_version_citation (
    id varchar(36) primary key,
    knowledge_version_id varchar(36) not null,
    citation_id varchar(36) not null,
    sort_order int not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_knowledge_version_citation_version foreign key (knowledge_version_id) references knowledge_version(id),
    constraint fk_knowledge_version_citation_citation foreign key (citation_id) references citation(id)
);

create table knowledge_item_link (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    source_item_id varchar(36) not null,
    target_item_id varchar(36) null,
    target_title varchar(300) not null,
    relation_type varchar(64) not null,
    relation_status varchar(32) not null,
    mention_count int not null default 1,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_knowledge_item_link_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_knowledge_item_link_source foreign key (source_item_id) references knowledge_item(id),
    constraint fk_knowledge_item_link_target foreign key (target_item_id) references knowledge_item(id)
);

create index idx_knowledge_item_link_source on knowledge_item_link(source_item_id, relation_status);
create index idx_knowledge_item_link_target on knowledge_item_link(target_item_id, relation_status);
