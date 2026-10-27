-- M-USAGE-FACT（FB-07 / RB-007 / C5）：供应商真实 usage 事实表。
-- 按 requestAttemptId 唯一：同一请求尝试只记录一次，重试形成新 attempt 身份；
-- sessionId 不能代替 attempt 唯一键。UNKNOWN 不能伪造 0：未上报时计数为 NULL、
-- reported=0。完整度单调升级 UNKNOWN → PARTIAL → COMPLETE；COMPLETE 不被降级覆盖，
-- 同等级不同计数的冲突可观察（conflict_count），不累加两次。
-- 不存 prompt/正文/密钥；无 workspace 的内部调用以 business_context 显式归属，
-- 不伪装成某用户账单。
create table actual_usage_fact (
    id varchar(36) primary key,
    request_attempt_id varchar(128) not null,
    workspace_id varchar(36) null,
    business_context varchar(64) not null,
    provider varchar(64) null,
    requested_model varchar(128) null,
    returned_model varchar(128) null,
    input_tokens bigint null,
    output_tokens bigint null,
    cached_input_tokens bigint null,
    reasoning_tokens bigint null,
    reported tinyint not null default 0,
    completeness varchar(16) not null,
    usage_schema_version varchar(32) null,
    conflict_count int not null default 0,
    occurred_at timestamp not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_actual_usage_attempt unique (request_attempt_id),
    constraint ck_actual_usage_completeness check (
        completeness in ('UNKNOWN', 'PARTIAL', 'COMPLETE')
    )
);

create index idx_actual_usage_workspace on actual_usage_fact(workspace_id, created_at);
create index idx_actual_usage_context on actual_usage_fact(business_context, created_at);
