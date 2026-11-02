-- F5-03：只用于本对话的会话要求。候选记忆仍走既有生命周期，会话要求只影响本对话。
create table conversation_session_requirement (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    source_revision_id varchar(36) null,
    requirement_text varchar(500) not null,
    status varchar(16) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_csr_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_csr_conversation foreign key (conversation_id) references conversation(id)
);

create index idx_csr_conversation_status
    on conversation_session_requirement(workspace_id, conversation_id, status);
create unique index uq_csr_source_revision
    on conversation_session_requirement(workspace_id, source_revision_id);
