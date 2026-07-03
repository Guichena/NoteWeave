create table wiki_log_entry (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    item_id varchar(36) null,
    event_type varchar(64) not null,
    message varchar(1000) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_wiki_log_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_wiki_log_item foreign key (item_id) references knowledge_item(id)
);

create index idx_wiki_log_workspace_created on wiki_log_entry(workspace_id, created_at);
