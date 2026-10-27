-- M-ARTIFACT-EDIT-EVENT（FB-03 / N-05 裁决）：C4 v2 专用窄范围持久事件表。
-- 裁决替换原 plan-v3「编辑事件必须写 task_outbox」的字面要求：task_outbox 行强制
-- task 外键与任务语义，编辑事件不是新任务，不能伪造 taskId 或复用已完成 originTaskId。
-- 本表同时承载请求幂等（workspace+artifactJobId+requestId 唯一）与投递状态机；
-- 复用 DurableOutboxDispatcher 同款认领/退避/确认协议（claim_token + 租约 + 有界重试）。
-- A 在编辑版本事务内 INSERT（业务事件存储）；B 的 infra 投递器认领后发 Kafka，
-- Memory 侧回执独立（B-08/FB-04），发送成功仍可能重复，消费者必须幂等。
-- request_digest 与 payload_digest 不可变：同请求不同载荷是冲突，不是覆盖。
create table artifact_edit_event (
    id varchar(64) primary key,
    workspace_id varchar(36) not null,
    artifact_job_id varchar(36) not null,
    request_id varchar(64) not null,
    schema_version int not null default 2,
    base_version_id varchar(36) null,
    new_version_id varchar(36) null,
    origin_task_id varchar(36) null,
    acting_user_id varchar(36) null,
    event_json varchar(8000) not null,
    request_digest varchar(64) not null,
    payload_digest varchar(64) not null,
    status varchar(32) not null,
    attempt_count int not null default 0,
    max_attempts int not null default 10,
    delivery_generation int not null default 1,
    claim_token varchar(64) null,
    lease_owner varchar(128) null,
    lease_until timestamp null,
    next_attempt_at timestamp null,
    sent_at timestamp null,
    last_error varchar(500) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_artifact_edit_event_request unique (workspace_id, artifact_job_id, request_id),
    constraint ck_artifact_edit_event_status check (
        status in ('READY', 'IN_FLIGHT', 'SENT', 'DEAD_LETTER')
    )
);

create index idx_artifact_edit_event_due
    on artifact_edit_event(status, next_attempt_at, lease_until, created_at);
create index idx_artifact_edit_event_sent
    on artifact_edit_event(workspace_id, status, sent_at);
