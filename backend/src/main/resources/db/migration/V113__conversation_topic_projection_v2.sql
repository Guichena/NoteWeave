create table conversation_topic_v2 (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    anchor_message_id varchar(36) not null,
    anchor_digest char(64) not null,
    status varchar(24) not null,
    rule_version varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_topic_v2_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_topic_v2_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_topic_v2_anchor foreign key (anchor_message_id) references conversation_message(id),
    constraint ck_topic_v2_status check (status in ('ACTIVE', 'STALE')),
    constraint uq_topic_v2_anchor unique (conversation_id, anchor_message_id)
);

create index idx_topic_v2_conversation on conversation_topic_v2(conversation_id, created_at);

create table conversation_topic_segment_v2 (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    topic_id varchar(36) not null,
    start_seq int not null,
    end_seq int not null,
    decision_status varchar(24) not null,
    decision_reason varchar(80) not null,
    rule_version varchar(64) not null,
    source_digest char(64) not null,
    lock_version int not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_topic_segment_v2_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_topic_segment_v2_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_topic_segment_v2_topic foreign key (topic_id) references conversation_topic_v2(id),
    constraint ck_topic_segment_v2_range check (start_seq > 0 and end_seq >= start_seq),
    constraint ck_topic_segment_v2_status check (decision_status in ('CONFIDENT', 'UNCERTAIN', 'STALE')),
    constraint uq_topic_segment_v2_start unique (conversation_id, start_seq)
);

create index idx_topic_segment_v2_range
    on conversation_topic_segment_v2(conversation_id, start_seq, end_seq);

create table conversation_constraint_v2 (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    source_message_id varchar(36) not null,
    kind varchar(32) not null,
    scope varchar(64) not null,
    constraint_text longtext not null,
    valid_from_seq int not null,
    invalid_after_seq int null,
    status varchar(24) not null,
    superseded_by varchar(36) null,
    rule_version varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_constraint_v2_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_constraint_v2_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_constraint_v2_source foreign key (source_message_id) references conversation_message(id),
    constraint ck_constraint_v2_status check (status in ('ACTIVE', 'REVOKED', 'UNRESOLVED')),
    constraint ck_constraint_v2_range check (valid_from_seq > 0 and
        (invalid_after_seq is null or invalid_after_seq >= valid_from_seq))
);

create index idx_constraint_v2_active
    on conversation_constraint_v2(conversation_id, status, valid_from_seq);
