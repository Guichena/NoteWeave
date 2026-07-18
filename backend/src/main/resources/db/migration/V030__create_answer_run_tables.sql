create table answer_run (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    mode varchar(32) not null,
    status varchar(32) not null,
    query_message_id varchar(36) not null,
    answer_message_id varchar(36) null,
    assistant_request_id varchar(36) null,
    execution_id varchar(36) null,
    model varchar(160) null,
    retrieval_plan_version varchar(64) not null default 'legacy-v1',
    event_seq bigint not null default 0,
    stream_owner varchar(36) null,
    stream_lease_until timestamp null,
    started_at timestamp not null default current_timestamp,
    first_token_at timestamp null,
    finished_at timestamp null,
    error_code varchar(80) null,
    error_message varchar(1000) null,
    version int not null default 0,
    created_by varchar(80) not null,
    updated_by varchar(80) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_answer_run_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_answer_run_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_answer_run_query_message foreign key (query_message_id) references conversation_message(id),
    constraint fk_answer_run_answer_message foreign key (answer_message_id) references conversation_message(id),
    constraint uk_answer_run_assistant_request unique (assistant_request_id)
);

create index idx_answer_run_workspace_created on answer_run(workspace_id, created_at);
create index idx_answer_run_conversation_created on answer_run(conversation_id, created_at);
create index idx_answer_run_status_updated on answer_run(status, updated_at);

create table message_revision (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    answer_run_id varchar(36) not null,
    message_id varchar(36) not null,
    revision_no int not null,
    status varchar(32) not null,
    content longtext not null,
    model varchar(160) null,
    prompt_version varchar(64) null,
    evidence_bundle_ref varchar(500) null,
    input_tokens int null,
    output_tokens int null,
    created_by varchar(80) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_message_revision_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_message_revision_run foreign key (answer_run_id) references answer_run(id),
    constraint fk_message_revision_message foreign key (message_id) references conversation_message(id),
    constraint uk_message_revision_no unique (message_id, revision_no)
);

create index idx_message_revision_run on message_revision(answer_run_id, revision_no);

create table answer_event (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    answer_run_id varchar(36) not null,
    seq bigint not null,
    event_type varchar(64) not null,
    payload_json longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_answer_event_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_answer_event_run foreign key (answer_run_id) references answer_run(id),
    constraint uk_answer_event_seq unique (answer_run_id, seq)
);

create index idx_answer_event_run_seq on answer_event(answer_run_id, seq);
