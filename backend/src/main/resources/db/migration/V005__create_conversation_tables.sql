create table conversation (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    title varchar(200) not null,
    conversation_type varchar(64) not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    last_active_at timestamp not null default current_timestamp,
    constraint fk_conversation_workspace foreign key (workspace_id) references workspace(id)
);

create index idx_conversation_workspace_active on conversation(workspace_id, last_active_at);

create table conversation_scope (
    id varchar(36) primary key,
    conversation_id varchar(36) not null,
    scope_type varchar(64) not null,
    scope_ref_id varchar(36) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_conversation_scope_conversation foreign key (conversation_id) references conversation(id)
);

create table conversation_message (
    id varchar(36) primary key,
    conversation_id varchar(36) not null,
    workspace_id varchar(36) not null,
    message_seq int not null,
    role varchar(32) not null,
    answer_mode varchar(32) null,
    content clob not null,
    assistant_request_id varchar(36) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_conversation_message_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_conversation_message_workspace foreign key (workspace_id) references workspace(id),
    constraint uk_conversation_message_seq unique (conversation_id, message_seq)
);

create index idx_conversation_message_created on conversation_message(conversation_id, created_at);

create table ui_context_snapshot (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) null,
    snapshot_json clob not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_ui_context_snapshot_workspace foreign key (workspace_id) references workspace(id)
);
