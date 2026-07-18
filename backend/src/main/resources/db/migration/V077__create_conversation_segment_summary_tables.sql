create table conversation_segment (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    covered_start_seq int not null,
    covered_end_seq int not null,
    lock_version int not null default 0,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_conversation_segment_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_conversation_segment_conversation foreign key (conversation_id) references conversation(id),
    constraint ck_conversation_segment_range check (covered_start_seq > 0 and covered_end_seq >= covered_start_seq),
    constraint uq_conversation_segment_range unique (conversation_id, covered_start_seq, covered_end_seq)
);

create index idx_conversation_segment_conversation_range
    on conversation_segment(conversation_id, covered_start_seq, covered_end_seq);

create table segment_summary_revision (
    id varchar(36) primary key,
    segment_id varchar(36) not null,
    revision_no int not null,
    status varchar(32) not null,
    source_segment_version int not null,
    summary_text longtext null,
    content_hash char(64) null,
    created_at timestamp not null default current_timestamp,
    ready_at timestamp null,
    constraint fk_segment_summary_revision_segment foreign key (segment_id) references conversation_segment(id),
    constraint ck_segment_summary_revision_status check (status in ('BUILDING', 'READY', 'FAILED', 'STALE')),
    constraint uq_segment_summary_revision_no unique (segment_id, revision_no)
);

create index idx_segment_summary_revision_ready
    on segment_summary_revision(segment_id, status, revision_no);
