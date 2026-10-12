-- 一次注入仍记在 memory_usage_log。这里按事件记下效果反馈，便于同一回答以最后一次为准并回退 β。
-- 效用和计数的当前值仍在 memory_item。revision 记的是反馈发生时正在生效的版本。

create table memory_application_outcome (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    usage_log_id varchar(36) null,
    memory_item_id varchar(36) null,
    memory_revision_id varchar(36) null,
    target_type varchar(64) not null,
    target_id varchar(128) not null,
    signal_kind varchar(32) not null,
    outcome_type varchar(32) not null,
    outcome_score decimal(8,4) not null,
    weight decimal(8,4) not null,
    beta_weight decimal(8,4) not null default 0,
    aspect varchar(32) null,
    feedback_note varchar(500) null,
    degradation_reason varchar(80) null,
    source_ref varchar(160) null,
    edit_magnitude decimal(8,4) null,
    utility_before decimal(8,4) null,
    utility_delta decimal(8,4) not null default 0,
    application_count_before int null,
    application_delta int not null default 0,
    positive_count_before int null,
    positive_delta int not null default 0,
    negative_count_before int null,
    negative_delta int not null default 0,
    edit_count_before int null,
    edit_delta int not null default 0,
    retry_count_before int null,
    retry_delta int not null default 0,
    status_before varchar(32) null,
    review_before varchar(32) null,
    row_no bigint not null,
    superseded_at timestamp null,
    created_at timestamp not null default current_timestamp
);

create index idx_memory_application_outcome_target
    on memory_application_outcome(workspace_id, target_type, target_id, signal_kind, created_at);

create index idx_memory_application_outcome_item
    on memory_application_outcome(memory_item_id, row_no);

create index idx_memory_application_outcome_source
    on memory_application_outcome(workspace_id, source_ref);
