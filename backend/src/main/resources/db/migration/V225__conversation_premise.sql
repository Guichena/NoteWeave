-- 第二期 F2-04：会话前提。用户陈述但未经资料核实的条件，独立于约束槽位与长期记忆。
create table conversation_premise (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    subject_key varchar(160) not null,
    premise_text varchar(1000) not null,
    source_message_id varchar(36) not null,
    source_seq int not null,
    scope varchar(32) not null,
    topic_id varchar(36) null,
    valid_from_seq int not null,
    invalid_after_seq int null,
    status varchar(32) not null,
    superseded_by varchar(36) null,
    extraction_version varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_conversation_premise_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_conversation_premise_conversation foreign key (conversation_id) references conversation(id)
);

create index idx_conversation_premise_lookup
    on conversation_premise(conversation_id, status, valid_from_seq);
create index idx_conversation_premise_source
    on conversation_premise(workspace_id, source_message_id);
