create table context_v2_shadow_snapshot (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    answer_run_id varchar(36) not null,
    conversation_id varchar(36) not null,
    query_message_id varchar(36) not null,
    cutoff_seq int not null,
    status varchar(24) not null,
    projection_json longtext null,
    projection_sha256 char(64) null,
    failure_code varchar(80) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_context_shadow_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_context_shadow_answer foreign key (answer_run_id) references answer_run(id),
    constraint fk_context_shadow_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_context_shadow_query foreign key (query_message_id) references conversation_message(id),
    constraint uq_context_shadow_answer unique (answer_run_id),
    constraint ck_context_shadow_status check (status in ('READY', 'FAILED', 'REDACTED')),
    constraint ck_context_shadow_cutoff check (cutoff_seq > 0)
);

create table context_v2_shadow_ref (
    snapshot_id varchar(36) not null,
    ref_type varchar(32) not null,
    ref_id varchar(36) not null,
    primary key (snapshot_id, ref_type, ref_id),
    constraint fk_context_shadow_ref_snapshot foreign key (snapshot_id)
        references context_v2_shadow_snapshot(id),
    constraint ck_context_shadow_ref_type check
        (ref_type in ('MESSAGE', 'TOPIC_SUMMARY', 'MEMORY_REVISION'))
);

create index idx_context_shadow_ref_lookup on context_v2_shadow_ref(ref_type, ref_id);
