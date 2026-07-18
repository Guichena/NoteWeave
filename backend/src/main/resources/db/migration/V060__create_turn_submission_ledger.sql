create table turn_submission (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    conversation_id varchar(36) not null,
    actor_user_id varchar(80) not null,
    client_request_id varchar(120) not null,
    operation_type varchar(32) not null,
    request_payload_hash char(64) not null,
    execution_kind varchar(32) not null,
    status varchar(32) not null,
    query_message_id varchar(36) null,
    answer_message_id varchar(36) null,
    answer_run_id varchar(36) null,
    research_run_id varchar(36) null,
    receipt_json longtext null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_turn_submission_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_turn_submission_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_turn_submission_query_message foreign key (query_message_id) references conversation_message(id),
    constraint fk_turn_submission_answer_message foreign key (answer_message_id) references conversation_message(id),
    constraint fk_turn_submission_answer_run foreign key (answer_run_id) references answer_run(id),
    constraint fk_turn_submission_research_run foreign key (research_run_id) references research_run(id),
    constraint uk_turn_submission_request unique (actor_user_id, conversation_id, client_request_id)
);

create index idx_turn_submission_conversation_created
    on turn_submission(conversation_id, created_at);
create index idx_turn_submission_status_updated
    on turn_submission(status, updated_at);

