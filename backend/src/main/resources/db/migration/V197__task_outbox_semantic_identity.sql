-- M-OUTBOX-IDENTITY：task_outbox 语义幂等身份与投递代次（B-02 / C2）。
-- semantic_key 由 workspace / businessKind / objectId / snapshotId / generation / stage / batchNo
-- 规范化编码（长度前缀）后 SHA-256；partitionKey 继续只负责 Kafka 分区。
-- 历史行 semantic_key / payload_digest 保留 NULL，本轮不做回填；MySQL 唯一索引对 NULL 不去重，
-- 旧行为不受影响。同 semantic_key + delivery_generation 重复入队返回既有行；
-- 载荷摘要不同判定 IDEMPOTENCY_CONFLICT。SENT / DEAD_LETTER 后的再次入队必须显式增加
-- delivery_generation（人工重驱），不自动复活旧消息。
alter table task_outbox add column semantic_key varchar(64) null;
alter table task_outbox add column delivery_generation int not null default 1;
alter table task_outbox add column payload_digest varchar(64) null;

create unique index uq_task_outbox_semantic on task_outbox(semantic_key, delivery_generation);

-- 人工重驱审计（B-03）：记录 actor、原因、旧/新代次与结果；
-- 累计 attempt、预算与旧审计不因重驱清零的约束由重驱实现保证。
create table task_outbox_redrive_audit (
    id varchar(36) primary key,
    outbox_id varchar(36) not null,
    workspace_id varchar(36) null,
    actor varchar(128) not null,
    reason varchar(500) null,
    old_delivery_generation int not null,
    new_delivery_generation int not null,
    result varchar(32) not null,
    detail varchar(500) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_task_outbox_redrive_outbox foreign key (outbox_id) references task_outbox(id)
);

create index idx_task_outbox_redrive_outbox on task_outbox_redrive_audit(outbox_id, created_at);
