create table conversation_topic_summary_revision_v2 (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    topic_id varchar(36) not null,
    segment_id varchar(36) not null,
    revision_no int not null,
    start_seq int not null,
    end_seq int not null,
    source_digest char(64) not null,
    status varchar(24) not null,
    summary_text longtext not null,
    content_hash char(64) null,
    compiler_version varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    ready_at timestamp null,
    constraint fk_topic_summary_v2_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_topic_summary_v2_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_topic_summary_v2_topic foreign key (topic_id) references conversation_topic_v2(id),
    constraint fk_topic_summary_v2_segment foreign key (segment_id) references conversation_topic_segment_v2(id),
    constraint ck_topic_summary_v2_status check (status in ('BUILDING', 'READY', 'STALE')),
    constraint ck_topic_summary_v2_range check (start_seq > 0 and end_seq >= start_seq),
    constraint uq_topic_summary_v2_range unique (segment_id, end_seq),
    constraint uq_topic_summary_v2_number unique (segment_id, revision_no)
);

create index idx_topic_summary_v2_ready
    on conversation_topic_summary_revision_v2(conversation_id, status, end_seq);
