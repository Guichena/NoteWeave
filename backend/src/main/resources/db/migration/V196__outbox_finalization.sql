-- M-OUTBOX-FINALIZE：outbox 死信的业务补偿收尾登记表（B-01）。
-- DEAD_LETTER 终止写入与 PENDING 补偿插入在同一短事务；业务收尾由独立 worker
-- 认领租约后执行，收尾结果只有 APPLIED / IGNORED_STALE 属于成功终态。
-- store_type: TASK_OUTBOX / AGENT_OUTBOX / KAFKA_DLT。
-- KAFKA_DLT 的 outbox_id 由 topic:partition:offset:consumerGroup 组成，与 producer 侧 outbox id 区分。
create table outbox_finalization (
    id varchar(36) primary key,
    workspace_id varchar(36) null,
    store_type varchar(32) not null,
    outbox_id varchar(190) not null,
    delivery_generation int not null default 1,
    business_kind varchar(64) not null,
    business_identity_json varchar(1000) not null,
    status varchar(32) not null,
    attempt_count int not null default 0,
    max_attempts int not null default 5,
    next_attempt_at timestamp null,
    lease_owner varchar(128) null,
    lease_until timestamp null,
    last_error_code varchar(128) null,
    last_error_summary varchar(500) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    completed_at timestamp null,
    constraint uq_outbox_finalization_identity unique (store_type, outbox_id, delivery_generation),
    constraint ck_outbox_finalization_status check (
        status in ('PENDING', 'RUNNING', 'APPLIED', 'IGNORED_STALE', 'RETRY_WAIT', 'TERMINAL_FAILURE')
    )
);

create index idx_outbox_finalization_due on outbox_finalization(status, next_attempt_at, created_at);
create index idx_outbox_finalization_lease on outbox_finalization(status, lease_until);
create index idx_outbox_finalization_store on outbox_finalization(store_type, status, completed_at);
