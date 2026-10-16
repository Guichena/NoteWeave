create table wiki_index_outbox (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    item_id varchar(36) not null,
    version_id varchar(36) null,
    operation varchar(16) not null,
    status varchar(16) not null,
    attempts int not null default 0,
    last_error varchar(500) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create index idx_wiki_index_outbox_pending
    on wiki_index_outbox(status, created_at);
