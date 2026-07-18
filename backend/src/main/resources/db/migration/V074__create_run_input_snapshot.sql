create table run_input_snapshot (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    execution_kind varchar(32) not null,
    answer_run_id varchar(36) null,
    research_run_id varchar(36) null,
    conversation_id varchar(36) not null,
    query_message_id varchar(36) not null,
    assistant_message_id varchar(36) not null,
    requested_turn_mode varchar(32) not null,
    history_head_message_id varchar(36) null,
    conversation_cutoff_seq int not null,
    retrieval_config_json longtext not null,
    snapshot_json longtext not null,
    compiler_version varchar(120) not null,
    prompt_version varchar(120) null,
    token_budget_json longtext not null,
    replay_availability varchar(32) not null default 'FULL',
    created_at timestamp not null default current_timestamp,
    constraint fk_run_input_snapshot_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_run_input_snapshot_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_run_input_snapshot_query foreign key (query_message_id) references conversation_message(id),
    constraint fk_run_input_snapshot_assistant foreign key (assistant_message_id) references conversation_message(id),
    constraint fk_run_input_snapshot_answer foreign key (answer_run_id) references answer_run(id),
    constraint fk_run_input_snapshot_research foreign key (research_run_id) references research_run(id),
    constraint ck_run_input_snapshot_one_run check (
        (answer_run_id is not null and research_run_id is null)
        or (answer_run_id is null and research_run_id is not null)
    )
);

create unique index uq_run_input_snapshot_answer on run_input_snapshot(answer_run_id);
create unique index uq_run_input_snapshot_research on run_input_snapshot(research_run_id);
create index idx_run_input_snapshot_workspace_created on run_input_snapshot(workspace_id, created_at);
