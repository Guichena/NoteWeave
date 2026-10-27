-- M-MEMORY-RETRY：Memory 抽取 / 隐式反馈的分阶段持久处理回执（B-05）。
-- 身份为 workspace + message + processorVersion 范围内的 workspace + message；
-- status: PENDING / RETRY_WAIT / COMPLETED / TERMINAL_FAILURE；
-- stage: EXTRACT / FEEDBACK / NONE（终态或无需反馈）。
-- 空候选结果同样是有效的抽取完成（EXTRACT_DONE 语义），不能用已有 memory_signal
-- 行数判断处理是否完成；反馈与抽取的重试上限分开计数。
create table memory_processing_receipt (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    message_id varchar(36) not null,
    task_id varchar(36) null,
    processor_version varchar(64) not null,
    status varchar(32) not null,
    stage varchar(32) not null,
    extract_attempts int not null default 0,
    feedback_attempts int not null default 0,
    delivery_generation int not null default 1,
    feedback_applicable tinyint not null default 1,
    extract_method varchar(64) null,
    candidates_json varchar(2000) null,
    next_attempt_at timestamp null,
    lease_owner varchar(128) null,
    lease_until timestamp null,
    last_error_code varchar(128) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    completed_at timestamp null,
    constraint uq_memory_processing_receipt unique (workspace_id, message_id),
    constraint ck_memory_receipt_status check (
        status in ('PENDING', 'RETRY_WAIT', 'COMPLETED', 'TERMINAL_FAILURE')
    ),
    constraint ck_memory_receipt_stage check (stage in ('EXTRACT', 'FEEDBACK', 'NONE'))
);
