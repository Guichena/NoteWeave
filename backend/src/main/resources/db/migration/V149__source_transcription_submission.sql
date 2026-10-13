-- 转写容量暂满时保留当前任务及原件身份，不依赖 Kafka 的有限消费重试。
create table source_transcription_submission (
    snapshot_id varchar(36) not null,
    task_id varchar(36) not null,
    workspace_id varchar(36) not null,
    source_id varchar(36) not null,
    object_key varchar(500) not null,
    sha256 varchar(64) not null,
    file_name varchar(300) not null,
    mime_type varchar(160) not null,
    status varchar(32) not null,
    attempt_count int not null default 0,
    next_retry_at timestamp not null default current_timestamp,
    lease_token varchar(36) null,
    lease_until timestamp null,
    last_error_code varchar(100) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    primary key (snapshot_id, task_id)
);
create index idx_transcription_submission_due on source_transcription_submission(status, next_retry_at);
